package application

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
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
	ErrAckSkip             = errors.New("ack skip")
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

type SegmentStatusRepository interface {
	SaveSegmentStatus(ctx context.Context, record *domain.SegmentStatusRecord) (bool, error)
	FindSegmentStatusByCommandID(ctx context.Context, commandID string) (*domain.SegmentStatusRecord, error)
}

type AncillaryHandoffRepository interface {
	SaveAncillaryHandoff(ctx context.Context, handoff *domain.AncillaryFulfillmentHandoff) error
	FindAncillaryHandoff(ctx context.Context, ancillaryOrderItemID string) (*domain.AncillaryFulfillmentHandoff, error)
}

type RideExecutionRepository interface {
	SaveRideExecution(ctx context.Context, view *domain.RideExecutionView) error
	FindRideExecution(ctx context.Context, rideRequestID string) (*domain.RideExecutionView, error)
}

type ConsumedEventLog interface {
	Claim(ctx context.Context, eventID string) (bool, error)
}

type UnitOfWork func(context.Context, func(context.Context) error) error

type IDGenerator func(prefix string) string

type Clock func() time.Time

type Service struct {
	repo       FulfillmentRepository
	segments   SegmentStatusRepository
	ancillary  AncillaryHandoffRepository
	rides      RideExecutionRepository
	pub        EventPublisher
	consumed   ConsumedEventLog
	idGen      IDGenerator
	clock      Clock
	uow        UnitOfWork
	mu         sync.Mutex
	tickets    map[string]TicketProjection
	ancInMem   map[string]*domain.AncillaryFulfillmentHandoff
	ridesInMem map[string]*domain.RideExecutionView
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
	segments, _ := repo.(SegmentStatusRepository)
	ancillary, _ := repo.(AncillaryHandoffRepository)
	rides, _ := repo.(RideExecutionRepository)
	return &Service{repo: repo, segments: segments, ancillary: ancillary, rides: rides, pub: publisher, consumed: consumed, idGen: idGen, clock: clock, tickets: map[string]TicketProjection{}, ancInMem: map[string]*domain.AncillaryFulfillmentHandoff{}, ridesInMem: map[string]*domain.RideExecutionView{}}
}

func (s *Service) WithUnitOfWork(uow UnitOfWork) *Service {
	if s != nil {
		s.uow = uow
	}
	return s
}

func (s *Service) WithSegmentStatusRepository(repo SegmentStatusRepository) *Service {
	if s != nil {
		s.segments = repo
	}
	return s
}

func (s *Service) WithAncillaryHandoffRepository(repo AncillaryHandoffRepository) *Service {
	if s != nil {
		s.ancillary = repo
	}
	return s
}

func (s *Service) WithRideExecutionRepository(repo RideExecutionRepository) *Service {
	if s != nil {
		s.rides = repo
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

type ReportSegmentStatusCommand struct {
	CommandID           string
	SegmentRef          domain.SegmentRef
	ScheduledServiceRef string
	ServiceDate         string
	Status              domain.SegmentOperationalStatus
	EstimatedArrivalAt  *time.Time
	ArrivedAt           *time.Time
	CancelledAt         *time.Time
	ObservedAt          time.Time
	SourceSystem        domain.SegmentStatusSourceSystem
}

type SegmentStatusResult struct {
	SegmentStatusRecordID domain.SegmentStatusRecordID    `json:"segmentStatusRecordId"`
	CommandID             string                          `json:"commandId"`
	SegmentRef            domain.SegmentRef               `json:"segmentRef"`
	Status                domain.SegmentOperationalStatus `json:"status"`
	ObservedAt            time.Time                       `json:"observedAt"`
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

func (s *Service) ReportSegmentStatus(ctx context.Context, cmd ReportSegmentStatusCommand, meta CommandMetadata) (SegmentStatusResult, error) {
	if s == nil || s.segments == nil || s.pub == nil {
		return SegmentStatusResult{}, errors.New("fulfillment service is not configured")
	}
	if err := validateReportSegmentStatus(cmd); err != nil {
		return SegmentStatusResult{}, err
	}
	var result SegmentStatusResult
	if err := s.within(ctx, func(txCtx context.Context) error {
		existing, err := s.segments.FindSegmentStatusByCommandID(txCtx, strings.TrimSpace(cmd.CommandID))
		if err == nil {
			result = segmentStatusResult(existing)
			return nil
		}
		if !errors.Is(err, ErrNotFound) {
			return err
		}
		record, err := domain.NewSegmentStatusRecord(domain.SegmentStatusRecordID(s.idGen("ssr")), strings.TrimSpace(cmd.CommandID), cmd.SegmentRef, cmd.ScheduledServiceRef, cmd.ServiceDate, cmd.Status, cmd.EstimatedArrivalAt, cmd.ArrivedAt, cmd.CancelledAt, cmd.ObservedAt, cmd.SourceSystem)
		if err != nil {
			return fmt.Errorf("%w: %v", ErrDomainRuleViolation, err)
		}
		saved, err := s.segments.SaveSegmentStatus(txCtx, record)
		if err != nil {
			return err
		}
		if saved {
			event := record.PendingEvent()
			if event != nil {
				if err := s.publishEvents(txCtx, []domain.DomainEvent{event}, meta); err != nil {
					return err
				}
				record.ClearEvent()
			}
		}
		result = segmentStatusResult(record)
		return nil
	}); err != nil {
		return SegmentStatusResult{}, err
	}
	return result, nil
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
		if errors.Is(err, ErrAckSkip) {
			log.Printf("WARN service=%s eventId=%s producer=%s eventType=%s ack-skip subscribed event: %v", ProducerName, envelope.EventID, envelope.Producer, envelope.EventType, err)
			return nil
		}
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
	case "AncillaryOrderItemFulfillmentReady":
		return s.applyAncillaryFulfillmentReady(ctx, envelope.EventID, envelope.Payload)
	case "AncillaryFulfillmentFactRecorded":
		return s.applyAncillaryFulfillmentFactRecorded(ctx, envelope.EventID, envelope.Payload)
	case "DriverArrived":
		return s.applyDriverArrived(ctx, envelope.EventID, envelope.Payload)
	case "RideStarted":
		return s.applyRideStarted(ctx, envelope.EventID, envelope.Payload)
	case "RideEnded":
		return s.applyRideEnded(ctx, envelope.EventID, envelope.Payload)
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

func (s *Service) applyAncillaryFulfillmentReady(ctx context.Context, sourceEventID string, payload json.RawMessage) error {
	var event ancillaryReadyEvent
	if err := json.Unmarshal(payload, &event); err != nil {
		return fmt.Errorf("%w: invalid AncillaryOrderItemFulfillmentReady payload: %v", ErrDomainRuleViolation, err)
	}
	if err := validateAncillaryReadyEvent(event); err != nil {
		return err
	}
	handoff, err := s.ancillaryHandoffFor(ctx, event.AncillaryOrderItemID, domain.OrderRef(event.JourneyOrderID), domain.TravelerRef(event.TravelerRef), domain.SegmentRef(event.SegmentRef), event.CatalogSnapshot.CatalogItemID, event.CatalogSnapshot.ServiceType)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrDomainRuleViolation, err)
	}
	if err := handoff.MarkReady(event.Status, event.ReadyAt, event.ProviderRef, domain.EntitlementRef(event.EntitlementRef)); err != nil {
		return fmt.Errorf("%w: %v", ErrAckSkip, err)
	}
	return s.saveAncillaryHandoff(ctx, handoff)
}

func (s *Service) applyAncillaryFulfillmentFactRecorded(ctx context.Context, sourceEventID string, payload json.RawMessage) error {
	var event ancillaryFactRecordedEvent
	if err := json.Unmarshal(payload, &event); err != nil {
		return fmt.Errorf("%w: invalid AncillaryFulfillmentFactRecorded payload: %v", ErrDomainRuleViolation, err)
	}
	if err := validateAncillaryFactRecordedEvent(event); err != nil {
		return err
	}
	handoff, err := s.ancillaryHandoffFor(ctx, event.AncillaryOrderItemID, domain.OrderRef(event.JourneyOrderID), domain.TravelerRef(event.TravelerRef), domain.SegmentRef(event.SegmentRef), event.CatalogItemID, event.ServiceType)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrAckSkip, err)
	}
	fact := domain.AncillaryFulfillmentFact{FulfillmentFactID: event.FulfillmentFact.FulfillmentFactID, FactType: event.FulfillmentFact.FactType, ProviderRef: event.FulfillmentFact.ProviderRef, PlaceRef: event.FulfillmentFact.PlaceRef, OccurredAt: event.FulfillmentFact.OccurredAt.UTC(), RecordedAt: event.FulfillmentFact.RecordedAt.UTC(), PerformedBy: event.FulfillmentFact.PerformedBy, IdempotencyRef: event.FulfillmentFact.IdempotencyRef, Compensable: event.FulfillmentFact.Compensable, SourceEventID: sourceEventID}
	if err := handoff.RecordFact(event.Status, fact); err != nil {
		return fmt.Errorf("%w: %v", ErrAckSkip, err)
	}
	return s.saveAncillaryHandoff(ctx, handoff)
}

func (s *Service) applyDriverArrived(ctx context.Context, sourceEventID string, payload json.RawMessage) error {
	var event dispatchArrivalEvent
	if err := json.Unmarshal(payload, &event); err != nil {
		return fmt.Errorf("%w: invalid DriverArrived payload: %v", ErrDomainRuleViolation, err)
	}
	if err := validateDispatchFields(event.dispatchFields, "DRIVER_ARRIVED"); err != nil {
		return err
	}
	if event.ArrivedAt.IsZero() {
		return fmt.Errorf("%w: arrivedAt is required", ErrDomainRuleViolation)
	}
	view, err := s.rideExecutionFor(ctx, event.RideRequestID, domain.TravelerRef(event.TravelerRef))
	if err != nil {
		return fmt.Errorf("%w: %v", ErrAckSkip, err)
	}
	if err := view.DriverArrived(sourceEventID, event.ArrivedAt, rideSnapshot(event.dispatchFields)); err != nil {
		return fmt.Errorf("%w: %v", ErrAckSkip, err)
	}
	return s.saveRideExecution(ctx, view)
}

func (s *Service) applyRideStarted(ctx context.Context, sourceEventID string, payload json.RawMessage) error {
	var event dispatchStartedEvent
	if err := json.Unmarshal(payload, &event); err != nil {
		return fmt.Errorf("%w: invalid RideStarted payload: %v", ErrDomainRuleViolation, err)
	}
	if err := validateDispatchFields(event.dispatchFields, "PICKED_UP"); err != nil {
		return err
	}
	if event.StartedAt.IsZero() {
		return fmt.Errorf("%w: startedAt is required", ErrDomainRuleViolation)
	}
	view, err := s.rideExecutionFor(ctx, event.RideRequestID, domain.TravelerRef(event.TravelerRef))
	if err != nil {
		return fmt.Errorf("%w: %v", ErrAckSkip, err)
	}
	if err := view.RideStarted(sourceEventID, event.StartedAt, rideSnapshot(event.dispatchFields)); err != nil {
		return fmt.Errorf("%w: %v", ErrAckSkip, err)
	}
	return s.saveRideExecution(ctx, view)
}

func (s *Service) applyRideEnded(ctx context.Context, sourceEventID string, payload json.RawMessage) error {
	var event dispatchEndedEvent
	if err := json.Unmarshal(payload, &event); err != nil {
		return fmt.Errorf("%w: invalid RideEnded payload: %v", ErrDomainRuleViolation, err)
	}
	if err := validateDispatchFields(event.dispatchFields, "COMPLETED"); err != nil {
		return err
	}
	if event.StartedAt.IsZero() || event.EndedAt.IsZero() {
		return fmt.Errorf("%w: startedAt and endedAt are required", ErrDomainRuleViolation)
	}
	view, err := s.rideExecutionFor(ctx, event.RideRequestID, domain.TravelerRef(event.TravelerRef))
	if err != nil {
		return fmt.Errorf("%w: %v", ErrAckSkip, err)
	}
	if err := view.RideEnded(sourceEventID, event.StartedAt, event.EndedAt, rideSnapshot(event.dispatchFields), event.FinalFareRef); err != nil {
		return fmt.Errorf("%w: %v", ErrAckSkip, err)
	}
	return s.saveRideExecution(ctx, view)
}

func (s *Service) ancillaryHandoffFor(ctx context.Context, id string, journeyOrderID domain.OrderRef, travelerRef domain.TravelerRef, segmentRef domain.SegmentRef, catalogItemID, serviceType string) (*domain.AncillaryFulfillmentHandoff, error) {
	if s.ancillary != nil {
		handoff, err := s.ancillary.FindAncillaryHandoff(ctx, id)
		if err == nil {
			return handoff, nil
		}
		if !errors.Is(err, ErrNotFound) {
			return nil, err
		}
	} else {
		s.mu.Lock()
		handoff, ok := s.ancInMem[strings.TrimSpace(id)]
		s.mu.Unlock()
		if ok {
			return cloneAncillaryHandoff(handoff), nil
		}
	}
	return domain.NewAncillaryFulfillmentHandoff(id, journeyOrderID, travelerRef, segmentRef, catalogItemID, serviceType)
}

func (s *Service) saveAncillaryHandoff(ctx context.Context, handoff *domain.AncillaryFulfillmentHandoff) error {
	if s.ancillary != nil {
		return s.ancillary.SaveAncillaryHandoff(ctx, handoff)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.ancInMem[handoff.AncillaryOrderItemID] = cloneAncillaryHandoff(handoff)
	return nil
}

func (s *Service) rideExecutionFor(ctx context.Context, rideRequestID string, travelerRef domain.TravelerRef) (*domain.RideExecutionView, error) {
	if s.rides != nil {
		view, err := s.rides.FindRideExecution(ctx, rideRequestID)
		if err == nil {
			return view, nil
		}
		if !errors.Is(err, ErrNotFound) {
			return nil, err
		}
	} else {
		s.mu.Lock()
		view, ok := s.ridesInMem[strings.TrimSpace(rideRequestID)]
		s.mu.Unlock()
		if ok {
			return cloneRideExecution(view), nil
		}
	}
	return domain.NewRideExecutionView(rideRequestID, travelerRef)
}

func (s *Service) saveRideExecution(ctx context.Context, view *domain.RideExecutionView) error {
	if s.rides != nil {
		return s.rides.SaveRideExecution(ctx, view)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.ridesInMem[view.RideRequestID] = cloneRideExecution(view)
	return nil
}

func (s *Service) GetAncillaryHandoff(ctx context.Context, ancillaryOrderItemID string) (*domain.AncillaryFulfillmentHandoff, error) {
	id := strings.TrimSpace(ancillaryOrderItemID)
	if s.ancillary != nil {
		return s.ancillary.FindAncillaryHandoff(ctx, id)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	handoff, ok := s.ancInMem[id]
	if !ok {
		return nil, ErrNotFound
	}
	return cloneAncillaryHandoff(handoff), nil
}

func (s *Service) GetRideExecution(ctx context.Context, rideRequestID string) (*domain.RideExecutionView, error) {
	id := strings.TrimSpace(rideRequestID)
	if s.rides != nil {
		return s.rides.FindRideExecution(ctx, id)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	view, ok := s.ridesInMem[id]
	if !ok {
		return nil, ErrNotFound
	}
	return cloneRideExecution(view), nil
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
	envelope, err := kitmsg.NewEventEnvelope(event.EventType(), ProducerName, meta.CorrelationID, payload, options...)
	if err != nil {
		return EventEnvelope{}, err
	}
	if deterministic := deterministicSegmentEventID(event); deterministic != "" {
		envelope.EventID = deterministic
	}
	return envelope, envelope.Validate()
}

func segmentStatusResult(record *domain.SegmentStatusRecord) SegmentStatusResult {
	return SegmentStatusResult{SegmentStatusRecordID: record.SegmentStatusRecordID, CommandID: record.CommandID, SegmentRef: record.SegmentRef, Status: record.Status, ObservedAt: record.ObservedAt.UTC()}
}

func validateReportSegmentStatus(cmd ReportSegmentStatusCommand) error {
	if strings.TrimSpace(cmd.CommandID) == "" {
		return errors.New("commandId is required")
	}
	if err := cmd.SegmentRef.Validate(); err != nil {
		return err
	}
	if strings.TrimSpace(cmd.ScheduledServiceRef) == "" {
		return errors.New("scheduledServiceRef is required")
	}
	if strings.TrimSpace(cmd.ServiceDate) == "" {
		return errors.New("serviceDate is required")
	}
	if cmd.ObservedAt.IsZero() {
		return errors.New("observedAt is required")
	}
	switch cmd.SourceSystem {
	case domain.SegmentStatusSourceSystemSystem, domain.SegmentStatusSourceSystemOps:
	default:
		return fmt.Errorf("invalid sourceSystem: %s", cmd.SourceSystem)
	}
	switch cmd.Status {
	case domain.SegmentOperationalStatusDelay:
		if cmd.EstimatedArrivalAt == nil {
			return errors.New("estimatedArrivalAt is required")
		}
	case domain.SegmentOperationalStatusArrival:
		if cmd.ArrivedAt == nil {
			return errors.New("arrivedAt is required")
		}
	case domain.SegmentOperationalStatusCancelled:
		if cmd.CancelledAt == nil {
			return errors.New("cancelledAt is required")
		}
	default:
		return fmt.Errorf("invalid status: %s", cmd.Status)
	}
	return nil
}

func deterministicSegmentEventID(event domain.DomainEvent) string {
	suffix := ""
	switch e := event.(type) {
	case domain.SegmentDelayedEvent:
		suffix = e.CommandID
	case domain.SegmentArrivedEvent:
		suffix = e.CommandID
	case domain.SegmentCancelledEvent:
		suffix = e.CommandID
	default:
		return ""
	}
	return "evt-" + foldedUUIDv7(ProducerName+":"+event.EventType()+":"+strings.TrimSpace(suffix))
}

func foldedUUIDv7(material string) string {
	digest := sha256.Sum256([]byte(material))
	bytes := append([]byte(nil), digest[:16]...)
	bytes[6] = (bytes[6] & 0x0f) | 0x70
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	encoded := hex.EncodeToString(bytes)
	return encoded[0:8] + "-" + encoded[8:12] + "-" + encoded[12:16] + "-" + encoded[16:20] + "-" + encoded[20:32]
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

type segmentDelayedPayload struct {
	SegmentRef          domain.SegmentRef                `json:"segmentRef"`
	ScheduledServiceRef string                           `json:"scheduledServiceRef"`
	ServiceDate         string                           `json:"serviceDate"`
	EstimatedArrivalAt  time.Time                        `json:"estimatedArrivalAt"`
	ObservedAt          time.Time                        `json:"observedAt"`
	SourceSystem        domain.SegmentStatusSourceSystem `json:"sourceSystem"`
}

type segmentArrivedPayload struct {
	SegmentRef          domain.SegmentRef                `json:"segmentRef"`
	ScheduledServiceRef string                           `json:"scheduledServiceRef"`
	ServiceDate         string                           `json:"serviceDate"`
	ArrivedAt           time.Time                        `json:"arrivedAt"`
	ObservedAt          time.Time                        `json:"observedAt"`
	SourceSystem        domain.SegmentStatusSourceSystem `json:"sourceSystem"`
}

type segmentCancelledPayload struct {
	SegmentRef          domain.SegmentRef                `json:"segmentRef"`
	ScheduledServiceRef string                           `json:"scheduledServiceRef"`
	ServiceDate         string                           `json:"serviceDate"`
	CancelledAt         time.Time                        `json:"cancelledAt"`
	ObservedAt          time.Time                        `json:"observedAt"`
	SourceSystem        domain.SegmentStatusSourceSystem `json:"sourceSystem"`
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

type ancillaryReadyEvent struct {
	AncillaryOrderItemID string                   `json:"ancillaryOrderItemId"`
	JourneyOrderID       string                   `json:"journeyOrderId"`
	TravelerRef          string                   `json:"travelerRef"`
	SegmentRef           string                   `json:"segmentRef"`
	CatalogSnapshot      ancillaryCatalogSnapshot `json:"catalogSnapshot"`
	ProviderRef          string                   `json:"providerRef"`
	EntitlementRef       string                   `json:"entitlementRef"`
	Status               string                   `json:"status"`
	ReadyAt              time.Time                `json:"readyAt"`
}

type ancillaryCatalogSnapshot struct {
	CatalogItemID string `json:"catalogItemId"`
	ServiceType   string `json:"serviceType"`
}

type ancillaryFactRecordedEvent struct {
	AncillaryOrderItemID string               `json:"ancillaryOrderItemId"`
	JourneyOrderID       string               `json:"journeyOrderId"`
	TravelerRef          string               `json:"travelerRef"`
	SegmentRef           string               `json:"segmentRef"`
	CatalogItemID        string               `json:"catalogItemId"`
	ServiceType          string               `json:"serviceType"`
	FulfillmentFact      ancillaryFactPayload `json:"fulfillmentFact"`
	Status               string               `json:"status"`
}

type ancillaryFactPayload struct {
	FulfillmentFactID string    `json:"fulfillmentFactId"`
	FactType          string    `json:"factType"`
	ProviderRef       string    `json:"providerRef"`
	PlaceRef          string    `json:"placeRef"`
	OccurredAt        time.Time `json:"occurredAt"`
	RecordedAt        time.Time `json:"recordedAt"`
	PerformedBy       string    `json:"performedBy"`
	IdempotencyRef    string    `json:"idempotencyRef"`
	Compensable       bool      `json:"compensable"`
}

type dispatchFields struct {
	RideRequestID    string `json:"rideRequestId"`
	RideAssignmentID string `json:"rideAssignmentId"`
	RiderAccountID   string `json:"riderAccountId"`
	TravelerRef      string `json:"travelerRef"`
	PickupRef        string `json:"pickupRef"`
	DropoffRef       string `json:"dropoffRef"`
	DriverRef        string `json:"driverRef"`
	VehicleRef       string `json:"vehicleRef"`
	Status           string `json:"status"`
}

type dispatchArrivalEvent struct {
	dispatchFields
	ArrivedAt time.Time `json:"arrivedAt"`
}

type dispatchStartedEvent struct {
	dispatchFields
	StartedAt time.Time `json:"startedAt"`
}

type dispatchEndedEvent struct {
	dispatchFields
	StartedAt    time.Time `json:"startedAt"`
	EndedAt      time.Time `json:"endedAt"`
	FinalFareRef string    `json:"finalFareRef"`
}

func validateAncillaryReadyEvent(event ancillaryReadyEvent) error {
	if strings.TrimSpace(event.AncillaryOrderItemID) == "" || strings.TrimSpace(event.JourneyOrderID) == "" || strings.TrimSpace(event.TravelerRef) == "" || strings.TrimSpace(event.CatalogSnapshot.CatalogItemID) == "" || strings.TrimSpace(event.CatalogSnapshot.ServiceType) == "" || event.ReadyAt.IsZero() {
		return fmt.Errorf("%w: missing required AncillaryOrderItemFulfillmentReady field", ErrDomainRuleViolation)
	}
	return nil
}

func validateAncillaryFactRecordedEvent(event ancillaryFactRecordedEvent) error {
	if strings.TrimSpace(event.AncillaryOrderItemID) == "" || strings.TrimSpace(event.JourneyOrderID) == "" || strings.TrimSpace(event.TravelerRef) == "" || strings.TrimSpace(event.CatalogItemID) == "" || strings.TrimSpace(event.ServiceType) == "" {
		return fmt.Errorf("%w: missing required AncillaryFulfillmentFactRecorded field", ErrDomainRuleViolation)
	}
	fact := event.FulfillmentFact
	if strings.TrimSpace(fact.FulfillmentFactID) == "" || strings.TrimSpace(fact.FactType) == "" || strings.TrimSpace(fact.PerformedBy) == "" || strings.TrimSpace(fact.IdempotencyRef) == "" || fact.OccurredAt.IsZero() || fact.RecordedAt.IsZero() {
		return fmt.Errorf("%w: missing required AncillaryFulfillmentFactRecorded fulfillmentFact field", ErrDomainRuleViolation)
	}
	return nil
}

func validateDispatchFields(fields dispatchFields, expectedStatus string) error {
	if strings.TrimSpace(fields.RideRequestID) == "" || strings.TrimSpace(fields.RideAssignmentID) == "" || strings.TrimSpace(fields.RiderAccountID) == "" || strings.TrimSpace(fields.TravelerRef) == "" || strings.TrimSpace(fields.PickupRef) == "" || strings.TrimSpace(fields.DropoffRef) == "" || strings.TrimSpace(fields.DriverRef) == "" || strings.TrimSpace(fields.VehicleRef) == "" || strings.TrimSpace(fields.Status) == "" {
		return fmt.Errorf("%w: missing required dispatch handoff field", ErrDomainRuleViolation)
	}
	if strings.TrimSpace(fields.Status) != expectedStatus {
		return fmt.Errorf("%w: unexpected dispatch status %q for expected %s", ErrAckSkip, fields.Status, expectedStatus)
	}
	return nil
}

func rideSnapshot(fields dispatchFields) domain.RideAssignmentSnapshot {
	return domain.RideAssignmentSnapshot{RideAssignmentID: fields.RideAssignmentID, RiderAccountID: fields.RiderAccountID, TravelerRef: domain.TravelerRef(fields.TravelerRef), PickupRef: fields.PickupRef, DropoffRef: fields.DropoffRef, DriverRef: fields.DriverRef, VehicleRef: fields.VehicleRef}
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
	case domain.SegmentDelayedEvent:
		payload = segmentDelayedPayload{e.SegmentRef, e.ScheduledServiceRef, e.ServiceDate, e.EstimatedArrivalAt.UTC(), e.ObservedAt.UTC(), e.SourceSystem}
	case domain.SegmentArrivedEvent:
		payload = segmentArrivedPayload{e.SegmentRef, e.ScheduledServiceRef, e.ServiceDate, e.ArrivedAt.UTC(), e.ObservedAt.UTC(), e.SourceSystem}
	case domain.SegmentCancelledEvent:
		payload = segmentCancelledPayload{e.SegmentRef, e.ScheduledServiceRef, e.ServiceDate, e.CancelledAt.UTC(), e.ObservedAt.UTC(), e.SourceSystem}
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
	mu               sync.RWMutex
	byID             map[domain.FulfillmentRecordID]*domain.FulfillmentRecord
	byTuple          map[string]domain.FulfillmentRecordID
	segmentByID      map[domain.SegmentStatusRecordID]*domain.SegmentStatusRecord
	segmentByCommand map[string]domain.SegmentStatusRecordID
	ancillary        map[string]*domain.AncillaryFulfillmentHandoff
	rides            map[string]*domain.RideExecutionView
}

func NewInMemoryRepository() *InMemoryRepository {
	return &InMemoryRepository{byID: map[domain.FulfillmentRecordID]*domain.FulfillmentRecord{}, byTuple: map[string]domain.FulfillmentRecordID{}, segmentByID: map[domain.SegmentStatusRecordID]*domain.SegmentStatusRecord{}, segmentByCommand: map[string]domain.SegmentStatusRecordID{}, ancillary: map[string]*domain.AncillaryFulfillmentHandoff{}, rides: map[string]*domain.RideExecutionView{}}
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

func (r *InMemoryRepository) SaveSegmentStatus(_ context.Context, record *domain.SegmentStatusRecord) (bool, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	commandID := strings.TrimSpace(record.CommandID)
	if _, ok := r.segmentByCommand[commandID]; ok {
		return false, nil
	}
	r.segmentByID[record.SegmentStatusRecordID] = cloneSegmentStatus(record)
	r.segmentByCommand[commandID] = record.SegmentStatusRecordID
	return true, nil
}

func (r *InMemoryRepository) FindSegmentStatusByCommandID(_ context.Context, commandID string) (*domain.SegmentStatusRecord, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	id, ok := r.segmentByCommand[strings.TrimSpace(commandID)]
	if !ok {
		return nil, ErrNotFound
	}
	return cloneSegmentStatus(r.segmentByID[id]), nil
}

func (r *InMemoryRepository) SaveAncillaryHandoff(_ context.Context, handoff *domain.AncillaryFulfillmentHandoff) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.ancillary[handoff.AncillaryOrderItemID] = cloneAncillaryHandoff(handoff)
	return nil
}

func (r *InMemoryRepository) FindAncillaryHandoff(_ context.Context, ancillaryOrderItemID string) (*domain.AncillaryFulfillmentHandoff, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	handoff, ok := r.ancillary[strings.TrimSpace(ancillaryOrderItemID)]
	if !ok {
		return nil, ErrNotFound
	}
	return cloneAncillaryHandoff(handoff), nil
}

func (r *InMemoryRepository) SaveRideExecution(_ context.Context, view *domain.RideExecutionView) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.rides[view.RideRequestID] = cloneRideExecution(view)
	return nil
}

func (r *InMemoryRepository) FindRideExecution(_ context.Context, rideRequestID string) (*domain.RideExecutionView, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	view, ok := r.rides[strings.TrimSpace(rideRequestID)]
	if !ok {
		return nil, ErrNotFound
	}
	return cloneRideExecution(view), nil
}

func cloneSegmentStatus(record *domain.SegmentStatusRecord) *domain.SegmentStatusRecord {
	if record == nil {
		return nil
	}
	copy := *record
	if record.EstimatedArrivalAt != nil {
		t := *record.EstimatedArrivalAt
		copy.EstimatedArrivalAt = &t
	}
	if record.ArrivedAt != nil {
		t := *record.ArrivedAt
		copy.ArrivedAt = &t
	}
	if record.CancelledAt != nil {
		t := *record.CancelledAt
		copy.CancelledAt = &t
	}
	return &copy
}

func tupleKey(entitlementID domain.EntitlementRef, segmentBookingID domain.SegmentBookingRef, segmentRef domain.SegmentRef) string {
	return string(entitlementID) + "|" + string(segmentBookingID) + "|" + string(segmentRef)
}

func cloneAncillaryHandoff(handoff *domain.AncillaryFulfillmentHandoff) *domain.AncillaryFulfillmentHandoff {
	if handoff == nil {
		return nil
	}
	copy := *handoff
	if handoff.ReadyAt != nil {
		t := *handoff.ReadyAt
		copy.ReadyAt = &t
	}
	copy.Facts = append([]domain.AncillaryFulfillmentFact(nil), handoff.Facts...)
	return &copy
}

func cloneRideExecution(view *domain.RideExecutionView) *domain.RideExecutionView {
	if view == nil {
		return nil
	}
	copy := *view
	if view.DriverArrivedAt != nil {
		t := *view.DriverArrivedAt
		copy.DriverArrivedAt = &t
	}
	if view.RideStartedAt != nil {
		t := *view.RideStartedAt
		copy.RideStartedAt = &t
	}
	if view.RideEndedAt != nil {
		t := *view.RideEndedAt
		copy.RideEndedAt = &t
	}
	copy.Evidence = append([]domain.RideExecutionEvidence(nil), view.Evidence...)
	return &copy
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
