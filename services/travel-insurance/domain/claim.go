package domain

import (
	"fmt"
	"strings"
	"time"
)

type ClaimType string

const (
	ClaimDelayAuto            ClaimType = "DELAY_AUTO"
	ClaimCancellation         ClaimType = "CANCELLATION"
	ClaimAccident             ClaimType = "ACCIDENT"
	ClaimAccidentManual       ClaimType = "ACCIDENT_MANUAL"
	ClaimBaggage              ClaimType = "BAGGAGE"
	ClaimServiceFailureManual ClaimType = "SERVICE_FAILURE_MANUAL"
)

type ClaimStatus string

const (
	ClaimPending            ClaimStatus = "PENDING"
	ClaimApproved           ClaimStatus = "APPROVED"
	ClaimRejected           ClaimStatus = "REJECTED"
	ClaimPaidOut            ClaimStatus = "PAID_OUT"
	ClaimOpened             ClaimStatus = "OPENED"
	ClaimEvidenceCollecting ClaimStatus = "EVIDENCE_COLLECTING"
	ClaimAutoVerifying      ClaimStatus = "AUTO_VERIFYING"
	ClaimManualReview       ClaimStatus = "MANUAL_REVIEW"
	ClaimPayoutRecommended  ClaimStatus = "PAYOUT_RECOMMENDED"
	ClaimFailed             ClaimStatus = "FAILED"
	ClaimClosed             ClaimStatus = "CLOSED"
)

type PayoutTarget string

const (
	PayoutWalletBenefit PayoutTarget = "WALLET_BENEFIT"
	PayoutPaymentRefund PayoutTarget = "PAYMENT_REFUND"
	PayoutManualReview  PayoutTarget = "MANUAL_REVIEW"
)

type DelayFact struct {
	SegmentRef          string    `json:"segmentRef,omitempty"`
	ServiceDate         string    `json:"serviceDate,omitempty"`
	ScheduledServiceRef string    `json:"scheduledServiceRef,omitempty"`
	SourceEventType     string    `json:"sourceEventType,omitempty"`
	SourceEventID       string    `json:"sourceEventId,omitempty"`
	DelayMinutes        int       `json:"delayMinutes,omitempty"`
	OccurredAt          time.Time `json:"occurredAt,omitempty"`
}

type Claim struct {
	ID               string      `json:"claimId"`
	PolicyID         string      `json:"policyId"`
	ClaimType        ClaimType   `json:"claimType"`
	TriggerFactKey   string      `json:"triggerFactKey"`
	Description      string      `json:"description,omitempty"`
	DelayFact        *DelayFact  `json:"delayFact,omitempty"`
	SupportCaseID    string      `json:"supportCaseId,omitempty"`
	EvidenceRefs     []string    `json:"evidenceRefs"`
	ClaimedAmount    Money       `json:"claimedAmount"`
	ApprovedAmount   Money       `json:"approvedAmount,omitempty"`
	PayoutAdviceID   string      `json:"payoutAdviceId,omitempty"`
	Status           ClaimStatus `json:"status"`
	ReasonCode       string      `json:"reasonCode,omitempty"`
	AggregateVersion int64       `json:"aggregateVersion"`
}

type PayoutAdvice struct {
	ID              string       `json:"payoutAdviceId"`
	ClaimID         string       `json:"claimId"`
	PolicyID        string       `json:"policyId"`
	PayoutTarget    PayoutTarget `json:"payoutTarget"`
	Amount          Money        `json:"amount"`
	ReasonCode      string       `json:"reasonCode"`
	IdempotencyKey  string       `json:"idempotencyKey"`
	DecisionVersion string       `json:"decisionVersion"`
	Status          string       `json:"status"`
}

func OpenClaim(id string, policy Policy, claimType ClaimType, triggerFactKey string, delayFact *DelayFact, supportCaseID string, evidenceRefs []string, claimedAmount Money, now time.Time) (Claim, error) {
	claim := Claim{ID: strings.TrimSpace(id), PolicyID: policy.ID, ClaimType: claimType, TriggerFactKey: strings.TrimSpace(triggerFactKey), DelayFact: delayFact, SupportCaseID: strings.TrimSpace(supportCaseID), EvidenceRefs: normalizeRefs(evidenceRefs), ClaimedAmount: claimedAmount, Status: ClaimOpened, AggregateVersion: 1}
	if err := claim.ValidateAgainstPolicy(policy, now); err != nil {
		return Claim{}, err
	}
	if claimType == ClaimDelayAuto {
		claim.Status = ClaimApproved
		claim.ApprovedAmount = capAmount(claimedAmount, policy.CoverageLimit)
	} else {
		claim.Status = ClaimPending
	}
	return claim, claim.ValidateAgainstPolicy(policy, now)
}

func (c Claim) ValidateAgainstPolicy(policy Policy, now time.Time) error {
	if strings.TrimSpace(c.ID) == "" || strings.TrimSpace(c.PolicyID) == "" {
		return fmt.Errorf("claim id and policy id are required")
	}
	if c.PolicyID != policy.ID {
		return fmt.Errorf("claim policy does not match policy aggregate")
	}
	if !policy.CanClaim(now) && policy.Status != PolicyClaimed {
		return fmt.Errorf("policy is not issued in coverage window")
	}
	if !SupportedClaimType(c.ClaimType) {
		return fmt.Errorf("unsupported claim type: %q", c.ClaimType)
	}
	if !claimTypeMatchesProduct(c.ClaimType, policy.ProductCode) {
		return fmt.Errorf("claim type %s does not match product %s", c.ClaimType, policy.ProductCode)
	}
	if strings.TrimSpace(c.TriggerFactKey) == "" {
		return fmt.Errorf("trigger fact key is required")
	}
	if err := c.ClaimedAmount.ValidatePositive(); err != nil {
		return fmt.Errorf("invalid claimed amount: %w", err)
	}
	if !c.ClaimedAmount.SameCurrency(policy.CoverageLimit) {
		return fmt.Errorf("claim amount currency must match policy")
	}
	if c.ClaimedAmount.MinorUnits > policy.CoverageLimit.MinorUnits {
		return fmt.Errorf("claimed amount exceeds policy coverage limit")
	}
	if c.ClaimType == ClaimDelayAuto {
		if c.DelayFact == nil || !validDelaySource(c.DelayFact.SourceEventType) || strings.TrimSpace(c.DelayFact.SourceEventID) == "" || c.DelayFact.DelayMinutes <= 60 {
			return fmt.Errorf("auto delay claim requires verified TrainDelayed fact over 60 minutes")
		}
	} else if len(c.EvidenceRefs) == 0 {
		return fmt.Errorf("manual claims require evidence references")
	}
	if c.ApprovedAmount.MinorUnits > 0 {
		if err := c.ApprovedAmount.ValidatePositive(); err != nil {
			return fmt.Errorf("invalid approved amount: %w", err)
		}
		if !c.ApprovedAmount.SameCurrency(policy.CoverageLimit) || c.ApprovedAmount.MinorUnits > policy.CoverageLimit.MinorUnits {
			return fmt.Errorf("approved amount must be within policy coverage limit")
		}
	}
	return nil
}

func (c *Claim) Approve(amount Money) error {
	if c.Status != ClaimPending && c.Status != ClaimApproved {
		return fmt.Errorf("claim cannot be approved from %s", c.Status)
	}
	if err := amount.ValidatePositive(); err != nil {
		return fmt.Errorf("invalid approved amount: %w", err)
	}
	if amount.MinorUnits > c.ClaimedAmount.MinorUnits || !amount.SameCurrency(c.ClaimedAmount) {
		return fmt.Errorf("approved amount must be within claimed amount")
	}
	c.ApprovedAmount = amount
	c.Status = ClaimApproved
	c.AggregateVersion++
	return nil
}

func (c *Claim) Reject(reason string) error {
	if c.Status != ClaimPending {
		return fmt.Errorf("claim cannot be rejected from %s", c.Status)
	}
	c.ReasonCode = strings.TrimSpace(reason)
	if c.ReasonCode == "" {
		c.ReasonCode = "CLAIM_REJECTED"
	}
	c.Status = ClaimRejected
	c.AggregateVersion++
	return nil
}

func (c *Claim) Settle(adviceID string, target PayoutTarget, reasonCode string) (PayoutAdvice, error) {
	if c.Status != ClaimApproved {
		return PayoutAdvice{}, fmt.Errorf("claim must be approved before payout advice")
	}
	if target != PayoutWalletBenefit && target != PayoutPaymentRefund && target != PayoutManualReview {
		return PayoutAdvice{}, fmt.Errorf("unsupported payout target: %q", target)
	}
	if strings.TrimSpace(reasonCode) == "" {
		reasonCode = "INSURANCE_CLAIM_APPROVED"
	}
	advice := PayoutAdvice{ID: strings.TrimSpace(adviceID), ClaimID: c.ID, PolicyID: c.PolicyID, PayoutTarget: target, Amount: c.ApprovedAmount, ReasonCode: reasonCode, IdempotencyKey: hashText(strings.Join([]string{c.ID, c.PolicyID, c.ApprovedAmount.Currency, fmt.Sprint(c.ApprovedAmount.MinorUnits), string(target), reasonCode, "v1"}, "|")), DecisionVersion: "v1", Status: "COMPLETED"}
	if strings.TrimSpace(advice.ID) == "" {
		return PayoutAdvice{}, fmt.Errorf("payout advice id is required")
	}
	c.PayoutAdviceID = advice.ID
	c.Status = ClaimPaidOut
	c.AggregateVersion++
	return advice, nil
}

func SupportedClaimType(claimType ClaimType) bool {
	switch claimType {
	case ClaimDelayAuto, ClaimCancellation, ClaimAccident, ClaimAccidentManual, ClaimBaggage, ClaimServiceFailureManual:
		return true
	default:
		return false
	}
}

func claimTypeMatchesProduct(claimType ClaimType, code ProductCode) bool {
	switch claimType {
	case ClaimDelayAuto:
		return code == ProductDelayInsurance
	case ClaimCancellation, ClaimServiceFailureManual:
		return code == ProductCancellationInsurance
	case ClaimAccident, ClaimAccidentManual:
		return code == ProductAccidentInsurance
	case ClaimBaggage:
		return code == ProductBaggageInsurance
	default:
		return false
	}
}

func capAmount(amount, limit Money) Money {
	if amount.MinorUnits > limit.MinorUnits {
		return limit
	}
	return amount
}

func validDelaySource(eventType string) bool {
	switch strings.TrimSpace(eventType) {
	case "TrainDelayed", "SegmentDelayed", "SegmentArrived", "SegmentCancelled", "DisruptionReported", "IncidentOpened", "RecoveryCaseOpened", "RecoveryOptionsGenerated", "RecoveryOptionSelected", "RecoveryCompleted", "RecoveryFailed":
		return true
	default:
		return false
	}
}
