package api

import (
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
		case "UNAVAILABLE":
			code = http.StatusServiceUnavailable
		}
		httpkit.WriteError(c, code, e.Code, e.Message, nil)
		return
	}
	httpkit.WriteError(c, http.StatusInternalServerError, httpkit.Unavailable, "internal error", nil)
}
