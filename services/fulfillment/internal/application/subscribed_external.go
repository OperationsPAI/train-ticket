package application

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

func (s *Service) applyAncillaryFulfillmentReady(ctx context.Context, envelope EventEnvelope) error {
	var event struct {
		AncillaryOrderItemID string    `json:"ancillaryOrderItemId"`
		JourneyOrderID       string    `json:"journeyOrderId"`
		TravelerRef          string    `json:"travelerRef"`
		SegmentRef           string    `json:"segmentRef"`
		EntitlementRef       string    `json:"entitlementRef"`
		ProviderRef          string    `json:"providerRef"`
		Status               string    `json:"status"`
		ReadyAt              time.Time `json:"readyAt"`
	}
	if err := json.Unmarshal(envelope.Payload, &event); err != nil {
		return fmt.Errorf("%w: invalid AncillaryOrderItemFulfillmentReady payload: %v", ErrDomainRuleViolation, err)
	}
	if strings.TrimSpace(event.AncillaryOrderItemID) == "" || event.ReadyAt.IsZero() {
		return fmt.Errorf("%w: invalid AncillaryOrderItemFulfillmentReady payload", ErrDomainRuleViolation)
	}
	handoff, err := s.externalHandoff(ctx, envelope.Producer, event.AncillaryOrderItemID, envelope.EventID)
	if err != nil {
		return err
	}
	if err := handoff.ApplyAncillaryReady(envelope.EventID, event.JourneyOrderID, event.TravelerRef, event.SegmentRef, event.EntitlementRef, event.ProviderRef, event.Status, event.ReadyAt); err != nil {
		if errors.Is(err, domain.ErrUnknownExternalStatus) {
			return nil
		}
		return fmt.Errorf("%w: %v", ErrDomainRuleViolation, err)
	}
	return s.saveExternalHandoff(ctx, handoff)
}

func (s *Service) applyAncillaryFulfillmentFactRecorded(ctx context.Context, envelope EventEnvelope) error {
	var event struct {
		AncillaryOrderItemID string `json:"ancillaryOrderItemId"`
		JourneyOrderID       string `json:"journeyOrderId"`
		TravelerRef          string `json:"travelerRef"`
		SegmentRef           string `json:"segmentRef"`
		ServiceType          string `json:"serviceType"`
		Status               string `json:"status"`
		FulfillmentFact      struct {
			FulfillmentFactID string    `json:"fulfillmentFactId"`
			FactType          string    `json:"factType"`
			ProviderRef       string    `json:"providerRef"`
			PlaceRef          string    `json:"placeRef"`
			OccurredAt        time.Time `json:"occurredAt"`
			RecordedAt        time.Time `json:"recordedAt"`
			PerformedBy       string    `json:"performedBy"`
			IdempotencyRef    string    `json:"idempotencyRef"`
			Compensable       bool      `json:"compensable"`
		} `json:"fulfillmentFact"`
	}
	if err := json.Unmarshal(envelope.Payload, &event); err != nil {
		return fmt.Errorf("%w: invalid AncillaryFulfillmentFactRecorded payload: %v", ErrDomainRuleViolation, err)
	}
	if strings.TrimSpace(event.AncillaryOrderItemID) == "" {
		return fmt.Errorf("%w: invalid AncillaryFulfillmentFactRecorded payload", ErrDomainRuleViolation)
	}
	handoff, err := s.externalHandoff(ctx, envelope.Producer, event.AncillaryOrderItemID, envelope.EventID)
	if err != nil {
		return err
	}
	fact := domain.ExternalFulfillmentFact{
		FactID:         event.FulfillmentFact.FulfillmentFactID,
		FactType:       event.FulfillmentFact.FactType,
		ProviderRef:    event.FulfillmentFact.ProviderRef,
		PlaceRef:       event.FulfillmentFact.PlaceRef,
		IdempotencyRef: event.FulfillmentFact.IdempotencyRef,
		OccurredAt:     event.FulfillmentFact.OccurredAt,
		RecordedAt:     event.FulfillmentFact.RecordedAt,
		PerformedBy:    event.FulfillmentFact.PerformedBy,
		Compensable:    event.FulfillmentFact.Compensable,
	}
	if err := handoff.ApplyAncillaryFact(envelope.EventID, event.JourneyOrderID, event.TravelerRef, event.SegmentRef, event.ServiceType, event.Status, fact); err != nil {
		return fmt.Errorf("%w: %v", ErrDomainRuleViolation, err)
	}
	return s.saveExternalHandoff(ctx, handoff)
}

func (s *Service) applyDispatchFulfillmentMilestone(ctx context.Context, envelope EventEnvelope) error {
	var event struct {
		RideRequestID    string    `json:"rideRequestId"`
		RideAssignmentID string    `json:"rideAssignmentId"`
		TravelerRef      string    `json:"travelerRef"`
		ArrivedAt        time.Time `json:"arrivedAt"`
		StartedAt        time.Time `json:"startedAt"`
		EndedAt          time.Time `json:"endedAt"`
		Status           string    `json:"status"`
	}
	if err := json.Unmarshal(envelope.Payload, &event); err != nil {
		return fmt.Errorf("%w: invalid %s payload: %v", ErrDomainRuleViolation, envelope.EventType, err)
	}
	if strings.TrimSpace(event.RideRequestID) == "" {
		return fmt.Errorf("%w: invalid %s payload", ErrDomainRuleViolation, envelope.EventType)
	}
	occurredAt := event.ArrivedAt
	if envelope.EventType == "RideStarted" {
		occurredAt = event.StartedAt
	} else if envelope.EventType == "RideEnded" {
		occurredAt = event.EndedAt
	}
	handoff, err := s.externalHandoff(ctx, envelope.Producer, event.RideRequestID, envelope.EventID)
	if err != nil {
		return err
	}
	if strings.TrimSpace(event.RideAssignmentID) != "" {
		handoff.ProviderRef = strings.TrimSpace(event.RideAssignmentID)
	}
	if err := handoff.ApplyDispatchMilestone(envelope.EventType, envelope.EventID, event.TravelerRef, event.Status, occurredAt); err != nil {
		if errors.Is(err, domain.ErrUnknownExternalStatus) {
			return nil
		}
		return fmt.Errorf("%w: %v", ErrDomainRuleViolation, err)
	}
	return s.saveExternalHandoff(ctx, handoff)
}

func (s *Service) externalHandoff(ctx context.Context, producer string, sourceRef string, eventID string) (*domain.ExternalFulfillmentHandoff, error) {
	producer = strings.TrimSpace(producer)
	sourceRef = strings.TrimSpace(sourceRef)
	if s.external != nil {
		handoff, err := s.external.FindExternalFulfillmentHandoff(ctx, producer, sourceRef)
		if err == nil {
			return handoff, nil
		}
		if !errors.Is(err, ErrNotFound) {
			return nil, err
		}
		return domain.NewExternalFulfillmentHandoff(producer, sourceRef, eventID)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	key := externalHandoffKey(producer, sourceRef)
	if existing, ok := s.externalMem[key]; ok {
		return cloneExternalHandoff(existing), nil
	}
	return domain.NewExternalFulfillmentHandoff(producer, sourceRef, eventID)
}

func (s *Service) saveExternalHandoff(ctx context.Context, handoff *domain.ExternalFulfillmentHandoff) error {
	if s.external != nil {
		return s.external.SaveExternalFulfillmentHandoff(ctx, handoff)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.externalMem[externalHandoffKey(handoff.Producer, handoff.SourceRef)] = cloneExternalHandoff(handoff)
	return nil
}

func externalHandoffKey(producer, sourceRef string) string {
	return strings.TrimSpace(producer) + "|" + strings.TrimSpace(sourceRef)
}

func cloneExternalHandoff(handoff *domain.ExternalFulfillmentHandoff) *domain.ExternalFulfillmentHandoff {
	if handoff == nil {
		return nil
	}
	copy := *handoff
	if handoff.ReadyAt != nil {
		t := *handoff.ReadyAt
		copy.ReadyAt = &t
	}
	if handoff.ArrivedAt != nil {
		t := *handoff.ArrivedAt
		copy.ArrivedAt = &t
	}
	if handoff.StartedAt != nil {
		t := *handoff.StartedAt
		copy.StartedAt = &t
	}
	if handoff.CompletedAt != nil {
		t := *handoff.CompletedAt
		copy.CompletedAt = &t
	}
	copy.Facts = append([]domain.ExternalFulfillmentFact(nil), handoff.Facts...)
	return &copy
}
