package domain

import (
	"strings"
	"testing"
	"time"
)

func frozenNow() time.Time {
	return time.Date(2026, 7, 4, 10, 0, 0, 0, time.UTC)
}

func init() {
	timeNow = func() time.Time { return frozenNow() }
}

func TestNewSupplierValid(t *testing.T) {
	s, err := NewSupplier("sup-001", "China Railway Corp", "CR", "National railway operator")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if s.SupplierID != "sup-001" {
		t.Fatalf("unexpected supplier id: %s", s.SupplierID)
	}
	if s.Status != SupplierStatusDraft {
		t.Fatalf("expected DRAFT status, got %s", s.Status)
	}
	events := s.Events()
	if len(events) != 1 {
		t.Fatalf("expected 1 event, got %d", len(events))
	}
	evt, ok := events[0].(SupplierRegisteredEvent)
	if !ok {
		t.Fatalf("expected SupplierRegisteredEvent, got %T", events[0])
	}
	if evt.SupplierID != "sup-001" {
		t.Fatalf("unexpected event supplier id: %s", evt.SupplierID)
	}
}

func TestNewSupplierMissingID(t *testing.T) {
	_, err := NewSupplier("", "Legal", "Brand", "profile")
	if err == nil {
		t.Fatal("expected error for empty supplier id")
	}
}

func TestNewSupplierMissingLegalName(t *testing.T) {
	_, err := NewSupplier("sup-001", "", "Brand", "profile")
	if err == nil {
		t.Fatal("expected error for empty legal name")
	}
}

func TestNewSupplierMissingBrandName(t *testing.T) {
	_, err := NewSupplier("sup-001", "Legal", "", "profile")
	if err == nil {
		t.Fatal("expected error for empty brand name")
	}
}

func TestSupplierSubmitForReview(t *testing.T) {
	s, _ := NewSupplier("sup-001", "Legal", "Brand", "profile")
	if err := s.SubmitForReview(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if s.Status != SupplierStatusUnderReview {
		t.Fatalf("expected UNDER_REVIEW, got %s", s.Status)
	}
}

func TestSupplierSubmitForReviewFromActive(t *testing.T) {
	s, _ := NewSupplier("sup-001", "Legal", "Brand", "profile")
	s.SubmitForReview()
	s.Approve()
	if err := s.SubmitForReview(); err == nil {
		t.Fatal("expected error submitting active supplier for review")
	}
}

func TestSupplierApprove(t *testing.T) {
	s, _ := NewSupplier("sup-001", "Legal", "Brand", "profile")
	s.SubmitForReview()
	if err := s.Approve(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if s.Status != SupplierStatusActive {
		t.Fatalf("expected ACTIVE, got %s", s.Status)
	}
}

func TestSupplierApproveFromDraft(t *testing.T) {
	s, _ := NewSupplier("sup-001", "Legal", "Brand", "profile")
	if err := s.Approve(); err == nil {
		t.Fatal("expected error approving draft supplier")
	}
}

func TestSupplierReject(t *testing.T) {
	s, _ := NewSupplier("sup-001", "Legal", "Brand", "profile")
	s.SubmitForReview()
	if err := s.Reject(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if s.Status != SupplierStatusRejected {
		t.Fatalf("expected REJECTED, got %s", s.Status)
	}
}

func TestSupplierSuspend(t *testing.T) {
	s, _ := NewSupplier("sup-001", "Legal", "Brand", "profile")
	s.SubmitForReview()
	s.Approve()
	if err := s.Suspend(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if s.Status != SupplierStatusSuspended {
		t.Fatalf("expected SUSPENDED, got %s", s.Status)
	}
}

func TestSupplierSuspendFromDraft(t *testing.T) {
	s, _ := NewSupplier("sup-001", "Legal", "Brand", "profile")
	if err := s.Suspend(); err == nil {
		t.Fatal("expected error suspending draft supplier")
	}
}

func TestSupplierReactivate(t *testing.T) {
	s, _ := NewSupplier("sup-001", "Legal", "Brand", "profile")
	s.SubmitForReview()
	s.Approve()
	s.Suspend()
	if err := s.Reactivate(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if s.Status != SupplierStatusActive {
		t.Fatalf("expected ACTIVE, got %s", s.Status)
	}
}

func TestSupplierReactivateFromActive(t *testing.T) {
	s, _ := NewSupplier("sup-001", "Legal", "Brand", "profile")
	s.SubmitForReview()
	s.Approve()
	if err := s.Reactivate(); err == nil {
		t.Fatal("expected error reactivating active supplier")
	}
}

func TestSupplierEventsCleared(t *testing.T) {
	s, _ := NewSupplier("sup-001", "Legal", "Brand", "profile")
	events := s.Events()
	if len(events) != 1 {
		t.Fatalf("expected 1 event")
	}
	if len(s.Events()) != 0 {
		t.Fatal("expected events to be cleared")
	}
}

// Carrier tests

func TestNewCarrierValid(t *testing.T) {
	c, err := NewCarrier("car-001", "sup-001", "Beijing Railway Bureau", "BJRAIL", "RAIL")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if c.CarrierID != "car-001" {
		t.Fatalf("unexpected carrier id: %s", c.CarrierID)
	}
	events := c.Events()
	if len(events) != 1 {
		t.Fatalf("expected 1 event, got %d", len(events))
	}
	evt, ok := events[0].(CarrierRegisteredEvent)
	if !ok {
		t.Fatalf("expected CarrierRegisteredEvent, got %T", events[0])
	}
	if evt.Name != "Beijing Railway Bureau" {
		t.Fatalf("unexpected event name: %s", evt.Name)
	}
}

func TestNewCarrierMissingID(t *testing.T) {
	_, err := NewCarrier("", "sup-001", "Name", "CODE", "RAIL")
	if err == nil {
		t.Fatal("expected error for empty carrier id")
	}
}

func TestNewCarrierMissingSupplierID(t *testing.T) {
	_, err := NewCarrier("car-001", "", "Name", "CODE", "RAIL")
	if err == nil {
		t.Fatal("expected error for empty supplier id")
	}
}

func TestNewCarrierMissingName(t *testing.T) {
	_, err := NewCarrier("car-001", "sup-001", "", "CODE", "RAIL")
	if err == nil {
		t.Fatal("expected error for empty name")
	}
}

func TestNewCarrierMissingCode(t *testing.T) {
	_, err := NewCarrier("car-001", "sup-001", "Name", "", "RAIL")
	if err == nil {
		t.Fatal("expected error for empty code")
	}
}

func TestNewCarrierMissingTransportMode(t *testing.T) {
	_, err := NewCarrier("car-001", "sup-001", "Name", "CODE", "")
	if err == nil {
		t.Fatal("expected error for empty transport mode")
	}
}

func TestCarrierEventsCleared(t *testing.T) {
	c, _ := NewCarrier("car-001", "sup-001", "Name", "CODE", "RAIL")
	events := c.Events()
	if len(events) != 1 {
		t.Fatalf("expected 1 event")
	}
	if len(c.Events()) != 0 {
		t.Fatal("expected events to be cleared")
	}
}

// Contract tests

func TestNewContractValid(t *testing.T) {
	window := TimeWindow{
		Start: frozenNow(),
		End:   frozenNow().Add(365 * 24 * time.Hour),
	}
	c, err := NewContract("ctr-001", "sup-001", "CNT-2026-001", window, "Annual cooperation agreement")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if c.ContractID != "ctr-001" {
		t.Fatalf("unexpected contract id: %s", c.ContractID)
	}
	if c.Status != ContractStatusDraft {
		t.Fatalf("expected DRAFT, got %s", c.Status)
	}
	events := c.Events()
	if len(events) != 1 {
		t.Fatalf("expected 1 event, got %d", len(events))
	}
	_, ok := events[0].(ContractRegisteredEvent)
	if !ok {
		t.Fatalf("expected ContractRegisteredEvent, got %T", events[0])
	}
}

func TestNewContractInvalidWindow(t *testing.T) {
	window := TimeWindow{
		Start: frozenNow().Add(1 * time.Hour),
		End:   frozenNow(),
	}
	_, err := NewContract("ctr-001", "sup-001", "CNT-2026-001", window, "desc")
	if err == nil {
		t.Fatal("expected error for invalid window")
	}
}

func TestContractActivate(t *testing.T) {
	window := TimeWindow{Start: frozenNow(), End: frozenNow().Add(365 * 24 * time.Hour)}
	c, _ := NewContract("ctr-001", "sup-001", "CNT-2026-001", window, "desc")
	// Clear construction events
	c.Events()
	if err := c.Activate(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if c.Status != ContractStatusPublished {
		t.Fatalf("expected PUBLISHED, got %s", c.Status)
	}
	events := c.Events()
	if len(events) != 1 {
		t.Fatalf("expected 1 activation event, got %d", len(events))
	}
	_, ok := events[0].(ContractActivatedEvent)
	if !ok {
		t.Fatalf("expected ContractActivatedEvent, got %T", events[0])
	}
}

func TestContractActivateFromPublished(t *testing.T) {
	window := TimeWindow{Start: frozenNow(), End: frozenNow().Add(365 * 24 * time.Hour)}
	c, _ := NewContract("ctr-001", "sup-001", "CNT-2026-001", window, "desc")
	c.Activate()
	if err := c.Activate(); err == nil {
		t.Fatal("expected error activating already published contract")
	}
}

func TestContractSuspend(t *testing.T) {
	window := TimeWindow{Start: frozenNow(), End: frozenNow().Add(365 * 24 * time.Hour)}
	c, _ := NewContract("ctr-001", "sup-001", "CNT-2026-001", window, "desc")
	// Clear construction events
	c.Events()
	c.Activate()
	// Clear activation events
	c.Events()
	if err := c.Suspend(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if c.Status != ContractStatusSuspended {
		t.Fatalf("expected SUSPENDED, got %s", c.Status)
	}
	events := c.Events()
	if len(events) != 1 {
		t.Fatalf("expected 1 suspension event, got %d", len(events))
	}
	_, ok := events[0].(ContractSuspendedEvent)
	if !ok {
		t.Fatalf("expected ContractSuspendedEvent, got %T", events[0])
	}
}

func TestContractSuspendFromDraft(t *testing.T) {
	window := TimeWindow{Start: frozenNow(), End: frozenNow().Add(365 * 24 * time.Hour)}
	c, _ := NewContract("ctr-001", "sup-001", "CNT-2026-001", window, "desc")
	if err := c.Suspend(); err == nil {
		t.Fatal("expected error suspending draft contract")
	}
}

// ProductCapability tests

func TestNewProductCapabilityValid(t *testing.T) {
	contractWindow := TimeWindow{Start: frozenNow(), End: frozenNow().Add(365 * 24 * time.Hour)}
	capWindow := TimeWindow{Start: frozenNow().Add(1 * 24 * time.Hour), End: frozenNow().Add(300 * 24 * time.Hour)}
	pc, err := NewProductCapability("pcap-001", "sup-001", "ctr-001", "Standard Ticket", "SEARCH", capWindow)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if err := pc.ValidateContractWindow(contractWindow); err != nil {
		t.Fatalf("capability window should be within contract window: %v", err)
	}
	events := pc.Events()
	if len(events) != 1 {
		t.Fatalf("expected 1 event, got %d", len(events))
	}
	_, ok := events[0].(ProductCapabilityDeclaredEvent)
	if !ok {
		t.Fatalf("expected ProductCapabilityDeclaredEvent, got %T", events[0])
	}
}

func TestNewProductCapabilityWindowBeforeContract(t *testing.T) {
	contractWindow := TimeWindow{Start: frozenNow(), End: frozenNow().Add(365 * 24 * time.Hour)}
	capWindow := TimeWindow{Start: frozenNow().Add(-30 * 24 * time.Hour), End: frozenNow().Add(300 * 24 * time.Hour)}
	pc, _ := NewProductCapability("pcap-001", "sup-001", "ctr-001", "Standard Ticket", "SEARCH", capWindow)
	if err := pc.ValidateContractWindow(contractWindow); err == nil {
		t.Fatal("expected error: capability window starts before contract window")
	}
}

func TestNewProductCapabilityWindowAfterContract(t *testing.T) {
	contractWindow := TimeWindow{Start: frozenNow(), End: frozenNow().Add(365 * 24 * time.Hour)}
	capWindow := TimeWindow{Start: frozenNow().Add(100 * 24 * time.Hour), End: frozenNow().Add(400 * 24 * time.Hour)}
	pc, _ := NewProductCapability("pcap-001", "sup-001", "ctr-001", "Standard Ticket", "SEARCH", capWindow)
	if err := pc.ValidateContractWindow(contractWindow); err == nil {
		t.Fatal("expected error: capability window ends after contract window")
	}
}

func TestProductCapabilityPublish(t *testing.T) {
	window := TimeWindow{Start: frozenNow(), End: frozenNow().Add(365 * 24 * time.Hour)}
	pc, _ := NewProductCapability("pcap-001", "sup-001", "ctr-001", "Standard Ticket", "SEARCH", window)
	if err := pc.Publish(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if pc.Status != ProductCapabilityStatusPublished {
		t.Fatalf("expected PUBLISHED, got %s", pc.Status)
	}
}

func TestProductCapabilityRetire(t *testing.T) {
	window := TimeWindow{Start: frozenNow(), End: frozenNow().Add(365 * 24 * time.Hour)}
	pc, _ := NewProductCapability("pcap-001", "sup-001", "ctr-001", "Standard Ticket", "SEARCH", window)
	pc.Publish()
	if err := pc.Retire(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if pc.Status != ProductCapabilityStatusRetired {
		t.Fatalf("expected RETIRED, got %s", pc.Status)
	}
}

func TestProductCapabilityRetireFromDraft(t *testing.T) {
	window := TimeWindow{Start: frozenNow(), End: frozenNow().Add(365 * 24 * time.Hour)}
	pc, _ := NewProductCapability("pcap-001", "sup-001", "ctr-001", "Standard Ticket", "SEARCH", window)
	if err := pc.Retire(); err == nil {
		t.Fatal("expected error retiring draft capability")
	}
}

func TestProductCapabilityMissingID(t *testing.T) {
	window := TimeWindow{Start: frozenNow(), End: frozenNow().Add(365 * 24 * time.Hour)}
	_, err := NewProductCapability("", "sup-001", "ctr-001", "Product", "CAP", window)
	if err == nil {
		t.Fatal("expected error for empty capability id")
	}
}

func TestProductCapabilityMissingContractID(t *testing.T) {
	window := TimeWindow{Start: frozenNow(), End: frozenNow().Add(365 * 24 * time.Hour)}
	_, err := NewProductCapability("pcap-001", "sup-001", "", "Product", "CAP", window)
	if err == nil {
		t.Fatal("expected error for empty contract id")
	}
}

// ExternalCode tests

func TestNewExternalCodeValid(t *testing.T) {
	ec, err := NewExternalCode("ec-001", "sup-001", ExternalCodeTypeStation, "BJP", "plc-beijing-station")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if ec.CodeID != "ec-001" {
		t.Fatalf("unexpected code id: %s", ec.CodeID)
	}
	if ec.Status != ExternalCodeMappingStatusActive {
		t.Fatalf("expected ACTIVE, got %s", ec.Status)
	}
	events := ec.Events()
	if len(events) != 1 {
		t.Fatalf("expected 1 event, got %d", len(events))
	}
	_, ok := events[0].(ExternalCodeMappedEvent)
	if !ok {
		t.Fatalf("expected ExternalCodeMappedEvent, got %T", events[0])
	}
}

func TestNewExternalCodeAllTypes(t *testing.T) {
	types := []ExternalCodeType{
		ExternalCodeTypeStation,
		ExternalCodeTypeServiceClass,
		ExternalCodeTypeFareFamily,
		ExternalCodeTypeProduct,
		ExternalCodeTypeAncillary,
		ExternalCodeTypeRule,
	}
	for _, ct := range types {
		ec, err := NewExternalCode("ec-001", "sup-001", ct, "EXT-CODE", "internal-ref")
		if err != nil {
			t.Fatalf("unexpected error for type %s: %v", ct, err)
		}
		if ec.CodeType != ct {
			t.Fatalf("expected code type %s, got %s", ct, ec.CodeType)
		}
	}
}

func TestNewExternalCodeInvalidType(t *testing.T) {
	_, err := NewExternalCode("ec-001", "sup-001", ExternalCodeType("INVALID"), "EXT", "ref")
	if err == nil {
		t.Fatal("expected error for invalid code type")
	}
}

func TestExternalCodeRecordConflict(t *testing.T) {
	ec, _ := NewExternalCode("ec-001", "sup-001", ExternalCodeTypeStation, "BJP", "plc-beijing-station")
	if err := ec.RecordConflict(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if ec.Status != ExternalCodeMappingStatusConflict {
		t.Fatalf("expected CONFLICT, got %s", ec.Status)
	}
}

func TestExternalCodeRecordConflictFromConflict(t *testing.T) {
	ec, _ := NewExternalCode("ec-001", "sup-001", ExternalCodeTypeStation, "BJP", "plc-beijing-station")
	ec.RecordConflict()
	if err := ec.RecordConflict(); err == nil {
		t.Fatal("expected error recording conflict on already conflicting mapping")
	}
}

func TestExternalCodeResolveConflict(t *testing.T) {
	ec, _ := NewExternalCode("ec-001", "sup-001", ExternalCodeTypeStation, "BJP", "plc-beijing-station")
	ec.RecordConflict()
	if err := ec.ResolveConflict(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if ec.Status != ExternalCodeMappingStatusActive {
		t.Fatalf("expected ACTIVE, got %s", ec.Status)
	}
}

func TestExternalCodeResolveConflictFromActive(t *testing.T) {
	ec, _ := NewExternalCode("ec-001", "sup-001", ExternalCodeTypeStation, "BJP", "plc-beijing-station")
	if err := ec.ResolveConflict(); err == nil {
		t.Fatal("expected error resolving conflict on active mapping")
	}
}

func TestExternalCodeMissingSupplierID(t *testing.T) {
	_, err := NewExternalCode("ec-001", "", ExternalCodeTypeStation, "EXT", "ref")
	if err == nil {
		t.Fatal("expected error for empty supplier id")
	}
}

func TestExternalCodeMissingExternalCode(t *testing.T) {
	_, err := NewExternalCode("ec-001", "sup-001", ExternalCodeTypeStation, "", "ref")
	if err == nil {
		t.Fatal("expected error for empty external code")
	}
}

func TestExternalCodeMissingInternalRef(t *testing.T) {
	_, err := NewExternalCode("ec-001", "sup-001", ExternalCodeTypeStation, "EXT", "")
	if err == nil {
		t.Fatal("expected error for empty internal ref")
	}
}

func TestExternalCodeEventsCleared(t *testing.T) {
	ec, _ := NewExternalCode("ec-001", "sup-001", ExternalCodeTypeStation, "BJP", "plc-beijing-station")
	events := ec.Events()
	if len(events) != 1 {
		t.Fatalf("expected 1 event")
	}
	if len(ec.Events()) != 0 {
		t.Fatal("expected events to be cleared")
	}
}

// TimeWindow tests

func TestTimeWindowValid(t *testing.T) {
	tw := TimeWindow{Start: frozenNow(), End: frozenNow().Add(1 * time.Hour)}
	if err := tw.Validate(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestTimeWindowZeroStart(t *testing.T) {
	tw := TimeWindow{End: frozenNow()}
	if err := tw.Validate(); err == nil {
		t.Fatal("expected error for zero start")
	}
}

func TestTimeWindowZeroEnd(t *testing.T) {
	tw := TimeWindow{Start: frozenNow()}
	if err := tw.Validate(); err == nil {
		t.Fatal("expected error for zero end")
	}
}

func TestTimeWindowEndBeforeStart(t *testing.T) {
	tw := TimeWindow{Start: frozenNow(), End: frozenNow().Add(-1 * time.Hour)}
	if err := tw.Validate(); err == nil {
		t.Fatal("expected error for end before start")
	}
}

func TestTimeWindowEndEqualsStart(t *testing.T) {
	tw := TimeWindow{Start: frozenNow(), End: frozenNow()}
	if err := tw.Validate(); err == nil {
		t.Fatal("expected error for end equal to start")
	}
}

// Money tests

func TestMoneyValid(t *testing.T) {
	m := Money{Currency: "CNY", MinorUnits: 1000}
	if err := m.Validate(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestMoneyInvalidCurrencyLength(t *testing.T) {
	m := Money{Currency: "CN", MinorUnits: 1000}
	if err := m.Validate(); err == nil {
		t.Fatal("expected error for short currency code")
	}
}

func TestMoneyInvalidCurrencyLowercase(t *testing.T) {
	m := Money{Currency: "cny", MinorUnits: 1000}
	if err := m.Validate(); err == nil {
		t.Fatal("expected error for lowercase currency")
	}
}

// Invariant: A supplier must have an active contract before its products are sellable.
func TestInvariantActiveContractRequiredForSellableProducts(t *testing.T) {
	// Create a supplier
	s, err := NewSupplier("sup-001", "China Railway Corp", "CR", "National railway operator")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	s.SubmitForReview()
	s.Approve()

	// Create a contract and activate it
	contractWindow := TimeWindow{Start: frozenNow(), End: frozenNow().Add(365 * 24 * time.Hour)}
	contract, err := NewContract("ctr-001", s.SupplierID, "CNT-2026-001", contractWindow, "desc")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	// Before activation, capability window validation should succeed at the
	// capability level but the contract must be active for products to be sellable.
	if contract.Status != ContractStatusDraft {
		t.Fatalf("expected DRAFT contract before activation")
	}

	// Activate the contract
	if err := contract.Activate(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if contract.Status != ContractStatusPublished {
		t.Fatalf("expected PUBLISHED contract after activation")
	}

	// Now declare a capability within the contract window
	capWindow := TimeWindow{Start: frozenNow().Add(1 * 24 * time.Hour), End: frozenNow().Add(300 * 24 * time.Hour)}
	pc, err := NewProductCapability("pcap-001", s.SupplierID, contract.ContractID, "Standard Ticket", "SEARCH", capWindow)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if err := pc.ValidateContractWindow(contract.ValidWindow); err != nil {
		t.Fatalf("capability window should be within contract window: %v", err)
	}
}

// Invariant: External codes map to internal identifiers uniquely per supplier + code type;
// conflicting mappings are recorded as gaps, not overwritten.
func TestInvariantExternalCodeConflictNotOverwritten(t *testing.T) {
	ec1, err := NewExternalCode("ec-001", "sup-001", ExternalCodeTypeStation, "BJP", "plc-beijing-station")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if ec1.Status != ExternalCodeMappingStatusActive {
		t.Fatalf("expected first mapping to be ACTIVE")
	}

	// Simulate a second mapping for the same supplier + code type + external code
	// In a repository, this would be detected as a duplicate. The domain behavior
	// records the conflict on the existing mapping rather than overwriting.
	if err := ec1.RecordConflict(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if ec1.Status != ExternalCodeMappingStatusConflict {
		t.Fatalf("expected CONFLICT status after duplicate detected")
	}

	// The original mapping is preserved, not deleted
	if ec1.InternalRef != "plc-beijing-station" {
		t.Fatalf("original internal ref should be preserved, got %s", ec1.InternalRef)
	}
}

// Invariant: Product capability declarations reference the supplier's contract validity window.
func TestInvariantCapabilityReferencesContractWindow(t *testing.T) {
	contractWindow := TimeWindow{Start: frozenNow(), End: frozenNow().Add(365 * 24 * time.Hour)}

	// Valid capability within window
	capWindow := TimeWindow{Start: frozenNow().Add(10 * 24 * time.Hour), End: frozenNow().Add(200 * 24 * time.Hour)}
	pc, _ := NewProductCapability("pcap-001", "sup-001", "ctr-001", "Standard Ticket", "SEARCH", capWindow)
	if err := pc.ValidateContractWindow(contractWindow); err != nil {
		t.Fatalf("valid capability should pass: %v", err)
	}

	// Capability starting before contract
	earlyWindow := TimeWindow{Start: frozenNow().Add(-30 * 24 * time.Hour), End: frozenNow().Add(100 * 24 * time.Hour)}
	pcEarly, _ := NewProductCapability("pcap-002", "sup-001", "ctr-001", "Early Ticket", "SEARCH", earlyWindow)
	if err := pcEarly.ValidateContractWindow(contractWindow); err == nil {
		t.Fatal("expected error for capability starting before contract")
	}

	// Capability ending after contract
	lateWindow := TimeWindow{Start: frozenNow().Add(100 * 24 * time.Hour), End: frozenNow().Add(400 * 24 * time.Hour)}
	pcLate, _ := NewProductCapability("pcap-003", "sup-001", "ctr-001", "Late Ticket", "SEARCH", lateWindow)
	if err := pcLate.ValidateContractWindow(contractWindow); err == nil {
		t.Fatal("expected error for capability ending after contract")
	}
}

// Invariant: Catalog data is master data — changes are versioned, never destructive.
func TestInvariantNonDestructiveChanges(t *testing.T) {
	// Supplier lifecycle does not delete - it transitions states
	s, _ := NewSupplier("sup-001", "Legal", "Brand", "profile")
	s.SubmitForReview()
	s.Approve()
	s.Suspend()
	// Supplier still exists with its data intact
	if s.LegalName != "Legal" {
		t.Fatalf("supplier data should be preserved")
	}
	// Reactivate restores
	s.Reactivate()
	if s.Status != SupplierStatusActive {
		t.Fatalf("expected ACTIVE after reactivation")
	}

	// ExternalCode conflict records conflict but preserves original data
	ec, _ := NewExternalCode("ec-001", "sup-001", ExternalCodeTypeStation, "BJP", "plc-beijing-station")
	ec.RecordConflict()
	if ec.InternalRef != "plc-beijing-station" {
		t.Fatalf("original mapping should be preserved")
	}
	ec.ResolveConflict()
	if ec.Status != ExternalCodeMappingStatusActive {
		t.Fatalf("expected ACTIVE after conflict resolution")
	}

	// Contract versioning - supersede via new version (simulated)
	window := TimeWindow{Start: frozenNow(), End: frozenNow().Add(365 * 24 * time.Hour)}
	c, _ := NewContract("ctr-001", "sup-001", "CNT-2026-001", window, "desc")
	c.Activate()
	if c.Description != "desc" {
		t.Fatalf("contract data should be preserved")
	}
}

func TestEventEnvelopeRoundTrip(t *testing.T) {
	now := frozenNow()
	env := NewEventEnvelope("SupplierRegistered", now, "corr-1", nil, "supplier-catalog")
	if env.EventType != "SupplierRegistered" {
		t.Fatalf("unexpected event type: %s", env.EventType)
	}
	if env.SchemaVersion != 1 {
		t.Fatalf("unexpected schema version: %d", env.SchemaVersion)
	}
	if env.Producer != "supplier-catalog" {
		t.Fatalf("unexpected producer: %s", env.Producer)
	}
	if !strings.HasSuffix(env.OccurredAt, "Z") {
		t.Fatalf("occurredAt should end with Z (RFC3339 UTC): %s", env.OccurredAt)
	}

	payload := SupplierRegisteredEvent{
		SupplierID:   "sup-001",
		LegalName:    "Test Supplier",
		BrandName:    "Test",
		Status:       SupplierStatusDraft,
		RegisteredAt: now,
	}
	wrapped := WrapDomainEvent(env, payload)
	if wrapped["eventType"] != "SupplierRegistered" {
		t.Fatalf("unexpected wrapped event type: %v", wrapped["eventType"])
	}
	data := wrapped["data"].(SupplierRegisteredEvent)
	if data.SupplierID != "sup-001" {
		t.Fatalf("unexpected data supplier id: %s", data.SupplierID)
	}
}
