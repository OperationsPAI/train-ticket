package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
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

type Handler struct{ svc *application.InsuranceService }

func Router() *gin.Engine {
	repo := application.NewInMemoryRepository()
	svc := application.NewInsuranceService(repo, application.NoopPublisher{}, nil, nil)
	_ = svc.EnsureDefaultCatalog(context.Background())
	return RouterWithService(svc)
}

func RouterWithService(svc *application.InsuranceService) *gin.Engine {
	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{ServiceID: profile.ServiceID, Metadata: profile, HealthStatus: domain.Health(), Observer: goruntime.ObserverFromEnv(profile.ServiceID)})
	RegisterRoutes(router, svc, idempotency.NewMemoryStore())
	return router
}

func RegisterRoutes(router gin.IRouter, svc *application.InsuranceService, store idempotency.Store) {
	if store == nil {
		store = idempotency.NewMemoryStore()
	}
	h := &Handler{svc: svc}
	idempotent := idempotency.Middleware(store)
	router.POST("/api/v1/policies", idempotent, h.issuePolicy)
	router.GET("/api/v1/policies/:id", h.getPolicy)
	router.POST("/api/v1/claims", idempotent, h.fileClaim)
	router.POST("/policies", idempotent, h.issuePolicy)
	router.GET("/policies/:id", h.getPolicy)
	router.POST("/claims", idempotent, h.fileClaim)
}

type issuePolicyRequest struct {
	ProductCode          string   `json:"productCode"`
	ProductVersion       int      `json:"productVersion"`
	JourneyOrderID       string   `json:"journeyOrderId"`
	AncillaryOrderItemID string   `json:"ancillaryOrderItemId"`
	AccountID            string   `json:"accountId"`
	TravelerRef          string   `json:"travelerRef"`
	SegmentRefs          []string `json:"segmentRefs"`
	PaymentIntentID      string   `json:"paymentIntentId"`
	CoverageStartAt      string   `json:"coverageStartAt"`
	CoverageEndAt        string   `json:"coverageEndAt"`
}

func (h *Handler) issuePolicy(ctx *gin.Context) {
	var req issuePolicyRequest
	if err := decodeJSON(ctx, &req); err != nil {
		httpkit.WriteValidation(ctx, err.Error())
		return
	}
	start, err := parseRequiredTime(req.CoverageStartAt, "coverageStartAt")
	if err != nil {
		httpkit.WriteValidation(ctx, err.Error())
		return
	}
	end, err := parseRequiredTime(req.CoverageEndAt, "coverageEndAt")
	if err != nil {
		httpkit.WriteValidation(ctx, err.Error())
		return
	}
	policy, err := h.svc.Issue(ctx.Request.Context(), application.IssuePolicyCommand{ProductCode: domain.ProductCode(req.ProductCode), ProductVersion: req.ProductVersion, JourneyOrderID: req.JourneyOrderID, AncillaryOrderItemID: req.AncillaryOrderItemID, AccountID: req.AccountID, TravelerRef: req.TravelerRef, SegmentRefs: req.SegmentRefs, PaymentIntentID: req.PaymentIntentID, CoverageStartAt: start, CoverageEndAt: end})
	if err != nil {
		writeMappedError(ctx, err)
		return
	}
	ctx.JSON(http.StatusCreated, policy)
}

func (h *Handler) getPolicy(ctx *gin.Context) {
	policy, ok, err := h.svc.GetPolicy(ctx.Request.Context(), ctx.Param("id"))
	if err != nil {
		writeMappedError(ctx, err)
		return
	}
	if !ok {
		httpkit.WriteError(ctx, http.StatusNotFound, httpkit.NotFound, "policy not found", nil)
		return
	}
	ctx.JSON(http.StatusOK, policy)
}

type fileClaimRequest struct {
	PolicyID       string       `json:"policyId"`
	ClaimType      string       `json:"claimType"`
	TriggerFactKey string       `json:"triggerFactKey"`
	SupportCaseID  string       `json:"supportCaseId"`
	EvidenceRefs   []string     `json:"evidenceRefs"`
	ClaimedAmount  domain.Money `json:"claimedAmount"`
}

func (h *Handler) fileClaim(ctx *gin.Context) {
	var req fileClaimRequest
	if err := decodeJSON(ctx, &req); err != nil {
		httpkit.WriteValidation(ctx, err.Error())
		return
	}
	claim, err := h.svc.FileClaim(ctx.Request.Context(), application.ClaimCommand{PolicyID: req.PolicyID, ClaimType: domain.ClaimType(req.ClaimType), TriggerFactKey: req.TriggerFactKey, SupportCaseID: req.SupportCaseID, EvidenceRefs: req.EvidenceRefs, ClaimedAmount: req.ClaimedAmount})
	if err != nil {
		writeMappedError(ctx, err)
		return
	}
	ctx.JSON(http.StatusCreated, claim)
}

func writeMappedError(ctx *gin.Context, err error) {
	switch {
	case errors.Is(err, domain.ErrNotFound):
		httpkit.WriteError(ctx, http.StatusNotFound, httpkit.NotFound, "resource not found", nil)
	case errors.Is(err, domain.ErrInvalidArgument):
		httpkit.WriteError(ctx, http.StatusBadRequest, httpkit.ValidationFailed, err.Error(), nil)
	case errors.Is(err, domain.ErrConflict), errors.Is(err, domain.ErrDuplicateActiveClaim):
		httpkit.WriteError(ctx, http.StatusConflict, httpkit.Conflict, err.Error(), nil)
	case errors.Is(err, domain.ErrInvalidTransition), errors.Is(err, domain.ErrRuleViolation):
		httpkit.WriteError(ctx, http.StatusUnprocessableEntity, httpkit.DomainRuleViolation, err.Error(), nil)
	default:
		httpkit.WriteError(ctx, http.StatusInternalServerError, "INTERNAL", "internal error", nil)
	}
}

func decodeJSON(ctx *gin.Context, target any) error {
	body, err := io.ReadAll(io.LimitReader(ctx.Request.Body, 1<<20))
	if err != nil {
		return err
	}
	if len(strings.TrimSpace(string(body))) == 0 {
		return fmt.Errorf("request body is required")
	}
	if err := json.Unmarshal(body, target); err != nil {
		return err
	}
	return nil
}

func parseRequiredTime(value, field string) (time.Time, error) {
	if strings.TrimSpace(value) == "" {
		return time.Time{}, fmt.Errorf("%s is required", field)
	}
	parsed, err := time.Parse(time.RFC3339, value)
	if err != nil {
		return time.Time{}, fmt.Errorf("%s must be RFC3339 UTC", field)
	}
	return parsed.UTC(), nil
}
