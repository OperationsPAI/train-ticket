package application

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

func delayIssueCommand(now time.Time) IssuePolicyCommand {
	return IssuePolicyCommand{ProductCode: string(domain.ProductDelayInsurance), ProductVersion: "v1", JourneyOrderID: "jo-1", AncillaryOrderItemID: "anc-1", AccountID: "acct-1", TravelerRef: "trav-1", SegmentRefs: []string{"seg-1"}, PaymentIntentID: "pi-1", CoverageStartAt: now.Add(-time.Minute), CoverageEndAt: now.Add(time.Hour)}
}

func TestIssuePolicyRefusesCoverAboveTheDeclaredRetention(t *testing.T) {
	now := time.Date(2026, 9, 27, 10, 0, 0, 0, time.UTC)
	svc := NewInsuranceService(ServiceConfig{Clock: fixedClock{now}, IDGenerator: sequentialIDs(), RetentionLimitMinor: 2000})
	if _, err := svc.IssuePolicy(context.Background(), delayIssueCommand(now)); !errors.Is(err, ErrDomainRule) {
		t.Fatalf("expected the delay cover to be refused, err = %v", err)
	}
}

func TestIssuePolicyIssuesCoverInsideTheDeclaredRetention(t *testing.T) {
	now := time.Date(2026, 9, 27, 10, 0, 0, 0, time.UTC)
	svc := NewInsuranceService(ServiceConfig{Clock: fixedClock{now}, IDGenerator: sequentialIDs(), RetentionLimitMinor: 5000})
	policy, err := svc.IssuePolicy(context.Background(), delayIssueCommand(now))
	if err != nil {
		t.Fatalf("IssuePolicy: %v", err)
	}
	if policy.CoverageLimit.MinorUnits != 3000 {
		t.Fatalf("coverage limit = %d, want 3000", policy.CoverageLimit.MinorUnits)
	}
}

func TestUnderwritingEnvelopeBoundaryIsTheDeclaredRetention(t *testing.T) {
	now := time.Date(2026, 9, 27, 10, 0, 0, 0, time.UTC)
	cover := int64(3000)
	for _, tc := range []struct {
		name       string
		retention  int64
		wantIssued bool
	}{
		{"a cover equal to the retention is inside it", cover, true},
		{"a release that declares none keeps the catalogue", 0, true},
		{"a cover above the retention is refused", cover - 1, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			svc := NewInsuranceService(ServiceConfig{Clock: fixedClock{now}, IDGenerator: sequentialIDs(), RetentionLimitMinor: tc.retention})
			_, err := svc.IssuePolicy(context.Background(), delayIssueCommand(now))
			if tc.wantIssued && err != nil {
				t.Fatalf("IssuePolicy: %v", err)
			}
			if !tc.wantIssued && !errors.Is(err, ErrDomainRule) {
				t.Fatalf("expected a domain rule violation, err = %v", err)
			}
		})
	}
}
