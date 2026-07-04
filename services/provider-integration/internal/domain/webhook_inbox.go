package domain

import (
	"fmt"
	"strings"
	"time"
)

// WebhookInboxStatus tracks the lifecycle of a received webhook event.
type WebhookInboxStatus string

const (
	WebhookReceived         WebhookInboxStatus = "RECEIVED"
	WebhookVerified         WebhookInboxStatus = "VERIFIED"
	WebhookRejected         WebhookInboxStatus = "REJECTED"
	WebhookDuplicateIgnored WebhookInboxStatus = "DUPLICATE_IGNORED"
	WebhookMapped           WebhookInboxStatus = "MAPPED"
	WebhookPublished        WebhookInboxStatus = "PUBLISHED"
	WebhookPublishFailed    WebhookInboxStatus = "PUBLISH_FAILED"
)

// WebhookInbox is the aggregate root for an incoming webhook from an external
// provider. Webhooks must be signature-verified before processing. The same
// providerId + eventId is idempotent.
type WebhookInbox struct {
	InboxID          string
	ProviderID       ProviderID
	EventID          string
	Signature        string
	RawPayload       string
	SchemaVersion    string
	MappingVersion   string
	Status           WebhookInboxStatus
	VerifiedAt       *time.Time
	MappedEventType  string
	MappedPayload    string
	RejectionReason  string
	ReceivedAt       time.Time
	PublishedAt      *time.Time
}

// NewWebhookInbox creates a validated WebhookInbox aggregate.
func NewWebhookInbox(inboxID string, providerID ProviderID, eventID string, signature string, rawPayload string, schemaVersion string) (WebhookInbox, error) {
	inbox := WebhookInbox{
		InboxID:       strings.TrimSpace(inboxID),
		ProviderID:    ProviderID(strings.TrimSpace(string(providerID))),
		EventID:       strings.TrimSpace(eventID),
		Signature:     strings.TrimSpace(signature),
		RawPayload:    rawPayload,
		SchemaVersion: strings.TrimSpace(schemaVersion),
		Status:        WebhookReceived,
		ReceivedAt:    time.Now().UTC(),
	}
	if err := inbox.Validate(); err != nil {
		return WebhookInbox{}, err
	}
	return inbox, nil
}

func (w WebhookInbox) Validate() error {
	if strings.TrimSpace(w.InboxID) == "" {
		return fmt.Errorf("inbox id is required")
	}
	if strings.TrimSpace(string(w.ProviderID)) == "" {
		return fmt.Errorf("provider id is required")
	}
	if strings.TrimSpace(w.EventID) == "" {
		return fmt.Errorf("event id is required")
	}
	if !validWebhookInboxStatus(w.Status) {
		return fmt.Errorf("unsupported webhook inbox status: %q", w.Status)
	}
	return nil
}

func (w *WebhookInbox) Verify() error {
	if w.Status != WebhookReceived {
		return fmt.Errorf("cannot verify webhook from status %q", w.Status)
	}
	now := time.Now().UTC()
	w.Status = WebhookVerified
	w.VerifiedAt = &now
	return nil
}

func (w *WebhookInbox) Reject(reason string) error {
	if w.Status != WebhookReceived {
		return fmt.Errorf("cannot reject webhook from status %q", w.Status)
	}
	w.Status = WebhookRejected
	w.RejectionReason = reason
	return nil
}

func (w *WebhookInbox) MarkDuplicate() error {
	if w.Status != WebhookVerified {
		return fmt.Errorf("cannot mark duplicate from status %q", w.Status)
	}
	w.Status = WebhookDuplicateIgnored
	return nil
}

func (w *WebhookInbox) MapEvent(mappedEventType string, mappedPayload string, mappingVersion string) error {
	if w.Status != WebhookVerified {
		return fmt.Errorf("cannot map webhook from status %q", w.Status)
	}
	w.Status = WebhookMapped
	w.MappedEventType = mappedEventType
	w.MappedPayload = mappedPayload
	w.MappingVersion = mappingVersion
	return nil
}

func (w *WebhookInbox) MarkPublished() error {
	if w.Status != WebhookMapped && w.Status != WebhookPublishFailed {
		return fmt.Errorf("cannot mark published from status %q", w.Status)
	}
	now := time.Now().UTC()
	w.Status = WebhookPublished
	w.PublishedAt = &now
	return nil
}

func (w *WebhookInbox) MarkPublishFailed(err error) error {
	if w.Status != WebhookMapped {
		return fmt.Errorf("cannot mark publish failed from status %q", w.Status)
	}
	w.Status = WebhookPublishFailed
	w.RejectionReason = err.Error()
	return nil
}

func validWebhookInboxStatus(value WebhookInboxStatus) bool {
	switch value {
	case WebhookReceived, WebhookVerified, WebhookRejected, WebhookDuplicateIgnored,
		WebhookMapped, WebhookPublished, WebhookPublishFailed:
		return true
	default:
		return false
	}
}
