package application

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

func TestWalletPayoutExecutorIssuesAndVerifiesStoredValue(t *testing.T) {
	now := time.Date(2026, 9, 25, 5, 0, 0, 0, time.UTC)
	amount := domain.Money{Currency: "CNY", MinorUnits: 3000}
	policy := domain.Policy{ID: "pol-1", AccountID: "acct-1"}
	claim := domain.Claim{ID: "clm-1", PolicyID: policy.ID, ApprovedAmount: amount, Status: domain.ClaimApproved}
	advice := domain.PayoutAdvice{ID: "pad-1", ClaimID: claim.ID, PolicyID: policy.ID, AccountID: policy.AccountID, PayoutTarget: domain.PayoutWalletBenefit, Amount: amount, ReasonCode: "TRAIN_DELAY_GT_60", IdempotencyKey: ids.NewUUIDv7(), DecisionVersion: "v1", Status: "REQUESTED", RequestedAt: now}

	postCalls := 0
	getCalls := 0
	var request map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/benefits":
			postCalls++
			if r.Header.Get("Idempotency-Key") != advice.IdempotencyKey || r.Header.Get("X-Correlation-Id") != "corr-test" {
				t.Fatalf("unexpected payout headers: %#v", r.Header)
			}
			if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
				t.Fatal(err)
			}
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/benefits/ben-1":
			getCalls++
		default:
			t.Fatalf("unexpected request %s %s", r.Method, r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"benefitId":"ben-1","accountId":"acct-1","benefitType":"BALANCE","balanceType":"STORED_VALUE","status":"ISSUED","issuedAmount":{"currency":"CNY","minorUnits":3000},"issuanceSource":"MANUAL_OPS","businessReason":{"reasonCode":"TRAIN_DELAY_GT_60","referenceType":"INSURANCE_CLAIM","referenceId":"clm-1"}}`))
	}))
	defer server.Close()

	receipt, err := (WalletPayoutExecutor{Client: server.Client(), BaseURLTemplate: server.URL}).Disburse(context.Background(), policy, claim, advice, "corr-test")
	if err != nil {
		t.Fatal(err)
	}
	if postCalls != 1 || getCalls != 1 || receipt.ExecutionRef != "ben-1" || receipt.Amount != amount || receipt.AccountID != policy.AccountID {
		t.Fatalf("unexpected payout result calls=%d/%d receipt=%#v", postCalls, getCalls, receipt)
	}
	if request["issuanceSource"] != "MANUAL_OPS" || request["issuanceRef"] != "insurance-claim:clm-1:v1" || request["benefitType"] != "BALANCE" || request["balanceType"] != "STORED_VALUE" {
		t.Fatalf("unexpected wallet request: %#v", request)
	}
	reason := request["businessReason"].(map[string]any)
	if reason["referenceType"] != "INSURANCE_CLAIM" || reason["referenceId"] != claim.ID {
		t.Fatalf("claim provenance missing: %#v", reason)
	}
}

func TestWalletPayoutExecutorRejectsMismatchedVerification(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"benefitId":"ben-1","accountId":"acct-other","benefitType":"BALANCE","balanceType":"STORED_VALUE","status":"ISSUED","issuedAmount":{"currency":"CNY","minorUnits":3000},"issuanceSource":"MANUAL_OPS","businessReason":{"reasonCode":"CLAIM","referenceType":"INSURANCE_CLAIM","referenceId":"clm-1"}}`))
	}))
	defer server.Close()
	amount := domain.Money{Currency: "CNY", MinorUnits: 3000}
	advice := domain.PayoutAdvice{ID: "pad-1", ClaimID: "clm-1", PolicyID: "pol-1", AccountID: "acct-1", PayoutTarget: domain.PayoutWalletBenefit, Amount: amount, ReasonCode: "CLAIM", IdempotencyKey: ids.NewUUIDv7(), DecisionVersion: "v1", Status: "REQUESTED", RequestedAt: time.Now().UTC()}
	_, err := (WalletPayoutExecutor{Client: server.Client(), BaseURLTemplate: server.URL}).Disburse(context.Background(), domain.Policy{ID: "pol-1", AccountID: "acct-1"}, domain.Claim{ID: "clm-1"}, advice, "")
	if err == nil {
		t.Fatal("expected mismatched wallet evidence to be rejected")
	}
}
