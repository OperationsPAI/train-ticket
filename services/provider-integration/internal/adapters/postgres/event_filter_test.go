package postgres

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/trainticket/greenfield/platform/go-kit/ids"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/provider-integration/internal/application"
)

func TestOnlyReservationEventsArePersisted(t *testing.T) {
	databaseURL := os.Getenv("TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL is required")
	}
	ctx := context.Background()
	admin, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	schema := fmt.Sprintf("provider_events_%d", time.Now().UnixNano())
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatal(err)
	}
	defer func() {
		if _, err := admin.Exec(ctx, "DROP SCHEMA "+schema+" CASCADE"); err != nil {
			t.Error(err)
		}
	}()
	config, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	config.ConnConfig.RuntimeParams["search_path"] = schema
	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	migrations, err := storage.LoadMigrations(os.DirFS("../../../migrations"), ".")
	if err != nil {
		t.Fatal(err)
	}
	if err := storage.NewMigrationRunner(pool).Run(ctx, migrations); err != nil {
		t.Fatal(err)
	}
	tx := NewTransactor(pool)
	service := NewReservationServiceWithProvider(tx, NewOutboxPublisherWithProvider(tx))
	handler := DeduplicatingHandler(NewProcessedEventsWithProvider(tx), application.NewInboundEventHandler(service))
	for index := 0; index < 100; index++ {
		if err := handler(ctx, application.EventEnvelope{EventID: ids.NewEventID(), EventType: "BookingSagaStepSucceeded", Producer: "booking-orchestration"}); err != nil {
			t.Fatal(err)
		}
	}
	var count int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM processed_events").Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 0 {
		t.Fatalf("unhandled events persisted: %d", count)
	}
	body, err := json.Marshal(map[string]any{
		"segmentBookingId": ids.NewPrefixed("sb"), "journeyOrderId": ids.NewPrefixed("ord"),
		"segmentRef": ids.NewPrefixed("seg"), "travelerRef": ids.NewPrefixed("tvl"), "idempotencyKey": ids.NewUUIDv7(),
	})
	if err != nil {
		t.Fatal(err)
	}
	event := application.EventEnvelope{EventID: ids.NewEventID(), EventType: "SegmentReservationRequested", Producer: "booking-orchestration", CorrelationID: ids.NewCorrelationID(), Payload: body}
	var workers sync.WaitGroup
	errors := make(chan error, 8)
	for attempt := 0; attempt < 8; attempt++ {
		workers.Add(1)
		go func() { defer workers.Done(); errors <- handler(ctx, event) }()
	}
	workers.Wait()
	close(errors)
	for err := range errors {
		if err != nil {
			t.Fatal(err)
		}
	}
	for _, table := range []string{"processed_events", "provider_reservation_snapshots", "outbox"} {
		if err := pool.QueryRow(ctx, "SELECT count(*) FROM "+table).Scan(&count); err != nil {
			t.Fatal(err)
		}
		if count != 1 {
			t.Fatalf("%s rows=%d want=1", table, count)
		}
	}
}
