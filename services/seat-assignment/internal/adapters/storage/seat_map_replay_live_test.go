package storage

import (
	"context"
	"encoding/json"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/application"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/domain"
	"os"
	"testing"
	"time"
)

func TestRecordedSeatMapConcurrentCreationAndPublish(t *testing.T) {
	if os.Getenv("TEST_SEAT_MAP") == "" {
		t.Skip("recorded map required")
	}
	input, err := os.ReadFile(os.Getenv("TEST_SEAT_MAP"))
	if err != nil {
		t.Fatal(err)
	}
	var source domain.ContractSeatMap
	if err = json.Unmarshal(input, &source); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	admin, err := pgxpool.New(ctx, os.Getenv("TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	schema := "seat_replay_" + uuid.New().String()[:8]
	if _, err = admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatal(err)
	}
	defer admin.Exec(context.Background(), "DROP SCHEMA "+schema+" CASCADE")
	config, err := pgxpool.ParseConfig(os.Getenv("TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	config.ConnConfig.RuntimeParams["search_path"] = schema
	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	migration, err := os.ReadFile("../../../migrations/001_seat_assignment.sql")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = pool.Exec(ctx, string(migration)); err != nil {
		t.Fatal(err)
	}
	tx := NewTransactor(pool)
	svc := application.New(NewRepository(tx), NewOutboxPublisher(tx), tx.Within)
	request := application.CreateSeatMapRequest{ScheduledServiceRef: source.ScheduledServiceRef, ServiceDate: source.ServiceDate, CompositionVersion: source.CompositionVersion, CompositionSeed: source.CompositionSeed, MappingVersion: source.MappingVersion, OperatorRef: "loadgen"}
	results := make(chan error, 16)
	for index := 0; index < 16; index++ {
		go func() {
			created, err := svc.CreateSeatMap(ctx, request)
			if err == nil && created.SeatMapID != source.SeatMapID {
				err = os.ErrInvalid
			}
			results <- err
		}()
	}
	for index := 0; index < 16; index++ {
		if err := <-results; err != nil {
			t.Fatal(err)
		}
	}
	for index := 0; index < 16; index++ {
		go func() {
			_, err := svc.PublishSeatMap(ctx, source.SeatMapID, application.PublishSeatMapRequest{ExpectedSeatMapVersion: 1, PublishReason: "READY", OperatorRef: "loadgen"})
			results <- err
		}()
	}
	for index := 0; index < 16; index++ {
		if err := <-results; err != nil {
			t.Fatal(err)
		}
	}
	created, err := svc.CreateSeatMap(ctx, request)
	if err != nil || created.Status != "PUBLISHED" {
		t.Fatalf("replay map=%v error=%v", created, err)
	}
	var count int
	if err = pool.QueryRow(ctx, "SELECT count(*) FROM outbox").Scan(&count); err != nil || count != 2 {
		t.Fatalf("expected2events got%d error=%v", count, err)
	}
}
