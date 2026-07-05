package application

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"

	"github.com/trainticket/greenfield/services/service-plan/internal/domain"
)

const (
	StatusActive    = "ACTIVE"
	StatusSuspended = "SUSPENDED"
	StatusCancelled = "CANCELLED"
)

var (
	ErrNotFound               = errors.New("not found")
	ErrConflict               = errors.New("conflict")
	ErrValidation             = errors.New("validation failed")
	ErrDomainRule             = errors.New("domain rule violation")
	ErrIdempotencyKeyReused   = errors.New("idempotency key reused")
	ErrIdempotencyKeyRequired = errors.New("idempotency key required")
	ErrPublish                = errors.New("publish failed")
)

type ScheduledService struct {
	ScheduledServiceRef string    `json:"scheduledServiceRef"`
	ServiceNumber       string    `json:"serviceNumber"`
	Status              string    `json:"status"`
	CarrierID           string    `json:"carrierId"`
	DepartureTime       time.Time `json:"departureTime"`
	ArrivalTime         time.Time `json:"arrivalTime"`
	OriginNodeID        string    `json:"originNodeId"`
	DestinationNodeID   string    `json:"destinationNodeId"`
}

type ServiceSegment struct {
	SegmentRef          string    `json:"segmentRef"`
	ScheduledServiceRef string    `json:"scheduledServiceRef"`
	OriginStopRef       string    `json:"originStopRef"`
	DestinationStopRef  string    `json:"destinationStopRef"`
	DepartureTime       time.Time `json:"departureTime"`
	ArrivalTime         time.Time `json:"arrivalTime"`
}

type CreateScheduledServiceCommand struct {
	ServiceRef        string
	CarrierID         string
	ServiceNumber     string
	DepartureTime     time.Time
	ArrivalTime       time.Time
	OriginNodeID      string
	DestinationNodeID string
	Status            string
	IdempotencyKey    string
	CorrelationID     string
	CausationID       string
	RequestHash       string
}

type CreateScheduledServiceResult struct {
	ScheduledServiceRef string `json:"scheduledServiceRef"`
	ServiceNumber       string `json:"serviceNumber"`
	Status              string `json:"status"`
}

type CreateServiceSegmentCommand struct {
	ScheduledServiceRef string
	OriginStopRef       string
	DestinationStopRef  string
	DepartureTime       time.Time
	ArrivalTime         time.Time
	IdempotencyKey      string
	CorrelationID       string
	CausationID         string
	RequestHash         string
}

type CreateServiceSegmentResult struct {
	SegmentRef          string `json:"segmentRef"`
	ScheduledServiceRef string `json:"scheduledServiceRef"`
}

type ListScheduledServicesQuery struct {
	Limit     int
	Offset    int
	CarrierID string
}

type PaginatedScheduledServices struct {
	Items  []ScheduledService `json:"items"`
	Total  int                `json:"total"`
	Limit  int                `json:"limit"`
	Offset int                `json:"offset"`
}

type idempotencyRecord struct {
	requestHash string
	statusCode  int
	body        any
}

type IdempotencyResult struct {
	StatusCode int
	Body       any
}

type scheduledServiceState struct {
	aggregate domain.ScheduledService
	view      ScheduledService
}

type Service struct {
	mu                 sync.Mutex
	publishMu          sync.Mutex
	now                func() time.Time
	idGenerator        func(prefix string) string
	publisher          EventPublisher
	scheduledServices  map[string]scheduledServiceState
	segments           map[string]ServiceSegment
	idempotencyRecords map[string]idempotencyRecord
	pendingEvents      []EventEnvelope
}

func NewService(publisher EventPublisher) *Service {
	if publisher == nil {
		publisher = NoopPublisher{}
	}
	return &Service{
		now:                func() time.Time { return time.Now().UTC() },
		idGenerator:        NewPrefixedID,
		publisher:          publisher,
		scheduledServices:  map[string]scheduledServiceState{},
		segments:           map[string]ServiceSegment{},
		idempotencyRecords: map[string]idempotencyRecord{},
		pendingEvents:      []EventEnvelope{},
	}
}

func (s *Service) CreateScheduledService(ctx context.Context, command CreateScheduledServiceCommand) (CreateScheduledServiceResult, *IdempotencyResult, error) {
	if err := validateCreateScheduledService(command); err != nil {
		return CreateScheduledServiceResult{}, nil, err
	}
	replay, err := s.idempotencyReplay(ctx, command.IdempotencyKey, command.RequestHash)
	if err != nil || replay != nil {
		return CreateScheduledServiceResult{}, replay, err
	}

	state, result, err := s.buildScheduledService(command)
	if err != nil {
		return CreateScheduledServiceResult{}, nil, err
	}
	payload, err := json.Marshal(map[string]any{
		"scheduledServiceRef": state.view.ScheduledServiceRef,
		"serviceNumber":       state.view.ServiceNumber,
		"status":              state.view.Status,
		"carrierId":           state.view.CarrierID,
		"departureTime":       state.view.DepartureTime,
		"arrivalTime":         state.view.ArrivalTime,
		"originNodeId":        state.view.OriginNodeID,
		"destinationNodeId":   state.view.DestinationNodeID,
	})
	if err != nil {
		return CreateScheduledServiceResult{}, nil, err
	}

	envelope := s.newEnvelope("ServicePlanPublished", command.CorrelationID, command.CausationID, payload)

	s.mu.Lock()
	if _, exists := s.scheduledServices[state.view.ScheduledServiceRef]; exists {
		s.mu.Unlock()
		return CreateScheduledServiceResult{}, nil, fmt.Errorf("%w: scheduled service already exists", ErrConflict)
	}
	s.scheduledServices[state.view.ScheduledServiceRef] = state
	s.idempotencyRecords[command.IdempotencyKey] = idempotencyRecord{requestHash: command.RequestHash, statusCode: 201, body: result}
	s.pendingEvents = append(s.pendingEvents, envelope)
	s.mu.Unlock()

	if err := s.flushPendingEvents(ctx); err != nil {
		return CreateScheduledServiceResult{}, nil, fmt.Errorf("%w: %v", ErrPublish, err)
	}
	return result, nil, nil
}

func (s *Service) buildScheduledService(command CreateScheduledServiceCommand) (scheduledServiceState, CreateScheduledServiceResult, error) {
	ref := strings.TrimSpace(command.ServiceRef)
	if ref == "" {
		ref = s.idGenerator("ss")
	}
	departure := command.DepartureTime.UTC()
	arrival := command.ArrivalTime.UTC()
	origin := strings.TrimSpace(command.OriginNodeID)
	destination := strings.TrimSpace(command.DestinationNodeID)
	status := normalizedStatus(command.Status)

	originStop, err := domain.NewServiceStop(1, domain.TransportNodeID(origin), "nodes-snapshot-unknown", true, false)
	if err != nil {
		return scheduledServiceState{}, CreateScheduledServiceResult{}, domainRule(err)
	}
	destinationStop, err := domain.NewServiceStop(2, domain.TransportNodeID(destination), "nodes-snapshot-unknown", false, true)
	if err != nil {
		return scheduledServiceState{}, CreateScheduledServiceResult{}, domainRule(err)
	}
	patternID := domain.ServicePatternID(s.idGenerator("sp"))
	pattern, err := domain.NewServicePattern(patternID, domain.ServiceModeTrain, domain.CarrierID(command.CarrierID), []domain.ServiceStop{originStop, destinationStop})
	if err != nil {
		return scheduledServiceState{}, CreateScheduledServiceResult{}, domainRule(err)
	}
	calendar, err := domain.NewCalendar(domain.CalendarID(s.idGenerator("cal")), serviceDate(departure), serviceDate(departure), []time.Weekday{departure.Weekday()})
	if err != nil {
		return scheduledServiceState{}, CreateScheduledServiceResult{}, domainRule(err)
	}
	departureTime, err := plannedTimeFor(departure, departure)
	if err != nil {
		return scheduledServiceState{}, CreateScheduledServiceResult{}, domainRule(err)
	}
	arrivalTime, err := plannedTimeFor(departure, arrival)
	if err != nil {
		return scheduledServiceState{}, CreateScheduledServiceResult{}, domainRule(err)
	}
	departureStopTime, err := domain.NewStopTime(1, nil, &departureTime)
	if err != nil {
		return scheduledServiceState{}, CreateScheduledServiceResult{}, domainRule(err)
	}
	arrivalStopTime, err := domain.NewStopTime(2, &arrivalTime, nil)
	if err != nil {
		return scheduledServiceState{}, CreateScheduledServiceResult{}, domainRule(err)
	}
	timetable, err := domain.NewTimetable(domain.TimetableID(s.idGenerator("tt")), "UTC", []domain.StopTime{departureStopTime, arrivalStopTime})
	if err != nil {
		return scheduledServiceState{}, CreateScheduledServiceResult{}, domainRule(err)
	}
	draft, err := domain.NewDraftPlanVersion(domain.PlanVersionID(s.idGenerator("pv")), serviceDate(departure), serviceDate(departure), pattern, calendar, timetable)
	if err != nil {
		return scheduledServiceState{}, CreateScheduledServiceResult{}, domainRule(err)
	}
	validated, _, err := draft.MarkValidated()
	if err != nil {
		return scheduledServiceState{}, CreateScheduledServiceResult{}, domainRule(err)
	}
	plan, _, err := domain.NewServicePlan(domain.ServicePlanID(s.idGenerator("plan")), domain.ServicePlanKey(ref), validated)
	if err != nil {
		return scheduledServiceState{}, CreateScheduledServiceResult{}, domainRule(err)
	}
	plan, _, err = plan.PublishVersion(validated.ID(), s.now().UTC())
	if err != nil {
		return scheduledServiceState{}, CreateScheduledServiceResult{}, domainRule(err)
	}
	published := plan.Versions()[0]
	aggregate, _, err := domain.NewScheduledService(domain.ScheduledServiceID(ref), domain.ScheduledServiceKey(command.ServiceNumber), published, serviceDate(departure))
	if err != nil {
		return scheduledServiceState{}, CreateScheduledServiceResult{}, domainRule(err)
	}

	view := ScheduledService{
		ScheduledServiceRef: string(aggregate.ID()),
		ServiceNumber:       strings.TrimSpace(command.ServiceNumber),
		Status:              status,
		CarrierID:           strings.TrimSpace(command.CarrierID),
		DepartureTime:       departure,
		ArrivalTime:         arrival,
		OriginNodeID:        origin,
		DestinationNodeID:   destination,
	}
	result := CreateScheduledServiceResult{ScheduledServiceRef: view.ScheduledServiceRef, ServiceNumber: view.ServiceNumber, Status: view.Status}
	return scheduledServiceState{aggregate: aggregate, view: view}, result, nil
}

func (s *Service) GetScheduledService(serviceRef string) (ScheduledService, error) {
	serviceRef = strings.TrimSpace(serviceRef)
	s.mu.Lock()
	defer s.mu.Unlock()
	state, exists := s.scheduledServices[serviceRef]
	if !exists {
		return ScheduledService{}, fmt.Errorf("%w: scheduled service", ErrNotFound)
	}
	return state.view, nil
}

func (s *Service) ListScheduledServices(query ListScheduledServicesQuery) PaginatedScheduledServices {
	if query.Limit <= 0 {
		query.Limit = 20
	}
	if query.Limit > 100 {
		query.Limit = 100
	}
	if query.Offset < 0 {
		query.Offset = 0
	}
	s.mu.Lock()
	items := make([]ScheduledService, 0, len(s.scheduledServices))
	for _, state := range s.scheduledServices {
		if query.CarrierID == "" || state.view.CarrierID == query.CarrierID {
			items = append(items, state.view)
		}
	}
	s.mu.Unlock()
	sort.Slice(items, func(i, j int) bool { return items[i].ScheduledServiceRef < items[j].ScheduledServiceRef })
	total := len(items)
	if query.Offset >= len(items) {
		items = []ScheduledService{}
	} else {
		end := query.Offset + query.Limit
		if end > len(items) {
			end = len(items)
		}
		items = items[query.Offset:end]
	}
	return PaginatedScheduledServices{Items: items, Total: total, Limit: query.Limit, Offset: query.Offset}
}

func (s *Service) CreateServiceSegment(ctx context.Context, command CreateServiceSegmentCommand) (CreateServiceSegmentResult, *IdempotencyResult, error) {
	if err := validateCreateServiceSegment(command); err != nil {
		return CreateServiceSegmentResult{}, nil, err
	}
	replay, err := s.idempotencyReplay(ctx, command.IdempotencyKey, command.RequestHash)
	if err != nil || replay != nil {
		return CreateServiceSegmentResult{}, replay, err
	}

	s.mu.Lock()
	state, exists := s.scheduledServices[strings.TrimSpace(command.ScheduledServiceRef)]
	if !exists {
		s.mu.Unlock()
		return CreateServiceSegmentResult{}, nil, fmt.Errorf("%w: scheduled service", ErrNotFound)
	}
	if !hasDomainSegment(state.aggregate, command.OriginStopRef, command.DestinationStopRef) {
		s.mu.Unlock()
		return CreateServiceSegmentResult{}, nil, fmt.Errorf("%w: requested segment is not part of scheduled service", ErrDomainRule)
	}
	segment := ServiceSegment{
		SegmentRef:          s.idGenerator("seg"),
		ScheduledServiceRef: strings.TrimSpace(command.ScheduledServiceRef),
		OriginStopRef:       strings.TrimSpace(command.OriginStopRef),
		DestinationStopRef:  strings.TrimSpace(command.DestinationStopRef),
		DepartureTime:       command.DepartureTime.UTC(),
		ArrivalTime:         command.ArrivalTime.UTC(),
	}
	result := CreateServiceSegmentResult{SegmentRef: segment.SegmentRef, ScheduledServiceRef: segment.ScheduledServiceRef}
	payload, err := json.Marshal(map[string]any{
		"segmentRef":          segment.SegmentRef,
		"scheduledServiceRef": segment.ScheduledServiceRef,
		"originStopRef":       segment.OriginStopRef,
		"destinationStopRef":  segment.DestinationStopRef,
		"departureTime":       segment.DepartureTime,
		"arrivalTime":         segment.ArrivalTime,
	})
	if err != nil {
		s.mu.Unlock()
		return CreateServiceSegmentResult{}, nil, err
	}
	envelope := s.newEnvelope("ServicePlanChanged", command.CorrelationID, command.CausationID, payload)
	s.segments[segment.SegmentRef] = segment
	s.idempotencyRecords[command.IdempotencyKey] = idempotencyRecord{requestHash: command.RequestHash, statusCode: 201, body: result}
	s.pendingEvents = append(s.pendingEvents, envelope)
	s.mu.Unlock()

	if err := s.flushPendingEvents(ctx); err != nil {
		return CreateServiceSegmentResult{}, nil, fmt.Errorf("%w: %v", ErrPublish, err)
	}
	return result, nil, nil
}

func (s *Service) idempotencyReplay(ctx context.Context, key, requestHash string) (*IdempotencyResult, error) {
	key = strings.TrimSpace(key)
	if key == "" {
		return nil, ErrIdempotencyKeyRequired
	}
	s.mu.Lock()
	record, exists := s.idempotencyRecords[key]
	if !exists {
		s.mu.Unlock()
		return nil, nil
	}
	if record.requestHash != requestHash {
		s.mu.Unlock()
		return nil, ErrIdempotencyKeyReused
	}
	s.mu.Unlock()
	if err := s.flushPendingEvents(ctx); err != nil {
		return nil, fmt.Errorf("%w: %v", ErrPublish, err)
	}
	return &IdempotencyResult{StatusCode: record.statusCode, Body: record.body}, nil
}

func (s *Service) flushPendingEvents(ctx context.Context) error {
	s.publishMu.Lock()
	defer s.publishMu.Unlock()
	for {
		s.mu.Lock()
		if len(s.pendingEvents) == 0 {
			s.mu.Unlock()
			return nil
		}
		envelope := s.pendingEvents[0]
		s.mu.Unlock()

		if err := s.publisher.Publish(ctx, envelope); err != nil {
			return err
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
		s.mu.Unlock()
	}
}

func (s *Service) newEnvelope(eventType, correlationID, causationID string, payload []byte) EventEnvelope {
	now := s.now().UTC()
	return kitmsg.EventEnvelope{
		EventID:       ids.NewEventID(),
		EventType:     eventType,
		OccurredAt:    now,
		CorrelationID: ids.CanonicalCorrelationID(correlationID),
		CausationID:   ids.CanonicalCausationID(causationID),
		Producer:      ProducerServicePlan,
		SchemaVersion: SchemaVersion,
		Payload:       append(json.RawMessage(nil), payload...),
	}
}

func validateCreateScheduledService(command CreateScheduledServiceCommand) error {
	if strings.TrimSpace(command.IdempotencyKey) == "" {
		return ErrIdempotencyKeyRequired
	}
	if !validUUIDv7(strings.TrimSpace(command.IdempotencyKey)) {
		return fmt.Errorf("%w: Idempotency-Key must be UUID v7", ErrValidation)
	}
	if strings.TrimSpace(command.CarrierID) == "" || strings.TrimSpace(command.ServiceNumber) == "" || strings.TrimSpace(command.OriginNodeID) == "" || strings.TrimSpace(command.DestinationNodeID) == "" {
		return fmt.Errorf("%w: required field missing", ErrValidation)
	}
	if !validPrefixedUUIDv7(strings.TrimSpace(command.CarrierID), "car") {
		return fmt.Errorf("%w: carrierId must be car-prefixed UUID v7", ErrValidation)
	}
	if strings.TrimSpace(command.ServiceRef) != "" && !validPrefixedUUIDv7(strings.TrimSpace(command.ServiceRef), "ss") {
		return fmt.Errorf("%w: serviceRef must be ss-prefixed UUID v7", ErrValidation)
	}
	if command.DepartureTime.IsZero() || command.ArrivalTime.IsZero() {
		return fmt.Errorf("%w: departureTime and arrivalTime are required", ErrValidation)
	}
	if !validStatus(normalizedStatus(command.Status)) {
		return fmt.Errorf("%w: unsupported status", ErrValidation)
	}
	return nil
}

func validateCreateServiceSegment(command CreateServiceSegmentCommand) error {
	if strings.TrimSpace(command.IdempotencyKey) == "" {
		return ErrIdempotencyKeyRequired
	}
	if !validUUIDv7(strings.TrimSpace(command.IdempotencyKey)) {
		return fmt.Errorf("%w: Idempotency-Key must be UUID v7", ErrValidation)
	}
	if strings.TrimSpace(command.ScheduledServiceRef) == "" || strings.TrimSpace(command.OriginStopRef) == "" || strings.TrimSpace(command.DestinationStopRef) == "" {
		return fmt.Errorf("%w: required field missing", ErrValidation)
	}
	if !validPrefixedUUIDv7(strings.TrimSpace(command.ScheduledServiceRef), "ss") {
		return fmt.Errorf("%w: scheduledServiceRef must be ss-prefixed UUID v7", ErrValidation)
	}
	if command.DepartureTime.IsZero() || command.ArrivalTime.IsZero() {
		return fmt.Errorf("%w: departureTime and arrivalTime are required", ErrValidation)
	}
	if !command.ArrivalTime.After(command.DepartureTime) {
		return fmt.Errorf("%w: arrivalTime must be after departureTime", ErrValidation)
	}
	return nil
}

func normalizedStatus(status string) string {
	status = strings.TrimSpace(status)
	if status == "" {
		return StatusActive
	}
	return strings.ToUpper(status)
}

func validStatus(status string) bool {
	switch status {
	case StatusActive, StatusSuspended, StatusCancelled:
		return true
	default:
		return false
	}
}

type NoopPublisher struct{}

func (NoopPublisher) Publish(context.Context, EventEnvelope) error { return nil }

func NewPrefixedID(prefix string) string {
	return ids.NewPrefixed(prefix)
}

func newCanonicalID(prefix string) string        { return ids.NewPrefixed(prefix) }
func canonicalCorrelationID(value string) string { return ids.CanonicalCorrelationID(value) }
func canonicalCausationID(value string) string   { return ids.CanonicalCausationID(value) }

func validPrefixedUUID(value, prefix string) bool {
	return strings.HasPrefix(value, prefix+"-") && validUUID(strings.TrimPrefix(value, prefix+"-"))
}

func validPrefixedUUIDv7(value, prefix string) bool {
	return ids.ValidPrefixedUUIDv7(value, prefix)
}

func validUUIDv7(value string) bool {
	return ids.ValidUUIDv7(value)
}

func validUUID(value string) bool {
	if len(value) != 36 {
		return false
	}
	for i, r := range value {
		switch i {
		case 8, 13, 18, 23:
			if r != '-' {
				return false
			}
		default:
			if !((r >= '0' && r <= '9') || (r >= 'a' && r <= 'f') || (r >= 'A' && r <= 'F')) {
				return false
			}
		}
	}
	return true
}

func plannedTimeFor(serviceStart, value time.Time) (domain.PlannedTime, error) {
	serviceStart = serviceDate(serviceStart)
	value = value.UTC()
	dayOffset := int(serviceDate(value).Sub(serviceStart).Hours() / 24)
	minuteOfDay := time.Duration(value.Hour()*60+value.Minute()) * time.Minute
	return domain.NewPlannedTime(dayOffset, minuteOfDay)
}

func serviceDate(value time.Time) time.Time {
	value = value.UTC()
	return time.Date(value.Year(), value.Month(), value.Day(), 0, 0, 0, 0, time.UTC)
}

func domainRule(err error) error {
	return fmt.Errorf("%w: %v", ErrDomainRule, err)
}

func hasDomainSegment(service domain.ScheduledService, originStopRef, destinationStopRef string) bool {
	originStopRef = strings.TrimSpace(originStopRef)
	destinationStopRef = strings.TrimSpace(destinationStopRef)
	for _, segment := range service.Segments() {
		if string(segment.FromNodeID) == originStopRef && string(segment.ToNodeID) == destinationStopRef {
			return true
		}
	}
	return false
}
