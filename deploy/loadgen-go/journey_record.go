package main

import (
	"context"
	"strings"
	"sync"
	"time"
)

// Journey-level outcome recording.
//
// A journey can fail without any HTTP request failing. journey_purchase.go's
// waitForResult blocks on an in-process channel and returns a timeout when
// staff never answer; every HTTP request that journey made returned 200. On a
// fault-injected deployment 8858 purchase journeys failed on "timed out
// waiting for sb" while the per-request records showed a 0% error rate, and
// the resident deployment's purchase and browse journeys failed 100% of the
// time on "available-train: no routes and fewer than 2 places" with the same
// invisibility. Only a record written per ATTEMPT can say that.
//
// The record's consumer is a system that reads one row and writes what that
// person would have said to support, so an Attempt tracks what the person
// knows: what they were doing, how far they got, how long they waited, and
// what (if anything) they were shown.

// journeyStatus values. The coarse answer to "did it work", which is what a
// complaint opens with. Deliberately small: the journey's own outcome word
// goes in the `outcome` field, unchanged, and carries the detail.
const (
	// StatusCompleted -- the journey ran to its own end. Includes ends a user
	// would not call happy (a no-show, a sold-out search) but which the system
	// answered fully.
	StatusCompleted = "completed"
	// StatusFailed -- it broke. Something the person was waiting for never
	// arrived, or arrived as an error.
	StatusFailed = "failed"
	// StatusAbandoned -- the person stopped on purpose, at one of the
	// behavior.p_abandon_* branches. Nothing was wrong.
	StatusAbandoned = "abandoned"
	// StatusSkipped -- there was nothing to attempt: no purchase to refund, no
	// route to book through the legacy facade. No person experienced anything,
	// so no complaint can come from this row.
	StatusSkipped = "skipped"
	// StatusCancelled -- the run stopped underneath the attempt. An artefact of
	// the generator shutting down, not an experience.
	StatusCancelled = "cancelled"
	// StatusRefused -- the system considered the request and said no, for a
	// reason it owns. A risk review that declined, a refund inside the
	// non-refundable window, a train with no seat left in the class asked for.
	//
	// Its own status and not StatusFailed, because these two answer different
	// questions about a deployment. "Did anything break" has to be able to read
	// zero on a healthy stack, and a stack where half of all high-risk
	// purchases are declined is healthy: p_risk_approve is 0.50 by
	// configuration, and a refund of a departure inside 48 hours is refused by
	// the fare rules on purpose. Counting those as failures makes the failure
	// rate a measure of how much deliberate refusal the configuration asks
	// for.
	//
	// Its own status and not StatusCompleted either, because the person was
	// told no and that is what a complaint would be about. The two are
	// distinguishable in the record and both are visible.
	StatusRefused = "refused"
)

// Attempt accumulates, over the life of one journey attempt, the facts the
// journey itself never returns: how far the attempt got and which requests it
// made. The journey's own return values (outcome, error) supply the rest.
//
// One Attempt exists per journey attempt, against tens of HTTP requests, so
// unlike ReqRecord its construction cost is irrelevant. The mutex is held only
// around a map write and four assignments, and never across a network call.
type Attempt struct {
	ID      string
	Chain   string
	Journey string
	Persona string
	Start   time.Time

	mu          sync.Mutex
	stepsSeen   map[string]struct{}
	lastStep    string
	lastTraceID string
}

// NewAttempt starts tracking one attempt. journey is what the person was
// trying to do in the journey's own terms; persona is "" for actors that have
// none (staff, scalper, ops, bootstrap).
func NewAttempt(chain, journey, persona string) *Attempt {
	return &Attempt{
		ID:        UUID7(),
		Chain:     chain,
		Journey:   journey,
		Persona:   persona,
		Start:     time.Now(),
		stepsSeen: make(map[string]struct{}, 24),
	}
}

// id returns the attempt's id, or "" when there is no attempt. Read on the
// request path, so it touches only an immutable field and takes no lock.
func (a *Attempt) id() string {
	if a == nil {
		return ""
	}
	return a.ID
}

// observe records that one HTTP request was issued under this attempt. Called
// from the request path for every request, whatever its outcome: the last
// request an attempt made is the step it stopped at, and its trace id is the
// join back to the server spans even when that request itself succeeded.
//
// Distinct step labels, not request count: offer-management's 422 retry loop
// issues eight requests for one step of progress, and "how far through the
// journey did they get" must not be inflated by a retry.
func (a *Attempt) observe(step, traceID string) {
	if a == nil {
		return
	}
	a.mu.Lock()
	if step != "" {
		a.stepsSeen[step] = struct{}{}
		a.lastStep = step
	}
	if traceID != "" {
		a.lastTraceID = traceID
	}
	a.mu.Unlock()
}

// progress returns the number of distinct steps reached, the label of the last
// one, and the trace id it carried.
func (a *Attempt) progress() (steps int, lastStep, lastTraceID string) {
	if a == nil {
		return 0, "", ""
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	return len(a.stepsSeen), a.lastStep, a.lastTraceID
}

// Record builds and hands off the journey record for a finished attempt.
//
// outcome is the journey's own return value and err its error; between them
// they decide status, failure kind and what the person saw. Called exactly
// once per attempt, on every path including the error ones, which is the whole
// point: a journey that returns early must still leave a row.
func (a *Attempt) Record(rec *Recorder, outcome string, err error) {
	if a == nil {
		return
	}

	steps, lastStep, lastTraceID := a.progress()

	jr := JourneyRecord{
		StartUnixNano: a.Start.UnixNano(),
		Chain:         a.Chain,
		Journey:       a.Journey,
		Persona:       a.Persona,
		Step:          lastStep,
		Steps:         steps,
		DurationMs:    float64(time.Since(a.Start).Nanoseconds()) / 1e6,
		JourneyID:     a.ID,
		TraceID:       lastTraceID,
	}

	switch {
	case err == nil:
		jr.Status = classifyOutcome(outcome)
		jr.Outcome = outcome

	case isShutdown(err):
		// The run ended under the attempt. Nobody saw this, and counting it as
		// a failure would put a fault in the tally at every shutdown.
		jr.Status = StatusCancelled
		jr.Outcome = "run_cancelled"
		jr.Failure = FailureShutdown

	default:
		jr.Status = StatusFailed
		jr.Detail = err.Error()
		if se, ok := err.(*StepError); ok {
			// The StepError is authoritative over the observed history: the
			// step it names is where the journey actually stopped, and its
			// status and trace id belong to the request that failed rather
			// than to whichever request happened to be last.
			jr.Outcome = "failed_at_" + se.Step
			jr.Step = se.Step
			jr.Failure = se.Kind
			jr.Detail = se.Detail
			jr.HTTPStatus = se.Status
			if se.TraceID != "" {
				jr.TraceID = se.TraceID
			}
		} else {
			// A journey returned a plain error. Every StepError site sets a
			// Kind, so this is the generator's own machinery failing, not
			// anything the person could have seen.
			jr.Outcome = "crashed"
			jr.Failure = FailureInternal
		}
	}

	// A refusal is shown by definition. It reaches here with no failure kind,
	// because nothing went wrong on the way: the journey ran to a service that
	// answered no, and the person read that answer. The downstream generator
	// that writes a complaint from this row needs the same "what were they
	// told" signal it gets from a failure.
	jr.ErrorShown = failureWasVisible(jr.Failure) || jr.Status == StatusRefused
	rec.RecordJourneyAttempt(jr)
}

// failureWasVisible reports whether the failure put something readable in
// front of the person.
//
// A complaint about a timeout and a complaint about a refusal are different
// complaints -- "it just sat there spinning" against "it said my payment was
// declined" -- and this is the field that decides which one gets written. It
// is kept as its own field so the downstream generator does not have to learn
// the failure taxonomy to answer that one question, and so successful and
// abandoned attempts carry the answer too (nothing was shown).
func failureWasVisible(failure string) bool {
	switch failure {
	case FailureHTTPStatus, FailureRejected, FailureUnavailable:
		// The system answered and the answer was the bad news: an error page,
		// a refusal with a reason, a "no trains found".
		return true
	case FailureTransport:
		// The page did not load. No message from the application, but a
		// visible error all the same.
		return true
	default:
		// FailureTimeout and FailureUnfulfilled never finished and showed
		// nothing; FailureMalformed looked like it worked; FailureInternal and
		// FailureShutdown never reached a screen. So does the empty kind, which
		// is every attempt that did not fail.
		return false
	}
}

// isShutdown reports whether an error is the run being cancelled rather than
// anything going wrong.
func isShutdown(err error) bool {
	return err == context.Canceled || err == context.DeadlineExceeded
}

// skippedOutcomes are the outcome words that mean "there was nothing to
// attempt". Listed rather than pattern-matched: `no_show` is a fully completed
// fulfillment and `no_available_capacity` is a real answer a customer received,
// so a prefix rule on "no_" would misfile both.
var skippedOutcomes = map[string]struct{}{
	"no_purchase_to_refund":      {},
	"no_purchase_to_change":      {},
	"no_purchase_to_fulfill":     {},
	"no_purchase_for_disruption": {},
	"no_order_for_support":       {},
	"no_legacy_route":            {},
	"no_itinerary":               {},
	"unknown_journey":            {},
}

// abandonedOutcomes are the words for a person choosing to stop. The purchase
// journey owns this vocabulary; it is collected here rather than restated.
var abandonedOutcomes = map[string]struct{}{
	"abandoned":                {},
	"abandoned_before_payment": {},
	"cancelled_payment_intent": {},
	"cancelled_before_payment": {},
	"waitlist_cancelled":       {},
	"user_cancelled":           {},
	"legacy_cancelled":         {},
}

// refusedOutcomes are the words for the system having considered the request
// and said no.
//
// Listed rather than derived from a suffix, because the suffix rules cannot
// tell a refusal from a breakage: `risk_rejected` is a review that declined
// and `group_failed` is a group booking that did not come back, and both end
// in a word a suffix rule reads the same way.
//
// `rejected` bare is the staff risk action's own result word
// (staff_workers.go's doRisk sets it), reaching classification through the
// staff attempt rather than through a journey. Both spellings are here so the
// same decision is recorded the same way whichever side saw it.
var refusedOutcomes = map[string]struct{}{
	"risk_rejected": {},
	"rejected":      {},
	"declined":      {},
	// The capacity answers. A search that found no seat and a train with none
	// left in the class asked for are answers the system gave, and the person
	// was shown them.
	"no_available_capacity": {},
	"waitlist_conflict":     {},
}

// failedOutcomes are the words that mean the journey broke, where a suffix
// rule would read them as an ordinary end.
//
// Both arrive as a lowercased service status. disruption-recovery's
// RecoveryCaseStatus includes FAILED and DECLINED, and transfer-management's
// ConnectionStatus includes MISSED and INVALIDATED, so the journey returns
// `failed`, `missed` and `invalidated` with no suffix for the rules below to
// match. A recovery that failed was being recorded as a completed journey.
var failedOutcomes = map[string]struct{}{
	"failed":      {},
	"invalidated": {},
}

// classifyOutcome maps a journey's own outcome word to the coarse status.
//
// The outcome vocabulary is partly generated -- waitlist_<terminal state>,
// lowercased service statuses from disruption and transfer -- so this cannot
// be a closed set. The named sets above cover the words the suffix rules would
// get wrong; everything else falls to the documented rules.
func classifyOutcome(outcome string) string {
	if outcome == "" {
		// A journey that returned no outcome and no error ran to its end
		// without naming it.
		return StatusCompleted
	}
	if _, ok := skippedOutcomes[outcome]; ok {
		return StatusSkipped
	}
	if _, ok := abandonedOutcomes[outcome]; ok {
		return StatusAbandoned
	}
	if _, ok := refusedOutcomes[outcome]; ok {
		return StatusRefused
	}
	if _, ok := failedOutcomes[outcome]; ok {
		return StatusFailed
	}
	// A refusal the service named for itself. transfer-management answers
	// `missed` for a connection the traveller did not make, which the
	// generator drives on purpose through p_transfer_missed, so it is the
	// system's own answer rather than a fault.
	if outcome == "missed" {
		return StatusRefused
	}
	// The services' own vocabulary for a failed end state: loyalty_enroll_failed,
	// group_failed, campaign_draft_failed, insurance_policy_failed,
	// corporate_agreement_failed.
	if strings.HasSuffix(outcome, "_failed") {
		return StatusFailed
	}
	// A refusal with its own subject: any `<something>_rejected` the services
	// produce is a decision, not a breakage.
	if strings.HasSuffix(outcome, "_rejected") || strings.HasSuffix(outcome, "_declined") {
		return StatusRefused
	}
	return StatusCompleted
}

// ---------------------------------------------------------------------------
// attempt labelling
// ---------------------------------------------------------------------------

type attemptCtxKey struct{}

// WithAttempt tags a context with the attempt that owns the requests made
// under it, so the request path can report progress back without every
// provider signature growing a parameter. It also sets the chain label, which
// is always the attempt's own chain: the two must agree or a journey row and
// its request rows will not join.
func WithAttempt(ctx context.Context, a *Attempt) context.Context {
	if ctx == nil {
		return nil
	}
	return context.WithValue(WithChain(ctx, a.Chain), attemptCtxKey{}, a)
}

// AttemptFromContext returns the attempt, or nil when there is none (bootstrap
// probes, the schedule publisher). Every Attempt method is nil-safe.
func AttemptFromContext(ctx context.Context) *Attempt {
	if ctx == nil {
		return nil
	}
	a, _ := ctx.Value(attemptCtxKey{}).(*Attempt)
	return a
}
