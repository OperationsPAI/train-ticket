package api

import (
	"bytes"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/trainticket/greenfield/platform/go-kit/httpkit"
	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/travel-insurance/application"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

type Handler struct{ service *application.InsuranceService }

type issuePolicyRequest struct {
	ProductCode          string    `json:"productCode"`
	ProductVersion       string    `json:"productVersion"`
	JourneyOrderID       string    `json:"journeyOrderId"`
	AncillaryOrderItemID string    `json:"ancillaryOrderItemId"`
	AccountID            string    `json:"accountId"`
	TravelerRef          string    `json:"travelerRef"`
	SegmentRefs          []string  `json:"segmentRefs"`
	PaymentIntentID      string    `json:"paymentIntentId"`
	CoverageStartAt      time.Time `json:"coverageStartAt"`
	CoverageEndAt        time.Time `json:"coverageEndAt"`
}

type fileClaimRequest struct {
	PolicyID       string            `json:"policyId"`
	ClaimType      string            `json:"claimType"`
	TriggerFactKey string            `json:"triggerFactKey"`
	DelayFact      *domain.DelayFact `json:"delayFact"`
	SupportCaseID  string            `json:"supportCaseId"`
	EvidenceRefs   []string          `json:"evidenceRefs"`
	ClaimedAmount  domain.Money      `json:"claimedAmount"`
	Settle         bool              `json:"settle"`
	PayoutTarget   string            `json:"payoutTarget"`
	ReasonCode     string            `json:"reasonCode"`
}

type fileClaimResponse struct {
	Claim        domain.Claim         `json:"claim"`
	PayoutAdvice *domain.PayoutAdvice `json:"payoutAdvice,omitempty"`
}

func Router() *gin.Engine {
	return RouterWithService(application.NewInsuranceService(application.ServiceConfig{}))
}
func RouterWithService(service *application.InsuranceService) *gin.Engine {
	return RouterWithServiceAndIdempotency(service, idempotency.NewMemoryStore())
}
func RouterWithServiceAndIdempotency(service *application.InsuranceService, store idempotency.Store) *gin.Engine {
	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{ServiceID: profile.ServiceID, Metadata: profile, HealthStatus: domain.Health(), Observer: goruntime.ObserverFromEnv(profile.ServiceID)})
	RegisterRoutes(router, service, store)
	return router
}

func RegisterRoutes(router gin.IRouter, service *application.InsuranceService, store idempotency.Store) {
	if store == nil {
		store = idempotency.NewMemoryStore()
	}
	h := Handler{service: service}
	idempotent := idempotency.Middleware(store)
	api := router.Group("/api/v1")
	api.POST("/policies", idempotent, h.issuePolicy)
	api.GET("/policies/:id", h.getPolicy)
	api.POST("/claims", idempotent, h.fileClaim)
}

func (h Handler) issuePolicy(ctx *gin.Context) {
	var req issuePolicyRequest
	if err := bindBody(ctx, &req); err != nil {
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, "Request body failed structural validation", nil)
		return
	}
	if strings.TrimSpace(req.ProductVersion) == "" {
		req.ProductVersion = "v1"
	}
	policy, err := h.service.IssuePolicy(ctx.Request.Context(), application.IssuePolicyCommand{ProductCode: req.ProductCode, ProductVersion: req.ProductVersion, JourneyOrderID: req.JourneyOrderID, AncillaryOrderItemID: req.AncillaryOrderItemID, AccountID: req.AccountID, TravelerRef: req.TravelerRef, SegmentRefs: req.SegmentRefs, PaymentIntentID: req.PaymentIntentID, CoverageStartAt: req.CoverageStartAt, CoverageEndAt: req.CoverageEndAt, CorrelationID: httpkit.CorrelationID(ctx), CausationID: ctx.GetHeader("X-Causation-Id")})
	if err != nil {
		writeMappedError(ctx, err)
		return
	}
	ctx.JSON(http.StatusCreated, policy)
}

func (h Handler) getPolicy(ctx *gin.Context) {
	policy, err := h.service.GetPolicy(ctx.Request.Context(), ctx.Param("id"))
	if err != nil {
		writeMappedError(ctx, err)
		return
	}
	ctx.JSON(http.StatusOK, policy)
}

func (h Handler) fileClaim(ctx *gin.Context) {
	var req fileClaimRequest
	if err := bindBody(ctx, &req); err != nil {
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, "Request body failed structural validation", nil)
		return
	}
	claim, err := h.service.FileClaim(ctx.Request.Context(), application.FileClaimCommand{PolicyID: req.PolicyID, ClaimType: req.ClaimType, TriggerFactKey: req.TriggerFactKey, DelayFact: req.DelayFact, SupportCaseID: req.SupportCaseID, EvidenceRefs: req.EvidenceRefs, ClaimedAmount: req.ClaimedAmount, CorrelationID: httpkit.CorrelationID(ctx), CausationID: ctx.GetHeader("X-Causation-Id")})
	if err != nil {
		writeMappedError(ctx, err)
		return
	}
	resp := fileClaimResponse{Claim: claim}
	if req.Settle {
		target := strings.TrimSpace(req.PayoutTarget)
		if target == "" {
			target = string(domain.PayoutPaymentRefund)
		}
		advice, err := h.service.SettleClaim(ctx.Request.Context(), application.SettleClaimCommand{ClaimID: claim.ID, PayoutTarget: target, ReasonCode: req.ReasonCode, CorrelationID: httpkit.CorrelationID(ctx), CausationID: claim.ID})
		if err != nil {
			writeMappedError(ctx, err)
			return
		}
		resp.PayoutAdvice = &advice
		resp.Claim.PayoutAdviceID = advice.ID
		resp.Claim.Status = domain.ClaimPayoutRecommended
	}
	ctx.JSON(http.StatusCreated, resp)
}

func bindBody(ctx *gin.Context, target any) error {
	body, err := io.ReadAll(ctx.Request.Body)
	if err != nil || len(strings.TrimSpace(string(body))) == 0 {
		return errors.New("request body is required")
	}
	ctx.Request.Body = io.NopCloser(bytes.NewReader(body))
	return jsonUnmarshalStrict(body, target)
}

func writeMappedError(ctx *gin.Context, err error) {
	switch {
	case errors.Is(err, application.ErrValidation):
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, err.Error(), nil)
	case errors.Is(err, application.ErrNotFound):
		httpkit.WriteError(ctx, http.StatusNotFound, httpkit.NotFound, err.Error(), nil)
	case errors.Is(err, application.ErrConflict):
		httpkit.WriteError(ctx, http.StatusConflict, httpkit.Conflict, err.Error(), nil)
	case errors.Is(err, application.ErrDomainRule):
		httpkit.WriteError(ctx, http.StatusUnprocessableEntity, httpkit.DomainRuleViolation, err.Error(), nil)
	case errors.Is(err, application.ErrPublish):
		httpkit.WriteError(ctx, http.StatusServiceUnavailable, httpkit.Unavailable, "Service is temporarily unavailable", nil)
	default:
		httpkit.WriteError(ctx, http.StatusInternalServerError, httpkit.Unavailable, "internal error", nil)
	}
}
