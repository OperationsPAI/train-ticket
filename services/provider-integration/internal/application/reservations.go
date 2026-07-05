package application

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/trainticket/greenfield/services/provider-integration/internal/domain"
)

var (
	ErrValidation  = errors.New("validation failed")
	ErrDomainRule  = errors.New("domain rule violation")
	ErrNotFound    = errors.New("not found")
	ErrUnavailable = errors.New("unavailable")
)

type ReservationStatus string

const (
	ReservationPending   ReservationStatus = "PENDING"
	ReservationConfirmed ReservationStatus = "CONFIRMED"
	ReservationFailed    ReservationStatus = "FAILED"
	ReservationTimedOut  ReservationStatus = "TIMED_OUT"
)

type CancellationStatus string

const (
	CancellationCancelled CancellationStatus = "CANCELLED"
	CancellationPending   CancellationStatus = "PENDING"
	CancellationFailed    CancellationStatus = "FAILED"
)

type RequestProviderReservationCommand struct {
	SegmentBookingID   string         `json:"segmentBookingId"`
	ProviderConfigRef  string         `json:"providerConfigRef"`
	ReservationPayload map[string]any `json:"reservationPayload"`
	CorrelationID      string         `json:"-"`
	CausationID        string         `json:"-"`
	IdempotencyKey     string         `json:"-"`
}

type ProviderReservationResult struct {
	SegmentBookingID   string            `json:"segmentBookingId"`
	Status             ReservationStatus `json:"status"`
	ProviderReference  string            `json:"providerReference,omitempty"`
	NormalizedEvidence string            `json:"-"`
}

type CancelProviderReservationCommand struct {
	SegmentBookingID string `json:"segmentBookingId"`
	CorrelationID    string `json:"-"`
	CausationID      string `json:"-"`
	IdempotencyKey   string `json:"-"`
}

type CancelProviderReservationResult struct {
	SegmentBookingID   string             `json:"segmentBookingId"`
	CancellationStatus CancellationStatus `json:"cancellationStatus"`
}

type ProviderReservationService interface {
	RequestReservation(context.Context, RequestProviderReservationCommand) (ProviderReservationResult, error)
	CancelReservation(context.Context, CancelProviderReservationCommand) (CancelProviderReservationResult, error)
}

type InMemoryReservationService struct {
	mu            sync.Mutex
	publisher     EventPublisher
	mapping       domain.StatusMappingCatalog
	results       map[string]ProviderReservationResult
	cancellations map[string]CancelProviderReservationResult
}

func NewInMemoryReservationService(publisher EventPublisher) *InMemoryReservationService {
	return &InMemoryReservationService{
		publisher:     publisher,
		mapping:       defaultReservationMappingCatalog(),
		results:       map[string]ProviderReservationResult{},
		cancellations: map[string]CancelProviderReservationResult{},
	}
}

func (s *InMemoryReservationService) RequestReservation(ctx context.Context, cmd RequestProviderReservationCommand) (ProviderReservationResult, error) {
	if err := validateReservationCommand(cmd); err != nil {
		return ProviderReservationResult{}, err
	}
	log, err := domain.NewProviderRequestLog(newLogID(), domain.ProviderID(cmd.ProviderConfigRef), domain.OperationConfirmReservation, cmd.IdempotencyKey, canonicalCorrelationID(cmd.CorrelationID), cmd.SegmentBookingID)
	if err != nil {
		return ProviderReservationResult{}, fmt.Errorf("%w: %v", ErrDomainRule, err)
	}
	if err := log.MarkSent(); err != nil {
		return ProviderReservationResult{}, fmt.Errorf("%w: %v", ErrDomainRule, err)
	}
	providerReference := "prv-" + strings.TrimPrefix(strings.TrimSpace(cmd.SegmentBookingID), "sb-")
	if err := log.MarkSucceeded(&domain.ProviderRef{ConfirmationCode: providerReference, ExternalID: providerReference}); err != nil {
		return ProviderReservationResult{}, fmt.Errorf("%w: %v", ErrDomainRule, err)
	}
	decision, err := s.mapProviderStatus(cmd, providerReference)
	if err != nil {
		return ProviderReservationResult{}, err
	}
	if decision.Fact == nil || decision.Fact.Kind != domain.FactProviderReservationConfirmed {
		return ProviderReservationResult{}, fmt.Errorf("%w: provider reservation was not confirmed", ErrDomainRule)
	}
	result := ProviderReservationResult{
		SegmentBookingID:   strings.TrimSpace(cmd.SegmentBookingID),
		Status:             ReservationConfirmed,
		ProviderReference:  string(decision.Fact.ProviderReference),
		NormalizedEvidence: normalizedEvidence(decision.Fact),
	}
	s.mu.Lock()
	s.results[result.SegmentBookingID] = result
	s.mu.Unlock()
	if err := s.publish(ctx, "ProviderReservationConfirmed", cmd.CorrelationID, cmd.CausationID, map[string]any{
		"segmentBookingId":   result.SegmentBookingID,
		"providerReference":  result.ProviderReference,
		"normalizedEvidence": result.NormalizedEvidence,
	}); err != nil {
		return ProviderReservationResult{}, ErrUnavailable
	}
	return result, nil
}

func (s *InMemoryReservationService) CancelReservation(ctx context.Context, cmd CancelProviderReservationCommand) (CancelProviderReservationResult, error) {
	if err := ValidateSegmentBookingID(cmd.SegmentBookingID); err != nil {
		return CancelProviderReservationResult{}, err
	}
	s.mu.Lock()
	_, exists := s.results[cmd.SegmentBookingID]
	s.mu.Unlock()
	if !exists {
		return CancelProviderReservationResult{}, ErrNotFound
	}
	result := CancelProviderReservationResult{SegmentBookingID: cmd.SegmentBookingID, CancellationStatus: CancellationCancelled}
	s.mu.Lock()
	s.cancellations[result.SegmentBookingID] = result
	s.mu.Unlock()
	return result, nil
}

func (s *InMemoryReservationService) publish(ctx context.Context, eventType, correlationID, causationID string, payload any) error {
	if s.publisher == nil {
		return nil
	}
	envelope, err := NewEventEnvelope(eventType, correlationID, causationID, payload)
	if err != nil {
		return err
	}
	return s.publisher.Publish(ctx, envelope)
}

func (s *InMemoryReservationService) mapProviderStatus(cmd RequestProviderReservationCommand, providerReference string) (domain.ProviderMappingDecision, error) {
	identity, err := domain.NewProviderRequestIdentity(domain.ProviderID(cmd.ProviderConfigRef), domain.ProviderRequestID(newLogID()), domain.OperationConfirmReservation, domain.IdempotencyKey(cmd.IdempotencyKey), domain.CorrelationID(canonicalCorrelationID(cmd.CorrelationID)), domain.BusinessRef(cmd.SegmentBookingID))
	if err != nil {
		return domain.ProviderMappingDecision{}, fmt.Errorf("%w: %v", ErrDomainRule, err)
	}
	status, err := domain.NewExternalProviderStatus(identity, "CONFIRMED", "provider confirmed reservation", domain.ProviderReference(providerReference), time.Now().UTC(), rawArchiveFor(cmd.SegmentBookingID))
	if err != nil {
		return domain.ProviderMappingDecision{}, fmt.Errorf("%w: %v", ErrDomainRule, err)
	}
	decision, err := s.mapping.Map(status)
	if err != nil {
		return domain.ProviderMappingDecision{}, fmt.Errorf("%w: %v", ErrDomainRule, err)
	}
	return decision, nil
}

func ValidateSegmentBookingID(value string) error {
	trimmed := strings.TrimSpace(value)
	if !strings.HasPrefix(trimmed, "sb-") {
		return fmt.Errorf("%w: segmentBookingId must be sb-<uuid-v7>", ErrValidation)
	}
	id, err := uuid.Parse(strings.TrimPrefix(trimmed, "sb-"))
	if err != nil || id.Version() != 7 {
		return fmt.Errorf("%w: segmentBookingId must be sb-<uuid-v7>", ErrValidation)
	}
	return nil
}

func validatePrefixedUUIDV7(field, value, prefix string) error {
	trimmed := strings.TrimSpace(value)
	if !strings.HasPrefix(trimmed, prefix+"-") {
		return fmt.Errorf("%w: %s must be %s-<uuid-v7>", ErrValidation, field, prefix)
	}
	return validateUUIDV7(field, strings.TrimPrefix(trimmed, prefix+"-"))
}

func validateUUIDV7(field, value string) error {
	id, err := uuid.Parse(strings.TrimSpace(value))
	if err != nil || id.Version() != 7 {
		return fmt.Errorf("%w: %s must be UUID v7", ErrValidation, field)
	}
	return nil
}

func validateRequiredToken(field, value string) error {
	if strings.TrimSpace(value) == "" {
		return fmt.Errorf("%w: %s is required", ErrValidation, field)
	}
	return nil
}

func validateReservationCommand(cmd RequestProviderReservationCommand) error {
	if err := ValidateSegmentBookingID(cmd.SegmentBookingID); err != nil {
		return err
	}
	if strings.TrimSpace(cmd.ProviderConfigRef) == "" {
		return fmt.Errorf("%w: providerConfigRef is required", ErrValidation)
	}
	if strings.Contains(strings.TrimSpace(cmd.ProviderConfigRef), " ") {
		return fmt.Errorf("%w: providerConfigRef must not contain spaces", ErrDomainRule)
	}
	if strings.TrimSpace(cmd.IdempotencyKey) == "" {
		return fmt.Errorf("%w: idempotency key is required", ErrDomainRule)
	}
	if len(cmd.ReservationPayload) == 0 {
		return fmt.Errorf("%w: reservationPayload is required", ErrValidation)
	}
	return nil
}

func defaultReservationMappingCatalog() domain.StatusMappingCatalog {
	confidence, _ := domain.NewMappingConfidence(domain.MappingConfidenceExact, 1, "")
	rule, _ := domain.NewStatusMappingRule("cr-rail", domain.OperationConfirmReservation, "CONFIRMED", domain.FactProviderReservationConfirmed, "", confidence, domain.NextActionStop, "provider-http-v1")
	catalog, _ := domain.NewStatusMappingCatalog([]domain.StatusMappingRule{rule})
	return catalog
}

func rawArchiveFor(segmentBookingID string) domain.RawArchiveReference {
	ref, _ := domain.NewRawArchiveReference(domain.RawArchiveID("raw-"+strings.TrimPrefix(strings.TrimSpace(segmentBookingID), "sb-")), domain.RawArchiveResponse, domain.RawArchiveURI("archive://provider-integration/"+strings.TrimSpace(segmentBookingID)), "provider-response-digest", "provider-http-v1")
	return ref
}

func normalizedEvidence(fact *domain.MappedInternalFact) string {
	return fmt.Sprintf("%s:%s", fact.Archive.ArchiveID, fact.ProviderReference)
}

func newLogID() string {
	id, err := uuid.NewV7()
	if err != nil {
		id = uuid.New()
	}
	return "preq-" + id.String()
}
