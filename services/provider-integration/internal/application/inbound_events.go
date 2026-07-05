package application

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
)

const segmentReservationRequested = "SegmentReservationRequested"

type SegmentReservationRequestedPayload struct {
	SegmentBookingID string `json:"segmentBookingId"`
	JourneyOrderID   string `json:"journeyOrderId"`
	SegmentRef       string `json:"segmentRef"`
	TravelerRef      string `json:"travelerRef"`
	IdempotencyKey   string `json:"idempotencyKey"`
}

func NewInboundEventHandler(reservations ProviderReservationService) EventHandler {
	return func(ctx context.Context, envelope EventEnvelope) error {
		switch envelope.EventType {
		case segmentReservationRequested:
			return handleSegmentReservationRequested(ctx, reservations, envelope)
		default:
			return nil
		}
	}
}

func handleSegmentReservationRequested(ctx context.Context, reservations ProviderReservationService, envelope EventEnvelope) error {
	if reservations == nil {
		return TransientHandlerError(fmt.Errorf("reservation service is not configured"))
	}
	var payload SegmentReservationRequestedPayload
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return FatalHandlerError(fmt.Errorf("invalid SegmentReservationRequested payload: %w", err))
	}
	if strings.TrimSpace(payload.IdempotencyKey) == "" {
		return FatalHandlerError(fmt.Errorf("SegmentReservationRequested idempotencyKey is required"))
	}
	cmd := RequestProviderReservationCommand{
		SegmentBookingID:  strings.TrimSpace(payload.SegmentBookingID),
		ProviderConfigRef: providerConfigRefForSegment(payload.SegmentRef),
		ReservationPayload: map[string]any{
			"journeyOrderId": strings.TrimSpace(payload.JourneyOrderID),
			"segmentRef":     strings.TrimSpace(payload.SegmentRef),
			"travelerRef":    strings.TrimSpace(payload.TravelerRef),
		},
		CorrelationID:  envelope.CorrelationID,
		CausationID:    envelope.EventID,
		IdempotencyKey: strings.TrimSpace(payload.IdempotencyKey),
	}
	if _, err := reservations.RequestReservation(ctx, cmd); err != nil {
		if strings.TrimSpace(payload.SegmentBookingID) == "" {
			return FatalHandlerError(err)
		}
		return TransientHandlerError(err)
	}
	return nil
}

func providerConfigRefForSegment(segmentRef string) string {
	trimmed := strings.TrimSpace(segmentRef)
	if trimmed == "" {
		return "cr-rail"
	}
	if provider, _, ok := strings.Cut(trimmed, ":"); ok && strings.TrimSpace(provider) != "" {
		return strings.TrimSpace(provider)
	}
	return "cr-rail"
}
