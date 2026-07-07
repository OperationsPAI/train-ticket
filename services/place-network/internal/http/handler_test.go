package http

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	"github.com/trainticket/greenfield/platform/go-kit/ids"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/place-network/internal/application"
	"github.com/trainticket/greenfield/services/place-network/internal/domain"
	"github.com/trainticket/greenfield/services/place-network/internal/domain/ports"
)

type recordingPublisher struct {
	mu     sync.Mutex
	events []domain.EventEnvelope
}

func (p *recordingPublisher) Publish(_ context.Context, envelope domain.EventEnvelope) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.events = append(p.events, envelope)
	return nil
}

func (p *recordingPublisher) last() domain.EventEnvelope {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.events[len(p.events)-1]
}

func setupTestRouter(publisher application.EventPublisher) *gin.Engine {
	gin.SetMode(gin.TestMode)
	if publisher == nil {
		publisher = application.NewNoopPublisher()
	}
	service := application.NewService(application.ServiceConfig{Places: ports.NewInMemoryPlaceRepository(), Nodes: ports.NewInMemoryTransportNodeRepository(), Publisher: publisher, Clock: fixedClock{time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC)}})
	router := gin.New()
	router.Use(func(ctx *gin.Context) {
		ctx.Request = ctx.Request.WithContext(goruntime.ContextWithRequestIDs(ctx.Request.Context(), "req-test", "corr-test"))
		ctx.Writer.Header().Set(goruntime.RequestIDHeader, "req-test")
		ctx.Writer.Header().Set(goruntime.CorrelationIDHeader, "corr-test")
		ctx.Next()
	})
	NewHandler(service, idempotency.NewMemoryStore()).RegisterRoutes(router)
	return router
}

func TestCreatePlaceHappyPath(t *testing.T) {
	router := setupTestRouter(nil)
	rec := performJSON(router, http.MethodPost, "/api/v1/places", map[string]any{"placeType": "CITY", "canonicalName": "Shanghai"}, "0194f2e0-7b3e-7001-8284-5c26e8b00001")
	if rec.Code != http.StatusCreated {
		t.Fatalf("expected 201, got %d: %s", rec.Code, rec.Body.String())
	}
	var resp application.CreatePlaceResponse
	decode(t, rec, &resp)
	if !strings.HasPrefix(resp.PlaceID, "plc-") || resp.PlaceType != "CITY" || resp.CanonicalName != "Shanghai" || resp.Status != "ACTIVE" || resp.CreatedAt != "2026-07-05T10:30:00Z" {
		t.Fatalf("unexpected response: %#v", resp)
	}
}

func TestGetPlaceHappyPath(t *testing.T) {
	router := setupTestRouter(nil)
	created := createPlace(t, router, "Station", "STATION", "0194f2e0-7b3e-7002-8284-5c26e8b00002")
	rec := perform(router, http.MethodGet, "/api/v1/places/"+created.PlaceID, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
	var resp application.GetPlaceResponse
	decode(t, rec, &resp)
	if resp.PlaceID != created.PlaceID || resp.PlaceType != "STATION" || resp.Code != "STN" || resp.Timezone != "Asia/Shanghai" || len(resp.Nodes) != 0 {
		t.Fatalf("unexpected response: %#v", resp)
	}
}

func TestListPlacesHappyPath(t *testing.T) {
	router := setupTestRouter(nil)
	createPlaceWithReferenceData(t, router, "Beijing", "CITY", "BJS", "Asia/Shanghai", "0194f2e0-7b3e-7003-8284-5c26e8b00003")
	createPlace(t, router, "Shanghai", "CITY", "0194f2e0-7b3e-7004-8284-5c26e8b00004")
	rec := perform(router, http.MethodGet, "/api/v1/places?limit=1&offset=0&status=ACTIVE", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
	var resp application.ListPlacesResponse
	decode(t, rec, &resp)
	if resp.Total != 2 || resp.Limit != 1 || resp.Offset != 0 || len(resp.Items) != 1 || resp.Items[0].Code != "BJS" {
		t.Fatalf("unexpected list: %#v", resp)
	}
}

func TestCreateTransportNodeHappyPath(t *testing.T) {
	router := setupTestRouter(nil)
	place := createPlace(t, router, "Beijing South", "STATION", "0194f2e0-7b3e-7005-8284-5c26e8b00005")
	rec := performJSON(router, http.MethodPost, "/api/v1/transport-nodes", map[string]any{"placeId": place.PlaceID, "displayName": "Platform 1", "servingModes": []string{"TRAIN"}}, "0194f2e0-7b3e-7006-8284-5c26e8b00006")
	if rec.Code != http.StatusCreated {
		t.Fatalf("expected 201, got %d: %s", rec.Code, rec.Body.String())
	}
	var resp application.CreateTransportNodeResponse
	decode(t, rec, &resp)
	if !strings.HasPrefix(resp.NodeID, "tnd-") || resp.PlaceID != place.PlaceID || resp.DisplayName != "Platform 1" || len(resp.ServingModes) != 1 || resp.ServingModes[0] != "RAIL" {
		t.Fatalf("unexpected response: %#v", resp)
	}
}

func TestGetTransportNodeHappyPath(t *testing.T) {
	router := setupTestRouter(nil)
	place := createPlace(t, router, "Beijing South", "STATION", "0194f2e0-7b3e-7007-8284-5c26e8b00007")
	createdRec := performJSON(router, http.MethodPost, "/api/v1/transport-nodes", map[string]any{"placeId": place.PlaceID, "displayName": "Platform 2", "servingModes": []string{"TRAIN"}}, "0194f2e0-7b3e-7008-8284-5c26e8b00008")
	var created application.CreateTransportNodeResponse
	decode(t, createdRec, &created)
	rec := perform(router, http.MethodGet, "/api/v1/transport-nodes/"+created.NodeID, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
	var resp application.GetTransportNodeResponse
	decode(t, rec, &resp)
	if resp.NodeID != created.NodeID || resp.PlaceID != place.PlaceID || resp.CreatedAt != "2026-07-05T10:30:00Z" {
		t.Fatalf("unexpected response: %#v", resp)
	}
}

func TestValidationFailureErrorBody(t *testing.T) {
	router := setupTestRouter(nil)
	rec := performJSON(router, http.MethodPost, "/api/v1/places", map[string]any{"placeType": "CITY"}, "0194f2e0-7b3e-7009-8284-5c26e8b00009")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", rec.Code, rec.Body.String())
	}
	assertError(t, rec, "VALIDATION_FAILED")
}

func TestMissingIdempotencyKey(t *testing.T) {
	router := setupTestRouter(nil)
	rec := performJSON(router, http.MethodPost, "/api/v1/places", map[string]any{"placeType": "CITY", "canonicalName": "Shanghai"}, "")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", rec.Code, rec.Body.String())
	}
	assertError(t, rec, "VALIDATION_FAILED")
}

func TestInvalidIdempotencyKeyFormat(t *testing.T) {
	router := setupTestRouter(nil)
	rec := performJSON(router, http.MethodPost, "/api/v1/places", map[string]any{"placeType": "CITY", "canonicalName": "Shanghai"}, "not-a-uuid-v7")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", rec.Code, rec.Body.String())
	}
	assertError(t, rec, "VALIDATION_FAILED")
}

func TestIdempotentReplayReturnsOriginalResult(t *testing.T) {
	router := setupTestRouter(nil)
	body := map[string]any{"placeType": "CITY", "canonicalName": "Shenzhen"}
	first := performJSON(router, http.MethodPost, "/api/v1/places", body, "0194f2e0-7b3e-700a-8284-5c26e8b0000a")
	second := performJSON(router, http.MethodPost, "/api/v1/places", body, "0194f2e0-7b3e-700a-8284-5c26e8b0000a")
	if first.Code != http.StatusCreated || second.Code != http.StatusCreated || first.Body.String() != second.Body.String() {
		t.Fatalf("expected identical 201 replay, first=%d %s second=%d %s", first.Code, first.Body.String(), second.Code, second.Body.String())
	}
}

func TestIdempotencyKeyReusedWithDifferentBody(t *testing.T) {
	router := setupTestRouter(nil)
	_ = performJSON(router, http.MethodPost, "/api/v1/places", map[string]any{"placeType": "CITY", "canonicalName": "A"}, "0194f2e0-7b3e-700b-8284-5c26e8b0000b")
	rec := performJSON(router, http.MethodPost, "/api/v1/places", map[string]any{"placeType": "CITY", "canonicalName": "B"}, "0194f2e0-7b3e-700b-8284-5c26e8b0000b")
	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("expected 422, got %d: %s", rec.Code, rec.Body.String())
	}
	assertError(t, rec, "IDEMPOTENCY_KEY_REUSED")
}

func TestPublisherWrapsEventsInEnvelope(t *testing.T) {
	publisher := &recordingPublisher{}
	router := setupTestRouter(publisher)
	createPlace(t, router, "Shanghai", "CITY", "0194f2e0-7b3e-700c-8284-5c26e8b0000c")
	envelope := publisher.last()
	if !ids.ValidPrefixedUUIDv7(envelope.EventID, "evt") || envelope.EventType != "PlaceRegistered" || envelope.SchemaVersion != 1 || envelope.Producer != "place-network" || envelope.CorrelationID != "corr-test" || envelope.OccurredAt != "2026-07-05T10:30:00Z" {
		t.Fatalf("unexpected envelope: %#v", envelope)
	}
	payload, ok := envelope.Payload.(domain.PlaceUpdatedEvent)
	if !ok || payload.PlaceID == "" || payload.UpdatedAt != "2026-07-05T10:30:00Z" {
		t.Fatalf("unexpected payload: %#v", envelope.Payload)
	}
}

func TestSubscriberHandlerDeduplicatesDuplicateEventID(t *testing.T) {
	calls := 0
	handler := application.NewDeduplicatingEventHandler(func(envelope domain.EventEnvelope) application.HandlerResult {
		calls++
		return application.HandlerSuccess
	})
	envelope := domain.EventEnvelope{EventID: "evt-duplicate", EventType: "PlaceUpdated"}
	if handler.Handle(envelope) != application.HandlerSuccess || handler.Handle(envelope) != application.HandlerSuccess {
		t.Fatal("expected success")
	}
	if calls != 1 {
		t.Fatalf("expected one handler call, got %d", calls)
	}
}

func TestNotFoundErrorBody(t *testing.T) {
	router := setupTestRouter(nil)
	rec := perform(router, http.MethodGet, "/api/v1/places/plc-missing", nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", rec.Code)
	}
	assertError(t, rec, "NOT_FOUND")
}

func createPlace(t *testing.T, router *gin.Engine, name, placeType, key string) application.CreatePlaceResponse {
	t.Helper()
	return createPlaceWithReferenceData(t, router, name, placeType, "STN", "Asia/Shanghai", key)
}

func createPlaceWithReferenceData(t *testing.T, router *gin.Engine, name, placeType, code, timezone, key string) application.CreatePlaceResponse {
	t.Helper()
	rec := performJSON(router, http.MethodPost, "/api/v1/places", map[string]any{"placeType": placeType, "canonicalName": name, "code": code, "timezone": timezone}, key)
	if rec.Code != http.StatusCreated {
		t.Fatalf("create place failed: %d %s", rec.Code, rec.Body.String())
	}
	var resp application.CreatePlaceResponse
	decode(t, rec, &resp)
	return resp
}

func performJSON(router *gin.Engine, method, path string, body any, idempotencyKey string) *httptest.ResponseRecorder {
	data, _ := json.Marshal(body)
	req := httptest.NewRequest(method, path, strings.NewReader(string(data)))
	req.Header.Set("Content-Type", "application/json")
	if idempotencyKey != "" {
		req.Header.Set("Idempotency-Key", idempotencyKey)
	}
	return performRequest(router, req)
}

func perform(router *gin.Engine, method, path string, body *strings.Reader) *httptest.ResponseRecorder {
	var reader *strings.Reader
	if body == nil {
		reader = strings.NewReader("")
	} else {
		reader = body
	}
	return performRequest(router, httptest.NewRequest(method, path, reader))
}

func performRequest(router *gin.Engine, req *http.Request) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	return rec
}

func decode(t *testing.T, rec *httptest.ResponseRecorder, out any) {
	t.Helper()
	if err := json.Unmarshal(rec.Body.Bytes(), out); err != nil {
		t.Fatalf("decode failed: %v; body=%s", err, rec.Body.String())
	}
}

func assertError(t *testing.T, rec *httptest.ResponseRecorder, code string) {
	t.Helper()
	var body struct {
		Code          string         `json:"code"`
		Message       string         `json:"message"`
		CorrelationID string         `json:"correlationId"`
		Details       map[string]any `json:"details"`
	}
	decode(t, rec, &body)
	if body.Code != code || body.Message == "" || body.CorrelationID != "corr-test" || body.Details == nil {
		t.Fatalf("unexpected error body: %#v", body)
	}
}

type fixedClock struct{ value time.Time }

func (c fixedClock) Now() time.Time { return c.value }
