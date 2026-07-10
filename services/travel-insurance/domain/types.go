package domain

import (
	"errors"
	"strings"
	"time"
)

var (
	ErrInvalidArgument      = errors.New("invalid argument")
	ErrNotFound             = errors.New("not found")
	ErrConflict             = errors.New("conflict")
	ErrInvalidTransition    = errors.New("invalid state transition")
	ErrRuleViolation        = errors.New("domain rule violation")
	ErrDuplicateActiveClaim = errors.New("duplicate active claim")
)

type Money struct {
	Currency   string `json:"currency"`
	MinorUnits int64  `json:"minorUnits"`
}

func (m Money) ValidatePositive() error {
	if strings.TrimSpace(m.Currency) == "" || m.MinorUnits <= 0 {
		return ErrInvalidArgument
	}
	return nil
}

func (m Money) SameCurrency(other Money) bool {
	return strings.EqualFold(strings.TrimSpace(m.Currency), strings.TrimSpace(other.Currency))
}

type ProductCode string

const (
	ProductDelayInsurance    ProductCode = "DELAY_INSURANCE"
	ProductAccidentInsurance ProductCode = "ACCIDENT_INSURANCE"
)

type ProductStatus string

const (
	ProductDraft      ProductStatus = "DRAFT"
	ProductPublished  ProductStatus = "PUBLISHED"
	ProductSuspended  ProductStatus = "SUSPENDED"
	ProductSuperseded ProductStatus = "SUPERSEDED"
	ProductRetired    ProductStatus = "RETIRED"
)

type PolicyStatus string

const (
	PolicyPurchaseSelected       PolicyStatus = "PURCHASE_SELECTED"
	PolicyAwaitingPremiumCapture PolicyStatus = "AWAITING_PREMIUM_CAPTURE"
	PolicyUnderwritingRequested  PolicyStatus = "UNDERWRITING_REQUESTED"
	PolicyUnderwritten           PolicyStatus = "UNDERWRITTEN"
	PolicyActive                 PolicyStatus = "ACTIVE"
	PolicySurrenderRequested     PolicyStatus = "SURRENDER_REQUESTED"
	PolicySurrendered            PolicyStatus = "SURRENDERED"
	PolicyUnderwritingFailed     PolicyStatus = "UNDERWRITING_FAILED"
	PolicyExpired                PolicyStatus = "EXPIRED"
	PolicyClosed                 PolicyStatus = "CLOSED"
)

type ClaimType string

const (
	ClaimDelayAuto            ClaimType = "DELAY_AUTO"
	ClaimAccidentManual       ClaimType = "ACCIDENT_MANUAL"
	ClaimServiceFailureManual ClaimType = "SERVICE_FAILURE_MANUAL"
)

type ClaimStatus string

const (
	ClaimOpened             ClaimStatus = "OPENED"
	ClaimEvidenceCollecting ClaimStatus = "EVIDENCE_COLLECTING"
	ClaimAutoVerifying      ClaimStatus = "AUTO_VERIFYING"
	ClaimManualReview       ClaimStatus = "MANUAL_REVIEW"
	ClaimApproved           ClaimStatus = "APPROVED"
	ClaimPayoutRecommended  ClaimStatus = "PAYOUT_RECOMMENDED"
	ClaimRejected           ClaimStatus = "REJECTED"
	ClaimFailed             ClaimStatus = "FAILED"
	ClaimClosed             ClaimStatus = "CLOSED"
)

type PayoutTarget string

const (
	PayoutWalletBenefit PayoutTarget = "WALLET_BENEFIT"
	PayoutPaymentRefund PayoutTarget = "PAYMENT_REFUND"
	PayoutManualReview  PayoutTarget = "MANUAL_REVIEW"
)

type Window struct {
	StartAt time.Time `json:"startAt"`
	EndAt   time.Time `json:"endAt"`
}

func (w Window) Validate() error {
	if w.StartAt.IsZero() || w.EndAt.IsZero() || !w.EndAt.After(w.StartAt) {
		return ErrInvalidArgument
	}
	return nil
}

func normalize(value string) string { return strings.TrimSpace(value) }
