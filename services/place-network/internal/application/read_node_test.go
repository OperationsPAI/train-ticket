package application

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

func TestGetTransportNodeAnswersTheNodeItStored(t *testing.T) {
	ctx := context.Background()
	service := newRig(time.Date(2026, 9, 29, 9, 0, 0, 0, time.UTC))
	place, err := service.CreatePlace(ctx, CreatePlaceRequest{PlaceType: "STATION", CanonicalName: "North Gate"})
	if err != nil {
		t.Fatalf("unexpected create error: %v", err)
	}
	node, err := service.CreateTransportNode(ctx, CreateTransportNodeRequest{PlaceID: place.PlaceID, DisplayName: "Platform 3", ServingModes: []string{"RAIL"}})
	if err != nil {
		t.Fatalf("unexpected node error: %v", err)
	}
	read, err := service.GetTransportNode(ctx, domain.TransportNodeID(node.NodeID))
	if err != nil {
		t.Fatalf("unexpected read error: %v", err)
	}
	if read.NodeID != node.NodeID || read.PlaceID != place.PlaceID || read.DisplayName != "Platform 3" {
		t.Fatalf("unexpected read: %#v", read)
	}
	if len(read.ServingModes) != 1 || read.ServingModes[0] != "RAIL" {
		t.Fatalf("expected the declared serving mode, got %#v", read.ServingModes)
	}
	if read.AccessTimeMinutes != nil || read.WalkingEdges != nil {
		t.Fatalf("a node filed without weights answered %#v", read)
	}
}

func TestCreateTransportNodeKeepsTheAccessWeightItWasGiven(t *testing.T) {
	ctx := context.Background()
	service := newRig(time.Date(2026, 9, 29, 9, 0, 0, 0, time.UTC))
	place, err := service.CreatePlace(ctx, CreatePlaceRequest{PlaceType: "STATION", CanonicalName: "North Gate"})
	if err != nil {
		t.Fatalf("unexpected create error: %v", err)
	}
	weight := 12
	node, err := service.CreateTransportNode(ctx, CreateTransportNodeRequest{PlaceID: place.PlaceID, DisplayName: "Platform 3", ServingModes: []string{"RAIL"}, AccessTimeMinutes: &weight})
	if err != nil {
		t.Fatalf("unexpected node error: %v", err)
	}
	read, err := service.GetTransportNode(ctx, domain.TransportNodeID(node.NodeID))
	if err != nil {
		t.Fatalf("unexpected read error: %v", err)
	}
	if read.AccessTimeMinutes == nil || *read.AccessTimeMinutes != weight {
		t.Fatalf("expected the declared weight %d, got %#v", weight, read.AccessTimeMinutes)
	}
}

func TestGetTransportNodeAnswersNotFoundForAReferenceItNeverStored(t *testing.T) {
	service := newRig(time.Date(2026, 9, 29, 9, 0, 0, 0, time.UTC))
	_, err := service.GetTransportNode(context.Background(), domain.TransportNodeID("tnd-0194f2e0-7b3e-7001-8284-5c26e8b00001"))
	var domainErr *DomainError
	if !errors.As(err, &domainErr) || domainErr.Code != "NOT_FOUND" {
		t.Fatalf("expected NOT_FOUND, got %v", err)
	}
}

func TestCreateTransportNodeRefusesAPlaceItNeverStored(t *testing.T) {
	service := newRig(time.Date(2026, 9, 29, 9, 0, 0, 0, time.UTC))
	_, err := service.CreateTransportNode(context.Background(), CreateTransportNodeRequest{PlaceID: "plc-0194f2e0-7b3e-7001-8284-5c26e8b00001", DisplayName: "Platform 3", ServingModes: []string{"RAIL"}})
	var domainErr *DomainError
	if !errors.As(err, &domainErr) || domainErr.Code != "NOT_FOUND" {
		t.Fatalf("expected NOT_FOUND, got %v", err)
	}
}
