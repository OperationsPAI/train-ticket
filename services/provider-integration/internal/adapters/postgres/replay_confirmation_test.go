package postgres

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/trainticket/greenfield/services/provider-integration/internal/application"
)

type capturedPublisher struct {
	envelopes []application.EventEnvelope
}

func (p *capturedPublisher) Publish(_ context.Context, envelope application.EventEnvelope) error {
	p.envelopes = append(p.envelopes, envelope)
	return nil
}

// TestAnExistingReservationIsAnsweredAgain is the regression that mattered in
// the shop: the booking asks for its reservation a second time because it never
// learned the outcome, and the answer has to be the confirmation it is waiting
// for. Returning the stored reservation silently left segment bookings in
// HOLDING with their capacity hold held.
func TestAnExistingReservationIsAnsweredAgain(t *testing.T) {
	publisher := &capturedPublisher{}
	service := &ReservationService{publisher: publisher}
	existing := application.ProviderReservationResult{
		SegmentBookingID:   "sb-1",
		ProviderReference:  "prv-1",
		NormalizedEvidence: "provider confirmed",
	}
	cmd := application.RequestProviderReservationCommand{SegmentBookingID: "sb-1",
		CorrelationID: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0d301", CausationID: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0d302"}

	if err := service.republishConfirmation(context.Background(), existing, cmd); err != nil {
		t.Fatalf("republish: %v", err)
	}

	if len(publisher.envelopes) != 1 {
		t.Fatalf("expected one confirmation, got %d", len(publisher.envelopes))
	}
	envelope := publisher.envelopes[0]
	if envelope.EventType != "ProviderReservationConfirmed" {
		t.Fatalf("event type = %q", envelope.EventType)
	}
	var payload map[string]any
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		t.Fatalf("payload: %v", err)
	}
	if payload["segmentBookingId"] != "sb-1" {
		t.Fatalf("payload must name the reservation being answered: %v", payload)
	}
	if payload["normalizedEvidence"] != "provider confirmed" {
		t.Fatalf("payload must carry the recorded evidence: %v", payload)
	}
}

func TestWithoutAPublisherAReplayIsStillAnswered(t *testing.T) {
	// A service wired without a publisher (tests, degraded startup) must not
	// fail the replay: the caller still gets its reservation back.
	service := &ReservationService{}
	existing := application.ProviderReservationResult{SegmentBookingID: "sb-1"}
	if err := service.republishConfirmation(context.Background(), existing, application.RequestProviderReservationCommand{SegmentBookingID: "sb-1"}); err != nil {
		t.Fatalf("a missing publisher must not fail the answer: %v", err)
	}
}
