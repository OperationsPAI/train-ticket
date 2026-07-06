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
	if err := validateSegmentReservationRequestedPayload(payload); err != nil {
		return FatalHandlerError(err)
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
		return TransientHandlerError(err)
	}
	return nil
}

func validateSegmentReservationRequestedPayload(payload SegmentReservationRequestedPayload) error {
	if err := ValidateSegmentBookingID(payload.SegmentBookingID); err != nil {
		return fmt.Errorf("invalid SegmentReservationRequested segmentBookingId: %w", err)
	}
	if err := validatePrefixedUUIDV7("journeyOrderId", payload.JourneyOrderID, "ord"); err != nil {
		return err
	}
	if err := validateRequiredToken("segmentRef", payload.SegmentRef); err != nil {
		return err
	}
	if err := validatePrefixedUUIDV7("travelerRef", payload.TravelerRef, "tvl"); err != nil {
		return err
	}
	// The messaging contract types idempotencyKey as an opaque string;
	// producers use composite business fingerprints, not bare UUIDs.
	if err := validateRequiredToken("idempotencyKey", payload.IdempotencyKey); err != nil {
		return err
	}
	return nil
}

func providerConfigRefForSegment(segmentRef string) string {
	trimmed := strings.TrimSpace(segmentRef)
	if provider, _, ok := strings.Cut(trimmed, ":"); ok {
		return strings.TrimSpace(provider)
	}
	// Plain seg-* references carry no provider prefix; phase 1 routes all
	// rail segments to the single configured provider.
	return "cr-rail"
}
