package domain

import (
	"fmt"
	"strings"
	"time"
)

// AncillaryFulfillmentHandoff is Fulfillment's local evidence/read model for
// ancillary services that are ready for provider or voucher handoff and for the
// fulfillment facts later recorded by Ancillary Service.
type AncillaryFulfillmentHandoff struct {
	AncillaryOrderItemID string
	JourneyOrderID       OrderRef
	TravelerRef          TravelerRef
	SegmentRef           SegmentRef
	CatalogItemID        string
	ServiceType          string
	EntitlementRef       EntitlementRef
	ProviderRef          string
	Status               string
	ReadyAt              *time.Time
	Facts                []AncillaryFulfillmentFact
	CreatedAt            time.Time
	UpdatedAt            time.Time
}

type AncillaryFulfillmentFact struct {
	FulfillmentFactID string
	FactType          string
	ProviderRef       string
	PlaceRef          string
	OccurredAt        time.Time
	RecordedAt        time.Time
	PerformedBy       string
	IdempotencyRef    string
	Compensable       bool
	SourceEventID     string
}

func NewAncillaryFulfillmentHandoff(ancillaryOrderItemID string, journeyOrderID OrderRef, travelerRef TravelerRef, segmentRef SegmentRef, catalogItemID, serviceType string) (*AncillaryFulfillmentHandoff, error) {
	now := timeNow().UTC()
	handoff := &AncillaryFulfillmentHandoff{
		AncillaryOrderItemID: strings.TrimSpace(ancillaryOrderItemID),
		JourneyOrderID:       journeyOrderID,
		TravelerRef:          travelerRef,
		SegmentRef:           segmentRef,
		CatalogItemID:        strings.TrimSpace(catalogItemID),
		ServiceType:          strings.TrimSpace(serviceType),
		CreatedAt:            now,
		UpdatedAt:            now,
	}
	return handoff, handoff.Validate()
}

func (h *AncillaryFulfillmentHandoff) MarkReady(status string, readyAt time.Time, providerRef string, entitlementRef EntitlementRef) error {
	if h == nil {
		return fmt.Errorf("ancillary handoff is nil")
	}
	if strings.TrimSpace(status) != "FULFILLMENT_READY" {
		return fmt.Errorf("unexpected ancillary ready status %q", status)
	}
	if readyAt.IsZero() {
		return fmt.Errorf("readyAt is required")
	}
	if strings.TrimSpace(string(entitlementRef)) != "" {
		if err := entitlementRef.Validate(); err != nil {
			return err
		}
		h.EntitlementRef = entitlementRef
	}
	h.ProviderRef = strings.TrimSpace(providerRef)
	h.Status = "FULFILLMENT_READY"
	at := readyAt.UTC()
	h.ReadyAt = &at
	h.UpdatedAt = timeNow().UTC()
	return h.Validate()
}

func (h *AncillaryFulfillmentHandoff) RecordFact(status string, fact AncillaryFulfillmentFact) error {
	if h == nil {
		return fmt.Errorf("ancillary handoff is nil")
	}
	if !isAncillaryFactStatus(status) {
		return fmt.Errorf("unexpected ancillary fulfillment fact status %q", status)
	}
	if err := fact.Validate(); err != nil {
		return err
	}
	for _, existing := range h.Facts {
		if existing.SourceEventID == fact.SourceEventID || existing.FulfillmentFactID == fact.FulfillmentFactID {
			h.Status = strings.TrimSpace(status)
			h.UpdatedAt = timeNow().UTC()
			return nil
		}
	}
	h.Facts = append(h.Facts, fact)
	h.Status = strings.TrimSpace(status)
	if strings.TrimSpace(h.ProviderRef) == "" && strings.TrimSpace(fact.ProviderRef) != "" {
		h.ProviderRef = strings.TrimSpace(fact.ProviderRef)
	}
	h.UpdatedAt = timeNow().UTC()
	return h.Validate()
}

func isAncillaryFactStatus(status string) bool {
	switch strings.TrimSpace(status) {
	case "FULFILLMENT_READY", "FULFILLED", "FAILED":
		return true
	default:
		return false
	}
}

func (h *AncillaryFulfillmentHandoff) Validate() error {
	if h == nil {
		return fmt.Errorf("ancillary handoff is nil")
	}
	if !strings.HasPrefix(strings.TrimSpace(h.AncillaryOrderItemID), "aoi-") {
		return fmt.Errorf("invalid ancillaryOrderItemId: %q", h.AncillaryOrderItemID)
	}
	if err := h.JourneyOrderID.Validate(); err != nil {
		return err
	}
	if err := h.TravelerRef.Validate(); err != nil {
		return err
	}
	if strings.TrimSpace(string(h.SegmentRef)) != "" {
		if err := h.SegmentRef.Validate(); err != nil {
			return err
		}
	}
	if strings.TrimSpace(h.CatalogItemID) == "" {
		return fmt.Errorf("catalogItemId is required")
	}
	if strings.TrimSpace(h.ServiceType) == "" {
		return fmt.Errorf("serviceType is required")
	}
	if strings.TrimSpace(string(h.EntitlementRef)) != "" {
		if err := h.EntitlementRef.Validate(); err != nil {
			return err
		}
	}
	return nil
}

func (f AncillaryFulfillmentFact) Validate() error {
	if !strings.HasPrefix(strings.TrimSpace(f.FulfillmentFactID), "aff-") {
		return fmt.Errorf("invalid fulfillmentFactId: %q", f.FulfillmentFactID)
	}
	if strings.TrimSpace(f.FactType) == "" {
		return fmt.Errorf("factType is required")
	}
	if strings.TrimSpace(f.PerformedBy) == "" {
		return fmt.Errorf("performedBy is required")
	}
	if strings.TrimSpace(f.IdempotencyRef) == "" {
		return fmt.Errorf("idempotencyRef is required")
	}
	if strings.TrimSpace(f.SourceEventID) == "" {
		return fmt.Errorf("sourceEventId is required")
	}
	if f.OccurredAt.IsZero() {
		return fmt.Errorf("occurredAt is required")
	}
	if f.RecordedAt.IsZero() {
		return fmt.Errorf("recordedAt is required")
	}
	return nil
}

// RideExecutionView is Fulfillment's local read model for Dispatch handoff facts.
type RideExecutionView struct {
	RideRequestID    string
	RideAssignmentID string
	RiderAccountID   string
	TravelerRef      TravelerRef
	PickupRef        string
	DropoffRef       string
	DriverRef        string
	VehicleRef       string
	Status           string
	DriverArrivedAt  *time.Time
	RideStartedAt    *time.Time
	RideEndedAt      *time.Time
	FinalFareRef     string
	Evidence         []RideExecutionEvidence
	CreatedAt        time.Time
	UpdatedAt        time.Time
}

type RideExecutionEvidence struct {
	EventType     string
	SourceEventID string
	OccurredAt    time.Time
}

func NewRideExecutionView(rideRequestID string, travelerRef TravelerRef) (*RideExecutionView, error) {
	now := timeNow().UTC()
	view := &RideExecutionView{RideRequestID: strings.TrimSpace(rideRequestID), TravelerRef: travelerRef, CreatedAt: now, UpdatedAt: now}
	return view, view.Validate()
}

func (v *RideExecutionView) DriverArrived(sourceEventID string, occurredAt time.Time, assignment RideAssignmentSnapshot) error {
	if err := v.applyRideSnapshot("DriverArrived", sourceEventID, "DRIVER_ARRIVED", occurredAt, assignment); err != nil {
		return err
	}
	at := occurredAt.UTC()
	v.DriverArrivedAt = &at
	return nil
}

func (v *RideExecutionView) RideStarted(sourceEventID string, occurredAt time.Time, assignment RideAssignmentSnapshot) error {
	if err := v.applyRideSnapshot("RideStarted", sourceEventID, "PICKED_UP", occurredAt, assignment); err != nil {
		return err
	}
	at := occurredAt.UTC()
	v.RideStartedAt = &at
	return nil
}

func (v *RideExecutionView) RideEnded(sourceEventID string, startedAt time.Time, endedAt time.Time, assignment RideAssignmentSnapshot, finalFareRef string) error {
	if err := v.applyRideSnapshot("RideEnded", sourceEventID, "COMPLETED", endedAt, assignment); err != nil {
		return err
	}
	if !startedAt.IsZero() && v.RideStartedAt == nil {
		start := startedAt.UTC()
		v.RideStartedAt = &start
	}
	end := endedAt.UTC()
	v.RideEndedAt = &end
	v.FinalFareRef = strings.TrimSpace(finalFareRef)
	return nil
}

type RideAssignmentSnapshot struct {
	RideAssignmentID string
	RiderAccountID   string
	TravelerRef      TravelerRef
	PickupRef        string
	DropoffRef       string
	DriverRef        string
	VehicleRef       string
}

func (v *RideExecutionView) applyRideSnapshot(eventType, sourceEventID, status string, occurredAt time.Time, assignment RideAssignmentSnapshot) error {
	if v == nil {
		return fmt.Errorf("ride execution view is nil")
	}
	if strings.TrimSpace(sourceEventID) == "" {
		return fmt.Errorf("sourceEventId is required")
	}
	if occurredAt.IsZero() {
		return fmt.Errorf("occurredAt is required")
	}
	if assignment.TravelerRef != "" && v.TravelerRef != "" && assignment.TravelerRef != v.TravelerRef {
		return fmt.Errorf("travelerRef mismatch")
	}
	v.RideAssignmentID = strings.TrimSpace(assignment.RideAssignmentID)
	v.RiderAccountID = strings.TrimSpace(assignment.RiderAccountID)
	if assignment.TravelerRef != "" {
		v.TravelerRef = assignment.TravelerRef
	}
	v.PickupRef = strings.TrimSpace(assignment.PickupRef)
	v.DropoffRef = strings.TrimSpace(assignment.DropoffRef)
	v.DriverRef = strings.TrimSpace(assignment.DriverRef)
	v.VehicleRef = strings.TrimSpace(assignment.VehicleRef)
	v.Status = status
	for _, evidence := range v.Evidence {
		if evidence.SourceEventID == sourceEventID {
			v.UpdatedAt = timeNow().UTC()
			return v.Validate()
		}
	}
	v.Evidence = append(v.Evidence, RideExecutionEvidence{EventType: eventType, SourceEventID: sourceEventID, OccurredAt: occurredAt.UTC()})
	v.UpdatedAt = timeNow().UTC()
	return v.Validate()
}

func (v *RideExecutionView) Validate() error {
	if v == nil {
		return fmt.Errorf("ride execution view is nil")
	}
	if !strings.HasPrefix(strings.TrimSpace(v.RideRequestID), "rrq-") {
		return fmt.Errorf("invalid rideRequestId: %q", v.RideRequestID)
	}
	if err := v.TravelerRef.Validate(); err != nil {
		return err
	}
	return nil
}
