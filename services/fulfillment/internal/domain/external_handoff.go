package domain

import (
	"errors"
	"fmt"
	"strings"
	"time"
)

var ErrUnknownExternalStatus = errors.New("unknown external fulfillment status")

type ExternalFulfillmentHandoff struct {
	HandoffID      string                    `json:"handoffId"`
	Producer       string                    `json:"producer"`
	SourceRef      string                    `json:"sourceRef"`
	TravelerRef    string                    `json:"travelerRef,omitempty"`
	JourneyOrderID string                    `json:"journeyOrderId,omitempty"`
	SegmentRef     string                    `json:"segmentRef,omitempty"`
	EntitlementRef string                    `json:"entitlementRef,omitempty"`
	ServiceType    string                    `json:"serviceType,omitempty"`
	ProviderRef    string                    `json:"providerRef,omitempty"`
	Status         string                    `json:"status,omitempty"`
	FirstEventID   string                    `json:"firstEventId"`
	LastEventID    string                    `json:"lastEventId"`
	ReadyAt        *time.Time                `json:"readyAt,omitempty"`
	ArrivedAt      *time.Time                `json:"arrivedAt,omitempty"`
	StartedAt      *time.Time                `json:"startedAt,omitempty"`
	CompletedAt    *time.Time                `json:"completedAt,omitempty"`
	Facts          []ExternalFulfillmentFact `json:"facts"`
	CreatedAt      time.Time                 `json:"createdAt"`
	UpdatedAt      time.Time                 `json:"updatedAt"`
}

type ExternalFulfillmentFact struct {
	EventID        string    `json:"eventId"`
	FactID         string    `json:"factId"`
	FactType       string    `json:"factType"`
	ProviderRef    string    `json:"providerRef,omitempty"`
	PlaceRef       string    `json:"placeRef,omitempty"`
	IdempotencyRef string    `json:"idempotencyRef,omitempty"`
	OccurredAt     time.Time `json:"occurredAt"`
	RecordedAt     time.Time `json:"recordedAt"`
	PerformedBy    string    `json:"performedBy,omitempty"`
	Compensable    bool      `json:"compensable"`
	Status         string    `json:"status,omitempty"`
}

func NewExternalFulfillmentHandoff(producer, sourceRef, firstEventID string) (*ExternalFulfillmentHandoff, error) {
	producer = strings.TrimSpace(producer)
	sourceRef = strings.TrimSpace(sourceRef)
	firstEventID = strings.TrimSpace(firstEventID)
	if producer == "" {
		return nil, fmt.Errorf("external fulfillment handoff: producer is required")
	}
	if sourceRef == "" {
		return nil, fmt.Errorf("external fulfillment handoff: sourceRef is required")
	}
	if firstEventID == "" {
		return nil, fmt.Errorf("external fulfillment handoff: firstEventId is required")
	}
	now := timeNow().UTC()
	return &ExternalFulfillmentHandoff{
		HandoffID:    producer + ":" + sourceRef,
		Producer:     producer,
		SourceRef:    sourceRef,
		FirstEventID: firstEventID,
		LastEventID:  firstEventID,
		Facts:        []ExternalFulfillmentFact{},
		CreatedAt:    now,
		UpdatedAt:    now,
	}, nil
}

func (h *ExternalFulfillmentHandoff) ApplyAncillaryReady(eventID, journeyOrderID, travelerRef, segmentRef, entitlementRef, providerRef, status string, readyAt time.Time) error {
	if err := h.validateExisting(eventID); err != nil {
		return err
	}
	if status != "FULFILLMENT_READY" {
		return fmt.Errorf("%w: ancillary fulfillment-ready status %q", ErrUnknownExternalStatus, status)
	}
	if readyAt.IsZero() {
		return fmt.Errorf("external fulfillment handoff: readyAt is required")
	}
	h.JourneyOrderID = strings.TrimSpace(journeyOrderID)
	h.TravelerRef = strings.TrimSpace(travelerRef)
	h.SegmentRef = strings.TrimSpace(segmentRef)
	h.EntitlementRef = strings.TrimSpace(entitlementRef)
	h.ProviderRef = strings.TrimSpace(providerRef)
	h.Status = status
	ready := readyAt.UTC()
	h.ReadyAt = &ready
	h.markUpdated(eventID)
	return nil
}

func (h *ExternalFulfillmentHandoff) ApplyAncillaryFact(eventID, journeyOrderID, travelerRef, segmentRef, serviceType, status string, fact ExternalFulfillmentFact) error {
	if err := h.validateExisting(eventID); err != nil {
		return err
	}
	if strings.TrimSpace(status) == "" {
		return fmt.Errorf("external fulfillment handoff: status is required")
	}
	if err := fact.validate(); err != nil {
		return err
	}
	if h.hasFact(eventID, fact.FactID) {
		return nil
	}
	h.JourneyOrderID = strings.TrimSpace(journeyOrderID)
	h.TravelerRef = strings.TrimSpace(travelerRef)
	h.SegmentRef = strings.TrimSpace(segmentRef)
	h.ServiceType = strings.TrimSpace(serviceType)
	h.Status = strings.TrimSpace(status)
	if strings.TrimSpace(fact.ProviderRef) != "" {
		h.ProviderRef = strings.TrimSpace(fact.ProviderRef)
	}
	fact.EventID = strings.TrimSpace(eventID)
	fact.Status = h.Status
	fact.OccurredAt = fact.OccurredAt.UTC()
	fact.RecordedAt = fact.RecordedAt.UTC()
	h.Facts = append(h.Facts, fact)
	h.markUpdated(eventID)
	return nil
}

func (h *ExternalFulfillmentHandoff) ApplyDispatchMilestone(eventType, eventID, travelerRef, status string, occurredAt time.Time) error {
	if err := h.validateExisting(eventID); err != nil {
		return err
	}
	if occurredAt.IsZero() {
		return fmt.Errorf("external fulfillment handoff: milestone time is required")
	}
	status = strings.TrimSpace(status)
	occurred := occurredAt.UTC()
	switch eventType {
	case "DriverArrived":
		if status != "DRIVER_ARRIVED" {
			return fmt.Errorf("%w: DriverArrived status %q", ErrUnknownExternalStatus, status)
		}
		h.ArrivedAt = &occurred
	case "RideStarted":
		if status != "PICKED_UP" {
			return fmt.Errorf("%w: RideStarted status %q", ErrUnknownExternalStatus, status)
		}
		h.StartedAt = &occurred
	case "RideEnded":
		if status != "COMPLETED" {
			return fmt.Errorf("%w: RideEnded status %q", ErrUnknownExternalStatus, status)
		}
		h.CompletedAt = &occurred
	default:
		return fmt.Errorf("%w: dispatch event type %q", ErrUnknownExternalStatus, eventType)
	}
	h.TravelerRef = strings.TrimSpace(travelerRef)
	h.Status = status
	h.markUpdated(eventID)
	return nil
}

func (h *ExternalFulfillmentHandoff) validateExisting(eventID string) error {
	if h == nil {
		return fmt.Errorf("external fulfillment handoff is nil")
	}
	if strings.TrimSpace(h.Producer) == "" || strings.TrimSpace(h.SourceRef) == "" {
		return fmt.Errorf("external fulfillment handoff identity is incomplete")
	}
	if strings.TrimSpace(eventID) == "" {
		return fmt.Errorf("external fulfillment handoff: eventId is required")
	}
	return nil
}

func (h *ExternalFulfillmentHandoff) hasFact(eventID, factID string) bool {
	eventID = strings.TrimSpace(eventID)
	factID = strings.TrimSpace(factID)
	for _, existing := range h.Facts {
		if existing.EventID == eventID || (factID != "" && existing.FactID == factID) {
			return true
		}
	}
	return false
}

func (h *ExternalFulfillmentHandoff) markUpdated(eventID string) {
	now := timeNow().UTC()
	if h.CreatedAt.IsZero() {
		h.CreatedAt = now
	}
	if h.FirstEventID == "" {
		h.FirstEventID = strings.TrimSpace(eventID)
	}
	h.LastEventID = strings.TrimSpace(eventID)
	h.UpdatedAt = now
}

func (f ExternalFulfillmentFact) validate() error {
	if strings.TrimSpace(f.FactID) == "" {
		return fmt.Errorf("external fulfillment fact: fulfillmentFactId is required")
	}
	if strings.TrimSpace(f.FactType) == "" {
		return fmt.Errorf("external fulfillment fact: factType is required")
	}
	if f.OccurredAt.IsZero() {
		return fmt.Errorf("external fulfillment fact: occurredAt is required")
	}
	if f.RecordedAt.IsZero() {
		return fmt.Errorf("external fulfillment fact: recordedAt is required")
	}
	return nil
}
