package http

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	kitstorage "github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/service-plan/internal/adapters/postgres"
	"github.com/trainticket/greenfield/services/service-plan/internal/application"
)

func TestRecordedServiceSegmentIsReadableWithoutChangingSupply(t *testing.T) {
	if os.Getenv("TEST_SERVICE_SEGMENT_SOURCE") == "" || os.Getenv("TEST_DATABASE_URL") == "" {
		t.Skip("requires recorded service segment and PostgreSQL")
	}
	var source struct {
		Segment application.ServiceSegment   `json:"segment"`
		Service application.ScheduledService `json:"service"`
	}
	input, err := os.ReadFile(os.Getenv("TEST_SERVICE_SEGMENT_SOURCE"))
	if err != nil {
		t.Fatal(err)
	}
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
	schema := fmt.Sprintf("segment_read_%d", time.Now().UnixNano())
	if _, err = admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatal(err)
	}
	defer func() {
		if _, err := admin.Exec(context.Background(), "DROP SCHEMA "+schema+" CASCADE"); err != nil {
			t.Error(err)
		}
	}()
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
	migrations, err := kitstorage.LoadMigrations(os.DirFS("../../migrations"), ".")
	if err != nil {
		t.Fatal(err)
	}
	if err = kitstorage.NewMigrationRunner(pool).Run(ctx, migrations); err != nil {
		t.Fatal(err)
	}
	tx := postgres.NewTransactor(pool)
	repo := postgres.NewRepositoryWithProvider(tx)
	if err = repo.SaveScheduledService(ctx, source.Service); err != nil {
		t.Fatal(err)
	}
	if err = repo.SaveServiceSegment(ctx, source.Segment); err != nil {
		t.Fatal(err)
	}
	service := application.NewService(postgres.NewOutboxPublisherWithProvider(tx)).WithRepository(repo).WithUnitOfWork(tx.Within)
	server := httptest.NewServer(RouterWithServiceAndIdempotency(service, kitstorage.NewIdempotencyStore(pool)))
	defer server.Close()
	response, err := http.Get(server.URL + "/api/v1/service-segments/" + source.Segment.SegmentRef)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(response.Body)
		t.Fatalf("status=%d body=%s", response.StatusCode, body)
	}
	var actual application.ServiceSegment
	if err = json.NewDecoder(response.Body).Decode(&actual); err != nil {
		t.Fatal(err)
	}
	if actual.SegmentRef != source.Segment.SegmentRef || actual.ScheduledServiceRef != source.Service.ScheduledServiceRef || !actual.DepartureTime.Equal(source.Segment.DepartureTime) || actual.OriginStopRef != source.Segment.OriginStopRef || actual.DestinationStopRef != source.Segment.DestinationStopRef {
		t.Fatalf("segment differs: %+v", actual)
	}
	missing, err := http.Get(server.URL + "/api/v1/service-segments/" + source.Service.ScheduledServiceRef)
	if err != nil {
		t.Fatal(err)
	}
	defer missing.Body.Close()
	if missing.StatusCode != http.StatusNotFound {
		t.Fatalf("missing segment returned %d", missing.StatusCode)
	}
	var count int
	if err = pool.QueryRow(ctx, "SELECT count(*) FROM outbox").Scan(&count); err != nil || count != 0 {
		t.Fatalf("read produced events: %d %v", count, err)
	}
}
