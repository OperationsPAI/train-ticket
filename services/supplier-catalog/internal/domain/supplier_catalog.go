package domain

import (
	"fmt"
	"strings"
	"time"
)

// ---------------------------------------------------------------------------
// ID types
// ---------------------------------------------------------------------------

// SupplierID is the canonical ID for a supplier. Format: sup-<uuid>.
type SupplierID string

// CarrierID is the canonical ID for a carrier. Format: car-<uuid>.
type CarrierID string

// ContractID is the canonical ID for a contract. Format: ctr-<uuid>.
type ContractID string

// ProductCapabilityID is the canonical ID for a product capability declaration. Format: pcap-<uuid>.
type ProductCapabilityID string

// ExternalCodeID is the canonical ID for an external-code mapping. Format: ec-<uuid>.
type ExternalCodeID string

// CorrelationID is a cross-context transaction correlation identifier.
type CorrelationID string

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

// SupplierStatus represents the lifecycle state of a supplier.
type SupplierStatus string

const (
	SupplierStatusDraft       SupplierStatus = "DRAFT"
	SupplierStatusUnderReview SupplierStatus = "UNDER_REVIEW"
	SupplierStatusActive      SupplierStatus = "ACTIVE"
	SupplierStatusSuspended   SupplierStatus = "SUSPENDED"
	SupplierStatusInactive    SupplierStatus = "INACTIVE"
	SupplierStatusRejected    SupplierStatus = "REJECTED"
	SupplierStatusArchived    SupplierStatus = "ARCHIVED"
)

// ContractStatus represents the lifecycle state of a contract.
type ContractStatus string

const (
	ContractStatusDraft       ContractStatus = "DRAFT"
	ContractStatusValidating  ContractStatus = "VALIDATING"
	ContractStatusPublished   ContractStatus = "PUBLISHED"
	ContractStatusSuspended   ContractStatus = "SUSPENDED"
	ContractStatusSuperseded  ContractStatus = "SUPERSEDED"
	ContractStatusTerminated  ContractStatus = "TERMINATED"
	ContractStatusExpired     ContractStatus = "EXPIRED"
)

// ProductCapabilityStatus represents the lifecycle state of a product capability.
type ProductCapabilityStatus string

const (
	ProductCapabilityStatusDraft     ProductCapabilityStatus = "DRAFT"
	ProductCapabilityStatusValidated ProductCapabilityStatus = "VALIDATED"
	ProductCapabilityStatusPublished ProductCapabilityStatus = "PUBLISHED"
	ProductCapabilityStatusRetired   ProductCapabilityStatus = "RETIRED"
)

// ExternalCodeType represents the type of external code being mapped.
type ExternalCodeType string

const (
	ExternalCodeTypeStation     ExternalCodeType = "STATION"
	ExternalCodeTypeServiceClass ExternalCodeType = "SERVICE_CLASS"
	ExternalCodeTypeFareFamily  ExternalCodeType = "FARE_FAMILY"
	ExternalCodeTypeProduct     ExternalCodeType = "PRODUCT"
	ExternalCodeTypeAncillary   ExternalCodeType = "ANCILLARY"
	ExternalCodeTypeRule        ExternalCodeType = "RULE"
)

// ExternalCodeMappingStatus represents the state of an external-code mapping.
type ExternalCodeMappingStatus string

const (
	ExternalCodeMappingStatusActive   ExternalCodeMappingStatus = "ACTIVE"
	ExternalCodeMappingStatusConflict ExternalCodeMappingStatus = "CONFLICT"
	ExternalCodeMappingStatusRetired  ExternalCodeMappingStatus = "RETIRED"
)

// ---------------------------------------------------------------------------
// Value objects
// ---------------------------------------------------------------------------

// TimeWindow is a closed-open interval [start, end).
type TimeWindow struct {
	Start time.Time
	End   time.Time
}

// Validate checks TimeWindow invariants.
func (tw TimeWindow) Validate() error {
	if tw.Start.IsZero() {
		return fmt.Errorf("time window start is required")
	}
	if tw.End.IsZero() {
		return fmt.Errorf("time window end is required")
	}
	if !tw.End.After(tw.Start) {
		return fmt.Errorf("time window end must be after start")
	}
	return nil
}

// Money represents an amount in a given currency using minor units.
type Money struct {
	Currency   string `json:"currency"`
	MinorUnits int64  `json:"minorUnits"`
}

// Validate checks Money invariants.
func (m Money) Validate() error {
	code := strings.TrimSpace(m.Currency)
	if len(code) != 3 {
		return fmt.Errorf("currency must be exactly 3 uppercase letters, got %q", code)
	}
	for _, c := range code {
		if c < 'A' || c > 'Z' {
			return fmt.Errorf("currency must be uppercase ASCII, got %q", code)
		}
	}
	return nil
}

// ---------------------------------------------------------------------------
// Supplier aggregate
// ---------------------------------------------------------------------------

// Supplier is the aggregate root for supplier master data.
type Supplier struct {
	SupplierID   SupplierID     `json:"supplierId"`
	LegalName    string         `json:"legalName"`
	BrandName    string         `json:"brandName"`
	Status       SupplierStatus `json:"status"`
	Profile      string         `json:"profile"`
	RegisteredAt time.Time      `json:"registeredAt"`
	UpdatedAt    time.Time      `json:"updatedAt"`
	events       []interface{}  `json:"-"`
}

// NewSupplier creates a validated Supplier aggregate.
func NewSupplier(id SupplierID, legalName, brandName, profile string) (Supplier, error) {
	s := Supplier{
		SupplierID:   SupplierID(strings.TrimSpace(string(id))),
		LegalName:    strings.TrimSpace(legalName),
		BrandName:    strings.TrimSpace(brandName),
		Status:       SupplierStatusDraft,
		Profile:      strings.TrimSpace(profile),
		RegisteredAt: timeNow().UTC(),
		UpdatedAt:    timeNow().UTC(),
	}
	if err := s.Validate(); err != nil {
		return Supplier{}, err
	}
	s.events = append(s.events, SupplierRegisteredEvent{
		SupplierID:   s.SupplierID,
		LegalName:    s.LegalName,
		BrandName:    s.BrandName,
		Status:       s.Status,
		RegisteredAt: s.RegisteredAt,
	})
	return s, nil
}

// Validate checks Supplier invariants.
func (s Supplier) Validate() error {
	if strings.TrimSpace(string(s.SupplierID)) == "" {
		return fmt.Errorf("supplier id is required")
	}
	if s.LegalName == "" {
		return fmt.Errorf("legal name is required")
	}
	if s.BrandName == "" {
		return fmt.Errorf("brand name is required")
	}
	if !validSupplierStatus(s.Status) {
		return fmt.Errorf("unsupported supplier status: %q", s.Status)
	}
	return nil
}

// SubmitForReview transitions the supplier from DRAFT to UNDER_REVIEW.
func (s *Supplier) SubmitForReview() error {
	if s.Status != SupplierStatusDraft {
		return fmt.Errorf("cannot submit supplier %q for review: current status is %q", s.SupplierID, s.Status)
	}
	s.Status = SupplierStatusUnderReview
	s.UpdatedAt = timeNow().UTC()
	return nil
}

// Approve transitions the supplier from UNDER_REVIEW to ACTIVE.
func (s *Supplier) Approve() error {
	if s.Status != SupplierStatusUnderReview {
		return fmt.Errorf("cannot approve supplier %q: current status is %q", s.SupplierID, s.Status)
	}
	s.Status = SupplierStatusActive
	s.UpdatedAt = timeNow().UTC()
	return nil
}

// Reject transitions the supplier from UNDER_REVIEW to REJECTED.
func (s *Supplier) Reject() error {
	if s.Status != SupplierStatusUnderReview {
		return fmt.Errorf("cannot reject supplier %q: current status is %q", s.SupplierID, s.Status)
	}
	s.Status = SupplierStatusRejected
	s.UpdatedAt = timeNow().UTC()
	return nil
}

// Suspend transitions the supplier to SUSPENDED.
func (s *Supplier) Suspend() error {
	if s.Status != SupplierStatusActive {
		return fmt.Errorf("cannot suspend supplier %q: current status is %q", s.SupplierID, s.Status)
	}
	s.Status = SupplierStatusSuspended
	s.UpdatedAt = timeNow().UTC()
	return nil
}

// Reactivate transitions the supplier from SUSPENDED back to ACTIVE.
func (s *Supplier) Reactivate() error {
	if s.Status != SupplierStatusSuspended {
		return fmt.Errorf("cannot reactivate supplier %q: current status is %q", s.SupplierID, s.Status)
	}
	s.Status = SupplierStatusActive
	s.UpdatedAt = timeNow().UTC()
	return nil
}

// Events returns the recorded domain events and clears the internal buffer.
func (s *Supplier) Events() []interface{} {
	events := s.events
	s.events = nil
	return events
}

func validSupplierStatus(s SupplierStatus) bool {
	switch s {
	case SupplierStatusDraft, SupplierStatusUnderReview, SupplierStatusActive,
		SupplierStatusSuspended, SupplierStatusInactive, SupplierStatusRejected,
		SupplierStatusArchived:
		return true
	default:
		return false
	}
}

// ---------------------------------------------------------------------------
// Carrier aggregate
// ---------------------------------------------------------------------------

// Carrier represents a carrier (e.g. a railway bureau, airline, coach company).
type Carrier struct {
	CarrierID     CarrierID  `json:"carrierId"`
	SupplierID    SupplierID `json:"supplierId"`
	Name          string     `json:"name"`
	Code          string     `json:"code"`
	TransportMode string    `json:"transportMode"`
	RegisteredAt  time.Time  `json:"registeredAt"`
	UpdatedAt     time.Time  `json:"updatedAt"`
	events        []interface{} `json:"-"`
}

// NewCarrier creates a validated Carrier aggregate.
func NewCarrier(id CarrierID, supplierID SupplierID, name, code, transportMode string) (Carrier, error) {
	c := Carrier{
		CarrierID:     CarrierID(strings.TrimSpace(string(id))),
		SupplierID:    SupplierID(strings.TrimSpace(string(supplierID))),
		Name:          strings.TrimSpace(name),
		Code:          strings.TrimSpace(code),
		TransportMode: strings.TrimSpace(transportMode),
		RegisteredAt:  timeNow().UTC(),
		UpdatedAt:     timeNow().UTC(),
	}
	if err := c.Validate(); err != nil {
		return Carrier{}, err
	}
	c.events = append(c.events, CarrierRegisteredEvent{
		CarrierID:     c.CarrierID,
		SupplierID:    c.SupplierID,
		Name:          c.Name,
		Code:          c.Code,
		TransportMode: c.TransportMode,
		RegisteredAt:  c.RegisteredAt,
	})
	return c, nil
}

// Validate checks Carrier invariants.
func (c Carrier) Validate() error {
	if strings.TrimSpace(string(c.CarrierID)) == "" {
		return fmt.Errorf("carrier id is required")
	}
	if strings.TrimSpace(string(c.SupplierID)) == "" {
		return fmt.Errorf("supplier id is required")
	}
	if c.Name == "" {
		return fmt.Errorf("carrier name is required")
	}
	if c.Code == "" {
		return fmt.Errorf("carrier code is required")
	}
	if c.TransportMode == "" {
		return fmt.Errorf("transport mode is required")
	}
	return nil
}

// Events returns the recorded domain events and clears the internal buffer.
func (c *Carrier) Events() []interface{} {
	events := c.events
	c.events = nil
	return events
}

// ---------------------------------------------------------------------------
// Contract aggregate
// ---------------------------------------------------------------------------

// Contract is the aggregate root for supplier contracts.
type Contract struct {
	ContractID   ContractID     `json:"contractId"`
	SupplierID   SupplierID     `json:"supplierId"`
	ContractNo   string         `json:"contractNo"`
	Status       ContractStatus `json:"status"`
	ValidWindow  TimeWindow     `json:"validWindow"`
	Description  string         `json:"description"`
	RegisteredAt time.Time      `json:"registeredAt"`
	UpdatedAt    time.Time      `json:"updatedAt"`
	events       []interface{}  `json:"-"`
}

// NewContract creates a validated Contract aggregate.
func NewContract(id ContractID, supplierID SupplierID, contractNo string, validWindow TimeWindow, description string) (Contract, error) {
	c := Contract{
		ContractID:   ContractID(strings.TrimSpace(string(id))),
		SupplierID:   SupplierID(strings.TrimSpace(string(supplierID))),
		ContractNo:   strings.TrimSpace(contractNo),
		Status:       ContractStatusDraft,
		ValidWindow:  validWindow,
		Description:  strings.TrimSpace(description),
		RegisteredAt: timeNow().UTC(),
		UpdatedAt:    timeNow().UTC(),
	}
	if err := c.Validate(); err != nil {
		return Contract{}, err
	}
	c.events = append(c.events, ContractRegisteredEvent{
		ContractID:   c.ContractID,
		SupplierID:   c.SupplierID,
		ContractNo:   c.ContractNo,
		Status:       c.Status,
		ValidWindow:  c.ValidWindow,
		Description:  c.Description,
		RegisteredAt: c.RegisteredAt,
	})
	return c, nil
}

// Validate checks Contract invariants.
func (c Contract) Validate() error {
	if strings.TrimSpace(string(c.ContractID)) == "" {
		return fmt.Errorf("contract id is required")
	}
	if strings.TrimSpace(string(c.SupplierID)) == "" {
		return fmt.Errorf("supplier id is required")
	}
	if c.ContractNo == "" {
		return fmt.Errorf("contract number is required")
	}
	if !validContractStatus(c.Status) {
		return fmt.Errorf("unsupported contract status: %q", c.Status)
	}
	if err := c.ValidWindow.Validate(); err != nil {
		return fmt.Errorf("invalid valid window: %w", err)
	}
	return nil
}

// Activate transitions the contract from DRAFT/VALIDATING to PUBLISHED.
func (c *Contract) Activate() error {
	if c.Status != ContractStatusDraft && c.Status != ContractStatusValidating {
		return fmt.Errorf("cannot activate contract %q: current status is %q", c.ContractID, c.Status)
	}
	c.Status = ContractStatusPublished
	c.UpdatedAt = timeNow().UTC()
	c.events = append(c.events, ContractActivatedEvent{
		ContractID:  c.ContractID,
		SupplierID:  c.SupplierID,
		ContractNo:  c.ContractNo,
		ValidWindow: c.ValidWindow,
		ActivatedAt: c.UpdatedAt,
	})
	return nil
}

// Suspend transitions the contract from PUBLISHED to SUSPENDED.
func (c *Contract) Suspend() error {
	if c.Status != ContractStatusPublished {
		return fmt.Errorf("cannot suspend contract %q: current status is %q", c.ContractID, c.Status)
	}
	c.Status = ContractStatusSuspended
	c.UpdatedAt = timeNow().UTC()
	c.events = append(c.events, ContractSuspendedEvent{
		ContractID:  c.ContractID,
		SupplierID:  c.SupplierID,
		ContractNo:  c.ContractNo,
		SuspendedAt: c.UpdatedAt,
	})
	return nil
}

// Events returns the recorded domain events and clears the internal buffer.
func (c *Contract) Events() []interface{} {
	events := c.events
	c.events = nil
	return events
}

func validContractStatus(s ContractStatus) bool {
	switch s {
	case ContractStatusDraft, ContractStatusValidating, ContractStatusPublished,
		ContractStatusSuspended, ContractStatusSuperseded, ContractStatusTerminated,
		ContractStatusExpired:
		return true
	default:
		return false
	}
}

// ---------------------------------------------------------------------------
// ProductCapability aggregate
// ---------------------------------------------------------------------------

// ProductCapability is the aggregate root for product capability declarations.
type ProductCapability struct {
	CapabilityID ProductCapabilityID     `json:"capabilityId"`
	SupplierID   SupplierID              `json:"supplierId"`
	ContractID   ContractID              `json:"contractId"`
	ProductName  string                  `json:"productName"`
	Capability   string                  `json:"capability"`
	Status       ProductCapabilityStatus `json:"status"`
	ValidWindow  TimeWindow              `json:"validWindow"`
	DeclaredAt   time.Time               `json:"declaredAt"`
	UpdatedAt    time.Time               `json:"updatedAt"`
	events       []interface{}           `json:"-"`
}

// NewProductCapability creates a validated ProductCapability aggregate.
// The capability's valid window must fall within the contract's valid window.
func NewProductCapability(
	id ProductCapabilityID,
	supplierID SupplierID,
	contractID ContractID,
	productName, capability string,
	validWindow TimeWindow,
) (ProductCapability, error) {
	pc := ProductCapability{
		CapabilityID: ProductCapabilityID(strings.TrimSpace(string(id))),
		SupplierID:   SupplierID(strings.TrimSpace(string(supplierID))),
		ContractID:   ContractID(strings.TrimSpace(string(contractID))),
		ProductName:  strings.TrimSpace(productName),
		Capability:   strings.TrimSpace(capability),
		Status:       ProductCapabilityStatusDraft,
		ValidWindow:  validWindow,
		DeclaredAt:   timeNow().UTC(),
		UpdatedAt:    timeNow().UTC(),
	}
	if err := pc.Validate(); err != nil {
		return ProductCapability{}, err
	}
	pc.events = append(pc.events, ProductCapabilityDeclaredEvent{
		CapabilityID: pc.CapabilityID,
		SupplierID:   pc.SupplierID,
		ContractID:   pc.ContractID,
		ProductName:  pc.ProductName,
		Capability:   pc.Capability,
		Status:       pc.Status,
		ValidWindow:  pc.ValidWindow,
		DeclaredAt:   pc.DeclaredAt,
	})
	return pc, nil
}

// Validate checks ProductCapability invariants.
func (pc ProductCapability) Validate() error {
	if strings.TrimSpace(string(pc.CapabilityID)) == "" {
		return fmt.Errorf("capability id is required")
	}
	if strings.TrimSpace(string(pc.SupplierID)) == "" {
		return fmt.Errorf("supplier id is required")
	}
	if strings.TrimSpace(string(pc.ContractID)) == "" {
		return fmt.Errorf("contract id is required")
	}
	if pc.ProductName == "" {
		return fmt.Errorf("product name is required")
	}
	if pc.Capability == "" {
		return fmt.Errorf("capability name is required")
	}
	if !validProductCapabilityStatus(pc.Status) {
		return fmt.Errorf("unsupported product capability status: %q", pc.Status)
	}
	if err := pc.ValidWindow.Validate(); err != nil {
		return fmt.Errorf("invalid valid window: %w", err)
	}
	return nil
}

// ValidateContractWindow checks that the capability's valid window falls within
// the given contract window. This enforces the invariant that a product capability
// must reference the supplier's contract validity window.
func (pc ProductCapability) ValidateContractWindow(contractWindow TimeWindow) error {
	if pc.ValidWindow.Start.Before(contractWindow.Start) {
		return fmt.Errorf("capability window start %v is before contract window start %v", pc.ValidWindow.Start, contractWindow.Start)
	}
	if pc.ValidWindow.End.After(contractWindow.End) {
		return fmt.Errorf("capability window end %v is after contract window end %v", pc.ValidWindow.End, contractWindow.End)
	}
	return nil
}

// Publish transitions the capability from DRAFT to PUBLISHED.
func (pc *ProductCapability) Publish() error {
	if pc.Status != ProductCapabilityStatusDraft && pc.Status != ProductCapabilityStatusValidated {
		return fmt.Errorf("cannot publish capability %q: current status is %q", pc.CapabilityID, pc.Status)
	}
	pc.Status = ProductCapabilityStatusPublished
	pc.UpdatedAt = timeNow().UTC()
	return nil
}

// Retire transitions the capability to RETIRED.
func (pc *ProductCapability) Retire() error {
	if pc.Status != ProductCapabilityStatusPublished {
		return fmt.Errorf("cannot retire capability %q: current status is %q", pc.CapabilityID, pc.Status)
	}
	pc.Status = ProductCapabilityStatusRetired
	pc.UpdatedAt = timeNow().UTC()
	return nil
}

// Events returns the recorded domain events and clears the internal buffer.
func (pc *ProductCapability) Events() []interface{} {
	events := pc.events
	pc.events = nil
	return events
}

func validProductCapabilityStatus(s ProductCapabilityStatus) bool {
	switch s {
	case ProductCapabilityStatusDraft, ProductCapabilityStatusValidated,
		ProductCapabilityStatusPublished, ProductCapabilityStatusRetired:
		return true
	default:
		return false
	}
}

// ---------------------------------------------------------------------------
// ExternalCode aggregate
// ---------------------------------------------------------------------------

// ExternalCode represents a mapping from an external code to an internal identifier.
// Mappings are unique per supplier + code type. Conflicting mappings are recorded
// as gaps (CONFLICT status), not overwritten.
type ExternalCode struct {
	CodeID       ExternalCodeID            `json:"codeId"`
	SupplierID   SupplierID                `json:"supplierId"`
	CodeType     ExternalCodeType          `json:"codeType"`
	ExternalCode string                    `json:"externalCode"`
	InternalRef  string                    `json:"internalRef"`
	Status       ExternalCodeMappingStatus `json:"status"`
	MappedAt     time.Time                 `json:"mappedAt"`
	UpdatedAt    time.Time                 `json:"updatedAt"`
	events       []interface{}             `json:"-"`
}

// NewExternalCode creates a validated ExternalCode mapping.
// If an existing mapping already exists for the same supplier + code type + external code,
// the new mapping is created with CONFLICT status.
func NewExternalCode(id ExternalCodeID, supplierID SupplierID, codeType ExternalCodeType, externalCode, internalRef string) (ExternalCode, error) {
	ec := ExternalCode{
		CodeID:       ExternalCodeID(strings.TrimSpace(string(id))),
		SupplierID:   SupplierID(strings.TrimSpace(string(supplierID))),
		CodeType:     codeType,
		ExternalCode: strings.TrimSpace(externalCode),
		InternalRef:  strings.TrimSpace(internalRef),
		Status:       ExternalCodeMappingStatusActive,
		MappedAt:     timeNow().UTC(),
		UpdatedAt:    timeNow().UTC(),
	}
	if err := ec.Validate(); err != nil {
		return ExternalCode{}, err
	}
	ec.events = append(ec.events, ExternalCodeMappedEvent{
		CodeID:       ec.CodeID,
		SupplierID:   ec.SupplierID,
		CodeType:     ec.CodeType,
		ExternalCode: ec.ExternalCode,
		InternalRef:  ec.InternalRef,
		Status:       ec.Status,
		MappedAt:     ec.MappedAt,
	})
	return ec, nil
}

// Validate checks ExternalCode invariants.
func (ec ExternalCode) Validate() error {
	if strings.TrimSpace(string(ec.CodeID)) == "" {
		return fmt.Errorf("code id is required")
	}
	if strings.TrimSpace(string(ec.SupplierID)) == "" {
		return fmt.Errorf("supplier id is required")
	}
	if ec.ExternalCode == "" {
		return fmt.Errorf("external code is required")
	}
	if ec.InternalRef == "" {
		return fmt.Errorf("internal ref is required")
	}
	if !validExternalCodeType(ec.CodeType) {
		return fmt.Errorf("unsupported external code type: %q", ec.CodeType)
	}
	if !validExternalCodeMappingStatus(ec.Status) {
		return fmt.Errorf("unsupported external code mapping status: %q", ec.Status)
	}
	return nil
}

// RecordConflict transitions an active mapping to CONFLICT status when a duplicate
// mapping is detected. The original mapping is preserved (never overwritten).
func (ec *ExternalCode) RecordConflict() error {
	if ec.Status != ExternalCodeMappingStatusActive {
		return fmt.Errorf("cannot record conflict for code %q: current status is %q", ec.CodeID, ec.Status)
	}
	ec.Status = ExternalCodeMappingStatusConflict
	ec.UpdatedAt = timeNow().UTC()
	return nil
}

// ResolveConflict transitions a conflicting mapping back to ACTIVE.
func (ec *ExternalCode) ResolveConflict() error {
	if ec.Status != ExternalCodeMappingStatusConflict {
		return fmt.Errorf("cannot resolve conflict for code %q: current status is %q", ec.CodeID, ec.Status)
	}
	ec.Status = ExternalCodeMappingStatusActive
	ec.UpdatedAt = timeNow().UTC()
	return nil
}

// Events returns the recorded domain events and clears the internal buffer.
func (ec *ExternalCode) Events() []interface{} {
	events := ec.events
	ec.events = nil
	return events
}

func validExternalCodeType(t ExternalCodeType) bool {
	switch t {
	case ExternalCodeTypeStation, ExternalCodeTypeServiceClass, ExternalCodeTypeFareFamily,
		ExternalCodeTypeProduct, ExternalCodeTypeAncillary, ExternalCodeTypeRule:
		return true
	default:
		return false
	}
}

func validExternalCodeMappingStatus(s ExternalCodeMappingStatus) bool {
	switch s {
	case ExternalCodeMappingStatusActive, ExternalCodeMappingStatusConflict, ExternalCodeMappingStatusRetired:
		return true
	default:
		return false
	}
}
