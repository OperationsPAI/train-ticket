package domain

import "time"

const (
	EventPolicyIssued = "PolicyIssued"
	EventClaimFiled   = "ClaimFiled"
	EventClaimSettled = "ClaimSettled"
)

type PolicyIssuedPayload struct {
	PolicyID             string      `json:"policyId"`
	PolicyNumber         string      `json:"policyNumber"`
	ProductCode          ProductCode `json:"productCode"`
	ProductVersion       int         `json:"productVersion"`
	JourneyOrderID       string      `json:"journeyOrderId"`
	AncillaryOrderItemID string      `json:"ancillaryOrderItemId,omitempty"`
	AccountID            string      `json:"accountId"`
	TravelerRef          string      `json:"travelerRef"`
	SegmentRefs          []string    `json:"segmentRefs"`
	Premium              Money       `json:"premium"`
	CoverageLimit        Money       `json:"coverageLimit"`
	CoverageStartAt      time.Time   `json:"coverageStartAt"`
	CoverageEndAt        time.Time   `json:"coverageEndAt"`
	IssuedAt             time.Time   `json:"issuedAt"`
}

type ClaimFiledPayload struct {
	ClaimID        string    `json:"claimId"`
	PolicyID       string    `json:"policyId"`
	ClaimType      ClaimType `json:"claimType"`
	TriggerFactKey string    `json:"triggerFactKey"`
	ClaimedAmount  Money     `json:"claimedAmount"`
	EvidenceRefs   []string  `json:"evidenceRefs"`
	FiledAt        time.Time `json:"filedAt"`
}

type ClaimSettledPayload struct {
	ClaimID        string       `json:"claimId"`
	PolicyID       string       `json:"policyId"`
	PayoutAdviceID string       `json:"payoutAdviceId"`
	PayoutTarget   PayoutTarget `json:"payoutTarget"`
	Amount         Money        `json:"amount"`
	ReasonCode     string       `json:"reasonCode"`
	SettledAt      time.Time    `json:"settledAt"`
}

type InsuranceOffer struct {
	OfferID        string      `json:"offerId"`
	JourneyOrderID string      `json:"journeyOrderId"`
	AccountID      string      `json:"accountId"`
	ProductCode    ProductCode `json:"productCode"`
	ProductVersion int         `json:"productVersion"`
	Premium        Money       `json:"premium"`
	CoverageLimit  Money       `json:"coverageLimit"`
	OfferedAt      time.Time   `json:"offeredAt"`
	ExpiresAt      time.Time   `json:"expiresAt"`
}
