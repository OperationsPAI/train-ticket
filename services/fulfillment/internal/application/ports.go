package application

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"sync"
	"time"

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

type EventEnvelope struct {
	EventID       string          `json:"eventId"`
	EventType     string          `json:"eventType"`
	OccurredAt    time.Time       `json:"occurredAt"`
	CorrelationID string          `json:"correlationId"`
	CausationID   string          `json:"causationId"`
	Producer      string          `json:"producer"`
	SchemaVersion int             `json:"schemaVersion"`
	Payload       json.RawMessage `json:"payload"`
}

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

type IDGenerator func(prefix string) string

type Clock func() time.Time

type Service struct {
	repo     FulfillmentRepository
	pub      EventPublisher
	consumed ConsumedEventLog
	idGen    IDGenerator
	clock    Clock
}

func NewService(repo FulfillmentRepository, publisher EventPublisher, consumed ConsumedEventLog, idGen IDGenerator, clock Clock) *Service {
	if idGen == nil {
		idGen = NewPrefixedID
	}
	if clock == nil {
		clock = func() time.Time { return time.Now().UTC() }
	}
	return &Service{repo: repo, pub: publisher, consumed: consumed, idGen: idGen, clock: clock}
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

func (s *Service) VerifyBoarding(ctx context.Context, cmd VerifyBoardingCommand, meta CommandMetadata) (BoardingResult, error) {
	if s == nil || s.repo == nil || s.pub == nil {
		return BoardingResult{}, errors.New("fulfillment service is not configured")
	}
	if err := validateVerifyBoarding(cmd); err != nil {
		return BoardingResult{}, err
	}
	record, err := s.repo.FindByEntitlementSegment(ctx, cmd.EntitlementID, cmd.SegmentBookingID, cmd.SegmentRef)
	if err != nil && !errors.Is(err, ErrNotFound) {
		return BoardingResult{}, err
	}
	if errors.Is(err, ErrNotFound) {
		record, err = domain.NewFulfillmentRecord(domain.FulfillmentRecordID(s.idGen("fr")), cmd.EntitlementID, cmd.SegmentBookingID, cmd.JourneyOrderID, cmd.TravelerID, cmd.SegmentRef)
		if err != nil {
			return BoardingResult{}, fmt.Errorf("%w: %v", ErrDomainRuleViolation, err)
		}
	}
	receivedAt := s.clock().UTC()
	if err := record.VerifyBoarding(cmd.EntitlementID, cmd.Source, cmd.SourceEventID, cmd.OccurredAt.UTC(), receivedAt, nil); err != nil {
		return BoardingResult{}, fmt.Errorf("%w: %v", ErrDomainRuleViolation, err)
	}
	events := record.Events()
	if err := s.repo.Save(ctx, record); err != nil {
		return BoardingResult{}, err
	}
	if err := s.publishEvents(ctx, events, meta); err != nil {
		return BoardingResult{}, err
	}
	return BoardingResult{FulfillmentRecordID: record.FulfillmentRecordID, EntitlementID: record.EntitlementID, Status: record.Status, OccurredAt: cmd.OccurredAt.UTC()}, nil
}

func (s *Service) RecordNoShow(ctx context.Context, cmd RecordNoShowCommand, meta CommandMetadata) (NoShowResult, error) {
	if s == nil || s.repo == nil || s.pub == nil {
		return NoShowResult{}, errors.New("fulfillment service is not configured")
	}
	if err := validateNoShow(cmd); err != nil {
		return NoShowResult{}, err
	}
	record, err := s.repo.FindByEntitlementSegment(ctx, cmd.EntitlementID, cmd.SegmentBookingID, cmd.SegmentRef)
	if err != nil && !errors.Is(err, ErrNotFound) {
		return NoShowResult{}, err
	}
	if errors.Is(err, ErrNotFound) {
		record, err = domain.NewFulfillmentRecord(domain.FulfillmentRecordID(s.idGen("fr")), cmd.EntitlementID, cmd.SegmentBookingID, cmd.JourneyOrderID, cmd.TravelerID, cmd.SegmentRef)
		if err != nil {
			return NoShowResult{}, fmt.Errorf("%w: %v", ErrDomainRuleViolation, err)
		}
	}
	assessedAt := s.clock().UTC()
	if err := record.RecordNoShow(cmd.Reason, assessedAt); err != nil {
		return NoShowResult{}, fmt.Errorf("%w: %v", ErrDomainRuleViolation, err)
	}
	events := record.Events()
	if err := s.repo.Save(ctx, record); err != nil {
		return NoShowResult{}, err
	}
	if err := s.publishEvents(ctx, events, meta); err != nil {
		return NoShowResult{}, err
	}
	return NoShowResult{FulfillmentRecordID: record.FulfillmentRecordID, Status: record.Status, AssessedAt: assessedAt}, nil
}

func (s *Service) GetFulfillmentRecord(ctx context.Context, id domain.FulfillmentRecordID) (*domain.FulfillmentRecord, error) {
	if err := id.Validate(); err != nil {
		return nil, err
	}
	return s.repo.FindByID(ctx, id)
}

func (s *Service) HandleSubscribedEvent(ctx context.Context, envelope EventEnvelope) error {
	if strings.TrimSpace(envelope.EventID) == "" {
		return fmt.Errorf("%w: eventId is required", ErrFatalHandler)
	}
	if envelope.EventType == "" || envelope.Producer == "" {
		return fmt.Errorf("%w: invalid envelope", ErrFatalHandler)
	}
	if s.consumed != nil {
		claimed, err := s.consumed.Claim(ctx, envelope.EventID)
		if err != nil {
			return fmt.Errorf("%w: %v", ErrTransientHandler, err)
		}
		if !claimed {
			return nil
		}
	}
	return nil
}

func (s *Service) publishEvents(ctx context.Context, events []domain.DomainEvent, meta CommandMetadata) error {
	for _, event := range events {
		envelope, err := s.WrapDomainEvent(event, meta)
		if err != nil {
			return err
		}
		if err := s.pub.Publish(ctx, envelope); err != nil {
			return fmt.Errorf("%w: %v", ErrPublishFailed, err)
		}
	}
	return nil
}

func (s *Service) WrapDomainEvent(event domain.DomainEvent, meta CommandMetadata) (EventEnvelope, error) {
	payload, err := MarshalDomainEventPayload(event)
	if err != nil {
		return EventEnvelope{}, err
	}
	correlationID := normalizePrefixed(meta.CorrelationID, "corr", s.idGen)
	causationID := normalizeCausationID(meta.CausationID, s.idGen)
	return EventEnvelope{
		EventID:       s.idGen("evt"),
		EventType:     event.EventType(),
		OccurredAt:    event.OccurredAt().UTC(),
		CorrelationID: correlationID,
		CausationID:   causationID,
		Producer:      ProducerName,
		SchemaVersion: SchemaVersion,
		Payload:       payload,
	}, nil
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
	if !validNoShowReason(cmd.Reason) {
		return fmt.Errorf("invalid reason: %s", cmd.Reason)
	}
	return nil
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

func MarshalDomainEventPayload(event domain.DomainEvent) (json.RawMessage, error) {
	var payload any
	switch e := event.(type) {
	case domain.BoardingVerifiedEvent:
		payload = boardingPayload{e.FulfillmentRecordID, e.EntitlementID, e.SegmentBookingID, e.JourneyOrderID, e.TravelerID, e.SegmentRef, e.Source, e.SourceEventID, e.OccurredAt().UTC(), e.ReceivedAt.UTC(), e.LocationSnapshot}
	case domain.NoShowRecordedEvent:
		payload = noShowPayload{e.FulfillmentRecordID, e.EntitlementID, e.SegmentBookingID, e.JourneyOrderID, e.TravelerID, e.SegmentRef, e.Reason, e.OccurredAt().UTC()}
	default:
		payload = event
	}
	bytes, err := json.Marshal(payload)
	return bytes, err
}

func normalizePrefixed(value, prefix string, idGen IDGenerator) string {
	value = strings.TrimSpace(value)
	value = strings.TrimPrefix(value, prefix+"-")
	if isUUIDLike(value) {
		return prefix + "-" + value
	}
	return idGen(prefix)
}

func normalizeCausationID(value string, idGen IDGenerator) string {
	value = strings.TrimSpace(value)
	for _, prefix := range []string{"cmd", "evt"} {
		unprefixed := strings.TrimPrefix(value, prefix+"-")
		if isUUIDLike(unprefixed) {
			return prefix + "-" + unprefixed
		}
	}
	return idGen("cmd")
}

var uuidPattern = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

func isUUIDLike(value string) bool {
	return uuidPattern.MatchString(value)
}

func NewPrefixedID(prefix string) string {
	return prefix + "-" + newUUIDLike()
}

func newUUIDLike() string {
	var b [16]byte
	if _, err := randRead(b[:]); err != nil {
		return fmt.Sprintf("%08x-%04x-7000-8000-%012x", uint32(time.Now().Unix()), uint16(time.Now().UnixNano()), time.Now().UnixNano())
	}
	millis := uint64(time.Now().UTC().UnixMilli())
	b[0] = byte(millis >> 40)
	b[1] = byte(millis >> 32)
	b[2] = byte(millis >> 24)
	b[3] = byte(millis >> 16)
	b[4] = byte(millis >> 8)
	b[5] = byte(millis)
	b[6] = (b[6] & 0x0f) | 0x70
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

var randRead = rand.Read

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
	copy.Events()
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
