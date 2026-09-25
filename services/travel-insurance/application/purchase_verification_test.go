package application

import (
	"context"
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
