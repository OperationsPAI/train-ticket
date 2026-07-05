package http

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/provider-integration/internal/application"
)

type fakePublisher struct{ envelopes []application.EventEnvelope }

func (p *fakePublisher) Publish(_ context.Context, envelope application.EventEnvelope) error {
	p.envelopes = append(p.envelopes, envelope)
	return nil
}

func TestRequestProviderReservationHappyPath(t *testing.T) {
	gin.SetMode(gin.TestMode)
	publisher := &fakePublisher{}
	router := RouterWithDependencies(application.NewInMemoryReservationService(publisher), application.NewIdempotencyStore())
	body := `{"segmentBookingId":"sb-123","providerConfigRef":"cr-rail","reservationPayload":{"seat":"1A"}}`

	recorder := post(router, "/api/v1/internal/provider-reservations", body, testUUIDv7(1))

	if recorder.Code != http.StatusAccepted {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
	var response application.ProviderReservationResult
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.SegmentBookingID != "sb-123" || response.Status != application.ReservationConfirmed || response.ProviderReference == "" {
		t.Fatalf("unexpected response: %#v", response)
	}
	if len(publisher.envelopes) != 1 {
		t.Fatalf("expected published event, got %d", len(publisher.envelopes))
	}
}

func TestCancelProviderReservationHappyPath(t *testing.T) {
	gin.SetMode(gin.TestMode)
	publisher := &fakePublisher{}
	service := application.NewInMemoryReservationService(publisher)
	router := RouterWithDependencies(service, application.NewIdempotencyStore())
	_ = post(router, "/api/v1/internal/provider-reservations", `{"segmentBookingId":"sb-123","providerConfigRef":"cr-rail","reservationPayload":{"seat":"1A"}}`, testUUIDv7(1))

	recorder := post(router, "/api/v1/internal/provider-reservations/sb-123/cancel", ``, testUUIDv7(2))

	if recorder.Code != http.StatusOK {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
	var response application.CancelProviderReservationResult
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.SegmentBookingID != "sb-123" || response.CancellationStatus != application.CancellationCancelled {
		t.Fatalf("unexpected response: %#v", response)
	}
}

func TestValidationFailureReturnsCanonicalBody(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := RouterWithDependencies(application.NewInMemoryReservationService(&fakePublisher{}), application.NewIdempotencyStore())

	recorder := post(router, "/api/v1/internal/provider-reservations", `{"providerConfigRef":"cr-rail"}`, testUUIDv7(1))

	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
	var body ErrorBody
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.Code != "VALIDATION_FAILED" || body.CorrelationID == "" || body.Details == nil {
		t.Fatalf("unexpected error body: %#v", body)
	}
}

func TestIdempotencyKeyHeaderValidation(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := RouterWithDependencies(application.NewInMemoryReservationService(&fakePublisher{}), application.NewIdempotencyStore())
	body := `{"segmentBookingId":"sb-123","providerConfigRef":"cr-rail","reservationPayload":{"seat":"1A"}}`

	missing := post(router, "/api/v1/internal/provider-reservations", body, "")
	if missing.Code != http.StatusBadRequest {
		t.Fatalf("missing header status: %d body=%s", missing.Code, missing.Body.String())
	}

	malformed := post(router, "/api/v1/internal/provider-reservations", body, "not-a-uuid")
	if malformed.Code != http.StatusBadRequest {
		t.Fatalf("malformed header status: %d body=%s", malformed.Code, malformed.Body.String())
	}
}

func TestIdempotencyKeyBodyFieldIsRejected(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := RouterWithDependencies(application.NewInMemoryReservationService(&fakePublisher{}), application.NewIdempotencyStore())

	recorder := post(router, "/api/v1/internal/provider-reservations", `{"segmentBookingId":"sb-123","providerConfigRef":"cr-rail","reservationPayload":{"seat":"1A"},"idempotencyKey":"`+testUUIDv7(1)+`"}`, testUUIDv7(1))

	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
}

func TestIdempotentReplayReturnsOriginalResult(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := RouterWithDependencies(application.NewInMemoryReservationService(&fakePublisher{}), application.NewIdempotencyStore())
	body := `{"segmentBookingId":"sb-123","providerConfigRef":"cr-rail","reservationPayload":{"seat":"1A"}}`

	first := post(router, "/api/v1/internal/provider-reservations", body, testUUIDv7(1))
	second := post(router, "/api/v1/internal/provider-reservations", body, testUUIDv7(1))

	if first.Code != second.Code || first.Body.String() != second.Body.String() {
		t.Fatalf("replay differed: first=%d %s second=%d %s", first.Code, first.Body.String(), second.Code, second.Body.String())
	}
}

func TestIdempotencyKeyReuseWithDifferentBodyFails(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := RouterWithDependencies(application.NewInMemoryReservationService(&fakePublisher{}), application.NewIdempotencyStore())
	_ = post(router, "/api/v1/internal/provider-reservations", `{"segmentBookingId":"sb-123","providerConfigRef":"cr-rail","reservationPayload":{"seat":"1A"}}`, testUUIDv7(1))

	recorder := post(router, "/api/v1/internal/provider-reservations", `{"segmentBookingId":"sb-124","providerConfigRef":"cr-rail","reservationPayload":{"seat":"1A"}}`, testUUIDv7(1))

	if recorder.Code != 422 {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
}

func TestPublisherWrapsCorrectEnvelope(t *testing.T) {
	publisher := &fakePublisher{}
	service := application.NewInMemoryReservationService(publisher)
	_, err := service.RequestReservation(context.Background(), application.RequestProviderReservationCommand{
		SegmentBookingID: "sb-123", ProviderConfigRef: "cr-rail", ReservationPayload: map[string]any{"seat": "1A"}, CorrelationID: testUUIDv7(3), CausationID: "cmd-" + testUUIDv7(4),
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
	if _, err := time.Parse(time.RFC3339Nano, envelope.OccurredAt); err != nil {
		t.Fatalf("occurredAt is not RFC3339: %q", envelope.OccurredAt)
	}
	var payload map[string]any
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		t.Fatalf("bad payload JSON: %v", err)
	}
	wantPayload := map[string]any{
		"segmentBookingId":   "sb-123",
		"providerReference":  "prv-123",
		"normalizedEvidence": "normalized provider confirmation",
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
	envelope := application.EventEnvelope{EventID: "evt-1", EventType: "SupplierUpdated", Producer: "supplier-catalog", SchemaVersion: 1}
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

func post(router http.Handler, path, body, idempotencyKey string) *httptest.ResponseRecorder {
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, path, bytes.NewBufferString(body))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set(goruntime.CorrelationIDHeader, testUUIDv7(9))
	if idempotencyKey != "" {
		request.Header.Set("Idempotency-Key", idempotencyKey)
	}
	router.ServeHTTP(recorder, request)
	return recorder
}
