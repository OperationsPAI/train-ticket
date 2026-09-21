package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// templateFor turns a test server's URL into a base URL template of the shape
// the config carries, so the gate is exercised through the same substitution
// the deployment uses.
//
// The service name lands in the path rather than in the host, which is where
// the deployed template `http://{service}:8080` puts it. One test server
// cannot answer on several hostnames, and substituting into the host of a
// server URL would append the name to its port.
func templateFor(address string) string {
	return address + "/%s"
}

func TestAwaitServicesReturnsWhenEveryServiceAnswers(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, readinessPath) {
			t.Errorf("gate asked for %s, want a path ending in %s", r.URL.Path, readinessPath)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	waiting := AwaitServices(context.Background(), templateFor(server.URL),
		[]string{"a", "b", "c"}, time.Second, 10*time.Millisecond)
	if len(waiting) != 0 {
		t.Fatalf("gate reported %v still waiting, want none", waiting)
	}
}

func TestAwaitServicesWaitsForOneThatIsNotReadyYet(t *testing.T) {
	// The measured case. place-network refused connections for the first
	// seconds of the run, and Bootstrap's single attempt landed inside that
	// window.
	var asked int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if atomic.AddInt32(&asked, 1) < 3 {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	waiting := AwaitServices(context.Background(), templateFor(server.URL),
		[]string{"place-network"}, time.Second, 5*time.Millisecond)
	if len(waiting) != 0 {
		t.Fatalf("gate gave up on %v, want it to have waited", waiting)
	}
	if atomic.LoadInt32(&asked) < 3 {
		t.Fatalf("gate asked %d times, want at least 3", asked)
	}
}

func TestAwaitServicesNamesWhatItGaveUpOn(t *testing.T) {
	// A service that never becomes ready must be named, because the run
	// continues and its consequence is tens of thousands of journey failures
	// that each report a symptom.
	ready := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer ready.Close()

	// The unready side is a port nothing listens on, so the gate sees a
	// refused connection, which is the failure the measured run actually saw.
	waiting := AwaitServices(context.Background(), "http://127.0.0.1:1/%s",
		[]string{"beta", "alpha"}, 40*time.Millisecond, 5*time.Millisecond)
	if len(waiting) != 2 {
		t.Fatalf("gate reported %v, want both services named", waiting)
	}
	if waiting[0] != "alpha" || waiting[1] != "beta" {
		t.Fatalf("gate named %v, want them sorted", waiting)
	}
}

func TestAwaitServicesStopsWhenTheRunIsCancelled(t *testing.T) {
	// Shutdown during the gate must not hold the process for the whole
	// timeout.
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	started := time.Now()
	waiting := AwaitServices(ctx, "http://127.0.0.1:1%s",
		[]string{"7"}, time.Minute, 10*time.Millisecond)
	if len(waiting) != 1 {
		t.Fatalf("gate reported %v, want the service still waiting", waiting)
	}
	if elapsed := time.Since(started); elapsed > 5*time.Second {
		t.Fatalf("gate took %s to notice cancellation", elapsed)
	}
}

func TestBootstrapServicesAreTheOnesBootstrapUses(t *testing.T) {
	// The gate's list and bootstrap.go's call sites must not drift. A service
	// bootstrap writes to and the gate omits is the defect this whole file
	// exists for, in a new place.
	for _, service := range bootstrapServices {
		if service == "" {
			t.Fatal("bootstrapServices carries an empty name")
		}
	}
	// place-network is first because Bootstrap's first request lists places,
	// and that is the request that failed on the measured run.
	if bootstrapServices[0] != "place-network" {
		t.Fatalf("first gated service is %s, want place-network", bootstrapServices[0])
	}
}
