package http

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/fulfillment/internal/application"
	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

const idempotencyKeyHeader = "Idempotency-Key"

type idempotencyStore interface {
	Get(key string) (idempotencyEntry, bool)
	Put(key string, entry idempotencyEntry)
}

type idempotencyEntry struct {
	Fingerprint string
	Status      int
	Body        []byte
}

type memoryIdempotencyStore struct {
	mu      sync.RWMutex
	entries map[string]idempotencyEntry
}

func newMemoryIdempotencyStore() *memoryIdempotencyStore {
	return &memoryIdempotencyStore{entries: map[string]idempotencyEntry{}}
}

func (s *memoryIdempotencyStore) Get(key string) (idempotencyEntry, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	entry, ok := s.entries[key]
	return entry, ok
}

func (s *memoryIdempotencyStore) Put(key string, entry idempotencyEntry) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.entries[key] = entry
}

type responseCaptureWriter struct {
	gin.ResponseWriter
	body bytes.Buffer
}

func (w *responseCaptureWriter) Write(data []byte) (int, error) {
	w.body.Write(data)
	return w.ResponseWriter.Write(data)
}

func (w *responseCaptureWriter) WriteString(data string) (int, error) {
	w.body.WriteString(data)
	return w.ResponseWriter.WriteString(data)
}

type Handler struct {
	svc         *application.Service
	idempotency idempotencyStore
}

func Router() *gin.Engine {
	repo := application.NewInMemoryRepository()
	publisher := application.NoopPublisher{}
	service := application.NewService(repo, publisher, application.NewInMemoryConsumedEventLog(), nil, nil)
	return RouterWithService(service)
}

func RouterWithService(service *application.Service) *gin.Engine {
	return RouterWithConfig(service, nil)
}

func RouterWithConfig(service *application.Service, idSource goruntime.IDGenerator) *gin.Engine {
	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{
		ServiceID:       profile.ServiceID,
		Metadata:        profile,
		HealthStatus:    domain.Health(),
		Observer:        goruntime.ObserverFromEnv(profile.ServiceID),
		RequestIDSource: idSource,
	})
	router.GET("/healthz", func(ctx *gin.Context) {
		ctx.JSON(http.StatusOK, gin.H{"status": domain.Health()})
	})
	h := &Handler{svc: service, idempotency: newMemoryIdempotencyStore()}
	router.POST("/api/v1/fulfillment-records/boarding", h.idempotentPost(h.verifyBoarding))
	router.POST("/api/v1/fulfillment-records/no-show", h.idempotentPost(h.recordNoShow))
	router.GET("/api/v1/fulfillment-records/:fulfillmentRecordId", h.getFulfillmentRecord)
	return router
}

func (h *Handler) idempotentPost(next gin.HandlerFunc) gin.HandlerFunc {
	return func(ctx *gin.Context) {
		key := strings.TrimSpace(ctx.GetHeader(idempotencyKeyHeader))
		if key == "" {
			writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", "Idempotency-Key header is required", nil)
			return
		}
		body, err := io.ReadAll(ctx.Request.Body)
		if err != nil {
			writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", "request body could not be read", nil)
			return
		}
		ctx.Request.Body = io.NopCloser(bytes.NewReader(body))
		fingerprint := requestFingerprint(ctx.Request.Method, ctx.FullPath(), body)
		if entry, ok := h.idempotency.Get(key); ok {
			if entry.Fingerprint != fingerprint {
				writeError(ctx, http.StatusUnprocessableEntity, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was reused with a different request body", nil)
				return
			}
			ctx.Data(entry.Status, "application/json", entry.Body)
			return
		}
		capture := &responseCaptureWriter{ResponseWriter: ctx.Writer}
		ctx.Writer = capture
		next(ctx)
		if capture.Status() < http.StatusBadRequest {
			h.idempotency.Put(key, idempotencyEntry{Fingerprint: fingerprint, Status: capture.Status(), Body: append([]byte(nil), capture.body.Bytes()...)})
		}
	}
}

func requestFingerprint(method, path string, body []byte) string {
	sum := sha256.Sum256(append([]byte(method+" "+path+"\n"), body...))
	return hex.EncodeToString(sum[:])
}

type verifyBoardingRequest struct {
	EntitlementID    string `json:"entitlementId"`
	SegmentBookingID string `json:"segmentBookingId"`
	JourneyOrderID   string `json:"journeyOrderId"`
	TravelerID       string `json:"travelerId"`
	SegmentRef       string `json:"segmentRef"`
	Source           string `json:"source"`
	SourceEventID    string `json:"sourceEventId"`
	OccurredAt       string `json:"occurredAt"`
}

func (h *Handler) verifyBoarding(ctx *gin.Context) {
	var req verifyBoardingRequest
	if err := decodeJSON(ctx, &req); err != nil {
		writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", err.Error(), nil)
		return
	}
	occurredAt, err := parseRequiredTime(req.OccurredAt, "occurredAt")
	if err != nil {
		writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", err.Error(), nil)
		return
	}
	result, err := h.svc.VerifyBoarding(ctx.Request.Context(), application.VerifyBoardingCommand{
		EntitlementID:    domain.EntitlementRef(req.EntitlementID),
		SegmentBookingID: domain.SegmentBookingRef(req.SegmentBookingID),
		JourneyOrderID:   domain.OrderRef(req.JourneyOrderID),
		TravelerID:       domain.TravelerRef(req.TravelerID),
		SegmentRef:       domain.SegmentRef(req.SegmentRef),
		Source:           domain.FulfillmentSource(req.Source),
		SourceEventID:    req.SourceEventID,
		OccurredAt:       occurredAt,
	}, commandMetadata(ctx))
	if err != nil {
		writeMappedError(ctx, err)
		return
	}
	ctx.JSON(http.StatusCreated, result)
}

type noShowRequest struct {
	EntitlementID    string `json:"entitlementId"`
	SegmentBookingID string `json:"segmentBookingId"`
	JourneyOrderID   string `json:"journeyOrderId"`
	TravelerID       string `json:"travelerId"`
	SegmentRef       string `json:"segmentRef"`
	Reason           string `json:"reason"`
}

func (h *Handler) recordNoShow(ctx *gin.Context) {
	var req noShowRequest
	if err := decodeJSON(ctx, &req); err != nil {
		writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", err.Error(), nil)
		return
	}
	result, err := h.svc.RecordNoShow(ctx.Request.Context(), application.RecordNoShowCommand{
		EntitlementID:    domain.EntitlementRef(req.EntitlementID),
		SegmentBookingID: domain.SegmentBookingRef(req.SegmentBookingID),
		JourneyOrderID:   domain.OrderRef(req.JourneyOrderID),
		TravelerID:       domain.TravelerRef(req.TravelerID),
		SegmentRef:       domain.SegmentRef(req.SegmentRef),
		Reason:           domain.NoShowReason(req.Reason),
	}, commandMetadata(ctx))
	if err != nil {
		writeMappedError(ctx, err)
		return
	}
	ctx.JSON(http.StatusCreated, result)
}

func (h *Handler) getFulfillmentRecord(ctx *gin.Context) {
	record, err := h.svc.GetFulfillmentRecord(ctx.Request.Context(), domain.FulfillmentRecordID(ctx.Param("fulfillmentRecordId")))
	if err != nil {
		writeMappedError(ctx, err)
		return
	}
	ctx.JSON(http.StatusOK, recordResponseFromDomain(record))
}

type recordResponse struct {
	FulfillmentRecordID string                   `json:"fulfillmentRecordId"`
	EntitlementID       string                   `json:"entitlementId"`
	SegmentBookingID    string                   `json:"segmentBookingId"`
	JourneyOrderID      string                   `json:"journeyOrderId"`
	TravelerID          string                   `json:"travelerId"`
	SegmentRef          string                   `json:"segmentRef"`
	Status              string                   `json:"status"`
	BoardingFact        *boardingFactJSON        `json:"boardingFact,omitempty"`
	NoShowReason        *domain.NoShowReason     `json:"noShowReason,omitempty"`
	NoShowAssessedAt    *time.Time               `json:"noShowAssessedAt,omitempty"`
	CompletedAt         *time.Time               `json:"completedAt,omitempty"`
	CompletionSource    *domain.CompletionSource `json:"completionSource,omitempty"`
	CreatedAt           time.Time                `json:"createdAt"`
	UpdatedAt           time.Time                `json:"updatedAt"`
}

type boardingFactJSON struct {
	EntitlementID    string    `json:"entitlementId"`
	SegmentBookingID string    `json:"segmentBookingId"`
	SegmentRef       string    `json:"segmentRef"`
	Source           string    `json:"source"`
	SourceEventID    string    `json:"sourceEventId"`
	OccurredAt       time.Time `json:"occurredAt"`
	ReceivedAt       time.Time `json:"receivedAt"`
}

func recordResponseFromDomain(record *domain.FulfillmentRecord) recordResponse {
	response := recordResponse{
		FulfillmentRecordID: string(record.FulfillmentRecordID),
		EntitlementID:       string(record.EntitlementID),
		SegmentBookingID:    string(record.SegmentBookingID),
		JourneyOrderID:      string(record.JourneyOrderID),
		TravelerID:          string(record.TravelerID),
		SegmentRef:          string(record.SegmentRef),
		Status:              string(record.Status),
		NoShowReason:        record.NoShowReason,
		NoShowAssessedAt:    record.NoShowAssessedAt,
		CompletedAt:         record.CompletedAt,
		CompletionSource:    record.CompletionSource,
		CreatedAt:           record.CreatedAt,
		UpdatedAt:           record.UpdatedAt,
	}
	if record.BoardingFact != nil {
		response.BoardingFact = &boardingFactJSON{
			EntitlementID:    string(record.BoardingFact.EntitlementID),
			SegmentBookingID: string(record.BoardingFact.SegmentBookingID),
			SegmentRef:       string(record.BoardingFact.SegmentRef),
			Source:           string(record.BoardingFact.Source),
			SourceEventID:    record.BoardingFact.SourceEventID,
			OccurredAt:       record.BoardingFact.OccurredAt,
			ReceivedAt:       record.BoardingFact.ReceivedAt,
		}
	}
	return response
}

func decodeJSON(ctx *gin.Context, dest any) error {
	decoder := json.NewDecoder(ctx.Request.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(dest); err != nil {
		return err
	}
	if decoder.Decode(&struct{}{}) != io.EOF {
		return errors.New("request body must contain a single JSON object")
	}
	return nil
}

func parseRequiredTime(value, field string) (time.Time, error) {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return time.Time{}, errors.New(field + " is required")
	}
	parsed, err := time.Parse(time.RFC3339, trimmed)
	if err != nil || !strings.HasSuffix(trimmed, "Z") {
		return time.Time{}, errors.New(field + " must be RFC3339 UTC")
	}
	return parsed.UTC(), nil
}

func commandMetadata(ctx *gin.Context) application.CommandMetadata {
	return application.CommandMetadata{
		CorrelationID: goruntime.CorrelationID(ctx.Request.Context()),
		CausationID:   goruntime.RequestID(ctx.Request.Context()),
	}
}

type errorBody struct {
	Code          string         `json:"code"`
	Message       string         `json:"message"`
	CorrelationID string         `json:"correlationId"`
	Details       map[string]any `json:"details"`
}

func writeMappedError(ctx *gin.Context, err error) {
	switch {
	case errors.Is(err, application.ErrNotFound):
		writeError(ctx, http.StatusNotFound, "NOT_FOUND", "fulfillment record not found", nil)
	case errors.Is(err, application.ErrConflict):
		writeError(ctx, http.StatusConflict, "CONFLICT", err.Error(), nil)
	case errors.Is(err, application.ErrDomainRuleViolation):
		writeError(ctx, http.StatusUnprocessableEntity, "DOMAIN_RULE_VIOLATION", err.Error(), nil)
	case errors.Is(err, application.ErrPublishFailed):
		writeError(ctx, http.StatusServiceUnavailable, "UNAVAILABLE", "event bus unavailable", nil)
	default:
		writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", err.Error(), nil)
	}
}

func writeError(ctx *gin.Context, status int, code, message string, details map[string]any) {
	if details == nil {
		details = map[string]any{}
	}
	ctx.JSON(status, errorBody{Code: code, Message: message, CorrelationID: goruntime.CorrelationID(ctx.Request.Context()), Details: details})
}
