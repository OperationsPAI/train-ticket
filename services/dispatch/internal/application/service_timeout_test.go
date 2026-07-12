package application

import (
	"context"
	"testing"
	"time"

	"github.com/trainticket/greenfield/services/dispatch/internal/domain"
	"github.com/trainticket/greenfield/services/dispatch/internal/domain/ports"
)

type fixedClock struct{ now time.Time }

func (c fixedClock) Now() time.Time { return c.now }

type recordingPublisher struct{ events []domain.EventEnvelope }

func (p *recordingPublisher) Publish(_ context.Context, event domain.EventEnvelope) error {
	p.events = append(p.events, event)
	return nil
}

func TestScanTimedOutMarksRequestedAndMatchingFailed(t *testing.T) {
	now := time.Date(2026, 7, 9, 12, 0, 0, 0, time.UTC)
	repo := ports.NewInMemoryRideRequestRepository()
	publisher := &recordingPublisher{}
	service := NewService(ServiceConfig{Rides: repo, Publisher: publisher, Clock: fixedClock{now: now}, RequestTimeout: time.Minute, MatchingTimeout: 2 * time.Minute})

	requested := mustRide(t, "rrq-requested", "acc-1", now.Add(-2*time.Minute), domain.StatusRequested)
	matching := mustRide(t, "rrq-matching", "acc-2", now.Add(-3*time.Minute), domain.StatusMatching)
	if err := repo.Save(context.Background(), requested); err != nil {
		t.Fatal(err)
	}
	if err := repo.Save(context.Background(), matching); err != nil {
		t.Fatal(err)
	}

	processed, err := service.ScanTimedOut(context.Background(), 10)
	if err != nil {
		t.Fatal(err)
	}
	if processed != 2 {
		t.Fatalf("processed = %d, want 2", processed)
	}
	for _, id := range []domain.RideRequestID{"rrq-requested", "rrq-matching"} {
		ride, err := repo.FindByID(context.Background(), id)
		if err != nil {
			t.Fatal(err)
		}
		if ride.Status != domain.StatusFailed {
			t.Fatalf("%s status = %s, want FAILED", id, ride.Status)
		}
	}
	if len(publisher.events) != 2 {
		t.Fatalf("events = %d, want 2", len(publisher.events))
	}
	for _, event := range publisher.events {
		if event.EventType != "DispatchFailed" {
			t.Fatalf("event type = %s, want DispatchFailed", event.EventType)
		}
	}
}

func TestScanTimedOutLeavesFreshRequestsActive(t *testing.T) {
	now := time.Date(2026, 7, 9, 12, 0, 0, 0, time.UTC)
	repo := ports.NewInMemoryRideRequestRepository()
	publisher := &recordingPublisher{}
	service := NewService(ServiceConfig{Rides: repo, Publisher: publisher, Clock: fixedClock{now: now}, RequestTimeout: time.Minute, MatchingTimeout: 2 * time.Minute})

	freshRequested := mustRide(t, "rrq-fresh-requested", "acc-1", now.Add(-30*time.Second), domain.StatusRequested)
	freshMatching := mustRide(t, "rrq-fresh-matching", "acc-2", now.Add(-90*time.Second), domain.StatusMatching)
	if err := repo.Save(context.Background(), freshRequested); err != nil {
		t.Fatal(err)
	}
	if err := repo.Save(context.Background(), freshMatching); err != nil {
		t.Fatal(err)
	}

	processed, err := service.ScanTimedOut(context.Background(), 10)
	if err != nil {
		t.Fatal(err)
	}
	if processed != 0 {
		t.Fatalf("processed = %d, want 0", processed)
	}
	for _, tc := range []struct {
		id   domain.RideRequestID
		want domain.RideStatus
	}{
		{id: "rrq-fresh-requested", want: domain.StatusRequested},
		{id: "rrq-fresh-matching", want: domain.StatusMatching},
	} {
		ride, err := repo.FindByID(context.Background(), tc.id)
		if err != nil {
			t.Fatal(err)
		}
		if ride.Status != tc.want {
			t.Fatalf("%s status = %s, want %s", tc.id, ride.Status, tc.want)
		}
	}
	if len(publisher.events) != 0 {
		t.Fatalf("events = %d, want 0", len(publisher.events))
	}
}

func mustRide(t *testing.T, id domain.RideRequestID, accountID string, updatedAt time.Time, status domain.RideStatus) domain.RideRequest {
	t.Helper()
	ride, err := domain.NewRideRequest(id, accountID, "tvl-"+accountID, "plc-a-"+accountID, "plc-b-"+accountID, domain.TimeWindow{StartAt: updatedAt, EndAt: updatedAt.Add(time.Hour)}, "", "intent-"+accountID, updatedAt)
	if err != nil {
		t.Fatal(err)
	}
	if status == domain.StatusMatching {
		if err := ride.EnterMatching(updatedAt); err != nil {
			t.Fatal(err)
		}
	}
	return ride
}
