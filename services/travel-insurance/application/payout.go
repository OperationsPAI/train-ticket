package application

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

type WalletPayoutExecutor struct {
	Client          *http.Client
	BaseURLTemplate string
}

func (e WalletPayoutExecutor) Disburse(ctx context.Context, policy domain.Policy, claim domain.Claim, advice domain.PayoutAdvice, correlationID string) (PayoutReceipt, error) {
	if advice.PayoutTarget != domain.PayoutWalletBenefit {
		return PayoutReceipt{}, fmt.Errorf("%w: wallet executor cannot execute %s", ErrDomainRule, advice.PayoutTarget)
	}
	client := e.Client
	if client == nil {
		client = &http.Client{Timeout: 5 * time.Second}
	}
	template := firstNonBlank(e.BaseURLTemplate, "http://%s:8080")
	base := template
	if strings.Contains(template, "%s") {
		base = fmt.Sprintf(template, "wallet-promotion")
	}
	base = strings.TrimRight(base, "/")
	requestBody := map[string]any{
		"accountId":      policy.AccountID,
		"benefitType":    "BALANCE",
		"balanceType":    "STORED_VALUE",
		"amount":         advice.Amount,
		"issuanceSource": "MANUAL_OPS",
		"issuanceRef":    "insurance-claim:" + claim.ID + ":" + advice.DecisionVersion,
		"applicableScope": map[string]any{
			"scopeType": "ANY_TRIP",
			"currency":  advice.Amount.Currency,
		},
		"redemptionRule": map[string]any{
			"singleUse":           false,
			"requiresReservation": false,
		},
		"revocationRule": map[string]any{},
		"validFrom":      advice.RequestedAt.UTC(),
		"validUntil":     advice.RequestedAt.UTC().AddDate(10, 0, 0),
		"businessReason": map[string]any{
			"reasonType":    "MANUAL_OPS",
			"reasonCode":    advice.ReasonCode,
			"referenceType": "INSURANCE_CLAIM",
			"referenceId":   claim.ID,
		},
	}
	body, err := json.Marshal(requestBody)
	if err != nil {
		return PayoutReceipt{}, fmt.Errorf("%w: encode wallet payout: %v", ErrPayoutUnavailable, err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, base+"/api/v1/benefits", bytes.NewReader(body))
	if err != nil {
		return PayoutReceipt{}, fmt.Errorf("%w: create wallet payout request: %v", ErrPayoutUnavailable, err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Idempotency-Key", advice.IdempotencyKey)
	if correlationID != "" {
		req.Header.Set("X-Correlation-Id", correlationID)
	}
	response, err := client.Do(req)
	if err != nil {
		return PayoutReceipt{}, fmt.Errorf("%w: wallet payout request failed: %v", ErrPayoutUnavailable, err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		payload, _ := io.ReadAll(io.LimitReader(response.Body, 4096))
		return PayoutReceipt{}, fmt.Errorf("%w: wallet payout returned %d: %s", ErrPayoutUnavailable, response.StatusCode, strings.TrimSpace(string(payload)))
	}
	var benefit walletBenefit
	if err := json.NewDecoder(response.Body).Decode(&benefit); err != nil {
		return PayoutReceipt{}, fmt.Errorf("%w: decode wallet payout: %v", ErrPayoutUnavailable, err)
	}
	if err := validateWalletBenefit(benefit, policy, claim, advice); err != nil {
		return PayoutReceipt{}, err
	}
	verified, err := e.readWalletBenefit(ctx, client, base, benefit.BenefitID, correlationID)
	if err != nil {
		return PayoutReceipt{}, err
	}
	if err := validateWalletBenefit(verified, policy, claim, advice); err != nil {
		return PayoutReceipt{}, err
	}
	return PayoutReceipt{ExecutionRef: verified.BenefitID, AccountID: verified.AccountID, Amount: verified.IssuedAmount, Target: domain.PayoutWalletBenefit}, nil
}

type walletBenefit struct {
	BenefitID      string       `json:"benefitId"`
	AccountID      string       `json:"accountId"`
	BenefitType    string       `json:"benefitType"`
	BalanceType    string       `json:"balanceType"`
	Status         string       `json:"status"`
	IssuedAmount   domain.Money `json:"issuedAmount"`
	IssuanceSource string       `json:"issuanceSource"`
	BusinessReason struct {
		ReasonCode    string `json:"reasonCode"`
		ReferenceType string `json:"referenceType"`
		ReferenceID   string `json:"referenceId"`
	} `json:"businessReason"`
}

func (e WalletPayoutExecutor) readWalletBenefit(ctx context.Context, client *http.Client, base, benefitID, correlationID string) (walletBenefit, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/api/v1/benefits/"+benefitID, nil)
	if err != nil {
		return walletBenefit{}, fmt.Errorf("%w: create wallet verification request: %v", ErrPayoutUnavailable, err)
	}
	if correlationID != "" {
		req.Header.Set("X-Correlation-Id", correlationID)
	}
	response, err := client.Do(req)
	if err != nil {
		return walletBenefit{}, fmt.Errorf("%w: wallet payout verification failed: %v", ErrPayoutUnavailable, err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return walletBenefit{}, fmt.Errorf("%w: wallet payout verification returned %d", ErrPayoutUnavailable, response.StatusCode)
	}
	var benefit walletBenefit
	if err := json.NewDecoder(response.Body).Decode(&benefit); err != nil {
		return walletBenefit{}, fmt.Errorf("%w: decode wallet payout verification: %v", ErrPayoutUnavailable, err)
	}
	return benefit, nil
}

func validateWalletBenefit(benefit walletBenefit, policy domain.Policy, claim domain.Claim, advice domain.PayoutAdvice) error {
	if strings.TrimSpace(benefit.BenefitID) == "" || benefit.AccountID != policy.AccountID || benefit.BenefitType != "BALANCE" || benefit.BalanceType != "STORED_VALUE" || benefit.Status == "REVOKED" || benefit.Status == "EXPIRED" || benefit.IssuanceSource != "MANUAL_OPS" || benefit.IssuedAmount != advice.Amount || benefit.BusinessReason.ReasonCode != advice.ReasonCode || benefit.BusinessReason.ReferenceType != "INSURANCE_CLAIM" || benefit.BusinessReason.ReferenceID != claim.ID {
		return fmt.Errorf("%w: wallet payout evidence differs from approved insurance claim", ErrPayoutUnavailable)
	}
	return nil
}

func deterministicPayoutKey(material string) string {
	bytes := sha256.Sum256([]byte(material))
	bytes[6] = (bytes[6] & 0x0f) | 0x70
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	raw := bytes[:16]
	return hex.EncodeToString(raw[0:4]) + "-" + hex.EncodeToString(raw[4:6]) + "-" + hex.EncodeToString(raw[6:8]) + "-" + hex.EncodeToString(raw[8:10]) + "-" + hex.EncodeToString(raw[10:16])
}
