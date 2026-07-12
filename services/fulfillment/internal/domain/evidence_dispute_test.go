package domain

import (
	"strings"
	"testing"
	"time"
)

func TestNewEvidenceDispute(t *testing.T) {
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)
	defer resetClock()

	dispute, err := NewEvidenceDispute(
		"disp-abc123",
		"fr-def456",
		"ent-ghi789",
		DisputeTypeOfflineConflict,
		"Gate scan at 10:05 UTC but station check-in at 10:02 UTC shows different location",
		"SYSTEM",
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if dispute.DisputeID != "disp-abc123" {
		t.Fatalf("unexpected id: %s", dispute.DisputeID)
	}
	if dispute.Status != DisputeStatusOpen {
		t.Fatalf("expected OPEN, got %s", dispute.Status)
	}
	if dispute.FulfillmentRecordID != "fr-def456" {
		t.Fatalf("unexpected fulfillment record id: %s", dispute.FulfillmentRecordID)
	}
	if dispute.EntitlementID != "ent-ghi789" {
		t.Fatalf("unexpected entitlement id: %s", dispute.EntitlementID)
	}
	if dispute.DisputeType != DisputeTypeOfflineConflict {
		t.Fatalf("unexpected dispute type: %s", dispute.DisputeType)
	}

	// Verify event was emitted.
	events := dispute.Events()
	if len(events) != 1 {
		t.Fatalf("expected 1 event, got %d", len(events))
	}
	if events[0].EventType() != "EvidenceDisputeOpened" {
		t.Fatalf("expected EvidenceDisputeOpened, got %s", events[0].EventType())
	}
	openedEvt, ok := events[0].(EvidenceDisputeOpenedEvent)
	if !ok {
		t.Fatalf("expected EvidenceDisputeOpenedEvent type")
	}
	if openedEvt.DisputeID != "disp-abc123" {
		t.Fatalf("unexpected dispute id in event: %s", openedEvt.DisputeID)
	}
	if openedEvt.DisputeType != DisputeTypeOfflineConflict {
		t.Fatalf("unexpected dispute type in event: %s", openedEvt.DisputeType)
	}
}

func TestNewEvidenceDisputeRejectsInvalidID(t *testing.T) {
	_, err := NewEvidenceDispute(
		"invalid",
		"fr-def456",
		"ent-ghi789",
		DisputeTypeOfflineConflict,
		"Evidence summary",
		"SYSTEM",
	)
	if err == nil || !strings.Contains(err.Error(), "invalid EvidenceDisputeID") {
		t.Fatalf("expected id error, got %v", err)
	}
}

func TestNewEvidenceDisputeRejectsInvalidFulfillmentRecordID(t *testing.T) {
	_, err := NewEvidenceDispute(
		"disp-abc123",
		"invalid",
		"ent-ghi789",
		DisputeTypeOfflineConflict,
		"Evidence summary",
		"SYSTEM",
	)
	if err == nil || !strings.Contains(err.Error(), "invalid FulfillmentRecordID") {
		t.Fatalf("expected fulfillment record id error, got %v", err)
	}
}

func TestNewEvidenceDisputeRejectsInvalidEntitlement(t *testing.T) {
	_, err := NewEvidenceDispute(
		"disp-abc123",
		"fr-def456",
		"invalid",
		DisputeTypeOfflineConflict,
		"Evidence summary",
		"SYSTEM",
	)
	if err == nil || !strings.Contains(err.Error(), "invalid EntitlementRef") {
		t.Fatalf("expected entitlement error, got %v", err)
	}
}

func TestNewEvidenceDisputeRejectsEmptyEvidence(t *testing.T) {
	_, err := NewEvidenceDispute(
		"disp-abc123",
		"fr-def456",
		"ent-ghi789",
		DisputeTypeOfflineConflict,
		"",
		"SYSTEM",
	)
	if err == nil || !strings.Contains(err.Error(), "evidence summary is required") {
		t.Fatalf("expected evidence error, got %v", err)
	}
}

func TestNewEvidenceDisputeRejectsEmptyOpenedBy(t *testing.T) {
	_, err := NewEvidenceDispute(
		"disp-abc123",
		"fr-def456",
		"ent-ghi789",
		DisputeTypeOfflineConflict,
		"Evidence summary",
		"",
	)
	if err == nil || !strings.Contains(err.Error(), "openedBy is required") {
		t.Fatalf("expected openedBy error, got %v", err)
	}
}

func TestResolveEvidenceDispute(t *testing.T) {
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)
	defer resetClock()

	dispute, _ := NewEvidenceDispute(
		"disp-abc123",
		"fr-def456",
		"ent-ghi789",
		DisputeTypeOfflineConflict,
		"Evidence summary",
		"SYSTEM",
	)
	// Clear the open event.
	dispute.Events()

	err := dispute.Resolve(DisputeResolutionUpheld, "Gate scan confirmed as primary evidence", "ADMIN-001")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if dispute.Status != DisputeStatusResolved {
		t.Fatalf("expected RESOLVED, got %s", dispute.Status)
	}
	if dispute.Resolution == nil || *dispute.Resolution != DisputeResolutionUpheld {
		t.Fatalf("expected UPHELD resolution")
	}
	if dispute.ResolutionReason != "Gate scan confirmed as primary evidence" {
		t.Fatalf("unexpected resolution reason: %s", dispute.ResolutionReason)
	}
	if dispute.ResolvedBy != "ADMIN-001" {
		t.Fatalf("unexpected resolved by: %s", dispute.ResolvedBy)
	}

	events := dispute.Events()
	if len(events) != 1 {
		t.Fatalf("expected 1 event, got %d", len(events))
	}
	if events[0].EventType() != "EvidenceDisputeResolved" {
		t.Fatalf("expected EvidenceDisputeResolved, got %s", events[0].EventType())
	}
	resolvedEvt, ok := events[0].(EvidenceDisputeResolvedEvent)
	if !ok {
		t.Fatalf("expected EvidenceDisputeResolvedEvent type")
	}
	if resolvedEvt.DisputeID != "disp-abc123" {
		t.Fatalf("unexpected dispute id in event: %s", resolvedEvt.DisputeID)
	}
	if resolvedEvt.Resolution != DisputeResolutionUpheld {
		t.Fatalf("unexpected resolution in event: %s", resolvedEvt.Resolution)
	}
}

func TestResolveEvidenceDisputeRejectsAlreadyResolved(t *testing.T) {
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)
	defer resetClock()

	dispute, _ := NewEvidenceDispute(
		"disp-abc123",
		"fr-def456",
		"ent-ghi789",
		DisputeTypeOfflineConflict,
		"Evidence summary",
		"SYSTEM",
	)
	dispute.Events()

	_ = dispute.Resolve(DisputeResolutionUpheld, "Gate scan confirmed", "ADMIN-001")

	err := dispute.Resolve(DisputeResolutionRejected, "Re-evaluated", "ADMIN-002")
	if err == nil || !strings.Contains(err.Error(), "already RESOLVED") {
		t.Fatalf("expected error for already resolved, got %v", err)
	}
}

func TestResolveEvidenceDisputeRejectsEmptyReason(t *testing.T) {
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)
	defer resetClock()

	dispute, _ := NewEvidenceDispute(
		"disp-abc123",
		"fr-def456",
		"ent-ghi789",
		DisputeTypeOfflineConflict,
		"Evidence summary",
		"SYSTEM",
	)
	dispute.Events()

	err := dispute.Resolve(DisputeResolutionUpheld, "", "ADMIN-001")
	if err == nil || !strings.Contains(err.Error(), "reason is required") {
		t.Fatalf("expected reason error, got %v", err)
	}
}

func TestResolveEvidenceDisputeRejectsEmptyResolvedBy(t *testing.T) {
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)
	defer resetClock()

	dispute, _ := NewEvidenceDispute(
		"disp-abc123",
		"fr-def456",
		"ent-ghi789",
		DisputeTypeOfflineConflict,
		"Evidence summary",
		"SYSTEM",
	)
	dispute.Events()

	err := dispute.Resolve(DisputeResolutionUpheld, "Reason", "")
	if err == nil || !strings.Contains(err.Error(), "resolvedBy is required") {
		t.Fatalf("expected resolvedBy error, got %v", err)
	}
}

func TestEvidenceDisputeEventsClearedAfterRead(t *testing.T) {
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)
	defer resetClock()

	dispute, _ := NewEvidenceDispute(
		"disp-abc123",
		"fr-def456",
		"ent-ghi789",
		DisputeTypeDuplicateBoarding,
		"Duplicate gate scan at 10:05",
		"SYSTEM",
	)

	events1 := dispute.Events()
	if len(events1) != 1 {
		t.Fatalf("expected 1 event on first read, got %d", len(events1))
	}

	events2 := dispute.Events()
	if len(events2) != 0 {
		t.Fatalf("expected 0 events after clearing, got %d", len(events2))
	}
}

func TestEvidenceDisputeMultipleTypes(t *testing.T) {
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)
	defer resetClock()

	testCases := []struct {
		name string
		typ  DisputeType
	}{
		{"OfflineConflict", DisputeTypeOfflineConflict},
		{"DuplicateBoarding", DisputeTypeDuplicateBoarding},
		{"UnrecognizedCheckIn", DisputeTypeUnrecognizedCheckIn},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			dispute, err := NewEvidenceDispute(
				"disp-abc123",
				"fr-def456",
				"ent-ghi789",
				tc.typ,
				"Evidence: "+tc.name,
				"SYSTEM",
			)
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if dispute.DisputeType != tc.typ {
				t.Fatalf("expected %s, got %s", tc.typ, dispute.DisputeType)
			}
		})
	}
}
