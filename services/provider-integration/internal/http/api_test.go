package http

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
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

func TestRequestProviderReservationHappyPath(t *testing.T) {
	gin.SetMode(gin.TestMode)
	publisher := &fakePublisher{}
	router := RouterWithDependencies(application.NewInMemoryReservationService(publisher), idempotency.NewMemoryStore())
	body := reservationRequestBody(segmentBookingID1, "1A")

	recorder := post(router, "/api/v1/internal/provider-reservations", body, testUUIDv7(1))

	if recorder.Code != http.StatusAccepted {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
	var response application.ProviderReservationResult
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.SegmentBookingID != segmentBookingID1 || response.Status != application.ReservationConfirmed || response.ProviderReference == "" {
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
	router := RouterWithDependencies(service, idempotency.NewMemoryStore())
	_ = post(router, "/api/v1/internal/provider-reservations", reservationRequestBody(segmentBookingID1, "1A"), testUUIDv7(1))

	recorder := post(router, "/api/v1/internal/provider-reservations/"+segmentBookingID1+"/cancel", ``, testUUIDv7(2))

	if recorder.Code != http.StatusOK {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
	var response application.CancelProviderReservationResult
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.SegmentBookingID != segmentBookingID1 || response.CancellationStatus != application.CancellationCancelled {
		t.Fatalf("unexpected response: %#v", response)
	}
	if len(publisher.envelopes) != 1 {
		t.Fatalf("cancel must not publish non-contract events, got %d envelopes", len(publisher.envelopes))
	}
}

func TestValidationFailureReturnsCanonicalBody(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := RouterWithDependencies(application.NewInMemoryReservationService(&fakePublisher{}), idempotency.NewMemoryStore())

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

func TestDomainRuleViolationSurfacesCanonicalBody(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := RouterWithDependencies(application.NewInMemoryReservationService(&fakePublisher{}), idempotency.NewMemoryStore())

	recorder := post(router, "/api/v1/internal/provider-reservations", reservationRequestBodyWithProvider(segmentBookingID1, "bad provider", "1A"), testUUIDv7(1))

	if recorder.Code != 422 {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
	var body ErrorBody
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.Code != "DOMAIN_RULE_VIOLATION" {
		t.Fatalf("unexpected error body: %#v", body)
	}
}

func TestSegmentBookingIDValidation(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := RouterWithDependencies(application.NewInMemoryReservationService(&fakePublisher{}), idempotency.NewMemoryStore())

	cases := []struct {
		name string
		body string
	}{
		{name: "malformed", body: reservationRequestBody("sb-123", "1A")},
		{name: "uuid v4", body: reservationRequestBody("sb-"+uuid.New().String(), "1A")},
	}
	for i, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			recorder := post(router, "/api/v1/internal/provider-reservations", tc.body, testUUIDv7(20+i))
			if recorder.Code != http.StatusBadRequest {
				t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
			}
			var body ErrorBody
			if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
				t.Fatal(err)
			}
			if body.Code != "VALIDATION_FAILED" {
				t.Fatalf("unexpected error body: %#v", body)
			}
		})
	}
}

func TestCancelSegmentBookingIDValidation(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := RouterWithDependencies(application.NewInMemoryReservationService(&fakePublisher{}), idempotency.NewMemoryStore())

	cases := []struct {
		name string
		id   string
	}{
		{name: "malformed", id: "sb-123"},
		{name: "uuid v4", id: "sb-" + uuid.New().String()},
	}
	for i, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			recorder := post(router, "/api/v1/internal/provider-reservations/"+tc.id+"/cancel", ``, testUUIDv7(30+i))
			if recorder.Code != http.StatusBadRequest {
				t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
			}
			var body ErrorBody
			if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
				t.Fatal(err)
			}
			if body.Code != "VALIDATION_FAILED" {
				t.Fatalf("unexpected error body: %#v", body)
			}
		})
	}
}

func TestIdempotencyKeyHeaderValidation(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := RouterWithDependencies(application.NewInMemoryReservationService(&fakePublisher{}), idempotency.NewMemoryStore())
	body := reservationRequestBody(segmentBookingID1, "1A")

	missing := post(router, "/api/v1/internal/provider-reservations", body, "")
	if missing.Code != http.StatusBadRequest {
		t.Fatalf("missing header status: %d body=%s", missing.Code, missing.Body.String())
	}

	malformed := post(router, "/api/v1/internal/provider-reservations", body, "not-a-uuid")
	if malformed.Code != http.StatusBadRequest {
		t.Fatalf("malformed header status: %d body=%s", malformed.Code, malformed.Body.String())
	}

	version4 := post(router, "/api/v1/internal/provider-reservations", body, uuid.New().String())
	if version4.Code != http.StatusBadRequest {
		t.Fatalf("v4 header status: %d body=%s", version4.Code, version4.Body.String())
	}
}

func TestIdempotencyKeyBodyFieldIsRejected(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := RouterWithDependencies(application.NewInMemoryReservationService(&fakePublisher{}), idempotency.NewMemoryStore())

	recorder := post(router, "/api/v1/internal/provider-reservations", reservationRequestBodyWithIdempotencyKey(segmentBookingID1, testUUIDv7(1)), testUUIDv7(1))

	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
}

func TestIdempotentReplayReturnsOriginalResult(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := RouterWithDependencies(application.NewInMemoryReservationService(&fakePublisher{}), idempotency.NewMemoryStore())
	body := reservationRequestBody(segmentBookingID1, "1A")

	first := post(router, "/api/v1/internal/provider-reservations", body, testUUIDv7(1))
	second := post(router, "/api/v1/internal/provider-reservations", body, testUUIDv7(1))

	if first.Code != second.Code || first.Body.String() != second.Body.String() {
		t.Fatalf("replay differed: first=%d %s second=%d %s", first.Code, first.Body.String(), second.Code, second.Body.String())
	}
}

func TestIdempotencyKeyReuseWithDifferentBodyFails(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := RouterWithDependencies(application.NewInMemoryReservationService(&fakePublisher{}), idempotency.NewMemoryStore())
	_ = post(router, "/api/v1/internal/provider-reservations", reservationRequestBody(segmentBookingID1, "1A"), testUUIDv7(1))

	recorder := post(router, "/api/v1/internal/provider-reservations", reservationRequestBody(segmentBookingID2, "1A"), testUUIDv7(1))

	if recorder.Code != 422 {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
}

func TestIdempotencyKeyReuseAcrossResolvedCancelPathsFails(t *testing.T) {
	gin.SetMode(gin.TestMode)
	service := application.NewInMemoryReservationService(&fakePublisher{})
	router := RouterWithDependencies(service, idempotency.NewMemoryStore())
	_ = post(router, "/api/v1/internal/provider-reservations", reservationRequestBody(segmentBookingID1, "1A"), testUUIDv7(1))
	_ = post(router, "/api/v1/internal/provider-reservations", reservationRequestBody(segmentBookingID2, "2A"), testUUIDv7(2))

	first := post(router, "/api/v1/internal/provider-reservations/"+segmentBookingID1+"/cancel", ``, testUUIDv7(3))
	second := post(router, "/api/v1/internal/provider-reservations/"+segmentBookingID2+"/cancel", ``, testUUIDv7(3))

	if first.Code != http.StatusOK {
		t.Fatalf("first cancel status: %d body=%s", first.Code, first.Body.String())
	}
	if second.Code != 422 {
		t.Fatalf("expected idempotency conflict for reused key on different resource, got %d body=%s", second.Code, second.Body.String())
	}
	var body ErrorBody
	if err := json.Unmarshal(second.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.Code != "IDEMPOTENCY_KEY_REUSED" {
		t.Fatalf("unexpected error body: %#v", body)
	}
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

func reservationRequestBody(segmentBookingID, seat string) string {
	return reservationRequestBodyWithProvider(segmentBookingID, "cr-rail", seat)
}

func reservationRequestBodyWithProvider(segmentBookingID, providerConfigRef, seat string) string {
	return fmt.Sprintf(`{"segmentBookingId":%q,"providerConfigRef":%q,"reservationPayload":{"seat":%q}}`, segmentBookingID, providerConfigRef, seat)
}

func reservationRequestBodyWithIdempotencyKey(segmentBookingID, idempotencyKey string) string {
	return fmt.Sprintf(`{"segmentBookingId":%q,"providerConfigRef":"cr-rail","reservationPayload":{"seat":"1A"},"idempotencyKey":%q}`, segmentBookingID, idempotencyKey)
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
