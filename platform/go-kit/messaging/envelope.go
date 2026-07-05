package messaging

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
)

const SchemaVersion = 1

type EventEnvelope struct {
	EventID       string          `json:"eventId"`
	EventType     string          `json:"eventType"`
	OccurredAt    time.Time       `json:"occurredAt"`
	CorrelationID string          `json:"correlationId"`
	CausationID   string          `json:"causationId,omitempty"`
	Producer      string          `json:"producer"`
	SchemaVersion int             `json:"schemaVersion"`
	Payload       json.RawMessage `json:"payload"`
}

type EnvelopeOptions struct {
	Now         time.Time
	CausationID string
}

func NewEventEnvelope(eventType, producer, correlationID string, payload any, options ...EnvelopeOptions) (EventEnvelope, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return EventEnvelope{}, fmt.Errorf("marshal event payload: %w", err)
	}
	now := time.Now().UTC()
	causationID := ""
	if len(options) > 0 {
		if !options[0].Now.IsZero() {
			now = options[0].Now.UTC()
		}
		causationID = options[0].CausationID
	}
	envelope := EventEnvelope{EventID: ids.NewEventID(), EventType: strings.TrimSpace(eventType), OccurredAt: now, CorrelationID: ids.CanonicalCorrelationID(correlationID), Producer: strings.TrimSpace(producer), SchemaVersion: SchemaVersion, Payload: body}
	if strings.TrimSpace(causationID) != "" {
		envelope.CausationID = ids.CanonicalCausationID(causationID)
	}
	return envelope, envelope.Validate()
}

func (e EventEnvelope) Validate() error {
	if !ids.ValidPrefixedUUIDv7(e.EventID, "evt") {
		return fmt.Errorf("eventId must be evt-prefixed UUID v7")
	}
	if strings.TrimSpace(e.EventType) == "" {
		return fmt.Errorf("eventType is required")
	}
	if e.OccurredAt.IsZero() {
		return fmt.Errorf("occurredAt is required")
	}
	if e.OccurredAt.Location() != time.UTC {
		e.OccurredAt = e.OccurredAt.UTC()
	}
	if !ids.ValidPrefixedUUIDv7(e.CorrelationID, "corr") {
		return fmt.Errorf("correlationId must be corr-prefixed UUID v7")
	}
	if strings.TrimSpace(e.CausationID) != "" && !ids.ValidPrefixedUUIDv7(e.CausationID, "cmd") && !ids.ValidPrefixedUUIDv7(e.CausationID, "evt") {
		return fmt.Errorf("causationId must be cmd- or evt-prefixed UUID v7")
	}
	if strings.TrimSpace(e.Producer) == "" {
		return fmt.Errorf("producer is required")
	}
	if e.SchemaVersion != SchemaVersion {
		return fmt.Errorf("unsupported schemaVersion: %d", e.SchemaVersion)
	}
	if len(e.Payload) == 0 || !json.Valid(e.Payload) {
		return fmt.Errorf("payload must be valid json")
	}
	return nil
}

type Publisher interface {
	Publish(ctx context.Context, envelope EventEnvelope) error
}

func PublishAfterCommit(ctx context.Context, publisher Publisher, envelopes []EventEnvelope) error {
	for _, e := range envelopes {
		if err := publisher.Publish(ctx, e); err != nil {
			return err
		}
	}
	return nil
}
