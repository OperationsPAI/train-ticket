package application

import (
	"context"
	"fmt"
	"testing"
	"time"

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
	// One load on the first call, none afterwards: the entry holds every status,
	// so the selection and the replacement lineage are answered from it.
	if repo.loads != 1 {
		t.Fatalf("seat maps loaded %d times for five unchanged calls, want 1", repo.loads)
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
	if repo.loads != 1 {
		t.Fatalf("a publish reloaded the maps %d times, want 1", repo.loads)
	}
	if after.SeatMapID != second.SeatMapID {
		t.Fatalf("composition selected %s after the replacement published %s: the stale list was served",
			after.SeatMapID, second.SeatMapID)
	}
	if before.SeatMapID == after.SeatMapID {
		t.Fatalf("both reads selected %s", after.SeatMapID)
	}
}

// The list route is what the load generator calls to find the map it replaces,
// and it returns whole seat maps -- twenty of them at the default limit, each
// with every coach and seat unit. It cost 27.8 milliseconds a call across the
// cluster and the client's ten second timeout was expiring on the queue, so a
// page must come out of the same cache the facts path uses.
func TestListSeatMapsServesPagesFromTheCache(t *testing.T) {
	repo := &countingRepo{memRepo: newMemRepo()}
	svc := New(repo, &memPub{}, nil)
	ctx := context.Background()
	newPublishedSeatMap(t, svc, "ss-page", "2026-09-25", "v1")
	newPublishedSeatMap(t, svc, "ss-page", "2026-09-25", "v2")

	repo.loads = 0
	page, err := svc.ListSeatMaps(ctx, "ss-page", "2026-09-25", domain.SeatMapStatusPublished, 20, 0)
	if err != nil {
		t.Fatal(err)
	}
	if page.Total != 1 || len(page.Items) != 1 {
		t.Fatalf("published page total=%d items=%d, want 1 and 1", page.Total, len(page.Items))
	}
	if repo.loads != 1 {
		t.Fatalf("the first page loaded the maps %d times, want 1", repo.loads)
	}

	second, err := svc.ListSeatMaps(ctx, "ss-page", "2026-09-25", domain.SeatMapStatusPublished, 20, 0)
	if err != nil {
		t.Fatal(err)
	}
	if repo.loads != 1 {
		t.Fatalf("the second page reloaded the maps %d times", repo.loads-1)
	}
	if second.Total != page.Total || len(second.Items) != len(page.Items) {
		t.Fatalf("the cached page differs: %+v against %+v", second, page)
	}

	// A second page of the whole list comes from the cached list too, and the
	// whole list is two maps.
	all, err := svc.ListSeatMaps(ctx, "ss-page", "2026-09-25", "", 1, 0)
	if err != nil {
		t.Fatal(err)
	}
	if all.Total != 2 || len(all.Items) != 1 {
		t.Fatalf("first whole-list page total=%d items=%d, want 2 and 1", all.Total, len(all.Items))
	}
	tail, err := svc.ListSeatMaps(ctx, "ss-page", "2026-09-25", "", 1, 1)
	if err != nil {
		t.Fatal(err)
	}
	if tail.Total != 2 || len(tail.Items) != 1 {
		t.Fatalf("second whole-list page total=%d items=%d, want 2 and 1", tail.Total, len(tail.Items))
	}
	if repo.loads != 1 {
		t.Fatalf("the whole list loaded %d times, want 1 (one entry per service and date)", repo.loads)
	}
}

// The cache follows memory, not call count, and the first version of it had no
// bound at all: a run filled the four gibibyte limit and the kubelet OOM-killed
// the replica. Growth past the budget must evict rather than hold.
func TestSeatMapCacheEvictsToStayInsideItsBudget(t *testing.T) {
	cache := newSeatMapCache()
	seatMap, err := domain.NewContractSeatMap("ss-evict", "2026-09-25", "v1", "CRH380A", "map-v1", "", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	perEntry := countSeatUnits([]domain.ContractSeatMap{*seatMap})
	if perEntry == 0 {
		t.Fatal("the fixture has no seat units, so the test cannot bound anything")
	}
	entries := maxCachedSeatUnits/perEntry + 4
	for index := 0; index < entries; index++ {
		date := fmt.Sprintf("2026-11-%02d", index%28+1)
		service := fmt.Sprintf("ss-evict-%d", index)
		cache.put(service, date, SeatMapRevision{Count: 1, VersionSum: 1}, []domain.ContractSeatMap{*seatMap})
	}
	if cache.units > maxCachedSeatUnits {
		t.Fatalf("cache holds %d units, above its budget of %d", cache.units, maxCachedSeatUnits)
	}
	if len(cache.entries) >= entries {
		t.Fatalf("cache held all %d entries: nothing was evicted", entries)
	}
	// The most recently written entry is the one that survives, because the
	// cache is a least recently used one.
	lastService := fmt.Sprintf("ss-evict-%d", entries-1)
	lastDate := fmt.Sprintf("2026-11-%02d", (entries-1)%28+1)
	if _, ok := cache.get(lastService, lastDate, SeatMapRevision{Count: 1, VersionSum: 1}); !ok {
		t.Fatalf("the newest entry %s/%s was evicted", lastService, lastDate)
	}
}

func TestSeatMapCacheRefusesAnEntryLargerThanItsBudget(t *testing.T) {
	cache := newSeatMapCache()
	huge := domain.ContractSeatMap{SeatMapID: "smap-huge", Status: domain.SeatMapStatusPublished}
	coach := domain.Coach{CoachNo: "01", ClassRef: "SECOND_CLASS", Assignable: true}
	for index := 0; index <= maxCachedSeatUnits; index++ {
		coach.SeatUnits = append(coach.SeatUnits, domain.SeatUnit{SeatUnitRef: fmt.Sprintf("su-%d", index), Assignable: true})
	}
	huge.Coaches = []domain.Coach{coach}

	cache.put("ss-huge", "2026-09-25", SeatMapRevision{Count: 1, VersionSum: 1}, []domain.ContractSeatMap{huge})

	if len(cache.entries) != 0 || cache.units != 0 {
		t.Fatalf("an oversized entry was kept: %d entries, %d units", len(cache.entries), cache.units)
	}
}
