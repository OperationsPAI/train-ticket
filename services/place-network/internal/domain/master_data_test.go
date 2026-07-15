package domain

import (
	"strings"
	"testing"
	"time"
)

func TestNewPlaceValidatesMasterData(t *testing.T) {
	coordinate := &Coordinate{Latitude: 31.2304, Longitude: 121.4737}
	place, err := NewPlace("place-sha-hongqiao", PlaceTypeStation, "Shanghai Hongqiao", PlaceStatusActive, coordinate)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if place.ID != "place-sha-hongqiao" || place.CanonicalName != "Shanghai Hongqiao" {
		t.Fatalf("unexpected place: %#v", place)
	}
}

func TestNewPlaceRejectsInvalidCoordinate(t *testing.T) {
	_, err := NewPlace("place-invalid", PlaceTypeStation, "Invalid", PlaceStatusDraft, &Coordinate{Latitude: 120, Longitude: 0})
	if err == nil || !strings.Contains(err.Error(), "latitude out of range") {
		t.Fatalf("expected latitude validation error, got %v", err)
	}
}

func TestNewTransportNodeRequiresServingMode(t *testing.T) {
	_, err := NewTransportNode("node-sha-hongqiao", "place-sha-hongqiao", "Shanghai Hongqiao Railway Station", nil)
	if err == nil || !strings.Contains(err.Error(), "serving mode") {
		t.Fatalf("expected serving mode validation error, got %v", err)
	}
}

func TestTransportNodeWalkingEdgeWeightIsOptionalAndNonNegative(t *testing.T) {
	node, err := NewTransportNode("node-sha-hongqiao", "place-sha-hongqiao", "Shanghai Hongqiao Railway Station", []TransportMode{TransportModeTrain})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	zero := 0
	node.SetAccessWeights(nil, []WalkingEdge{{ToNodeID: "node-unknown"}, {ToNodeID: "node-zero", WalkingTimeMinutes: &zero}})
	if err := node.Validate(); err != nil {
		t.Fatalf("unexpected validation error: %v", err)
	}
	if node.WalkingEdges[0].WalkingTimeMinutes != nil {
		t.Fatalf("omitted walkingTimeMinutes must stay nil: %#v", node.WalkingEdges[0])
	}
	if node.WalkingEdges[1].WalkingTimeMinutes == nil || *node.WalkingEdges[1].WalkingTimeMinutes != 0 {
		t.Fatalf("explicit zero walkingTimeMinutes must stay zero: %#v", node.WalkingEdges[1])
	}
}

func TestTransportNodeRejectsNegativeWalkingEdgeWeight(t *testing.T) {
	node, err := NewTransportNode("node-sha-hongqiao", "place-sha-hongqiao", "Shanghai Hongqiao Railway Station", []TransportMode{TransportModeTrain})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	negative := -1
	node.SetAccessWeights(nil, []WalkingEdge{{ToNodeID: "node-other", WalkingTimeMinutes: &negative}})
	if err := node.Validate(); err == nil || !strings.Contains(err.Error(), "walkingTimeMinutes") {
		t.Fatalf("expected walkingTimeMinutes validation error, got %v", err)
	}
}

func TestProviderPlaceMappingStandardRequiresTargetAndConfidence(t *testing.T) {
	validFrom := time.Date(2026, 7, 3, 0, 0, 0, 0, time.UTC)
	_, err := NewProviderPlaceMapping("cr", "rail-import", "AOH", "Shanghai Hongqiao", nil, ProviderMappingStandard, 0.95, validFrom, nil, "")
	if err == nil || !strings.Contains(err.Error(), "target node") {
		t.Fatalf("expected target node validation error, got %v", err)
	}

	target := TransportNodeID("node-sha-hongqiao")
	_, err = NewProviderPlaceMapping("cr", "rail-import", "AOH", "Shanghai Hongqiao", &target, ProviderMappingStandard, 0.70, validFrom, nil, "")
	if err == nil || !strings.Contains(err.Error(), "confidence") {
		t.Fatalf("expected confidence validation error, got %v", err)
	}
}

func TestProviderPlaceMappingAllowsExplicitLowConfidenceGap(t *testing.T) {
	validFrom := time.Date(2026, 7, 3, 0, 0, 0, 0, time.UTC)
	mapping, err := NewProviderPlaceMapping("cr", "rail-import", "X123", "Ambiguous Station", nil, ProviderMappingLowConfidence, 0.42, validFrom, nil, "ambiguous provider station name")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mapping.Status != ProviderMappingLowConfidence || mapping.TargetNode != nil {
		t.Fatalf("unexpected low-confidence mapping: %#v", mapping)
	}
}
