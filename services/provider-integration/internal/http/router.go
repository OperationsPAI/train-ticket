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
	"github.com/trainticket/greenfield/platform/go-kit/ids"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"

	"github.com/trainticket/greenfield/services/provider-integration/internal/application"
	"github.com/trainticket/greenfield/services/provider-integration/internal/domain"
)

type Handler struct {
	segments    application.ProviderSegmentStatusService
	idempotency idempotency.Store
}

func Router() *gin.Engine {
	publisher := NoopPublisher{}
	service := application.NewInMemoryReservationService(publisher)
	return RouterWithDependencies(service, idempotency.NewMemoryStore())
}

func RouterWithDependencies(service interface {
	application.ProviderReservationService
	application.ProviderSegmentStatusService
}, store idempotency.Store) *gin.Engine {
	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{
		ServiceID:    profile.ServiceID,
		Metadata:     profile,
		HealthStatus: domain.Health(),
		Observer:     goruntime.ObserverFromEnv(profile.ServiceID),
	})
	RegisterRoutes(router, service, store)
	return router
}

func RegisterRoutes(router gin.IRouter, segments application.ProviderSegmentStatusService, store idempotency.Store) {
	if store == nil {
		store = idempotency.NewMemoryStore()
	}
	h := &Handler{segments: segments, idempotency: store}
	router.POST("/api/v1/provider-segment-status", idempotency.Middleware(h.idempotency), h.reportSegmentStatus)
}

type segmentStatusRequest struct {
	SegmentRef          string `json:"segmentRef"`
	ScheduledServiceRef string `json:"scheduledServiceRef"`
	ServiceDate         string `json:"serviceDate"`
	Status              string `json:"status"`
	EstimatedArrivalAt  string `json:"estimatedArrivalAt"`
	CancelledAt         string `json:"cancelledAt"`
	ObservedAt          string `json:"observedAt"`
	SourceSystem        string `json:"sourceSystem"`
}

func (h *Handler) reportSegmentStatus(ctx *gin.Context) {
	var req segmentStatusRequest
	if err := decodeJSON(ctx, &req); err != nil {
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, err.Error(), nil)
		return
	}
	observedAt, err := parseRequiredTime(req.ObservedAt, "observedAt")
	if err != nil {
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, err.Error(), nil)
		return
	}
	estimatedArrivalAt, err := parseOptionalTime(req.EstimatedArrivalAt, "estimatedArrivalAt")
	if err != nil {
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, err.Error(), nil)
		return
	}
	cancelledAt, err := parseOptionalTime(req.CancelledAt, "cancelledAt")
	if err != nil {
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, err.Error(), nil)
		return
	}
	idem, _ := idempotency.FromContext(ctx)
	cmd := application.ReportProviderSegmentStatusCommand{
		SegmentRef:          req.SegmentRef,
		ScheduledServiceRef: req.ScheduledServiceRef,
		ServiceDate:         req.ServiceDate,
		Status:              strings.ToUpper(strings.TrimSpace(req.Status)),
		ObservedAt:          observedAt,
		SourceSystem:        req.SourceSystem,
		CorrelationID:       ids.CanonicalCorrelationID(goruntime.CorrelationID(ctx.Request.Context())),
		CausationID:         ids.CanonicalCausationID(goruntime.RequestID(ctx.Request.Context())),
		IdempotencyKey:      idem.Key,
	}
	if estimatedArrivalAt != nil {
		cmd.EstimatedArrivalAt = *estimatedArrivalAt
	}
	if cancelledAt != nil {
		cmd.CancelledAt = *cancelledAt
	}
	result, err := h.segments.ReportProviderSegmentStatus(ctx.Request.Context(), cmd)
	if err != nil {
		writeMappedError(ctx, err)
		return
	}
	ctx.JSON(http.StatusCreated, result)
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

func parseOptionalTime(value, field string) (*time.Time, error) {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return nil, nil
	}
	parsed, err := parseRequiredTime(trimmed, field)
	if err != nil {
		return nil, err
	}
	return &parsed, nil
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

func writeMappedError(ctx *gin.Context, err error) {
	switch {
	case errors.Is(err, application.ErrUnavailable):
		httpkit.WriteError(ctx, http.StatusServiceUnavailable, httpkit.Unavailable, "event bus unavailable", nil)
	case errors.Is(err, application.ErrDomainRule):
		httpkit.WriteError(ctx, http.StatusUnprocessableEntity, httpkit.DomainRuleViolation, err.Error(), nil)
	default:
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, err.Error(), nil)
	}
}
