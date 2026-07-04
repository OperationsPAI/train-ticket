package domain

import (
	"fmt"
	"strings"
	"time"
)

// AdapterVersion identifies a specific version of a provider adapter.
type AdapterVersion string

// ProviderAdapterStatus represents the operational state of an adapter.
type ProviderAdapterStatus string

const (
	ProviderAdapterStatusActive   ProviderAdapterStatus = "ACTIVE"
	ProviderAdapterStatusInactive ProviderAdapterStatus = "INACTIVE"
	ProviderAdapterStatusDisabled ProviderAdapterStatus = "DISABLED"
)

// Capability describes a single provider capability supported by an adapter.
type Capability string

const (
	CapabilitySearchAvailability     Capability = "SearchAvailability"
	CapabilityQuoteProviderOffer     Capability = "QuoteProviderOffer"
	CapabilityReserve                Capability = "Reserve"
	CapabilityConfirmReservation     Capability = "ConfirmReservation"
	CapabilityCancelReservation      Capability = "CancelReservation"
	CapabilityIssueCredential        Capability = "IssueCredential"
	CapabilityVoidCredential         Capability = "VoidCredential"
	CapabilityChangeReservation      Capability = "ChangeReservation"
	CapabilityQueryReservationStatus Capability = "QueryReservationStatus"
	CapabilityQueryDisruptions       Capability = "QueryDisruptions"
	CapabilityReconcileSettlement    Capability = "ReconcileSettlement"
	CapabilityReceiveWebhook         Capability = "ReceiveWebhook"
)

// ProviderType categorises the kind of external provider.
type ProviderType string

const (
	ProviderTypeRail        ProviderType = "RAIL"
	ProviderTypeAir         ProviderType = "AIR"
	ProviderTypeCoach       ProviderType = "COACH"
	ProviderTypeFerry       ProviderType = "FERRY"
	ProviderTypeRideHailing ProviderType = "RIDE_HAILING"
	ProviderTypePayment     ProviderType = "PAYMENT"
	ProviderTypeInsurance   ProviderType = "INSURANCE"
)

// AuthConfig captures authentication configuration for a provider adapter.
type AuthConfig struct {
	AuthType    string
	Credentials map[string]string
}

// SlaPolicy defines service-level agreement parameters for an adapter.
type SlaPolicy struct {
	P95LatencyMs   int
	SuccessRate    float64
	MaxConcurrency int
	DailyQuota     int
	MaintenanceWin string
}

// CapabilityMatrix declares which capabilities a provider supports.
type CapabilityMatrix struct {
	Version      string
	Capabilities map[Capability]bool
}

// ProviderAdapter is the aggregate root for a provider's integration adapter.
// It owns registration, version, capability declaration, health status,
// authentication configuration, and SLA policy.
type ProviderAdapter struct {
	ProviderID        ProviderID
	ProviderType      ProviderType
	AdapterVersion    AdapterVersion
	Status            ProviderAdapterStatus
	AuthConfig        AuthConfig
	CapabilityMatrix  CapabilityMatrix
	SlaPolicy         SlaPolicy
	HealthStatus      string
	RegisteredAt      time.Time
	LastHealthCheckAt *time.Time
}

// NewProviderAdapter creates a validated ProviderAdapter aggregate.
func NewProviderAdapter(
	providerID ProviderID,
	providerType ProviderType,
	adapterVersion AdapterVersion,
	authConfig AuthConfig,
	capabilityMatrix CapabilityMatrix,
	slaPolicy SlaPolicy,
) (ProviderAdapter, error) {
	adapter := ProviderAdapter{
		ProviderID:       ProviderID(strings.TrimSpace(string(providerID))),
		ProviderType:     providerType,
		AdapterVersion:   AdapterVersion(strings.TrimSpace(string(adapterVersion))),
		Status:           ProviderAdapterStatusActive,
		AuthConfig:       authConfig,
		CapabilityMatrix: capabilityMatrix,
		SlaPolicy:        slaPolicy,
		HealthStatus:     "unknown",
		RegisteredAt:     timeNow().UTC(),
	}
	if err := adapter.Validate(); err != nil {
		return ProviderAdapter{}, err
	}
	return adapter, nil
}

// Validate checks ProviderAdapter invariants.
func (a ProviderAdapter) Validate() error {
	if strings.TrimSpace(string(a.ProviderID)) == "" {
		return fmt.Errorf("provider id is required")
	}
	if !validProviderType(a.ProviderType) {
		return fmt.Errorf("unsupported provider type: %q", a.ProviderType)
	}
	if strings.TrimSpace(string(a.AdapterVersion)) == "" {
		return fmt.Errorf("adapter version is required")
	}
	if !validProviderAdapterStatus(a.Status) {
		return fmt.Errorf("unsupported provider adapter status: %q", a.Status)
	}
	if a.AuthConfig.AuthType == "" {
		return fmt.Errorf("auth config type is required for an active adapter")
	}
	if len(a.CapabilityMatrix.Capabilities) == 0 {
		return fmt.Errorf("capability matrix must declare at least one capability")
	}
	for cap := range a.CapabilityMatrix.Capabilities {
		if !validCapability(cap) {
			return fmt.Errorf("unsupported capability: %q", cap)
		}
	}
	return nil
}

// Enable transitions the adapter to active status.
func (a *ProviderAdapter) Enable() error {
	if a.Status == ProviderAdapterStatusDisabled {
		return fmt.Errorf("cannot enable a disabled adapter; re-registration required")
	}
	a.Status = ProviderAdapterStatusActive
	return nil
}

// Disable transitions the adapter to disabled status. No new commands are accepted.
func (a *ProviderAdapter) Disable() error {
	if a.Status == ProviderAdapterStatusDisabled {
		return fmt.Errorf("adapter is already disabled")
	}
	a.Status = ProviderAdapterStatusDisabled
	return nil
}

// UpdateCapabilityMatrix replaces the current capability matrix.
func (a *ProviderAdapter) UpdateCapabilityMatrix(matrix CapabilityMatrix) error {
	if len(matrix.Capabilities) == 0 {
		return fmt.Errorf("capability matrix must declare at least one capability")
	}
	for cap := range matrix.Capabilities {
		if !validCapability(cap) {
			return fmt.Errorf("unsupported capability: %q", cap)
		}
	}
	a.CapabilityMatrix = matrix
	return nil
}

// UpdateSlaPolicy replaces the current SLA policy.
func (a *ProviderAdapter) UpdateSlaPolicy(policy SlaPolicy) {
	a.SlaPolicy = policy
}

func validProviderType(value ProviderType) bool {
	switch value {
	case ProviderTypeRail, ProviderTypeAir, ProviderTypeCoach, ProviderTypeFerry,
		ProviderTypeRideHailing, ProviderTypePayment, ProviderTypeInsurance:
		return true
	default:
		return false
	}
}

func validProviderAdapterStatus(value ProviderAdapterStatus) bool {
	switch value {
	case ProviderAdapterStatusActive, ProviderAdapterStatusInactive, ProviderAdapterStatusDisabled:
		return true
	default:
		return false
	}
}

func validCapability(value Capability) bool {
	switch value {
	case CapabilitySearchAvailability, CapabilityQuoteProviderOffer, CapabilityReserve,
		CapabilityConfirmReservation, CapabilityCancelReservation, CapabilityIssueCredential,
		CapabilityVoidCredential, CapabilityChangeReservation, CapabilityQueryReservationStatus,
		CapabilityQueryDisruptions, CapabilityReconcileSettlement, CapabilityReceiveWebhook:
		return true
	default:
		return false
	}
}
