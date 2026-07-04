package domain

import (
	"errors"
	"strings"
	"testing"
)

func TestNewProviderOutboxValidates(t *testing.T) {
	outbox, err := NewProviderOutbox(
		"outbox-001",
		"log-001",
		"provider-request-log",
		"SegmentReservationConfirmed",
		`{"ref":"CNF-001"}`,
		"corr-001",
		"idem-001",
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if outbox.Status != OutboxEnqueued {
		t.Fatalf("expected ENQUEUED, got %s", outbox.Status)
	}
	if outbox.MappedEventType != "SegmentReservationConfirmed" {
		t.Fatalf("unexpected event type: %s", outbox.MappedEventType)
	}
}

func TestNewProviderOutboxRejectsMissingFields(t *testing.T) {
	_, err := NewProviderOutbox("", "log-1", "src", "Event", `{}`, "corr", "idem")
	if err == nil || !strings.Contains(err.Error(), "outbox id is required") {
		t.Fatalf("expected outbox id error, got %v", err)
	}

	_, err = NewProviderOutbox("outbox-1", "", "src", "Event", `{}`, "corr", "idem")
	if err == nil || !strings.Contains(err.Error(), "source log id is required") {
		t.Fatalf("expected source log id error, got %v", err)
	}

	_, err = NewProviderOutbox("outbox-1", "log-1", "src", "", `{}`, "corr", "idem")
	if err == nil || !strings.Contains(err.Error(), "mapped event type is required") {
		t.Fatalf("expected event type error, got %v", err)
	}

	_, err = NewProviderOutbox("outbox-1", "log-1", "src", "Event", "", "corr", "idem")
	if err == nil || !strings.Contains(err.Error(), "mapped payload is required") {
		t.Fatalf("expected payload error, got %v", err)
	}
}

func TestProviderOutboxPublishLifecycle(t *testing.T) {
	outbox, _ := NewProviderOutbox("outbox-002", "log-002", "src", "Event", `{}`, "corr", "idem")

	if err := outbox.MarkPublished(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if outbox.Status != OutboxPublished {
		t.Fatalf("expected PUBLISHED, got %s", outbox.Status)
	}
	if outbox.PublishedAt == nil {
		t.Fatal("expected published at to be set")
	}
}

func TestProviderOutboxDeliveryFailedRetry(t *testing.T) {
	outbox, _ := NewProviderOutbox("outbox-003", "log-003", "src", "Event", `{}`, "corr", "idem")

	outbox.MarkDeliveryFailed(errors.New("broker unavailable"))
	if outbox.Status != OutboxDeliveryFailed {
		t.Fatalf("expected DELIVERY_FAILED, got %s", outbox.Status)
	}
	if outbox.DeliveryAttempts != 1 {
		t.Fatalf("expected 1 delivery attempt, got %d", outbox.DeliveryAttempts)
	}
	if outbox.LastError != "broker unavailable" {
		t.Fatalf("unexpected last error: %s", outbox.LastError)
	}

	if err := outbox.MarkPublished(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if outbox.Status != OutboxPublished {
		t.Fatalf("expected PUBLISHED after retry, got %s", outbox.Status)
	}
}
