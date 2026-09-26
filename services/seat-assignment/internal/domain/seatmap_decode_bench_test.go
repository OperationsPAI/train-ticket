package domain_test

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/trainticket/greenfield/services/seat-assignment/internal/domain"
)

// What a capacity-facts call spent its time on.
//
// Capacity asks for the composition and the occupancy of every reservation it
// admits, and each of those loaded the service and date's seat maps twice: once
// to select the published map and once to walk the replacement lineage. This is
// the decode that dominated it, on a map of the size the cluster holds (sixteen
// coaches, about 1700 seat units, and a document measured at 468 kilobytes).
// The application layer now reuses the decoded maps while the database reports
// the same revision, so the decode happens once per revision rather than once
// per call; see seatMapCache.
func seededSeatMap(b *testing.B) []byte {
	b.Helper()
	// CRH380A is the seed the cluster's maps are built from: the real coach
	// topology rather than the eight seat placeholder.
	seatMap, err := domain.NewContractSeatMap("ss-bench", "2026-10-03", "v1", "CRH380A", "map-v1", "", time.Now())
	if err != nil {
		b.Fatal(err)
	}
	raw, err := json.Marshal(seatMap)
	if err != nil {
		b.Fatal(err)
	}
	var coaches, units int
	for _, coach := range seatMap.Coaches {
		coaches++
		units += len(coach.SeatUnits)
	}
	b.Logf("fixture: %d coaches, %d seat units, %d bytes", coaches, units, len(raw))
	return raw
}

func BenchmarkDecodeSeatMap(b *testing.B) {
	raw := seededSeatMap(b)
	b.SetBytes(int64(len(raw)))
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		var seatMap domain.ContractSeatMap
		if err := json.Unmarshal(raw, &seatMap); err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkAssignableUnits(b *testing.B) {
	raw := seededSeatMap(b)
	var seatMap domain.ContractSeatMap
	if err := json.Unmarshal(raw, &seatMap); err != nil {
		b.Fatal(err)
	}
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		_ = seatMap.AssignableUnits("SECOND_CLASS")
	}
}
