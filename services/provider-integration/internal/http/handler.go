package http

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	stdhttp "net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/trainticket/greenfield/platform/go-kit/httpkit"
	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/provider-integration/internal/application"
)

type ErrorBody = httpkit.ErrorBody

type Handler struct {
	service     application.ProviderReservationService
	idempotency idempotency.Store
}

func NewHandler(service application.ProviderReservationService, store idempotency.Store) Handler {
	return Handler{service: service, idempotency: store}
}

func (h Handler) Register(router gin.IRouter) {
	idempotent := idempotency.Middleware(h.idempotency)
	router.POST("/api/v1/internal/provider-reservations", idempotent, h.requestReservation)
	router.POST("/api/v1/internal/provider-reservations/:segmentBookingId/cancel", idempotent, h.cancelReservation)
}

func (h Handler) requestReservation(ctx *gin.Context) {
	var cmd application.RequestProviderReservationCommand
	if !bindJSON(ctx, &cmd) {
		return
	}
	metadata, _ := idempotency.FromContext(ctx)
	cmd.CorrelationID = goruntime.CorrelationID(ctx.Request.Context())
	cmd.IdempotencyKey = metadata.Key
	result, err := h.service.RequestReservation(ctx.Request.Context(), cmd)
	if err != nil {
		h.writeError(ctx, err)
		return
	}
	h.writeCachedJSON(ctx, stdhttp.StatusAccepted, result)
}

func (h Handler) cancelReservation(ctx *gin.Context) {
	metadata, _ := idempotency.FromContext(ctx)
	cmd := application.CancelProviderReservationCommand{
		SegmentBookingID: strings.TrimSpace(ctx.Param("segmentBookingId")),
		CorrelationID:    goruntime.CorrelationID(ctx.Request.Context()),
		IdempotencyKey:   metadata.Key,
	}
	result, err := h.service.CancelReservation(ctx.Request.Context(), cmd)
	if err != nil {
		h.writeError(ctx, err)
		return
	}
	h.writeCachedJSON(ctx, stdhttp.StatusOK, result)
}

func bindJSON(ctx *gin.Context, target any) bool {
	body, err := io.ReadAll(ctx.Request.Body)
	if err != nil {
		httpkit.WriteValidation(ctx, "invalid request body")
		return false
	}
	ctx.Request.Body = io.NopCloser(bytes.NewReader(body))
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		httpkit.WriteValidation(ctx, "invalid JSON request body")
		return false
	}
	return true
}

func (h Handler) writeCachedJSON(ctx *gin.Context, status int, body any) {
	metadata, _ := idempotency.FromContext(ctx)
	bytes, err := idempotency.StoreJSON(ctx.Request.Context(), h.idempotency, metadata.Key, metadata.Fingerprint, status, body)
	if err != nil {
		h.writeError(ctx, application.ErrUnavailable)
		return
	}
	ctx.Data(status, "application/json", bytes)
}

func (h Handler) writeError(ctx *gin.Context, err error) {
	switch {
	case errors.Is(err, application.ErrValidation):
		httpkit.WriteError(ctx, stdhttp.StatusBadRequest, httpkit.ValidationFailed, err.Error(), nil)
	case errors.Is(err, application.ErrNotFound):
		httpkit.WriteError(ctx, stdhttp.StatusNotFound, httpkit.NotFound, err.Error(), nil)
	case errors.Is(err, idempotency.ErrKeyReused):
		httpkit.WriteIdempotencyReused(ctx)
	case errors.Is(err, application.ErrDomainRule):
		httpkit.WriteError(ctx, stdhttp.StatusUnprocessableEntity, httpkit.DomainRuleViolation, err.Error(), nil)
	default:
		httpkit.WriteError(ctx, stdhttp.StatusServiceUnavailable, httpkit.Unavailable, "provider integration is unavailable", nil)
	}
}
