package http

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/gin-gonic/gin"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"

	"github.com/trainticket/greenfield/services/supplier-catalog/internal/application"
)

type fakePublisher struct {
	mu        sync.Mutex
	envelopes []application.EventEnvelope
	failures  int
}

func (p *fakePublisher) Publish(_ context.Context, envelope application.EventEnvelope) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.failures > 0 {
		p.failures--
		return errors.New("redis unavailable")
	}
	p.envelopes = append(p.envelopes, envelope)
	return nil
}

func (p *fakePublisher) all() []application.EventEnvelope {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := make([]application.EventEnvelope, len(p.envelopes))
	copy(out, p.envelopes)
	return out
}

func newTestRouter() (*gin.Engine, *fakePublisher) {
	gin.SetMode(gin.TestMode)
	publisher := &fakePublisher{}
	return RouterWithService(application.NewService(publisher)), publisher
}

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

func TestRegisterSupplierHappyPath(t *testing.T) {
	router, publisher := newTestRouter()
	response := doJSON(router, http.MethodPost, "/api/v1/suppliers", `{"legalName":"China Railway","brandName":"CR","supplierCode":"CR"}`, "0194f2e0-7b3e-7000-8000-000000000001")
	if response.Code != http.StatusCreated {
		t.Fatalf("unexpected status %d body %s", response.Code, response.Body.String())
	}
	var body map[string]interface{}
	mustJSON(t, response.Body.Bytes(), &body)
	if !strings.HasPrefix(body["supplierId"].(string), "sup-") || body["status"] != "DRAFT" {
		t.Fatalf("unexpected body: %#v", body)
	}
	assertEnvelope(t, publisher.all()[0], "SupplierRegistered")
}

func TestGetAndListSuppliersHappyPath(t *testing.T) {
	router, _ := newTestRouter()
	created := doJSON(router, http.MethodPost, "/api/v1/suppliers", `{"legalName":"China Railway","brandName":"CR","supplierCode":"CR"}`, "0194f2e0-7b3e-7000-8000-000000000002")
	var supplier map[string]interface{}
	mustJSON(t, created.Body.Bytes(), &supplier)
	get := httptest.NewRecorder()
	router.ServeHTTP(get, httptest.NewRequest(http.MethodGet, "/api/v1/suppliers/"+supplier["supplierId"].(string), nil))
	if get.Code != http.StatusOK {
		t.Fatalf("get status %d", get.Code)
	}
	list := httptest.NewRecorder()
	router.ServeHTTP(list, httptest.NewRequest(http.MethodGet, "/api/v1/suppliers?limit=20&offset=0", nil))
	if list.Code != http.StatusOK || !strings.Contains(list.Body.String(), `"total":1`) {
		t.Fatalf("list status/body %d %s", list.Code, list.Body.String())
	}
}

func TestRegisterCarrierHappyPath(t *testing.T) {
	router, publisher := newTestRouter()
	created := doJSON(router, http.MethodPost, "/api/v1/suppliers", `{"legalName":"China Railway","brandName":"CR","supplierCode":"CR"}`, "0194f2e0-7b3e-7000-8000-000000000003")
	var supplier map[string]interface{}
	mustJSON(t, created.Body.Bytes(), &supplier)
	body := `{"supplierId":"` + supplier["supplierId"].(string) + `","name":"Beijing Railway","code":"BJRAIL","transportMode":"RAIL"}`
	response := doJSON(router, http.MethodPost, "/api/v1/carriers", body, "0194f2e0-7b3e-7000-8000-000000000004")
	if response.Code != http.StatusCreated {
		t.Fatalf("unexpected status %d body %s", response.Code, response.Body.String())
	}
	var carrier map[string]interface{}
	mustJSON(t, response.Body.Bytes(), &carrier)
	if !strings.HasPrefix(carrier["carrierId"].(string), "car-") || carrier["transportMode"] != "RAIL" {
		t.Fatalf("unexpected carrier: %#v", carrier)
	}
	assertEnvelope(t, publisher.all()[1], "CarrierRegistered")
}

func TestActivateContractHappyPath(t *testing.T) {
	router, publisher := newTestRouter()
	supplierID, carrierID := seedSupplierAndCarrier(t, router)
	body := `{"supplierId":"` + supplierID + `","carrierId":"` + carrierID + `","contractRef":"CNT-1","effectiveFrom":"2026-07-05T00:00:00Z","effectiveUntil":"2027-07-05T00:00:00Z"}`
	response := doJSON(router, http.MethodPost, "/api/v1/contracts", body, "0194f2e0-7b3e-7000-8000-000000000005")
	if response.Code != http.StatusCreated {
		t.Fatalf("unexpected status %d body %s", response.Code, response.Body.String())
	}
	var contract map[string]interface{}
	mustJSON(t, response.Body.Bytes(), &contract)
	if !strings.HasPrefix(contract["contractId"].(string), "ctr-") || contract["status"] != "PUBLISHED" {
		t.Fatalf("unexpected contract: %#v", contract)
	}
	assertEnvelope(t, publisher.all()[2], "ContractActivated")
}

func TestValidationFailureBodyShape(t *testing.T) {
	router, _ := newTestRouter()
	response := doJSON(router, http.MethodPost, "/api/v1/suppliers", `{"legalName":"Only Legal"}`, "0194f2e0-7b3e-7000-8000-000000000006")
	if response.Code != http.StatusBadRequest {
		t.Fatalf("unexpected status %d", response.Code)
	}
	var body ErrorBody
	mustJSON(t, response.Body.Bytes(), &body)
	if body.Code != "VALIDATION_FAILED" || body.CorrelationID == "" || body.Details == nil {
		t.Fatalf("unexpected error body: %#v", body)
	}
}

func TestIdempotentReplayReturnsOriginalResultAndReuseFails(t *testing.T) {
	router, _ := newTestRouter()
	first := doJSON(router, http.MethodPost, "/api/v1/suppliers", `{"legalName":"China Railway","brandName":"CR","supplierCode":"CR"}`, "0194f2e0-7b3e-7000-8000-000000000007")
	second := doJSON(router, http.MethodPost, "/api/v1/suppliers", `{"legalName":"China Railway","brandName":"CR","supplierCode":"CR"}`, "0194f2e0-7b3e-7000-8000-000000000007")
	if first.Code != http.StatusCreated || second.Code != http.StatusCreated || first.Body.String() != second.Body.String() {
		t.Fatalf("replay mismatch: %d/%d %s/%s", first.Code, second.Code, first.Body.String(), second.Body.String())
	}
	reused := doJSON(router, http.MethodPost, "/api/v1/suppliers", `{"legalName":"Other","brandName":"Other","supplierCode":"OTHER"}`, "0194f2e0-7b3e-7000-8000-000000000007")
	if reused.Code != http.StatusUnprocessableEntity || !strings.Contains(reused.Body.String(), "IDEMPOTENCY_KEY_REUSED") {
		t.Fatalf("expected key reused, got %d %s", reused.Code, reused.Body.String())
	}
}

func TestInvalidIdempotencyKeyFormatFails(t *testing.T) {
	router, _ := newTestRouter()
	for _, key := range []string{"not-a-uuid", "0194f2e0-7b3e-4610-8284-5c26e8b0c401"} {
		response := doJSON(router, http.MethodPost, "/api/v1/suppliers", `{"legalName":"China Railway","brandName":"CR","supplierCode":"CR"}`, key)
		if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), "VALIDATION_FAILED") {
			t.Fatalf("expected validation failure for %s, got %d %s", key, response.Code, response.Body.String())
		}
	}
}

func TestErrorBodyUsesGeneratedCorrelationID(t *testing.T) {
	router, _ := newTestRouter()
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/api/v1/suppliers", bytes.NewBufferString(`{"legalName":"Only Legal"}`))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Idempotency-Key", "0194f2e0-7b3e-7000-8000-000000000013")

	router.ServeHTTP(recorder, request)

	var body ErrorBody
	mustJSON(t, recorder.Body.Bytes(), &body)
	if recorder.Code != http.StatusBadRequest || body.CorrelationID == "" || body.CorrelationID != recorder.Header().Get(goruntime.CorrelationIDHeader) {
		t.Fatalf("error did not use generated correlation id: status=%d body=%#v header=%q", recorder.Code, body, recorder.Header().Get(goruntime.CorrelationIDHeader))
	}
}

func TestMissingIdempotencyKeyFails(t *testing.T) {
	router, _ := newTestRouter()
	response := doJSON(router, http.MethodPost, "/api/v1/suppliers", `{"legalName":"China Railway","brandName":"CR","supplierCode":"CR"}`, "")
	if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), "VALIDATION_FAILED") {
		t.Fatalf("unexpected status/body: %d %s", response.Code, response.Body.String())
	}
}

func TestPublishFailureRetainsEventAndRetryWithSameIdempotencyKeySucceeds(t *testing.T) {
	router, publisher := newTestRouter()
	publisher.failures = 1
	key := "0194f2e0-7b3e-7000-8000-000000000014"
	body := `{"legalName":"China Railway","brandName":"CR","supplierCode":"CR"}`

	first := doJSON(router, http.MethodPost, "/api/v1/suppliers", body, key)
	if first.Code != http.StatusServiceUnavailable || !strings.Contains(first.Body.String(), "UNAVAILABLE") {
		t.Fatalf("expected publish failure response, got %d %s", first.Code, first.Body.String())
	}
	second := doJSON(router, http.MethodPost, "/api/v1/suppliers", body, key)
	if second.Code != http.StatusCreated {
		t.Fatalf("retry should succeed, got %d %s", second.Code, second.Body.String())
	}
	if len(publisher.all()) != 1 {
		t.Fatalf("expected retained event to publish once, got %d", len(publisher.all()))
	}
	third := doJSON(router, http.MethodPost, "/api/v1/suppliers", body, key)
	if third.Code != http.StatusCreated || third.Body.String() != second.Body.String() {
		t.Fatalf("retry should be cached after successful publish: %d %s", third.Code, third.Body.String())
	}
}

func TestCarrierTransportModeDomainViolationUsesCanonicalError(t *testing.T) {
	router, _ := newTestRouter()
	created := doJSON(router, http.MethodPost, "/api/v1/suppliers", `{"legalName":"China Railway","brandName":"CR","supplierCode":"CR"}`, "0194f2e0-7b3e-7000-8000-000000000008")
	var supplier map[string]interface{}
	mustJSON(t, created.Body.Bytes(), &supplier)
	body := `{"supplierId":"` + supplier["supplierId"].(string) + `","name":"Bad Carrier","code":"BAD","transportMode":"SPACESHIP"}`
	response := doJSON(router, http.MethodPost, "/api/v1/carriers", body, "0194f2e0-7b3e-7000-8000-000000000009")
	if response.Code != http.StatusUnprocessableEntity || !strings.Contains(response.Body.String(), "DOMAIN_RULE_VIOLATION") {
		t.Fatalf("unexpected status/body: %d %s", response.Code, response.Body.String())
	}
}

func TestDomainInvariantViolationSurfacesAsDomainRuleViolation(t *testing.T) {
	router, _ := newTestRouter()
	response := doJSON(router, http.MethodPost, "/api/v1/contracts", `{"supplierId":"sup-missing","carrierId":"car-missing","contractRef":"CNT","effectiveFrom":"2026-07-05T00:00:00Z","effectiveUntil":"2026-07-04T00:00:00Z"}`, "0194f2e0-7b3e-7000-8000-000000000010")
	if response.Code != http.StatusUnprocessableEntity || !strings.Contains(response.Body.String(), "DOMAIN_RULE_VIOLATION") {
		t.Fatalf("unexpected status/body: %d %s", response.Code, response.Body.String())
	}
}

func doJSON(router *gin.Engine, method, path, body, idempotencyKey string) *httptest.ResponseRecorder {
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(method, path, bytes.NewBufferString(body))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set(goruntime.CorrelationIDHeader, "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444")
	request.Header.Set(goruntime.RequestIDHeader, "0194f2e0-7b3e-7610-0284-5c26e8b0c555")
	if idempotencyKey != "" {
		request.Header.Set("Idempotency-Key", idempotencyKey)
	}
	router.ServeHTTP(recorder, request)
	return recorder
}

func seedSupplierAndCarrier(t *testing.T, router *gin.Engine) (string, string) {
	t.Helper()
	created := doJSON(router, http.MethodPost, "/api/v1/suppliers", `{"legalName":"China Railway","brandName":"CR","supplierCode":"CR"}`, "0194f2e0-7b3e-7000-8000-000000000011")
	var supplier map[string]interface{}
	mustJSON(t, created.Body.Bytes(), &supplier)
	carrierBody := `{"supplierId":"` + supplier["supplierId"].(string) + `","name":"Beijing Railway","code":"BJRAIL","transportMode":"RAIL"}`
	carrierCreated := doJSON(router, http.MethodPost, "/api/v1/carriers", carrierBody, "0194f2e0-7b3e-7000-8000-000000000012")
	var carrier map[string]interface{}
	mustJSON(t, carrierCreated.Body.Bytes(), &carrier)
	return supplier["supplierId"].(string), carrier["carrierId"].(string)
}

func mustJSON(t *testing.T, data []byte, out interface{}) {
	t.Helper()
	if err := json.Unmarshal(data, out); err != nil {
		t.Fatalf("invalid json %s: %v", string(data), err)
	}
}

func assertEnvelope(t *testing.T, envelope application.EventEnvelope, eventType string) {
	t.Helper()
	if !strings.HasPrefix(envelope.EventID, "evt-") || envelope.EventType != eventType || envelope.Producer != "supplier-catalog" || envelope.SchemaVersion != 1 {
		t.Fatalf("bad envelope: %#v", envelope)
	}
	if !strings.HasPrefix(envelope.CorrelationID, "corr-") || !strings.HasPrefix(envelope.CausationID, "cmd-") || envelope.Payload == nil {
		t.Fatalf("bad envelope IDs/payload: %#v", envelope)
	}
}
