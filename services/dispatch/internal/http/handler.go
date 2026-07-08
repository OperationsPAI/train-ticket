package http

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/trainticket/greenfield/platform/go-kit/httpkit"
	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/dispatch/internal/application"
)

type Handler struct {
	svc         *application.Service
	idempotency idempotency.Store
}

func NewHandler(svc *application.Service, store idempotency.Store) *Handler {
	return &Handler{svc: svc, idempotency: store}
}
func (h *Handler) RegisterRoutes(r gin.IRouter) {
	v1 := r.Group("/api/v1")
	idem := idempotency.Middleware(h.idempotency)
	v1.POST("/ride-requests", idem, h.Create)
	v1.GET("/ride-requests", h.List)
	v1.GET("/ride-requests/:rideRequestId", h.Get)
	v1.POST("/ride-requests/:rideRequestId/assign", idem, h.Assign)
	v1.POST("/ride-requests/:rideRequestId/eta", idem, h.ETA)
	v1.POST("/ride-requests/:rideRequestId/driver-arrived", idem, h.DriverArrived)
	v1.POST("/ride-requests/:rideRequestId/start", idem, h.Start)
	v1.POST("/ride-requests/:rideRequestId/complete", idem, h.Complete)
	v1.POST("/ride-requests/:rideRequestId/driver-cancel", idem, h.DriverCancel)
	v1.POST("/ride-requests/:rideRequestId/user-cancel", idem, h.UserCancel)
	v1.POST("/ride-requests/:rideRequestId/no-show", idem, h.NoShow)
}
func correlationID(c *gin.Context) string { return goruntime.CorrelationID(c.Request.Context()) }
func causationID(c *gin.Context) string {
	if v, ok := idempotency.FromContext(c); ok {
		return v.Key
	}
	return ""
}
func (h *Handler) Create(c *gin.Context) {
	var req struct {
		PickupRef         string                    `json:"pickupRef" binding:"required"`
		DropoffRef        string                    `json:"dropoffRef" binding:"required"`
		TimeWindow        application.TimeWindowDTO `json:"timeWindow" binding:"required"`
		RiderAccountID    string                    `json:"riderAccountId" binding:"required"`
		TravelerRef       string                    `json:"travelerRef" binding:"required"`
		EstimatedFareRef  string                    `json:"estimatedFareRef"`
		IntentFingerprint string                    `json:"intentFingerprint" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		httpkit.WriteValidation(c, "invalid request body: "+err.Error())
		return
	}
	resp, err := h.svc.CreateRideRequest(c.Request.Context(), application.CreateRideRequest{PickupRef: req.PickupRef, DropoffRef: req.DropoffRef, TimeWindow: req.TimeWindow, RiderAccountID: req.RiderAccountID, TravelerRef: req.TravelerRef, EstimatedFareRef: req.EstimatedFareRef, IntentFingerprint: req.IntentFingerprint, CorrelationID: correlationID(c), CausationID: causationID(c)})
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.JSON(http.StatusCreated, resp)
}
func (h *Handler) Get(c *gin.Context) {
	resp, err := h.svc.GetRideRequest(c.Request.Context(), c.Param("rideRequestId"))
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.JSON(http.StatusOK, resp)
}
func (h *Handler) List(c *gin.Context) {
	limit, err := intQuery(c, "limit", 20)
	if err != nil {
		httpkit.WriteValidation(c, "limit must be an integer")
		return
	}
	offset, err := intQuery(c, "offset", 0)
	if err != nil {
		httpkit.WriteValidation(c, "offset must be an integer")
		return
	}
	resp, err := h.svc.ListRideRequests(c.Request.Context(), c.Query("riderAccountId"), c.Query("status"), limit, offset)
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.JSON(http.StatusOK, resp)
}
func (h *Handler) Assign(c *gin.Context) {
	var req struct {
		DriverRef  string `json:"driverRef" binding:"required"`
		VehicleRef string `json:"vehicleRef" binding:"required"`
		ETASeconds int    `json:"etaSeconds"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		httpkit.WriteValidation(c, "invalid request body: "+err.Error())
		return
	}
	resp, err := h.svc.AssignDriver(c.Request.Context(), application.AssignDriverRequest{RideRequestID: c.Param("rideRequestId"), DriverRef: req.DriverRef, VehicleRef: req.VehicleRef, ETASeconds: req.ETASeconds, CorrelationID: correlationID(c), CausationID: causationID(c)})
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.JSON(http.StatusOK, resp)
}
func (h *Handler) ETA(c *gin.Context) {
	var req struct {
		ETASeconds int `json:"etaSeconds"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		httpkit.WriteValidation(c, "invalid request body: "+err.Error())
		return
	}
	resp, err := h.svc.UpdateETA(c.Request.Context(), application.ETARequest{RideRequestID: c.Param("rideRequestId"), ETASeconds: req.ETASeconds, CorrelationID: correlationID(c), CausationID: causationID(c)})
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.JSON(http.StatusOK, resp)
}
func (h *Handler) DriverArrived(c *gin.Context) {
	resp, err := h.svc.MarkDriverArrived(c.Request.Context(), c.Param("rideRequestId"), correlationID(c), causationID(c))
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.JSON(http.StatusOK, resp)
}
func (h *Handler) Start(c *gin.Context) {
	resp, err := h.svc.StartRide(c.Request.Context(), c.Param("rideRequestId"), correlationID(c), causationID(c))
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.JSON(http.StatusOK, resp)
}
func (h *Handler) Complete(c *gin.Context) {
	var req struct {
		FinalFareRef string `json:"finalFareRef"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		httpkit.WriteValidation(c, "invalid request body: "+err.Error())
		return
	}
	resp, err := h.svc.Complete(c.Request.Context(), application.CompleteRequest{RideRequestID: c.Param("rideRequestId"), FinalFareRef: req.FinalFareRef, CorrelationID: correlationID(c), CausationID: causationID(c)})
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.JSON(http.StatusOK, resp)
}
func (h *Handler) DriverCancel(c *gin.Context) { h.reason(c, h.svc.DriverCancel) }
func (h *Handler) UserCancel(c *gin.Context)   { h.reason(c, h.svc.UserCancel) }
func (h *Handler) NoShow(c *gin.Context)       { h.reason(c, h.svc.NoShow) }
func (h *Handler) reason(c *gin.Context, fn func(context.Context, application.ReasonRequest) (*application.RideRequestDTO, error)) {
	var req struct {
		Reason string `json:"reason"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		httpkit.WriteValidation(c, "invalid request body: "+err.Error())
		return
	}
	resp, err := fn(c.Request.Context(), application.ReasonRequest{RideRequestID: c.Param("rideRequestId"), Reason: req.Reason, CorrelationID: correlationID(c), CausationID: causationID(c)})
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.JSON(http.StatusOK, resp)
}
func intQuery(c *gin.Context, name string, def int) (int, error) {
	raw := strings.TrimSpace(c.Query(name))
	if raw == "" {
		return def, nil
	}
	return strconv.Atoi(raw)
}
func (h *Handler) writeError(c *gin.Context, err error) {
	var app application.Error
	if !errors.As(err, &app) {
		httpkit.WriteError(c, http.StatusInternalServerError, httpkit.Unavailable, "dispatch unavailable", nil)
		return
	}
	status := http.StatusBadRequest
	switch app.Code {
	case "NOT_FOUND":
		status = http.StatusNotFound
	case "CONFLICT":
		status = http.StatusConflict
	case "PRECONDITION_FAILED":
		status = http.StatusPreconditionFailed
	case "UNAVAILABLE":
		status = http.StatusServiceUnavailable
	case "DOMAIN_RULE_VIOLATION":
		status = http.StatusUnprocessableEntity
	}
	httpkit.WriteError(c, status, app.Code, app.Message, nil)
}
