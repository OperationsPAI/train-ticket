package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/trainticket/greenfield/platform/go-kit/ids"
	kitstorage "github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/fulfillment/internal/application"
	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

func TestPersistedFulfillmentRejectsStaleWritesAndSerializesBoarding(t *testing.T) {
	url, path := os.Getenv("TEST_DATABASE_URL"), os.Getenv("TEST_FULFILLMENT_RECORD_FILE")
	if url == "" || path == "" {
		t.Skip("requires PostgreSQL and a real fulfillment snapshot")
	}
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var source struct {
		ID      string          `json:"id"`
		Version int64           `json:"version"`
		Data    json.RawMessage `json:"data"`
	}
	if err := json.Unmarshal(body, &source); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	admin, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	schema := "fulfillment_concurrent_" + time.Now().Format("20060102150405000000")
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
	config.MaxConns = 8
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
	if _, err := pool.Exec(ctx, "INSERT INTO fulfillment_record_snapshots(id,version,data) VALUES ($1,$2,$3)", source.ID, source.Version, source.Data); err != nil {
		t.Fatal(err)
	}
	tx := NewTransactor(pool)
	repo := NewFulfillmentRepositoryWithProvider(tx)
	first, err := repo.FindByID(ctx, domain.FulfillmentRecordID(source.ID))
	if err != nil {
		t.Fatal(err)
	}
	stale, err := repo.FindByID(ctx, first.FulfillmentRecordID)
	if err != nil {
		t.Fatal(err)
	}
	if first.Status != domain.FulfillmentStatusReady {
		t.Fatalf("source state=%s", first.Status)
	}
	svc := application.NewService(repo, NewOutboxPublisherWithProvider(tx), NewProcessedEventsWithProvider(tx), nil, nil).WithUnitOfWork(tx.Within)
	var workers sync.WaitGroup
	results := make(chan error, 8)
	for index := 0; index < 8; index++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			_, err := svc.VerifyBoarding(ctx, application.VerifyBoardingCommand{
				EntitlementID: first.EntitlementID, SegmentBookingID: first.SegmentBookingID, JourneyOrderID: first.JourneyOrderID, TravelerID: first.TravelerID, SegmentRef: first.SegmentRef,
				Source: domain.FulfillmentSourceGate, SourceEventID: source.ID, OccurredAt: time.Now().UTC()}, application.CommandMetadata{CorrelationID: ids.NewCorrelationID(), CausationID: ids.NewCommandID()})
			results <- err
		}()
	}
	workers.Wait()
	close(results)
	for err := range results {
		if err != nil {
			t.Fatal(err)
		}
	}
	if err := stale.RecordNoShow(domain.NoShowReasonWindowExpired, time.Now().UTC()); err != nil {
		t.Fatal(err)
	}
	if err := repo.Save(ctx, stale); !errors.Is(err, application.ErrConflict) {
		t.Fatalf("stale write error=%v", err)
	}
	saved, err := repo.FindByID(ctx, first.FulfillmentRecordID)
	if err != nil {
		t.Fatal(err)
	}
	if saved.Status != domain.FulfillmentStatusBoarded || saved.BoardingFact == nil || saved.NoShowReason != nil {
		t.Fatalf("saved record=%+v", saved)
	}
	var count int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM outbox WHERE envelope->>'eventType'='BoardingVerified'").Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Fatalf("boarding event count=%d", count)
	}
}
