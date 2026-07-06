package domain

import (
	"strings"
	"testing"
	"time"
)

func resetClock() {
	timeNow = time.Now
}

func fixedClock(t *testing.T, now time.Time) {
	t.Helper()
	timeNow = func() time.Time { return now }
}

func TestNewFulfillmentRecord(t *testing.T) {
	rec, err := NewFulfillmentRecord(
		"fr-abc123",
		"ent-def456",
		"sb-ghi789",
		"ord-jkl012",
		"tvl-mno345",
		"seg-pqr678",
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if rec.FulfillmentRecordID != "fr-abc123" {
		t.Fatalf("unexpected id: %s", rec.FulfillmentRecordID)
	}
	if rec.Status != FulfillmentStatusReady {
		t.Fatalf("expected READY, got %s", rec.Status)
	}
	if rec.EntitlementID != "ent-def456" {
		t.Fatalf("unexpected entitlement: %s", rec.EntitlementID)
	}
}

func TestNewFulfillmentRecordRejectsInvalidID(t *testing.T) {
	_, err := NewFulfillmentRecord(
		"invalid",
		"ent-def456",
		"sb-ghi789",
		"ord-jkl012",
		"tvl-mno345",
		"seg-pqr678",
	)
	if err == nil || !strings.Contains(err.Error(), "invalid FulfillmentRecordID") {
		t.Fatalf("expected id error, got %v", err)
	}
}

func TestNewFulfillmentRecordRejectsInvalidEntitlement(t *testing.T) {
	_, err := NewFulfillmentRecord(
		"fr-abc123",
		"invalid-ent",
		"sb-ghi789",
		"ord-jkl012",
		"tvl-mno345",
		"seg-pqr678",
	)
	if err == nil || !strings.Contains(err.Error(), "invalid EntitlementRef") {
		t.Fatalf("expected entitlement error, got %v", err)
	}
}

func TestRecordCheckIn(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	err := rec.RecordCheckIn(FulfillmentSourceStation, "evt-checkin-001", now)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if rec.Status != FulfillmentStatusCheckedIn {
		t.Fatalf("expected CHECKED_IN, got %s", rec.Status)
	}
	if len(rec.AuditTrail) != 1 {
		t.Fatalf("expected 1 audit entry, got %d", len(rec.AuditTrail))
	}
}

func TestRecordCheckInIdempotent(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	_ = rec.RecordCheckIn(FulfillmentSourceStation, "evt-checkin-001", now)
	// Second check-in should be idempotent.
	err := rec.RecordCheckIn(FulfillmentSourceStation, "evt-checkin-001", now)
	if err != nil {
		t.Fatalf("expected idempotent check-in, got error: %v", err)
	}
	if rec.Status != FulfillmentStatusCheckedIn {
		t.Fatalf("expected CHECKED_IN, got %s", rec.Status)
	}
}

func TestRecordCheckInRejectsAfterCompleted(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	_ = rec.VerifyBoarding(rec.EntitlementID, FulfillmentSourceGate, "evt-board-001", now, now, nil)
	_ = rec.CompleteFulfillment(CompletionSourceArrival, now.Add(2*time.Hour))

	err := rec.RecordCheckIn(FulfillmentSourceStation, "evt-checkin-late", now)
	if err == nil || !strings.Contains(err.Error(), "cannot check in") {
		t.Fatalf("expected error for completed check-in, got %v", err)
	}
}

func TestRecordCheckInRejectsAfterNoShow(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	_ = rec.RecordNoShow(NoShowReasonWindowExpired, now)

	err := rec.RecordCheckIn(FulfillmentSourceStation, "evt-checkin-late", now)
	if err == nil || !strings.Contains(err.Error(), "NO_SHOW") {
		t.Fatalf("expected error for no-show check-in, got %v", err)
	}
}

func TestVerifyBoarding(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	err := rec.VerifyBoarding(rec.EntitlementID, FulfillmentSourceGate, "evt-board-001", now, now, nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if rec.Status != FulfillmentStatusBoarded {
		t.Fatalf("expected BOARDED, got %s", rec.Status)
	}
	if rec.BoardingFact == nil {
		t.Fatalf("expected boarding fact")
	}
	if rec.BoardingFact.SourceEventID != "evt-board-001" {
		t.Fatalf("unexpected source event id: %s", rec.BoardingFact.SourceEventID)
	}

	// Verify event was emitted.
	events := rec.Events()
	if len(events) != 1 {
		t.Fatalf("expected 1 event, got %d", len(events))
	}
	if events[0].EventType() != "BoardingVerified" {
		t.Fatalf("expected BoardingVerified, got %s", events[0].EventType())
	}
}

func TestVerifyBoardingEntitlementMismatch(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)

	err := rec.VerifyBoarding("ent-wrong", FulfillmentSourceGate, "evt-board-001", now, now, nil)
	if err == nil || !strings.Contains(err.Error(), "entitlement mismatch") {
		t.Fatalf("expected entitlement mismatch error, got %v", err)
	}
}

func TestVerifyBoardingDuplicateIdempotent(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	_ = rec.VerifyBoarding(rec.EntitlementID, FulfillmentSourceGate, "evt-board-001", now, now, nil)
	// Clear events from first boarding.
	rec.Events()

	// Same source + sourceEventID => idempotent, no event emitted.
	err := rec.VerifyBoarding(rec.EntitlementID, FulfillmentSourceGate, "evt-board-001", now, now, nil)
	if err != nil {
		t.Fatalf("expected idempotent boarding, got error: %v", err)
	}
	if rec.Status != FulfillmentStatusBoarded {
		t.Fatalf("expected BOARDED, got %s", rec.Status)
	}
	// No new events.
	events := rec.Events()
	if len(events) != 0 {
		t.Fatalf("expected 0 events for idempotent boarding, got %d", len(events))
	}
}

func TestVerifyBoardingDuplicateForSameEntitlementSegment(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	_ = rec.VerifyBoarding(rec.EntitlementID, FulfillmentSourceGate, "evt-board-001", now, now, nil)
	rec.Events()

	// Different sourceEventID but already boarded => idempotent (no event, no state change).
	err := rec.VerifyBoarding(rec.EntitlementID, FulfillmentSourceConductor, "evt-board-002", now, now, nil)
	if err != nil {
		t.Fatalf("expected idempotent duplicate boarding, got error: %v", err)
	}
	if rec.Status != FulfillmentStatusBoarded {
		t.Fatalf("expected BOARDED, got %s", rec.Status)
	}
	events := rec.Events()
	if len(events) != 0 {
		t.Fatalf("expected 0 events for duplicate boarding, got %d", len(events))
	}
}

func TestVerifyBoardingRejectsAfterCompleted(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	_ = rec.VerifyBoarding(rec.EntitlementID, FulfillmentSourceGate, "evt-board-001", now, now, nil)
	_ = rec.CompleteFulfillment(CompletionSourceArrival, now.Add(2*time.Hour))

	err := rec.VerifyBoarding(rec.EntitlementID, FulfillmentSourceGate, "evt-board-002", now, now, nil)
	if err == nil || !strings.Contains(err.Error(), "cannot verify boarding") {
		t.Fatalf("expected error for completed boarding, got %v", err)
	}
}

func TestVerifyBoardingRejectsAfterNoShow(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	_ = rec.RecordNoShow(NoShowReasonWindowExpired, now)

	err := rec.VerifyBoarding(rec.EntitlementID, FulfillmentSourceGate, "evt-board-001", now, now, nil)
	if err == nil || !strings.Contains(err.Error(), "cannot verify boarding") {
		t.Fatalf("expected error for no-show boarding, got %v", err)
	}
}

func TestRecordNoShow(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	err := rec.RecordNoShow(NoShowReasonWindowExpired, now)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if rec.Status != FulfillmentStatusNoShow {
		t.Fatalf("expected NO_SHOW, got %s", rec.Status)
	}
	if rec.NoShowReason == nil || *rec.NoShowReason != NoShowReasonWindowExpired {
		t.Fatalf("expected window expired reason")
	}

	events := rec.Events()
	if len(events) != 1 {
		t.Fatalf("expected 1 event, got %d", len(events))
	}
	if events[0].EventType() != "NoShowRecorded" {
		t.Fatalf("expected NoShowRecorded, got %s", events[0].EventType())
	}
}

func TestRecordNoShowIdempotent(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	_ = rec.RecordNoShow(NoShowReasonWindowExpired, now)
	rec.Events()

	err := rec.RecordNoShow(NoShowReasonManualRecord, now)
	if err != nil {
		t.Fatalf("expected idempotent no-show, got error: %v", err)
	}
	// No new events for idempotent no-show.
	events := rec.Events()
	if len(events) != 0 {
		t.Fatalf("expected 0 events, got %d", len(events))
	}
}

func TestRecordNoShowRejectsAfterBoarded(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	_ = rec.VerifyBoarding(rec.EntitlementID, FulfillmentSourceGate, "evt-board-001", now, now, nil)

	err := rec.RecordNoShow(NoShowReasonWindowExpired, now)
	if err == nil || !strings.Contains(err.Error(), "already boarded") {
		t.Fatalf("expected error for boarded no-show, got %v", err)
	}
}

func TestRecordNoShowRejectsAfterCompleted(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	_ = rec.VerifyBoarding(rec.EntitlementID, FulfillmentSourceGate, "evt-board-001", now, now, nil)
	_ = rec.CompleteFulfillment(CompletionSourceArrival, now.Add(2*time.Hour))

	err := rec.RecordNoShow(NoShowReasonWindowExpired, now)
	if err == nil || !strings.Contains(err.Error(), "cannot record no-show") {
		t.Fatalf("expected error for completed no-show, got %v", err)
	}
}

func TestCompleteFulfillment(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	_ = rec.VerifyBoarding(rec.EntitlementID, FulfillmentSourceGate, "evt-board-001", now, now, nil)
	rec.Events()

	completedAt := now.Add(2 * time.Hour)
	err := rec.CompleteFulfillment(CompletionSourceArrival, completedAt)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if rec.Status != FulfillmentStatusCompleted {
		t.Fatalf("expected COMPLETED, got %s", rec.Status)
	}
	if rec.CompletedAt == nil || !rec.CompletedAt.Equal(completedAt) {
		t.Fatalf("unexpected completed at")
	}
	if rec.CompletionSource == nil || *rec.CompletionSource != CompletionSourceArrival {
		t.Fatalf("unexpected completion source")
	}

	events := rec.Events()
	if len(events) != 1 {
		t.Fatalf("expected 1 event, got %d", len(events))
	}
	if events[0].EventType() != "FulfillmentCompleted" {
		t.Fatalf("expected FulfillmentCompleted, got %s", events[0].EventType())
	}
}

func TestCompleteFulfillmentRejectsWithoutBoarding(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	_ = rec.RecordCheckIn(FulfillmentSourceStation, "evt-checkin-001", now)
	rec.Events()

	err := rec.CompleteFulfillment(CompletionSourceProvider, now.Add(3*time.Hour))
	if err == nil || !strings.Contains(err.Error(), "must be boarded") {
		t.Fatalf("expected boarding precondition error, got %v", err)
	}
}

func TestCompleteFulfillmentIdempotent(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	_ = rec.VerifyBoarding(rec.EntitlementID, FulfillmentSourceGate, "evt-board-001", now, now, nil)
	_ = rec.CompleteFulfillment(CompletionSourceArrival, now.Add(2*time.Hour))
	rec.Events()

	err := rec.CompleteFulfillment(CompletionSourceSystem, now.Add(3*time.Hour))
	if err != nil {
		t.Fatalf("expected idempotent completion, got error: %v", err)
	}
	events := rec.Events()
	if len(events) != 0 {
		t.Fatalf("expected 0 events, got %d", len(events))
	}
}

func TestCompleteFulfillmentRejectsCancelled(t *testing.T) {
	// We don't have a Cancel command yet, but we can test the guard for
	// the pre-completion states. This test just validates that cancelled
	// status prevents completion.
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	// Force status to cancelled (no cancel command in foundation phase).
	rec.Status = FulfillmentStatusCancelled

	err := rec.CompleteFulfillment(CompletionSourceSystem, now)
	if err == nil || !strings.Contains(err.Error(), "cannot complete cancelled") {
		t.Fatalf("expected error for cancelled, got %v", err)
	}
}

func TestVerifyBoardingWithLocationSnapshot(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	loc := &LocationSnapshot{
		PlaceID:     "plc-abc",
		NodeID:      "tnd-def",
		DisplayName: "Gate A1",
	}
	err := rec.VerifyBoarding(rec.EntitlementID, FulfillmentSourceGate, "evt-board-001", now, now, loc)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if rec.Status != FulfillmentStatusBoarded {
		t.Fatalf("expected BOARDED, got %s", rec.Status)
	}

	events := rec.Events()
	if len(events) != 1 {
		t.Fatalf("expected 1 event, got %d", len(events))
	}
	evt, ok := events[0].(BoardingVerifiedEvent)
	if !ok {
		t.Fatalf("expected BoardingVerifiedEvent")
	}
	if evt.LocationSnapshot == nil {
		t.Fatalf("expected location snapshot")
	}
	if evt.LocationSnapshot.PlaceID != "plc-abc" {
		t.Fatalf("unexpected place id: %s", evt.LocationSnapshot.PlaceID)
	}
}

func TestFulfillmentRecordEventsClearedAfterRead(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	_ = rec.VerifyBoarding(rec.EntitlementID, FulfillmentSourceGate, "evt-board-001", now, now, nil)

	events1 := rec.Events()
	if len(events1) != 1 {
		t.Fatalf("expected 1 event on first read, got %d", len(events1))
	}

	events2 := rec.Events()
	if len(events2) != 0 {
		t.Fatalf("expected 0 events after clearing, got %d", len(events2))
	}
}

func TestAuditTrailRecording(t *testing.T) {
	rec, _ := NewFulfillmentRecord(
		"fr-abc123", "ent-def456", "sb-ghi789", "ord-jkl012", "tvl-mno345", "seg-pqr678",
	)
	now := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	fixedClock(t, now)

	_ = rec.RecordCheckIn(FulfillmentSourceStation, "evt-checkin-001", now)
	_ = rec.VerifyBoarding(rec.EntitlementID, FulfillmentSourceGate, "evt-board-001", now, now, nil)

	if len(rec.AuditTrail) != 2 {
		t.Fatalf("expected 2 audit entries, got %d", len(rec.AuditTrail))
	}
	if rec.AuditTrail[0].CommandID != "RecordCheckIn" {
		t.Fatalf("expected first entry to be RecordCheckIn")
	}
	if rec.AuditTrail[0].PreviousState != FulfillmentStatusReady {
		t.Fatalf("expected previous state READY, got %s", rec.AuditTrail[0].PreviousState)
	}
	if rec.AuditTrail[0].NewState != FulfillmentStatusCheckedIn {
		t.Fatalf("expected new state CHECKED_IN, got %s", rec.AuditTrail[0].NewState)
	}
	if rec.AuditTrail[1].CommandID != "VerifyBoarding" {
		t.Fatalf("expected second entry to be VerifyBoarding")
	}
	if rec.AuditTrail[1].PreviousState != FulfillmentStatusCheckedIn {
		t.Fatalf("expected previous state CHECKED_IN, got %s", rec.AuditTrail[1].PreviousState)
	}
	if rec.AuditTrail[1].NewState != FulfillmentStatusBoarded {
		t.Fatalf("expected new state BOARDED, got %s", rec.AuditTrail[1].NewState)
	}
}
