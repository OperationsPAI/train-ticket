package http

import (
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/trainticket/greenfield/platform/go-kit/httpkit"
	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/place-network/internal/application"
	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

type Handler struct {
	svc         *application.Service
	idempotency idempotency.Store
}

func NewHandler(svc *application.Service, store idempotency.Store) *Handler {
	return &Handler{svc: svc, idempotency: store}
}

func (h *Handler) RegisterRoutes(router gin.IRouter) {
	v1 := router.Group("/api/v1")
	idempotent := idempotency.Middleware(h.idempotency)
	v1.POST("/places", idempotent, h.CreatePlace)
	v1.GET("/places", h.ListPlaces)
	v1.GET("/places/:placeId", h.GetPlace)
	v1.POST("/transport-nodes", idempotent, h.CreateTransportNode)
	v1.GET("/transport-nodes/:nodeId", h.GetTransportNode)
}

type errorResponse = httpkit.ErrorBody

func correlationID(ctx *gin.Context) string {
	return goruntime.CorrelationID(ctx.Request.Context())
}

func (h *Handler) CreatePlace(ctx *gin.Context) {
	var req struct {
		PlaceType     string `json:"placeType" binding:"required"`
		CanonicalName string `json:"canonicalName" binding:"required"`
		Code          string `json:"code"`
		Timezone      string `json:"timezone"`
	}
	if err := ctx.ShouldBindJSON(&req); err != nil {
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, "invalid request body: "+err.Error(), nil)
		return
	}
	resp, err := h.svc.CreatePlace(ctx.Request.Context(), application.CreatePlaceRequest{PlaceType: req.PlaceType, CanonicalName: req.CanonicalName, Code: req.Code, Timezone: req.Timezone, CorrelationID: correlationID(ctx)})
	if err != nil {
		h.writeApplicationError(ctx, err)
		return
	}
	ctx.JSON(http.StatusCreated, resp)
}

func (h *Handler) GetPlace(ctx *gin.Context) {
	resp, err := h.svc.GetPlace(ctx.Request.Context(), domain.PlaceID(ctx.Param("placeId")))
	if err != nil {
		h.writeApplicationError(ctx, err)
		return
	}
	ctx.JSON(http.StatusOK, resp)
}

func (h *Handler) ListPlaces(ctx *gin.Context) {
	limit, err := intQuery(ctx, "limit", 20)
	if err != nil {
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, "limit must be an integer", nil)
		return
	}
	offset, err := intQuery(ctx, "offset", 0)
	if err != nil {
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, "offset must be an integer", nil)
		return
	}
	resp, err := h.svc.ListPlaces(ctx.Request.Context(), application.ListPlacesRequest{Limit: limit, Offset: offset, Status: ctx.Query("status")})
	if err != nil {
		h.writeApplicationError(ctx, err)
		return
	}
	ctx.JSON(http.StatusOK, resp)
}

func (h *Handler) CreateTransportNode(ctx *gin.Context) {
	var req struct {
		PlaceID           string   `json:"placeId" binding:"required"`
		DisplayName       string   `json:"displayName" binding:"required"`
		ServingModes      []string `json:"servingModes" binding:"required"`
		AccessTimeMinutes *int     `json:"accessTimeMinutes"`
		WalkingEdges      []struct {
			ToNodeID           string `json:"toNodeId" binding:"required"`
			WalkingTimeMinutes *int   `json:"walkingTimeMinutes,omitempty"`
		} `json:"walkingEdges"`
	}
	if err := ctx.ShouldBindJSON(&req); err != nil {
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, "invalid request body: "+err.Error(), nil)
		return
	}
	walkingEdges := make([]application.WalkingEdgeRequest, len(req.WalkingEdges))
	for i, edge := range req.WalkingEdges {
		walkingEdges[i] = application.WalkingEdgeRequest{ToNodeID: edge.ToNodeID, WalkingTimeMinutes: edge.WalkingTimeMinutes}
	}
	resp, err := h.svc.CreateTransportNode(ctx.Request.Context(), application.CreateTransportNodeRequest{PlaceID: req.PlaceID, DisplayName: req.DisplayName, ServingModes: req.ServingModes, AccessTimeMinutes: req.AccessTimeMinutes, WalkingEdges: walkingEdges, CorrelationID: correlationID(ctx)})
	if err != nil {
		h.writeApplicationError(ctx, err)
		return
	}
	ctx.JSON(http.StatusCreated, resp)
}

func (h *Handler) GetTransportNode(ctx *gin.Context) {
	resp, err := h.svc.GetTransportNode(ctx.Request.Context(), domain.TransportNodeID(ctx.Param("nodeId")))
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
		httpkit.WriteError(ctx, http.StatusInternalServerError, httpkit.Unavailable, "internal error", nil)
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
	httpkit.WriteError(ctx, status, appErr.Code, appErr.Message, nil)
}
