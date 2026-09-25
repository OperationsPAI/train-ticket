package domain

import (
	"testing"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
)

func TestDiscrepancyRequiresTraceableReferenceAndKnownType(t *testing.T) {
	now := time.Date(2026, time.September, 25, 12, 0, 0, 0, time.UTC)
	base := func(diff, lineID, orderID string) error {
		_, err := NewDiscrepancy("", "chs-test", lineID, orderID, "", "", diff,
			Money{Currency: "CNY", MinorUnits: 100}, Money{Currency: "CNY", MinorUnits: 101},
			"evidence", now, ids.NewCorrelationID(), ids.NewCommandID())
		return err
	}
	if err := base("AMOUNT_MISMATCH", "", ""); err == nil || err.Error() != "discrepancy requires a statement line, channel order, channel refund, or finance case reference" {
		t.Fatalf("untraceable discrepancy was accepted: %v", err)
	}
	if err := base("NOT_A_DIFFERENCE", "csl-test", ""); err == nil || err.Error() != "unsupported differenceType" {
		t.Fatalf("unknown discrepancy type was accepted: %v", err)
	}
}

func TestDiscrepancyResolutionRequiresAuditAndStableReplay(t *testing.T) {
	now := time.Date(2026, time.September, 25, 12, 0, 0, 0, time.UTC)
	discrepancy, err := NewDiscrepancy("", "chs-test", "csl-test", "cho-test", "", "", "AMOUNT_MISMATCH",
		Money{Currency: "CNY", MinorUnits: 100}, Money{Currency: "CNY", MinorUnits: 101},
		"evidence", now, ids.NewCorrelationID(), ids.NewCommandID())
	if err != nil {
		t.Fatal(err)
	}
	if err := discrepancy.Resolve("RESOLVED", "", "ops", "MATCHED", discrepancy.Version, now.Add(time.Minute), ids.NewCorrelationID(), ids.NewCommandID()); err == nil {
		t.Fatal("resolution without evidence was accepted")
	}
	if err := discrepancy.Resolve("RESOLVED", "resolution-evidence", "ops", "MATCHED", discrepancy.Version, now.Add(time.Minute), ids.NewCorrelationID(), ids.NewCommandID()); err != nil {
		t.Fatal(err)
	}
	if err := discrepancy.Resolve("REJECTED", "other-evidence", "ops", "REPLAY", 1, now.Add(2*time.Minute), ids.NewCorrelationID(), ids.NewCommandID()); err == nil || err.Error() != "terminal discrepancy replay does not match stored resolution" {
		t.Fatalf("changed terminal replay was accepted: %v", err)
	}
	if err := discrepancy.Resolve("RESOLVED", "resolution-evidence", "ops", "MATCHED", 1, now.Add(2*time.Minute), ids.NewCorrelationID(), ids.NewCommandID()); err != nil {
		t.Fatalf("identical terminal replay failed: %v", err)
	}
}
