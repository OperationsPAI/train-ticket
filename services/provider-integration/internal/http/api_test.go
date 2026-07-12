package http

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"go.opentelemetry.io/otel/trace"

	"github.com/trainticket/greenfield/services/provider-integration/internal/application"
)

const (
	segmentBookingID1 = "sb-018f0000-0000-7000-8000-000000000101"
	segmentBookingID2 = "sb-018f0000-0000-7000-8000-000000000102"
)

type fakePublisher struct{ envelopes []application.EventEnvelope }

func (p *fakePublisher) Publish(_ context.Context, envelope application.EventEnvelope) error {
	p.envelopes = append(p.envelopes, envelope)
	return nil
}

func TestPublisherWrapsCorrectEnvelope(t *testing.T) {
	publisher := &fakePublisher{}
	service := application.NewInMemoryReservationService(publisher)
	_, err := service.RequestReservation(context.Background(), application.RequestProviderReservationCommand{
		SegmentBookingID: segmentBookingID1, ProviderConfigRef: "cr-rail", ReservationPayload: map[string]any{"seat": "1A"}, CorrelationID: testUUIDv7(3), CausationID: "cmd-" + testUUIDv7(4), IdempotencyKey: testUUIDv7(5),
	})
	if err != nil {
		t.Fatal(err)
	}
	envelope := publisher.envelopes[0]
	assertCanonicalPrefixedUUID(t, envelope.EventID, "evt-")
	assertCanonicalPrefixedUUID(t, envelope.CorrelationID, "corr-")
	assertCanonicalPrefixedUUID(t, envelope.CausationID, "cmd-")
	if envelope.EventType != "ProviderReservationConfirmed" || envelope.Producer != application.ProducerName || envelope.SchemaVersion != 1 {
		t.Fatalf("bad envelope metadata: %#v", envelope)
	}
	if envelope.OccurredAt.IsZero() {
		t.Fatalf("occurredAt is required")
	}
	body, err := json.Marshal(envelope)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(body), `"traceparent"`) {
		t.Fatalf("traceparent must be omitted without span context: %s", body)
	}
	var payload map[string]any
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		t.Fatalf("bad payload JSON: %v", err)
	}
	wantPayload := map[string]any{
		"segmentBookingId":   segmentBookingID1,
		"providerReference":  providerReferenceFor(segmentBookingID1),
		"normalizedEvidence": normalizedEvidenceFor(segmentBookingID1),
	}
	if len(payload) != len(wantPayload) {
		t.Fatalf("payload has non-contract fields: %#v", payload)
	}
	for field, want := range wantPayload {
		if payload[field] != want {
			t.Fatalf("payload[%s]=%#v, want %#v (payload %#v)", field, payload[field], want, payload)
		}
	}
}

func TestPublisherEnvelopeIncludesTraceparentFromContext(t *testing.T) {
	publisher := &fakePublisher{}
	service := application.NewInMemoryReservationService(publisher)
	ctx := trace.ContextWithSpanContext(context.Background(), mustSpanContext(t))
	_, err := service.RequestReservation(ctx, application.RequestProviderReservationCommand{
		SegmentBookingID: segmentBookingID1, ProviderConfigRef: "cr-rail", ReservationPayload: map[string]any{"seat": "1A"}, CorrelationID: testUUIDv7(3), IdempotencyKey: testUUIDv7(5),
	})
	if err != nil {
		t.Fatal(err)
	}
	if got := publisher.envelopes[0].Traceparent; got != wantTraceparent {
		t.Fatalf("traceparent mismatch: %q", got)
	}
}

func TestInboundSegmentReservationRequestedInvokesReservationFlow(t *testing.T) {
	publisher := &fakePublisher{}
	service := application.NewInMemoryReservationService(publisher)
	handler := application.DeduplicatingHandler(application.NewInMemoryConsumedEventLog(), application.NewInboundEventHandler(service))
	payload := validSegmentReservationRequestedPayload()
	payloadBytes, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	envelope := application.EventEnvelope{
		EventID:       "evt-" + testUUIDv7(42),
		EventType:     "SegmentReservationRequested",
		OccurredAt:    time.Date(2026, 7, 5, 0, 0, 0, 0, time.UTC),
		CorrelationID: "corr-" + testUUIDv7(43),
		CausationID:   "cmd-" + testUUIDv7(44),
		Producer:      "booking-orchestration",
		SchemaVersion: 1,
		Payload:       payloadBytes,
	}

	if err := handler(context.Background(), envelope); err != nil {
		t.Fatal(err)
	}
	if err := handler(context.Background(), envelope); err != nil {
		t.Fatal(err)
	}
	if len(publisher.envelopes) != 1 {
		t.Fatalf("expected one documented outcome event after duplicate delivery, got %d", len(publisher.envelopes))
	}
	outcome := publisher.envelopes[0]
	if outcome.EventType != "ProviderReservationConfirmed" {
		t.Fatalf("unexpected outcome event: %#v", outcome)
	}
	assertCanonicalPrefixedUUID(t, outcome.EventID, "evt-")
	if outcome.CausationID != envelope.EventID {
		t.Fatalf("causationId = %q, want inbound eventId %q", outcome.CausationID, envelope.EventID)
	}
	var outcomePayload map[string]any
	if err := json.Unmarshal(outcome.Payload, &outcomePayload); err != nil {
		t.Fatal(err)
	}
	if len(outcomePayload) != 3 || outcomePayload["segmentBookingId"] != segmentBookingID1 || outcomePayload["providerReference"] != providerReferenceFor(segmentBookingID1) || outcomePayload["normalizedEvidence"] != normalizedEvidenceFor(segmentBookingID1) {
		t.Fatalf("unexpected outcome payload: %#v", outcomePayload)
	}
}

func TestInboundSegmentReservationRequestedMissingSegmentRefIsFatal(t *testing.T) {
	publisher := &fakePublisher{}
	service := application.NewInMemoryReservationService(publisher)
	handler := application.NewInboundEventHandler(service)
	payload := validSegmentReservationRequestedPayload()
	delete(payload, "segmentRef")
	payloadBytes, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	envelope := application.EventEnvelope{
		EventID:       "evt-" + testUUIDv7(45),
		EventType:     "SegmentReservationRequested",
		OccurredAt:    time.Date(2026, 7, 5, 0, 0, 0, 0, time.UTC),
		CorrelationID: "corr-" + testUUIDv7(46),
		CausationID:   "cmd-" + testUUIDv7(47),
		Producer:      "booking-orchestration",
		SchemaVersion: 1,
		Payload:       payloadBytes,
	}

	err = handler(context.Background(), envelope)
	if err == nil {
		t.Fatal("expected fatal handler error")
	}
	var handlerErr application.HandlerError
	if !errors.As(err, &handlerErr) || handlerErr.Kind != application.HandlerErrorFatal {
		t.Fatalf("expected fatal handler error, got %T %[1]v", err)
	}
	if len(publisher.envelopes) != 0 {
		t.Fatalf("missing required ingress field must not publish outcome events, got %d", len(publisher.envelopes))
	}
}

func validSegmentReservationRequestedPayload() map[string]any {
	return map[string]any{
		"segmentBookingId": segmentBookingID1,
		"journeyOrderId":   "ord-" + testUUIDv7(201),
		"segmentRef":       "cr-rail:G1234:2026-07-05",
		"travelerRef":      "tvl-" + testUUIDv7(301),
		"idempotencyKey":   testUUIDv7(41),
	}
}

func providerReferenceFor(segmentBookingID string) string {
	return "prv-" + strings.TrimPrefix(segmentBookingID, "sb-")
}

func normalizedEvidenceFor(segmentBookingID string) string {
	return "raw-" + strings.TrimPrefix(segmentBookingID, "sb-") + ":" + providerReferenceFor(segmentBookingID)
}

func testUUIDv7(n int) string {
	return uuid.MustParse(fmt.Sprintf("018f0000-0000-7000-8000-%012d", n)).String()
}

func assertCanonicalPrefixedUUID(t *testing.T, value, prefix string) {
	t.Helper()
	if !strings.HasPrefix(value, prefix) {
		t.Fatalf("%q does not have prefix %q", value, prefix)
	}
	parsed, err := uuid.Parse(strings.TrimPrefix(value, prefix))
	if err != nil {
		t.Fatalf("%q does not contain a UUID: %v", value, err)
	}
	if parsed.Version() != 7 {
		t.Fatalf("%q UUID version = %d, want 7", value, parsed.Version())
	}
}

func TestSubscriberDeduplicatesDuplicateEventID(t *testing.T) {
	log := application.NewInMemoryConsumedEventLog()
	calls := 0
	handler := application.DeduplicatingHandler(log, func(context.Context, application.EventEnvelope) error {
		calls++
		return nil
	})
	envelope := application.EventEnvelope{EventID: "evt-" + testUUIDv7(6), EventType: "SupplierUpdated", Producer: "supplier-catalog", SchemaVersion: 1}
	if err := handler(context.Background(), envelope); err != nil {
		t.Fatal(err)
	}
	if err := handler(context.Background(), envelope); err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatalf("expected one handler call, got %d", calls)
	}
}

func TestInboundSegmentBookingCancelledCancelsExistingReservation(t *testing.T) {
	publisher := &fakePublisher{}
	service := application.NewInMemoryReservationService(publisher)
	handler := application.NewInboundEventHandler(service)
	requested := validSegmentReservationRequestedPayload()
	requestedBytes, err := json.Marshal(requested)
	if err != nil {
		t.Fatal(err)
	}
	if err := handler(context.Background(), application.EventEnvelope{
		EventID:       "evt-" + testUUIDv7(80),
		EventType:     "SegmentReservationRequested",
		OccurredAt:    time.Date(2026, 7, 8, 0, 0, 0, 0, time.UTC),
		CorrelationID: "corr-" + testUUIDv7(81),
		CausationID:   "cmd-" + testUUIDv7(82),
		Producer:      "booking-orchestration",
		SchemaVersion: 1,
		Payload:       requestedBytes,
	}); err != nil {
		t.Fatal(err)
	}
	cancelled, err := json.Marshal(map[string]any{
		"segmentBookingId": requested["segmentBookingId"],
		"reason":           "cancel late provider confirmation",
	})
	if err != nil {
		t.Fatal(err)
	}
	envelope := application.EventEnvelope{
		EventID:       "evt-" + testUUIDv7(83),
		EventType:     "SegmentBookingCancelled",
		OccurredAt:    time.Date(2026, 7, 8, 0, 1, 0, 0, time.UTC),
		CorrelationID: "corr-" + testUUIDv7(81),
		CausationID:   "evt-" + testUUIDv7(80),
		Producer:      "booking-orchestration",
		SchemaVersion: 1,
		Payload:       cancelled,
	}
	if err := handler(context.Background(), envelope); err != nil {
		t.Fatalf("expected cancellation of existing reservation to ack, got %v", err)
	}
	if err := handler(context.Background(), envelope); err != nil {
		t.Fatalf("expected duplicate cancellation to stay idempotent, got %v", err)
	}
}

func TestInboundSegmentBookingCancelledWithoutReservationAcksAsNoOp(t *testing.T) {
	publisher := &fakePublisher{}
	service := application.NewInMemoryReservationService(publisher)
	handler := application.NewInboundEventHandler(service)
	payloadBytes, err := json.Marshal(map[string]any{
		"segmentBookingId": "sb-" + testUUIDv7(84),
		"reason":           "capacity failed before provider reservation",
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := handler(context.Background(), application.EventEnvelope{
		EventID:       "evt-" + testUUIDv7(85),
		EventType:     "SegmentBookingCancelled",
		OccurredAt:    time.Date(2026, 7, 8, 0, 2, 0, 0, time.UTC),
		CorrelationID: "corr-" + testUUIDv7(86),
		CausationID:   "evt-" + testUUIDv7(87),
		Producer:      "booking-orchestration",
		SchemaVersion: 1,
		Payload:       payloadBytes,
	}); err != nil {
		t.Fatalf("expected no-reservation cancellation to ack as no-op, got %v", err)
	}
}

func TestInboundSegmentBookingCancelledMissingReasonIsFatal(t *testing.T) {
	publisher := &fakePublisher{}
	service := application.NewInMemoryReservationService(publisher)
	handler := application.NewInboundEventHandler(service)
	payloadBytes, err := json.Marshal(map[string]any{
		"segmentBookingId": "sb-" + testUUIDv7(88),
	})
	if err != nil {
		t.Fatal(err)
	}
	err = handler(context.Background(), application.EventEnvelope{
		EventID:       "evt-" + testUUIDv7(89),
		EventType:     "SegmentBookingCancelled",
		OccurredAt:    time.Date(2026, 7, 8, 0, 3, 0, 0, time.UTC),
		CorrelationID: "corr-" + testUUIDv7(90),
		CausationID:   "evt-" + testUUIDv7(91),
		Producer:      "booking-orchestration",
		SchemaVersion: 1,
		Payload:       payloadBytes,
	})
	if err == nil {
		t.Fatal("expected missing reason to be fatal")
	}
}

const wantTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

func mustSpanContext(t *testing.T) trace.SpanContext {
	t.Helper()
	traceID, err := trace.TraceIDFromHex("4bf92f3577b34da6a3ce929d0e0e4736")
	if err != nil {
		t.Fatal(err)
	}
	spanID, err := trace.SpanIDFromHex("00f067aa0ba902b7")
	if err != nil {
		t.Fatal(err)
	}
	spanContext := trace.NewSpanContext(trace.SpanContextConfig{TraceID: traceID, SpanID: spanID, TraceFlags: trace.FlagsSampled})
	if !spanContext.IsValid() {
		t.Fatal("invalid span context")
	}
	return spanContext
}

func TestInboundSegmentReservationRequestedMalformedSegmentBookingIDIsFatal(t *testing.T) {
	cases := []struct {
		name             string
		segmentBookingID string
	}{
		{name: "malformed", segmentBookingID: "sb-123"},
		{name: "uuid v4", segmentBookingID: "sb-a4712f6c-2205-4762-9ee7-118f8d1a4a0b"},
		{name: "missing prefix", segmentBookingID: "018f0000-0000-7000-8000-000000000101"},
	}
	for i, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			publisher := &fakePublisher{}
			service := application.NewInMemoryReservationService(publisher)
			handler := application.NewInboundEventHandler(service)
			payload := validSegmentReservationRequestedPayload()
			payload["segmentBookingId"] = tc.segmentBookingID
			payloadBytes, err := json.Marshal(payload)
			if err != nil {
				t.Fatal(err)
			}
			err = handler(context.Background(), application.EventEnvelope{
				EventID:       "evt-" + testUUIDv7(60+i),
				EventType:     "SegmentReservationRequested",
				OccurredAt:    time.Date(2026, 7, 8, 0, 0, 0, 0, time.UTC),
				CorrelationID: "corr-" + testUUIDv7(70+i),
				CausationID:   "cmd-" + testUUIDv7(80+i),
				Producer:      "booking-orchestration",
				SchemaVersion: 1,
				Payload:       payloadBytes,
			})
			if err == nil {
				t.Fatal("expected malformed segmentBookingId to be fatal")
			}
			if len(publisher.envelopes) != 0 {
				t.Fatalf("no outcome event may be published for rejected payloads, got %d", len(publisher.envelopes))
			}
		})
	}
}
