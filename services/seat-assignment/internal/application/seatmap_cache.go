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

// seatMapReadLimit is how many maps of one service and date a cached read takes.
// It is the same ceiling the list handler clamps a page to, and a service and
// date hold a handful of maps, so a read at this bound is a complete list.
const seatMapReadLimit = 100

// maxCachedSeatUnits bounds what the cache may hold.
//
// Memory follows the seat units, not the call count: BenchmarkDecodeSeatMap
// allocates 991 kilobytes for a map of sixteen coaches and 1731 seat units, so a
// unit costs about 573 bytes of live maps and this bound is roughly 143
// megabytes of them. seat-assignment's limit is four gibibytes and it also
// builds list responses of a megabyte or more, so the cache is the smaller
// tenant on purpose. A first version of this cache had no bound at all and the
// service was OOM-killed after twenty minutes of a run.
const maxCachedSeatUnits = 250_000

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
//
// Entries are keyed by service and date alone and hold every status: the
// revision is per service and date, and holding the published list and the whole
// list separately would keep the same maps twice. A status filter is applied to
// the held list, which is the same predicate the repository would use.
type seatMapCache struct {
	mu      sync.Mutex
	entries map[string]seatMapCacheEntry
	units   int
	clock   uint64
}

type seatMapCacheEntry struct {
	revision SeatMapRevision
	maps     []domain.ContractSeatMap
	units    int
	used     uint64
}

func newSeatMapCache() seatMapCache {
	return seatMapCache{entries: map[string]seatMapCacheEntry{}}
}

func seatMapCacheKey(service, date string) string {
	return service + "\x1f" + date
}

// get returns every map of that service and date. The slice is shared, and every
// reader in this package treats a seat map as immutable: AssignableUnits copies
// the units it returns and the lineage walks only read.
func (c *seatMapCache) get(service, date string, revision SeatMapRevision) ([]domain.ContractSeatMap, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	key := seatMapCacheKey(service, date)
	entry, ok := c.entries[key]
	if !ok || entry.revision != revision {
		return nil, false
	}
	c.clock++
	entry.used = c.clock
	c.entries[key] = entry
	return entry.maps, true
}

func (c *seatMapCache) put(service, date string, revision SeatMapRevision, maps []domain.ContractSeatMap) {
	units := countSeatUnits(maps)
	if units > maxCachedSeatUnits {
		// One service and date may not hold the whole budget: evicting everything
		// else to keep it would leave the cache useless and thrashing.
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.entries == nil {
		c.entries = map[string]seatMapCacheEntry{}
	}
	key := seatMapCacheKey(service, date)
	if existing, ok := c.entries[key]; ok {
		c.units -= existing.units
	}
	c.clock++
	c.entries[key] = seatMapCacheEntry{revision: revision, maps: maps, units: units, used: c.clock}
	c.units += units
	c.evictLocked()
}

// evictLocked drops least recently used entries until the cache fits.
func (c *seatMapCache) evictLocked() {
	for c.units > maxCachedSeatUnits && len(c.entries) > 0 {
		oldestKey, oldestUsed := "", ^uint64(0)
		for key, entry := range c.entries {
			if entry.used < oldestUsed {
				oldestKey, oldestUsed = key, entry.used
			}
		}
		c.units -= c.entries[oldestKey].units
		delete(c.entries, oldestKey)
	}
}

func countSeatUnits(maps []domain.ContractSeatMap) int {
	units := 0
	for _, seatMap := range maps {
		for _, coach := range seatMap.Coaches {
			units += len(coach.SeatUnits)
		}
	}
	return units
}

// seatMaps returns one service and date's seat maps of a status, reading them
// from the database only when the revision moved. `status` empty means every
// status, which is what the replacement lineage needs. The cached list is always
// the whole one, so callers can page it as well as walk it.
func (s *Service) seatMaps(ctx context.Context, service, date, status string) ([]domain.ContractSeatMap, int, error) {
	revision, err := s.repo.SeatMapRevision(ctx, service, date)
	if err != nil {
		return nil, 0, err
	}
	if cached, ok := s.cache.get(service, date, revision); ok {
		return seatMapsOfStatus(cached, status)
	}
	maps, total, err := s.repo.FindSeatMaps(ctx, service, date, "", seatMapReadLimit, 0)
	if err != nil {
		return nil, 0, err
	}
	if total > seatMapReadLimit {
		// A truncated list cannot answer a status filter: ask for that status
		// directly instead of caching a list that is missing rows.
		return s.repo.FindSeatMaps(ctx, service, date, status, seatMapReadLimit, 0)
	}
	s.cache.put(service, date, revision, maps)
	return seatMapsOfStatus(maps, status)
}

// seatMapsOfStatus is the filter the repository would have applied, on the list
// the cache holds, keeping the order the repository returned.
func seatMapsOfStatus(maps []domain.ContractSeatMap, status string) ([]domain.ContractSeatMap, int, error) {
	if status == "" {
		return maps, len(maps), nil
	}
	matching := make([]domain.ContractSeatMap, 0, len(maps))
	for _, seatMap := range maps {
		if seatMap.Status == status {
			matching = append(matching, seatMap)
		}
	}
	return matching, len(matching), nil
}

// seatMapPage answers one page of a list from the cached list.
//
// The list route is what the load generator calls to find the map to replace,
// and it returns whole seat maps: twenty of them at the default limit, each with
// every coach and seat unit. Loading and decoding them per call cost 27.8
// milliseconds and four cores across the two replicas, and the client's ten
// second timeout was expiring on the queue. `ok` is false when the page reaches
// past the cached window, which only happens once a service and date hold more
// than a hundred maps; the caller then asks the repository.
func seatMapPage(maps []domain.ContractSeatMap, total, limit, offset int) ([]domain.ContractSeatMap, bool) {
	if total > seatMapReadLimit || offset < 0 || offset > len(maps) {
		return nil, false
	}
	end := offset + limit
	if end > len(maps) {
		end = len(maps)
	}
	return maps[offset:end], true
}
