package http

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/trainticket/greenfield/platform/go-kit/httpkit"
	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/place-network/internal/application"
	"github.com/trainticket/greenfield/services/place-network/internal/domain"
	"github.com/trainticket/greenfield/services/place-network/internal/domain/ports"
)

type Handler struct {
	svc         *application.Service
	idempotency ports.IdempotencyStore
}

func NewHandler(svc *application.Service, idempotency ports.IdempotencyStore) *Handler {
	return &Handler{svc: svc, idempotency: idempotency}
}

func (h *Handler) RegisterRoutes(router gin.IRouter) {
	v1 := router.Group("/api/v1")
	v1.POST("/places", h.CreatePlace)
	v1.GET("/places", h.ListPlaces)
	v1.GET("/places/:placeId", h.GetPlace)
	v1.POST("/transport-nodes", h.CreateTransportNode)
	v1.GET("/transport-nodes/:nodeId", h.GetTransportNode)
}

type errorResponse = httpkit.ErrorBody

func writeError(ctx *gin.Context, status int, code, message string) {
	httpkit.WriteError(ctx, status, code, message, nil)
}

func correlationID(ctx *gin.Context) string {
	return goruntime.CorrelationID(ctx.Request.Context())
}

func idempotencyKey(ctx *gin.Context) string {
	return strings.TrimSpace(ctx.GetHeader("Idempotency-Key"))
}

func validUUIDv7(value string) bool {
	return idempotency.ValidateKey(value)
}

type idempotencyOutcome struct {
	key  string
	hash string
	done bool
}

func (h *Handler) beginIdempotent(ctx *gin.Context, body any) (*idempotencyOutcome, bool) {
	key := idempotencyKey(ctx)
	if key == "" {
		writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", "Idempotency-Key header is required")
		return nil, false
	}
	if !validUUIDv7(key) {
		writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", "Idempotency-Key header must be a UUID v7")
		return nil, false
	}
	hash, err := requestHash(body)
	if err != nil {
		writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", "invalid request body")
		return nil, false
	}
	if record, exists := h.idempotency.Get(key); exists {
		if record.RequestHash != hash {
			writeError(ctx, http.StatusUnprocessableEntity, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was reused with a different request body")
			return nil, false
		}
		ctx.JSON(record.StatusCode, record.Response)
		return &idempotencyOutcome{done: true}, true
	}
	return &idempotencyOutcome{key: key, hash: hash}, true
}

func (h *Handler) finishIdempotent(outcome *idempotencyOutcome, status int, response any) {
	if outcome == nil || outcome.done {
		return
	}
	_ = h.idempotency.Put(outcome.key, ports.IdempotencyRecord{RequestHash: outcome.hash, StatusCode: status, Response: response})
}

func requestHash(body any) (string, error) {
	data, err := json.Marshal(body)
	if err != nil {
		return "", err
	}
	return idempotency.BodyFingerprint(data), nil
}

func (h *Handler) CreatePlace(ctx *gin.Context) {
	var req struct {
		PlaceType     string `json:"placeType" binding:"required"`
		CanonicalName string `json:"canonicalName" binding:"required"`
		Code          string `json:"code"`
		Timezone      string `json:"timezone"`
	}
	if err := ctx.ShouldBindJSON(&req); err != nil {
		writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", "invalid request body: "+err.Error())
		return
	}
	outcome, ok := h.beginIdempotent(ctx, req)
	if !ok || outcome.done {
		return
	}
	resp, err := h.svc.CreatePlace(application.CreatePlaceRequest{PlaceType: req.PlaceType, CanonicalName: req.CanonicalName, Code: req.Code, Timezone: req.Timezone, CorrelationID: correlationID(ctx)})
	if err != nil {
		h.writeApplicationError(ctx, err)
		return
	}
	h.finishIdempotent(outcome, http.StatusCreated, resp)
	ctx.JSON(http.StatusCreated, resp)
}

func (h *Handler) GetPlace(ctx *gin.Context) {
	resp, err := h.svc.GetPlace(domain.PlaceID(ctx.Param("placeId")))
	if err != nil {
		h.writeApplicationError(ctx, err)
		return
	}
	ctx.JSON(http.StatusOK, resp)
}

func (h *Handler) ListPlaces(ctx *gin.Context) {
	limit, err := intQuery(ctx, "limit", 20)
	if err != nil {
		writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", "limit must be an integer")
		return
	}
	offset, err := intQuery(ctx, "offset", 0)
	if err != nil {
		writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", "offset must be an integer")
		return
	}
	resp, err := h.svc.ListPlaces(application.ListPlacesRequest{Limit: limit, Offset: offset, Status: ctx.Query("status")})
	if err != nil {
		h.writeApplicationError(ctx, err)
		return
	}
	ctx.JSON(http.StatusOK, resp)
}

func (h *Handler) CreateTransportNode(ctx *gin.Context) {
	var req struct {
		PlaceID      string   `json:"placeId" binding:"required"`
		DisplayName  string   `json:"displayName" binding:"required"`
		ServingModes []string `json:"servingModes" binding:"required"`
	}
	if err := ctx.ShouldBindJSON(&req); err != nil {
		writeError(ctx, http.StatusBadRequest, "VALIDATION_FAILED", "invalid request body: "+err.Error())
		return
	}
	outcome, ok := h.beginIdempotent(ctx, req)
	if !ok || outcome.done {
		return
	}
	resp, err := h.svc.CreateTransportNode(application.CreateTransportNodeRequest{PlaceID: req.PlaceID, DisplayName: req.DisplayName, ServingModes: req.ServingModes, CorrelationID: correlationID(ctx)})
	if err != nil {
		h.writeApplicationError(ctx, err)
		return
	}
	h.finishIdempotent(outcome, http.StatusCreated, resp)
	ctx.JSON(http.StatusCreated, resp)
}

func (h *Handler) GetTransportNode(ctx *gin.Context) {
	resp, err := h.svc.GetTransportNode(domain.TransportNodeID(ctx.Param("nodeId")))
	if err != nil {
		h.writeApplicationError(ctx, err)
		return
	}
	ctx.JSON(http.StatusOK, resp)
}

func intQuery(ctx *gin.Context, name string, defaultValue int) (int, error) {
	raw := strings.TrimSpace(ctx.Query(name))
	if raw == "" {
		return defaultValue, nil
	}
	return strconv.Atoi(raw)
}

func (h *Handler) writeApplicationError(ctx *gin.Context, err error) {
	appErr, ok := err.(*application.DomainError)
	if !ok {
		writeError(ctx, http.StatusInternalServerError, "UNAVAILABLE", "internal error")
		return
	}
	status := http.StatusUnprocessableEntity
	switch appErr.Code {
	case "VALIDATION_FAILED":
		status = http.StatusBadRequest
	case "NOT_FOUND":
		status = http.StatusNotFound
	case "CONFLICT":
		status = http.StatusConflict
	case "UNAVAILABLE":
		status = http.StatusServiceUnavailable
	}
	writeError(ctx, status, appErr.Code, appErr.Message)
}
