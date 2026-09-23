package storage

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	kitstorage "github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/application"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/domain"
)

func TestRecordedSeatAllocationSerializesConcurrentReservationsAndReplays(t *testing.T) {
	if os.Getenv("TEST_SEAT_ALLOCATION") == "" {
		t.Skip("recorded allocation and map required")
	}
	input, err := os.ReadFile(os.Getenv("TEST_SEAT_ALLOCATION"))
	if err != nil {
		t.Fatal(err)
	}
	var allocation domain.ContractSeatAllocation
	if err := json.Unmarshal(input, &allocation); err != nil {
		t.Fatal(err)
	}
	input, err = os.ReadFile(os.Getenv("TEST_SEAT_MAP"))
	if err != nil {
		t.Fatal(err)
	}
	var seatMap domain.ContractSeatMap
	if err := json.Unmarshal(input, &seatMap); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	admin, err := pgxpool.New(ctx, os.Getenv("TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	schema := fmt.Sprintf("seat_allocation_%d", time.Now().UnixNano())
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatal(err)
	}
	defer admin.Exec(context.Background(), "DROP SCHEMA "+schema+" CASCADE")
	config, err := pgxpool.ParseConfig(os.Getenv("TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	config.ConnConfig.RuntimeParams["search_path"] = schema
	config.MaxConns = 12
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
	repo := NewRepository(tx)
	if err := repo.SaveSeatMap(ctx, &seatMap); err != nil {
		t.Fatal(err)
	}
	svc := application.New(repo, NewOutboxPublisher(tx), tx.Within)
	class := ""
	for _, coach := range seatMap.Coaches {
		for _, unit := range coach.SeatUnits {
			if unit.SeatUnitRef == allocation.SeatRef.SeatUnitRef {
				class = coach.ClassRef
			}
		}
	}
	if class == "" {
		t.Fatal("recorded allocation must belong to map")
	}
	request := application.AllocateSeatRequest{SegmentBookingID: allocation.SegmentBookingID, JourneyOrderID: allocation.JourneyOrderID, TravelerRef: allocation.TravelerRef, SegmentRef: allocation.SegmentRef, ScheduledServiceRef: allocation.ScheduledServiceRef, ServiceDate: allocation.ServiceDate, CapacityHoldID: allocation.CapacityHoldID, CapacityUnitRef: allocation.CapacityUnitRef, Interval: allocation.Interval, ClassRef: class, IssuePurpose: "REPLACEMENT", ExpiresAt: allocation.ExpiresAt}
	results := make(chan *application.AllocateSeatResponse, 16)
	errors := make(chan error, 16)
	start := make(chan struct{})
	for index := 0; index < 16; index++ {
		go func() { <-start; result, err := svc.AllocateSeat(ctx, request); results <- result; errors <- err }()
	}
	close(start)
	var id string
	for index := 0; index < 16; index++ {
		result := <-results
		if err := <-errors; err != nil {
			t.Fatal(err)
		}
		if result == nil {
			t.Fatal("missing allocation")
		}
		if id == "" {
			id = result.SeatAllocationID
		}
		if result.SeatAllocationID != id {
			t.Fatal("replay created another allocation")
		}
	}
	var count int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM outbox WHERE envelope->>'eventType'='SeatAllocated'").Scan(&count); err != nil || count != 1 {
		t.Fatalf("events=%d error=%v", count, err)
	}
	changed := request
	changed.ClassRef = "BUSINESS_CLASS"
	if _, err := svc.AllocateSeat(ctx, changed); err == nil {
		t.Fatal("changed class accepted")
	}
	seen := map[string]bool{}
	for index := 0; index < 16; index++ {
		index := index
		go func() {
			command := request
			command.SegmentBookingID = fmt.Sprintf("%s-%d", request.SegmentBookingID, index)
			command.TravelerRef = fmt.Sprintf("%s-%d", request.TravelerRef, index)
			command.CapacityHoldID = fmt.Sprintf("%s-%d", request.CapacityHoldID, index)
			result, err := svc.AllocateSeat(ctx, command)
			results <- result
			errors <- err
		}()
	}
	for index := 0; index < 16; index++ {
		result := <-results
		if err := <-errors; err != nil {
			t.Fatal(err)
		}
		if result == nil || seen[result.SeatRef.SeatUnitRef] {
			t.Fatal("concurrent allocation assigned one seat twice")
		}
		seen[result.SeatRef.SeatUnitRef] = true
	}
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM seat_allocations").Scan(&count); err != nil || count != 17 {
		t.Fatalf("allocations=%d error=%v", count, err)
	}
}
