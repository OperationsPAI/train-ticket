package domain

import (
	"fmt"
	"strings"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
)

type Claim struct {
	ID             string      `json:"claimId"`
	PolicyID       string      `json:"policyId"`
	ClaimType      ClaimType   `json:"claimType"`
	TriggerFactKey string      `json:"triggerFactKey"`
	SupportCaseID  string      `json:"supportCaseId,omitempty"`
	EvidenceRefs   []string    `json:"evidenceRefs"`
	ClaimedAmount  Money       `json:"claimedAmount"`
	ApprovedAmount Money       `json:"approvedAmount,omitempty"`
	PayoutAdviceID string      `json:"payoutAdviceId,omitempty"`
	Status         ClaimStatus `json:"status"`
	CreatedAt      time.Time   `json:"createdAt"`
	UpdatedAt      time.Time   `json:"updatedAt"`
	Version        int64       `json:"aggregateVersion"`
}

type PayoutAdvice struct {
	ID              string       `json:"payoutAdviceId"`
	ClaimID         string       `json:"claimId"`
	PolicyID        string       `json:"policyId"`
	PayoutTarget    PayoutTarget `json:"payoutTarget"`
	Amount          Money        `json:"amount"`
	ReasonCode      string       `json:"reasonCode"`
	IdempotencyKey  string       `json:"idempotencyKey"`
	DecisionVersion int          `json:"decisionVersion"`
	Status          string       `json:"status"`
	CreatedAt       time.Time    `json:"createdAt"`
}

type ClaimSpec struct {
	Policy         Policy
	ClaimType      ClaimType
	TriggerFactKey string
	SupportCaseID  string
	EvidenceRefs   []string
	ClaimedAmount  Money
}

func NewClaim(spec ClaimSpec, now time.Time) (Claim, error) {
	if strings.TrimSpace(spec.Policy.ID) == "" || strings.TrimSpace(spec.TriggerFactKey) == "" {
		return Claim{}, ErrInvalidArgument
	}
	if err := validateClaimType(spec); err != nil {
		return Claim{}, err
	}
	if err := spec.ClaimedAmount.ValidatePositive(); err != nil {
		return Claim{}, err
	}
	if !spec.ClaimedAmount.SameCurrency(spec.Policy.CoverageLimit) || spec.ClaimedAmount.MinorUnits > spec.Policy.CoverageLimit.MinorUnits {
		return Claim{}, fmt.Errorf("%w: claimed amount exceeds coverage limit", ErrRuleViolation)
	}
	if now.IsZero() {
		now = time.Now().UTC()
	}
	status := ClaimOpened
	if spec.ClaimType == ClaimDelayAuto {
		status = ClaimAutoVerifying
	} else {
		status = ClaimManualReview
	}
	return Claim{ID: ids.NewPrefixed("clm"), PolicyID: spec.Policy.ID, ClaimType: spec.ClaimType, TriggerFactKey: normalize(spec.TriggerFactKey), SupportCaseID: normalize(spec.SupportCaseID), EvidenceRefs: normalizeSegments(spec.EvidenceRefs), ClaimedAmount: spec.ClaimedAmount, Status: status, CreatedAt: now.UTC(), UpdatedAt: now.UTC(), Version: 1}, nil
}

func validateClaimType(spec ClaimSpec) error {
	switch spec.ClaimType {
	case ClaimDelayAuto:
		if len(spec.EvidenceRefs) == 0 {
			return fmt.Errorf("%w: automatic delay claim requires verified event evidence", ErrRuleViolation)
		}
	case ClaimAccidentManual, ClaimServiceFailureManual:
		if strings.TrimSpace(spec.SupportCaseID) == "" || len(spec.EvidenceRefs) == 0 {
			return fmt.Errorf("%w: manual claim requires support case and evidence references", ErrRuleViolation)
		}
	default:
		return ErrInvalidArgument
	}
	return nil
}

func (c *Claim) Approve(amount Money, now time.Time) error {
	if c.Status == ClaimRejected || c.Status == ClaimFailed || c.Status == ClaimClosed || c.Status == ClaimPayoutRecommended {
		return fmt.Errorf("%w: claim is terminal or already advised", ErrInvalidTransition)
	}
	if err := amount.ValidatePositive(); err != nil {
		return err
	}
	if !amount.SameCurrency(c.ClaimedAmount) || amount.MinorUnits > c.ClaimedAmount.MinorUnits {
		return fmt.Errorf("%w: approved amount exceeds claimed amount", ErrRuleViolation)
	}
	c.ApprovedAmount = amount
	c.Status = ClaimApproved
	c.touch(now)
	return nil
}

func (c *Claim) RecommendPayout(adviceID string, now time.Time) error {
	if c.Status != ClaimApproved {
		return fmt.Errorf("%w: claim is not approved", ErrInvalidTransition)
	}
	if strings.TrimSpace(adviceID) == "" {
		return ErrInvalidArgument
	}
	c.PayoutAdviceID = strings.TrimSpace(adviceID)
	c.Status = ClaimPayoutRecommended
	c.touch(now)
	return nil
}

func NewPayoutAdvice(claim Claim, target PayoutTarget, reasonCode string, now time.Time) (PayoutAdvice, error) {
	if claim.Status != ClaimApproved {
		return PayoutAdvice{}, fmt.Errorf("%w: claim must be approved", ErrInvalidTransition)
	}
	if err := claim.ApprovedAmount.ValidatePositive(); err != nil {
		return PayoutAdvice{}, err
	}
	switch target {
	case PayoutWalletBenefit, PayoutPaymentRefund, PayoutManualReview:
	default:
		return PayoutAdvice{}, ErrInvalidArgument
	}
	if strings.TrimSpace(reasonCode) == "" {
		return PayoutAdvice{}, ErrInvalidArgument
	}
	if now.IsZero() {
		now = time.Now().UTC()
	}
	id := ids.NewPrefixed("pad")
	return PayoutAdvice{ID: id, ClaimID: claim.ID, PolicyID: claim.PolicyID, PayoutTarget: target, Amount: claim.ApprovedAmount, ReasonCode: normalize(reasonCode), IdempotencyKey: ids.NewUUIDv7(), DecisionVersion: 1, Status: "RECOMMENDED", CreatedAt: now.UTC()}, nil
}

func (c *Claim) touch(now time.Time) {
	if now.IsZero() {
		now = time.Now().UTC()
	}
	c.UpdatedAt = now.UTC()
	c.Version++
}
