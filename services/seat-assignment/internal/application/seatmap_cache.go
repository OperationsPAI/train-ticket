package application

import (
	"context"
	"sync"

	"github.com/trainticket/greenfield/services/seat-assignment/internal/domain"
)

// SeatMapRevision identifies the seat maps of one service and date.
//
// Both parts only ever grow: an insert raises the count, and every write to an
// existing map raises the sum of versions, because UpdateSeatMap increments the
// version of the row it writes. A timestamp would not do: `updated_at` is
// `now()`, which is the transaction's start time, so two writers that commit out
// of order can leave the newest commit carrying the earlier stamp.
//
// The revision covers every map of the service and date, not one status of them.
// A replacement is the reason: publishing it supersedes the old map and inserts
// the new one, and if the two carry the same version the count and the sum of a
// status-filtered set are unchanged while its membership is not.
type SeatMapRevision struct {
	Count      int
	VersionSum int64
}

// seatMapCache holds decoded seat maps between calls.
//
// Capacity asks for the composition and the occupancy of every reservation it
// admits, and each of those loaded the service and date's seat maps twice: once
// to select the published map and once to walk the replacement lineage. Decoding
// one map of sixteen coaches and 1731 seat units is 6.9 milliseconds and about a
// megabyte of allocation (BenchmarkDecodeSeatMap), so the hundred and
// eighty-seven calls a second this cluster was serving spent about five cores
// rebuilding documents that had not changed -- and the delay landed inside
// capacity's admission lock, which is one lock for the whole service and date.
//
// An entry is reused only while the database reports the same revision, so a
// publish, a retirement or a unit marking is visible on the next call. Checking
// is one indexed aggregate over at most a handful of rows, against the four
// document decodes it saves.
type seatMapCache struct {
	mu      sync.Mutex
	entries map[string]seatMapCacheEntry
}

type seatMapCacheEntry struct {
	revision SeatMapRevision
	maps     []domain.ContractSeatMap
}

func newSeatMapCache() seatMapCache {
	return seatMapCache{entries: map[string]seatMapCacheEntry{}}
}

func seatMapCacheKey(service, date, status string) string {
	return service + "\x1f" + date + "\x1f" + status
}

// get returns the decoded maps of that revision. The slice is shared, and every
// reader in this package treats a seat map as immutable: AssignableUnits copies
// the units it returns and the lineage walks only read.
func (c *seatMapCache) get(service, date, status string, revision SeatMapRevision) ([]domain.ContractSeatMap, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	entry, ok := c.entries[seatMapCacheKey(service, date, status)]
	if !ok || entry.revision != revision {
		return nil, false
	}
	return entry.maps, true
}

func (c *seatMapCache) put(service, date, status string, revision SeatMapRevision, maps []domain.ContractSeatMap) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.entries == nil {
		c.entries = map[string]seatMapCacheEntry{}
	}
	c.entries[seatMapCacheKey(service, date, status)] = seatMapCacheEntry{revision: revision, maps: maps}
}

// seatMaps returns one service and date's seat maps of a status, reading them
// from the database only when the revision moved. `status` empty means every
// status, which is what the replacement lineage needs.
func (s *Service) seatMaps(ctx context.Context, service, date, status string) ([]domain.ContractSeatMap, int, error) {
	revision, err := s.repo.SeatMapRevision(ctx, service, date)
	if err != nil {
		return nil, 0, err
	}
	if cached, ok := s.cache.get(service, date, status, revision); ok {
		return cached, revision.Count, nil
	}
	maps, total, err := s.repo.FindSeatMaps(ctx, service, date, status, 100, 0)
	if err != nil {
		return nil, 0, err
	}
	if total <= 100 {
		// Only a complete list is worth keeping: a truncated one is an error at
		// every call site, and caching it would hide the next insert.
		s.cache.put(service, date, status, revision, maps)
	}
	return maps, total, nil
}
