package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestPolicyAndClaimHTTPFlow(t *testing.T) {
	router := Router()
	now := time.Now().UTC()
	policyBody := map[string]any{"productCode": "DELAY_INSURANCE", "productVersion": "v1", "journeyOrderId": "jo-http", "ancillaryOrderItemId": "anc-http", "accountId": "acct-http", "travelerRef": "trav-http", "segmentRefs": []string{"seg-http"}, "paymentIntentId": "pi-http", "coverageStartAt": now.Add(-time.Minute).Format(time.RFC3339), "coverageEndAt": now.Add(time.Hour).Format(time.RFC3339)}
	w := performJSON(router, http.MethodPost, "/api/v1/policies", policyBody)
	if w.Code != http.StatusCreated {
		t.Fatalf("POST /policies status %d body %s", w.Code, w.Body.String())
	}
	var policy struct {
		PolicyID string `json:"policyId"`
		Status   string `json:"status"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &policy); err != nil {
		t.Fatal(err)
	}
	if policy.PolicyID == "" || policy.Status != "ISSUED" {
		t.Fatalf("policy response: %#v", policy)
	}
	w = performJSON(router, http.MethodGet, "/api/v1/policies/"+policy.PolicyID, nil)
	if w.Code != http.StatusOK {
		t.Fatalf("GET /policies status %d body %s", w.Code, w.Body.String())
	}
	claimBody := map[string]any{"policyId": policy.PolicyID, "claimType": "DELAY_AUTO", "triggerFactKey": "fact-http", "delayFact": map[string]any{"segmentRef": "seg-http", "sourceEventType": "SegmentArrived", "sourceEventId": "evt-http", "delayMinutes": 90}, "claimedAmount": map[string]any{"currency": "CNY", "minorUnits": 1000}, "settle": true, "payoutTarget": "PAYMENT_REFUND"}
	w = performJSON(router, http.MethodPost, "/api/v1/claims", claimBody)
	if w.Code != http.StatusCreated {
		t.Fatalf("POST /claims status %d body %s", w.Code, w.Body.String())
	}
	var claimResp struct {
		Claim struct {
			Status string `json:"status"`
		} `json:"claim"`
		PayoutAdvice *struct {
			Status string `json:"status"`
		} `json:"payoutAdvice"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &claimResp); err != nil {
		t.Fatal(err)
	}
	if claimResp.Claim.Status != "PAID_OUT" || claimResp.PayoutAdvice == nil {
		t.Fatalf("claim response: %#v", claimResp)
	}
}

func TestPolicyPostRejectsUnknownField(t *testing.T) {
	router := Router()
	w := performJSON(router, http.MethodPost, "/api/v1/policies", map[string]any{"unexpected": "field"})
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d body %s", w.Code, w.Body.String())
	}
}

func performJSON(handler http.Handler, method, path string, body any) *httptest.ResponseRecorder {
	var buf bytes.Buffer
	if body != nil {
		_ = json.NewEncoder(&buf).Encode(body)
	}
	req := httptest.NewRequest(method, path, &buf)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Idempotency-Key", idempotencyKey(method, path))
	}
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	return w
}

func idempotencyKey(method, path string) string {
	switch method + " " + path {
	case "POST /api/v1/policies":
		return "019f4d68-21b9-7929-acd0-27646534d84d"
	case "POST /api/v1/claims":
		return "019f4d68-21b9-7929-acd0-27646534d84e"
	default:
		return "019f4d68-21b9-7929-acd0-27646534d84f"
	}
}
