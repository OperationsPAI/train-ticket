package application

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

const (
	ProducerName  = "fulfillment"
	SchemaVersion = 1
)

var (
	ErrNotFound            = errors.New("not found")
	ErrConflict            = errors.New("conflict")
	ErrDomainRuleViolation = errors.New("domain rule violation")
	ErrPublishFailed       = errors.New("publish failed")
	ErrSubscribeFailed     = errors.New("subscribe failed")
	ErrTransientHandler    = errors.New("transient handler error")
	ErrFatalHandler        = errors.New("fatal handler error")
)

type EventEnvelope = kitmsg.EventEnvelope

type EventPublisher interface {
	Publish(ctx context.Context, envelope EventEnvelope) error
}

type EventSubscriber interface {
	Subscribe(ctx context.Context, streams []string, group string, consumerName string, handler EventHandler) error
}

type EventHandler func(context.Context, EventEnvelope) error

type FulfillmentRepository interface {
	Save(ctx context.Context, record *domain.FulfillmentRecord) error
	FindByID(ctx context.Context, id domain.FulfillmentRecordID) (*domain.FulfillmentRecord, error)
	FindByEntitlementSegment(ctx context.Context, entitlementID domain.EntitlementRef, segmentBookingID domain.SegmentBookingRef, segmentRef domain.SegmentRef) (*domain.FulfillmentRecord, error)
}

type ConsumedEventLog interface {
	Claim(ctx context.Context, eventID string) (bool, error)
}

type UnitOfWork func(context.Context, func(context.Context) error) error

type IDGenerator func(prefix string) string

type Clock func() time.Time

type Service struct {
	repo     FulfillmentRepository
	pub      EventPublisher
	consumed ConsumedEventLog
	idGen    IDGenerator
	clock    Clock
	uow      UnitOfWork
	mu       sync.Mutex
	tickets  map[string]TicketProjection
}

type TicketProjection struct {
	EntitlementID    domain.EntitlementRef
	SegmentBookingID domain.SegmentBookingRef
	JourneyOrderID   domain.OrderRef
	TravelerID       domain.TravelerRef
	SegmentRef       domain.SegmentRef
	Voided           bool
}

func NewService(repo FulfillmentRepository, publisher EventPublisher, consumed ConsumedEventLog, idGen IDGenerator, clock Clock) *Service {
	if idGen == nil {
		idGen = NewPrefixedID
	}
	if clock == nil {
		clock = func() time.Time { return time.Now().UTC() }
	}
	return &Service{repo: repo, pub: publisher, consumed: consumed, idGen: idGen, clock: clock, tickets: map[string]TicketProjection{}}
}

func (s *Service) WithUnitOfWork(uow UnitOfWork) *Service {
	if s != nil {
		s.uow = uow
	}
	return s
}

func (s *Service) within(ctx context.Context, fn func(context.Context) error) error {
	if s.uow == nil {
		return fn(ctx)
	}
	return s.uow(ctx, fn)
}

type CommandMetadata struct {
	CorrelationID string
	CausationID   string
}

type VerifyBoardingCommand struct {
	EntitlementID    domain.EntitlementRef
	SegmentBookingID domain.SegmentBookingRef
	JourneyOrderID   domain.OrderRef
	TravelerID       domain.TravelerRef
	SegmentRef       domain.SegmentRef
	Source           domain.FulfillmentSource
	SourceEventID    string
	OccurredAt       time.Time
}

type BoardingResult struct {
	FulfillmentRecordID domain.FulfillmentRecordID `json:"fulfillmentRecordId"`
	EntitlementID       domain.EntitlementRef      `json:"entitlementId"`
	Status              domain.FulfillmentStatus   `json:"status"`
	OccurredAt          time.Time                  `json:"occurredAt"`
}

type RecordNoShowCommand struct {
	EntitlementID    domain.EntitlementRef
	SegmentBookingID domain.SegmentBookingRef
	JourneyOrderID   domain.OrderRef
	TravelerID       domain.TravelerRef
	SegmentRef       domain.SegmentRef
	Reason           domain.NoShowReason
}

type NoShowResult struct {
	FulfillmentRecordID domain.FulfillmentRecordID `json:"fulfillmentRecordId"`
	Status              domain.FulfillmentStatus   `json:"status"`
	AssessedAt          time.Time                  `json:"assessedAt"`
}

type FulfillmentCompletedCommand struct {
	EntitlementID    domain.EntitlementRef
	SegmentBookingID domain.SegmentBookingRef
	JourneyOrderID   domain.OrderRef
	TravelerID       domain.TravelerRef
	SegmentRef       domain.SegmentRef
	CompletionSource domain.CompletionSource
	CompletedAt      time.Time
}

type FulfillmentCompletedResult struct {
	FulfillmentRecordID domain.FulfillmentRecordID `json:"fulfillmentRecordId"`
	Status              domain.FulfillmentStatus   `json:"status"`
	CompletedAt         time.Time                  `json:"completedAt"`
}

func (s *Service) VerifyBoarding(ctx context.Context, cmd VerifyBoardingCommand, meta CommandMetadata) (BoardingResult, error) {
	if s == nil || s.repo == nil || s.pub == nil {
		return BoardingResult{}, errors.New("fulfillment service is not configured")
	}
	if err := validateVerifyBoarding(cmd); err != nil {
		return BoardingResult{}, err
	}
	var result BoardingResult
	if err := s.within(ctx, func(txCtx context.Context) error {
		record, err := s.readyRecordForCommand(txCtx, cmd.EntitlementID, cmd.SegmentBookingID, cmd.JourneyOrderID, cmd.TravelerID, cmd.SegmentRef)
		if err != nil {
			return err
		}
		receivedAt := s.clock().UTC()
		if err := record.VerifyBoarding(cmd.EntitlementID, cmd.Source, cmd.SourceEventID, cmd.OccurredAt.UTC(), receivedAt, nil); err != nil {
			return fmt.Errorf("%w: %v", ErrDomainRuleViolation, err)
		}
		if err := s.saveAndPublishPending(txCtx, record, meta); err != nil {
			return err
		}
		result = BoardingResult{FulfillmentRecordID: record.FulfillmentRecordID, EntitlementID: record.EntitlementID, Status: record.Status, OccurredAt: cmd.OccurredAt.UTC()}
		return nil
	}); err != nil {
		return BoardingResult{}, err
	}
	return result, nil
}

func (s *Service) RecordNoShow(ctx context.Context, cmd RecordNoShowCommand, meta CommandMetadata) (NoShowResult, error) {
	if s == nil || s.repo == nil || s.pub == nil {
		return NoShowResult{}, errors.New("fulfillment service is not configured")
	}
	if err := validateNoShow(cmd); err != nil {
		return NoShowResult{}, err
	}
	var result NoShowResult
	if err := s.within(ctx, func(txCtx context.Context) error {
		record, err := s.readyRecordForCommand(txCtx, cmd.EntitlementID, cmd.SegmentBookingID, cmd.JourneyOrderID, cmd.TravelerID, cmd.SegmentRef)
		if err != nil {
			return err
		}
		assessedAt := s.clock().UTC()
		if err := record.RecordNoShow(cmd.Reason, assessedAt); err != nil {
			return fmt.Errorf("%w: %v", ErrConflict, err)
		}
		if err := s.saveAndPublishPending(txCtx, record, meta); err != nil {
			return err
		}
		result = NoShowResult{FulfillmentRecordID: record.FulfillmentRecordID, Status: record.Status, AssessedAt: assessedAt}
		return nil
	}); err != nil {
		return NoShowResult{}, err
	}
	return result, nil
}

func (s *Service) RecordFulfillmentCompleted(ctx context.Context, cmd FulfillmentCompletedCommand, meta CommandMetadata) (FulfillmentCompletedResult, error) {
	if s == nil || s.repo == nil || s.pub == nil {
		return FulfillmentCompletedResult{}, errors.New("fulfillment service is not configured")
	}
	if err := validateSegmentProgress(cmd.EntitlementID, cmd.SegmentBookingID, cmd.JourneyOrderID, cmd.TravelerID, cmd.SegmentRef, domain.FulfillmentSource(cmd.CompletionSource), cmd.CompletedAt, "completedAt"); err != nil {
		return FulfillmentCompletedResult{}, err
	}
	var result FulfillmentCompletedResult
	if err := s.within(ctx, func(txCtx context.Context) error {
		record, err := s.readyRecordForCommand(txCtx, cmd.EntitlementID, cmd.SegmentBookingID, cmd.JourneyOrderID, cmd.TravelerID, cmd.SegmentRef)
		if err != nil {
			return err
		}
		if err := record.CompleteFulfillment(cmd.CompletionSource, cmd.CompletedAt.UTC()); err != nil {
			return fmt.Errorf("%w: %v", ErrDomainRuleViolation, err)
		}
		if err := s.saveAndPublishPending(txCtx, record, meta); err != nil {
			return err
		}
		result = FulfillmentCompletedResult{FulfillmentRecordID: record.FulfillmentRecordID, Status: record.Status, CompletedAt: cmd.CompletedAt.UTC()}
		return nil
	}); err != nil {
		return FulfillmentCompletedResult{}, err
	}
	return result, nil
}

func (s *Service) GetFulfillmentRecord(ctx context.Context, id domain.FulfillmentRecordID) (*domain.FulfillmentRecord, error) {
	if err := id.Validate(); err != nil {
		return nil, err
	}
	return s.repo.FindByID(ctx, id)
}

func (s *Service) HandleSubscribedEvent(ctx context.Context, envelope EventEnvelope) error {
	if strings.TrimSpace(envelope.EventID) == "" {
		return kitmsg.FatalHandlerError(fmt.Errorf("eventId is required"))
	}
	if envelope.EventType == "" || envelope.Producer == "" {
		return kitmsg.FatalHandlerError(fmt.Errorf("invalid envelope"))
	}
	if s.consumed != nil {
		claimed, err := s.consumed.Claim(ctx, envelope.EventID)
		if err != nil {
			return kitmsg.TransientHandlerError(err)
		}
		if !claimed {
			return nil
		}
	}
	if err := s.applySubscribedEvent(ctx, envelope); err != nil {
		if errors.Is(err, ErrDomainRuleViolation) || errors.Is(err, ErrNotFound) {
			return kitmsg.FatalHandlerError(err)
		}
		return kitmsg.TransientHandlerError(err)
	}
	return nil
}

func (s *Service) applySubscribedEvent(ctx context.Context, envelope EventEnvelope) error {
	if len(envelope.Payload) == 0 {
		return nil
	}
	switch envelope.EventType {
	case "EntitlementIssued":
		return s.applyEntitlementIssued(ctx, envelope.Payload)
	case "EntitlementVoided":
		return s.applyEntitlementVoided(envelope.Payload)
	case "SegmentTicketed":
		return s.applySegmentTicketed(ctx, envelope.Payload)
	default:
		return nil
	}
}

func (s *Service) applyEntitlementIssued(ctx context.Context, payload json.RawMessage) error {
	var event struct {
		EntitlementID    string `json:"entitlementId"`
		SegmentBookingID string `json:"segmentBookingId"`
		JourneyOrderID   string `json:"journeyOrderId"`
		TravelerRef      string `json:"travelerRef"`
		SegmentRef       string `json:"segmentRef"`
	}
	if err := json.Unmarshal(payload, &event); err != nil {
		return fmt.Errorf("%w: invalid EntitlementIssued payload: %v", ErrDomainRuleViolation, err)
	}
	projection := TicketProjection{EntitlementID: domain.EntitlementRef(event.EntitlementID), SegmentBookingID: domain.SegmentBookingRef(event.SegmentBookingID), JourneyOrderID: domain.OrderRef(event.JourneyOrderID), TravelerID: domain.TravelerRef(event.TravelerRef), SegmentRef: domain.SegmentRef(event.SegmentRef)}
	if err := projection.validate(); err != nil {
		return fmt.Errorf("%w: %v", ErrDomainRuleViolation, err)
	}
	return s.upsertTicketAndRecord(ctx, projection)
}

func (s *Service) applySegmentTicketed(ctx context.Context, payload json.RawMessage) error {
	var event struct {
		EntitlementID    string `json:"entitlementId"`
		SegmentBookingID string `json:"segmentBookingId"`
	}
	if err := json.Unmarshal(payload, &event); err != nil {
		return fmt.Errorf("%w: invalid SegmentTicketed payload: %v", ErrDomainRuleViolation, err)
	}
	entitlementID := domain.EntitlementRef(event.EntitlementID)
	segmentBookingID := domain.SegmentBookingRef(event.SegmentBookingID)
	if err := entitlementID.Validate(); err != nil {
		return fmt.Errorf("%w: %v", ErrDomainRuleViolation, err)
	}
	if err := segmentBookingID.Validate(); err != nil {
		return fmt.Errorf("%w: %v", ErrDomainRuleViolation, err)
	}
	s.mu.Lock()
	projection, exists := s.tickets[ticketKey(entitlementID, segmentBookingID)]
	if exists {
		projection.SegmentBookingID = segmentBookingID
		s.tickets[ticketKey(entitlementID, segmentBookingID)] = projection
	}
	s.mu.Unlock()
	if !exists {
		return nil
	}
	_, err := s.ensureRecordFromProjection(ctx, projection)
	return err
}

func (s *Service) applyEntitlementVoided(payload json.RawMessage) error {
	var event struct {
		EntitlementID    string `json:"entitlementId"`
		SegmentBookingID string `json:"segmentBookingId"`
	}
	if err := json.Unmarshal(payload, &event); err != nil {
		return fmt.Errorf("%w: invalid EntitlementVoided payload: %v", ErrDomainRuleViolation, err)
	}
	entitlementID := domain.EntitlementRef(event.EntitlementID)
	if err := entitlementID.Validate(); err != nil {
		return fmt.Errorf("%w: %v", ErrDomainRuleViolation, err)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if strings.TrimSpace(event.SegmentBookingID) != "" {
		segmentBookingID := domain.SegmentBookingRef(event.SegmentBookingID)
		if err := segmentBookingID.Validate(); err != nil {
			return fmt.Errorf("%w: %v", ErrDomainRuleViolation, err)
		}
		key := ticketKey(entitlementID, segmentBookingID)
		projection, ok := s.tickets[key]
		if !ok {
			return nil
		}
		projection.Voided = true
		s.tickets[key] = projection
		return nil
	}
	for key, projection := range s.tickets {
		if projection.EntitlementID == entitlementID {
			projection.Voided = true
			s.tickets[key] = projection
		}
	}
	return nil
}

func (s *Service) readyRecordForCommand(ctx context.Context, entitlementID domain.EntitlementRef, segmentBookingID domain.SegmentBookingRef, journeyOrderID domain.OrderRef, travelerID domain.TravelerRef, segmentRef domain.SegmentRef) (*domain.FulfillmentRecord, error) {
	record, err := s.readyRecord(ctx, entitlementID, segmentBookingID, segmentRef)
	if err != nil {
		return nil, err
	}
	if record.JourneyOrderID != journeyOrderID || record.TravelerID != travelerID {
		return nil, fmt.Errorf("%w: command references do not match persisted fulfillment record", ErrDomainRuleViolation)
	}
	return record, nil
}

func (s *Service) readyRecord(ctx context.Context, entitlementID domain.EntitlementRef, segmentBookingID domain.SegmentBookingRef, segmentRef domain.SegmentRef) (*domain.FulfillmentRecord, error) {
	record, err := s.repo.FindByEntitlementSegment(ctx, entitlementID, segmentBookingID, segmentRef)
	if err == nil {
		if s.isTicketVoided(entitlementID, segmentBookingID) {
			return nil, fmt.Errorf("%w: entitlement is VOIDED", ErrDomainRuleViolation)
		}
		return record, nil
	}
	if !errors.Is(err, ErrNotFound) {
		return nil, err
	}

	s.mu.Lock()
	projection, ok := s.tickets[ticketKey(entitlementID, segmentBookingID)]
	s.mu.Unlock()
	if !ok {
		return nil, ErrNotFound
	}
	if projection.Voided {
		return nil, fmt.Errorf("%w: entitlement is VOIDED", ErrDomainRuleViolation)
	}
	if projection.SegmentRef != segmentRef {
		return nil, fmt.Errorf("%w: segmentRef does not match ticket read model", ErrDomainRuleViolation)
	}
	return s.ensureRecordFromProjection(ctx, projection)
}

func (s *Service) isTicketVoided(entitlementID domain.EntitlementRef, segmentBookingID domain.SegmentBookingRef) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	projection, ok := s.tickets[ticketKey(entitlementID, segmentBookingID)]
	return ok && projection.Voided
}

func (s *Service) upsertTicketAndRecord(ctx context.Context, projection TicketProjection) error {
	s.mu.Lock()
	s.tickets[ticketKey(projection.EntitlementID, projection.SegmentBookingID)] = projection
	s.mu.Unlock()
	_, err := s.ensureRecordFromProjection(ctx, projection)
	return err
}

func (s *Service) ensureRecordFromProjection(ctx context.Context, projection TicketProjection) (*domain.FulfillmentRecord, error) {
	record, err := s.repo.FindByEntitlementSegment(ctx, projection.EntitlementID, projection.SegmentBookingID, projection.SegmentRef)
	if err == nil {
		return record, nil
	}
	if !errors.Is(err, ErrNotFound) {
		return nil, err
	}
	record, err = domain.NewFulfillmentRecord(domain.FulfillmentRecordID(s.idGen("fr")), projection.EntitlementID, projection.SegmentBookingID, projection.JourneyOrderID, projection.TravelerID, projection.SegmentRef)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrDomainRuleViolation, err)
	}
	if err := s.repo.Save(ctx, record); err != nil {
		return nil, err
	}
	return record, nil
}

func (p TicketProjection) validate() error {
	if err := p.EntitlementID.Validate(); err != nil {
		return err
	}
	if err := p.SegmentBookingID.Validate(); err != nil {
		return err
	}
	if err := p.JourneyOrderID.Validate(); err != nil {
		return err
	}
	if err := p.TravelerID.Validate(); err != nil {
		return err
	}
	return p.SegmentRef.Validate()
}

func ticketKey(entitlementID domain.EntitlementRef, segmentBookingID domain.SegmentBookingRef) string {
	return string(entitlementID) + "|" + string(segmentBookingID)
}

func (s *Service) saveAndPublishPending(ctx context.Context, record *domain.FulfillmentRecord, meta CommandMetadata) error {
	if err := s.repo.Save(ctx, record); err != nil {
		return err
	}
	if err := s.publishEvents(ctx, record.PendingEvents(), meta); err != nil {
		return err
	}
	record.ClearEvents()
	return s.repo.Save(ctx, record)
}

func (s *Service) publishEvents(ctx context.Context, events []domain.DomainEvent, meta CommandMetadata) error {
	for _, event := range events {
		envelope, err := s.WrapDomainEvent(ctx, event, meta)
		if err != nil {
			return err
		}
		if err := s.pub.Publish(ctx, envelope); err != nil {
			return fmt.Errorf("%w: %v", ErrPublishFailed, err)
		}
	}
	return nil
}

func (s *Service) WrapDomainEvent(ctx context.Context, event domain.DomainEvent, meta CommandMetadata) (EventEnvelope, error) {
	payload, err := MarshalDomainEventPayload(event)
	if err != nil {
		return EventEnvelope{}, err
	}
	options := []kitmsg.EnvelopeOptions{{Now: event.OccurredAt().UTC(), Context: ctx}}
	if strings.TrimSpace(meta.CausationID) != "" {
		options[0].CausationID = meta.CausationID
	}
	return kitmsg.NewEventEnvelope(event.EventType(), ProducerName, meta.CorrelationID, payload, options...)
}

func validateVerifyBoarding(cmd VerifyBoardingCommand) error {
	if err := cmd.EntitlementID.Validate(); err != nil {
		return err
	}
	if err := cmd.SegmentBookingID.Validate(); err != nil {
		return err
	}
	if err := cmd.JourneyOrderID.Validate(); err != nil {
		return err
	}
	if err := cmd.TravelerID.Validate(); err != nil {
		return err
	}
	if err := cmd.SegmentRef.Validate(); err != nil {
		return err
	}
	if !validSource(cmd.Source) {
		return fmt.Errorf("invalid source: %s", cmd.Source)
	}
	if strings.TrimSpace(cmd.SourceEventID) == "" {
		return errors.New("sourceEventId is required")
	}
	if cmd.OccurredAt.IsZero() {
		return errors.New("occurredAt is required")
	}
	return nil
}

func validateNoShow(cmd RecordNoShowCommand) error {
	if err := validateReferences(cmd.EntitlementID, cmd.SegmentBookingID, cmd.JourneyOrderID, cmd.TravelerID, cmd.SegmentRef); err != nil {
		return err
	}
	if !validNoShowReason(cmd.Reason) {
		return fmt.Errorf("invalid reason: %s", cmd.Reason)
	}
	return nil
}

func validateSegmentProgress(entitlementID domain.EntitlementRef, segmentBookingID domain.SegmentBookingRef, journeyOrderID domain.OrderRef, travelerID domain.TravelerRef, segmentRef domain.SegmentRef, source domain.FulfillmentSource, occurredAt time.Time, field string) error {
	if err := validateReferences(entitlementID, segmentBookingID, journeyOrderID, travelerID, segmentRef); err != nil {
		return err
	}
	if !validCompletionSource(domain.CompletionSource(source)) {
		return fmt.Errorf("invalid source: %s", source)
	}
	if occurredAt.IsZero() {
		return errors.New(field + " is required")
	}
	return nil
}

func validateReferences(entitlementID domain.EntitlementRef, segmentBookingID domain.SegmentBookingRef, journeyOrderID domain.OrderRef, travelerID domain.TravelerRef, segmentRef domain.SegmentRef) error {
	if err := entitlementID.Validate(); err != nil {
		return err
	}
	if err := segmentBookingID.Validate(); err != nil {
		return err
	}
	if err := journeyOrderID.Validate(); err != nil {
		return err
	}
	if err := travelerID.Validate(); err != nil {
		return err
	}
	return segmentRef.Validate()
}

func validSource(source domain.FulfillmentSource) bool {
	switch source {
	case domain.FulfillmentSourceGate, domain.FulfillmentSourceStation, domain.FulfillmentSourceProvider, domain.FulfillmentSourceConductor, domain.FulfillmentSourceAdmin:
		return true
	default:
		return false
	}
}

func validNoShowReason(reason domain.NoShowReason) bool {
	switch reason {
	case domain.NoShowReasonWindowExpired, domain.NoShowReasonVerificationFailed, domain.NoShowReasonManualRecord:
		return true
	default:
		return false
	}
}

func validCompletionSource(source domain.CompletionSource) bool {
	switch source {
	case domain.CompletionSourceArrival, domain.CompletionSourceProvider, domain.CompletionSourceAdmin, domain.CompletionSourceSystem:
		return true
	default:
		return false
	}
}

type boardingPayload struct {
	FulfillmentRecordID domain.FulfillmentRecordID `json:"fulfillmentRecordId"`
	EntitlementID       domain.EntitlementRef      `json:"entitlementId"`
	SegmentBookingID    domain.SegmentBookingRef   `json:"segmentBookingId"`
	JourneyOrderID      domain.OrderRef            `json:"journeyOrderId"`
	TravelerID          domain.TravelerRef         `json:"travelerId"`
	SegmentRef          domain.SegmentRef          `json:"segmentRef"`
	Source              domain.FulfillmentSource   `json:"source"`
	SourceEventID       string                     `json:"sourceEventId"`
	OccurredAt          time.Time                  `json:"occurredAt"`
	ReceivedAt          time.Time                  `json:"receivedAt"`
	LocationSnapshot    *domain.LocationSnapshot   `json:"locationSnapshot,omitempty"`
}

type noShowPayload struct {
	FulfillmentRecordID domain.FulfillmentRecordID `json:"fulfillmentRecordId"`
	EntitlementID       domain.EntitlementRef      `json:"entitlementId"`
	SegmentBookingID    domain.SegmentBookingRef   `json:"segmentBookingId"`
	JourneyOrderID      domain.OrderRef            `json:"journeyOrderId"`
	TravelerID          domain.TravelerRef         `json:"travelerId"`
	SegmentRef          domain.SegmentRef          `json:"segmentRef"`
	Reason              domain.NoShowReason        `json:"reason"`
	AssessedAt          time.Time                  `json:"assessedAt"`
}

type segmentCompletedPayload struct {
	FulfillmentRecordID domain.FulfillmentRecordID `json:"fulfillmentRecordId"`
	EntitlementID       domain.EntitlementRef      `json:"entitlementId"`
	SegmentBookingID    domain.SegmentBookingRef   `json:"segmentBookingId"`
	JourneyOrderID      domain.OrderRef            `json:"journeyOrderId"`
	TravelerID          domain.TravelerRef         `json:"travelerId"`
	CompletedAt         time.Time                  `json:"completedAt"`
	CompletionSource    domain.CompletionSource    `json:"completionSource"`
}

func MarshalDomainEventPayload(event domain.DomainEvent) (json.RawMessage, error) {
	var payload any
	switch e := event.(type) {
	case domain.BoardingVerifiedEvent:
		payload = boardingPayload{e.FulfillmentRecordID, e.EntitlementID, e.SegmentBookingID, e.JourneyOrderID, e.TravelerID, e.SegmentRef, e.Source, e.SourceEventID, e.OccurredAt().UTC(), e.ReceivedAt.UTC(), e.LocationSnapshot}
	case domain.NoShowRecordedEvent:
		payload = noShowPayload{e.FulfillmentRecordID, e.EntitlementID, e.SegmentBookingID, e.JourneyOrderID, e.TravelerID, e.SegmentRef, e.Reason, e.OccurredAt().UTC()}
	case domain.FulfillmentCompletedEvent:
		payload = segmentCompletedPayload{e.FulfillmentRecordID, e.EntitlementID, e.SegmentBookingID, e.JourneyOrderID, e.TravelerID, e.OccurredAt().UTC(), e.CompletionSource}
	default:
		payload = event
	}
	bytes, err := json.Marshal(payload)
	return bytes, err
}

func NewPrefixedID(prefix string) string { return ids.NewPrefixed(prefix) }

func newUUIDLike() string { return ids.NewUUIDv7() }

// InMemoryRepository is the default process-local repository used by the HTTP runtime.
type InMemoryRepository struct {
	mu      sync.RWMutex
	byID    map[domain.FulfillmentRecordID]*domain.FulfillmentRecord
	byTuple map[string]domain.FulfillmentRecordID
}

func NewInMemoryRepository() *InMemoryRepository {
	return &InMemoryRepository{byID: map[domain.FulfillmentRecordID]*domain.FulfillmentRecord{}, byTuple: map[string]domain.FulfillmentRecordID{}}
}

func (r *InMemoryRepository) Save(_ context.Context, record *domain.FulfillmentRecord) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.byID[record.FulfillmentRecordID] = cloneRecord(record)
	r.byTuple[tupleKey(record.EntitlementID, record.SegmentBookingID, record.SegmentRef)] = record.FulfillmentRecordID
	return nil
}

func (r *InMemoryRepository) FindByID(_ context.Context, id domain.FulfillmentRecordID) (*domain.FulfillmentRecord, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	record, ok := r.byID[id]
	if !ok {
		return nil, ErrNotFound
	}
	return cloneRecord(record), nil
}

func (r *InMemoryRepository) FindByEntitlementSegment(_ context.Context, entitlementID domain.EntitlementRef, segmentBookingID domain.SegmentBookingRef, segmentRef domain.SegmentRef) (*domain.FulfillmentRecord, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	id, ok := r.byTuple[tupleKey(entitlementID, segmentBookingID, segmentRef)]
	if !ok {
		return nil, ErrNotFound
	}
	return cloneRecord(r.byID[id]), nil
}

func tupleKey(entitlementID domain.EntitlementRef, segmentBookingID domain.SegmentBookingRef, segmentRef domain.SegmentRef) string {
	return string(entitlementID) + "|" + string(segmentBookingID) + "|" + string(segmentRef)
}

func cloneRecord(record *domain.FulfillmentRecord) *domain.FulfillmentRecord {
	if record == nil {
		return nil
	}
	copy := *record
	if record.BoardingFact != nil {
		fact := *record.BoardingFact
		copy.BoardingFact = &fact
	}
	if record.NoShowReason != nil {
		reason := *record.NoShowReason
		copy.NoShowReason = &reason
	}
	if record.NoShowAssessedAt != nil {
		t := *record.NoShowAssessedAt
		copy.NoShowAssessedAt = &t
	}
	if record.CompletedAt != nil {
		t := *record.CompletedAt
		copy.CompletedAt = &t
	}
	if record.CompletionSource != nil {
		source := *record.CompletionSource
		copy.CompletionSource = &source
	}
	copy.AuditTrail = append([]domain.AuditEntry(nil), record.AuditTrail...)
	copy.RestorePendingEvents(record.PendingEvents())
	return &copy
}

type InMemoryConsumedEventLog struct {
	mu   sync.Mutex
	seen map[string]struct{}
}

func NewInMemoryConsumedEventLog() *InMemoryConsumedEventLog {
	return &InMemoryConsumedEventLog{seen: map[string]struct{}{}}
}
func (l *InMemoryConsumedEventLog) Claim(_ context.Context, eventID string) (bool, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if _, ok := l.seen[eventID]; ok {
		return false, nil
	}
	l.seen[eventID] = struct{}{}
	return true, nil
}
func (l *InMemoryConsumedEventLog) AlreadyConsumed(_ context.Context, eventID string) (bool, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	_, ok := l.seen[eventID]
	return ok, nil
}
