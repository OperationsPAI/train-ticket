package application

import (
	"context"
	"testing"
	"time"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

func TestReportSegmentStatusPublishesDeterministicDelayEvent(t *testing.T) {
	repo := NewInMemoryRepository()
	publisher := &capturePublisher{}
	service := NewService(repo, publisher, NewInMemoryConsumedEventLog(), func(prefix string) string { return prefix + "-0194f2e0-7b3e-7610-8284-5c26e8b0c125" }, nil).WithSegmentStatusRepository(repo)
	estimated := time.Date(2026, 7, 5, 10, 45, 0, 0, time.UTC)
	observed := time.Date(2026, 7, 5, 10, 0, 0, 0, time.UTC)
	cmd := ReportSegmentStatusCommand{CommandID: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c126", SegmentRef: "seg-delay1", ScheduledServiceRef: "svc-100", ServiceDate: "2026-07-05", Status: domain.SegmentOperationalStatusDelay, EstimatedArrivalAt: &estimated, ObservedAt: observed, SourceSystem: domain.SegmentStatusSourceSystemOps}

	first, err := service.ReportSegmentStatus(context.Background(), cmd, CommandMetadata{CorrelationID: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c127", CausationID: cmd.CommandID})
	if err != nil {
		t.Fatalf("ReportSegmentStatus failed: %v", err)
	}
	second, err := service.ReportSegmentStatus(context.Background(), cmd, CommandMetadata{CorrelationID: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c127", CausationID: cmd.CommandID})
	if err != nil {
		t.Fatalf("idempotent ReportSegmentStatus failed: %v", err)
	}
	if first.SegmentStatusRecordID != second.SegmentStatusRecordID {
		t.Fatalf("expected idempotent result, got %s then %s", first.SegmentStatusRecordID, second.SegmentStatusRecordID)
	}
	if len(publisher.events) != 1 {
		t.Fatalf("expected one published event, got %d", len(publisher.events))
	}
	envelope := publisher.events[0]
	if envelope.EventType != "SegmentDelayed" || envelope.EventID != "evt-62d6c0aa-d8e3-7da0-a5bb-1f2f18483210" {
		t.Fatalf("unexpected envelope: %#v", envelope)
	}
}

type capturePublisher struct{ events []EventEnvelope }

func (p *capturePublisher) Publish(_ context.Context, envelope EventEnvelope) error {
	p.events = append(p.events, envelope)
	return nil
}
