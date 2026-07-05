package http

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"

	"github.com/trainticket/greenfield/services/service-plan/internal/application"
	"github.com/trainticket/greenfield/services/service-plan/internal/domain"
)

type Handler struct {
	service *application.Service
}

type errorBody struct {
	Code          string         `json:"code"`
	Message       string         `json:"message"`
	CorrelationID string         `json:"correlationId"`
	Details       map[string]any `json:"details"`
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
	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{
		ServiceID:    profile.ServiceID,
		Metadata:     profile,
		HealthStatus: domain.Health(),
		Observer:     goruntime.ObserverFromEnv(profile.ServiceID),
	})
	handler := Handler{service: service}
	api := router.Group("/api/v1")
	api.POST("/scheduled-services", handler.createScheduledService)
	api.GET("/scheduled-services/:serviceRef", handler.getScheduledService)
	api.GET("/scheduled-services", handler.listScheduledServices)
	api.POST("/service-segments", handler.createServiceSegment)
	return router
}

func (h Handler) createScheduledService(ctx *gin.Context) {
	body, ok := readJSONBody(ctx)
	if !ok {
		return
	}
	var request createScheduledServiceRequest
	if err := bindBody(body, &request); err != nil {
		writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", "Request body failed structural validation")
		return
	}
	result, replay, err := h.service.CreateScheduledService(ctx.Request.Context(), application.CreateScheduledServiceCommand{
		ServiceRef:        request.ServiceRef,
		CarrierID:         request.CarrierID,
		ServiceNumber:     request.ServiceNumber,
		DepartureTime:     request.DepartureTime,
		ArrivalTime:       request.ArrivalTime,
		OriginNodeID:      request.OriginNodeID,
		DestinationNodeID: request.DestinationNodeID,
		Status:            request.Status,
		IdempotencyKey:    ctx.GetHeader("Idempotency-Key"),
		CorrelationID:     correlationID(ctx),
		CausationID:       ctx.GetHeader("X-Causation-Id"),
		RequestHash:       hashBody(body),
	})
	if replay != nil {
		ctx.JSON(replay.StatusCode, replay.Body)
		return
	}
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
		writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", "limit must be an integer between 1 and 100")
		return
	}
	offset, err := parseBoundedInt(ctx.Query("offset"), 0, 0, int(^uint(0)>>1))
	if err != nil {
		writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", "offset must be a non-negative integer")
		return
	}
	ctx.JSON(http.StatusOK, h.service.ListScheduledServices(application.ListScheduledServicesQuery{Limit: limit, Offset: offset, CarrierID: strings.TrimSpace(ctx.Query("carrierId"))}))
}

func (h Handler) createServiceSegment(ctx *gin.Context) {
	body, ok := readJSONBody(ctx)
	if !ok {
		return
	}
	var request createServiceSegmentRequest
	if err := bindBody(body, &request); err != nil {
		writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", "Request body failed structural validation")
		return
	}
	result, replay, err := h.service.CreateServiceSegment(ctx.Request.Context(), application.CreateServiceSegmentCommand{
		ScheduledServiceRef: request.ScheduledServiceRef,
		OriginStopRef:       request.OriginStopRef,
		DestinationStopRef:  request.DestinationStopRef,
		DepartureTime:       request.DepartureTime,
		ArrivalTime:         request.ArrivalTime,
		IdempotencyKey:      ctx.GetHeader("Idempotency-Key"),
		CorrelationID:       correlationID(ctx),
		CausationID:         ctx.GetHeader("X-Causation-Id"),
		RequestHash:         hashBody(body),
	})
	if replay != nil {
		ctx.JSON(replay.StatusCode, replay.Body)
		return
	}
	if err != nil {
		writeMappedError(ctx, err)
		return
	}
	ctx.JSON(http.StatusCreated, result)
}

func readJSONBody(ctx *gin.Context) ([]byte, bool) {
	body, err := io.ReadAll(ctx.Request.Body)
	if err != nil || len(strings.TrimSpace(string(body))) == 0 {
		writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", "Request body failed structural validation")
		return nil, false
	}
	return body, true
}

func bindBody(body []byte, target any) error {
	return jsonUnmarshalStrict(body, target)
}

func writeMappedError(ctx *gin.Context, err error) {
	switch {
	case errors.Is(err, application.ErrValidation), errors.Is(err, application.ErrIdempotencyKeyRequired):
		writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", err.Error())
	case errors.Is(err, application.ErrNotFound):
		writeError(ctx, http.StatusNotFound, "NOT_FOUND", err.Error())
	case errors.Is(err, application.ErrConflict):
		writeError(ctx, http.StatusConflict, "CONFLICT", err.Error())
	case errors.Is(err, application.ErrIdempotencyKeyReused):
		writeError(ctx, http.StatusUnprocessableEntity, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was reused with a different request body")
	case errors.Is(err, application.ErrDomainRule):
		writeError(ctx, http.StatusUnprocessableEntity, "DOMAIN_RULE_VIOLATION", err.Error())
	case errors.Is(err, application.ErrPublish):
		writeError(ctx, http.StatusServiceUnavailable, "UNAVAILABLE", "Service is temporarily unavailable")
	default:
		writeError(ctx, http.StatusServiceUnavailable, "UNAVAILABLE", "Service is temporarily unavailable")
	}
}

func writeError(ctx *gin.Context, status int, code, message string) {
	ctx.JSON(status, errorBody{Code: code, Message: message, CorrelationID: correlationID(ctx), Details: map[string]any{}})
}

func correlationID(ctx *gin.Context) string {
	if value := ctx.Writer.Header().Get(goruntime.CorrelationIDHeader); value != "" {
		return value
	}
	if value := ctx.GetHeader(goruntime.CorrelationIDHeader); value != "" {
		return value
	}
	return goruntime.CorrelationID(ctx.Request.Context())
}

func hashBody(body []byte) string {
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:])
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
