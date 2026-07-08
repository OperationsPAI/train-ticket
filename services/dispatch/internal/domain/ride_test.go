package domain

import (
	"testing"
	"time"
)

func TestRideRequestStateMachineAndDriverCancelRematch(t *testing.T) {
	now := time.Date(2026, 7, 8, 10, 0, 0, 0, time.UTC)
	r, err := NewRideRequest("rrq-test", "acc-1", "tvl-1", "plc-a", "plc-b", TimeWindow{StartAt: now, EndAt: now.Add(time.Hour)}, "fare-1", "intent-1", now)
	if err != nil {
		t.Fatal(err)
	}
	if err := r.EnterMatching(now); err != nil {
		t.Fatal(err)
	}
	if err := r.AssignDriver("drv-1", "veh-1", 90, now); err != nil {
		t.Fatal(err)
	}
	assignmentID := r.Assignment.ID
	cancel, err := r.CancelByDriver("flat tire", now.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if r.Status != StatusMatching || r.Assignment != nil {
		t.Fatalf("expected rematch without active assignment: %#v", r)
	}
	if cancel.AssignmentID != assignmentID || len(r.Cancellations) != 1 {
		t.Fatalf("missing cancellation audit: %#v", r.Cancellations)
	}
}

func TestRideRequestHappyPath(t *testing.T) {
	now := time.Date(2026, 7, 8, 10, 0, 0, 0, time.UTC)
	r, _ := NewRideRequest("rrq-test", "acc-1", "tvl-1", "plc-a", "plc-b", TimeWindow{StartAt: now, EndAt: now.Add(time.Hour)}, "", "intent-1", now)
	_ = r.EnterMatching(now)
	_ = r.AssignDriver("drv-1", "veh-1", 90, now)
	if err := r.MarkDriverArrived(now); err != nil {
		t.Fatal(err)
	}
	if err := r.StartRide(now); err != nil {
		t.Fatal(err)
	}
	if err := r.Complete("fare-final", now); err != nil {
		t.Fatal(err)
	}
	if r.Status != StatusCompleted || r.Assignment.EndedAt == nil {
		t.Fatalf("unexpected completed ride: %#v", r)
	}
}
