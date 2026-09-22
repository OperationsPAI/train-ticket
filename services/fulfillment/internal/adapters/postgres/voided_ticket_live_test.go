package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/trainticket/greenfield/platform/go-kit/ids"
	kitstorage "github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/fulfillment/internal/application"
	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

func TestVoidedTicketRemainsBlockedAfterRestartAndEventReordering(t *testing.T) {
	url, path := os.Getenv("TEST_DATABASE_URL"), os.Getenv("TEST_FULFILLMENT_VOID_EVENTS")
	if url == "" || path == "" {
		t.Skip("requires real PostgreSQL and issued/voided ticket events")
	}
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var events map[string]application.EventEnvelope
	if err := json.Unmarshal(body, &events); err != nil {
		t.Fatal(err)
	}
	var ticket struct {
		EntitlementID domain.EntitlementRef    `json:"entitlementId"`
		BookingID     domain.SegmentBookingRef `json:"segmentBookingId"`
		OrderID       domain.OrderRef          `json:"journeyOrderId"`
		TravelerID    domain.TravelerRef       `json:"travelerRef"`
		Segment       domain.SegmentRef        `json:"segmentRef"`
	}
	if err := json.Unmarshal(events["issued"].Payload, &ticket); err != nil {
		t.Fatal(err)
	}
	for _, ordering := range []string{"issued_first", "void_first"} {
		t.Run(ordering, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
			defer cancel()
			admin, err := pgxpool.New(ctx, url)
			if err != nil {
				t.Fatal(err)
			}
			defer admin.Close()
			schema := "fulfillment_void_" + time.Now().Format("20060102150405") + "_" + ordering
			if _, err := admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
				t.Fatal(err)
			}
			defer func() {
				if _, err := admin.Exec(context.Background(), "DROP SCHEMA "+schema+" CASCADE"); err != nil {
					t.Error(err)
				}
			}()
			config, err := pgxpool.ParseConfig(url)
			if err != nil {
				t.Fatal(err)
			}
			config.ConnConfig.RuntimeParams["search_path"] = schema
			pool, err := pgxpool.NewWithConfig(ctx, config)
			if err != nil {
				t.Fatal(err)
			}
			defer pool.Close()
			migrations, err := kitstorage.LoadMigrations(os.DirFS("../../../migrations"), ".")
			if err != nil {
				t.Fatal(err)
			}
			if err := kitstorage.NewMigrationRunner(pool).Run(ctx, migrations); err != nil {
				t.Fatal(err)
			}
			tx := NewTransactor(pool)
			repo := NewFulfillmentRepositoryWithProvider(tx)
			service := application.NewService(repo, NewOutboxPublisherWithProvider(tx), NewProcessedEventsWithProvider(tx), nil, nil).WithUnitOfWork(tx.Within)
			handle := func(event application.EventEnvelope) {
				t.Helper()
				if err := tx.Within(ctx, func(ctx context.Context) error { return service.HandleSubscribedEvent(ctx, event) }); err != nil {
					t.Fatal(err)
				}
			}
			if ordering == "issued_first" {
				handle(events["issued"])
			}
			handle(events["void"])
			service = application.NewService(repo, NewOutboxPublisherWithProvider(tx), NewProcessedEventsWithProvider(tx), nil, nil).WithUnitOfWork(tx.Within)
			handle(events["issued"])
			if _, err := pool.Exec(ctx, "DELETE FROM processed_events WHERE event_id=$1", events["issued"].EventID); err != nil {
				t.Fatal(err)
			}
			handle(events["issued"])
			_, err = service.VerifyBoarding(ctx, application.VerifyBoardingCommand{EntitlementID: ticket.EntitlementID, SegmentBookingID: ticket.BookingID, JourneyOrderID: ticket.OrderID, TravelerID: ticket.TravelerID, SegmentRef: ticket.Segment,
				Source: domain.FulfillmentSourceGate, SourceEventID: events["issued"].EventID, OccurredAt: time.Now().UTC()}, application.CommandMetadata{CorrelationID: ids.NewCorrelationID(), CausationID: ids.NewCommandID()})
			if !errors.Is(err, application.ErrDomainRuleViolation) {
				t.Fatalf("voided ticket boarding error=%v", err)
			}
			var count int
			if err := pool.QueryRow(ctx, "SELECT count(*) FROM outbox WHERE envelope->>'eventType'='BoardingVerified'").Scan(&count); err != nil {
				t.Fatal(err)
			}
			if count != 0 {
				t.Fatalf("boarding event count=%d", count)
			}
			handle(events["void"])
			if err := pool.QueryRow(ctx, "SELECT count(*) FROM voided_entitlements").Scan(&count); err != nil {
				t.Fatal(err)
			}
			if count != 1 {
				t.Fatalf("void fact count=%d", count)
			}
		})
	}
}
