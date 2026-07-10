package domain

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
)

type Policy struct {
	ID                   string       `json:"policyId"`
	PolicyNumber         string       `json:"policyNumber,omitempty"`
	ProductCode          ProductCode  `json:"productCode"`
	ProductVersion       int          `json:"productVersion"`
	Premium              Money        `json:"premium"`
	CoverageLimit        Money        `json:"coverageLimit"`
	JourneyOrderID       string       `json:"journeyOrderId"`
	AncillaryOrderItemID string       `json:"ancillaryOrderItemId,omitempty"`
	AccountID            string       `json:"accountId"`
	TravelerRef          string       `json:"travelerRef"`
	SegmentRefs          []string     `json:"segmentRefs"`
	PaymentIntentID      string       `json:"paymentIntentId,omitempty"`
	CoverageStartAt      time.Time    `json:"coverageStartAt"`
	CoverageEndAt        time.Time    `json:"coverageEndAt"`
	Status               PolicyStatus `json:"status"`
	UnderwritingSeedRef  string       `json:"underwritingSeedRef"`
	CreatedAt            time.Time    `json:"createdAt"`
	UpdatedAt            time.Time    `json:"updatedAt"`
	Version              int64        `json:"aggregateVersion"`
}

type IssuePolicySpec struct {
	Product              InsuranceProduct
	JourneyOrderID       string
	AncillaryOrderItemID string
	AccountID            string
	TravelerRef          string
	SegmentRefs          []string
	PaymentIntentID      string
	CoverageStartAt      time.Time
	CoverageEndAt        time.Time
}

func NewPolicy(spec IssuePolicySpec, now time.Time) (Policy, error) {
	if !spec.Product.IsAvailableAt(now) {
		return Policy{}, fmt.Errorf("%w: product is not published in the sales window", ErrRuleViolation)
	}
	if strings.TrimSpace(spec.JourneyOrderID) == "" || strings.TrimSpace(spec.AccountID) == "" || strings.TrimSpace(spec.TravelerRef) == "" || len(spec.SegmentRefs) == 0 {
		return Policy{}, ErrInvalidArgument
	}
	window := Window{StartAt: spec.CoverageStartAt, EndAt: spec.CoverageEndAt}
	if err := window.Validate(); err != nil {
		return Policy{}, err
	}
	if now.IsZero() {
		now = time.Now().UTC()
	}
	segments := normalizeSegments(spec.SegmentRefs)
	if len(segments) == 0 {
		return Policy{}, ErrInvalidArgument
	}
	policy := Policy{ID: ids.NewPrefixed("pol"), ProductCode: spec.Product.ProductCode, ProductVersion: spec.Product.Version, Premium: spec.Product.Premium, CoverageLimit: spec.Product.CoverageLimit, JourneyOrderID: normalize(spec.JourneyOrderID), AncillaryOrderItemID: normalize(spec.AncillaryOrderItemID), AccountID: normalize(spec.AccountID), TravelerRef: normalize(spec.TravelerRef), SegmentRefs: segments, PaymentIntentID: normalize(spec.PaymentIntentID), CoverageStartAt: spec.CoverageStartAt.UTC(), CoverageEndAt: spec.CoverageEndAt.UTC(), Status: PolicyAwaitingPremiumCapture, CreatedAt: now.UTC(), UpdatedAt: now.UTC(), Version: 1}
	policy.UnderwritingSeedRef = policy.SelectionFingerprint()
	return policy, nil
}

func (p *Policy) RequestUnderwriting(now time.Time) error {
	if p.Status != PolicyAwaitingPremiumCapture {
		return fmt.Errorf("%w: policy must await premium capture", ErrInvalidTransition)
	}
	p.Status = PolicyUnderwritingRequested
	p.touch(now)
	return nil
}

func (p *Policy) ApplyUnderwritingSucceeded(policyNumber string, now time.Time) error {
	if p.Status != PolicyUnderwritingRequested {
		return fmt.Errorf("%w: underwriting was not requested", ErrInvalidTransition)
	}
	if strings.TrimSpace(policyNumber) == "" {
		return ErrInvalidArgument
	}
	p.PolicyNumber = strings.TrimSpace(policyNumber)
	p.Status = PolicyUnderwritten
	if !now.Before(p.CoverageStartAt) && now.Before(p.CoverageEndAt) {
		p.Status = PolicyActive
	}
	p.touch(now)
	return nil
}

func (p *Policy) Activate(now time.Time) error {
	if p.Status != PolicyUnderwritten {
		return fmt.Errorf("%w: policy is not underwritten", ErrInvalidTransition)
	}
	if now.Before(p.CoverageStartAt) || !now.Before(p.CoverageEndAt) {
		return fmt.Errorf("%w: outside coverage window", ErrRuleViolation)
	}
	p.Status = PolicyActive
	p.touch(now)
	return nil
}

func (p Policy) CanFileClaim(now time.Time) bool {
	return (p.Status == PolicyActive || p.Status == PolicyUnderwritten) && now.UTC().Before(p.CoverageEndAt.UTC())
}

func (p Policy) SelectionFingerprint() string {
	material := strings.Join([]string{p.JourneyOrderID, p.AncillaryOrderItemID, p.AccountID, p.TravelerRef, strings.Join(p.SegmentRefs, ","), string(p.ProductCode), fmt.Sprint(p.ProductVersion), p.Premium.Currency, fmt.Sprint(p.Premium.MinorUnits)}, "|")
	sum := sha256.Sum256([]byte(material))
	return hex.EncodeToString(sum[:])
}

func (p *Policy) touch(now time.Time) {
	if now.IsZero() {
		now = time.Now().UTC()
	}
	p.UpdatedAt = now.UTC()
	p.Version++
}

func normalizeSegments(segmentRefs []string) []string {
	set := map[string]struct{}{}
	for _, ref := range segmentRefs {
		if v := strings.TrimSpace(ref); v != "" {
			set[v] = struct{}{}
		}
	}
	out := make([]string, 0, len(set))
	for ref := range set {
		out = append(out, ref)
	}
	sort.Strings(out)
	return out
}
