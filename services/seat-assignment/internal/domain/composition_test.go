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

func TestSleeperCompositionContainsBerthPositions(t *testing.T) {
	seats, err := NewContractSeatMap("ss-sleeper", "2026-09-30", "1", "SLEEPER", "1", "", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	for class, count := range map[string]int{"SLEEPER_HARD": 54, "SLEEPER_SOFT": 36} {
		units := seats.AssignableUnits(class)
		if len(units) != count {
			t.Fatalf("class=%s count=%d", class, len(units))
		}
		for _, unit := range units {
			if unit.AllocationType != AllocationTypeBerth || unit.BerthPosition == "" {
				t.Fatalf("invalid berth %+v", unit)
			}
		}
	}
}
