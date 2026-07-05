package http

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	stdhttp "net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/provider-integration/internal/application"
)

type ErrorBody struct {
	Code          string         `json:"code"`
	Message       string         `json:"message"`
	CorrelationID string         `json:"correlationId"`
	Details       map[string]any `json:"details"`
}

type Handler struct {
	service     application.ProviderReservationService
	idempotency *application.IdempotencyStore
}

func NewHandler(service application.ProviderReservationService, idempotency *application.IdempotencyStore) Handler {
	return Handler{service: service, idempotency: idempotency}
}

func (h Handler) Register(router gin.IRouter) {
	router.POST("/api/v1/internal/provider-reservations", h.requestReservation)
	router.POST("/api/v1/internal/provider-reservations/:segmentBookingId/cancel", h.cancelReservation)
}

func (h Handler) requestReservation(ctx *gin.Context) {
	var cmd application.RequestProviderReservationCommand
	body, ok := bindJSON(ctx, &cmd)
	if !ok {
		return
	}
	idempotencyKey, ok := h.requireIdempotencyKey(ctx)
	if !ok {
		return
	}
	cmd.CorrelationID = goruntime.CorrelationID(ctx.Request.Context())
	requestHash, _ := application.HashJSON(struct {
		Method string `json:"method"`
		Path   string `json:"path"`
		Body   string `json:"body"`
	}{ctx.Request.Method, ctx.Request.URL.Path, string(body)})
	if h.replay(ctx, idempotencyKey, requestHash) {
		return
	}
	result, err := h.service.RequestReservation(ctx.Request.Context(), cmd)
	if err != nil {
		h.writeError(ctx, err)
		return
	}
	h.writeIdempotentJSON(ctx, idempotencyKey, requestHash, stdhttp.StatusAccepted, result)
}

func (h Handler) cancelReservation(ctx *gin.Context) {
	key, ok := h.requireIdempotencyKey(ctx)
	if !ok {
		return
	}
	cmd := application.CancelProviderReservationCommand{
		SegmentBookingID: strings.TrimSpace(ctx.Param("segmentBookingId")),
		CorrelationID:    goruntime.CorrelationID(ctx.Request.Context()),
	}
	requestHash, _ := application.HashJSON(struct {
		Method string `json:"method"`
		Path   string `json:"path"`
	}{ctx.Request.Method, ctx.Request.URL.Path})
	if h.replay(ctx, key, requestHash) {
		return
	}
	result, err := h.service.CancelReservation(ctx.Request.Context(), cmd)
	if err != nil {
		h.writeError(ctx, err)
		return
	}
	h.writeIdempotentJSON(ctx, key, requestHash, stdhttp.StatusOK, result)
}

func (h Handler) requireIdempotencyKey(ctx *gin.Context) (string, bool) {
	key := strings.TrimSpace(ctx.GetHeader("Idempotency-Key"))
	if key == "" {
		h.writeError(ctx, errors.Join(application.ErrValidation, errors.New("Idempotency-Key header is required")))
		return "", false
	}
	parsed, err := uuid.Parse(key)
	if err != nil || parsed.Version() != 7 {
		h.writeError(ctx, errors.Join(application.ErrValidation, errors.New("Idempotency-Key header must be a UUID v7")))
		return "", false
	}
	return parsed.String(), true
}

func bindJSON(ctx *gin.Context, target any) ([]byte, bool) {
	body, err := io.ReadAll(ctx.Request.Body)
	if err != nil {
		writeErrorBody(ctx, stdhttp.StatusBadRequest, "VALIDATION_FAILED", "invalid request body")
		return nil, false
	}
	ctx.Request.Body = io.NopCloser(bytes.NewReader(body))
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		writeErrorBody(ctx, stdhttp.StatusBadRequest, "VALIDATION_FAILED", "invalid JSON request body")
		return nil, false
	}
	return body, true
}

func (h Handler) replay(ctx *gin.Context, key, hash string) bool {
	if strings.TrimSpace(key) == "" {
		return false
	}
	status, body, ok, err := h.idempotency.Replay(key, hash)
	if err != nil {
		writeErrorBody(ctx, 422, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was reused with a different request body")
		return true
	}
	if !ok {
		return false
	}
	ctx.Data(status, "application/json", body)
	return true
}

func (h Handler) writeIdempotentJSON(ctx *gin.Context, key, requestHash string, status int, body any) {
	bytes, err := json.Marshal(body)
	if err != nil {
		h.writeError(ctx, application.ErrUnavailable)
		return
	}
	h.idempotency.Save(key, requestHash, status, bytes)
	ctx.Data(status, "application/json", bytes)
}

func (h Handler) writeError(ctx *gin.Context, err error) {
	switch {
	case errors.Is(err, application.ErrValidation):
		writeErrorBody(ctx, stdhttp.StatusBadRequest, "VALIDATION_FAILED", err.Error())
	case errors.Is(err, application.ErrNotFound):
		writeErrorBody(ctx, stdhttp.StatusNotFound, "NOT_FOUND", err.Error())
	case errors.Is(err, application.ErrIdempotencyConflict):
		writeErrorBody(ctx, 422, "IDEMPOTENCY_KEY_REUSED", err.Error())
	default:
		writeErrorBody(ctx, stdhttp.StatusServiceUnavailable, "UNAVAILABLE", "provider integration is unavailable")
	}
}

func writeErrorBody(ctx *gin.Context, status int, code, message string) {
	ctx.JSON(status, ErrorBody{
		Code:          code,
		Message:       message,
		CorrelationID: goruntime.CorrelationID(ctx.Request.Context()),
		Details:       map[string]any{},
	})
}
