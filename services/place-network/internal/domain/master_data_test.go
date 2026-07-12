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
