package storage

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
	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	kitstorage "github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/application"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/domain"
)

func TestConcurrentSeatAllocationEventReplaysConvergeWithoutConflicts(t *testing.T) {
	databaseURL := os.Getenv("TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	pool := newSeatAssignmentTestPool(t, ctx, databaseURL, "seat_event_replay")
	defer pool.Close()

	tx := NewTransactor(pool)
	repo := NewRepository(tx)
	svc := application.New(repo, NewOutboxPublisher(tx), tx.Within)
	now := time.Now().UTC().Truncate(time.Second)
	confirmed := newEventTestAllocation("salloc-confirm-replay", "hold-confirm-replay", now)
	if err := repo.SaveSeatAllocation(ctx, confirmed); err != nil {
		t.Fatal(err)
	}

	runConcurrentEvents(t, 32, func(index int) error {
		payload, err := json.Marshal(map[string]any{
			"seatAllocationId": confirmed.SeatAllocationID,
			"entitlementId":    fmt.Sprintf("ent-%d", index),
		})
		if err != nil {
			return err
		}
		return svc.HandleSubscribedEvent(ctx, kitmsg.EventEnvelope{
			EventID: ids.NewEventID(), EventType: "EntitlementIssued",
			CorrelationID: ids.NewCorrelationID(), Payload: payload,
		})
	})
	assertAllocationState(t, ctx, pool, repo, confirmed.SeatAllocationID, domain.AllocationStatusConfirmed, 2)
	assertOutboxEventCount(t, ctx, pool, confirmed.SeatAllocationID, "SeatAllocationConfirmed", 1)

	released := newEventTestAllocation("salloc-release-replay", "hold-release-replay", now)
	if err := repo.SaveSeatAllocation(ctx, released); err != nil {
		t.Fatal(err)
	}
	runConcurrentEvents(t, 32, func(index int) error {
		payload, err := json.Marshal(map[string]any{
			"holdId":          released.CapacityHoldID,
			"capacityUnitRef": released.CapacityUnitRef,
			"interval":        released.Interval,
			"releasedAt":      now.Add(time.Duration(index) * time.Millisecond).Format(time.RFC3339Nano),
		})
		if err != nil {
			return err
		}
		return svc.HandleSubscribedEvent(ctx, kitmsg.EventEnvelope{
			EventID: ids.NewEventID(), EventType: "CapacityReleased",
			CorrelationID: ids.NewCorrelationID(), Payload: payload,
		})
	})
	assertAllocationState(t, ctx, pool, repo, released.SeatAllocationID, domain.AllocationStatusReleased, 2)
	assertOutboxEventCount(t, ctx, pool, released.SeatAllocationID, "SeatAllocationReleased", 1)

	var allocations int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM seat_allocations").Scan(&allocations); err != nil {
		t.Fatal(err)
	}
	if allocations != 2 {
		t.Fatalf("event replay created duplicate allocations: got %d want 2", allocations)
	}
}

func newEventTestAllocation(id, holdID string, now time.Time) *domain.ContractSeatAllocation {
	return &domain.ContractSeatAllocation{
		SeatAllocationID: id, SegmentBookingID: "sb-" + id, JourneyOrderID: "ord-" + id,
		TravelerRef: "tvl-" + id, SegmentRef: "seg-1", ScheduledServiceRef: "ss-1",
		ServiceDate: "2026-09-25", CapacityHoldID: holdID, CapacityUnitRef: "cap-standard",
		Interval: domain.StationInterval{FromSeq: 1, ToSeq: 3},
		SeatRef: domain.SeatRef{SeatAllocationID: id, SeatMapID: "smap-1", SeatMapVersion: 2,
			SeatUnitRef: "seat-" + id, AllocationType: domain.AllocationTypeSeat, DisplayLabel: "01车 01A"},
		Status: domain.AllocationStatusAllocated, CreatedAt: now, ExpiresAt: now.Add(time.Hour),
	}
}

func runConcurrentEvents(t *testing.T, count int, handle func(int) error) {
	t.Helper()
	start := make(chan struct{})
	errs := make(chan error, count)
	var workers sync.WaitGroup
	for index := 0; index < count; index++ {
		workers.Add(1)
		go func(index int) {
			defer workers.Done()
			<-start
			errs <- handle(index)
		}(index)
	}
	close(start)
	workers.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatal(err)
		}
	}
}

func assertAllocationState(t *testing.T, ctx context.Context, pool *pgxpool.Pool, repo *Repository, id, status string, version int64) {
	t.Helper()
	allocation, _, err := repo.GetSeatAllocation(ctx, id)
	if err != nil {
		t.Fatal(err)
	}
	if allocation.Status != status {
		t.Fatalf("allocation %s status=%s want %s", id, allocation.Status, status)
	}
	var storedVersion int64
	if err := pool.QueryRow(ctx, "SELECT version FROM seat_allocations WHERE id=$1", id).Scan(&storedVersion); err != nil {
		t.Fatal(err)
	}
	if storedVersion != version {
		t.Fatalf("allocation %s version=%d want %d; replay wrote the aggregate more than once", id, storedVersion, version)
	}
}

func assertOutboxEventCount(t *testing.T, ctx context.Context, pool *pgxpool.Pool, allocationID, eventType string, want int) {
	t.Helper()
	var count int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM outbox WHERE envelope->>'eventType'=$1 AND envelope->'payload'->>'seatAllocationId'=$2", eventType, allocationID).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != want {
		t.Fatalf("%s events for %s=%d want %d", eventType, allocationID, count, want)
	}
}

func newSeatAssignmentTestPool(t *testing.T, ctx context.Context, databaseURL, prefix string) *pgxpool.Pool {
	t.Helper()
	admin, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(admin.Close)
	schema := fmt.Sprintf("%s_%d", prefix, time.Now().UnixNano())
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if _, err := admin.Exec(context.Background(), "DROP SCHEMA "+schema+" CASCADE"); err != nil {
			t.Error(err)
		}
	})
	config, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	config.ConnConfig.RuntimeParams["search_path"] = schema
	config.MaxConns = 40
	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		t.Fatal(err)
	}
	migrations, err := kitstorage.LoadMigrations(os.DirFS("../../../migrations"), ".")
	if err != nil {
		pool.Close()
		t.Fatal(err)
	}
	if err := kitstorage.NewMigrationRunner(pool).Run(ctx, migrations); err != nil {
		pool.Close()
		t.Fatal(err)
	}
	return pool
}

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
