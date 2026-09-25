package entitlement

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/fulfillment/internal/application"
)

func TestClientGetsAuthoritativeEntitlement(t *testing.T) {
	var gotPath, gotCorrelation string
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		gotPath = request.URL.EscapedPath()
		gotCorrelation = request.Header.Get(goruntime.CorrelationIDHeader)
		response.Header().Set("Content-Type", "application/json")
		_, _ = response.Write([]byte(`{"entitlementId":"ent-client1","segmentBookingId":"sb-client1","journeyOrderId":"ord-client1","travelerRef":"tvl-client1","segmentRef":"seg-client1","status":"ISSUED"}`))
	}))
	defer server.Close()
	client := NewClient(server.URL, server.Client())
	ctx := goruntime.ContextWithRequestIDs(context.Background(), "req-client1", "corr-client1")

	ticket, err := client.GetEntitlement(ctx, "ent-client1")
	if err != nil {
		t.Fatalf("get entitlement: %v", err)
	}
	if gotPath != "/api/v1/entitlements/ent-client1" || gotCorrelation != "corr-client1" {
		t.Fatalf("request path=%q correlation=%q", gotPath, gotCorrelation)
	}
	if ticket.EntitlementID != "ent-client1" || ticket.SegmentBookingID != "sb-client1" ||
		ticket.JourneyOrderID != "ord-client1" || ticket.TravelerID != "tvl-client1" ||
		ticket.SegmentRef != "seg-client1" || ticket.Status != "ISSUED" {
		t.Fatalf("ticket=%+v", ticket)
	}
}

func TestClientMapsNotFound(t *testing.T) {
	server := httptest.NewServer(http.NotFoundHandler())
	defer server.Close()
	_, err := NewClient(server.URL, server.Client()).GetEntitlement(context.Background(), "ent-missing1")
	if !errors.Is(err, application.ErrNotFound) {
		t.Fatalf("error=%v, want not found", err)
	}
}

func TestClientMapsUpstreamAndMalformedResponsesToUnavailable(t *testing.T) {
	for name, handler := range map[string]http.Handler{
		"upstream error": http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
			response.WriteHeader(http.StatusServiceUnavailable)
		}),
		"malformed body": http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
			_, _ = response.Write([]byte(`{"entitlementId":`))
		}),
	} {
		t.Run(name, func(t *testing.T) {
			server := httptest.NewServer(handler)
			defer server.Close()
			_, err := NewClient(server.URL, server.Client()).GetEntitlement(context.Background(), "ent-error1")
			if !errors.Is(err, application.ErrDependencyUnavailable) {
				t.Fatalf("error=%v, want dependency unavailable", err)
			}
		})
	}
}
