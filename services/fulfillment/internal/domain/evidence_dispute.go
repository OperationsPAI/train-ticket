package domain

import (
	"fmt"
	"strings"
	"time"
)

// EvidenceDispute is an aggregate representing a dispute over offline evidence
// or conflicting fulfillment facts. It has its own lifecycle and never mutates
// the original fulfillment fact.
type EvidenceDispute struct {
	DisputeID           EvidenceDisputeID
	FulfillmentRecordID FulfillmentRecordID
	EntitlementID       EntitlementRef
	DisputeType         DisputeType
	Status              DisputeStatus
	EvidenceSummary     string
	Resolution          *DisputeResolution
	ResolutionReason    string
	OpenedAt            time.Time
	OpenedBy            string
	ResolvedAt          *time.Time
	ResolvedBy          string
	UpdatedAt           time.Time

	events []DomainEvent
}

// NewEvidenceDispute creates a new EvidenceDispute aggregate.
func NewEvidenceDispute(
	disputeID EvidenceDisputeID,
	fulfillmentRecordID FulfillmentRecordID,
	entitlementID EntitlementRef,
	disputeType DisputeType,
	evidenceSummary string,
	openedBy string,
) (*EvidenceDispute, error) {
	if err := disputeID.Validate(); err != nil {
		return nil, fmt.Errorf("new evidence dispute: %w", err)
	}
	if err := fulfillmentRecordID.Validate(); err != nil {
		return nil, fmt.Errorf("new evidence dispute: %w", err)
	}
	if err := entitlementID.Validate(); err != nil {
		return nil, fmt.Errorf("new evidence dispute: %w", err)
	}
	if strings.TrimSpace(evidenceSummary) == "" {
		return nil, fmt.Errorf("new evidence dispute: evidence summary is required")
	}
	if strings.TrimSpace(openedBy) == "" {
		return nil, fmt.Errorf("new evidence dispute: openedBy is required")
	}

	now := timeNow().UTC()
	dispute := &EvidenceDispute{
		DisputeID:           disputeID,
		FulfillmentRecordID: fulfillmentRecordID,
		EntitlementID:       entitlementID,
		DisputeType:         disputeType,
		Status:              DisputeStatusOpen,
		EvidenceSummary:     evidenceSummary,
		OpenedAt:            now,
		OpenedBy:            openedBy,
		UpdatedAt:           now,
		events:              []DomainEvent{},
	}

	dispute.emit(EvidenceDisputeOpenedEvent{
		DisputeID:           disputeID,
		FulfillmentRecordID: fulfillmentRecordID,
		EntitlementID:       entitlementID,
		DisputeType:         disputeType,
		EvidenceSummary:     evidenceSummary,
		openedAt:            now,
		OpenedBy:            openedBy,
	})
	return dispute, nil
}

// Resolve resolves an open evidence dispute with the given resolution.
func (d *EvidenceDispute) Resolve(resolution DisputeResolution, reason string, resolvedBy string) error {
	if d.Status != DisputeStatusOpen {
		return fmt.Errorf("cannot resolve dispute %q: already %s", d.DisputeID, d.Status)
	}
	if strings.TrimSpace(reason) == "" {
		return fmt.Errorf("resolve dispute: reason is required")
	}
	if strings.TrimSpace(resolvedBy) == "" {
		return fmt.Errorf("resolve dispute: resolvedBy is required")
	}

	now := timeNow().UTC()
	d.Status = DisputeStatusResolved
	d.Resolution = &resolution
	d.ResolutionReason = reason
	d.ResolvedAt = &now
	d.ResolvedBy = resolvedBy
	d.UpdatedAt = now

	d.emit(EvidenceDisputeResolvedEvent{
		DisputeID:           d.DisputeID,
		FulfillmentRecordID: d.FulfillmentRecordID,
		Resolution:          resolution,
		Reason:              reason,
		resolvedAt:          now,
		ResolvedBy:          resolvedBy,
	})
	return nil
}

// Events returns the pending domain events and clears the event log.
func (d *EvidenceDispute) Events() []DomainEvent {
	events := d.events
	d.events = nil
	return events
}

func (d *EvidenceDispute) emit(event DomainEvent) {
	d.events = append(d.events, event)
}
