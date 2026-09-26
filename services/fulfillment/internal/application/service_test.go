package application

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"strings"
	"testing"
	"time"

	"go.opentelemetry.io/otel/trace"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

type recordingPublisher struct{ envelopes []EventEnvelope }

func (p *recordingPublisher) Publish(_ context.Context, envelope EventEnvelope) error {
	p.envelopes = append(p.envelopes, envelope)
	return nil
}

type failingOncePublisher struct {
	err       error
	attempts  int
	envelopes []EventEnvelope
}

type stubEntitlementReader struct {
	snapshot EntitlementSnapshot
	err      error
	calls    int
}

func (r *stubEntitlementReader) GetEntitlement(_ context.Context, _ domain.EntitlementRef) (EntitlementSnapshot, error) {
	r.calls++
	return r.snapshot, r.err
}

func (p *failingOncePublisher) Publish(_ context.Context, envelope EventEnvelope) error {
	p.attempts++
	if p.attempts == 1 {
		return p.err
	}
	p.envelopes = append(p.envelopes, envelope)
	return nil
}

func seedTicket(t *testing.T, service *Service, entitlementID, segmentBookingID, journeyOrderID, travelerID, segmentRef string) {
	t.Helper()
	envelope := EventEnvelope{EventID: "evt-seed-" + entitlementID, EventType: "EntitlementIssued", Producer: "entitlement-ticketing", SchemaVersion: 1, Payload: json.RawMessage(`{"entitlementId":"` + entitlementID + `","segmentBookingId":"` + segmentBookingID + `","journeyOrderId":"` + journeyOrderID + `","travelerRef":"` + travelerID + `","segmentRef":"` + segmentRef + `"}`)}
	if err := service.HandleSubscribedEvent(context.Background(), envelope); err != nil {
		t.Fatalf("seed ticket: %v", err)
	}
}

func TestPublisherWrapsDomainEventInCanonicalEnvelope(t *testing.T) {
	publisher := &recordingPublisher{}
	service := NewService(NewInMemoryRepository(), publisher, NewInMemoryConsumedEventLog(), func(prefix string) string {
		return prefix + "-00000000-0000-4000-8000-000000000001"
	}, func() time.Time { return time.Date(2026, 7, 5, 10, 1, 0, 0, time.UTC) })

	seedTicket(t, service, "ent-abc123", "sb-def456", "ord-ghi789", "tvl-jkl012", "seg-mno345")
	occurredAt := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	_, err := service.VerifyBoarding(context.Background(), VerifyBoardingCommand{
		EntitlementID:    "ent-abc123",
		SegmentBookingID: "sb-def456",
		JourneyOrderID:   "ord-ghi789",
		TravelerID:       "tvl-jkl012",
		SegmentRef:       "seg-mno345",
		Source:           domain.FulfillmentSourceGate,
		SourceEventID:    "gate-scan-1",
		OccurredAt:       occurredAt,
	}, CommandMetadata{CorrelationID: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c0aa", CausationID: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c0bb"})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(publisher.envelopes) != 1 {
		t.Fatalf("expected one published envelope, got %d", len(publisher.envelopes))
	}
	envelope := publisher.envelopes[0]
	if !strings.HasPrefix(envelope.EventID, "evt-") {
		t.Fatalf("eventId must be prefixed: %s", envelope.EventID)
	}
	if envelope.EventType != "BoardingVerified" || envelope.Producer != ProducerName || envelope.SchemaVersion != 1 {
		t.Fatalf("unexpected envelope: %#v", envelope)
	}
	if !strings.HasPrefix(envelope.CorrelationID, "corr-") || !strings.HasPrefix(envelope.CausationID, "cmd-") {
		t.Fatalf("unexpected correlation/causation: %#v", envelope)
	}
	if !envelope.OccurredAt.Equal(occurredAt) {
		t.Fatalf("unexpected occurredAt: %s", envelope.OccurredAt)
	}
	var payload struct {
		FulfillmentRecordID string `json:"fulfillmentRecordId"`
		EntitlementID       string `json:"entitlementId"`
		SourceEventID       string `json:"sourceEventId"`
	}
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		t.Fatalf("payload is not json: %v", err)
	}
	if payload.FulfillmentRecordID == "" || payload.EntitlementID != "ent-abc123" || payload.SourceEventID != "gate-scan-1" {
		t.Fatalf("unexpected payload: %#v", payload)
	}
	body, err := json.Marshal(envelope)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(body), `"traceparent"`) {
		t.Fatalf("traceparent must be omitted without span context: %s", body)
	}
}

func TestPublisherEnvelopeIncludesTraceparentFromContext(t *testing.T) {
	publisher := &recordingPublisher{}
	service := NewService(NewInMemoryRepository(), publisher, NewInMemoryConsumedEventLog(), func(prefix string) string {
		return prefix + "-00000000-0000-4000-8000-000000000001"
	}, func() time.Time { return time.Date(2026, 7, 5, 10, 1, 0, 0, time.UTC) })

	seedTicket(t, service, "ent-trace", "sb-trace", "ord-trace", "tvl-trace", "seg-trace")
	ctx := trace.ContextWithSpanContext(context.Background(), mustSpanContext(t))
	_, err := service.VerifyBoarding(ctx, VerifyBoardingCommand{
		EntitlementID:    "ent-trace",
		SegmentBookingID: "sb-trace",
		JourneyOrderID:   "ord-trace",
		TravelerID:       "tvl-trace",
		SegmentRef:       "seg-trace",
		Source:           domain.FulfillmentSourceGate,
		SourceEventID:    "gate-scan-trace",
		OccurredAt:       time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC),
	}, CommandMetadata{CorrelationID: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c0aa"})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got := publisher.envelopes[0].Traceparent; got != wantTraceparent {
		t.Fatalf("traceparent mismatch: %q", got)
	}
}

func TestSubscribedEventHandlerDeduplicatesByEventID(t *testing.T) {
	log := NewInMemoryConsumedEventLog()
	service := NewService(NewInMemoryRepository(), NoopPublisher{}, log, nil, nil)
	envelope := EventEnvelope{EventID: "evt-duplicate", EventType: "EntitlementIssued", Producer: "entitlement-ticketing", SchemaVersion: 1}
	if err := service.HandleSubscribedEvent(context.Background(), envelope); err != nil {
		t.Fatalf("first event failed: %v", err)
	}
	if err := service.HandleSubscribedEvent(context.Background(), envelope); err != nil {
		t.Fatalf("duplicate event should be ack-safe no-op: %v", err)
	}
	seen, err := log.AlreadyConsumed(context.Background(), envelope.EventID)
	if err != nil || !seen {
		t.Fatalf("event was not recorded as consumed: seen=%v err=%v", seen, err)
	}
}

func TestPublishFailureRetainsPendingEventsForRetry(t *testing.T) {
	repo := NewInMemoryRepository()
	publisher := &failingOncePublisher{err: errors.New("redis unavailable")}
	service := NewService(repo, publisher, NewInMemoryConsumedEventLog(), func(prefix string) string {
		return prefix + "-00000000-0000-7000-8000-000000000001"
	}, func() time.Time { return time.Date(2026, 7, 5, 10, 1, 0, 0, time.UTC) })
	seedTicket(t, service, "ent-retry1", "sb-retry1", "ord-retry1", "tvl-retry1", "seg-retry1")
	cmd := VerifyBoardingCommand{
		EntitlementID:    "ent-retry1",
		SegmentBookingID: "sb-retry1",
		JourneyOrderID:   "ord-retry1",
		TravelerID:       "tvl-retry1",
		SegmentRef:       "seg-retry1",
		Source:           domain.FulfillmentSourceGate,
		SourceEventID:    "gate-scan-retry",
		OccurredAt:       time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC),
	}
	_, err := service.VerifyBoarding(context.Background(), cmd, CommandMetadata{})
	if !errors.Is(err, ErrPublishFailed) {
		t.Fatalf("expected publish failure, got %v", err)
	}
	record, err := repo.FindByEntitlementSegment(context.Background(), cmd.EntitlementID, cmd.SegmentBookingID, cmd.SegmentRef)
	if err != nil {
		t.Fatalf("mutated record was not saved: %v", err)
	}
	if record.Status != domain.FulfillmentStatusBoarded {
		t.Fatalf("record mutation was not retained: %s", record.Status)
	}
	if got := len(record.PendingEvents()); got != 1 {
		t.Fatalf("expected pending event after failed publish, got %d", got)
	}

	_, err = service.VerifyBoarding(context.Background(), cmd, CommandMetadata{})
	if err != nil {
		t.Fatalf("retry should publish retained event: %v", err)
	}
	if len(publisher.envelopes) != 1 {
		t.Fatalf("expected retained event to be published once, got %d", len(publisher.envelopes))
	}
	record, err = repo.FindByEntitlementSegment(context.Background(), cmd.EntitlementID, cmd.SegmentBookingID, cmd.SegmentRef)
	if err != nil {
		t.Fatal(err)
	}
	if got := len(record.PendingEvents()); got != 0 {
		t.Fatalf("expected pending events cleared after successful retry, got %d", got)
	}
}

func TestVerifyBoardingRequiresPreparedTicketAndRejectsVoided(t *testing.T) {
	service := NewService(NewInMemoryRepository(), &recordingPublisher{}, NewInMemoryConsumedEventLog(), nil, nil)
	cmd := VerifyBoardingCommand{EntitlementID: "ent-missing1", SegmentBookingID: "sb-missing1", JourneyOrderID: "ord-missing1", TravelerID: "tvl-missing1", SegmentRef: "seg-missing1", Source: domain.FulfillmentSourceGate, SourceEventID: "scan-missing", OccurredAt: time.Now().UTC()}
	if _, err := service.VerifyBoarding(context.Background(), cmd, CommandMetadata{}); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expected not found for unprepared ticket, got %v", err)
	}
	seedTicket(t, service, "ent-void1", "sb-void1", "ord-void1", "tvl-void1", "seg-void1")
	voided := EventEnvelope{EventID: "evt-void", EventType: "EntitlementVoided", Producer: "entitlement-ticketing", SchemaVersion: 1, Payload: json.RawMessage(`{"entitlementId":"ent-void1","segmentBookingId":"sb-void1"}`)}
	if err := service.HandleSubscribedEvent(context.Background(), voided); err != nil {
		t.Fatal(err)
	}
	cmd = VerifyBoardingCommand{EntitlementID: "ent-void1", SegmentBookingID: "sb-void1", JourneyOrderID: "ord-void1", TravelerID: "tvl-void1", SegmentRef: "seg-void1", Source: domain.FulfillmentSourceGate, SourceEventID: "scan-void", OccurredAt: time.Now().UTC()}
	if _, err := service.VerifyBoarding(context.Background(), cmd, CommandMetadata{}); !errors.Is(err, ErrDomainRuleViolation) {
		t.Fatalf("expected domain violation for voided ticket, got %v", err)
	}
}

func TestVerifyBoardingRecoversMissingProjectionFromAuthoritativeEntitlement(t *testing.T) {
	repo := NewInMemoryRepository()
	publisher := &recordingPublisher{}
	reader := &stubEntitlementReader{snapshot: EntitlementSnapshot{
		EntitlementID:    "ent-recover1",
		SegmentBookingID: "sb-recover1",
		JourneyOrderID:   "ord-recover1",
		TravelerID:       "tvl-recover1",
		SegmentRef:       "seg-recover1",
		Status:           "ISSUED",
	}}
	service := NewService(repo, publisher, NewInMemoryConsumedEventLog(), func(prefix string) string {
		return prefix + "-00000000-0000-7000-8000-000000000201"
	}, func() time.Time { return time.Date(2026, 9, 25, 4, 5, 0, 0, time.UTC) }).WithEntitlementReader(reader)
	cmd := VerifyBoardingCommand{
		EntitlementID:    "ent-recover1",
		SegmentBookingID: "sb-recover1",
		JourneyOrderID:   "ord-recover1",
		TravelerID:       "tvl-recover1",
		SegmentRef:       "seg-recover1",
		Source:           domain.FulfillmentSourceGate,
		SourceEventID:    "gate-recover1",
		OccurredAt:       time.Date(2026, 9, 25, 4, 4, 0, 0, time.UTC),
	}

	result, err := service.VerifyBoarding(context.Background(), cmd, CommandMetadata{})
	if err != nil {
		t.Fatalf("verify boarding: %v", err)
	}
	if reader.calls != 1 {
		t.Fatalf("entitlement lookups=%d, want 1", reader.calls)
	}
	if result.Status != domain.FulfillmentStatusBoarded {
		t.Fatalf("status=%s, want BOARDED", result.Status)
	}
	record, err := repo.FindByEntitlementSegment(context.Background(), cmd.EntitlementID, cmd.SegmentBookingID, cmd.SegmentRef)
	if err != nil {
		t.Fatalf("find recovered record: %v", err)
	}
	if record.JourneyOrderID != cmd.JourneyOrderID || record.TravelerID != cmd.TravelerID || record.Status != domain.FulfillmentStatusBoarded {
		t.Fatalf("recovered record=%+v", record)
	}
	if len(publisher.envelopes) != 1 || publisher.envelopes[0].EventType != "BoardingVerified" {
		t.Fatalf("published envelopes=%#v", publisher.envelopes)
	}
}

func TestVerifyBoardingDoesNotFetchEntitlementWhenProjectionExists(t *testing.T) {
	reader := &stubEntitlementReader{err: errors.New("must not be called")}
	service := NewService(NewInMemoryRepository(), NoopPublisher{}, NewInMemoryConsumedEventLog(), nil, nil).WithEntitlementReader(reader)
	seedTicket(t, service, "ent-local1", "sb-local1", "ord-local1", "tvl-local1", "seg-local1")

	_, err := service.VerifyBoarding(context.Background(), VerifyBoardingCommand{
		EntitlementID: "ent-local1", SegmentBookingID: "sb-local1", JourneyOrderID: "ord-local1",
		TravelerID: "tvl-local1", SegmentRef: "seg-local1", Source: domain.FulfillmentSourceGate,
		SourceEventID: "gate-local1", OccurredAt: time.Date(2026, 9, 25, 4, 4, 0, 0, time.UTC),
	}, CommandMetadata{})
	if err != nil {
		t.Fatalf("verify boarding: %v", err)
	}
	if reader.calls != 0 {
		t.Fatalf("entitlement lookups=%d, want 0", reader.calls)
	}
}

func TestVerifyBoardingRejectsAuthoritativeEntitlementReferenceMismatch(t *testing.T) {
	base := EntitlementSnapshot{
		EntitlementID: "ent-match1", SegmentBookingID: "sb-match1", JourneyOrderID: "ord-match1",
		TravelerID: "tvl-match1", SegmentRef: "seg-match1", Status: "ISSUED",
	}
	cmd := VerifyBoardingCommand{
		EntitlementID: "ent-match1", SegmentBookingID: "sb-match1", JourneyOrderID: "ord-match1",
		TravelerID: "tvl-match1", SegmentRef: "seg-match1", Source: domain.FulfillmentSourceGate,
		SourceEventID: "gate-match1", OccurredAt: time.Date(2026, 9, 25, 4, 4, 0, 0, time.UTC),
	}
	tests := map[string]func(*EntitlementSnapshot){
		"entitlement id":     func(ticket *EntitlementSnapshot) { ticket.EntitlementID = "ent-other1" },
		"segment booking id": func(ticket *EntitlementSnapshot) { ticket.SegmentBookingID = "sb-other1" },
		"segment ref":        func(ticket *EntitlementSnapshot) { ticket.SegmentRef = "seg-other1" },
		"journey order id":   func(ticket *EntitlementSnapshot) { ticket.JourneyOrderID = "ord-other1" },
		"traveler id":        func(ticket *EntitlementSnapshot) { ticket.TravelerID = "tvl-other1" },
	}
	for name, mutate := range tests {
		t.Run(name, func(t *testing.T) {
			ticket := base
			mutate(&ticket)
			repo := NewInMemoryRepository()
			service := NewService(repo, NoopPublisher{}, NewInMemoryConsumedEventLog(), nil, nil).
				WithEntitlementReader(&stubEntitlementReader{snapshot: ticket})
			_, err := service.VerifyBoarding(context.Background(), cmd, CommandMetadata{})
			if !errors.Is(err, ErrDomainRuleViolation) {
				t.Fatalf("error=%v, want domain rule violation", err)
			}
			if _, findErr := repo.FindByEntitlementSegment(context.Background(), cmd.EntitlementID, cmd.SegmentBookingID, cmd.SegmentRef); !errors.Is(findErr, ErrNotFound) {
				t.Fatalf("mismatched entitlement created a record: %v", findErr)
			}
		})
	}
}

func TestVerifyBoardingRejectsNonBoardableAuthoritativeEntitlement(t *testing.T) {
	for _, status := range []string{"VOIDED", "SUSPENDED", "BOARDED", "NO_SHOW"} {
		t.Run(status, func(t *testing.T) {
			repo := NewInMemoryRepository()
			reader := &stubEntitlementReader{snapshot: EntitlementSnapshot{
				EntitlementID: "ent-status1", SegmentBookingID: "sb-status1", JourneyOrderID: "ord-status1",
				TravelerID: "tvl-status1", SegmentRef: "seg-status1", Status: status,
			}}
			service := NewService(repo, NoopPublisher{}, NewInMemoryConsumedEventLog(), nil, nil).WithEntitlementReader(reader)
			_, err := service.VerifyBoarding(context.Background(), VerifyBoardingCommand{
				EntitlementID: "ent-status1", SegmentBookingID: "sb-status1", JourneyOrderID: "ord-status1",
				TravelerID: "tvl-status1", SegmentRef: "seg-status1", Source: domain.FulfillmentSourceGate,
				SourceEventID: "gate-status1", OccurredAt: time.Date(2026, 9, 25, 4, 4, 0, 0, time.UTC),
			}, CommandMetadata{})
			if !errors.Is(err, ErrDomainRuleViolation) {
				t.Fatalf("error=%v, want domain rule violation", err)
			}
		})
	}
}

func TestVerifyBoardingSurfacesEntitlementLookupFailure(t *testing.T) {
	repo := NewInMemoryRepository()
	reader := &stubEntitlementReader{err: fmt.Errorf("%w: timeout", ErrDependencyUnavailable)}
	service := NewService(repo, NoopPublisher{}, NewInMemoryConsumedEventLog(), nil, nil).WithEntitlementReader(reader)
	_, err := service.VerifyBoarding(context.Background(), VerifyBoardingCommand{
		EntitlementID: "ent-down1", SegmentBookingID: "sb-down1", JourneyOrderID: "ord-down1",
		TravelerID: "tvl-down1", SegmentRef: "seg-down1", Source: domain.FulfillmentSourceGate,
		SourceEventID: "gate-down1", OccurredAt: time.Date(2026, 9, 25, 4, 4, 0, 0, time.UTC),
	}, CommandMetadata{})
	if !errors.Is(err, ErrDependencyUnavailable) {
		t.Fatalf("error=%v, want dependency unavailable", err)
	}
}

func TestCommandPathContinuesAfterRestartWithPersistedRecord(t *testing.T) {
	repo := NewInMemoryRepository()
	publisher := &recordingPublisher{}
	first := NewService(repo, publisher, NewInMemoryConsumedEventLog(), func(prefix string) string {
		return prefix + "-00000000-0000-7000-8000-000000000101"
	}, func() time.Time { return time.Date(2026, 7, 5, 10, 1, 0, 0, time.UTC) })
	seedTicket(t, first, "ent-restart1", "sb-restart1", "ord-restart1", "tvl-restart1", "seg-restart1")
	_, err := first.VerifyBoarding(context.Background(), VerifyBoardingCommand{EntitlementID: "ent-restart1", SegmentBookingID: "sb-restart1", JourneyOrderID: "ord-restart1", TravelerID: "tvl-restart1", SegmentRef: "seg-restart1", Source: domain.FulfillmentSourceGate, SourceEventID: "gate-restart", OccurredAt: time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)}, CommandMetadata{})
	if err != nil {
		t.Fatalf("boarding before restart: %v", err)
	}

	restarted := NewService(repo, publisher, NewInMemoryConsumedEventLog(), nil, nil)
	_, err = restarted.RecordFulfillmentCompleted(context.Background(), FulfillmentCompletedCommand{EntitlementID: "ent-restart1", SegmentBookingID: "sb-restart1", JourneyOrderID: "ord-restart1", TravelerID: "tvl-restart1", SegmentRef: "seg-restart1", CompletionSource: domain.CompletionSourceArrival, CompletedAt: time.Date(2026, 7, 5, 11, 0, 0, 0, time.UTC)}, CommandMetadata{})
	if err != nil {
		t.Fatalf("completion after restart: %v", err)
	}
	record, err := repo.FindByEntitlementSegment(context.Background(), "ent-restart1", "sb-restart1", "seg-restart1")
	if err != nil {
		t.Fatalf("find persisted record: %v", err)
	}
	if record.Status != domain.FulfillmentStatusCompleted {
		t.Fatalf("expected completed record after restart, got %s", record.Status)
	}
}

func TestNoShowCommandContinuesAfterRestartWithPersistedRecord(t *testing.T) {
	repo := NewInMemoryRepository()
	first := NewService(repo, NoopPublisher{}, NewInMemoryConsumedEventLog(), func(prefix string) string {
		return prefix + "-00000000-0000-7000-8000-000000000102"
	}, nil)
	seedTicket(t, first, "ent-restart2", "sb-restart2", "ord-restart2", "tvl-restart2", "seg-restart2")

	restarted := NewService(repo, NoopPublisher{}, NewInMemoryConsumedEventLog(), nil, func() time.Time { return time.Date(2026, 7, 5, 10, 1, 0, 0, time.UTC) })
	_, err := restarted.RecordNoShow(context.Background(), RecordNoShowCommand{EntitlementID: "ent-restart2", SegmentBookingID: "sb-restart2", JourneyOrderID: "ord-restart2", TravelerID: "tvl-restart2", SegmentRef: "seg-restart2", Reason: domain.NoShowReasonManualRecord}, CommandMetadata{})
	if err != nil {
		t.Fatalf("no-show after restart: %v", err)
	}
	record, err := repo.FindByEntitlementSegment(context.Background(), "ent-restart2", "sb-restart2", "seg-restart2")
	if err != nil {
		t.Fatalf("find persisted record: %v", err)
	}
	if record.Status != domain.FulfillmentStatusNoShow {
		t.Fatalf("expected no-show record after restart, got %s", record.Status)
	}
}

func TestRecordNoShowRecoversMissingProjectionFromAuthoritativeEntitlement(t *testing.T) {
	repo := NewInMemoryRepository()
	publisher := &recordingPublisher{}
	reader := &stubEntitlementReader{snapshot: EntitlementSnapshot{
		EntitlementID: "ent-no-show-recover1", SegmentBookingID: "sb-no-show-recover1",
		JourneyOrderID: "ord-no-show-recover1", TravelerID: "tvl-no-show-recover1",
		SegmentRef: "seg-no-show-recover1", Status: "ISSUED",
	}}
	service := NewService(repo, publisher, NewInMemoryConsumedEventLog(), func(prefix string) string {
		return prefix + "-00000000-0000-7000-8000-000000000202"
	}, func() time.Time { return time.Date(2026, 9, 25, 4, 22, 0, 0, time.UTC) }).WithEntitlementReader(reader)
	cmd := RecordNoShowCommand{
		EntitlementID: "ent-no-show-recover1", SegmentBookingID: "sb-no-show-recover1",
		JourneyOrderID: "ord-no-show-recover1", TravelerID: "tvl-no-show-recover1",
		SegmentRef: "seg-no-show-recover1", Reason: domain.NoShowReasonManualRecord,
	}

	result, err := service.RecordNoShow(context.Background(), cmd, CommandMetadata{})
	if err != nil {
		t.Fatalf("record no-show: %v", err)
	}
	if reader.calls != 1 {
		t.Fatalf("entitlement lookups=%d, want 1", reader.calls)
	}
	if result.Status != domain.FulfillmentStatusNoShow {
		t.Fatalf("status=%s, want NO_SHOW", result.Status)
	}
	record, err := repo.FindByEntitlementSegment(context.Background(), cmd.EntitlementID, cmd.SegmentBookingID, cmd.SegmentRef)
	if err != nil {
		t.Fatalf("find recovered no-show record: %v", err)
	}
	if record.JourneyOrderID != cmd.JourneyOrderID || record.TravelerID != cmd.TravelerID || record.Status != domain.FulfillmentStatusNoShow {
		t.Fatalf("recovered record=%+v", record)
	}
	if len(publisher.envelopes) != 1 || publisher.envelopes[0].EventType != "NoShowRecorded" {
		t.Fatalf("published envelopes=%#v", publisher.envelopes)
	}
}

func TestFulfillmentCompletedPublishesContractEvent(t *testing.T) {
	publisher := &recordingPublisher{}
	service := NewService(NewInMemoryRepository(), publisher, NewInMemoryConsumedEventLog(), nil, func() time.Time { return time.Date(2026, 7, 5, 10, 1, 0, 0, time.UTC) })
	seedTicket(t, service, "ent-complete1", "sb-complete1", "ord-complete1", "tvl-complete1", "seg-complete1")
	_, err := service.VerifyBoarding(context.Background(), VerifyBoardingCommand{EntitlementID: "ent-complete1", SegmentBookingID: "sb-complete1", JourneyOrderID: "ord-complete1", TravelerID: "tvl-complete1", SegmentRef: "seg-complete1", Source: domain.FulfillmentSourceGate, SourceEventID: "scan-complete", OccurredAt: time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)}, CommandMetadata{CorrelationID: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c0aa"})
	if err != nil {
		t.Fatalf("boarding: %v", err)
	}
	publisher.envelopes = nil
	completedAt := time.Date(2026, 7, 5, 11, 0, 0, 0, time.UTC)
	_, err = service.RecordFulfillmentCompleted(context.Background(), FulfillmentCompletedCommand{EntitlementID: "ent-complete1", SegmentBookingID: "sb-complete1", JourneyOrderID: "ord-complete1", TravelerID: "tvl-complete1", SegmentRef: "seg-complete1", CompletionSource: domain.CompletionSourceArrival, CompletedAt: completedAt}, CommandMetadata{CorrelationID: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c0aa"})
	if err != nil {
		t.Fatalf("complete: %v", err)
	}
	if len(publisher.envelopes) != 1 || publisher.envelopes[0].EventType != "FulfillmentCompleted" {
		t.Fatalf("unexpected envelopes: %#v", publisher.envelopes)
	}
	var payload map[string]any
	if err := json.Unmarshal(publisher.envelopes[0].Payload, &payload); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"fulfillmentRecordId", "entitlementId", "segmentBookingId", "journeyOrderId", "travelerId", "completedAt", "completionSource"} {
		if _, ok := payload[key]; !ok {
			t.Fatalf("missing %s in %#v", key, payload)
		}
	}
	if _, ok := payload["segmentRef"]; ok {
		t.Fatalf("FulfillmentCompleted payload must match contract exactly, got segmentRef")
	}
}

func TestConsumesAncillaryFulfillmentReadyAndFact(t *testing.T) {
	repo := NewInMemoryRepository()
	log := NewInMemoryConsumedEventLog()
	service := NewService(repo, NoopPublisher{}, log, nil, nil)
	ready := EventEnvelope{EventID: "evt-anc-ready", EventType: "AncillaryOrderItemFulfillmentReady", Producer: "ancillary-service", SchemaVersion: 1, Payload: json.RawMessage(`{"ancillaryOrderItemId":"aoi-ready1","journeyOrderId":"ord-ready1","travelerRef":"tvl-ready1","segmentRef":"seg-ready1","catalogSnapshot":{"catalogItemId":"aci-meal1","serviceType":"MEAL"},"providerRef":"voucher-123","entitlementRef":"ent-ready1","previousStatus":"CONFIRMED","status":"FULFILLMENT_READY","readyAt":"2026-07-05T10:00:00Z","aggregateVersion":3}`)}
	if err := service.HandleSubscribedEvent(context.Background(), ready); err != nil {
		t.Fatalf("ready event: %v", err)
	}
	handoff, err := repo.FindAncillaryHandoff(context.Background(), "aoi-ready1")
	if err != nil {
		t.Fatalf("find handoff: %v", err)
	}
	if handoff.Status != "FULFILLMENT_READY" || handoff.ProviderRef != "voucher-123" || handoff.ReadyAt == nil {
		t.Fatalf("unexpected ready handoff: %#v", handoff)
	}
	fact := EventEnvelope{EventID: "evt-anc-fact", EventType: "AncillaryFulfillmentFactRecorded", Producer: "ancillary-service", SchemaVersion: 1, Payload: json.RawMessage(`{"ancillaryOrderItemId":"aoi-ready1","journeyOrderId":"ord-ready1","travelerRef":"tvl-ready1","segmentRef":"seg-ready1","catalogItemId":"aci-meal1","serviceType":"MEAL","fulfillmentFact":{"fulfillmentFactId":"aff-fact1","factType":"MEAL_ISSUED","providerRef":"voucher-123","occurredAt":"2026-07-05T10:05:00Z","recordedAt":"2026-07-05T10:06:00Z","performedBy":"PROVIDER","idempotencyRef":"provider-event-1","compensable":false},"previousStatus":"FULFILLMENT_READY","status":"FULFILLED","aggregateVersion":4}`)}
	if err := service.HandleSubscribedEvent(context.Background(), fact); err != nil {
		t.Fatalf("fact event: %v", err)
	}
	if err := service.HandleSubscribedEvent(context.Background(), fact); err != nil {
		t.Fatalf("duplicate fact event: %v", err)
	}
	handoff, err = repo.FindAncillaryHandoff(context.Background(), "aoi-ready1")
	if err != nil {
		t.Fatalf("find handoff after fact: %v", err)
	}
	if handoff.Status != "FULFILLED" || len(handoff.Facts) != 1 || handoff.Facts[0].SourceEventID != "evt-anc-fact" {
		t.Fatalf("unexpected fact handoff: %#v", handoff)
	}
}

func TestConsumesDispatchRideLifecycle(t *testing.T) {
	repo := NewInMemoryRepository()
	service := NewService(repo, NoopPublisher{}, NewInMemoryConsumedEventLog(), nil, nil)
	arrived := EventEnvelope{EventID: "evt-ride-arrived", EventType: "DriverArrived", Producer: "dispatch", SchemaVersion: 1, Payload: json.RawMessage(`{"rideRequestId":"rrq-ride1","rideAssignmentId":"ras-ride1","riderAccountId":"acct-1","travelerRef":"tvl-ride1","pickupRef":"place-a","dropoffRef":"place-b","driverRef":"drv-1","vehicleRef":"veh-1","arrivedAt":"2026-07-05T10:00:00Z","status":"DRIVER_ARRIVED"}`)}
	if err := service.HandleSubscribedEvent(context.Background(), arrived); err != nil {
		t.Fatalf("arrived: %v", err)
	}
	ended := EventEnvelope{EventID: "evt-ride-ended", EventType: "RideEnded", Producer: "dispatch", SchemaVersion: 1, Payload: json.RawMessage(`{"rideRequestId":"rrq-ride1","rideAssignmentId":"ras-ride1","riderAccountId":"acct-1","travelerRef":"tvl-ride1","pickupRef":"place-a","dropoffRef":"place-b","driverRef":"drv-1","vehicleRef":"veh-1","startedAt":"2026-07-05T10:03:00Z","endedAt":"2026-07-05T10:30:00Z","finalFareRef":"fare-final-1","status":"COMPLETED"}`)}
	if err := service.HandleSubscribedEvent(context.Background(), ended); err != nil {
		t.Fatalf("ended: %v", err)
	}
	view, err := repo.FindRideExecution(context.Background(), "rrq-ride1")
	if err != nil {
		t.Fatalf("find ride execution: %v", err)
	}
	if view.Status != "COMPLETED" || view.DriverArrivedAt == nil || view.RideEndedAt == nil || view.FinalFareRef != "fare-final-1" || len(view.Evidence) != 2 {
		t.Fatalf("unexpected ride view: %#v", view)
	}
}

func TestAckSkipsUnexpectedExternalLifecycleStatusesWithWarn(t *testing.T) {
	repo := NewInMemoryRepository()
	service := NewService(repo, NoopPublisher{}, NewInMemoryConsumedEventLog(), nil, nil)
	var logs bytes.Buffer
	originalWriter := log.Writer()
	log.SetOutput(&logs)
	t.Cleanup(func() { log.SetOutput(originalWriter) })

	wrongDispatch := EventEnvelope{EventID: "evt-ride-bad-status", EventType: "DriverArrived", Producer: "dispatch", SchemaVersion: 1, Payload: json.RawMessage(`{"rideRequestId":"rrq-badstatus1","rideAssignmentId":"ras-badstatus1","riderAccountId":"acct-1","travelerRef":"tvl-badstatus1","pickupRef":"place-a","dropoffRef":"place-b","driverRef":"drv-1","vehicleRef":"veh-1","arrivedAt":"2026-07-05T10:00:00Z","status":"ASSIGNED"}`)}
	if err := service.HandleSubscribedEvent(context.Background(), wrongDispatch); err != nil {
		t.Fatalf("wrong dispatch status should be ack-skipped, got %v", err)
	}
	if _, err := repo.FindRideExecution(context.Background(), "rrq-badstatus1"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("wrong dispatch status mutated ride view: %v", err)
	}
	if got := logs.String(); !strings.Contains(got, "WARN") || !strings.Contains(got, "evt-ride-bad-status") || !strings.Contains(got, "ack-skip") {
		t.Fatalf("expected WARN ack-skip log for dispatch status, got %q", got)
	}

	ready := EventEnvelope{EventID: "evt-anc-bad-ready", EventType: "AncillaryOrderItemFulfillmentReady", Producer: "ancillary-service", SchemaVersion: 1, Payload: json.RawMessage(`{"ancillaryOrderItemId":"aoi-badstatus1","journeyOrderId":"ord-badstatus1","travelerRef":"tvl-badstatus1","segmentRef":"seg-badstatus1","catalogSnapshot":{"catalogItemId":"aci-meal1","serviceType":"MEAL"},"providerRef":"voucher-123","previousStatus":"CONFIRMED","status":"FULFILLMENT_READY","readyAt":"2026-07-05T10:00:00Z","aggregateVersion":3}`)}
	if err := service.HandleSubscribedEvent(context.Background(), ready); err != nil {
		t.Fatalf("ready event: %v", err)
	}
	wrongAncillaryFact := EventEnvelope{EventID: "evt-anc-bad-status", EventType: "AncillaryFulfillmentFactRecorded", Producer: "ancillary-service", SchemaVersion: 1, Payload: json.RawMessage(`{"ancillaryOrderItemId":"aoi-badstatus1","journeyOrderId":"ord-badstatus1","travelerRef":"tvl-badstatus1","segmentRef":"seg-badstatus1","catalogItemId":"aci-meal1","serviceType":"MEAL","fulfillmentFact":{"fulfillmentFactId":"aff-badstatus1","factType":"MEAL_ISSUED","providerRef":"voucher-123","occurredAt":"2026-07-05T10:05:00Z","recordedAt":"2026-07-05T10:06:00Z","performedBy":"PROVIDER","idempotencyRef":"provider-event-1","compensable":false},"previousStatus":"FULFILLMENT_READY","status":"SOMETHING_NEW","aggregateVersion":4}`)}
	if err := service.HandleSubscribedEvent(context.Background(), wrongAncillaryFact); err != nil {
		t.Fatalf("wrong ancillary status should be ack-skipped, got %v", err)
	}
	handoff, err := repo.FindAncillaryHandoff(context.Background(), "aoi-badstatus1")
	if err != nil {
		t.Fatalf("find handoff: %v", err)
	}
	if handoff.Status != "FULFILLMENT_READY" || len(handoff.Facts) != 0 {
		t.Fatalf("wrong ancillary status mutated handoff: %#v", handoff)
	}
	if got := logs.String(); !strings.Contains(got, "evt-anc-bad-status") {
		t.Fatalf("expected WARN ack-skip log for ancillary status, got %q", got)
	}
}

const wantTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

func mustSpanContext(t *testing.T) trace.SpanContext {
	t.Helper()
	traceID, err := trace.TraceIDFromHex("4bf92f3577b34da6a3ce929d0e0e4736")
	if err != nil {
		t.Fatal(err)
	}
	spanID, err := trace.SpanIDFromHex("00f067aa0ba902b7")
	if err != nil {
		t.Fatal(err)
	}
	spanContext := trace.NewSpanContext(trace.SpanContextConfig{TraceID: traceID, SpanID: spanID, TraceFlags: trace.FlagsSampled})
	if !spanContext.IsValid() {
		t.Fatal("invalid span context")
	}
	return spanContext
}

func TestVerifyBoardingRefusesAReaderTheEstateDoesNotCarry(t *testing.T) {
	repo := NewInMemoryRepository()
	publisher := &recordingPublisher{}
	service := NewService(repo, publisher, NewInMemoryConsumedEventLog(), nil, nil)
	seedTicket(t, service, "ent-reader1", "sb-reader1", "ord-reader1", "tvl-reader1", "seg-reader1")

	_, err := service.VerifyBoarding(context.Background(), VerifyBoardingCommand{
		EntitlementID:    "ent-reader1",
		SegmentBookingID: "sb-reader1",
		JourneyOrderID:   "ord-reader1",
		TravelerID:       "tvl-reader1",
		SegmentRef:       "seg-reader1",
		Source:           domain.FulfillmentSource("KIOSK"),
		SourceEventID:    "kiosk-scan-1",
		OccurredAt:       time.Date(2026, 9, 26, 12, 0, 0, 0, time.UTC),
	}, CommandMetadata{})
	if err == nil {
		t.Fatal("a reader the estate does not carry was accepted")
	}
	record, err := repo.FindByEntitlementSegment(context.Background(), "ent-reader1", "sb-reader1", "seg-reader1")
	if err != nil {
		t.Fatalf("find record: %v", err)
	}
	if record.Status != domain.FulfillmentStatusReady || record.BoardingFact != nil {
		t.Fatalf("the refused boarding reached the record: %s", record.Status)
	}
	if len(publisher.envelopes) != 0 {
		t.Fatalf("the refused boarding published %d event(s)", len(publisher.envelopes))
	}
}
