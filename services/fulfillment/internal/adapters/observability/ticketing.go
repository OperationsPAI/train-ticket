// Package observability owns Fulfillment's low-cardinality business telemetry.
// The contract is docs/07-observability/business-metrics.md.
//
// Fulfillment is the validation half of the ticketing funnel: check-in, boarding
// verification and no-show assessment. It records
//
//	train_ticket.ticketing.transitions (counter) by stage, outcome, ticket_type, reason_code
//
// with stage "validation". Entitlement Ticketing records the issue, replacement
// and void stages; both services carry the same `service` label, so one query
// reads the whole ticket lifecycle without double counting a transition.
//
// No identifying value is a label. Fulfillment record, entitlement, segment
// booking, journey order, traveler and segment references are unbounded and
// never reach an attribute. `reason_code` comes from the owned vocabulary below
// and is classified from typed state and wrapped sentinel errors, never from
// error text: a message is free-form and would leak a record id into a series.
package observability

import (
	"context"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

// RawTicketingTransitions is the metric name before the Collector rewrites it
// into train_ticket_ticketing_transitions_total.
const RawTicketingTransitions = "train_ticket.ticketing.transitions"

// Stage validation, closed outcome vocabulary and closed ticket-type vocabulary.
const (
	StageValidation = "validation"

	OutcomeAccepted = "accepted"
	OutcomeRejected = "rejected"
	OutcomeNoop     = "noop"
	OutcomeFailed   = "failed"

	TicketTypeUnknown = "unknown"

	ReasonNone                  = "none"
	ReasonIdempotentReplay      = "idempotent_replay"
	ReasonPreconditionsUnmet    = "preconditions_unmet"
	ReasonInvalidRequest        = "invalid_request"
	ReasonTerminalStatus        = "terminal_status"
	ReasonBoardingConflict      = "boarding_conflict"
	ReasonConflict              = "conflict"
	ReasonNotFound              = "not_found"
	ReasonDependencyUnavailable = "dependency_unavailable"
	ReasonOther                 = "other"
)

// TransitionRecorder is the seam the application layer records through. The
// application default is a no-op, so a service with metrics export disabled
// pays nothing.
type TransitionRecorder interface {
	Transition(outcome, ticketType, reasonCode string)
}

// NoopRecorder discards transitions.
type NoopRecorder struct{}

func (NoopRecorder) Transition(string, string, string) {}

// OpenTelemetryRecorder writes the counter. Every value it emits is validated
// against the owned vocabulary first, so a caller cannot widen the label space
// by passing a status string through.
type OpenTelemetryRecorder struct {
	counter metric.Int64Counter
}

func NewOpenTelemetryRecorder(meter metric.Meter) (*OpenTelemetryRecorder, error) {
	counter, err := meter.Int64Counter(
		RawTicketingTransitions,
		metric.WithDescription("Ticket issue, replacement, void and validation outcomes by bounded reason."),
		metric.WithUnit("{transition}"),
	)
	if err != nil {
		return nil, err
	}
	return &OpenTelemetryRecorder{counter: counter}, nil
}

var (
	outcomes = map[string]bool{
		OutcomeAccepted: true,
		OutcomeRejected: true,
		OutcomeNoop:     true,
		OutcomeFailed:   true,
	}
	ticketTypes = map[string]bool{
		TicketTypeUnknown: true,
		"eticket":         true,
		"paper_ticket":    true,
		"pickup_code":     true,
		"boarding_pass":   true,
		"ferry_ticket":    true,
		"coach_eticket":   true,
		"ride_code":       true,
	}
	reasonCodes = map[string]bool{
		ReasonNone:                  true,
		ReasonIdempotentReplay:      true,
		ReasonPreconditionsUnmet:    true,
		ReasonInvalidRequest:        true,
		ReasonTerminalStatus:        true,
		ReasonBoardingConflict:      true,
		ReasonConflict:              true,
		ReasonNotFound:              true,
		ReasonDependencyUnavailable: true,
		ReasonOther:                 true,
	}
)

// ReasonCode narrows any value to the owned vocabulary. Unknown values become
// "other"; exception text must never be passed through unchanged.
func ReasonCode(value string) string {
	if reasonCodes[value] {
		return value
	}
	return ReasonOther
}

// Outcome narrows any value to the closed outcome vocabulary.
func Outcome(value string) string {
	if outcomes[value] {
		return value
	}
	return OutcomeFailed
}

// TicketType narrows any value to the closed ticket-type vocabulary.
func TicketType(value string) string {
	if ticketTypes[value] {
		return value
	}
	return TicketTypeUnknown
}

func (r *OpenTelemetryRecorder) Transition(outcome, ticketType, reasonCode string) {
	if r == nil {
		return
	}
	r.counter.Add(context.Background(), 1, metric.WithAttributes(
		attribute.String("stage", StageValidation),
		attribute.String("outcome", Outcome(outcome)),
		attribute.String("ticket_type", TicketType(ticketType)),
		attribute.String("reason_code", ReasonCode(reasonCode)),
	))
}
