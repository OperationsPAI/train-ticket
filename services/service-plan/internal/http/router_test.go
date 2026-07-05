package http

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
)

func TestRuntimeEndpoints(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := Router()

	for _, path := range []string{"/health", "/live", "/livez", "/ready", "/readyz", "/metadata"} {
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

func TestCreateScheduledServiceHappyPath(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := Router()
	body := `{"carrierId":"car-0194f2e0-7b3e-7610-0284-5c26e8b0c001","serviceNumber":"G1234","departureTime":"2026-07-05T10:30:00Z","arrivalTime":"2026-07-05T12:30:00Z","originNodeId":"node-a","destinationNodeId":"node-b"}`
	recorder := performJSON(router, http.MethodPost, "/api/v1/scheduled-services", body, "0194f2e0-7b3e-7610-0284-5c26e8b0c101")
	if recorder.Code != http.StatusCreated {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
	var response struct {
		ScheduledServiceRef string `json:"scheduledServiceRef"`
		ServiceNumber       string `json:"serviceNumber"`
		Status              string `json:"status"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("response is not json: %v", err)
	}
	if response.ScheduledServiceRef == "" || response.ServiceNumber != "G1234" || response.Status != "ACTIVE" {
		t.Fatalf("unexpected response: %#v", response)
	}
}

func TestScheduledServiceValidationFailureUsesCanonicalError(t *testing.T) {
	gin.SetMode(gin.TestMode)
	recorder := performJSON(Router(), http.MethodPost, "/api/v1/scheduled-services", `{"carrierId":"car-0194f2e0-7b3e-7610-0284-5c26e8b0c001"}`, "0194f2e0-7b3e-7610-0284-5c26e8b0c102")
	assertCanonicalError(t, recorder, http.StatusBadRequest, "VALIDATION_FAILED")
}

func TestInvalidIdempotencyKeyRejected(t *testing.T) {
	gin.SetMode(gin.TestMode)
	body := `{"carrierId":"car-0194f2e0-7b3e-7610-0284-5c26e8b0c001","serviceNumber":"G1234","departureTime":"2026-07-05T10:30:00Z","arrivalTime":"2026-07-05T12:30:00Z","originNodeId":"node-a","destinationNodeId":"node-b"}`
	recorder := performJSON(Router(), http.MethodPost, "/api/v1/scheduled-services", body, "idem-invalid")
	assertCanonicalError(t, recorder, http.StatusBadRequest, "VALIDATION_FAILED")
}

func TestInvalidCarrierIDRejected(t *testing.T) {
	gin.SetMode(gin.TestMode)
	body := `{"carrierId":"car-invalid","serviceNumber":"G1234","departureTime":"2026-07-05T10:30:00Z","arrivalTime":"2026-07-05T12:30:00Z","originNodeId":"node-a","destinationNodeId":"node-b"}`
	recorder := performJSON(Router(), http.MethodPost, "/api/v1/scheduled-services", body, "0194f2e0-7b3e-7610-0284-5c26e8b0c110")
	assertCanonicalError(t, recorder, http.StatusBadRequest, "VALIDATION_FAILED")
}

func TestCreateScheduledServiceIdempotentReplayReturnsOriginalResult(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := Router()
	body := `{"carrierId":"car-0194f2e0-7b3e-7610-0284-5c26e8b0c001","serviceNumber":"G1234","departureTime":"2026-07-05T10:30:00Z","arrivalTime":"2026-07-05T12:30:00Z","originNodeId":"node-a","destinationNodeId":"node-b"}`
	first := performJSON(router, http.MethodPost, "/api/v1/scheduled-services", body, "0194f2e0-7b3e-7610-0284-5c26e8b0c103")
	second := performJSON(router, http.MethodPost, "/api/v1/scheduled-services", body, "0194f2e0-7b3e-7610-0284-5c26e8b0c103")
	if first.Code != http.StatusCreated || second.Code != http.StatusCreated {
		t.Fatalf("unexpected statuses: %d %d", first.Code, second.Code)
	}
	if first.Body.String() != second.Body.String() {
		t.Fatalf("expected identical replay response\nfirst=%s\nsecond=%s", first.Body.String(), second.Body.String())
	}
	reused := performJSON(router, http.MethodPost, "/api/v1/scheduled-services", strings.Replace(body, "G1234", "G5678", 1), "0194f2e0-7b3e-7610-0284-5c26e8b0c103")
	assertCanonicalError(t, reused, http.StatusUnprocessableEntity, "IDEMPOTENCY_KEY_REUSED")
}

func TestGetAndListScheduledServicesHappyPath(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := Router()
	body := `{"serviceRef":"ss-0194f2e0-7b3e-7610-0284-5c26e8b0c201","carrierId":"car-0194f2e0-7b3e-7610-0284-5c26e8b0c001","serviceNumber":"G1234","departureTime":"2026-07-05T10:30:00Z","arrivalTime":"2026-07-05T12:30:00Z","originNodeId":"node-a","destinationNodeId":"node-b"}`
	performJSON(router, http.MethodPost, "/api/v1/scheduled-services", body, "0194f2e0-7b3e-7610-0284-5c26e8b0c104")

	getRecorder := performJSON(router, http.MethodGet, "/api/v1/scheduled-services/ss-0194f2e0-7b3e-7610-0284-5c26e8b0c201", "", "")
	if getRecorder.Code != http.StatusOK {
		t.Fatalf("unexpected get status: %d", getRecorder.Code)
	}
	var service map[string]any
	_ = json.Unmarshal(getRecorder.Body.Bytes(), &service)
	if service["scheduledServiceRef"] != "ss-0194f2e0-7b3e-7610-0284-5c26e8b0c201" || service["carrierId"] != "car-0194f2e0-7b3e-7610-0284-5c26e8b0c001" {
		t.Fatalf("unexpected service body: %#v", service)
	}

	listRecorder := performJSON(router, http.MethodGet, "/api/v1/scheduled-services?limit=20&offset=0&carrierId=car-0194f2e0-7b3e-7610-0284-5c26e8b0c001", "", "")
	if listRecorder.Code != http.StatusOK {
		t.Fatalf("unexpected list status: %d", listRecorder.Code)
	}
	var list struct {
		Items  []map[string]any `json:"items"`
		Total  int              `json:"total"`
		Limit  int              `json:"limit"`
		Offset int              `json:"offset"`
	}
	if err := json.Unmarshal(listRecorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("list response json: %v", err)
	}
	if list.Total != 1 || len(list.Items) != 1 || list.Limit != 20 || list.Offset != 0 {
		t.Fatalf("unexpected list response: %#v", list)
	}
}

func TestCreateServiceSegmentHappyPath(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := Router()
	serviceBody := `{"serviceRef":"ss-0194f2e0-7b3e-7610-0284-5c26e8b0c202","carrierId":"car-0194f2e0-7b3e-7610-0284-5c26e8b0c001","serviceNumber":"G1234","departureTime":"2026-07-05T10:30:00Z","arrivalTime":"2026-07-05T12:30:00Z","originNodeId":"node-a","destinationNodeId":"node-b"}`
	performJSON(router, http.MethodPost, "/api/v1/scheduled-services", serviceBody, "0194f2e0-7b3e-7610-0284-5c26e8b0c105")
	segmentBody := `{"scheduledServiceRef":"ss-0194f2e0-7b3e-7610-0284-5c26e8b0c202","originStopRef":"node-a","destinationStopRef":"node-b","departureTime":"2026-07-05T10:30:00Z","arrivalTime":"2026-07-05T12:30:00Z"}`
	recorder := performJSON(router, http.MethodPost, "/api/v1/service-segments", segmentBody, "0194f2e0-7b3e-7610-0284-5c26e8b0c106")
	if recorder.Code != http.StatusCreated {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
	var response struct {
		SegmentRef          string `json:"segmentRef"`
		ScheduledServiceRef string `json:"scheduledServiceRef"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("response is not json: %v", err)
	}
	if response.SegmentRef == "" || response.ScheduledServiceRef != "ss-0194f2e0-7b3e-7610-0284-5c26e8b0c202" {
		t.Fatalf("unexpected response: %#v", response)
	}
}

func TestCreateServiceSegmentValidationFailure(t *testing.T) {
	gin.SetMode(gin.TestMode)
	recorder := performJSON(Router(), http.MethodPost, "/api/v1/service-segments", `{"scheduledServiceRef":"ss-missing"}`, "0194f2e0-7b3e-7610-0284-5c26e8b0c107")
	assertCanonicalError(t, recorder, http.StatusBadRequest, "VALIDATION_FAILED")
}

func TestDomainInvariantViolationSurfacesAsDomainRuleViolation(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := Router()
	serviceBody := `{"serviceRef":"ss-0194f2e0-7b3e-7610-0284-5c26e8b0c203","carrierId":"car-0194f2e0-7b3e-7610-0284-5c26e8b0c001","serviceNumber":"G1234","departureTime":"2026-07-05T10:30:00Z","arrivalTime":"2026-07-05T12:30:00Z","originNodeId":"node-a","destinationNodeId":"node-b"}`
	performJSON(router, http.MethodPost, "/api/v1/scheduled-services", serviceBody, "0194f2e0-7b3e-7610-0284-5c26e8b0c108")
	segmentBody := `{"scheduledServiceRef":"ss-0194f2e0-7b3e-7610-0284-5c26e8b0c203","originStopRef":"node-a","destinationStopRef":"node-a","departureTime":"2026-07-05T10:30:00Z","arrivalTime":"2026-07-05T11:30:00Z"}`
	recorder := performJSON(router, http.MethodPost, "/api/v1/service-segments", segmentBody, "0194f2e0-7b3e-7610-0284-5c26e8b0c109")
	assertCanonicalError(t, recorder, http.StatusUnprocessableEntity, "DOMAIN_RULE_VIOLATION")
}

func performJSON(router http.Handler, method, path, body, idempotencyKey string) *httptest.ResponseRecorder {
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(method, path, strings.NewReader(body))
	if body != "" {
		request.Header.Set("Content-Type", "application/json")
	}
	request.Header.Set(goruntime.CorrelationIDHeader, "0194f2e0-7b3e-7610-0284-5c26e8b0c401")
	if idempotencyKey != "" {
		request.Header.Set("Idempotency-Key", idempotencyKey)
	}
	router.ServeHTTP(recorder, request)
	return recorder
}

func assertCanonicalError(t *testing.T, recorder *httptest.ResponseRecorder, status int, code string) {
	t.Helper()
	if recorder.Code != status {
		t.Fatalf("unexpected status: %d body=%s", recorder.Code, recorder.Body.String())
	}
	var response struct {
		Code          string         `json:"code"`
		Message       string         `json:"message"`
		CorrelationID string         `json:"correlationId"`
		Details       map[string]any `json:"details"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("error response is not json: %v", err)
	}
	if response.Code != code || response.Message == "" || response.CorrelationID == "" || response.Details == nil {
		t.Fatalf("unexpected error body: %#v", response)
	}
}
