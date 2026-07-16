package api

import (
	"fmt"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/trainticket/greenfield/platform/go-kit/httpkit"
	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/application"
)

type Handler struct {
	svc  *application.Service
	idem idempotency.Store
}

func NewHandler(svc *application.Service, idem idempotency.Store) *Handler {
	return &Handler{svc: svc, idem: idem}
}
func (h *Handler) RegisterRoutes(r gin.IRouter) {
	v := r.Group("/api/v1")
	m := idempotency.Middleware(h.idem)
	v.POST("/seat-assignments", m, h.assign)
	v.GET("/seat-assignments/:segmentRef/:departureDate/availability", h.availability)
	v.DELETE("/seat-assignments/:assignmentId", h.release)
	v.POST("/seat-assignments/:assignmentId/confirm", m, h.confirm)
	v.POST("/seat-maps", m, h.createSeatMap)
	v.GET("/seat-maps", h.listSeatMaps)
	v.GET("/seat-maps/:seatMapId", h.getSeatMap)
	v.POST("/seat-maps/:seatMapId/publish", m, h.publishSeatMap)
	v.POST("/seat-maps/:seatMapId/retire", m, h.retireSeatMap)
	v.POST("/seat-maps/:seatMapId/seat-units/:seatUnitRef/mark-unavailable", m, h.markSeatUnitUnavailable)
	v.POST("/seat-maps/:seatMapId/seat-units/:seatUnitRef/reopen", m, h.reopenSeatUnit)
	v.POST("/internal/seat-allocations", m, h.allocateSeat)
	v.GET("/seat-allocations/:seatAllocationId", h.getSeatAllocation)
	v.GET("/seat-allocations", h.listSeatAllocations)
}
func (h *Handler) assign(c *gin.Context) {
	var req application.AssignSeatsRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		httpkit.WriteError(c, http.StatusBadRequest, httpkit.ValidationFailed, "invalid request body: "+err.Error(), nil)
		return
	}
	req.CorrelationID = goruntime.CorrelationID(c.Request.Context())
	resp, err := h.svc.AssignSeats(c.Request.Context(), req)
	h.write(c, http.StatusCreated, resp, err)
}
func (h *Handler) availability(c *gin.Context) {
	resp, err := h.svc.Availability(c.Request.Context(), c.Param("segmentRef"), c.Param("departureDate"))
	h.write(c, http.StatusOK, resp, err)
}
func (h *Handler) release(c *gin.Context) {
	err := h.svc.Release(c.Request.Context(), c.Param("assignmentId"), goruntime.CorrelationID(c.Request.Context()), "")
	h.write(c, http.StatusOK, gin.H{"released": err == nil}, err)
}
func (h *Handler) confirm(c *gin.Context) {
	var req struct {
		HoldId string `json:"holdId"`
	}
	_ = c.ShouldBindJSON(&req)
	resp, err := h.svc.Confirm(c.Request.Context(), c.Param("assignmentId"), req.HoldId, goruntime.CorrelationID(c.Request.Context()), "")
	if err != nil {
		h.write(c, http.StatusOK, nil, err)
		return
	}
	h.write(c, http.StatusOK, gin.H{"confirmed": true, "seatId": resp.SeatId, "travelerRef": resp.TravelerRef}, nil)
}

func (h *Handler) createSeatMap(c *gin.Context) {
	var req application.CreateSeatMapRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		httpkit.WriteError(c, http.StatusBadRequest, httpkit.ValidationFailed, "invalid request body: "+err.Error(), nil)
		return
	}
	req.CorrelationID = goruntime.CorrelationID(c.Request.Context())
	resp, err := h.svc.CreateSeatMap(c.Request.Context(), req)
	h.write(c, http.StatusCreated, resp, err)
}
func (h *Handler) publishSeatMap(c *gin.Context) {
	var req application.PublishSeatMapRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		httpkit.WriteError(c, http.StatusBadRequest, httpkit.ValidationFailed, "invalid request body: "+err.Error(), nil)
		return
	}
	req.CorrelationID = goruntime.CorrelationID(c.Request.Context())
	resp, err := h.svc.PublishSeatMap(c.Request.Context(), c.Param("seatMapId"), req)
	h.write(c, http.StatusOK, resp, err)
}
func (h *Handler) retireSeatMap(c *gin.Context) {
	var req application.RetireSeatMapRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		httpkit.WriteError(c, http.StatusBadRequest, httpkit.ValidationFailed, "invalid request body: "+err.Error(), nil)
		return
	}
	req.CorrelationID = goruntime.CorrelationID(c.Request.Context())
	resp, err := h.svc.RetireSeatMap(c.Request.Context(), c.Param("seatMapId"), req)
	h.write(c, http.StatusOK, resp, err)
}
func (h *Handler) markSeatUnitUnavailable(c *gin.Context) {
	var req application.MarkSeatUnitUnavailableRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		httpkit.WriteError(c, http.StatusBadRequest, httpkit.ValidationFailed, "invalid request body: "+err.Error(), nil)
		return
	}
	req.CorrelationID = goruntime.CorrelationID(c.Request.Context())
	resp, err := h.svc.MarkSeatUnitUnavailable(c.Request.Context(), c.Param("seatMapId"), c.Param("seatUnitRef"), req)
	h.write(c, http.StatusOK, resp, err)
}
func (h *Handler) reopenSeatUnit(c *gin.Context) {
	var req application.ReopenSeatUnitRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		httpkit.WriteError(c, http.StatusBadRequest, httpkit.ValidationFailed, "invalid request body: "+err.Error(), nil)
		return
	}
	req.CorrelationID = goruntime.CorrelationID(c.Request.Context())
	resp, err := h.svc.ReopenSeatUnit(c.Request.Context(), c.Param("seatMapId"), c.Param("seatUnitRef"), req)
	h.write(c, http.StatusOK, resp, err)
}
func (h *Handler) getSeatMap(c *gin.Context) {
	resp, err := h.svc.GetSeatMap(c.Request.Context(), c.Param("seatMapId"))
	h.write(c, http.StatusOK, resp, err)
}
func (h *Handler) listSeatMaps(c *gin.Context) {
	resp, err := h.svc.ListSeatMaps(c.Request.Context(), c.Query("scheduledServiceRef"), c.Query("serviceDate"), c.Query("status"), atoiDefault(c.Query("limit"), 20), atoiDefault(c.Query("offset"), 0))
	h.write(c, http.StatusOK, resp, err)
}
func (h *Handler) allocateSeat(c *gin.Context) {
	var req application.AllocateSeatRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		httpkit.WriteError(c, http.StatusBadRequest, httpkit.ValidationFailed, "invalid request body: "+err.Error(), nil)
		return
	}
	req.CorrelationID = goruntime.CorrelationID(c.Request.Context())
	resp, err := h.svc.AllocateSeat(c.Request.Context(), req)
	h.write(c, http.StatusCreated, resp, err)
}
func (h *Handler) getSeatAllocation(c *gin.Context) {
	resp, err := h.svc.GetSeatAllocation(c.Request.Context(), c.Param("seatAllocationId"))
	h.write(c, http.StatusOK, resp, err)
}
func (h *Handler) listSeatAllocations(c *gin.Context) {
	resp, err := h.svc.ListSeatAllocations(c.Request.Context(), c.Query("segmentBookingId"), c.Query("status"), atoiDefault(c.Query("limit"), 20), atoiDefault(c.Query("offset"), 0))
	h.write(c, http.StatusOK, resp, err)
}
func atoiDefault(raw string, def int) int {
	var v int
	if _, err := fmt.Sscanf(raw, "%d", &v); err != nil {
		return def
	}
	return v
}

func (h *Handler) write(c *gin.Context, status int, body any, err error) {
	if err == nil {
		c.JSON(status, body)
		return
	}
	if e, ok := err.(*application.DomainError); ok {
		code := http.StatusUnprocessableEntity
		switch e.Code {
		case "VALIDATION_FAILED":
			code = http.StatusBadRequest
		case "NOT_FOUND":
			code = http.StatusNotFound
		case "CONFLICT":
			code = http.StatusConflict
		case "PRECONDITION_FAILED":
			code = http.StatusPreconditionFailed
		case "UNAVAILABLE":
			code = http.StatusServiceUnavailable
		}
		httpkit.WriteError(c, code, e.Code, e.Message, nil)
		return
	}
	httpkit.WriteError(c, http.StatusInternalServerError, httpkit.Unavailable, "internal error", nil)
}
