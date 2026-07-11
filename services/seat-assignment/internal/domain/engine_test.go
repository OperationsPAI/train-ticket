package domain

import (
	"fmt"
	"testing"
	"time"
)

func TestWindowPreferenceGetsWindowInSecondClass(t *testing.T) {
	engine := NewAssignmentEngine()
	seats, err := engine.Choose(DefaultSeatMap(), nil, AssignmentRequest{SegmentRef: "seg-1", TravelerRefs: []string{"t1"}, SeatClass: ClassSecond, Preferences: []SeatPreference{{PreferenceType: PreferenceWindow, Priority: 1}}})
	if err != nil {
		t.Fatal(err)
	}
	if got := seats[0].Letter; got != "A" && got != "E" {
		t.Fatalf("expected window A or E, got %s (%+v)", got, seats[0])
	}
}

func TestTogetherForThreeTravelersSameRowAndAdjacent(t *testing.T) {
	engine := NewAssignmentEngine()
	seats, err := engine.Choose(DefaultSeatMap(), nil, AssignmentRequest{SegmentRef: "seg-1", TravelerRefs: []string{"t1", "t2", "t3"}, SeatClass: ClassSecond, Preferences: []SeatPreference{{PreferenceType: PreferenceTogether, Priority: 1}}})
	if err != nil {
		t.Fatal(err)
	}
	if len(seats) != 3 {
		t.Fatalf("expected 3 seats")
	}
	assertSameRowAndAdjacent(t, seats)
}

func TestTogetherSkipsRowsWithGaps(t *testing.T) {
	engine := NewAssignmentEngine()
	occupied := []string{"06-01B", "06-01D"}
	seats, err := engine.Choose(DefaultSeatMap(), occupied, AssignmentRequest{SegmentRef: "seg-1", TravelerRefs: []string{"t1", "t2", "t3"}, SeatClass: ClassSecond, Preferences: []SeatPreference{{PreferenceType: PreferenceTogether, Priority: 1}}})
	if err != nil {
		t.Fatal(err)
	}
	assertSameRowAndAdjacent(t, seats)
	if seats[0].CarNumber == 6 && seats[0].Row == 1 {
		t.Fatalf("expected row with gaps to be skipped, got %+v", seats)
	}
}

func TestFullCarFallsBackToNextCar(t *testing.T) {
	engine := NewAssignmentEngine()
	occupied := make([]string, 0, 125)
	for row := 1; row <= 25; row++ {
		for _, letter := range []string{"A", "B", "C", "D", "E"} {
			occupied = append(occupied, formatSeatID(6, row, letter))
		}
	}
	seats, err := engine.Choose(DefaultSeatMap(), occupied, AssignmentRequest{SegmentRef: "seg-1", TravelerRefs: []string{"t1"}, SeatClass: ClassSecond, Preferences: []SeatPreference{{PreferenceType: PreferenceWindow, Priority: 1}}})
	if err != nil {
		t.Fatal(err)
	}
	if seats[0].CarNumber != 7 {
		t.Fatalf("expected next available second-class car 7, got %+v", seats[0])
	}
}

func TestBusinessClassTwoPlusOnePositionsMatchContract(t *testing.T) {
	seatMap := DefaultSeatMap()
	want := map[string]string{"01-01A": PositionAisle, "01-01B": PositionWindow, "01-01C": PositionWindow}
	for seatID, position := range want {
		seat, ok := seatMap.Find(seatID)
		if !ok {
			t.Fatalf("seat %s not found", seatID)
		}
		if seat.Position != position {
			t.Fatalf("seat %s position: want %s got %s", seatID, position, seat.Position)
		}
	}
}

func TestHoldExpiresSeatAvailableAgain(t *testing.T) {
	inv := NewSeatInventory("seg-1", "2026-07-20", "CRH380A")
	now := time.Date(2026, 7, 20, 9, 0, 0, 0, time.UTC)
	if _, err := inv.HoldSeat("06-01A", "t1", "hold-1", now); err != nil {
		t.Fatal(err)
	}
	if _, err := inv.HoldSeat("06-01A", "t2", "hold-2", now.Add(time.Minute)); err == nil {
		t.Fatalf("seat should be unavailable while held")
	}
	inv.ExpireHolds(now.Add(11 * time.Minute))
	if _, err := inv.HoldSeat("06-01A", "t2", "hold-2", now.Add(11*time.Minute)); err != nil {
		t.Fatalf("seat should be available after expiry: %v", err)
	}
}

func TestConfirmExpiredHoldRejected(t *testing.T) {
	inv := NewSeatInventory("seg-1", "2026-07-20", "CRH380A")
	now := time.Date(2026, 7, 20, 9, 0, 0, 0, time.UTC)
	if _, err := inv.HoldSeat("06-01A", "t1", "hold-1", now); err != nil {
		t.Fatal(err)
	}
	if _, err := inv.ConfirmSeat("06-01A", "hold-1", now.Add(11*time.Minute)); err == nil {
		t.Fatalf("expected expired hold confirmation to be rejected")
	}
	if _, err := inv.HoldSeat("06-01A", "t2", "hold-2", now.Add(11*time.Minute)); err != nil {
		t.Fatalf("seat should be available after rejected expired confirmation: %v", err)
	}
}

func TestConfirmAndReleaseLifecycle(t *testing.T) {
	inv := NewSeatInventory("seg-1", "2026-07-20", "CRH380A")
	now := time.Now().UTC()
	if _, err := inv.HoldSeat("06-01A", "t1", "hold-1", now); err != nil {
		t.Fatal(err)
	}
	a, err := inv.ConfirmSeat("06-01A", "hold-1", now.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if a.Status != StatusConfirmed {
		t.Fatalf("want confirmed got %s", a.Status)
	}
	a, err = inv.ReleaseSeat("06-01A", now.Add(2*time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if a.Status != StatusReleased {
		t.Fatalf("want released got %s", a.Status)
	}
}

func assertSameRowAndAdjacent(t *testing.T, seats []Seat) {
	t.Helper()
	for _, seat := range seats[1:] {
		if seat.CarNumber != seats[0].CarNumber || seat.Row != seats[0].Row {
			t.Fatalf("not same row: %+v", seats)
		}
	}
	if !seatsAreAdjacent(seats) {
		t.Fatalf("not adjacent: %+v", seats)
	}
}

func formatSeatID(car, row int, letter string) string {
	return fmt.Sprintf("%02d-%02d%s", car, row, letter)
}
