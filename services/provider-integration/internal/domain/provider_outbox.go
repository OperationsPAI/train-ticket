package domain

import (
	"fmt"
	"strings"
	"time"
)

// OutboxStatus tracks the lifecycle of a mapped event ready for publication.
type OutboxStatus string

const (
	OutboxEnqueued       OutboxStatus = "ENQUEUED"
	OutboxPublished      OutboxStatus = "PUBLISHED"
	OutboxDeliveryFailed OutboxStatus = "DELIVERY_FAILED"
)

// ProviderOutbox is the aggregate root for publishing mapped provider events
// to the platform. It ensures same sourceLogId + mappedEventType is idempotent.
type ProviderOutbox struct {
	OutboxID         string
	SourceLogID      string
	SourceType       string
	MappedEventType  string
	MappedPayload    string
	CorrelationID    string
	IdempotencyKey   string
	Status           OutboxStatus
	PublishedAt      *time.Time
	DeliveryAttempts int
	LastError        string
	CreatedAt        time.Time
}

// NewProviderOutbox creates a validated ProviderOutbox entry.
func NewProviderOutbox(outboxID string, sourceLogID string, sourceType string, mappedEventType string, mappedPayload string, correlationID string, idempotencyKey string) (ProviderOutbox, error) {
	outbox := ProviderOutbox{
		OutboxID:        strings.TrimSpace(outboxID),
		SourceLogID:     strings.TrimSpace(sourceLogID),
		SourceType:      strings.TrimSpace(sourceType),
		MappedEventType: strings.TrimSpace(mappedEventType),
		MappedPayload:   mappedPayload,
		CorrelationID:   strings.TrimSpace(correlationID),
		IdempotencyKey:  strings.TrimSpace(idempotencyKey),
		Status:          OutboxEnqueued,
		CreatedAt:       time.Now().UTC(),
	}
	if err := outbox.Validate(); err != nil {
		return ProviderOutbox{}, err
	}
	return outbox, nil
}

func (o ProviderOutbox) Validate() error {
	if strings.TrimSpace(o.OutboxID) == "" {
		return fmt.Errorf("outbox id is required")
	}
	if strings.TrimSpace(o.SourceLogID) == "" {
		return fmt.Errorf("source log id is required")
	}
	if strings.TrimSpace(o.MappedEventType) == "" {
		return fmt.Errorf("mapped event type is required")
	}
	if strings.TrimSpace(o.MappedPayload) == "" {
		return fmt.Errorf("mapped payload is required")
	}
	if !validOutboxStatus(o.Status) {
		return fmt.Errorf("unsupported outbox status: %q", o.Status)
	}
	return nil
}

func (o *ProviderOutbox) MarkPublished() error {
	if o.Status != OutboxEnqueued && o.Status != OutboxDeliveryFailed {
		return fmt.Errorf("cannot mark published from status %q", o.Status)
	}
	now := time.Now().UTC()
	o.Status = OutboxPublished
	o.PublishedAt = &now
	return nil
}

func (o *ProviderOutbox) MarkDeliveryFailed(err error) {
	o.Status = OutboxDeliveryFailed
	o.DeliveryAttempts++
	o.LastError = err.Error()
}

func validOutboxStatus(value OutboxStatus) bool {
	switch value {
	case OutboxEnqueued, OutboxPublished, OutboxDeliveryFailed:
		return true
	default:
		return false
	}
}
