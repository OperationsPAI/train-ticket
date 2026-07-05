package messaging

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestNewEventEnvelopeCanonicalShape(t *testing.T) {
	envelope, err := NewEventEnvelope("ThingHappened", "test-producer", "0194f2e0-7b3e-7610-0284-5c26e8b0c123", map[string]string{"thingId": "thing-1"}, EnvelopeOptions{Now: time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC), CausationID: "0194f2e0-7b3e-7610-0284-5c26e8b0c124"})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(envelope.EventID, "evt-") || envelope.CorrelationID != "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c123" || envelope.CausationID != "cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c124" {
		t.Fatalf("unexpected ids: %#v", envelope)
	}
	body, err := json.Marshal(envelope)
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(body, &fields); err != nil {
		t.Fatal(err)
	}
	if len(fields) != 8 {
		t.Fatalf("envelope must have exactly 8 fields, got %d: %s", len(fields), body)
	}
	for _, name := range []string{"eventId", "eventType", "occurredAt", "correlationId", "causationId", "producer", "schemaVersion", "payload"} {
		if _, ok := fields[name]; !ok {
			t.Fatalf("missing envelope field %s in %s", name, body)
		}
	}
}

func TestNewEventEnvelopeOmitsOptionalCausationID(t *testing.T) {
	envelope, err := NewEventEnvelope("ThingHappened", "test-producer", "0194f2e0-7b3e-7610-0284-5c26e8b0c123", map[string]string{"thingId": "thing-1"}, EnvelopeOptions{Now: time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC)})
	if err != nil {
		t.Fatal(err)
	}
	if envelope.CausationID != "" {
		t.Fatalf("causationId should be absent, got %q", envelope.CausationID)
	}
	body, err := json.Marshal(envelope)
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(body, &fields); err != nil {
		t.Fatal(err)
	}
	if _, ok := fields["causationId"]; ok {
		t.Fatalf("causationId must be omitted when absent: %s", body)
	}
	if len(fields) != 7 {
		t.Fatalf("envelope without causationId must have 7 fields, got %d: %s", len(fields), body)
	}
}
