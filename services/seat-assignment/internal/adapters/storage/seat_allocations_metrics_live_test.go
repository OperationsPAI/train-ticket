package storage

import (
	"context"
	"encoding/json"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/adapters/observability"
)

// The seat allocation gauge against a real seat_allocations table.
//
// The in-package test proves the fold is a pure function of durable rows. This
// one proves the rows really are durable: allocations are written, a second
// reader sees the same level, which is the restart case the contract requires.
func TestSeatAllocationLevelIsReconstructedFromDurableRows(t *testing.T) {
	databaseURL := os.Getenv("TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	pool := newSeatAssignmentTestPool(t, ctx, databaseURL, "seat_allocation_metrics")
	defer pool.Close()

	now := time.Now().UTC()
	insertSeatAllocation(t, ctx, pool, map[string]any{
		"seatAllocationId":  "salloc-live-1",
		"segmentBookingId":  "sb-live-1",
		"requestedClassRef": "SECOND_CLASS",
		"occupancyClassRef": "SECOND_CLASS",
		"status":            "ALLOCATED",
		"seatRef":           map[string]any{"seatAllocationId": "salloc-live-1", "allocationType": "SEAT", "seatUnitRef": "unit-1", "coachNo": "07", "seatNo": "12A"},
		"createdAt":         now,
	})
	// A standing substitution: the traveler bought SECOND_CLASS and the unit
	// delivered is standing. This is the pair the conservation check reads.
	insertSeatAllocation(t, ctx, pool, map[string]any{
		"seatAllocationId":  "salloc-live-2",
		"segmentBookingId":  "sb-live-2",
		"requestedClassRef": "SECOND_CLASS",
		"occupancyClassRef": "STANDING",
		"status":            "STANDING",
		"seatRef":           map[string]any{"seatAllocationId": "salloc-live-2", "allocationType": "STANDING"},
		"createdAt":         now,
	})

	repository := NewRepository(NewTransactor(pool))
	first, err := repository.LoadSeatAllocationsForMetrics(ctx)
	if err != nil {
		t.Fatalf("load allocations: %v", err)
	}
	// A second reader over the same durable rows is a restart.
	second, err := repository.LoadSeatAllocationsForMetrics(ctx)
	if err != nil {
		t.Fatalf("reload allocations: %v", err)
	}

	before := observability.BuildSeatAllocationRows(first)
	after := observability.BuildSeatAllocationRows(second)
	if len(before) != len(after) {
		t.Fatalf("a restart changed the series set: %#v vs %#v", before, after)
	}
	for i := range before {
		if before[i] != after[i] {
			t.Fatalf("restart changed series %d: %#v vs %#v", i, before[i], after[i])
		}
	}
	if !hasUnits(before, observability.AllocationStateAllocated, "second_class", "seat", 1) {
		t.Fatalf("the physical allocation was not reconstructed: %#v", before)
	}
	if !hasUnits(before, observability.AllocationStateAllocated, "second_class", "standing", 1) {
		t.Fatalf("the standing substitution was not reconstructed: %#v", before)
	}
}

func insertSeatAllocation(t *testing.T, ctx context.Context, pool *pgxpool.Pool, document map[string]any) {
	t.Helper()
	encoded, err := json.Marshal(document)
	if err != nil {
		t.Fatalf("encode allocation: %v", err)
	}
	if _, err := pool.Exec(ctx, "INSERT INTO seat_allocations(id, version, data) VALUES ($1, 1, $2)", document["seatAllocationId"], encoded); err != nil {
		t.Fatalf("insert allocation: %v", err)
	}
}

func hasUnits(rows []observability.SeatAllocationRow, state, travelClass, seatMode string, units int64) bool {
	for _, row := range rows {
		if row.State == state && row.TravelClass == travelClass && row.SeatMode == seatMode && row.Units == units {
			return true
		}
	}
	return false
}
