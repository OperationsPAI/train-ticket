package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/trainticket/greenfield/platform/go-kit/ids"
	"github.com/trainticket/greenfield/services/travel-insurance/application"
)

func TestHealthAndPolicyEndpoints(t *testing.T) {
	gin.SetMode(gin.TestMode)
	repo := application.NewInMemoryRepository()
	svc := application.NewInsuranceService(repo, application.NoopPublisher{}, nil, func() time.Time { return time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC) })
	if err := svc.EnsureDefaultCatalog(context.Background()); err != nil {
		t.Fatal(err)
	}
	router := RouterWithService(svc)

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/health", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("health status=%d", rec.Code)
	}

	body, _ := json.Marshal(map[string]any{"productCode": "DELAY_INSURANCE", "journeyOrderId": "ord-1", "accountId": "acct-1", "travelerRef": "tvl-1", "segmentRefs": []string{"seg-1"}, "coverageStartAt": "2026-07-10T09:59:00Z", "coverageEndAt": "2026-07-10T11:00:00Z"})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/policies", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Idempotency-Key", ids.NewUUIDv7())
	rec = httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	if rec.Code != http.StatusCreated {
		t.Fatalf("issue status=%d body=%s", rec.Code, rec.Body.String())
	}
	var policy struct {
		PolicyID string `json:"policyId"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &policy); err != nil || policy.PolicyID == "" {
		t.Fatalf("policy response: %v %#v", err, policy)
	}

	rec = httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/policies/"+policy.PolicyID, nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("get status=%d body=%s", rec.Code, rec.Body.String())
	}

	rec = httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/policies/"+policy.PolicyID, nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("unversioned get status=%d body=%s", rec.Code, rec.Body.String())
	}
}
