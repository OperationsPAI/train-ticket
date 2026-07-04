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
