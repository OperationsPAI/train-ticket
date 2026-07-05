package http

import (
	"bytes"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/trainticket/greenfield/platform/go-kit/httpkit"
	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"

	"github.com/trainticket/greenfield/services/service-plan/internal/application"
	"github.com/trainticket/greenfield/services/service-plan/internal/domain"
)

type Handler struct {
	service *application.Service
}

type createScheduledServiceRequest struct {
	ServiceRef        string    `json:"serviceRef"`
	CarrierID         string    `json:"carrierId"`
	ServiceNumber     string    `json:"serviceNumber"`
	DepartureTime     time.Time `json:"departureTime"`
	ArrivalTime       time.Time `json:"arrivalTime"`
	OriginNodeID      string    `json:"originNodeId"`
	DestinationNodeID string    `json:"destinationNodeId"`
	Status            string    `json:"status"`
}

type createServiceSegmentRequest struct {
	ScheduledServiceRef string    `json:"scheduledServiceRef"`
	OriginStopRef       string    `json:"originStopRef"`
	DestinationStopRef  string    `json:"destinationStopRef"`
	DepartureTime       time.Time `json:"departureTime"`
	ArrivalTime         time.Time `json:"arrivalTime"`
}

func Router() *gin.Engine {
	return RouterWithService(application.NewService(application.NoopPublisher{}))
}

func RouterWithService(service *application.Service) *gin.Engine {
	return RouterWithServiceAndIdempotency(service, idempotency.NewMemoryStore())
}

func RouterWithServiceAndIdempotency(service *application.Service, store idempotency.Store) *gin.Engine {
	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{
		ServiceID:    profile.ServiceID,
		Metadata:     profile,
		HealthStatus: domain.Health(),
		Observer:     goruntime.ObserverFromEnv(profile.ServiceID),
	})
	handler := Handler{service: service}
	idempotent := idempotency.Middleware(store)
	api := router.Group("/api/v1")
	api.POST("/scheduled-services", idempotent, handler.createScheduledService)
	api.GET("/scheduled-services/:serviceRef", handler.getScheduledService)
	api.GET("/scheduled-services", handler.listScheduledServices)
	api.POST("/service-segments", idempotent, handler.createServiceSegment)
	return router
}

func (h Handler) createScheduledService(ctx *gin.Context) {
	var request createScheduledServiceRequest
	if err := bindBody(ctx, &request); err != nil {
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, "Request body failed structural validation", nil)
		return
	}
	metadata := idempotencyMetadata(ctx)
	result, _, err := h.service.CreateScheduledService(ctx.Request.Context(), application.CreateScheduledServiceCommand{
		ServiceRef:        request.ServiceRef,
		CarrierID:         request.CarrierID,
		ServiceNumber:     request.ServiceNumber,
		DepartureTime:     request.DepartureTime,
		ArrivalTime:       request.ArrivalTime,
		OriginNodeID:      request.OriginNodeID,
		DestinationNodeID: request.DestinationNodeID,
		Status:            request.Status,
		IdempotencyKey:    metadata.Key,
		CorrelationID:     httpkit.CorrelationID(ctx),
		CausationID:       ctx.GetHeader("X-Causation-Id"),
		RequestHash:       metadata.Fingerprint,
	})
	if err != nil {
		writeMappedError(ctx, err)
		return
	}
	ctx.JSON(http.StatusCreated, result)
}

func (h Handler) getScheduledService(ctx *gin.Context) {
	service, err := h.service.GetScheduledService(ctx.Param("serviceRef"))
	if err != nil {
		writeMappedError(ctx, err)
		return
	}
	ctx.JSON(http.StatusOK, service)
}

func (h Handler) listScheduledServices(ctx *gin.Context) {
	limit, err := parseBoundedInt(ctx.Query("limit"), 20, 1, 100)
	if err != nil {
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, "limit must be an integer between 1 and 100", nil)
		return
	}
	offset, err := parseBoundedInt(ctx.Query("offset"), 0, 0, int(^uint(0)>>1))
	if err != nil {
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, "offset must be a non-negative integer", nil)
		return
	}
	ctx.JSON(http.StatusOK, h.service.ListScheduledServices(application.ListScheduledServicesQuery{Limit: limit, Offset: offset, CarrierID: strings.TrimSpace(ctx.Query("carrierId"))}))
}

func (h Handler) createServiceSegment(ctx *gin.Context) {
	var request createServiceSegmentRequest
	if err := bindBody(ctx, &request); err != nil {
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, "Request body failed structural validation", nil)
		return
	}
	metadata := idempotencyMetadata(ctx)
	result, _, err := h.service.CreateServiceSegment(ctx.Request.Context(), application.CreateServiceSegmentCommand{
		ScheduledServiceRef: request.ScheduledServiceRef,
		OriginStopRef:       request.OriginStopRef,
		DestinationStopRef:  request.DestinationStopRef,
		DepartureTime:       request.DepartureTime,
		ArrivalTime:         request.ArrivalTime,
		IdempotencyKey:      metadata.Key,
		CorrelationID:       httpkit.CorrelationID(ctx),
		CausationID:         ctx.GetHeader("X-Causation-Id"),
		RequestHash:         metadata.Fingerprint,
	})
	if err != nil {
		writeMappedError(ctx, err)
		return
	}
	ctx.JSON(http.StatusCreated, result)
}

func bindBody(ctx *gin.Context, target any) error {
	body, err := io.ReadAll(ctx.Request.Body)
	if err != nil || len(strings.TrimSpace(string(body))) == 0 {
		return errors.New("request body is required")
	}
	ctx.Request.Body = io.NopCloser(bytes.NewReader(body))
	return jsonUnmarshalStrict(body, target)
}

func idempotencyMetadata(ctx *gin.Context) idempotency.ContextValue {
	metadata, _ := idempotency.FromContext(ctx)
	return metadata
}

func writeMappedError(ctx *gin.Context, err error) {
	switch {
	case errors.Is(err, application.ErrValidation), errors.Is(err, application.ErrIdempotencyKeyRequired):
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, err.Error(), nil)
	case errors.Is(err, application.ErrNotFound):
		httpkit.WriteError(ctx, http.StatusNotFound, httpkit.NotFound, err.Error(), nil)
	case errors.Is(err, application.ErrConflict):
		httpkit.WriteError(ctx, http.StatusConflict, httpkit.Conflict, err.Error(), nil)
	case errors.Is(err, application.ErrIdempotencyKeyReused):
		httpkit.WriteIdempotencyReused(ctx)
	case errors.Is(err, application.ErrDomainRule):
		httpkit.WriteError(ctx, http.StatusUnprocessableEntity, httpkit.DomainRuleViolation, err.Error(), nil)
	case errors.Is(err, application.ErrPublish):
		httpkit.WriteError(ctx, http.StatusServiceUnavailable, httpkit.Unavailable, "Service is temporarily unavailable", nil)
	default:
		httpkit.WriteError(ctx, http.StatusServiceUnavailable, httpkit.Unavailable, "Service is temporarily unavailable", nil)
	}
}

func parseBoundedInt(raw string, defaultValue, minValue, maxValue int) (int, error) {
	if strings.TrimSpace(raw) == "" {
		return defaultValue, nil
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < minValue || value > maxValue {
		return 0, errors.New("invalid integer")
	}
	return value, nil
}
