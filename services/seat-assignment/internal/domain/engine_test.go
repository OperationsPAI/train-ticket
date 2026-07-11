package domain

import (
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

func TestTogetherForThreeTravelersSameRow(t *testing.T) {
	engine := NewAssignmentEngine()
	seats, err := engine.Choose(DefaultSeatMap(), nil, AssignmentRequest{SegmentRef: "seg-1", TravelerRefs: []string{"t1", "t2", "t3"}, SeatClass: ClassSecond, Preferences: []SeatPreference{{PreferenceType: PreferenceTogether, Priority: 1}}})
	if err != nil {
		t.Fatal(err)
	}
	if len(seats) != 3 {
		t.Fatalf("expected 3 seats")
	}
	for _, seat := range seats[1:] {
		if seat.CarNumber != seats[0].CarNumber || seat.Row != seats[0].Row {
			t.Fatalf("not together: %+v", seats)
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
