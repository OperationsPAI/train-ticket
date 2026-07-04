package domain

import (
	"fmt"
	"strings"
	"time"
)

// FulfillmentRecord is the aggregate root for a traveler's fulfillment on a segment.
// It records physical travel facts: check-in, boarding, no-show, and completion.
// It never issues or voids entitlements itself.
type FulfillmentRecord struct {
	FulfillmentRecordID FulfillmentRecordID
	EntitlementID       EntitlementRef
	SegmentBookingID    SegmentBookingRef
	JourneyOrderID      OrderRef
	TravelerID          TravelerRef
	SegmentRef          SegmentRef
	Status              FulfillmentStatus

	// Boarding fact recorded (value object).
	BoardingFact *BoardingFact

	// No-show details.
	NoShowReason    *NoShowReason
	NoShowAssessedAt *time.Time

	// Completion details.
	CompletedAt      *time.Time
	CompletionSource *CompletionSource

	// Audit trail.
	AuditTrail []AuditEntry

	// Event log - events pending publication.
	events []DomainEvent

	// Tracking fields.
	CreatedAt time.Time
	UpdatedAt time.Time
}

// NewFulfillmentRecord creates a new FulfillmentRecord aggregate.
func NewFulfillmentRecord(
	id FulfillmentRecordID,
	entitlementID EntitlementRef,
	segmentBookingID SegmentBookingRef,
	journeyOrderID OrderRef,
	travelerID TravelerRef,
	segmentRef SegmentRef,
) (*FulfillmentRecord, error) {
	if err := id.Validate(); err != nil {
		return nil, fmt.Errorf("new fulfillment record: %w", err)
	}
	if err := entitlementID.Validate(); err != nil {
		return nil, fmt.Errorf("new fulfillment record: %w", err)
	}
	if err := segmentBookingID.Validate(); err != nil {
		return nil, fmt.Errorf("new fulfillment record: %w", err)
	}
	if err := travelerID.Validate(); err != nil {
		return nil, fmt.Errorf("new fulfillment record: %w", err)
	}

	now := timeNow().UTC()
	return &FulfillmentRecord{
		FulfillmentRecordID: id,
		EntitlementID:       entitlementID,
		SegmentBookingID:    segmentBookingID,
		JourneyOrderID:      journeyOrderID,
		TravelerID:          travelerID,
		SegmentRef:          segmentRef,
		Status:              FulfillmentStatusReady,
		AuditTrail:          []AuditEntry{},
		events:              []DomainEvent{},
		CreatedAt:           now,
		UpdatedAt:           now,
	}, nil
}

// ============================================================
// Command: RecordCheckIn
// ============================================================

// RecordCheckIn processes a check-in event. Idempotent if already checked in.
func (r *FulfillmentRecord) RecordCheckIn(source FulfillmentSource, sourceEventID string, occurredAt time.Time) error {
	if r.Status == FulfillmentStatusCompleted || r.Status == FulfillmentStatusCancelled {
		return fmt.Errorf("cannot check in fulfillment record %q in status %s", r.FulfillmentRecordID, r.Status)
	}
	if r.Status == FulfillmentStatusNoShow {
		return fmt.Errorf("cannot check in fulfillment record %q in status NO_SHOW", r.FulfillmentRecordID)
	}
	if r.Status == FulfillmentStatusCheckedIn {
		// Idempotent: already checked in.
		return nil
	}
	if strings.TrimSpace(sourceEventID) == "" {
		return fmt.Errorf("sourceEventId is required for check-in")
	}

	prev := r.Status
	r.Status = FulfillmentStatusCheckedIn
	r.UpdatedAt = timeNow().UTC()
	r.appendAuditEntry("RecordCheckIn", string(source), "check-in recorded", prev, r.Status)
	return nil
}

// ============================================================
// Command: VerifyBoarding
// ============================================================

// VerifyBoarding processes a boarding verification. Idempotent for the same
// entitlement + segment (duplicate boarding events are ignored).
func (r *FulfillmentRecord) VerifyBoarding(
	entitlementID EntitlementRef,
	source FulfillmentSource,
	sourceEventID string,
	occurredAt time.Time,
	receivedAt time.Time,
	location *LocationSnapshot,
) error {
	if err := entitlementID.Validate(); err != nil {
		return fmt.Errorf("verify boarding: %w", err)
	}
	if r.EntitlementID != entitlementID {
		return fmt.Errorf("verify boarding: entitlement mismatch: record has %q, got %q", r.EntitlementID, entitlementID)
	}
	if strings.TrimSpace(sourceEventID) == "" {
		return fmt.Errorf("verify boarding: sourceEventId is required")
	}

	// Check terminal states.
	switch r.Status {
	case FulfillmentStatusCompleted, FulfillmentStatusCancelled, FulfillmentStatusNoShow:
		return fmt.Errorf("cannot verify boarding in status %s", r.Status)
	}

	// Idempotency: same source + sourceEventID already recorded.
	if r.BoardingFact != nil && r.BoardingFact.SourceEventID == sourceEventID && r.BoardingFact.Source == source {
		return nil
	}

	// Duplicate boarding for same entitlement + segment: if already boarded, silently idempotent.
	if r.Status == FulfillmentStatusBoarded {
		return nil
	}

	prev := r.Status
	r.BoardingFact = &BoardingFact{
		EntitlementID:    entitlementID,
		SegmentBookingID: r.SegmentBookingID,
		SegmentRef:       r.SegmentRef,
		Source:           source,
		SourceEventID:    sourceEventID,
		OccurredAt:       occurredAt,
		ReceivedAt:       receivedAt,
	}
	r.Status = FulfillmentStatusBoarded
	r.UpdatedAt = timeNow().UTC()
	r.appendAuditEntry("VerifyBoarding", string(source), "boarding verified", prev, r.Status)

	r.emit(BoardingVerifiedEvent{
		FulfillmentRecordID: r.FulfillmentRecordID,
		EntitlementID:       entitlementID,
		SegmentBookingID:    r.SegmentBookingID,
		JourneyOrderID:      r.JourneyOrderID,
		TravelerID:          r.TravelerID,
		SegmentRef:          r.SegmentRef,
		Source:              source,
		SourceEventID:       sourceEventID,
		eventTime:           occurredAt,
		ReceivedAt:          receivedAt,
		LocationSnapshot:    location,
	})
	return nil
}

// ============================================================
// Command: RecordNoShow
// ============================================================

// RecordNoShow records a no-show fact for this fulfillment record.
func (r *FulfillmentRecord) RecordNoShow(reason NoShowReason, assessedAt time.Time) error {
	switch r.Status {
	case FulfillmentStatusCompleted, FulfillmentStatusCancelled:
		return fmt.Errorf("cannot record no-show in status %s", r.Status)
	case FulfillmentStatusBoarded:
		return fmt.Errorf("cannot record no-show: already boarded")
	case FulfillmentStatusNoShow:
		// Idempotent: already recorded as no-show.
		return nil
	}

	prev := r.Status
	r.Status = FulfillmentStatusNoShow
	r.NoShowReason = &reason
	r.NoShowAssessedAt = &assessedAt
	r.UpdatedAt = timeNow().UTC()
	r.appendAuditEntry("RecordNoShow", "SYSTEM", string(reason), prev, r.Status)

	r.emit(NoShowRecordedEvent{
		FulfillmentRecordID: r.FulfillmentRecordID,
		EntitlementID:       r.EntitlementID,
		SegmentBookingID:    r.SegmentBookingID,
		JourneyOrderID:      r.JourneyOrderID,
		TravelerID:          r.TravelerID,
		SegmentRef:          r.SegmentRef,
		Reason:              reason,
		assessedAt:          assessedAt,
	})
	return nil
}

// ============================================================
// Command: CompleteFulfillment
// ============================================================

// CompleteFulfillment marks the fulfillment as completed.
func (r *FulfillmentRecord) CompleteFulfillment(source CompletionSource, completedAt time.Time) error {
	if r.Status == FulfillmentStatusCancelled {
		return fmt.Errorf("cannot complete cancelled fulfillment")
	}
	if r.Status == FulfillmentStatusCompleted {
		return nil // idempotent
	}
	if r.Status != FulfillmentStatusBoarded && r.Status != FulfillmentStatusCheckedIn && r.Status != FulfillmentStatusReady {
		return fmt.Errorf("cannot complete fulfillment in status %s (must be boarded, checked-in, or ready)", r.Status)
	}

	prev := r.Status
	r.Status = FulfillmentStatusCompleted
	r.CompletedAt = &completedAt
	r.CompletionSource = &source
	r.UpdatedAt = timeNow().UTC()
	r.appendAuditEntry("CompleteFulfillment", string(source), "fulfillment completed", prev, r.Status)

	r.emit(FulfillmentCompletedEvent{
		FulfillmentRecordID: r.FulfillmentRecordID,
		EntitlementID:       r.EntitlementID,
		SegmentBookingID:    r.SegmentBookingID,
		JourneyOrderID:      r.JourneyOrderID,
		TravelerID:          r.TravelerID,
		completedAt:         completedAt,
		CompletionSource:    source,
	})
	return nil
}

// ============================================================
// Event Sink
// ============================================================

// Events returns the pending domain events and clears the event log.
func (r *FulfillmentRecord) Events() []DomainEvent {
	events := r.events
	r.events = nil
	return events
}

func (r *FulfillmentRecord) emit(event DomainEvent) {
	r.events = append(r.events, event)
}

func (r *FulfillmentRecord) appendAuditEntry(commandID, actor, reason string, prev, new FulfillmentStatus) {
	r.AuditTrail = append(r.AuditTrail, AuditEntry{
		CommandID:     commandID,
		Actor:         actor,
		Reason:        reason,
		PreviousState: prev,
		NewState:      new,
		OccurredAt:    timeNow().UTC(),
	})
}
