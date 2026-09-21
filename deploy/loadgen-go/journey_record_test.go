package main

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

// readJourneyRecords parses the journey rows out of the JSON Lines record
// file. It selects on the `type` field rather than on which keys are present,
// which is the discipline the field exists to allow.
func readJourneyRecords(t *testing.T, path string) []journeyLine {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read record file: %v", err)
	}
	var out []journeyLine
	for _, line := range strings.Split(strings.TrimSpace(string(data)), "\n") {
		if line == "" {
			continue
		}
		var probe struct {
			Type string `json:"type"`
		}
		if err := json.Unmarshal([]byte(line), &probe); err != nil {
			t.Fatalf("record line is not valid JSON (%q): %v", line, err)
		}
		if probe.Type != RecordTypeJourney {
			continue
		}
		var jl journeyLine
		if err := json.Unmarshal([]byte(line), &jl); err != nil {
			t.Fatalf("journey line is not valid JSON (%q): %v", line, err)
		}
		out = append(out, jl)
	}
	return out
}

// ---------------------------------------------------------------------------
// schema
// ---------------------------------------------------------------------------

// TestJourneyRecordSchemaHasEveryRequiredField pins the journey schema against
// what a user-facing complaint needs to state. A dropped or renamed field
// silently removes a sentence the downstream generator can no longer write,
// and neither the aggregate snapshot nor the per-request rows can substitute
// for any of them.
func TestJourneyRecordSchemaHasEveryRequiredField(t *testing.T) {
	mesh := newFakeMesh(t, nil)
	cfg, path := recordingConfig(t, mesh.srv.URL)
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	api := NewApiClientWithRecorder(cfg, NewStats(), rec)

	attempt := NewAttempt("purchase", "purchase", "business")
	ctx := WithAttempt(context.Background(), attempt)
	if _, _, err := api.Request(ctx, "POST", "payment", "/api/v1/payment-intents",
		nil, nil, []int{200}, "payment-capture"); err != nil {
		t.Fatalf("request: %v", err)
	}
	// The failure this whole record type exists for: an in-process wait ran
	// out while every HTTP request succeeded.
	attempt.Record(rec, "", &StepError{
		Step: "ticketing", Detail: "timed out waiting for entitlement",
		Kind: FailureTimeout,
	})
	rec.Close()

	recs := readJourneyRecords(t, path)
	if len(recs) != 1 {
		t.Fatalf("got %d journey records, want 1", len(recs))
	}
	r := recs[0]

	// Distinguishable by a field, not by guessing which keys are present.
	if r.Type != RecordTypeJourney {
		t.Errorf("type = %q, want %q", r.Type, RecordTypeJourney)
	}
	// "I waited, and it never finished" needs a when.
	ts, err := time.Parse(time.RFC3339Nano, r.TS)
	if err != nil {
		t.Errorf("ts %q is not RFC3339Nano: %v", r.TS, err)
	} else if time.Since(ts) > time.Minute || time.Since(ts) < -time.Minute {
		t.Errorf("ts %q is not near now", r.TS)
	}
	// What they were trying to do, and for which persona.
	if r.Journey != "purchase" {
		t.Errorf("journey = %q, want purchase", r.Journey)
	}
	if r.Chain != "purchase" {
		t.Errorf("chain = %q, want purchase", r.Chain)
	}
	if r.Persona != "business" {
		t.Errorf("persona = %q, want business", r.Persona)
	}
	// What happened from their side.
	if r.Status != StatusFailed {
		t.Errorf("status = %q, want %q", r.Status, StatusFailed)
	}
	if r.Outcome != "failed_at_ticketing" {
		t.Errorf("outcome = %q, want failed_at_ticketing", r.Outcome)
	}
	// Where it stopped, and how far through that was.
	if r.Step != "ticketing" {
		t.Errorf("step = %q, want ticketing", r.Step)
	}
	if r.Steps != 1 {
		t.Errorf("steps = %d, want 1 (one request step completed)", r.Steps)
	}
	// How long they waited.
	if r.DurationMs <= 0 {
		t.Errorf("duration_ms = %v, want > 0", r.DurationMs)
	}
	// What they saw: a timeout shows nothing, which is a different complaint
	// from a refusal.
	if r.Failure != FailureTimeout {
		t.Errorf("failure = %q, want %q", r.Failure, FailureTimeout)
	}
	if r.ErrorShown {
		t.Error("error_shown = true for a timeout: nothing was displayed")
	}
	if r.HTTPStatus != 0 {
		t.Errorf("http_status = %d, want 0: no status was shown", r.HTTPStatus)
	}
	if r.Detail != "timed out waiting for entitlement" {
		t.Errorf("detail = %q, want the timeout message", r.Detail)
	}
	// Enough to join back to the technical record.
	if r.JourneyID != attempt.ID {
		t.Errorf("journey_id = %q, want %q", r.JourneyID, attempt.ID)
	}
	if len(r.TraceID) != 32 {
		t.Errorf("trace_id = %q, want the 32-hex trace of the step it stopped at", r.TraceID)
	}
}

// TestBothRecordTypesShareTheStreamAndAreDistinguishable: the journey record
// is added ALONGSIDE the per-request rows, and a reader separates them by
// reading `type` rather than by inspecting which keys exist.
func TestBothRecordTypesShareTheStreamAndAreDistinguishable(t *testing.T) {
	mesh := newFakeMesh(t, nil)
	cfg, path := recordingConfig(t, mesh.srv.URL)
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	api := NewApiClientWithRecorder(cfg, NewStats(), rec)

	attempt := NewAttempt("browse", "browse", "casual")
	ctx := WithAttempt(context.Background(), attempt)
	for i := 0; i < 3; i++ {
		if _, _, err := api.Request(ctx, "GET", "trip-planning", "/api/v1/itineraries",
			nil, nil, []int{200}, "search"); err != nil {
			t.Fatalf("request: %v", err)
		}
	}
	attempt.Record(rec, "browsed", nil)
	rec.Close()

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read record file: %v", err)
	}
	counts := map[string]int{}
	for _, line := range strings.Split(strings.TrimSpace(string(data)), "\n") {
		if line == "" {
			continue
		}
		var probe struct {
			Type string `json:"type"`
		}
		if err := json.Unmarshal([]byte(line), &probe); err != nil {
			t.Fatalf("line is not valid JSON (%q): %v", line, err)
		}
		if probe.Type == "" {
			t.Errorf("line has no type field: %s", line)
		}
		counts[probe.Type]++
	}
	if counts[RecordTypeRequest] != 3 {
		t.Errorf("%d request records, want 3: the per-request rows must be unaffected",
			counts[RecordTypeRequest])
	}
	if counts[RecordTypeJourney] != 1 {
		t.Errorf("%d journey records, want 1", counts[RecordTypeJourney])
	}

	// The journey row and its request rows must be joinable exactly.
	for _, r := range readRecords(t, path) {
		if r.JourneyID != attempt.ID {
			t.Errorf("request row journey_id = %q, want %q", r.JourneyID, attempt.ID)
		}
	}
}

// TestJourneySchemaIsAStableRectangle: every field is present on every row
// whatever the outcome, so the file loads without per-row key checks. A
// successful journey must still carry `failure` and `detail` as empty, because
// an absent key and an empty one are different things to a loader.
func TestJourneySchemaIsAStableRectangle(t *testing.T) {
	mesh := newFakeMesh(t, nil)
	cfg, path := recordingConfig(t, mesh.srv.URL)
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}

	// One of each outcome shape: completed, failed, abandoned, skipped.
	NewAttempt("purchase", "purchase", "casual").Record(rec, "purchased", nil)
	NewAttempt("purchase", "purchase", "casual").Record(rec, "",
		&StepError{Step: "payment", Detail: "declined", Kind: FailureHTTPStatus, Status: 402})
	NewAttempt("purchase", "purchase", "casual").Record(rec, "abandoned_before_payment", nil)
	NewAttempt("refund", "refund", "casual").Record(rec, "no_purchase_to_refund", nil)
	rec.Close()

	want := []string{
		"type", "ts", "chain", "journey", "persona", "status", "outcome", "step",
		"steps", "duration_ms", "failure", "error_shown", "http_status", "detail",
		"journey_id", "trace_id",
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read record file: %v", err)
	}
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) != 4 {
		t.Fatalf("got %d lines, want 4", len(lines))
	}
	for _, line := range lines {
		var raw map[string]json.RawMessage
		if err := json.Unmarshal([]byte(line), &raw); err != nil {
			t.Fatalf("line is not valid JSON (%q): %v", line, err)
		}
		for _, k := range want {
			if _, ok := raw[k]; !ok {
				t.Errorf("field %q missing from %s", k, line)
			}
		}
		if len(raw) != len(want) {
			t.Errorf("line has %d fields, want exactly %d: %s", len(raw), len(want), line)
		}
	}

	// Field ORDER is the on-disk order, so a positional reader stays valid.
	first := lines[0]
	pos := 0
	for _, k := range want {
		at := strings.Index(first, `"`+k+`":`)
		if at < 0 {
			t.Fatalf("field %q not in %s", k, first)
		}
		if at < pos {
			t.Errorf("field %q is out of schema order in %s", k, first)
		}
		pos = at
	}
}

// ---------------------------------------------------------------------------
// the failure the record type exists for
// ---------------------------------------------------------------------------

// TestJourneyFailureIsRecordedWhenNoRequestFailed is the regression this whole
// change closes. waitForResult blocks on an in-process channel, so a purchase
// can time out with every one of its HTTP requests returning 200. Measured on
// a fault-injected deployment: 8858 purchase journeys failed on "timed out
// waiting for sb" while the per-request records showed a 0% error rate.
func TestJourneyFailureIsRecordedWhenNoRequestFailed(t *testing.T) {
	mesh := newFakeMesh(t, nil)
	cfg, path := recordingConfig(t, mesh.srv.URL)
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	api := NewApiClientWithRecorder(cfg, NewStats(), rec)

	attempt := NewAttempt("purchase", "purchase", "casual")
	ctx := WithAttempt(context.Background(), attempt)
	// The 200-answering poll loop that made the failure invisible.
	for i := 0; i < 5; i++ {
		if _, _, err := api.Request(ctx, "GET", "journey-order", "/api/v1/journey-orders/ord-1",
			nil, nil, []int{200}, "poll-order"); err != nil {
			t.Fatalf("poll: %v", err)
		}
	}
	// waitForResult's timeout, reproduced through the real function against a
	// work item nobody ever completes.
	item := NewWorkItem("reservation")
	waitErr := waitForResult(context.Background(), item, "sb", 0.01)
	if waitErr == nil {
		t.Fatal("waitForResult returned nil for a work item nobody completed")
	}
	attempt.Record(rec, "", waitErr)
	rec.Close()

	// Every request succeeded.
	for _, r := range readRecords(t, path) {
		if r.Status != 200 || r.Error != "" {
			t.Fatalf("request row is not a success: status=%d error=%q", r.Status, r.Error)
		}
	}
	// And the journey row says it failed anyway.
	jrecs := readJourneyRecords(t, path)
	if len(jrecs) != 1 {
		t.Fatalf("got %d journey records, want 1", len(jrecs))
	}
	j := jrecs[0]
	if j.Status != StatusFailed {
		t.Errorf("status = %q, want %q: the journey failed with no failed request",
			j.Status, StatusFailed)
	}
	if j.Failure != FailureTimeout {
		t.Errorf("failure = %q, want %q", j.Failure, FailureTimeout)
	}
	if j.Step != "reservation" {
		t.Errorf("step = %q, want reservation", j.Step)
	}
	if !strings.Contains(j.Detail, "timed out waiting for sb") {
		t.Errorf("detail = %q, want the timeout message the printf used to carry", j.Detail)
	}
	if j.Steps != 1 {
		t.Errorf("steps = %d, want 1: five poll retries are one step of progress", j.Steps)
	}
}

// TestUnavailableSearchIsRecordedAsAFailedJourney covers the other measured
// invisibility: the resident deployment reported purchase and browse at 100%
// failure over millions of attempts, every one on available-train, and the
// structured records showed none of it.
func TestUnavailableSearchIsRecordedAsAFailedJourney(t *testing.T) {
	_, path := recordingConfig(t, "http://unused/%s")
	rec, err := NewRecorder(&Config{Recording: RecordingConfig{
		Enabled: true, Path: path, FlushIntervalSeconds: 3600, BufferRecords: 1024,
	}})
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}

	// The real provider error, reached before any request is issued.
	p := newTestProviders(t, newFakeMesh(t, nil), &Config{})
	_, searchErr := p.AvailableTrain(context.Background(), []string{"tvl-1"}, "WEB")
	if searchErr == nil {
		t.Fatal("AvailableTrain succeeded against an empty registry")
	}
	NewAttempt("purchase", "purchase", "casual").Record(rec, "", searchErr)
	rec.Close()

	jrecs := readJourneyRecords(t, path)
	if len(jrecs) != 1 {
		t.Fatalf("got %d journey records, want 1", len(jrecs))
	}
	j := jrecs[0]
	if j.Status != StatusFailed {
		t.Errorf("status = %q, want %q", j.Status, StatusFailed)
	}
	if j.Failure != FailureUnavailable {
		t.Errorf("failure = %q, want %q", j.Failure, FailureUnavailable)
	}
	if !j.ErrorShown {
		t.Error("error_shown = false: a search with nothing to book shows the user a result")
	}
	if j.Steps != 0 {
		t.Errorf("steps = %d, want 0: it failed before issuing a request", j.Steps)
	}
	if j.Step != "available-train" {
		t.Errorf("step = %q, want available-train", j.Step)
	}
}

// ---------------------------------------------------------------------------
// what the user saw
// ---------------------------------------------------------------------------

// TestErrorShownSeparatesASilentTimeoutFromADisplayedRefusal: "it just sat
// there spinning" and "it said my payment was declined" are different
// complaints, and error_shown is the field that decides which one is written.
func TestErrorShownSeparatesASilentTimeoutFromADisplayedRefusal(t *testing.T) {
	cases := []struct {
		failure string
		want    bool
		why     string
	}{
		{FailureHTTPStatus, true, "an error page was displayed"},
		{FailureRejected, true, "the system answered with a refusal"},
		{FailureUnavailable, true, "the search showed nothing to book"},
		{FailureTransport, true, "the page failed to load"},
		{FailureTimeout, false, "it never finished and showed nothing"},
		{FailureUnfulfilled, false, "accepted, then never completed"},
		{FailureMalformed, false, "it looked like it worked"},
		{FailureInternal, false, "never reached a screen"},
		{FailureShutdown, false, "never reached a screen"},
		{"", false, "the attempt did not fail"},
	}
	for _, c := range cases {
		if got := failureWasVisible(c.failure); got != c.want {
			t.Errorf("failureWasVisible(%q) = %v, want %v (%s)", c.failure, got, c.want, c.why)
		}
	}
}

// TestDisplayedStatusReachesTheRecord: a refused payment must carry the status
// the person was shown, because "it said 402" and "it said 500" are different
// complaints about the same step.
func TestDisplayedStatusReachesTheRecord(t *testing.T) {
	mesh := newFakeMesh(t, func(service, method, path string) (int, map[string]interface{}) {
		return 402, map[string]interface{}{"reason": "CARD_DECLINED"}
	})
	cfg, path := recordingConfig(t, mesh.srv.URL)
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	api := NewApiClientWithRecorder(cfg, NewStats(), rec)

	attempt := NewAttempt("purchase", "purchase", "business")
	ctx := WithAttempt(context.Background(), attempt)
	_, _, reqErr := api.Request(ctx, "POST", "payment", "/api/v1/payment-intents/pi-1/capture",
		nil, nil, []int{200}, "payment-capture")
	if reqErr == nil {
		t.Fatal("a 402 against ok=[200] must be a StepError")
	}
	attempt.Record(rec, "", reqErr)
	rec.Close()

	jrecs := readJourneyRecords(t, path)
	if len(jrecs) != 1 {
		t.Fatalf("got %d journey records, want 1", len(jrecs))
	}
	j := jrecs[0]
	if j.HTTPStatus != 402 {
		t.Errorf("http_status = %d, want 402", j.HTTPStatus)
	}
	if j.Failure != FailureHTTPStatus {
		t.Errorf("failure = %q, want %q", j.Failure, FailureHTTPStatus)
	}
	if !j.ErrorShown {
		t.Error("error_shown = false for a 402: an error was displayed")
	}
	if !strings.Contains(j.Detail, "CARD_DECLINED") {
		t.Errorf("detail = %q, want the refusal reason the user was shown", j.Detail)
	}
	if j.Step != "payment-capture" {
		t.Errorf("step = %q, want payment-capture", j.Step)
	}
	// The trace of the FAILING request, so an engineer opens the right span.
	reqs := readRecords(t, path)
	if len(reqs) != 1 {
		t.Fatalf("got %d request records, want 1", len(reqs))
	}
	if j.TraceID != reqs[0].TraceID {
		t.Errorf("journey trace_id = %q, want the failing request's %q",
			j.TraceID, reqs[0].TraceID)
	}
}

// TestTransportFailureAndTimeoutAreDifferentJourneyFailures: both produce
// status 0 in a request row, and the request row's `error` marker cannot be
// read as "what the user saw". The journey record must split them.
func TestTransportFailureAndTimeoutAreDifferentJourneyFailures(t *testing.T) {
	// A closed port: the page fails to load.
	mesh := newFakeMesh(t, nil)
	deadURL := mesh.srv.URL
	mesh.srv.Close()

	cfg, path := recordingConfig(t, deadURL)
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	api := NewApiClientWithRecorder(cfg, NewStats(), rec)

	attempt := NewAttempt("purchase", "purchase", "casual")
	_, _, reqErr := api.Request(WithAttempt(context.Background(), attempt),
		"GET", "order", "/api/v1/orders", nil, nil, []int{200}, "list")
	if reqErr == nil {
		t.Fatal("a request to a closed server must fail")
	}
	attempt.Record(rec, "", reqErr)
	rec.Close()

	jrecs := readJourneyRecords(t, path)
	if len(jrecs) != 1 {
		t.Fatalf("got %d journey records, want 1", len(jrecs))
	}
	j := jrecs[0]
	if j.Failure != FailureTransport {
		t.Errorf("failure = %q, want %q for a refused connection", j.Failure, FailureTransport)
	}
	if !j.ErrorShown {
		t.Error("error_shown = false: a page that fails to load is visible")
	}
	if j.HTTPStatus != 0 {
		t.Errorf("http_status = %d, want 0: no status was received", j.HTTPStatus)
	}
}

// ---------------------------------------------------------------------------
// outcome vocabulary
// ---------------------------------------------------------------------------

// TestOutcomeVocabularyIsCollectedNotInvented: the journeys already have their
// own outcome words, and the record carries them unchanged while mapping them
// onto the coarse status a complaint opens with. Every word here is returned
// by a journey in this package.
func TestOutcomeVocabularyIsCollectedNotInvented(t *testing.T) {
	cases := []struct {
		outcome string
		want    string
	}{
		// journey_purchase.go
		{"purchased", StatusCompleted},
		{"abandoned", StatusAbandoned},
		{"abandoned_before_payment", StatusAbandoned},
		{"cancelled_payment_intent", StatusAbandoned},
		{"cancelled_before_payment", StatusAbandoned},
		// The system considered these and said no. p_risk_approve is 0.50 by
		// configuration, so half of all reviewed purchases are declined on
		// purpose, and a sold-out train is an answer rather than a fault.
		{"risk_rejected", StatusRefused},
		{"no_available_capacity", StatusRefused},
		{"waitlist_conflict", StatusRefused},
		{"waitlist_queued", StatusCompleted},
		{"waitlist_cancelled", StatusAbandoned},
		// journey_browse.go
		{"browsed", StatusCompleted},
		{"browsed_with_quote", StatusCompleted},
		// journey_refund.go / journey_change.go
		{"refunded", StatusCompleted},
		{"changed", StatusCompleted},
		{"no_purchase_to_refund", StatusSkipped},
		{"no_purchase_to_change", StatusSkipped},
		// journey_fulfillment.go -- a no-show is a COMPLETED fulfillment, which
		// is why the skipped set is listed rather than matched on a "no" prefix.
		{"fulfilled", StatusCompleted},
		{"no_show", StatusCompleted},
		{"no_purchase_to_fulfill", StatusSkipped},
		// journey_ride.go
		{"user_cancelled", StatusAbandoned},
		{"completed", StatusCompleted},
		// journey_legacy.go
		{"legacy_completed", StatusCompleted},
		{"legacy_cancelled", StatusAbandoned},
		{"no_legacy_route", StatusSkipped},
		// the services' own "it did not come back" words
		{"loyalty_enroll_failed", StatusFailed},
		{"group_failed", StatusFailed},
		{"campaign_draft_failed", StatusFailed},
		{"insurance_policy_failed", StatusFailed},
		{"corporate_agreement_failed", StatusFailed},
		// journey_disruption.go and journey_transfer.go return a lowercased
		// service status, so these three arrive with no suffix for the rules to
		// match. A recovery case that reached FAILED was being recorded as a
		// completed journey.
		{"failed", StatusFailed},
		{"invalidated", StatusFailed},
		{"declined", StatusRefused},
		// A connection the traveller did not make, which p_transfer_missed
		// drives on purpose.
		{"missed", StatusRefused},
		// The staff risk action's own result word, reaching classification
		// through the staff attempt rather than a journey.
		{"rejected", StatusRefused},
		// main.go's default arm
		{"unknown_journey", StatusSkipped},
		// a journey that named no outcome still ran to its end
		{"", StatusCompleted},
	}
	for _, c := range cases {
		if got := classifyOutcome(c.outcome); got != c.want {
			t.Errorf("classifyOutcome(%q) = %q, want %q", c.outcome, got, c.want)
		}
	}
}

// TestAbandonmentIsNotRecordedAsAFailure: a person choosing to stop is not a
// fault, and counting it as one would put the abandon knobs into the error
// rate.
func TestAbandonmentIsNotRecordedAsAFailure(t *testing.T) {
	_, path := recordingConfig(t, "http://unused/%s")
	rec, err := NewRecorder(&Config{Recording: RecordingConfig{
		Enabled: true, Path: path, FlushIntervalSeconds: 3600, BufferRecords: 1024,
	}})
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	NewAttempt("purchase", "purchase", "casual").Record(rec, "abandoned_before_payment", nil)
	rec.Close()

	jrecs := readJourneyRecords(t, path)
	if len(jrecs) != 1 {
		t.Fatalf("got %d journey records, want 1", len(jrecs))
	}
	j := jrecs[0]
	if j.Status != StatusAbandoned {
		t.Errorf("status = %q, want %q", j.Status, StatusAbandoned)
	}
	if j.Outcome != "abandoned_before_payment" {
		t.Errorf("outcome = %q, want the journey's own word unchanged", j.Outcome)
	}
	if j.Failure != "" {
		t.Errorf("failure = %q, want empty: nothing went wrong", j.Failure)
	}
	if j.ErrorShown {
		t.Error("error_shown = true for an abandonment")
	}
}

// TestRefusalIsNotRecordedAsAFailure: a decision the system made on purpose is
// not a fault, and counting it as one makes the failure rate a measure of how
// much deliberate refusal the configuration asks for.
//
// p_risk_approve is 0.50 on the deployed profile, so half of every reviewed
// purchase is declined by design. A deployment gate that reads "no journey
// failed" cannot exist while those rows say failed.
func TestRefusalIsNotRecordedAsAFailure(t *testing.T) {
	_, path := recordingConfig(t, "http://unused/%s")
	rec, err := NewRecorder(&Config{Recording: RecordingConfig{
		Enabled: true, Path: path, FlushIntervalSeconds: 3600, BufferRecords: 1024,
	}})
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	NewAttempt("purchase", "purchase", "business").Record(rec, "risk_rejected", nil)
	rec.Close()

	jrecs := readJourneyRecords(t, path)
	if len(jrecs) != 1 {
		t.Fatalf("got %d journey records, want 1", len(jrecs))
	}
	j := jrecs[0]
	if j.Status != StatusRefused {
		t.Errorf("status = %q, want %q", j.Status, StatusRefused)
	}
	if j.Outcome != "risk_rejected" {
		t.Errorf("outcome = %q, want the journey's own word unchanged", j.Outcome)
	}
	if j.Failure != "" {
		t.Errorf("failure = %q, want empty: the request was answered", j.Failure)
	}
	// The one thing a refusal shares with a failure. The person was told no,
	// and that is what a complaint written from this row would be about.
	if !j.ErrorShown {
		t.Error("error_shown = false for a refusal the person was shown")
	}
}

// TestAFailedRecoveryIsNotRecordedAsCompleted: disruption-recovery and
// transfer-management answer with a bare lowercased status, so `failed` and
// `invalidated` carry no suffix for the classification rules to match and were
// landing in completed. A recovery case that failed is the exact row a
// deployment gate has to see.
func TestAFailedRecoveryIsNotRecordedAsCompleted(t *testing.T) {
	for _, outcome := range []string{"failed", "invalidated"} {
		if got := classifyOutcome(outcome); got != StatusFailed {
			t.Errorf("classifyOutcome(%q) = %q, want %q", outcome, got, StatusFailed)
		}
	}
}

// TestShutdownIsNotRecordedAsAFailure: cancelling the run under an in-flight
// attempt would otherwise add a burst of fake faults at every shutdown.
func TestShutdownIsNotRecordedAsAFailure(t *testing.T) {
	_, path := recordingConfig(t, "http://unused/%s")
	rec, err := NewRecorder(&Config{Recording: RecordingConfig{
		Enabled: true, Path: path, FlushIntervalSeconds: 3600, BufferRecords: 1024,
	}})
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	NewAttempt("purchase", "purchase", "casual").Record(rec, "", context.Canceled)
	NewAttempt("browse", "browse", "casual").Record(rec, "", context.DeadlineExceeded)
	rec.Close()

	jrecs := readJourneyRecords(t, path)
	if len(jrecs) != 2 {
		t.Fatalf("got %d journey records, want 2", len(jrecs))
	}
	for _, j := range jrecs {
		if j.Status != StatusCancelled {
			t.Errorf("%s status = %q, want %q", j.Journey, j.Status, StatusCancelled)
		}
		if j.Failure != FailureShutdown {
			t.Errorf("%s failure = %q, want %q", j.Journey, j.Failure, FailureShutdown)
		}
		if j.ErrorShown {
			t.Errorf("%s error_shown = true for a shutdown", j.Journey)
		}
	}
}

// TestPlainErrorIsRecordedAsInternal: every StepError site sets a Kind, so a
// bare error is the generator's own machinery failing rather than anything a
// person could have seen.
func TestPlainErrorIsRecordedAsInternal(t *testing.T) {
	_, path := recordingConfig(t, "http://unused/%s")
	rec, err := NewRecorder(&Config{Recording: RecordingConfig{
		Enabled: true, Path: path, FlushIntervalSeconds: 3600, BufferRecords: 1024,
	}})
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	NewAttempt("purchase", "purchase", "casual").Record(rec, "", errors.New("boom"))
	rec.Close()

	jrecs := readJourneyRecords(t, path)
	if len(jrecs) != 1 {
		t.Fatalf("got %d journey records, want 1", len(jrecs))
	}
	j := jrecs[0]
	if j.Status != StatusFailed {
		t.Errorf("status = %q, want %q", j.Status, StatusFailed)
	}
	if j.Outcome != "crashed" {
		t.Errorf("outcome = %q, want crashed", j.Outcome)
	}
	if j.Failure != FailureInternal {
		t.Errorf("failure = %q, want %q", j.Failure, FailureInternal)
	}
	if j.ErrorShown {
		t.Error("error_shown = true for an internal error nobody saw")
	}
}

// ---------------------------------------------------------------------------
// every StepError carries a user-visible kind
// ---------------------------------------------------------------------------

// TestEveryStepErrorCarriesAFailureKind guards the invariant the record set
// depends on: a StepError with no Kind produces a journey row nobody can write
// a complaint from, because it says a booking failed without saying whether
// anything was displayed. Scraped rather than exercised, so a new construction
// site anywhere in the package is caught.
func TestEveryStepErrorCarriesAFailureKind(t *testing.T) {
	entries, err := os.ReadDir(".")
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		name := e.Name()
		if !strings.HasSuffix(name, ".go") || strings.HasSuffix(name, "_test.go") {
			continue
		}
		src, err := os.ReadFile(name)
		if err != nil {
			t.Fatal(err)
		}
		// Each construction is a `&StepError{` through its closing brace. The
		// literals in this package are all brace-balanced on one or more whole
		// lines, so scanning to the matching brace is exact.
		text := string(src)
		for idx := 0; ; {
			at := strings.Index(text[idx:], "&StepError{")
			if at < 0 {
				break
			}
			start := idx + at
			depth := 0
			end := start
			for i := start + len("&StepError"); i < len(text); i++ {
				if text[i] == '{' {
					depth++
				} else if text[i] == '}' {
					depth--
					if depth == 0 {
						end = i
						break
					}
				}
			}
			lit := text[start : end+1]
			if !strings.Contains(lit, "Kind:") {
				line := 1 + strings.Count(text[:start], "\n")
				t.Errorf("%s:%d: StepError with no Kind: %s", name, line, lit)
			}
			idx = end + 1
		}
	}
}

// ---------------------------------------------------------------------------
// progress accounting
// ---------------------------------------------------------------------------

// TestStepsCountsProgressNotRetries: "it failed at payment" and "it failed at
// search" are different complaints, and how far through the journey the person
// got must not be inflated by a retry loop. offer-management's 422 retry
// issues eight requests for one step of progress.
func TestStepsCountsProgressNotRetries(t *testing.T) {
	mesh := newFakeMesh(t, nil)
	cfg, path := recordingConfig(t, mesh.srv.URL)
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	api := NewApiClientWithRecorder(cfg, NewStats(), rec)

	attempt := NewAttempt("purchase", "purchase", "casual")
	ctx := WithAttempt(context.Background(), attempt)
	for _, step := range []string{"register-account", "create-traveler", "search", "quote"} {
		if _, _, err := api.Request(ctx, "GET", "svc", "/api/v1/thing",
			nil, nil, []int{200}, step); err != nil {
			t.Fatalf("%s: %v", step, err)
		}
	}
	// Eight attempts at ONE step.
	for i := 0; i < 8; i++ {
		if _, _, err := api.Request(ctx, "POST", "offer-management", "/api/v1/offers",
			nil, nil, []int{200}, "offer"); err != nil {
			t.Fatalf("offer: %v", err)
		}
	}
	attempt.Record(rec, "purchased", nil)
	rec.Close()

	jrecs := readJourneyRecords(t, path)
	if len(jrecs) != 1 {
		t.Fatalf("got %d journey records, want 1", len(jrecs))
	}
	if jrecs[0].Steps != 5 {
		t.Errorf("steps = %d, want 5 distinct steps (12 requests, 8 of them one retried step)",
			jrecs[0].Steps)
	}
	if jrecs[0].Step != "offer" {
		t.Errorf("step = %q, want offer (the last step reached)", jrecs[0].Step)
	}
}

// TestJourneyDurationIsTheWholeAttempt: a complaint is about the wait, and the
// number has to be the end-to-end one rather than the failing request's
// latency. The measured case was 58453 poll-order requests at 3.7 ms each
// inside journeys that took minutes.
func TestJourneyDurationIsTheWholeAttempt(t *testing.T) {
	_, path := recordingConfig(t, "http://unused/%s")
	rec, err := NewRecorder(&Config{Recording: RecordingConfig{
		Enabled: true, Path: path, FlushIntervalSeconds: 3600, BufferRecords: 1024,
	}})
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	attempt := NewAttempt("purchase", "purchase", "casual")
	time.Sleep(30 * time.Millisecond)
	attempt.Record(rec, "purchased", nil)
	rec.Close()

	jrecs := readJourneyRecords(t, path)
	if len(jrecs) != 1 {
		t.Fatalf("got %d journey records, want 1", len(jrecs))
	}
	if jrecs[0].DurationMs < 25 {
		t.Errorf("duration_ms = %v, want >= 25: it must span the whole attempt",
			jrecs[0].DurationMs)
	}
}

// ---------------------------------------------------------------------------
// coverage of the actors
// ---------------------------------------------------------------------------

// TestEveryActorProducesJourneyRecords: the printf lines this replaces were
// spread over customer journeys, staff, scalper and ops, each in its own
// format. All of them must land in the one stream.
func TestEveryActorProducesJourneyRecords(t *testing.T) {
	_, path := recordingConfig(t, "http://unused/%s")
	rec, err := NewRecorder(&Config{Recording: RecordingConfig{
		Enabled: true, Path: path, FlushIntervalSeconds: 3600, BufferRecords: 1024,
	}})
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	// The chain vocabulary WithChain already uses, so journey rows and request
	// rows are sliceable the same way.
	NewAttempt("purchase", "purchase", "casual").Record(rec, "purchased", nil)
	NewAttempt("staff", "staff_reservation", "").Record(rec, "", &StepError{
		Step: "reservation", Detail: "no saga", Kind: FailureUnfulfilled})
	NewAttempt("scalper", "scalper_grab", "").Record(rec, "grabbed", nil)
	NewAttempt("ops", "ops_sweep", "").Record(rec, "swept", nil)
	NewAttempt("bootstrap", "bootstrap_inventory", "").Record(rec, "seeded_4_routes", nil)
	NewAttempt("schedule", "schedule_publish", "").Record(rec, "published_9_routes", nil)
	rec.Close()

	seen := map[string]string{}
	for _, j := range readJourneyRecords(t, path) {
		seen[j.Chain] = j.Journey
	}
	for _, want := range []string{"purchase", "staff", "scalper", "ops", "bootstrap", "schedule"} {
		if _, ok := seen[want]; !ok {
			t.Errorf("no journey record with chain %q", want)
		}
	}
	// A persona is a customer-only notion, and a complaint from a back-office
	// action has no persona to attribute.
	for _, j := range readJourneyRecords(t, path) {
		if j.Chain != "purchase" && j.Persona != "" {
			t.Errorf("chain %q has persona %q, want empty", j.Chain, j.Persona)
		}
	}
}

// TestPersonaReachesTheRecord: providers.go picks the persona and PersonaConfig
// has no name of its own, so the configured key is the only identifier. A
// complaint has to say which kind of customer is complaining.
func TestPersonaReachesTheRecord(t *testing.T) {
	cfg := &Config{Personas: map[string]PersonaConfig{
		"business": {Weight: 1},
	}}
	p := newTestProviders(t, newFakeMesh(t, nil), cfg)
	name, persona := p.PickPersona()
	if name != "business" {
		t.Fatalf("PickPersona name = %q, want business", name)
	}
	if persona == nil {
		t.Fatal("PickPersona returned a nil config for a configured persona")
	}

	// No personas configured is a supported deployment (the config file calls
	// it "a single implicit persona"), and it must not invent a name.
	bare := newTestProviders(t, newFakeMesh(t, nil), &Config{})
	if name, persona := bare.PickPersona(); name != "" || persona != nil {
		t.Errorf("PickPersona with no personas = (%q, %v), want (\"\", nil)", name, persona)
	}
}

// ---------------------------------------------------------------------------
// recording disabled / detail bounds
// ---------------------------------------------------------------------------

// TestJourneyRecordingIsInertWhenDisabled: a nil Recorder is the "disabled"
// state, and the journey path must be as nil-safe as the request path.
func TestJourneyRecordingIsInertWhenDisabled(t *testing.T) {
	var rec *Recorder
	NewAttempt("purchase", "purchase", "casual").Record(rec, "purchased", nil)
	rec.RecordJourneyAttempt(JourneyRecord{})
	if written, dropped := rec.Counters(); written != 0 || dropped != 0 {
		t.Errorf("nil recorder counters = (%d, %d), want (0, 0)", written, dropped)
	}

	// A nil Attempt must also be safe: bootstrap probes and the schedule
	// publisher make requests outside any attempt.
	var a *Attempt
	a.observe("step", "trace")
	a.Record(rec, "x", nil)
	if steps, step, trace := a.progress(); steps != 0 || step != "" || trace != "" {
		t.Errorf("nil attempt progress = (%d, %q, %q), want (0, \"\", \"\")", steps, step, trace)
	}
	if id := a.id(); id != "" {
		t.Errorf("nil attempt id = %q, want empty", id)
	}
	if AttemptFromContext(context.Background()) != nil {
		t.Error("a bare context must carry no attempt")
	}
}

// TestRecordingSurvivesAJourneyThatOUTLIVESShutdown.
//
// The open-loop scheduler starts one goroutine per arrival and does not track
// them on the WaitGroup (RunOpenLoop in scheduler.go), so journeys are still
// issuing requests when main calls Close. Signalling shutdown by closing the
// record channel made that a `send on closed channel` panic, which took the
// whole generator down at the end of every open-loop run -- reproduced against
// a fake mesh with `panic: send on closed channel` out of Record.
//
// The non-blocking select in Record is no defence: its `default` arm is taken
// when the channel is FULL, not when it is closed.
func TestRecordingSurvivesAJourneyThatOutlivesShutdown(t *testing.T) {
	mesh := newFakeMesh(t, nil)
	cfg, path := recordingConfig(t, mesh.srv.URL)
	rec, err := NewRecorder(cfg)
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	api := NewApiClientWithRecorder(cfg, NewStats(), rec)

	// A journey that is mid-flight when shutdown happens, exactly as an
	// open-loop arrival goroutine is.
	var wg sync.WaitGroup
	stop := make(chan struct{})
	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			select {
			case <-stop:
				return
			default:
			}
			attempt := NewAttempt("purchase", "purchase", "casual")
			ctx := WithAttempt(context.Background(), attempt)
			_, _, _ = api.Request(ctx, "GET", "order", "/api/v1/orders",
				nil, nil, []int{200}, "list")
			attempt.Record(rec, "purchased", nil)
		}
	}()

	// Let it get going, then close underneath it. A panic here fails the test
	// by crashing the process, which is precisely the regression.
	time.Sleep(50 * time.Millisecond)
	rec.Close()
	// Keep recording past the close for a while: every one of these calls
	// would have panicked.
	time.Sleep(50 * time.Millisecond)
	close(stop)
	wg.Wait()

	// Close is idempotent, and a second one must not panic either.
	rec.Close()

	// The records written before shutdown are on disk, and the file is valid
	// JSON Lines throughout.
	if len(readRecords(t, path)) == 0 {
		t.Error("no request records survived shutdown")
	}
	if len(readJourneyRecords(t, path)) == 0 {
		t.Error("no journey records survived shutdown")
	}
	written, _ := rec.Counters()
	if written == 0 {
		t.Error("the recorder wrote nothing")
	}
}

// TestJourneyDetailIsBounded: StepError.Detail already carries up to 150 bytes
// of response body, and `detail` is read as a sentence. An unbounded blob in
// that column would make the rectangle unusable.
func TestJourneyDetailIsBounded(t *testing.T) {
	_, path := recordingConfig(t, "http://unused/%s")
	rec, err := NewRecorder(&Config{Recording: RecordingConfig{
		Enabled: true, Path: path, FlushIntervalSeconds: 3600, BufferRecords: 1024,
	}})
	if err != nil {
		t.Fatalf("NewRecorder: %v", err)
	}
	NewAttempt("purchase", "purchase", "casual").Record(rec, "", &StepError{
		Step:   "payment-capture",
		Detail: strings.Repeat("x", 5000),
		Kind:   FailureHTTPStatus,
	})
	rec.Close()

	jrecs := readJourneyRecords(t, path)
	if len(jrecs) != 1 {
		t.Fatalf("got %d journey records, want 1", len(jrecs))
	}
	if len(jrecs[0].Detail) != detailLimit {
		t.Errorf("detail is %d bytes, want it truncated to %d",
			len(jrecs[0].Detail), detailLimit)
	}
}

// TestWithAttemptSetsTheChainLabelToo: the journey row's chain and its request
// rows' chain must agree, or the two record types cannot be sliced the same
// way.
func TestWithAttemptSetsTheChainLabelToo(t *testing.T) {
	a := NewAttempt("refund", "refund", "casual")
	ctx := WithAttempt(context.Background(), a)
	if got := ChainFromContext(ctx); got != "refund" {
		t.Errorf("chain from an attempt context = %q, want refund", got)
	}
	if got := AttemptFromContext(ctx); got != a {
		t.Errorf("attempt from context = %v, want the attempt that was set", got)
	}
}
