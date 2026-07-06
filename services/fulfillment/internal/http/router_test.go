package http

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/fulfillment/internal/application"
	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

func TestRuntimeEndpoints(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := Router()

	for _, path := range []string{"/health", "/healthz", "/live", "/livez", "/ready", "/readyz", "/metadata"} {
		t.Run(path, func(t *testing.T) {
			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodGet, path, nil)

			router.ServeHTTP(recorder, request)

			if recorder.Code != http.StatusOK {
				t.Fatalf("unexpected status for %s: %d", path, recorder.Code)
			}
			if recorder.Header().Get(goruntime.RequestIDHeader) == "" {
				t.Fatalf("missing request id header for %s", path)
			}
			if recorder.Header().Get(goruntime.CorrelationIDHeader) == "" {
				t.Fatalf("missing correlation id header for %s", path)
			}
		})
	}
}

func TestRequestAndCorrelationIDsPropagate(t *testing.T) {
	gin.SetMode(gin.TestMode)
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/health", nil)
	request.Header.Set(goruntime.RequestIDHeader, "request-123")
	request.Header.Set(goruntime.CorrelationIDHeader, "correlation-456")

	Router().ServeHTTP(recorder, request)

	if got := recorder.Header().Get(goruntime.RequestIDHeader); got != "request-123" {
		t.Fatalf("unexpected request id: %q", got)
	}
	if got := recorder.Header().Get(goruntime.CorrelationIDHeader); got != "correlation-456" {
		t.Fatalf("unexpected correlation id: %q", got)
	}
}

func TestMetadataEndpointReturnsServiceProfile(t *testing.T) {
	gin.SetMode(gin.TestMode)
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/metadata", nil)

	Router().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("unexpected status: %d", recorder.Code)
	}
	var body struct {
		ServiceID string `json:"serviceId"`
		Language  string `json:"language"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("metadata response is not json: %v", err)
	}
	if body.ServiceID == "" || body.Language != "golang" {
		t.Fatalf("unexpected metadata body: %#v", body)
	}
}

func TestFulfillmentHTTPVerifyBoardingHappyPathAndGet(t *testing.T) {
	gin.SetMode(gin.TestMode)
	service := application.NewService(application.NewInMemoryRepository(), application.NoopPublisher{}, application.NewInMemoryConsumedEventLog(), nil, nil)
	seedHTTPService(t, service, "ent-http1", "sb-http1", "ord-http1", "tvl-http1", "seg-http1")
	router := RouterWithService(service)
	body := `{"entitlementId":"ent-http1","segmentBookingId":"sb-http1","journeyOrderId":"ord-http1","travelerId":"tvl-http1","segmentRef":"seg-http1","source":"GATE","sourceEventId":"scan-1","occurredAt":"2026-07-05T10:00:00Z"}`
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/api/v1/fulfillment-records/boarding", strings.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Idempotency-Key", "0194f2e0-7b3e-7610-8284-5c26e8b0c001")
	request.Header.Set(goruntime.CorrelationIDHeader, "0194f2e0-7b3e-7610-0284-5c26e8b0c444")
	router.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
	var response struct {
		FulfillmentRecordID string `json:"fulfillmentRecordId"`
		Status              string `json:"status"`
		EntitlementID       string `json:"entitlementId"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.FulfillmentRecordID == "" || response.Status != "BOARDED" || response.EntitlementID != "ent-http1" {
		t.Fatalf("unexpected response: %#v", response)
	}

	get := httptest.NewRecorder()
	router.ServeHTTP(get, httptest.NewRequest(http.MethodGet, "/api/v1/fulfillment-records/"+response.FulfillmentRecordID, nil))
	if get.Code != http.StatusOK {
		t.Fatalf("unexpected get status: %d body=%s", get.Code, get.Body.String())
	}
}

func TestFulfillmentHTTPNoShowHappyPath(t *testing.T) {
	gin.SetMode(gin.TestMode)
	service := application.NewService(application.NewInMemoryRepository(), application.NoopPublisher{}, application.NewInMemoryConsumedEventLog(), nil, nil)
	seedHTTPService(t, service, "ent-noshow1", "sb-noshow1", "ord-noshow1", "tvl-noshow1", "seg-noshow1")
	router := RouterWithService(service)
	body := `{"entitlementId":"ent-noshow1","segmentBookingId":"sb-noshow1","journeyOrderId":"ord-noshow1","travelerId":"tvl-noshow1","segmentRef":"seg-noshow1","reason":"MANUAL_RECORD"}`
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/api/v1/fulfillment-records/no-show", strings.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Idempotency-Key", "0194f2e0-7b3e-7610-8284-5c26e8b0c002")
	router.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
	var response struct {
		FulfillmentRecordID string `json:"fulfillmentRecordId"`
		Status              string `json:"status"`
		AssessedAt          string `json:"assessedAt"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.FulfillmentRecordID == "" || response.Status != "NO_SHOW" || response.AssessedAt == "" {
		t.Fatalf("unexpected response: %#v", response)
	}
}

func TestFulfillmentHTTPRejectsMalformedIdempotencyKey(t *testing.T) {
	gin.SetMode(gin.TestMode)
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/api/v1/fulfillment-records/boarding", strings.NewReader(`{}`))
	request.Header.Set("Idempotency-Key", "not-a-uuid-v7")
	Router().ServeHTTP(recorder, request)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
	var body struct {
		Code string `json:"code"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.Code != "VALIDATION_FAILED" {
		t.Fatalf("unexpected error body: %#v", body)
	}
}

func TestFulfillmentHTTPValidationFailureBodyShape(t *testing.T) {
	gin.SetMode(gin.TestMode)
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/api/v1/fulfillment-records/boarding", strings.NewReader(`{"entitlementId":"bad"}`))
	request.Header.Set("Idempotency-Key", "0194f2e0-7b3e-7610-8284-5c26e8b0c003")
	Router().ServeHTTP(recorder, request)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
	var body struct {
		Code          string         `json:"code"`
		Message       string         `json:"message"`
		CorrelationID string         `json:"correlationId"`
		Details       map[string]any `json:"details"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.Code != "VALIDATION_FAILED" || body.CorrelationID == "" || body.Details == nil {
		t.Fatalf("unexpected error body: %#v", body)
	}
}

func TestFulfillmentHTTPIdempotentReplayAndReuse(t *testing.T) {
	gin.SetMode(gin.TestMode)
	service := application.NewService(application.NewInMemoryRepository(), application.NoopPublisher{}, application.NewInMemoryConsumedEventLog(), nil, nil)
	seedHTTPService(t, service, "ent-replay1", "sb-replay1", "ord-replay1", "tvl-replay1", "seg-replay1")
	router := RouterWithService(service)
	body := `{"entitlementId":"ent-replay1","segmentBookingId":"sb-replay1","journeyOrderId":"ord-replay1","travelerId":"tvl-replay1","segmentRef":"seg-replay1","source":"GATE","sourceEventId":"scan-1","occurredAt":"2026-07-05T10:00:00Z"}`
	makeReq := func(payload string) *httptest.ResponseRecorder {
		recorder := httptest.NewRecorder()
		request := httptest.NewRequest(http.MethodPost, "/api/v1/fulfillment-records/boarding", strings.NewReader(payload))
		request.Header.Set("Idempotency-Key", "0194f2e0-7b3e-7610-8284-5c26e8b0c004")
		router.ServeHTTP(recorder, request)
		return recorder
	}
	first := makeReq(body)
	second := makeReq(body)
	if first.Code != http.StatusCreated || second.Code != http.StatusCreated || first.Body.String() != second.Body.String() {
		t.Fatalf("replay mismatch: first=%d %s second=%d %s", first.Code, first.Body.String(), second.Code, second.Body.String())
	}
	changed := makeReq(strings.Replace(body, "scan-1", "scan-2", 1))
	if changed.Code != http.StatusUnprocessableEntity {
		t.Fatalf("expected 422 for reused key, got %d body=%s", changed.Code, changed.Body.String())
	}
}

func TestFulfillmentHTTPDomainRuleViolationSurfaces(t *testing.T) {
	gin.SetMode(gin.TestMode)
	service := application.NewService(application.NewInMemoryRepository(), application.NoopPublisher{}, application.NewInMemoryConsumedEventLog(), nil, nil)
	seedHTTPService(t, service, "ent-domain1", "sb-domain1", "ord-domain1", "tvl-domain1", "seg-domain1")
	router := RouterWithService(service)
	noShow := `{"entitlementId":"ent-domain1","segmentBookingId":"sb-domain1","journeyOrderId":"ord-domain1","travelerId":"tvl-domain1","segmentRef":"seg-domain1","reason":"MANUAL_RECORD"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/fulfillment-records/no-show", strings.NewReader(noShow))
	req.Header.Set("Idempotency-Key", "0194f2e0-7b3e-7610-8284-5c26e8b0c005")
	router.ServeHTTP(httptest.NewRecorder(), req)
	boarding := `{"entitlementId":"ent-domain1","segmentBookingId":"sb-domain1","journeyOrderId":"ord-domain1","travelerId":"tvl-domain1","segmentRef":"seg-domain1","source":"GATE","sourceEventId":"scan-after-noshow","occurredAt":"2026-07-05T10:00:00Z"}`
	recorder := httptest.NewRecorder()
	req2 := httptest.NewRequest(http.MethodPost, "/api/v1/fulfillment-records/boarding", strings.NewReader(boarding))
	req2.Header.Set("Idempotency-Key", "0194f2e0-7b3e-7610-8284-5c26e8b0c006")
	router.ServeHTTP(recorder, req2)
	if recorder.Code != http.StatusUnprocessableEntity {
		t.Fatalf("expected domain violation, got %d body=%s", recorder.Code, recorder.Body.String())
	}
	var body struct {
		Code string `json:"code"`
	}
	_ = json.Unmarshal(recorder.Body.Bytes(), &body)
	if body.Code != "DOMAIN_RULE_VIOLATION" {
		t.Fatalf("unexpected error code: %#v", body)
	}
}

func TestFulfillmentHTTPCausationDoesNotReuseIdempotencyKey(t *testing.T) {
	gin.SetMode(gin.TestMode)
	repo := application.NewInMemoryRepository()
	publisher := &httpRecordingPublisher{}
	service := application.NewService(repo, publisher, application.NewInMemoryConsumedEventLog(), func(prefix string) string {
		return prefix + "-00000000-0000-4000-8000-000000000099"
	}, nil)
	seedHTTPService(t, service, "ent-cause1", "sb-cause1", "ord-cause1", "tvl-cause1", "seg-cause1")
	router := RouterWithConfig(service, func() string { return "0194f2e0-7b3e-7610-0284-5c26e8b0c555" })
	body := `{"entitlementId":"ent-cause1","segmentBookingId":"sb-cause1","journeyOrderId":"ord-cause1","travelerId":"tvl-cause1","segmentRef":"seg-cause1","source":"GATE","sourceEventId":"scan-cause","occurredAt":"2026-07-05T10:00:00Z"}`
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/api/v1/fulfillment-records/boarding", strings.NewReader(body))
	request.Header.Set("Idempotency-Key", "0194f2e0-7b3e-7610-8284-5c26e8b0c007")
	router.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
	if len(publisher.envelopes) != 1 {
		t.Fatalf("expected published envelope")
	}
	if publisher.envelopes[0].CausationID == "0194f2e0-7b3e-7610-8284-5c26e8b0c007" || !strings.HasPrefix(publisher.envelopes[0].CausationID, "cmd-") {
		t.Fatalf("bad causation id: %s", publisher.envelopes[0].CausationID)
	}
}

func TestFulfillmentHTTPCompletionHappyPath(t *testing.T) {
	gin.SetMode(gin.TestMode)
	service := application.NewService(application.NewInMemoryRepository(), application.NoopPublisher{}, application.NewInMemoryConsumedEventLog(), nil, nil)
	seedHTTPService(t, service, "ent-complete1", "sb-complete1", "ord-complete1", "tvl-complete1", "seg-complete1")
	_, err := service.VerifyBoarding(context.Background(), application.VerifyBoardingCommand{EntitlementID: "ent-complete1", SegmentBookingID: "sb-complete1", JourneyOrderID: "ord-complete1", TravelerID: "tvl-complete1", SegmentRef: "seg-complete1", Source: domain.FulfillmentSourceGate, SourceEventID: "scan-complete", OccurredAt: time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)}, application.CommandMetadata{CorrelationID: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c0dd"})
	if err != nil {
		t.Fatalf("boarding: %v", err)
	}
	router := RouterWithService(service)
	body := `{"entitlementId":"ent-complete1","segmentBookingId":"sb-complete1","journeyOrderId":"ord-complete1","travelerId":"tvl-complete1","segmentRef":"seg-complete1","completionSource":"ARRIVAL","completedAt":"2026-07-05T12:00:00Z"}`
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/api/v1/fulfillment-records/completions", strings.NewReader(body))
	request.Header.Set("Idempotency-Key", "0194f2e0-7b3e-7610-8284-5c26e8b0c008")
	router.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
}

func seedHTTPService(t *testing.T, service *application.Service, entitlementID, segmentBookingID, journeyOrderID, travelerID, segmentRef string) {
	t.Helper()
	envelope := application.EventEnvelope{EventID: "evt-seed-" + entitlementID, EventType: "EntitlementIssued", Producer: "entitlement-ticketing", SchemaVersion: 1, Payload: json.RawMessage(`{"entitlementId":"` + entitlementID + `","segmentBookingId":"` + segmentBookingID + `","journeyOrderId":"` + journeyOrderID + `","travelerRef":"` + travelerID + `","segmentRef":"` + segmentRef + `"}`)}
	if err := service.HandleSubscribedEvent(context.Background(), envelope); err != nil {
		t.Fatalf("seed ticket: %v", err)
	}
}

type httpRecordingPublisher struct{ envelopes []application.EventEnvelope }

func (p *httpRecordingPublisher) Publish(_ context.Context, envelope application.EventEnvelope) error {
	p.envelopes = append(p.envelopes, envelope)
	return nil
}
