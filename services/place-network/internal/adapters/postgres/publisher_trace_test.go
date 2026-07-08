package postgres

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"go.opentelemetry.io/otel/trace"

	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

type captureOutboxDB struct {
	envelope json.RawMessage
}

func (d *captureOutboxDB) Exec(_ context.Context, _ string, args ...any) (pgconn.CommandTag, error) {
	if len(args) >= 3 {
		d.envelope, _ = json.Marshal(args[2])
	}
	return pgconn.NewCommandTag("INSERT 1"), nil
}
func (d *captureOutboxDB) Query(context.Context, string, ...any) (pgx.Rows, error) { return nil, nil }
func (d *captureOutboxDB) QueryRow(context.Context, string, ...any) pgx.Row        { return nil }

func TestOutboxPublisherOmitsTraceparentWithoutSpanContext(t *testing.T) {
	db := &captureOutboxDB{}
	envelope := domain.NewEventEnvelope("PlaceRegistered", time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC), "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c123", "", domain.ProducerPlaceNetwork, domain.PlaceUpdatedEvent{PlaceID: "plc-test"})

	if err := NewOutboxPublisher(db).Publish(context.Background(), envelope); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(db.envelope), `"traceparent"`) {
		t.Fatalf("traceparent must be omitted without span context: %s", db.envelope)
	}
}

func TestOutboxPublisherInjectsTraceparentFromContext(t *testing.T) {
	db := &captureOutboxDB{}
	envelope := domain.NewEventEnvelope("PlaceRegistered", time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC), "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c123", "", domain.ProducerPlaceNetwork, domain.PlaceUpdatedEvent{PlaceID: "plc-test"})
	ctx := trace.ContextWithSpanContext(context.Background(), mustSpanContext(t))

	if err := NewOutboxPublisher(db).Publish(ctx, envelope); err != nil {
		t.Fatal(err)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(db.envelope, &fields); err != nil {
		t.Fatal(err)
	}
	if got := strings.Trim(string(fields["traceparent"]), `"`); got != wantTraceparent {
		t.Fatalf("traceparent mismatch: %q in %s", got, db.envelope)
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
