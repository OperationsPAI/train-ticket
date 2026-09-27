package application

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestResolveJourneyCoverageCrossChecksPostSalesAndCurrentOrder(t *testing.T) {
	start := time.Date(2026, 10, 12, 8, 0, 0, 0, time.UTC)
	end := start.Add(2 * time.Hour)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.Contains(r.URL.Path, "/post-sales-cases/"):
			_, _ = w.Write([]byte(`{"caseId":"psc-change","journeyOrderId":"ord-change","caseType":"CHANGE","status":"EXECUTING","scope":{"segmentRefs":["seg-old"],"travelerRefs":["tvl-a"]},"changeOperations":{"target":{"itinerary":{"legs":[{"serviceSegmentRef":"seg-new","departureTime":"2026-10-12T08:00:00Z","arrivalTime":"2026-10-12T10:00:00Z"}]}}}}`))
		case strings.Contains(r.URL.Path, "/journey-orders/"):
			_, _ = w.Write([]byte(`{"orderId":"ord-change","accountId":"acct-change","status":"CONFIRMED","segmentRefs":["seg-new"],"segments":[{"segmentRef":"seg-new","departureTime":"2026-10-12T08:00:00Z","arrivalTime":"2026-10-12T10:00:00Z"}]}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()
	coverage, err := (PurchaseVerifier{Client: server.Client(), BaseURLTemplate: server.URL + "/%s"}).ResolveJourneyCoverage(context.Background(), "ord-change", "psc-change")
	if err != nil {
		t.Fatal(err)
	}
	if coverage.OrderID != "ord-change" || coverage.AccountID != "acct-change" || len(coverage.ReplacedSegmentRefs) != 1 || coverage.ReplacedSegmentRefs[0] != "seg-old" || len(coverage.SegmentRefs) != 1 || coverage.SegmentRefs[0] != "seg-new" || !coverage.StartsAt.Equal(start) || !coverage.EndsAt.Equal(end) {
		t.Fatalf("unexpected coverage: %#v", coverage)
	}
}

func TestResolveJourneyCoverageTreatsATerminalChangeAsNothingToAdjust(t *testing.T) {
	// A change that was rejected, failed or cancelled will never execute, so an
	// announcement about it has nothing to adjust -- exactly like a refund or a
	// cancellation. Waiting for it instead dead-lettered three announcements on
	// 2026-09-27.
	for _, status := range []string{"REJECTED", "FAILED", "CANCELLED"} {
		t.Run(status, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				_, _ = w.Write([]byte(`{"caseId":"psc-change","journeyOrderId":"ord-change","caseType":"CHANGE","status":"` + status + `"}`))
			}))
			defer server.Close()
			_, err := (PurchaseVerifier{Client: server.Client(), BaseURLTemplate: server.URL + "/%s"}).ResolveJourneyCoverage(context.Background(), "ord-change", "psc-change")
			if !errors.Is(err, ErrNotChangeAdjustment) {
				t.Fatalf("expected ErrNotChangeAdjustment for %s, got %v", status, err)
			}
		})
	}
}

func TestResolveJourneyCoverageWaitsWhileAChangeIsStillPending(t *testing.T) {
	// Quoted or approved means the case has not executed yet: that is a read that
	// has not caught up, and it is the common case rather than a rare one --
	// post-sales commits the case's applied snapshot a few milliseconds AFTER
	// journey-order announces the order's adjustment, so the announcement this
	// service is woken by always precedes the state it reads. It stays a retry,
	// but it is a wait rather than a rule violation.
	for _, status := range []string{"QUOTED", "APPROVED"} {
		t.Run(status, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				_, _ = w.Write([]byte(`{"caseId":"psc-change","journeyOrderId":"ord-change","caseType":"CHANGE","status":"` + status + `"}`))
			}))
			defer server.Close()
			_, err := (PurchaseVerifier{Client: server.Client(), BaseURLTemplate: server.URL + "/%s"}).ResolveJourneyCoverage(context.Background(), "ord-change", "psc-change")
			if !errors.Is(err, ErrNotReady) {
				t.Fatalf("expected a not-ready error for %s, got %v", status, err)
			}
		})
	}
}

func TestReadWithA404IsAWaitRatherThanARuleViolation(t *testing.T) {
	// A read that 404s after its announcement names the resource is the same
	// race as one that answers a stale state: the owning service has not exposed
	// it yet. Measured on the resident release, these were 11 of 66 handler
	// failures in ninety seconds, each recovered on its next delivery.
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer server.Close()

	var out map[string]any
	err := (PurchaseVerifier{Client: server.Client(), BaseURLTemplate: server.URL + "/%s"}).read(context.Background(), "payment", "/api/v1/refunds/rf-1", "", &out)
	if !errors.Is(err, ErrNotReady) {
		t.Fatalf("expected ErrNotReady for a 404, got %v", err)
	}
}
