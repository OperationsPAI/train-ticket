package domain

import (
	"strings"
	"testing"
)

func TestNewProviderAdapterValidates(t *testing.T) {
	matrix := CapabilityMatrix{
		Version: "1.0",
		Capabilities: map[Capability]bool{
			CapabilityReserve:         true,
			CapabilityIssueCredential: true,
		},
	}
	adapter, err := NewProviderAdapter(
		"cr-rail",
		ProviderTypeRail,
		"1.0.0",
		AuthConfig{AuthType: "api-key", Credentials: map[string]string{"key": "abc"}},
		matrix,
		SlaPolicy{P95LatencyMs: 2000, SuccessRate: 0.99, MaxConcurrency: 10},
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if adapter.ProviderID != "cr-rail" {
		t.Fatalf("unexpected provider id: %s", adapter.ProviderID)
	}
	if adapter.Status != ProviderAdapterStatusActive {
		t.Fatalf("expected active status, got %s", adapter.Status)
	}
	if adapter.HealthStatus != "unknown" {
		t.Fatalf("expected unknown health, got %s", adapter.HealthStatus)
	}
}

func TestNewProviderAdapterRejectsMissingFields(t *testing.T) {
	matrix := CapabilityMatrix{
		Version:      "1.0",
		Capabilities: map[Capability]bool{CapabilityReserve: true},
	}
	_, err := NewProviderAdapter("", ProviderTypeRail, "1.0.0", AuthConfig{AuthType: "key", Credentials: nil}, matrix, SlaPolicy{})
	if err == nil || !strings.Contains(err.Error(), "provider id is required") {
		t.Fatalf("expected provider id error, got %v", err)
	}

	_, err = NewProviderAdapter("cr", ProviderTypeRail, "", AuthConfig{AuthType: "key", Credentials: nil}, matrix, SlaPolicy{})
	if err == nil || !strings.Contains(err.Error(), "adapter version is required") {
		t.Fatalf("expected version error, got %v", err)
	}

	_, err = NewProviderAdapter("cr", ProviderTypeRail, "1.0.0", AuthConfig{AuthType: "", Credentials: nil}, matrix, SlaPolicy{})
	if err == nil || !strings.Contains(err.Error(), "auth config") {
		t.Fatalf("expected auth error, got %v", err)
	}
}

func TestProviderAdapterEnableDisable(t *testing.T) {
	matrix := CapabilityMatrix{
		Version:      "1.0",
		Capabilities: map[Capability]bool{CapabilityReserve: true},
	}
	adapter, _ := NewProviderAdapter("cr", ProviderTypeRail, "1.0.0", AuthConfig{AuthType: "key"}, matrix, SlaPolicy{})

	if err := adapter.Disable(); err != nil {
		t.Fatalf("unexpected disable error: %v", err)
	}
	if adapter.Status != ProviderAdapterStatusDisabled {
		t.Fatalf("expected disabled, got %s", adapter.Status)
	}

	if err := adapter.Enable(); err == nil {
		t.Fatalf("expected error enabling a disabled adapter")
	}
}

func TestProviderAdapterUpdateCapabilityMatrix(t *testing.T) {
	matrix := CapabilityMatrix{
		Version:      "1.0",
		Capabilities: map[Capability]bool{CapabilityReserve: true},
	}
	adapter, _ := NewProviderAdapter("cr", ProviderTypeRail, "1.0.0", AuthConfig{AuthType: "key"}, matrix, SlaPolicy{})

	newMatrix := CapabilityMatrix{
		Version: "2.0",
		Capabilities: map[Capability]bool{
			CapabilityReserve:           true,
			CapabilityCancelReservation: true,
		},
	}
	if err := adapter.UpdateCapabilityMatrix(newMatrix); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if adapter.CapabilityMatrix.Version != "2.0" {
		t.Fatalf("expected version 2.0, got %s", adapter.CapabilityMatrix.Version)
	}
}

func TestProviderAdapterRejectsUnsupportedCapability(t *testing.T) {
	matrix := CapabilityMatrix{
		Version: "1.0",
		Capabilities: map[Capability]bool{
			CapabilityReserve:   true,
			"UnknownCapability": true,
		},
	}
	_, err := NewProviderAdapter("cr", ProviderTypeRail, "1.0.0", AuthConfig{AuthType: "key"}, matrix, SlaPolicy{})
	if err == nil || !strings.Contains(err.Error(), "unsupported capability") {
		t.Fatalf("expected unsupported capability error, got %v", err)
	}
}

func TestProviderAdapterRejectsUnsupportedProviderType(t *testing.T) {
	matrix := CapabilityMatrix{
		Version:      "1.0",
		Capabilities: map[Capability]bool{CapabilityReserve: true},
	}
	_, err := NewProviderAdapter("test", ProviderType("UNKNOWN"), "1.0.0", AuthConfig{AuthType: "key"}, matrix, SlaPolicy{})
	if err == nil || !strings.Contains(err.Error(), "unsupported provider type") {
		t.Fatalf("expected unsupported provider type error, got %v", err)
	}
}

func TestProviderAdapterUpdateSlaPolicy(t *testing.T) {
	matrix := CapabilityMatrix{
		Version:      "1.0",
		Capabilities: map[Capability]bool{CapabilityReserve: true},
	}
	adapter, _ := NewProviderAdapter("cr", ProviderTypeRail, "1.0.0", AuthConfig{AuthType: "key"}, matrix, SlaPolicy{})

	newPolicy := SlaPolicy{P95LatencyMs: 1000, SuccessRate: 0.95, MaxConcurrency: 50}
	adapter.UpdateSlaPolicy(newPolicy)
	if adapter.SlaPolicy.P95LatencyMs != 1000 {
		t.Fatalf("expected 1000ms latency, got %d", adapter.SlaPolicy.P95LatencyMs)
	}
}

func TestProviderAdapterRegisteredAtSet(t *testing.T) {
	matrix := CapabilityMatrix{
		Version:      "1.0",
		Capabilities: map[Capability]bool{CapabilityReserve: true},
	}
	adapter, _ := NewProviderAdapter("cr", ProviderTypeRail, "1.0.0", AuthConfig{AuthType: "key"}, matrix, SlaPolicy{})
	if adapter.RegisteredAt.IsZero() {
		t.Fatal("expected registered at to be set")
	}
}
