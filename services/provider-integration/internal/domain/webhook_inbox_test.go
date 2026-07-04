package domain

import (
	"errors"
	"strings"
	"testing"
)

func TestNewWebhookInboxValidates(t *testing.T) {
	inbox, err := NewWebhookInbox(
		"inbox-001",
		"cr-rail",
		"evt-001",
		"sig-abc-123",
		`{"event":"reservation.confirmed"}`,
		"1.0",
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if inbox.Status != WebhookReceived {
		t.Fatalf("expected RECEIVED, got %s", inbox.Status)
	}
	if inbox.ProviderID != "cr-rail" {
		t.Fatalf("unexpected provider id: %s", inbox.ProviderID)
	}
}

func TestNewWebhookInboxRejectsMissingFields(t *testing.T) {
	_, err := NewWebhookInbox("", "cr", "evt-1", "sig", "{}", "1.0")
	if err == nil || !strings.Contains(err.Error(), "inbox id is required") {
		t.Fatalf("expected inbox id error, got %v", err)
	}

	_, err = NewWebhookInbox("inbox-1", "", "evt-1", "sig", "{}", "1.0")
	if err == nil || !strings.Contains(err.Error(), "provider id is required") {
		t.Fatalf("expected provider id error, got %v", err)
	}

	_, err = NewWebhookInbox("inbox-1", "cr", "", "sig", "{}", "1.0")
	if err == nil || !strings.Contains(err.Error(), "event id is required") {
		t.Fatalf("expected event id error, got %v", err)
	}
}

func TestWebhookInboxLifecycle(t *testing.T) {
	inbox, _ := NewWebhookInbox("inbox-002", "cr", "evt-002", "sig", `{"event":"test"}`, "1.0")

	if err := inbox.Verify(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if inbox.Status != WebhookVerified {
		t.Fatalf("expected VERIFIED, got %s", inbox.Status)
	}
	if inbox.VerifiedAt == nil {
		t.Fatal("expected verified at to be set")
	}

	if err := inbox.MapEvent("SegmentReservationConfirmed", `{"ref":"CNF-001"}`, "1.0"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if inbox.Status != WebhookMapped {
		t.Fatalf("expected MAPPED, got %s", inbox.Status)
	}
	if inbox.MappedEventType != "SegmentReservationConfirmed" {
		t.Fatalf("unexpected mapped event type: %s", inbox.MappedEventType)
	}

	if err := inbox.MarkPublished(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if inbox.Status != WebhookPublished {
		t.Fatalf("expected PUBLISHED, got %s", inbox.Status)
	}
}

func TestWebhookInboxRejectAndDuplicate(t *testing.T) {
	inbox, _ := NewWebhookInbox("inbox-003", "cr", "evt-003", "sig", `{}`, "1.0")

	if err := inbox.Reject("invalid signature"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if inbox.Status != WebhookRejected {
		t.Fatalf("expected REJECTED, got %s", inbox.Status)
	}
	if inbox.RejectionReason != "invalid signature" {
		t.Fatalf("unexpected rejection reason: %s", inbox.RejectionReason)
	}

	inbox2, _ := NewWebhookInbox("inbox-004", "cr", "evt-004", "sig", `{}`, "1.0")
	inbox2.Verify()
	if err := inbox2.MarkDuplicate(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if inbox2.Status != WebhookDuplicateIgnored {
		t.Fatalf("expected DUPLICATE_IGNORED, got %s", inbox2.Status)
	}
}

func TestWebhookInboxPublishFailed(t *testing.T) {
	inbox, _ := NewWebhookInbox("inbox-005", "cr", "evt-005", "sig", `{}`, "1.0")
	inbox.Verify()
	inbox.MapEvent("TestEvent", `{}`, "1.0")

	if err := inbox.MarkPublishFailed(errors.New("connection refused")); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if inbox.Status != WebhookPublishFailed {
		t.Fatalf("expected PUBLISH_FAILED, got %s", inbox.Status)
	}

	if err := inbox.MarkPublished(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if inbox.Status != WebhookPublished {
		t.Fatalf("expected PUBLISHED after retry, got %s", inbox.Status)
	}
}
