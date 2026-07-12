package messaging

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

type fakeRedisStream struct {
	entries []fakeStreamEntry
}

type fakeStreamEntry struct {
	stream string
	values map[string]any
}

func (f *fakeRedisStream) XAdd(ctx context.Context, args *redis.XAddArgs) *redis.StringCmd {
	cmd := redis.NewStringCmd(ctx)
	f.entries = append(f.entries, fakeStreamEntry{stream: args.Stream, values: args.Values.(map[string]any)})
	cmd.SetVal("1-0")
	return cmd
}

func TestRedisPublisherWrapsEnvelopeAsSingleStreamField(t *testing.T) {
	stream := &fakeRedisStream{}
	publisher := NewPublisher(stream)
	envelope := domain.NewEventEnvelope("PlaceUpdated", time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC), "corr-1", "cmd-1", domain.ProducerPlaceNetwork, domain.PlaceUpdatedEvent{PlaceID: "plc-test"})

	if err := publisher.Publish(context.Background(), envelope); err != nil {
		t.Fatalf("publish failed: %v", err)
	}
	if len(stream.entries) != 1 {
		t.Fatalf("expected one stream entry, got %d", len(stream.entries))
	}
	entry := stream.entries[0]
	if entry.stream != StreamPlaceNetwork {
		t.Fatalf("unexpected stream: %s", entry.stream)
	}
	if len(entry.values) != 1 {
		t.Fatalf("expected single field, got %#v", entry.values)
	}
	raw, ok := entry.values["envelope"].(string)
	if !ok || raw == "" {
		t.Fatalf("missing envelope field: %#v", entry.values)
	}
	var decoded domain.EventEnvelope
	if err := json.Unmarshal([]byte(raw), &decoded); err != nil {
		t.Fatalf("envelope is not JSON: %v", err)
	}
	if decoded.EventID != envelope.EventID || decoded.EventType != "PlaceUpdated" || decoded.Producer != domain.ProducerPlaceNetwork || decoded.SchemaVersion != 1 {
		t.Fatalf("unexpected envelope: %#v", decoded)
	}
}
