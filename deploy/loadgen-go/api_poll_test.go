package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// The stats error tally answers exactly one question: is the system actually
// failing? It stops answering it the moment a status the caller polls for on
// purpose is counted the same as a fault. That is not hypothetical -- on a
// healthy cluster the two largest entries were `booking-orchestration:404`
// (1159, against 583 successes) and `offer-management:422` (213, against 378),
// both of them retry loops working exactly as designed, and both of them
// drowning out everything real.
//
// These tests pin the distinction so it cannot be quietly undone by someone
// swapping a PollRequest back to a Request.

func testClient(t *testing.T, handler http.HandlerFunc) (*ApiClient, *Stats, func()) {
	t.Helper()
	srv := httptest.NewServer(handler)
	cfg := &Config{}
	// The template is formatted with the service name; point every service at the
	// one test server.
	cfg.Target.BaseURLTemplate = strings.Replace(srv.URL, "%", "%%", -1) + "%s"
	cfg.Target.RequestTimeoutSeconds = 5
	stats := NewStats()
	return NewApiClient(cfg, stats), stats, srv.Close
}

func TestRequestCountsAnUnexpectedStatusAsAnError(t *testing.T) {
	api, stats, done := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(404)
	})
	defer done()

	_, _, err := api.Request(context.Background(), "GET", "", "/thing", nil, nil, []int{200}, "step")
	if err == nil {
		t.Fatal("expected a StepError for an unexpected 404")
	}
	if got := stats.Snapshot().Errors[":404"]; got != 1 {
		t.Errorf("an unexpected 404 must be tallied as an error, got %d entries: %v",
			got, stats.Snapshot().Errors)
	}
}

func TestPollRequestDoesNotTallyAnExpectedStatus(t *testing.T) {
	api, stats, done := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(404)
	})
	defer done()

	// The caller still needs the error: its retry loop decides on it.
	_, _, err := api.PollRequest(context.Background(), "GET", "", "/thing", nil, nil,
		[]int{200}, "step", []int{404})
	if err == nil {
		t.Fatal("PollRequest must still return an error so the caller's retry loop can act on it")
	}
	if errs := stats.Snapshot().Errors; len(errs) != 0 {
		t.Errorf("an expected poll status must not be tallied as an error, got %v", errs)
	}
}

func TestPollRequestStillTalliesAnUnexpectedStatus(t *testing.T) {
	api, stats, done := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(500)
	})
	defer done()

	// 404 is the polled-for status; a 500 is a real failure and must be counted
	// even on a poll. This is the assertion that keeps `expected` from becoming a
	// blanket mute.
	_, _, err := api.PollRequest(context.Background(), "GET", "", "/thing", nil, nil,
		[]int{200}, "step", []int{404})
	if err == nil {
		t.Fatal("expected a StepError for a 500")
	}
	if got := stats.Snapshot().Errors[":500"]; got != 1 {
		t.Errorf("a status outside `expected` must still be tallied, got %d entries: %v",
			got, stats.Snapshot().Errors)
	}
}

func TestPollRequestSucceedsNormally(t *testing.T) {
	api, stats, done := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(200)
		_, _ = w.Write([]byte(`{"sagaId":"saga-1"}`))
	})
	defer done()

	code, data, err := api.PollRequest(context.Background(), "GET", "", "/thing", nil, nil,
		[]int{200}, "step", []int{404})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if code != 200 {
		t.Errorf("code = %d, want 200", code)
	}
	if getString(data, "sagaId") != "saga-1" {
		t.Errorf("body did not decode: %v", data)
	}
	if errs := stats.Snapshot().Errors; len(errs) != 0 {
		t.Errorf("a successful poll must tally no errors, got %v", errs)
	}
}
