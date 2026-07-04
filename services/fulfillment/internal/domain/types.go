package domain

import (
	"fmt"
	"regexp"
	"strings"
	"time"
)

// ============================================================
// Canonical ID types following docs/08-contracts/shared-primitives.md
// ============================================================

// FulfillmentRecordID is a fulfillment record identifier (fr-<uuid>).
type FulfillmentRecordID string

func (id FulfillmentRecordID) Validate() error {
	if matched, _ := regexp.MatchString(`^fr-[a-zA-Z0-9\-]+$`, string(id)); !matched {
		return fmt.Errorf("invalid FulfillmentRecordID: %q", string(id))
	}
	return nil
}

// EvidenceDisputeID is a dispute identifier (disp-<uuid>).
type EvidenceDisputeID string

func (id EvidenceDisputeID) Validate() error {
	if matched, _ := regexp.MatchString(`^disp-[a-zA-Z0-9\-]+$`, string(id)); !matched {
		return fmt.Errorf("invalid EvidenceDisputeID: %q", string(id))
	}
	return nil
}

// EntitlementRef references an entitlement (ent-<uuid>).
type EntitlementRef string

func (r EntitlementRef) Validate() error {
	if matched, _ := regexp.MatchString(`^ent-[a-zA-Z0-9\-]+$`, string(r)); !matched {
		return fmt.Errorf("invalid EntitlementRef: %q", string(r))
	}
	return nil
}

// SegmentBookingRef references a segment booking (sb-<uuid>).
type SegmentBookingRef string

func (r SegmentBookingRef) Validate() error {
	if matched, _ := regexp.MatchString(`^sb-[a-zA-Z0-9\-]+$`, string(r)); !matched {
		return fmt.Errorf("invalid SegmentBookingRef: %q", string(r))
	}
	return nil
}

// OrderRef references a journey order (ord-<uuid>).
type OrderRef string

func (r OrderRef) Validate() error {
	if matched, _ := regexp.MatchString(`^ord-[a-zA-Z0-9\-]+$`, string(r)); !matched {
		return fmt.Errorf("invalid OrderRef: %q", string(r))
	}
	return nil
}

// TravelerRef references a traveler (tvl-<uuid>).
type TravelerRef string

func (r TravelerRef) Validate() error {
	if matched, _ := regexp.MatchString(`^tvl-[a-zA-Z0-9\-]+$`, string(r)); !matched {
		return fmt.Errorf("invalid TravelerRef: %q", string(r))
	}
	return nil
}

// SegmentRef references a service segment (seg-<uuid>).
type SegmentRef string

func (r SegmentRef) Validate() error {
	if matched, _ := regexp.MatchString(`^seg-[a-zA-Z0-9\-]+$`, string(r)); !matched {
		return fmt.Errorf("invalid SegmentRef: %q", string(r))
	}
	return nil
}

// ============================================================
// Enums
// ============================================================

// FulfillmentStatus represents the lifecycle state of a FulfillmentRecord.
type FulfillmentStatus string

const (
	FulfillmentStatusNotOpened  FulfillmentStatus = "NOT_OPENED"
	FulfillmentStatusReady      FulfillmentStatus = "READY"
	FulfillmentStatusCheckedIn  FulfillmentStatus = "CHECKED_IN"
	FulfillmentStatusBoarded    FulfillmentStatus = "BOARDED"
	FulfillmentStatusCompleted  FulfillmentStatus = "COMPLETED"
	FulfillmentStatusNoShow     FulfillmentStatus = "NO_SHOW"
	FulfillmentStatusCancelled  FulfillmentStatus = "CANCELLED"
)

// FulfillmentSource represents the source of a fulfillment fact.
type FulfillmentSource string

const (
	FulfillmentSourceGate      FulfillmentSource = "GATE"
	FulfillmentSourceStation   FulfillmentSource = "STATION"
	FulfillmentSourceProvider  FulfillmentSource = "PROVIDER"
	FulfillmentSourceConductor FulfillmentSource = "CONDUCTOR"
	FulfillmentSourceAdmin     FulfillmentSource = "ADMIN"
	FulfillmentSourceSystem    FulfillmentSource = "SYSTEM"
)

// NoShowReason represents the reason for a no-show record.
type NoShowReason string

const (
	NoShowReasonWindowExpired      NoShowReason = "BOARDING_WINDOW_EXPIRED"
	NoShowReasonVerificationFailed NoShowReason = "VERIFICATION_FAILED"
	NoShowReasonManualRecord       NoShowReason = "MANUAL_RECORD"
)

// DisputeType represents the type of evidence dispute.
type DisputeType string

const (
	DisputeTypeOfflineConflict     DisputeType = "OFFLINE_EVIDENCE_CONFLICT"
	DisputeTypeDuplicateBoarding   DisputeType = "DUPLICATE_BOARDING"
	DisputeTypeUnrecognizedCheckIn DisputeType = "UNRECOGNIZED_CHECK_IN"
)

// DisputeStatus represents the lifecycle state of an EvidenceDispute.
type DisputeStatus string

const (
	DisputeStatusOpen     DisputeStatus = "OPEN"
	DisputeStatusResolved DisputeStatus = "RESOLVED"
)

// DisputeResolution represents the outcome of a resolved dispute.
type DisputeResolution string

const (
	DisputeResolutionUpheld       DisputeResolution = "UPHELD"
	DisputeResolutionRejected     DisputeResolution = "REJECTED"
	DisputeResolutionInconclusive DisputeResolution = "INCONCLUSIVE"
)

// CompletionSource represents the source of fulfillment completion.
type CompletionSource string

const (
	CompletionSourceArrival  CompletionSource = "ARRIVAL"
	CompletionSourceProvider CompletionSource = "PROVIDER"
	CompletionSourceAdmin    CompletionSource = "ADMIN"
	CompletionSourceSystem   CompletionSource = "SYSTEM"
)

// ============================================================
// Location Snapshot
// ============================================================

// LocationSnapshot captures the location context of a fulfillment event.
type LocationSnapshot struct {
	PlaceID     string `json:"placeId,omitempty"`
	NodeID      string `json:"nodeId,omitempty"`
	DisplayName string `json:"displayName,omitempty"`
}

// ============================================================
// Domain Events
// ============================================================

// DomainEvent is the interface all fulfillment domain events implement.
type DomainEvent interface {
	EventType() string
	OccurredAt() time.Time
}

// BoardingVerifiedEvent is emitted when boarding is verified.
type BoardingVerifiedEvent struct {
	FulfillmentRecordID FulfillmentRecordID
	EntitlementID       EntitlementRef
	SegmentBookingID    SegmentBookingRef
	JourneyOrderID      OrderRef
	TravelerID          TravelerRef
	SegmentRef          SegmentRef
	Source              FulfillmentSource
	SourceEventID       string
	eventTime           time.Time
	ReceivedAt          time.Time
	LocationSnapshot    *LocationSnapshot
}

func (e BoardingVerifiedEvent) EventType() string   { return "BoardingVerified" }
func (e BoardingVerifiedEvent) OccurredAt() time.Time { return e.eventTime }

// NoShowRecordedEvent is emitted when a no-show is recorded.
type NoShowRecordedEvent struct {
	FulfillmentRecordID FulfillmentRecordID
	EntitlementID       EntitlementRef
	SegmentBookingID    SegmentBookingRef
	JourneyOrderID      OrderRef
	TravelerID          TravelerRef
	SegmentRef          SegmentRef
	Reason              NoShowReason
	assessedAt          time.Time
}

func (e NoShowRecordedEvent) EventType() string   { return "NoShowRecorded" }
func (e NoShowRecordedEvent) OccurredAt() time.Time { return e.assessedAt }

// FulfillmentCompletedEvent is emitted when fulfillment is completed.
type FulfillmentCompletedEvent struct {
	FulfillmentRecordID FulfillmentRecordID
	EntitlementID       EntitlementRef
	SegmentBookingID    SegmentBookingRef
	JourneyOrderID      OrderRef
	TravelerID          TravelerRef
	completedAt         time.Time
	CompletionSource    CompletionSource
}

func (e FulfillmentCompletedEvent) EventType() string   { return "FulfillmentCompleted" }
func (e FulfillmentCompletedEvent) OccurredAt() time.Time { return e.completedAt }

// EvidenceDisputeOpenedEvent is emitted when a dispute is opened.
type EvidenceDisputeOpenedEvent struct {
	DisputeID           EvidenceDisputeID
	FulfillmentRecordID FulfillmentRecordID
	EntitlementID       EntitlementRef
	DisputeType         DisputeType
	EvidenceSummary     string
	openedAt            time.Time
	OpenedBy            string
}

func (e EvidenceDisputeOpenedEvent) EventType() string   { return "EvidenceDisputeOpened" }
func (e EvidenceDisputeOpenedEvent) OccurredAt() time.Time { return e.openedAt }

// EvidenceDisputeResolvedEvent is emitted when a dispute is resolved.
type EvidenceDisputeResolvedEvent struct {
	DisputeID           EvidenceDisputeID
	FulfillmentRecordID FulfillmentRecordID
	Resolution          DisputeResolution
	Reason              string
	resolvedAt          time.Time
	ResolvedBy          string
}

func (e EvidenceDisputeResolvedEvent) EventType() string   { return "EvidenceDisputeResolved" }
func (e EvidenceDisputeResolvedEvent) OccurredAt() time.Time { return e.resolvedAt }

// ============================================================
// Value Objects for Events
// ============================================================

// BoardingFact is a value object representing a verified boarding fact.
type BoardingFact struct {
	EntitlementID    EntitlementRef
	SegmentBookingID SegmentBookingRef
	SegmentRef       SegmentRef
	Source           FulfillmentSource
	SourceEventID    string
	OccurredAt       time.Time
	ReceivedAt       time.Time
}

// Validate checks that the boarding fact references a known entitlement.
func (f BoardingFact) Validate() error {
	if err := f.EntitlementID.Validate(); err != nil {
		return fmt.Errorf("boarding fact: %w", err)
	}
	if err := f.SegmentBookingID.Validate(); err != nil {
		return fmt.Errorf("boarding fact: %w", err)
	}
	if strings.TrimSpace(f.SourceEventID) == "" {
		return fmt.Errorf("boarding fact: sourceEventId is required")
	}
	return nil
}

// AuditEntry represents a single audit trail entry.
type AuditEntry struct {
	CommandID     string
	Actor         string
	Reason        string
	PreviousState FulfillmentStatus
	NewState      FulfillmentStatus
	OccurredAt    time.Time
}
