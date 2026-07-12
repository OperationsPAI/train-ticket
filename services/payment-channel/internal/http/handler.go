package http

import (
	"github.com/gin-gonic/gin"
	"github.com/trainticket/greenfield/platform/go-kit/httpkit"
	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/payment-channel/internal/application"
	"github.com/trainticket/greenfield/services/payment-channel/internal/domain"
	"net/http"
	"strconv"
	"strings"
)

type Handler struct {
	svc  *application.Service
	idem idempotency.Store
}

func New(s *application.Service, idem idempotency.Store) *Handler { return &Handler{s, idem} }
func (h *Handler) RegisterRoutes(r gin.IRouter) {
	v := r.Group("/api/v1")
	m := idempotency.Middleware(h.idem)
	v.POST("/channel-orders", m, h.createOrder)
	v.POST("/channel-orders/:id/submit", m, h.submitOrder)
	v.POST("/channel-orders/:id/query", m, h.queryOrder)
	v.GET("/channel-orders/:id", h.getOrder)
	v.POST("/channel-refunds", m, h.createRefund)
	v.POST("/channel-refunds/:id/submit", m, h.submitRefund)
	v.POST("/channel-refunds/:id/query", m, h.queryRefund)
	v.GET("/channel-refunds/:id", h.getRefund)
	v.POST("/channel-statements/generate", m, h.generateStatement)
	v.POST("/channel-statements/:id/freeze", m, h.freezeStatement)
	v.GET("/channel-statements/:id", h.getStatement)
	v.GET("/channel-statements", h.listStatements)
	v.POST("/channel-discrepancies", m, h.openDiscrepancy)
	v.POST("/channel-discrepancies/:id/resolve", m, h.resolveDiscrepancy)
	v.GET("/channel-discrepancies/:id", h.getDiscrepancy)
	v.GET("/channel-discrepancies", h.listDiscrepancies)
}
func corr(c *gin.Context) string { return goruntime.CorrelationID(c.Request.Context()) }
func idem(c *gin.Context) string { return c.GetHeader("Idempotency-Key") }
func (h *Handler) createOrder(c *gin.Context) {
	var r struct {
		PaymentIntentID, BusinessRef, Purpose, Channel, SourceCommandID, CorrelationID string
		Amount                                                                         domain.Money
		FaultSeed                                                                      *domain.FaultSeed
	}
	if bind(c, &r) {
		return
	}
	if r.CorrelationID == "" {
		r.CorrelationID = corr(c)
	}
	out, err := h.svc.CreateOrder(c.Request.Context(), application.CreateOrderRequest{PaymentIntentID: r.PaymentIntentID, BusinessRef: r.BusinessRef, Purpose: r.Purpose, Channel: r.Channel, Amount: r.Amount, IdempotencyKey: idem(c), SourceCommandID: r.SourceCommandID, CorrelationID: r.CorrelationID, FaultSeed: r.FaultSeed})
	h.write(c, http.StatusCreated, out, err)
}
func (h *Handler) submitOrder(c *gin.Context) {
	var r struct {
		ExpectedVersion    int64
		RequestFingerprint string
	}
	if bind(c, &r) {
		return
	}
	out, err := h.svc.SubmitOrder(c.Request.Context(), c.Param("id"), r.ExpectedVersion, r.RequestFingerprint)
	h.write(c, http.StatusOK, out, err)
}
func (h *Handler) queryOrder(c *gin.Context) {
	var r struct {
		ExpectedVersion int64
		QueryReasonCode string
	}
	if bind(c, &r) {
		return
	}
	out, err := h.svc.QueryOrder(c.Request.Context(), c.Param("id"), r.ExpectedVersion, r.QueryReasonCode)
	h.write(c, http.StatusOK, out, err)
}
func (h *Handler) getOrder(c *gin.Context) {
	out, err := h.svc.GetOrder(c.Request.Context(), c.Param("id"))
	h.write(c, http.StatusOK, out, err)
}
func (h *Handler) createRefund(c *gin.Context) {
	var r struct {
		RefundID, PaymentIntentID, ChannelOrderID, OriginalChannelTransactionID, Channel, RefundReasonCode, SourceCommandID, CorrelationID string
		Amount                                                                                                                             domain.Money
		FaultSeed                                                                                                                          *domain.FaultSeed
	}
	if bind(c, &r) {
		return
	}
	if r.CorrelationID == "" {
		r.CorrelationID = corr(c)
	}
	out, err := h.svc.CreateRefund(c.Request.Context(), application.CreateRefundRequest{RefundID: r.RefundID, PaymentIntentID: r.PaymentIntentID, ChannelOrderID: r.ChannelOrderID, OriginalChannelTransactionID: r.OriginalChannelTransactionID, Channel: r.Channel, Amount: r.Amount, RefundReasonCode: r.RefundReasonCode, IdempotencyKey: idem(c), SourceCommandID: r.SourceCommandID, CorrelationID: r.CorrelationID, FaultSeed: r.FaultSeed})
	h.write(c, http.StatusCreated, out, err)
}
func (h *Handler) submitRefund(c *gin.Context) {
	var r struct {
		ExpectedVersion    int64
		RequestFingerprint string
	}
	if bind(c, &r) {
		return
	}
	out, err := h.svc.SubmitRefund(c.Request.Context(), c.Param("id"), r.ExpectedVersion, r.RequestFingerprint)
	h.write(c, http.StatusOK, out, err)
}
func (h *Handler) queryRefund(c *gin.Context) {
	var r struct {
		ExpectedVersion int64
		QueryReasonCode string
	}
	if bind(c, &r) {
		return
	}
	out, err := h.svc.QueryRefund(c.Request.Context(), c.Param("id"), r.ExpectedVersion, r.QueryReasonCode)
	h.write(c, http.StatusOK, out, err)
}
func (h *Handler) getRefund(c *gin.Context) {
	out, err := h.svc.GetRefund(c.Request.Context(), c.Param("id"))
	h.write(c, http.StatusOK, out, err)
}
func (h *Handler) generateStatement(c *gin.Context) {
	var r struct {
		Channel, StatementDate, Currency, SeedVersion, OperatorRef, ReasonCode string
		ScenarioCodes                                                          []string
	}
	if bind(c, &r) {
		return
	}
	out, err := h.svc.GenerateStatement(c.Request.Context(), application.GenerateStatementRequest{Channel: r.Channel, StatementDate: r.StatementDate, Currency: r.Currency, SeedVersion: r.SeedVersion, OperatorRef: r.OperatorRef, ReasonCode: r.ReasonCode, CorrelationID: corr(c), SourceCommandID: domain.NewID("cmd"), ScenarioCodes: r.ScenarioCodes})
	h.write(c, http.StatusCreated, out, err)
}
func (h *Handler) freezeStatement(c *gin.Context) {
	var r struct {
		StatementHash           string
		ExpectedVersion         int64
		OperatorRef, ReasonCode string
	}
	if bind(c, &r) {
		return
	}
	out, err := h.svc.FreezeStatement(c.Request.Context(), c.Param("id"), r.StatementHash, r.ExpectedVersion, r.OperatorRef, r.ReasonCode, corr(c), domain.NewID("cmd"))
	h.write(c, http.StatusOK, out, err)
}
func (h *Handler) getStatement(c *gin.Context) {
	out, err := h.svc.GetStatement(c.Request.Context(), c.Param("id"))
	h.write(c, http.StatusOK, out, err)
}
func (h *Handler) listStatements(c *gin.Context) {
	f := application.StatementFilter{Channel: c.Query("channel"), StatementDate: c.Query("statementDate"), Currency: c.Query("currency"), Status: c.Query("status"), Limit: intq(c, "limit", 20), Offset: intq(c, "offset", 0)}
	items, total, err := h.svc.ListStatements(c.Request.Context(), f)
	h.write(c, http.StatusOK, gin.H{"items": items, "total": total, "limit": f.Limit, "offset": f.Offset}, err)
}
func (h *Handler) openDiscrepancy(c *gin.Context) {
	var r struct {
		ChannelStatementID, StatementLineID, ChannelOrderID, ChannelRefundID, FinanceReconciliationCaseID, DifferenceType, EvidenceRef string
		ExpectedAmount, ActualAmount                                                                                                   domain.Money
	}
	if bind(c, &r) {
		return
	}
	out, err := h.svc.OpenDiscrepancy(c.Request.Context(), application.OpenDiscrepancyRequest{ChannelStatementID: r.ChannelStatementID, StatementLineID: r.StatementLineID, ChannelOrderID: r.ChannelOrderID, ChannelRefundID: r.ChannelRefundID, FinanceReconciliationCaseID: r.FinanceReconciliationCaseID, DifferenceType: r.DifferenceType, ExpectedAmount: r.ExpectedAmount, ActualAmount: r.ActualAmount, EvidenceRef: r.EvidenceRef, CorrelationID: corr(c), SourceCommandID: domain.NewID("cmd")})
	h.write(c, http.StatusCreated, out, err)
}
func (h *Handler) resolveDiscrepancy(c *gin.Context) {
	var r struct {
		ResolutionStatus, ResolutionRef, OperatorRef, ReasonCode string
		ExpectedVersion                                          int64
	}
	if bind(c, &r) {
		return
	}
	out, err := h.svc.ResolveDiscrepancy(c.Request.Context(), c.Param("id"), r.ResolutionStatus, r.ResolutionRef, r.OperatorRef, r.ReasonCode, r.ExpectedVersion, corr(c), domain.NewID("cmd"))
	h.write(c, http.StatusOK, out, err)
}
func (h *Handler) getDiscrepancy(c *gin.Context) {
	out, err := h.svc.GetDiscrepancy(c.Request.Context(), c.Param("id"))
	h.write(c, http.StatusOK, out, err)
}
func (h *Handler) listDiscrepancies(c *gin.Context) {
	f := application.DiscrepancyFilter{ChannelStatementID: c.Query("channelStatementId"), Status: c.Query("status"), DifferenceType: c.Query("differenceType"), Limit: intq(c, "limit", 20), Offset: intq(c, "offset", 0)}
	items, total, err := h.svc.ListDiscrepancies(c.Request.Context(), f)
	h.write(c, http.StatusOK, gin.H{"items": items, "total": total, "limit": f.Limit, "offset": f.Offset}, err)
}
func bind(c *gin.Context, v any) bool {
	if err := c.ShouldBindJSON(v); err != nil {
		httpkit.WriteError(c, http.StatusBadRequest, httpkit.ValidationFailed, "invalid request body: "+err.Error(), nil)
		return true
	}
	return false
}
func intq(c *gin.Context, n string, d int) int {
	v := strings.TrimSpace(c.Query(n))
	if v == "" {
		return d
	}
	i, err := strconv.Atoi(v)
	if err != nil {
		return d
	}
	return i
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
		case "CONFLICT", "PRECONDITION_FAILED":
			code = http.StatusConflict
		case "UNAVAILABLE":
			code = http.StatusServiceUnavailable
		}
		httpkit.WriteError(c, code, e.Code, e.Message, nil)
		return
	}
	httpkit.WriteError(c, http.StatusInternalServerError, httpkit.Unavailable, "internal error", nil)
}
