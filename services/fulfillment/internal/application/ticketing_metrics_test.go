package application

import (
	"context"
	"testing"
	"time"

	"github.com/trainticket/greenfield/services/fulfillment/internal/adapters/observability"
	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

type recordedTransition struct {
	Outcome    string
	TicketType string
	Reason     string
}

type recordingRecorder struct{ transitions []recordedTransition }

func (r *recordingRecorder) Transition(outcome, ticketType, reasonCode string) {
	r.transitions = append(r.transitions, recordedTransition{Outcome: outcome, TicketType: ticketType, Reason: reasonCode})
}

func newRecordingService(t *testing.T) (*Service, *recordingRecorder, *recordingPublisher) {
	t.Helper()
	publisher := &recordingPublisher{}
	service := NewService(NewInMemoryRepository(), publisher, NewInMemoryConsumedEventLog(), func(prefix string) string {
		return prefix + "-00000000-0000-4000-8000-000000000001"
	}, func() time.Time { return time.Date(2026, 7, 5, 10, 1, 0, 0, time.UTC) })
	recorder := &recordingRecorder{}
	service.WithTicketingMetrics(recorder)
	return service, recorder, publisher
}

func TestBoardingRecordsAnAcceptedValidationTransition(t *testing.T) {
	service, recorder, _ := newRecordingService(t)
	seedTicket(t, service, "ent-metric-1", "sb-metric-1", "ord-metric-1", "tvl-metric-1", "seg-metric-1")

	if _, err := service.VerifyBoarding(context.Background(), VerifyBoardingCommand{
		EntitlementID:    "ent-metric-1",
		SegmentBookingID: "sb-metric-1",
		JourneyOrderID:   "ord-metric-1",
		TravelerID:       "tvl-metric-1",
		SegmentRef:       "seg-metric-1",
		Source:           domain.FulfillmentSourceGate,
		SourceEventID:    "gate-scan-metric-1",
		OccurredAt:       time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC),
	}, CommandMetadata{CorrelationID: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c101", CausationID: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c102"}); err != nil {
		t.Fatalf("verify boarding: %v", err)
	}

	if len(recorder.transitions) != 1 {
		t.Fatalf("expected one transition, got %#v", recorder.transitions)
	}
	got := recorder.transitions[0]
	if got.Outcome != observability.OutcomeAccepted || got.Reason != observability.ReasonNone {
		t.Fatalf("unexpected transition: %#v", got)
	}
	if got.TicketType != observability.TicketTypeUnknown {
		t.Fatalf("fulfillment cannot name a credential type: %#v", got)
	}
}

// A duplicate boarding scan changes nothing. Reporting it as "accepted" would
// make the funnel look like it boarded more travelers than it has.
func TestDuplicateBoardingRecordsANoop(t *testing.T) {
	service, recorder, _ := newRecordingService(t)
	seedTicket(t, service, "ent-metric-2", "sb-metric-2", "ord-metric-2", "tvl-metric-2", "seg-metric-2")
	command := VerifyBoardingCommand{
		EntitlementID:    "ent-metric-2",
		SegmentBookingID: "sb-metric-2",
		JourneyOrderID:   "ord-metric-2",
		TravelerID:       "tvl-metric-2",
		SegmentRef:       "seg-metric-2",
		Source:           domain.FulfillmentSourceGate,
		SourceEventID:    "gate-scan-metric-2",
		OccurredAt:       time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC),
	}
	meta := CommandMetadata{CorrelationID: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c201", CausationID: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c202"}
	if _, err := service.VerifyBoarding(context.Background(), command, meta); err != nil {
		t.Fatalf("first boarding: %v", err)
	}
	if _, err := service.VerifyBoarding(context.Background(), command, meta); err != nil {
		t.Fatalf("duplicate boarding: %v", err)
	}
	if len(recorder.transitions) != 2 {
		t.Fatalf("expected two transitions, got %#v", recorder.transitions)
	}
	if recorder.transitions[1].Outcome != observability.OutcomeNoop {
		t.Fatalf("a duplicate boarding is a no-op: %#v", recorder.transitions[1])
	}
	if recorder.transitions[1].Reason != observability.ReasonIdempotentReplay {
		t.Fatalf("a duplicate boarding is an idempotent replay: %#v", recorder.transitions[1])
	}
}

// The refusal reason comes from the state the record was in. The domain error
// text names the record id, and that text must not become a label.
func TestCompletionBeforeBoardingRecordsAPreconditionReason(t *testing.T) {
	service, recorder, _ := newRecordingService(t)
	seedTicket(t, service, "ent-metric-3", "sb-metric-3", "ord-metric-3", "tvl-metric-3", "seg-metric-3")

	_, err := service.RecordFulfillmentCompleted(context.Background(), FulfillmentCompletedCommand{
		EntitlementID:    "ent-metric-3",
		SegmentBookingID: "sb-metric-3",
		JourneyOrderID:   "ord-metric-3",
		TravelerID:       "tvl-metric-3",
		SegmentRef:       "seg-metric-3",
		CompletionSource: domain.CompletionSourceSystem,
		CompletedAt:      time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC),
	}, CommandMetadata{CorrelationID: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c301", CausationID: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c302"})
	if err == nil {
		t.Fatal("completion before boarding must fail")
	}
	if len(recorder.transitions) != 1 {
		t.Fatalf("expected one transition, got %#v", recorder.transitions)
	}
	got := recorder.transitions[0]
	if got.Outcome != observability.OutcomeRejected || got.Reason != observability.ReasonPreconditionsUnmet {
		t.Fatalf("unexpected transition: %#v", got)
	}
	for _, field := range []string{got.Outcome, got.TicketType, got.Reason} {
		if field == "" || len(field) > 32 {
			t.Fatalf("a label value has to be an owned token, got %q", field)
		}
	}
}

// No-show after boarding is a conflict with a recorded fact, not a generic
// rejection, and it is classified without reading the error text.
func TestNoShowAfterBoardingRecordsABoardingConflict(t *testing.T) {
	service, recorder, _ := newRecordingService(t)
	seedTicket(t, service, "ent-metric-4", "sb-metric-4", "ord-metric-4", "tvl-metric-4", "seg-metric-4")
	base := CommandMetadata{CorrelationID: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c401", CausationID: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c402"}
	if _, err := service.VerifyBoarding(context.Background(), VerifyBoardingCommand{
		EntitlementID:    "ent-metric-4",
		SegmentBookingID: "sb-metric-4",
		JourneyOrderID:   "ord-metric-4",
		TravelerID:       "tvl-metric-4",
		SegmentRef:       "seg-metric-4",
		Source:           domain.FulfillmentSourceGate,
		SourceEventID:    "gate-scan-metric-4",
		OccurredAt:       time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC),
	}, base); err != nil {
		t.Fatalf("verify boarding: %v", err)
	}
	_, err := service.RecordNoShow(context.Background(), RecordNoShowCommand{
		EntitlementID:    "ent-metric-4",
		SegmentBookingID: "sb-metric-4",
		JourneyOrderID:   "ord-metric-4",
		TravelerID:       "tvl-metric-4",
		SegmentRef:       "seg-metric-4",
		Reason:           domain.NoShowReasonWindowExpired,
	}, base)
	if err == nil {
		t.Fatal("no-show after boarding must fail")
	}
	last := recorder.transitions[len(recorder.transitions)-1]
	if last.Outcome != observability.OutcomeRejected || last.Reason != observability.ReasonBoardingConflict {
		t.Fatalf("unexpected transition: %#v", last)
	}
}
