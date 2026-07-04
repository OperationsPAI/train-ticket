package domain

import "time"

// ---------------------------------------------------------------------------
// Domain events
// ---------------------------------------------------------------------------

// SupplierRegisteredEvent is emitted when a new supplier is registered.
type SupplierRegisteredEvent struct {
	SupplierID   SupplierID     `json:"supplierId"`
	LegalName    string         `json:"legalName"`
	BrandName    string         `json:"brandName"`
	Status       SupplierStatus `json:"status"`
	RegisteredAt time.Time      `json:"registeredAt"`
}

// CarrierRegisteredEvent is emitted when a new carrier is registered.
type CarrierRegisteredEvent struct {
	CarrierID     CarrierID  `json:"carrierId"`
	SupplierID    SupplierID `json:"supplierId"`
	Name          string     `json:"name"`
	Code          string     `json:"code"`
	TransportMode string    `json:"transportMode"`
	RegisteredAt  time.Time  `json:"registeredAt"`
}

// ContractRegisteredEvent is emitted when a new contract is registered.
type ContractRegisteredEvent struct {
	ContractID   ContractID     `json:"contractId"`
	SupplierID   SupplierID     `json:"supplierId"`
	ContractNo   string         `json:"contractNo"`
	Status       ContractStatus `json:"status"`
	ValidWindow  TimeWindow     `json:"validWindow"`
	Description  string         `json:"description"`
	RegisteredAt time.Time      `json:"registeredAt"`
}

// ContractActivatedEvent is emitted when a contract is activated (published).
type ContractActivatedEvent struct {
	ContractID  ContractID `json:"contractId"`
	SupplierID  SupplierID `json:"supplierId"`
	ContractNo  string     `json:"contractNo"`
	ValidWindow TimeWindow `json:"validWindow"`
	ActivatedAt time.Time  `json:"activatedAt"`
}

// ContractSuspendedEvent is emitted when a contract is suspended.
type ContractSuspendedEvent struct {
	ContractID  ContractID `json:"contractId"`
	SupplierID  SupplierID `json:"supplierId"`
	ContractNo  string     `json:"contractNo"`
	SuspendedAt time.Time  `json:"suspendedAt"`
}

// ProductCapabilityDeclaredEvent is emitted when a product capability is declared.
type ProductCapabilityDeclaredEvent struct {
	CapabilityID ProductCapabilityID     `json:"capabilityId"`
	SupplierID   SupplierID              `json:"supplierId"`
	ContractID   ContractID              `json:"contractId"`
	ProductName  string                  `json:"productName"`
	Capability   string                  `json:"capability"`
	Status       ProductCapabilityStatus `json:"status"`
	ValidWindow  TimeWindow              `json:"validWindow"`
	DeclaredAt   time.Time               `json:"declaredAt"`
}

// ExternalCodeMappedEvent is emitted when an external code mapping is created.
type ExternalCodeMappedEvent struct {
	CodeID       ExternalCodeID            `json:"codeId"`
	SupplierID   SupplierID                `json:"supplierId"`
	CodeType     ExternalCodeType          `json:"codeType"`
	ExternalCode string                    `json:"externalCode"`
	InternalRef  string                    `json:"internalRef"`
	Status       ExternalCodeMappingStatus `json:"status"`
	MappedAt     time.Time                 `json:"mappedAt"`
}

// ---------------------------------------------------------------------------
// EventEnvelope -- contract-conformant event wrapper
// ---------------------------------------------------------------------------

// EventEnvelope wraps every published domain event with contract-conformant
// metadata per docs/08-contracts/shared-primitives.md.
type EventEnvelope struct {
	EventID       string  `json:"eventId"`
	EventType     string  `json:"eventType"`
	SchemaVersion uint32  `json:"schemaVersion"`
	OccurredAt    string  `json:"occurredAt"` // RFC3339 UTC
	CorrelationID string  `json:"correlationId"`
	CausationID   *string `json:"causationId,omitempty"`
	Producer      string  `json:"producer"`
}

// NewEventEnvelope creates a new EventEnvelope with a generated event ID and
// the current time as RFC3339 UTC.
func NewEventEnvelope(eventType string, occurredAt time.Time, correlationID string, causationID *string, producer string) EventEnvelope {
	env := EventEnvelope{
		EventID:       "evt-" + occurredAt.Format("150405000000000"),
		EventType:     eventType,
		SchemaVersion: 1,
		OccurredAt:    occurredAt.UTC().Format(time.RFC3339Nano),
		CorrelationID: correlationID,
		Producer:      producer,
	}
	if causationID != nil {
		env.CausationID = causationID
	}
	return env
}

// WrapDomainEvent wraps a domain event payload into a map that includes the
// envelope fields alongside a "data" key containing the payload.
func WrapDomainEvent(env EventEnvelope, payload interface{}) map[string]interface{} {
	return map[string]interface{}{
		"eventId":       env.EventID,
		"eventType":     env.EventType,
		"schemaVersion": env.SchemaVersion,
		"occurredAt":    env.OccurredAt,
		"correlationId": env.CorrelationID,
		"causationId":   env.CausationID,
		"producer":      env.Producer,
		"data":          payload,
	}
}
