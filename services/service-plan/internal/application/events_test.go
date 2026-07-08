package application

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"go.opentelemetry.io/otel/trace"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
)

type recordingPublisher struct {
	envelopes []EventEnvelope
	failures  int
}

func (p *recordingPublisher) Publish(_ context.Context, envelope EventEnvelope) error {
	if p.failures > 0 {
		p.failures--
		return errors.New("publish unavailable")
	}
	p.envelopes = append(p.envelopes, envelope)
	return nil
}

type memoryRepository struct {
	services map[string]ScheduledService
	segments map[string]ServiceSegment
}

func newMemoryRepository() *memoryRepository {
	return &memoryRepository{services: map[string]ScheduledService{}, segments: map[string]ServiceSegment{}}
}
func (r *memoryRepository) SaveScheduledService(_ context.Context, service ScheduledService) error {
	r.services[service.ScheduledServiceRef] = service
	return nil
}
func (r *memoryRepository) FindScheduledService(_ context.Context, ref string) (ScheduledService, error) {
	service, ok := r.services[ref]
	if !ok {
		return ScheduledService{}, ErrNotFound
	}
	return service, nil
}
func (r *memoryRepository) ListScheduledServices(_ context.Context, query ListScheduledServicesQuery) (PaginatedScheduledServices, error) {
	items := make([]ScheduledService, 0, len(r.services))
	for _, service := range r.services {
		if query.CarrierID == "" || service.CarrierID == query.CarrierID {
			items = append(items, service)
		}
	}
	return PaginatedScheduledServices{Items: items, Total: len(items), Limit: query.Limit, Offset: query.Offset}, nil
}
func (r *memoryRepository) SaveServiceSegment(_ context.Context, segment ServiceSegment) error {
	r.segments[segment.SegmentRef] = segment
	return nil
}

type failingRepository struct{ err error }

func (r failingRepository) SaveScheduledService(context.Context, ScheduledService) error {
	return r.err
}
func (r failingRepository) FindScheduledService(context.Context, string) (ScheduledService, error) {
	return ScheduledService{}, r.err
}
func (r failingRepository) ListScheduledServices(context.Context, ListScheduledServicesQuery) (PaginatedScheduledServices, error) {
	return PaginatedScheduledServices{}, r.err
}
func (r failingRepository) SaveServiceSegment(context.Context, ServiceSegment) error { return r.err }

func TestPublisherReceivesCorrectServicePlanEnvelope(t *testing.T) {
	publisher := &recordingPublisher{}
	service := NewService(publisher)
	_, err := service.CreateScheduledService(context.Background(), CreateScheduledServiceCommand{
		CarrierID:         "car-0194f2e0-7b3e-7610-0284-5c26e8b0c001",
		ServiceNumber:     "G1234",
		DepartureTime:     time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:       time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
		OriginNodeID:      "node-a",
		DestinationNodeID: "node-b",
		CorrelationID:     "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444",
		CausationID:       "cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c555",
	})
	if err != nil {
		t.Fatalf("create scheduled service: %v", err)
	}
	if len(publisher.envelopes) != 1 {
		t.Fatalf("expected one envelope, got %d", len(publisher.envelopes))
	}
	envelope := publisher.envelopes[0]
	if envelope.Producer != ProducerServicePlan || envelope.EventType != "ServicePlanPublished" || envelope.SchemaVersion != 1 {
		t.Fatalf("unexpected envelope headers: %#v", envelope)
	}
	assertPrefixedUUID(t, envelope.EventID, "evt")
	assertPrefixedUUID(t, envelope.CorrelationID, "corr")
	assertPrefixedUUID(t, envelope.CausationID, "cmd")
	if envelope.OccurredAt.IsZero() {
		t.Fatalf("missing occurredAt: %#v", envelope)
	}
	body, err := json.Marshal(envelope)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(body), `"traceparent"`) {
		t.Fatalf("traceparent must be omitted without span context: %s", body)
	}
	var payload map[string]any
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		t.Fatalf("payload is not json: %v", err)
	}
	if payload["serviceNumber"] != "G1234" || payload["status"] != "ACTIVE" {
		t.Fatalf("unexpected payload: %#v", payload)
	}
}

func TestPublisherEnvelopeIncludesTraceparentFromContext(t *testing.T) {
	publisher := &recordingPublisher{}
	service := NewService(publisher)
	ctx := trace.ContextWithSpanContext(context.Background(), mustSpanContext(t))
	_, err := service.CreateScheduledService(ctx, CreateScheduledServiceCommand{
		CarrierID:         "car-0194f2e0-7b3e-7610-0284-5c26e8b0c001",
		ServiceNumber:     "G1234",
		DepartureTime:     time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:       time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
		OriginNodeID:      "node-a",
		DestinationNodeID: "node-b",
		CorrelationID:     "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444",
	})
	if err != nil {
		t.Fatalf("create scheduled service: %v", err)
	}
	if got := publisher.envelopes[0].Traceparent; got != wantTraceparent {
		t.Fatalf("traceparent mismatch: %q", got)
	}
}

func TestDeduplicatingHandlerSkipsDuplicateEventID(t *testing.T) {
	store := NewInMemoryDedupStore()
	calls := 0
	handler := DeduplicatingHandler(store, func(context.Context, EventEnvelope) error {
		calls++
		return nil
	})
	envelope := EventEnvelope{EventID: "evt-0194f2e0-7b3e-7610-0284-5c26e8b0c301", EventType: "Any", OccurredAt: time.Now().UTC(), CorrelationID: "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c401", CausationID: "cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c501", Producer: ProducerServicePlan, SchemaVersion: 1, Payload: json.RawMessage(`{}`)}
	if err := handler(context.Background(), envelope); err != nil {
		t.Fatalf("first handle: %v", err)
	}
	if err := handler(context.Background(), envelope); err != nil {
		t.Fatalf("duplicate handle: %v", err)
	}
	if calls != 1 {
		t.Fatalf("expected one handler call, got %d", calls)
	}
}

func TestEnvelopeIDShapesAndCausationIndependentOfIdempotencyKey(t *testing.T) {
	publisher := &recordingPublisher{}
	service := NewService(publisher)
	_, err := service.CreateScheduledService(context.Background(), CreateScheduledServiceCommand{
		CarrierID:         "car-0194f2e0-7b3e-7610-0284-5c26e8b0c001",
		ServiceNumber:     "G1234",
		DepartureTime:     time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:       time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
		OriginNodeID:      "node-a",
		DestinationNodeID: "node-b",
		CorrelationID:     "not-canonical",
	})
	if err != nil {
		t.Fatalf("create scheduled service: %v", err)
	}
	if len(publisher.envelopes) != 1 {
		t.Fatalf("expected one envelope, got %d", len(publisher.envelopes))
	}
	envelope := publisher.envelopes[0]
	assertPrefixedUUID(t, envelope.EventID, "evt")
	assertPrefixedUUID(t, envelope.CorrelationID, "corr")
	if envelope.CausationID != "" {
		assertPrefixedUUID(t, envelope.CausationID, "cmd")
	}
	if envelope.CausationID == "0194f2e0-7b3e-7610-0284-5c26e8b0c102" || envelope.CausationID == "cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c102" {
		t.Fatalf("causationId must not be derived from idempotency key")
	}
}

func TestDomainRuleViolationDoesNotPublish(t *testing.T) {
	publisher := &recordingPublisher{}
	service := NewService(publisher)
	_, err := service.CreateScheduledService(context.Background(), CreateScheduledServiceCommand{
		ServiceRef:        "ss-0194f2e0-7b3e-7610-0284-5c26e8b0c201",
		CarrierID:         "car-0194f2e0-7b3e-7610-0284-5c26e8b0c001",
		ServiceNumber:     "G1234",
		DepartureTime:     time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:       time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
		OriginNodeID:      "node-a",
		DestinationNodeID: "node-b",
	})
	if err != nil {
		t.Fatalf("create scheduled service: %v", err)
	}
	publishedBeforeRejectedChange := len(publisher.envelopes)
	_, err = service.CreateServiceSegment(context.Background(), CreateServiceSegmentCommand{
		ScheduledServiceRef: "ss-0194f2e0-7b3e-7610-0284-5c26e8b0c201",
		OriginStopRef:       "node-a",
		DestinationStopRef:  "node-a",
		DepartureTime:       time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:         time.Date(2026, 7, 5, 11, 30, 0, 0, time.UTC),
	})
	if err == nil {
		t.Fatal("expected domain rule violation")
	}
	if len(publisher.envelopes) != publishedBeforeRejectedChange {
		t.Fatalf("expected no events for rejected state change, got %d new events", len(publisher.envelopes)-publishedBeforeRejectedChange)
	}
}

func assertPrefixedUUID(t *testing.T, value, prefix string) {
	t.Helper()
	if !ids.ValidPrefixedUUIDv7(value, prefix) {
		t.Fatalf("%s is not a canonical %s-prefixed UUID", value, prefix)
	}
}

func TestInvalidCarrierIDRejectedBeforePublish(t *testing.T) {
	publisher := &recordingPublisher{}
	service := NewService(publisher)
	_, err := service.CreateScheduledService(context.Background(), CreateScheduledServiceCommand{
		CarrierID:         "car-invalid",
		ServiceNumber:     "G1234",
		DepartureTime:     time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:       time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
		OriginNodeID:      "node-a",
		DestinationNodeID: "node-b",
	})
	if !errors.Is(err, ErrValidation) {
		t.Fatalf("expected validation error for invalid carrierId, got %v", err)
	}
	if len(publisher.envelopes) != 0 {
		t.Fatalf("expected no events for invalid carrierId, got %d", len(publisher.envelopes))
	}
}

func TestRepositoryReadErrorsPropagateWithoutMemoryFallback(t *testing.T) {
	boom := errors.New("postgres unavailable")
	service := NewService(NoopPublisher{}).WithRepository(failingRepository{err: boom})
	_, err := service.GetScheduledService(context.Background(), "ss-0194f2e0-7b3e-7610-0284-5c26e8b0c701")
	if !errors.Is(err, boom) {
		t.Fatalf("expected get storage error, got %v", err)
	}
	_, err = service.ListScheduledServices(context.Background(), ListScheduledServicesQuery{})
	if !errors.Is(err, boom) {
		t.Fatalf("expected list storage error, got %v", err)
	}
}

func TestCreateServiceSegmentAfterRestartUsesPersistedService(t *testing.T) {
	repository := newMemoryRepository()
	first := NewService(NoopPublisher{}).WithRepository(repository)
	_, err := first.CreateScheduledService(context.Background(), CreateScheduledServiceCommand{
		ServiceRef:        "ss-0194f2e0-7b3e-7610-0284-5c26e8b0c801",
		CarrierID:         "car-0194f2e0-7b3e-7610-0284-5c26e8b0c001",
		ServiceNumber:     "G1234",
		DepartureTime:     time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:       time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
		OriginNodeID:      "node-a",
		DestinationNodeID: "node-b",
	})
	if err != nil {
		t.Fatalf("create service before restart: %v", err)
	}

	restarted := NewService(NoopPublisher{}).WithRepository(repository)
	result, err := restarted.CreateServiceSegment(context.Background(), CreateServiceSegmentCommand{
		ScheduledServiceRef: "ss-0194f2e0-7b3e-7610-0284-5c26e8b0c801",
		OriginStopRef:       "node-a",
		DestinationStopRef:  "node-b",
		DepartureTime:       time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:         time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
	})
	if err != nil {
		t.Fatalf("create segment after restart: %v", err)
	}
	if result.ScheduledServiceRef != "ss-0194f2e0-7b3e-7610-0284-5c26e8b0c801" || result.SegmentRef == "" {
		t.Fatalf("unexpected segment result: %#v", result)
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
