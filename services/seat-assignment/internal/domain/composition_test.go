package domain

import (
	"testing"
	"time"
)

func TestCRHCompositionPreservesConfiguredSeats(t *testing.T) {
	seatMap, err := NewContractSeatMap("ss-composition", "2026-09-30", "1", "CRH380A", "1", "", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	counts := map[string]int{}
	for _, seat := range DefaultSeatMap().Seats {
		counts[seat.SeatType]++
	}
	for class, count := range counts {
		if actual := len(seatMap.AssignableUnits(class)); actual != count {
			t.Fatalf("class %s expected %d seats got %d", class, count, actual)
		}
	}
	second, err := NewContractSeatMap("ss-composition", "2026-09-30", "1", "CRH380A", "1", "", time.Now())
	if err != nil || second.SeatMapID != seatMap.SeatMapID {
		t.Fatal("composition identity changed")
	}
}
