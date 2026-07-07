package http

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/trainticket/greenfield/platform/go-kit/httpkit"
	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"

	"github.com/trainticket/greenfield/services/supplier-catalog/internal/application"
	"github.com/trainticket/greenfield/services/supplier-catalog/internal/domain"
)

type Handler struct {
	service     *application.Service
	idempotency idempotency.Store
}

type ErrorBody = httpkit.ErrorBody

func Router() *gin.Engine {
	return RouterWithService(application.NewService(nil))
}

func RouterWithService(service *application.Service) *gin.Engine {
	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{
		ServiceID:    profile.ServiceID,
		Metadata:     profile,
		HealthStatus: domain.Health(),
		Observer:     goruntime.ObserverFromEnv(profile.ServiceID),
	})
	RegisterRoutes(router, service, idempotency.NewMemoryStore())
	return router
}

func RegisterRoutes(router gin.IRouter, service *application.Service, store idempotency.Store) {
	if store == nil {
		store = idempotency.NewMemoryStore()
	}
	h := Handler{service: service, idempotency: store}
	idempotent := idempotency.Middleware(h.idempotency)
	router.POST("/api/v1/suppliers", idempotent, h.postSupplier)
	router.GET("/api/v1/suppliers/:supplierId", h.getSupplier)
	router.GET("/api/v1/suppliers", h.listSuppliers)
	router.POST("/api/v1/carriers", idempotent, h.postCarrier)
	router.POST("/api/v1/contracts", idempotent, h.postContract)
}

type registerSupplierRequest struct {
	LegalName    string `json:"legalName"`
	BrandName    string `json:"brandName"`
	SupplierCode string `json:"supplierCode"`
}

type registerCarrierRequest struct {
	SupplierID    string `json:"supplierId"`
	Name          string `json:"name"`
	Code          string `json:"code"`
	TransportMode string `json:"transportMode"`
}

type activateContractRequest struct {
	SupplierID     string  `json:"supplierId"`
	CarrierID      string  `json:"carrierId"`
	ContractRef    string  `json:"contractRef"`
	EffectiveFrom  string  `json:"effectiveFrom"`
	EffectiveUntil *string `json:"effectiveUntil"`
}

func (h Handler) postSupplier(c *gin.Context) {
	h.withIdempotency(c, func(body []byte) (int, interface{}, error) {
		var req registerSupplierRequest
		if err := decode(body, &req); err != nil {
			return http.StatusBadRequest, nil, err
		}
		resp, err := h.service.RegisterSupplier(c.Request.Context(), application.RegisterSupplierCommand{
			LegalName: req.LegalName, BrandName: req.BrandName, SupplierCode: req.SupplierCode,
			CorrelationID: correlationID(c), CausationID: "",
		})
		return http.StatusCreated, resp, err
	})
}

func (h Handler) getSupplier(c *gin.Context) {
	resp, err := h.service.GetSupplier(c.Request.Context(), c.Param("supplierId"))
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h Handler) listSuppliers(c *gin.Context) {
	limit := queryInt(c, "limit", 20)
	offset := queryInt(c, "offset", 0)
	resp, err := h.service.ListSuppliers(c.Request.Context(), c.Query("status"), limit, offset)
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h Handler) postCarrier(c *gin.Context) {
	h.withIdempotency(c, func(body []byte) (int, interface{}, error) {
		var req registerCarrierRequest
		if err := decode(body, &req); err != nil {
			return http.StatusBadRequest, nil, err
		}
		if !validTransportMode(req.TransportMode) {
			return http.StatusBadRequest, nil, validationError("transportMode must be one of RAIL, AIR, COACH, FERRY, RIDE_HAILING")
		}
		resp, err := h.service.RegisterCarrier(c.Request.Context(), application.RegisterCarrierCommand{
			SupplierID: req.SupplierID, Name: req.Name, Code: req.Code, TransportMode: req.TransportMode,
			CorrelationID: correlationID(c), CausationID: "",
		})
		return http.StatusCreated, resp, err
	})
}

func (h Handler) postContract(c *gin.Context) {
	h.withIdempotency(c, func(body []byte) (int, interface{}, error) {
		var req activateContractRequest
		if err := decode(body, &req); err != nil {
			return http.StatusBadRequest, nil, err
		}
		effectiveFrom, err := parseRequiredTime(req.EffectiveFrom)
		if err != nil {
			return http.StatusBadRequest, nil, err
		}
		var effectiveUntil *time.Time
		if req.EffectiveUntil != nil && strings.TrimSpace(*req.EffectiveUntil) != "" {
			parsed, err := time.Parse(time.RFC3339, *req.EffectiveUntil)
			if err != nil {
				return http.StatusBadRequest, nil, err
			}
			utc := parsed.UTC()
			effectiveUntil = &utc
		}
		resp, err := h.service.ActivateContract(c.Request.Context(), application.ActivateContractCommand{
			SupplierID: req.SupplierID, CarrierID: req.CarrierID, ContractRef: req.ContractRef,
			EffectiveFrom: effectiveFrom, EffectiveUntil: effectiveUntil,
			CorrelationID: correlationID(c), CausationID: "",
		})
		return http.StatusCreated, resp, err
	})
}

func (h Handler) withIdempotency(c *gin.Context, run func([]byte) (int, interface{}, error)) {
	body, err := readRequestBody(c)
	if err != nil {
		h.writeError(c, err)
		return
	}
	status, response, err := run(body)
	if err != nil {
		if status == http.StatusBadRequest {
			h.writeError(c, validationError(err.Error()))
			return
		}
		h.writeError(c, err)
		return
	}
	metadata, _ := idempotency.FromContext(c)
	encoded, err := idempotency.StoreJSON(c.Request.Context(), h.idempotency, metadata.Key, metadata.Fingerprint, status, response)
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.Data(status, "application/json", encoded)
}

func readRequestBody(c *gin.Context) ([]byte, error) {
	body, err := c.GetRawData()
	if err != nil {
		return nil, validationError("request body is required")
	}
	c.Request.Body = io.NopCloser(bytes.NewReader(body))
	return body, nil
}

func decode(body []byte, out interface{}) error {
	if len(bytes.TrimSpace(body)) == 0 {
		return validationError("request body is required")
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(out); err != nil {
		return validationError("invalid JSON request body")
	}
	return nil
}

func parseRequiredTime(value string) (time.Time, error) {
	if strings.TrimSpace(value) == "" {
		return time.Time{}, validationError("effectiveFrom is required")
	}
	parsed, err := time.Parse(time.RFC3339, value)
	if err != nil {
		return time.Time{}, validationError("effectiveFrom must be RFC3339 UTC")
	}
	return parsed.UTC(), nil
}

type validationError string

func (e validationError) Error() string { return string(e) }

func (h Handler) writeError(c *gin.Context, err error) {
	status, code := errorStatus(err)
	httpkit.WriteError(c, status, code, err.Error(), nil)
}

func correlationID(c *gin.Context) string {
	if value := strings.TrimSpace(c.Writer.Header().Get(goruntime.CorrelationIDHeader)); value != "" {
		return value
	}
	if value := strings.TrimSpace(goruntime.CorrelationID(c.Request.Context())); value != "" {
		return value
	}
	return strings.TrimSpace(c.GetHeader(goruntime.CorrelationIDHeader))
}

func validTransportMode(value string) bool {
	switch strings.TrimSpace(value) {
	case "RAIL", "AIR", "COACH", "FERRY", "RIDE_HAILING":
		return true
	default:
		return false
	}
}

func errorStatus(err error) (int, string) {
	switch {
	case errors.Is(err, idempotency.ErrKeyReused):
		return http.StatusUnprocessableEntity, "IDEMPOTENCY_KEY_REUSED"
	case errors.Is(err, application.ErrNotFound):
		return http.StatusNotFound, "NOT_FOUND"
	case errors.Is(err, application.ErrConflict):
		return http.StatusConflict, "CONFLICT"
	case errors.Is(err, application.ErrDomainViolation):
		return http.StatusUnprocessableEntity, "DOMAIN_RULE_VIOLATION"
	case errors.Is(err, application.ErrValidation):
		return http.StatusBadRequest, "VALIDATION_FAILED"
	}
	var v validationError
	if errors.As(err, &v) {
		return http.StatusBadRequest, "VALIDATION_FAILED"
	}
	return http.StatusServiceUnavailable, "UNAVAILABLE"
}

func queryInt(c *gin.Context, key string, fallback int) int {
	if raw := c.Query(key); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil {
			return parsed
		}
	}
	return fallback
}
