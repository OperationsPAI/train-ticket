package http

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/trainticket/greenfield/platform/go-kit/httpkit"
	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/fulfillment/internal/application"
	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

type Handler struct {
	svc         *application.Service
	idempotency idempotency.Store
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
	h := &Handler{svc: service, idempotency: idempotency.NewMemoryStore()}
	idempotent := idempotency.Middleware(h.idempotency)
	router.POST("/api/v1/fulfillment-records/boarding", idempotent, h.verifyBoarding)
	router.POST("/api/v1/fulfillment-records/no-show", idempotent, h.recordNoShow)
	router.GET("/api/v1/fulfillment-records/:fulfillmentRecordId", h.getFulfillmentRecord)
	return router
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
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, err.Error(), nil)
		return
	}
	occurredAt, err := parseRequiredTime(req.OccurredAt, "occurredAt")
	if err != nil {
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, err.Error(), nil)
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
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, err.Error(), nil)
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

func writeMappedError(ctx *gin.Context, err error) {
	switch {
	case errors.Is(err, application.ErrNotFound):
		httpkit.WriteError(ctx, http.StatusNotFound, httpkit.NotFound, "fulfillment record not found", nil)
	case errors.Is(err, application.ErrConflict):
		httpkit.WriteError(ctx, http.StatusConflict, httpkit.Conflict, err.Error(), nil)
	case errors.Is(err, application.ErrDomainRuleViolation):
		httpkit.WriteError(ctx, http.StatusUnprocessableEntity, httpkit.DomainRuleViolation, err.Error(), nil)
	case errors.Is(err, application.ErrPublishFailed):
		httpkit.WriteError(ctx, http.StatusServiceUnavailable, httpkit.Unavailable, "event bus unavailable", nil)
	default:
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, err.Error(), nil)
	}
}
