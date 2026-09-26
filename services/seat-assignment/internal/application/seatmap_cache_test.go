package application

import (
	"context"
	"testing"

	"github.com/trainticket/greenfield/services/seat-assignment/internal/domain"
)

// countingRepo reports how often the seat maps were actually loaded.
type countingRepo struct {
	*memRepo
	loads int
}

func (r *countingRepo) FindSeatMaps(ctx context.Context, service, date, status string, limit, offset int) ([]domain.ContractSeatMap, int, error) {
	r.loads++
	return r.memRepo.FindSeatMaps(ctx, service, date, status, limit, offset)
}

func newPublishedSeatMap(t *testing.T, svc *Service, service, date, compositionVersion string) *domain.ContractSeatMap {
	t.Helper()
	ctx := context.Background()
	created, err := svc.CreateSeatMap(ctx, CreateSeatMapRequest{ScheduledServiceRef: service, ServiceDate: date,
		CompositionVersion: compositionVersion, CompositionSeed: "BASE", MappingVersion: "map-" + compositionVersion, OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	published, err := svc.PublishSeatMap(ctx, created.SeatMapID, PublishSeatMapRequest{
		ExpectedSeatMapVersion: created.SeatMapVersion, PublishReason: "INITIAL", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	return published
}

// Capacity asks seat assignment for the composition and the occupancy of every
// reservation it admits. Both used to load and decode the seat maps of the
// service and date, and decoding one map measured 6.9 milliseconds; at the rate
// this cluster serves them, the second and later calls for an unchanged set must
// not touch the database's documents at all.
func TestSeatMapsAreLoadedOnceWhileTheRevisionHolds(t *testing.T) {
	repo := &countingRepo{memRepo: newMemRepo()}
	svc := New(repo, &memPub{}, nil)
	ctx := context.Background()
	newPublishedSeatMap(t, svc, "ss-cached", "2026-09-25", "v1")

	repo.loads = 0
	for attempt := 0; attempt < 5; attempt++ {
		if _, err := svc.CapacityComposition(ctx, "ss-cached", "2026-09-25", "standard"); err != nil {
			t.Fatal(err)
		}
	}
	// Two loads on the first call: the published list the selection needs and
	// the whole list the replacement lineage walks. None afterwards.
	if repo.loads != 2 {
		t.Fatalf("seat maps loaded %d times for five unchanged calls, want 2", repo.loads)
	}
}

func TestSeatMapsAreReloadedWhenOneIsPublished(t *testing.T) {
	repo := &countingRepo{memRepo: newMemRepo()}
	svc := New(repo, &memPub{}, nil)
	ctx := context.Background()
	newPublishedSeatMap(t, svc, "ss-replaced", "2026-09-25", "v1")

	before, err := svc.CapacityComposition(ctx, "ss-replaced", "2026-09-25", "standard")
	if err != nil {
		t.Fatal(err)
	}
	repo.loads = 0
	if _, err := svc.CapacityComposition(ctx, "ss-replaced", "2026-09-25", "standard"); err != nil {
		t.Fatal(err)
	}
	if repo.loads != 0 {
		t.Fatalf("an unchanged revision reloaded the maps %d times", repo.loads)
	}

	// The load generator publishes replacement maps while it runs, and a
	// replacement moves both revisions: the published list loses one map and the
	// whole list gains one.
	second := newPublishedSeatMap(t, svc, "ss-replaced", "2026-09-25", "v2")
	repo.loads = 0
	after, err := svc.CapacityComposition(ctx, "ss-replaced", "2026-09-25", "standard")
	if err != nil {
		t.Fatal(err)
	}
	if repo.loads != 2 {
		t.Fatalf("a publish reloaded the maps %d times, want 2", repo.loads)
	}
	if after.SeatMapID != second.SeatMapID {
		t.Fatalf("composition selected %s after the replacement published %s: the stale list was served",
			after.SeatMapID, second.SeatMapID)
	}
	if before.SeatMapID == after.SeatMapID {
		t.Fatalf("both reads selected %s", after.SeatMapID)
	}
}
