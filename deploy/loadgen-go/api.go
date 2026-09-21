package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// ApiClient provides HTTP calls against the microservice mesh with stats recording.
type ApiClient struct {
	template string
	client   *http.Client
	stats    *Stats
	// rec is nil when per-request recording is disabled; every Recorder
	// method is nil-safe, so there is no branch on the request path.
	rec *Recorder
}

func NewApiClient(cfg *Config, stats *Stats) *ApiClient {
	return NewApiClientWithRecorder(cfg, stats, nil)
}

// NewApiClientWithRecorder builds a client that also writes one per-request
// outcome record (issue #420). Pass nil to disable recording.
func NewApiClientWithRecorder(cfg *Config, stats *Stats, rec *Recorder) *ApiClient {
	timeout := time.Duration(cfg.Target.RequestTimeoutSeconds * float64(time.Second))
	return &ApiClient{
		template: cfg.Target.BaseURLTemplate,
		client: &http.Client{
			Timeout: timeout,
			Transport: &http.Transport{
				MaxIdleConns:        1000,
				MaxIdleConnsPerHost: 200,
				IdleConnTimeout:     90 * time.Second,
			},
		},
		stats: stats,
		rec:   rec,
	}
}

// Failure kinds for StepError.Kind.
//
// These answer the one question a journey record cannot answer from a step
// name and a detail string: what did the person in front of the screen
// actually see. A timeout shows a spinner and no message; an error status
// shows an error page; a refusal shows a reason. Those are three different
// complaints about the same failed booking, so the distinction has to be
// carried structurally rather than recovered by matching substrings of
// Detail.
const (
	// FailureHTTPStatus -- the service answered, with a status the caller did
	// not accept. An error was displayed.
	FailureHTTPStatus = "http_status"
	// FailureTransport -- no response at all: the connection failed, was
	// refused or was reset. The page did not load.
	FailureTransport = "transport"
	// FailureTimeout -- nothing came back before the wait ran out. Nothing was
	// displayed; it simply never finished.
	FailureTimeout = "timeout"
	// FailureRejected -- the system answered and said no: the order ended
	// CANCELLED, the legacy facade returned a non-success status, the offer
	// was refused for every attempt.
	FailureRejected = "rejected"
	// FailureUnfulfilled -- the request was accepted and then never completed,
	// with no reason given to the person waiting.
	FailureUnfulfilled = "unfulfilled"
	// FailureUnavailable -- there was nothing to act on: no trains, no places,
	// no order to refund.
	FailureUnavailable = "unavailable"
	// FailureMalformed -- the service answered successfully but the answer was
	// unusable, e.g. a created resource came back with no id.
	FailureMalformed = "malformed"
	// FailureInternal -- the generator itself could not issue the call. Not
	// something a person could have seen.
	FailureInternal = "internal"
	// FailureShutdown -- the run was cancelled under the journey. Set by the
	// journey runner, never by a StepError.
	FailureShutdown = "shutdown"
)

// StepError is returned when an API call fails expectations.
type StepError struct {
	Step   string
	Detail string
	// Kind is one of the Failure* constants above. Every construction site
	// sets it: it is the only field that says what the failure looked like
	// from the user's side, and a journey record with an empty Kind is a
	// failure nobody can write a complaint about.
	Kind string
	// Status is the HTTP status the person was shown, 0 when they were shown
	// none. Set only where a status was actually received.
	Status int
	// TraceID is the trace of the request that failed. The journey record
	// prefers this over the last request it saw, because the failing request
	// is the one an engineer reading the complaint needs to open.
	TraceID string
}

func (e *StepError) Error() string {
	return fmt.Sprintf("%s: %s", e.Step, e.Detail)
}

// Request makes an HTTP request and returns the status code and decoded JSON body.
// Any status outside `ok` is counted as an error in the stats.
func (a *ApiClient) Request(ctx context.Context, method, service, path string,
	body interface{}, headers map[string]string, ok []int, step string) (int, map[string]interface{}, error) {
	return a.request(ctx, method, service, path, body, headers, ok, step, nil)
}

// PollRequest is Request for a status that is polled until it appears. Statuses
// listed in `expected` still return a StepError -- the caller's loop needs that
// to decide whether to retry -- but they are NOT counted as errors in the stats.
//
// WHY THIS EXISTS
// A poll for "has the saga been created yet" legitimately answers 404 several
// times before it answers 200. Request counts every one of those as an error, so
// the stats showed 1159 `booking-orchestration:404` against 583 successes on a
// perfectly healthy cluster -- while every reservation the poll was waiting for
// went on to succeed. That is worse than merely noisy: it makes the error tally
// useless for deciding whether the system is actually failing, which is the one
// question it exists to answer. An expected-status poll is a normal part of an
// asynchronous flow, not a fault.
func (a *ApiClient) PollRequest(ctx context.Context, method, service, path string,
	body interface{}, headers map[string]string, ok []int, step string, expected []int) (int, map[string]interface{}, error) {
	return a.request(ctx, method, service, path, body, headers, ok, step, expected)
}

func (a *ApiClient) request(ctx context.Context, method, service, path string,
	body interface{}, headers map[string]string, ok []int, step string, expected []int) (int, map[string]interface{}, error) {

	url := fmt.Sprintf(a.template, service) + path

	var bodyReader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return 0, nil, &StepError{Step: step, Detail: fmt.Sprintf("marshal: %v", err),
				Kind: FailureInternal}
		}
		bodyReader = bytes.NewReader(b)
	}

	req, err := http.NewRequestWithContext(ctx, method, url, bodyReader)
	if err != nil {
		return 0, nil, &StepError{Step: step, Detail: fmt.Sprintf("build request: %v", err),
			Kind: FailureInternal}
	}

	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	// Idempotency key for mutating requests
	if (method == "POST" || method == "PUT" || method == "PATCH") && req.Header.Get("Idempotency-Key") == "" {
		req.Header.Set("Idempotency-Key", UUID7())
	}

	// W3C trace context. The loadgen originates the trace, so it mints the
	// traceparent itself rather than waiting for an instrumented client to do
	// it; the trace id it puts on the wire is the one it records, which is
	// what makes a recorded row joinable to the server spans in Jaeger.
	// A caller that supplied its own traceparent header wins.
	var tc traceContext
	if existing := req.Header.Get("traceparent"); existing != "" {
		tc = parseTraceparent(existing)
	} else {
		tc = newTraceContext(a.rec.TraceSampled())
		req.Header.Set("traceparent", tc.Header)
	}

	// The journey attempt that owns this request, if any. Reported to on every
	// path below: the last request an attempt made is the step it stopped at,
	// even when that request itself succeeded.
	attempt := AttemptFromContext(ctx)

	t0 := time.Now()
	resp, err := a.client.Do(req)
	elapsed := time.Since(t0).Seconds() * 1000

	attempt.observe(step, tc.TraceID)

	if err != nil {
		a.stats.RecordError(fmt.Sprintf("%s:transport", service))
		// status 0 + non-empty error is the "never got a status" marker.
		a.rec.Record(ReqRecord{
			StartUnixNano: t0.UnixNano(),
			Chain:         ChainFromContext(ctx),
			JourneyID:     attempt.id(),
			Step:          step,
			Service:       service,
			Method:        method,
			Path:          path,
			Status:        0,
			LatencyMs:     elapsed,
			TransportErr:  classifyTransportError(err),
			TraceID:       tc.TraceID,
			SpanID:        tc.SpanID,
			Sampled:       tc.Sampled,
		})
		// A client timeout and a refused connection are different experiences
		// -- a spinner that never resolves against a page that fails to load
		// -- so the transport classifier decides the kind here too.
		kind := FailureTransport
		if classifyTransportError(err) == "timeout" {
			kind = FailureTimeout
		}
		return 0, nil, &StepError{Step: step, Detail: fmt.Sprintf("transport: %v", err),
			Kind: kind, TraceID: tc.TraceID}
	}
	defer resp.Body.Close()

	a.stats.RecordHTTP(service, resp.StatusCode, elapsed)
	a.rec.Record(ReqRecord{
		StartUnixNano: t0.UnixNano(),
		Chain:         ChainFromContext(ctx),
		JourneyID:     attempt.id(),
		Step:          step,
		Service:       service,
		Method:        method,
		Path:          path,
		Status:        resp.StatusCode,
		LatencyMs:     elapsed,
		TraceID:       tc.TraceID,
		SpanID:        tc.SpanID,
		Sampled:       tc.Sampled,
	})

	var data map[string]interface{}
	respBody, _ := io.ReadAll(resp.Body)
	if len(respBody) > 0 {
		_ = json.Unmarshal(respBody, &data)
	}
	if data == nil {
		data = make(map[string]interface{})
	}

	if len(ok) > 0 {
		found := false
		for _, c := range ok {
			if resp.StatusCode == c {
				found = true
				break
			}
		}
		if !found {
			// An expected poll status is still an error to the CALLER (its retry
			// loop needs to know the thing is not there yet) but not to the stats.
			isExpected := false
			for _, c := range expected {
				if resp.StatusCode == c {
					isExpected = true
					break
				}
			}
			if !isExpected {
				a.stats.RecordError(fmt.Sprintf("%s:%d", service, resp.StatusCode))
			}
			detail := fmt.Sprintf("%s %s%s -> %d", method, service, path, resp.StatusCode)
			if len(respBody) > 0 {
				snippet := string(respBody)
				if len(snippet) > 150 {
					snippet = snippet[:150]
				}
				detail += " " + snippet
			}
			return resp.StatusCode, data, &StepError{Step: step, Detail: detail,
				Kind: FailureHTTPStatus, Status: resp.StatusCode, TraceID: tc.TraceID}
		}
	}

	return resp.StatusCode, data, nil
}

// FormatURL builds a URL from the template. Exported for use by other packages.
func (a *ApiClient) FormatURL(service, path string) string {
	return fmt.Sprintf(a.template, service) + path
}

// serviceURL uses fmt-style %s replacement for the template.
// Config uses a {service} placeholder; Go's fmt needs %s.
func init() {
	// Ensure the template uses %s for service substitution.
	// Config loader will handle converting {service} to %s.
}
