package application

import (
	"context"
	"testing"
	"time"

	"github.com/trainticket/greenfield/services/place-network/internal/domain"
	"github.com/trainticket/greenfield/services/place-network/internal/domain/ports"
)

type fixedClock struct{ now time.Time }

func (c fixedClock) Now() time.Time { return c.now }

func testService(allowance int) (*Service, *ports.InMemoryPlaceRepository) {
	places := ports.NewInMemoryPlaceRepository()
	return NewService(ServiceConfig{
		Places:                        places,
		Nodes:                         ports.NewInMemoryTransportNodeRepository(),
		Clock:                         fixedClock{now: time.Date(2026, 9, 28, 12, 0, 0, 0, time.UTC)},
		StationAccessAllowanceMinutes: allowance,
	}), places
}

func saveStation(t *testing.T, places *ports.InMemoryPlaceRepository) domain.Place {
	t.Helper()
	place, err := domain.NewPlace("plc-test-station", domain.PlaceTypeStation, "Test Junction", domain.PlaceStatusActive, nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if err := places.Save(context.Background(), place); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	return place
}

func TestCreateTransportNodeAppliesTheStationAllowance(t *testing.T) {
	service, places := testService(45)
	place := saveStation(t, places)
	created, err := service.CreateTransportNode(context.Background(), CreateTransportNodeRequest{PlaceID: string(place.ID), DisplayName: "Test Junction node", ServingModes: []string{"RAIL"}})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if created.AccessTimeMinutes == nil || *created.AccessTimeMinutes != 45 {
		t.Fatalf("expected the station allowance, got %v", created.AccessTimeMinutes)
	}
}

func TestCreateTransportNodeKeepsADeclaredAccessWeight(t *testing.T) {
	service, places := testService(45)
	place := saveStation(t, places)
	declared := 7
	created, err := service.CreateTransportNode(context.Background(), CreateTransportNodeRequest{PlaceID: string(place.ID), DisplayName: "Test Junction platform", ServingModes: []string{"RAIL"}, AccessTimeMinutes: &declared})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if created.AccessTimeMinutes == nil || *created.AccessTimeMinutes != 7 {
		t.Fatalf("expected the declared weight to win, got %v", created.AccessTimeMinutes)
	}
}

func TestCreateTransportNodeLeavesANonStationNodeUnmeasured(t *testing.T) {
	service, places := testService(45)
	place, err := domain.NewPlace("plc-test-city", domain.PlaceTypeCity, "Test City", domain.PlaceStatusActive, nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if err := places.Save(context.Background(), place); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	created, err := service.CreateTransportNode(context.Background(), CreateTransportNodeRequest{PlaceID: string(place.ID), DisplayName: "Test City stop", ServingModes: []string{"BUS"}})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if created.AccessTimeMinutes != nil {
		t.Fatalf("expected the field to stay absent, got %v", *created.AccessTimeMinutes)
	}
}

// A zero allowance is how the settings file says "leave the field absent": the
// node keeps the absent value the contract documents instead of a measured 0.
func TestCreateTransportNodeLeavesTheFieldAbsentWithoutAnAllowance(t *testing.T) {
	service, places := testService(0)
	place := saveStation(t, places)
	created, err := service.CreateTransportNode(context.Background(), CreateTransportNodeRequest{PlaceID: string(place.ID), DisplayName: "Test Junction exit", ServingModes: []string{"RAIL"}})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if created.AccessTimeMinutes != nil {
		t.Fatalf("expected the field to stay absent, got %v", *created.AccessTimeMinutes)
	}
}

// The allowance fills a node the request left unweighed, and the read answers
// the same value the create did: one node, one weight, whichever route the
// consumer takes to it.
func TestGetTransportNodeCarriesTheAllowanceTheCreateAnswered(t *testing.T) {
	service, places := testService(45)
	place := saveStation(t, places)
	created, err := service.CreateTransportNode(context.Background(), CreateTransportNodeRequest{PlaceID: string(place.ID), DisplayName: "Test Junction entrance", ServingModes: []string{"RAIL"}})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if created.AccessTimeMinutes == nil {
		t.Fatal("expected the station allowance on the create response")
	}
	fetched, err := service.GetTransportNode(context.Background(), domain.TransportNodeID(created.NodeID))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if fetched.AccessTimeMinutes == nil || *fetched.AccessTimeMinutes != *created.AccessTimeMinutes {
		t.Fatalf("expected the read to carry the same allowance, got %v", fetched.AccessTimeMinutes)
	}
}
