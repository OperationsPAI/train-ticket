package application

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/trainticket/greenfield/services/supplier-catalog/internal/domain"
)

const producerName = "supplier-catalog"

var (
	ErrValidation      = errors.New("validation failed")
	ErrNotFound        = errors.New("not found")
	ErrConflict        = errors.New("conflict")
	ErrDomainViolation = errors.New("domain rule violation")
)

type Service struct {
	mu                  sync.RWMutex
	publishMu           sync.Mutex
	suppliers           map[domain.SupplierID]domain.Supplier
	carriers            map[domain.CarrierID]domain.Carrier
	contracts           map[domain.ContractID]domain.Contract
	pendingEvents       []EventEnvelope
	hasPendingPublishes bool
	publisher           EventPublisher
}

func NewService(publisher EventPublisher) *Service {
	return &Service{
		suppliers:     make(map[domain.SupplierID]domain.Supplier),
		carriers:      make(map[domain.CarrierID]domain.Carrier),
		contracts:     make(map[domain.ContractID]domain.Contract),
		pendingEvents: []EventEnvelope{},
		publisher:     publisher,
	}
}

type RegisterSupplierCommand struct {
	LegalName     string
	BrandName     string
	SupplierCode  string
	CorrelationID string
	CausationID   string
}

type RegisterCarrierCommand struct {
	SupplierID    string
	Name          string
	Code          string
	TransportMode string
	CorrelationID string
	CausationID   string
}

type ActivateContractCommand struct {
	SupplierID     string
	CarrierID      string
	ContractRef    string
	EffectiveFrom  time.Time
	EffectiveUntil *time.Time
	CorrelationID  string
	CausationID    string
}

type SupplierView struct {
	SupplierID   string                `json:"supplierId"`
	LegalName    string                `json:"legalName"`
	BrandName    string                `json:"brandName"`
	Status       domain.SupplierStatus `json:"status"`
	RegisteredAt time.Time             `json:"registeredAt"`
}

type CarrierView struct {
	CarrierID     string `json:"carrierId"`
	SupplierID    string `json:"supplierId"`
	Name          string `json:"name"`
	Code          string `json:"code"`
	TransportMode string `json:"transportMode"`
}

type ContractView struct {
	ContractID string                `json:"contractId"`
	Status     domain.ContractStatus `json:"status"`
}

type SupplierList struct {
	Items  []SupplierView `json:"items"`
	Total  int            `json:"total"`
	Limit  int            `json:"limit"`
	Offset int            `json:"offset"`
}

func (s *Service) RegisterSupplier(ctx context.Context, cmd RegisterSupplierCommand) (SupplierView, error) {
	if strings.TrimSpace(cmd.LegalName) == "" || strings.TrimSpace(cmd.BrandName) == "" || strings.TrimSpace(cmd.SupplierCode) == "" {
		return SupplierView{}, fmt.Errorf("%w: legalName, brandName, and supplierCode are required", ErrValidation)
	}
	supplierID := domain.SupplierID("sup-" + newUUIDString())
	supplier, err := domain.NewSupplier(supplierID, cmd.LegalName, cmd.BrandName, cmd.SupplierCode)
	if err != nil {
		return SupplierView{}, fmt.Errorf("%w: %v", ErrDomainViolation, err)
	}
	events := supplier.Events()

	s.mu.Lock()
	for _, existing := range s.suppliers {
		if strings.EqualFold(existing.Profile, supplier.Profile) {
			if s.hasPendingPublishes && existing.LegalName == supplier.LegalName && existing.BrandName == supplier.BrandName {
				s.mu.Unlock()
				if err := s.flushPendingEvents(ctx); err != nil {
					return SupplierView{}, err
				}
				return supplierView(existing), nil
			}
			s.mu.Unlock()
			return SupplierView{}, fmt.Errorf("%w: supplierCode already exists", ErrConflict)
		}
	}
	envelopes := wrapDomainEvents(events, cmd.CorrelationID, cmd.CausationID)
	s.suppliers[supplier.SupplierID] = supplier
	s.pendingEvents = append(s.pendingEvents, envelopes...)
	s.hasPendingPublishes = true
	s.mu.Unlock()

	if err := s.flushPendingEvents(ctx); err != nil {
		return SupplierView{}, err
	}
	return supplierView(supplier), nil
}

func (s *Service) GetSupplier(_ context.Context, supplierID string) (domain.Supplier, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	supplier, ok := s.suppliers[domain.SupplierID(strings.TrimSpace(supplierID))]
	if !ok {
		return domain.Supplier{}, fmt.Errorf("%w: supplier not found", ErrNotFound)
	}
	return supplier, nil
}

func (s *Service) ListSuppliers(_ context.Context, status string, limit, offset int) SupplierList {
	if limit <= 0 {
		limit = 20
	}
	if limit > 100 {
		limit = 100
	}
	if offset < 0 {
		offset = 0
	}

	s.mu.RLock()
	items := make([]SupplierView, 0, len(s.suppliers))
	for _, supplier := range s.suppliers {
		if status == "" || string(supplier.Status) == status {
			items = append(items, supplierView(supplier))
		}
	}
	s.mu.RUnlock()
	sort.Slice(items, func(i, j int) bool { return items[i].SupplierID < items[j].SupplierID })
	total := len(items)
	if offset > total {
		items = []SupplierView{}
	} else {
		end := offset + limit
		if end > total {
			end = total
		}
		items = items[offset:end]
	}
	return SupplierList{Items: items, Total: total, Limit: limit, Offset: offset}
}

func (s *Service) RegisterCarrier(ctx context.Context, cmd RegisterCarrierCommand) (CarrierView, error) {
	if strings.TrimSpace(cmd.SupplierID) == "" || strings.TrimSpace(cmd.Name) == "" || strings.TrimSpace(cmd.Code) == "" || strings.TrimSpace(cmd.TransportMode) == "" {
		return CarrierView{}, fmt.Errorf("%w: supplierId, name, code, and transportMode are required", ErrValidation)
	}
	carrierID := domain.CarrierID("car-" + newUUIDString())
	carrier, err := domain.NewCarrier(carrierID, domain.SupplierID(cmd.SupplierID), cmd.Name, cmd.Code, cmd.TransportMode)
	if err != nil {
		return CarrierView{}, fmt.Errorf("%w: %v", ErrDomainViolation, err)
	}
	events := carrier.Events()

	s.mu.Lock()
	if _, ok := s.suppliers[carrier.SupplierID]; !ok {
		s.mu.Unlock()
		return CarrierView{}, fmt.Errorf("%w: supplier not found", ErrNotFound)
	}
	for _, existing := range s.carriers {
		if strings.EqualFold(existing.Code, carrier.Code) {
			if s.hasPendingPublishes && existing.SupplierID == carrier.SupplierID && existing.Name == carrier.Name && existing.TransportMode == carrier.TransportMode {
				s.mu.Unlock()
				if err := s.flushPendingEvents(ctx); err != nil {
					return CarrierView{}, err
				}
				return carrierView(existing), nil
			}
			s.mu.Unlock()
			return CarrierView{}, fmt.Errorf("%w: carrier code already exists", ErrConflict)
		}
	}
	envelopes := wrapDomainEvents(events, cmd.CorrelationID, cmd.CausationID)
	s.carriers[carrier.CarrierID] = carrier
	s.pendingEvents = append(s.pendingEvents, envelopes...)
	s.hasPendingPublishes = true
	s.mu.Unlock()

	if err := s.flushPendingEvents(ctx); err != nil {
		return CarrierView{}, err
	}
	return carrierView(carrier), nil
}

func (s *Service) ActivateContract(ctx context.Context, cmd ActivateContractCommand) (ContractView, error) {
	if strings.TrimSpace(cmd.SupplierID) == "" || strings.TrimSpace(cmd.CarrierID) == "" || strings.TrimSpace(cmd.ContractRef) == "" || cmd.EffectiveFrom.IsZero() {
		return ContractView{}, fmt.Errorf("%w: supplierId, carrierId, contractRef, and effectiveFrom are required", ErrValidation)
	}
	effectiveUntil := cmd.EffectiveFrom.AddDate(100, 0, 0)
	if cmd.EffectiveUntil != nil {
		effectiveUntil = *cmd.EffectiveUntil
	}
	contract, err := domain.NewContract(domain.ContractID("ctr-"+newUUIDString()), domain.SupplierID(cmd.SupplierID), cmd.ContractRef, domain.TimeWindow{Start: cmd.EffectiveFrom.UTC(), End: effectiveUntil.UTC()}, "")
	if err != nil {
		return ContractView{}, fmt.Errorf("%w: %v", ErrDomainViolation, err)
	}
	contract.Events()
	if err := contract.Activate(); err != nil {
		return ContractView{}, fmt.Errorf("%w: %v", ErrDomainViolation, err)
	}
	events := contract.Events()

	s.mu.Lock()
	if _, ok := s.suppliers[contract.SupplierID]; !ok {
		s.mu.Unlock()
		return ContractView{}, fmt.Errorf("%w: supplier not found", ErrNotFound)
	}
	carrier, ok := s.carriers[domain.CarrierID(cmd.CarrierID)]
	if !ok || carrier.SupplierID != contract.SupplierID {
		s.mu.Unlock()
		return ContractView{}, fmt.Errorf("%w: carrier not found", ErrNotFound)
	}
	for _, existing := range s.contracts {
		if strings.EqualFold(existing.ContractNo, contract.ContractNo) {
			if s.hasPendingPublishes && existing.SupplierID == contract.SupplierID && existing.Status == contract.Status {
				s.mu.Unlock()
				if err := s.flushPendingEvents(ctx); err != nil {
					return ContractView{}, err
				}
				return ContractView{ContractID: string(existing.ContractID), Status: existing.Status}, nil
			}
			s.mu.Unlock()
			return ContractView{}, fmt.Errorf("%w: contractRef already exists", ErrConflict)
		}
	}
	envelopes := wrapDomainEvents(events, cmd.CorrelationID, cmd.CausationID)
	s.contracts[contract.ContractID] = contract
	s.pendingEvents = append(s.pendingEvents, envelopes...)
	s.hasPendingPublishes = true
	s.mu.Unlock()

	if err := s.flushPendingEvents(ctx); err != nil {
		return ContractView{}, err
	}
	return ContractView{ContractID: string(contract.ContractID), Status: contract.Status}, nil
}

func (s *Service) flushPendingEvents(ctx context.Context) error {
	if s.publisher == nil {
		s.mu.Lock()
		s.pendingEvents = nil
		s.hasPendingPublishes = false
		s.mu.Unlock()
		return nil
	}
	s.publishMu.Lock()
	defer s.publishMu.Unlock()
	for {
		s.mu.RLock()
		if !s.hasPendingPublishes {
			s.mu.RUnlock()
			return nil
		}
		envelope := s.pendingEvents[0]
		s.mu.RUnlock()

		if err := s.publisher.Publish(ctx, envelope); err != nil {
			return fmt.Errorf("publish failed: %w", err)
		}

		s.mu.Lock()
		if len(s.pendingEvents) > 0 && s.pendingEvents[0].EventID == envelope.EventID {
			s.pendingEvents = append([]EventEnvelope{}, s.pendingEvents[1:]...)
		} else {
			for i, pending := range s.pendingEvents {
				if pending.EventID == envelope.EventID {
					s.pendingEvents = append(s.pendingEvents[:i], s.pendingEvents[i+1:]...)
					break
				}
			}
		}
		s.hasPendingPublishes = len(s.pendingEvents) > 0
		s.mu.Unlock()
	}
}

func wrapDomainEvents(events []interface{}, correlationID, causationID string) []EventEnvelope {
	envelopes := make([]EventEnvelope, 0, len(events))
	for _, event := range events {
		envelopes = append(envelopes, WrapDomainEvent(event, correlationID, causationID))
	}
	return envelopes
}

func WrapDomainEvent(event interface{}, correlationID, causationID string) EventEnvelope {
	return EventEnvelope{
		EventID:       "evt-" + newUUIDString(),
		EventType:     eventType(event),
		OccurredAt:    formatUTC(time.Now()),
		CorrelationID: canonicalCorrelationID(correlationID),
		CausationID:   canonicalCausationID(causationID),
		Producer:      producerName,
		SchemaVersion: 1,
		Payload:       event,
	}
}

func newUUIDString() string {
	id, err := uuid.NewV7()
	if err != nil {
		return uuid.NewString()
	}
	return id.String()
}

func formatUTC(t time.Time) string {
	return t.UTC().Format("2006-01-02T15:04:05.000Z")
}

func canonicalCorrelationID(value string) string {
	value = strings.TrimSpace(value)
	if strings.HasPrefix(value, "corr-") {
		return value
	}
	if _, err := uuid.Parse(value); err == nil {
		return "corr-" + value
	}
	return "corr-" + newUUIDString()
}

func canonicalCausationID(value string) string {
	value = strings.TrimSpace(value)
	if strings.HasPrefix(value, "cmd-") || strings.HasPrefix(value, "evt-") {
		return value
	}
	if _, err := uuid.Parse(value); err == nil {
		return "cmd-" + value
	}
	return "cmd-" + newUUIDString()
}

func eventType(event interface{}) string {
	switch event.(type) {
	case domain.SupplierRegisteredEvent:
		return "SupplierRegistered"
	case domain.CarrierRegisteredEvent:
		return "CarrierRegistered"
	case domain.ContractActivatedEvent:
		return "ContractActivated"
	case domain.ContractSuspendedEvent:
		return "ContractSuspended"
	case domain.ProductCapabilityDeclaredEvent:
		return "ProductCapabilityDeclared"
	case domain.ExternalCodeMappedEvent:
		return "ExternalCodeMapped"
	default:
		return fmt.Sprintf("%T", event)
	}
}

func supplierView(s domain.Supplier) SupplierView {
	return SupplierView{SupplierID: string(s.SupplierID), LegalName: s.LegalName, BrandName: s.BrandName, Status: s.Status, RegisteredAt: s.RegisteredAt.UTC()}
}

func carrierView(c domain.Carrier) CarrierView {
	return CarrierView{CarrierID: string(c.CarrierID), SupplierID: string(c.SupplierID), Name: c.Name, Code: c.Code, TransportMode: c.TransportMode}
}
