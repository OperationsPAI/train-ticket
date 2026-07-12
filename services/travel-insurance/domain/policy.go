package domain

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"sort"
	"strings"
	"time"
)

type PolicyStatus string

const (
	PolicyIssued                PolicyStatus = "ISSUED"
	PolicyClaimed               PolicyStatus = "CLAIMED"
	PolicyPaidOut               PolicyStatus = "PAID_OUT"
	PolicyRejected              PolicyStatus = "REJECTED"
	PolicyExpired               PolicyStatus = "EXPIRED"
	PolicyCancelled             PolicyStatus = "CANCELLED"
	PolicyPurchaseSelected      PolicyStatus = "PURCHASE_SELECTED"
	PolicyAwaitingPremium       PolicyStatus = "AWAITING_PREMIUM_CAPTURE"
	PolicyUnderwritingRequested PolicyStatus = "UNDERWRITING_REQUESTED"
	PolicyUnderwritten          PolicyStatus = "UNDERWRITTEN"
	PolicyActive                PolicyStatus = "ACTIVE"
	PolicySurrenderRequested    PolicyStatus = "SURRENDER_REQUESTED"
	PolicySurrendered           PolicyStatus = "SURRENDERED"
	PolicyUnderwritingFailed    PolicyStatus = "UNDERWRITING_FAILED"
	PolicyClosed                PolicyStatus = "CLOSED"
)

type Policy struct {
	ID                   string       `json:"policyId"`
	PolicyNumber         string       `json:"policyNumber"`
	ProductCode          ProductCode  `json:"productCode"`
	ProductVersion       string       `json:"productVersion"`
	Premium              Money        `json:"premium"`
	CoverageLimit        Money        `json:"coverageLimit"`
	JourneyOrderID       string       `json:"journeyOrderId"`
	AncillaryOrderItemID string       `json:"ancillaryOrderItemId"`
	AccountID            string       `json:"accountId"`
	TravelerRef          string       `json:"travelerRef"`
	SegmentRefs          []string     `json:"segmentRefs"`
	PaymentIntentID      string       `json:"paymentIntentId,omitempty"`
	CoverageStartAt      time.Time    `json:"coverageStartAt"`
	CoverageEndAt        time.Time    `json:"coverageEndAt"`
	Status               PolicyStatus `json:"status"`
	RefundedPremium      Money        `json:"refundedPremium,omitempty"`
	UnderwritingSeedRef  string       `json:"underwritingSeedRef"`
	AggregateVersion     int64        `json:"aggregateVersion"`
}

type PolicyIssueRequest struct {
	PolicyID             string
	Product              InsuranceProduct
	JourneyOrderID       string
	AncillaryOrderItemID string
	AccountID            string
	TravelerRef          string
	SegmentRefs          []string
	PaymentIntentID      string
	CoverageStartAt      time.Time
	CoverageEndAt        time.Time
	Now                  time.Time
}

func NewIssuedPolicy(req PolicyIssueRequest) (Policy, error) {
	if !req.Product.AvailableAt(req.Now) {
		return Policy{}, fmt.Errorf("product is not published in sales window")
	}
	policy := Policy{ID: strings.TrimSpace(req.PolicyID), ProductCode: req.Product.ProductCode, ProductVersion: req.Product.Version, Premium: req.Product.Premium, CoverageLimit: req.Product.CoverageLimit, JourneyOrderID: strings.TrimSpace(req.JourneyOrderID), AncillaryOrderItemID: strings.TrimSpace(req.AncillaryOrderItemID), AccountID: strings.TrimSpace(req.AccountID), TravelerRef: strings.TrimSpace(req.TravelerRef), SegmentRefs: normalizeRefs(req.SegmentRefs), PaymentIntentID: strings.TrimSpace(req.PaymentIntentID), CoverageStartAt: req.CoverageStartAt.UTC(), CoverageEndAt: req.CoverageEndAt.UTC(), Status: PolicyPurchaseSelected, AggregateVersion: 1}
	if policy.PaymentIntentID != "" {
		policy.Status = PolicyAwaitingPremium
	}
	if err := policy.Validate(); err != nil {
		return Policy{}, err
	}
	if policy.PaymentIntentID == "" {
		return Policy{}, fmt.Errorf("premium capture reference is required before underwriting")
	}
	policy.Status = PolicyUnderwritingRequested
	policy.UnderwritingSeedRef = policy.issueFingerprint()
	policy.PolicyNumber = deterministicPolicyNumber(policy.UnderwritingSeedRef)
	policy.Status = PolicyIssued
	return policy, policy.Validate()
}

func (p Policy) Validate() error {
	for name, value := range map[string]string{"policy id": p.ID, "product version": p.ProductVersion, "journey order id": p.JourneyOrderID, "ancillary order item id": p.AncillaryOrderItemID, "account id": p.AccountID, "traveler ref": p.TravelerRef} {
		if strings.TrimSpace(value) == "" {
			return fmt.Errorf("%s is required", name)
		}
	}
	if !SupportedProductCode(p.ProductCode) {
		return fmt.Errorf("unsupported product code: %q", p.ProductCode)
	}
	if err := p.Premium.ValidatePositive(); err != nil {
		return fmt.Errorf("invalid policy premium: %w", err)
	}
	if err := p.CoverageLimit.ValidatePositive(); err != nil {
		return fmt.Errorf("invalid policy coverage limit: %w", err)
	}
	if !p.Premium.SameCurrency(p.CoverageLimit) {
		return fmt.Errorf("policy premium and coverage limit currency must match")
	}
	if len(p.SegmentRefs) == 0 {
		return fmt.Errorf("at least one segment ref is required")
	}
	if p.CoverageStartAt.IsZero() || p.CoverageEndAt.IsZero() || !p.CoverageEndAt.After(p.CoverageStartAt) {
		return fmt.Errorf("coverage window must be present and end after start")
	}
	if p.Status == PolicyIssued || p.Status == PolicyClaimed || p.Status == PolicyPaidOut || p.Status == PolicyRejected || p.Status == PolicyExpired || p.Status == PolicyCancelled || p.Status == PolicyUnderwritten || p.Status == PolicyActive || p.Status == PolicySurrendered {
		if strings.TrimSpace(p.PolicyNumber) == "" {
			return fmt.Errorf("policy number is required after underwriting succeeds")
		}
	}
	return nil
}

func (p Policy) CanClaim(now time.Time) bool {
	now = now.UTC()
	return p.Status == PolicyIssued && !now.Before(p.CoverageStartAt) && !now.After(p.CoverageEndAt)
}

func (p *Policy) MarkClaimed(now time.Time) error {
	if !p.CanClaim(now) {
		return fmt.Errorf("policy is not issued in coverage window")
	}
	p.Status = PolicyClaimed
	p.AggregateVersion++
	return p.Validate()
}

func (p *Policy) MarkPaidOut() error {
	if p.Status != PolicyClaimed {
		return fmt.Errorf("policy must be claimed before payout")
	}
	p.Status = PolicyPaidOut
	p.AggregateVersion++
	return p.Validate()
}

func (p *Policy) MarkRejected() error {
	if p.Status != PolicyClaimed {
		return fmt.Errorf("policy must be claimed before rejection")
	}
	p.Status = PolicyRejected
	p.AggregateVersion++
	return p.Validate()
}

func (p *Policy) CancelForRefund() (Money, error) {
	if p.Status != PolicyIssued {
		return Money{}, fmt.Errorf("policy cannot be cancelled from %s", p.Status)
	}
	p.Status = PolicyCancelled
	p.RefundedPremium = p.Premium
	p.AggregateVersion++
	return p.RefundedPremium, p.Validate()
}

func (p *Policy) AdjustCoverage(start, end time.Time) error {
	if p.Status != PolicyIssued {
		return fmt.Errorf("policy coverage cannot be adjusted from %s", p.Status)
	}
	p.CoverageStartAt = start.UTC()
	p.CoverageEndAt = end.UTC()
	p.AggregateVersion++
	return p.Validate()
}

func (p *Policy) Expire(now time.Time) error {
	if p.Status != PolicyIssued {
		return fmt.Errorf("policy cannot expire from %s", p.Status)
	}
	if now.UTC().Before(p.CoverageEndAt) {
		return fmt.Errorf("policy coverage has not ended")
	}
	p.Status = PolicyExpired
	p.AggregateVersion++
	return p.Validate()
}

func (p Policy) SegmentScopeHash() string {
	refs := normalizeRefs(p.SegmentRefs)
	return hashText(strings.Join(refs, "|"))
}

func (p Policy) issueFingerprint() string {
	return hashText(strings.Join([]string{p.ID, string(p.ProductCode), p.ProductVersion, p.Premium.Currency, fmt.Sprint(p.Premium.MinorUnits), p.JourneyOrderID, p.AncillaryOrderItemID, p.TravelerRef, p.SegmentScopeHash()}, "|"))
}

func deterministicPolicyNumber(seed string) string {
	prefix := seed
	if len(prefix) > 16 {
		prefix = prefix[:16]
	}
	return "SIM-TI-" + strings.ToUpper(prefix)
}

func normalizeRefs(refs []string) []string {
	out := make([]string, 0, len(refs))
	seen := map[string]struct{}{}
	for _, ref := range refs {
		ref = strings.TrimSpace(ref)
		if ref == "" {
			continue
		}
		if _, ok := seen[ref]; ok {
			continue
		}
		seen[ref] = struct{}{}
		out = append(out, ref)
	}
	sort.Strings(out)
	return out
}

func hashText(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}
