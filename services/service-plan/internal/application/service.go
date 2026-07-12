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
	ErrNotFound   = errors.New("not found")
	ErrConflict   = errors.New("conflict")
	ErrValidation = errors.New("validation failed")
	ErrDomainRule = errors.New("domain rule violation")
	ErrPublish    = errors.New("publish failed")
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
	SchedulePeriod      string    `json:"schedulePeriod,omitempty"`
	CapacityMultiplier  float64   `json:"capacityMultiplier,omitempty"`
	Bookable            bool      `json:"bookable"`
}

type ServiceSegment struct {
	SegmentRef          string     `json:"segmentRef"`
	ScheduledServiceRef string     `json:"scheduledServiceRef"`
	OriginStopRef       string     `json:"originStopRef"`
	DestinationStopRef  string     `json:"destinationStopRef"`
	DepartureTime       time.Time  `json:"departureTime"`
	ArrivalTime         time.Time  `json:"arrivalTime"`
	ActualDepartureTime *time.Time `json:"actualDepartureTime,omitempty"`
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
	CorrelationID     string
	CausationID       string
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
	CorrelationID       string
	CausationID         string
}

type CreateServiceSegmentResult struct {
	SegmentRef          string `json:"segmentRef"`
	ScheduledServiceRef string `json:"scheduledServiceRef"`
}

type AddTemporaryServiceCommand struct {
	BaseServiceRef   string
	TempServiceRef   string
	TempTrainNumber  string
	Period           string
	StopsSubset      []string
	AvailableClasses []string
	CorrelationID    string
	CausationID      string
}

type AddTemporaryServiceResult struct {
	TempServiceRef  string `json:"tempServiceRef"`
	BaseServiceRef  string `json:"baseServiceRef"`
	TempTrainNumber string `json:"tempTrainNumber"`
	Period          string `json:"period"`
}

type RecordDelayCommand struct {
	ScheduledServiceRef string
	SegmentRef          string
	DelayMinutes        int
	CorrelationID       string
	CausationID         string
}

type TrainDelayed struct {
	ServiceRef            string    `json:"serviceRef"`
	SegmentRef            string    `json:"segmentRef"`
	DelayMinutes          int       `json:"delayMinutes"`
	EstimatedNewDeparture time.Time `json:"estimatedNewDeparture"`
}

type RecordDelayResult struct {
	Events []TrainDelayed `json:"events"`
}

type CancelServiceCommand struct {
	ScheduledServiceRef string
	Date                time.Time
	Reason              string
	CorrelationID       string
	CausationID         string
}

type RestoreServiceCommand struct {
	ScheduledServiceRef string
	Date                time.Time
	CorrelationID       string
	CausationID         string
}

type ServiceDateStatusResult struct {
	ScheduledServiceRef string `json:"scheduledServiceRef"`
	Date                string `json:"date"`
	Bookable            bool   `json:"bookable"`
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

type scheduledServiceState struct {
	plan      domain.ServicePlan
	aggregate domain.ScheduledService
	view      ScheduledService
}

type Repository interface {
	SaveScheduledService(context.Context, ScheduledService) error
	FindScheduledService(context.Context, string) (ScheduledService, error)
	ListScheduledServices(context.Context, ListScheduledServicesQuery) (PaginatedScheduledServices, error)
	SaveServiceSegment(context.Context, ServiceSegment) error
	FindServiceSegment(context.Context, string) (ServiceSegment, error)
}

type UnitOfWork func(context.Context, func(context.Context) error) error

type Service struct {
	mu                sync.Mutex
	publishMu         sync.Mutex
	now               func() time.Time
	idGenerator       func(prefix string) string
	publisher         EventPublisher
	repository        Repository
	uow               UnitOfWork
	scheduledServices map[string]scheduledServiceState
	segments          map[string]ServiceSegment
	pendingEvents     []EventEnvelope
}

func NewService(publisher EventPublisher) *Service {
	if publisher == nil {
		publisher = NoopPublisher{}
	}
	return &Service{
		now:               func() time.Time { return time.Now().UTC() },
		idGenerator:       NewPrefixedID,
		publisher:         publisher,
		scheduledServices: map[string]scheduledServiceState{},
		segments:          map[string]ServiceSegment{},
		pendingEvents:     []EventEnvelope{},
	}
}

func (s *Service) WithRepository(repository Repository) *Service {
	if s != nil {
		s.repository = repository
	}
	return s
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

func (s *Service) CreateScheduledService(ctx context.Context, command CreateScheduledServiceCommand) (CreateScheduledServiceResult, error) {
	if err := validateCreateScheduledService(command); err != nil {
		return CreateScheduledServiceResult{}, err
	}

	state, result, err := s.buildScheduledService(command)
	if err != nil {
		return CreateScheduledServiceResult{}, err
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
		return CreateScheduledServiceResult{}, err
	}

	envelope := s.newEnvelope(ctx, "ServicePlanPublished", command.CorrelationID, command.CausationID, payload)

	if err := s.within(ctx, func(txCtx context.Context) error {
		if s.repository != nil {
			if err := s.repository.SaveScheduledService(txCtx, state.view); err != nil {
				return err
			}
		} else {
			s.mu.Lock()
			if _, exists := s.scheduledServices[state.view.ScheduledServiceRef]; exists {
				s.mu.Unlock()
				return fmt.Errorf("%w: scheduled service already exists", ErrConflict)
			}
			s.mu.Unlock()
		}
		if err := s.publisher.Publish(txCtx, envelope); err != nil {
			return fmt.Errorf("%w: %v", ErrPublish, err)
		}
		return nil
	}); err != nil {
		return CreateScheduledServiceResult{}, err
	}
	s.mu.Lock()
	s.scheduledServices[state.view.ScheduledServiceRef] = state
	s.mu.Unlock()
	return result, nil
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
		SchedulePeriod:      string(plan.ActiveVariant(departure).PeriodType()),
		CapacityMultiplier:  plan.ActiveVariant(departure).CapacityMultiplier(),
		Bookable:            status != StatusCancelled,
	}
	result := CreateScheduledServiceResult{ScheduledServiceRef: view.ScheduledServiceRef, ServiceNumber: view.ServiceNumber, Status: view.Status}
	return scheduledServiceState{plan: plan, aggregate: aggregate, view: view}, result, nil
}

func (s *Service) GetScheduledService(ctx context.Context, serviceRef string) (ScheduledService, error) {
	serviceRef = strings.TrimSpace(serviceRef)
	if s.repository != nil {
		service, err := s.repository.FindScheduledService(ctx, serviceRef)
		if err != nil {
			return ScheduledService{}, err
		}
		return normalizeScheduledServiceView(service), nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	state, exists := s.scheduledServices[serviceRef]
	if !exists {
		return ScheduledService{}, fmt.Errorf("%w: scheduled service", ErrNotFound)
	}
	return state.view, nil
}

func (s *Service) ListScheduledServices(ctx context.Context, query ListScheduledServicesQuery) (PaginatedScheduledServices, error) {
	if s.repository != nil {
		page, err := s.repository.ListScheduledServices(ctx, query)
		if err != nil {
			return PaginatedScheduledServices{}, err
		}
		for i := range page.Items {
			page.Items[i] = normalizeScheduledServiceView(page.Items[i])
		}
		return page, nil
	}
	return s.listScheduledServicesFromMemory(query), nil
}

func (s *Service) listScheduledServicesFromMemory(query ListScheduledServicesQuery) PaginatedScheduledServices {
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

func (s *Service) CreateServiceSegment(ctx context.Context, command CreateServiceSegmentCommand) (CreateServiceSegmentResult, error) {
	if err := validateCreateServiceSegment(command); err != nil {
		return CreateServiceSegmentResult{}, err
	}

	state, err := s.loadScheduledServiceState(ctx, strings.TrimSpace(command.ScheduledServiceRef))
	if err != nil {
		return CreateServiceSegmentResult{}, err
	}
	if !serviceViewHasSegment(state.view, command.OriginStopRef, command.DestinationStopRef) {
		return CreateServiceSegmentResult{}, fmt.Errorf("%w: requested segment is not part of scheduled service", ErrDomainRule)
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
		return CreateServiceSegmentResult{}, err
	}
	envelope := s.newEnvelope(ctx, "ServicePlanChanged", command.CorrelationID, command.CausationID, payload)

	if err := s.within(ctx, func(txCtx context.Context) error {
		if s.repository != nil {
			if err := s.repository.SaveServiceSegment(txCtx, segment); err != nil {
				return err
			}
		}
		if err := s.publisher.Publish(txCtx, envelope); err != nil {
			return fmt.Errorf("%w: %v", ErrPublish, err)
		}
		return nil
	}); err != nil {
		return CreateServiceSegmentResult{}, err
	}
	s.mu.Lock()
	s.segments[segment.SegmentRef] = segment
	s.mu.Unlock()
	return result, nil
}

func (s *Service) AddTemporaryService(ctx context.Context, command AddTemporaryServiceCommand) (AddTemporaryServiceResult, error) {
	if err := validateAddTemporaryService(command); err != nil {
		return AddTemporaryServiceResult{}, err
	}
	state, err := s.loadScheduledServiceState(ctx, strings.TrimSpace(command.BaseServiceRef))
	if err != nil {
		return AddTemporaryServiceResult{}, err
	}
	period := domain.SchedulePeriodType(strings.ToUpper(strings.TrimSpace(command.Period)))
	tempRef := strings.TrimSpace(command.TempServiceRef)
	if tempRef == "" {
		tempRef = s.idGenerator("ss")
	}
	tempService, err := domain.NewTemporaryService(domain.TemporaryServiceID(tempRef), domain.ScheduledServiceID(command.BaseServiceRef), command.TempTrainNumber, period, toNodeIDs(command.StopsSubset), command.AvailableClasses)
	if err != nil {
		return AddTemporaryServiceResult{}, domainRule(err)
	}
	plan, event, err := state.plan.AddTemporaryService(tempService)
	if err != nil {
		return AddTemporaryServiceResult{}, domainRule(err)
	}
	payload, err := json.Marshal(map[string]any{"tempServiceRef": event.TempServiceRef, "baseServiceRef": event.BaseServiceRef, "period": event.Period})
	if err != nil {
		return AddTemporaryServiceResult{}, err
	}
	envelope := s.newEnvelope(ctx, "TemporaryServiceAdded", command.CorrelationID, command.CausationID, payload)
	if err := s.within(ctx, func(txCtx context.Context) error {
		if err := s.publisher.Publish(txCtx, envelope); err != nil {
			return fmt.Errorf("%w: %v", ErrPublish, err)
		}
		return nil
	}); err != nil {
		return AddTemporaryServiceResult{}, err
	}
	state.plan = plan
	s.mu.Lock()
	s.scheduledServices[state.view.ScheduledServiceRef] = state
	s.mu.Unlock()
	return AddTemporaryServiceResult{TempServiceRef: string(tempService.TempServiceRef), BaseServiceRef: string(tempService.BaseServiceRef), TempTrainNumber: tempService.TempTrainNumber, Period: string(tempService.PeriodRef)}, nil
}

func (s *Service) RecordDelay(ctx context.Context, command RecordDelayCommand) (RecordDelayResult, error) {
	if err := validateRecordDelay(command); err != nil {
		return RecordDelayResult{}, err
	}
	state, err := s.loadScheduledServiceState(ctx, strings.TrimSpace(command.ScheduledServiceRef))
	if err != nil {
		return RecordDelayResult{}, err
	}
	if s.repository != nil {
		if segment, err := s.repository.FindServiceSegment(ctx, strings.TrimSpace(command.SegmentRef)); err == nil {
			s.mu.Lock()
			s.segments[segment.SegmentRef] = segment
			s.mu.Unlock()
		}
	}
	scheduledDepartures := make(map[domain.ServiceSegmentID]time.Time, len(state.aggregate.Segments()))
	for _, segment := range state.aggregate.Segments() {
		scheduledDepartures[segment.ID] = departureForSequence(state.view.DepartureTime, state.view.ArrivalTime, segment.FromSequence, len(state.aggregate.Segments()))
	}
	domainSegmentRef := s.resolveDomainSegmentRef(command.SegmentRef, state)
	aggregate, domainEvents, err := state.aggregate.RecordDelay(domainSegmentRef, command.DelayMinutes, scheduledDepartures)
	if err != nil {
		return RecordDelayResult{}, domainRule(err)
	}
	envelopes := make([]EventEnvelope, 0, len(domainEvents))
	result := RecordDelayResult{Events: make([]TrainDelayed, 0, len(domainEvents))}
	for _, event := range domainEvents {
		viewEvent := TrainDelayed{ServiceRef: string(event.ServiceRef), SegmentRef: string(event.SegmentRef), DelayMinutes: event.DelayMinutes, EstimatedNewDeparture: event.EstimatedNewDeparture}
		result.Events = append(result.Events, viewEvent)
		payload, err := json.Marshal(map[string]any{"serviceRef": viewEvent.ServiceRef, "segmentRef": viewEvent.SegmentRef, "delayMinutes": viewEvent.DelayMinutes, "estimatedNewDeparture": viewEvent.EstimatedNewDeparture})
		if err != nil {
			return RecordDelayResult{}, err
		}
		envelopes = append(envelopes, s.newEnvelope(ctx, "TrainDelayed", command.CorrelationID, command.CausationID, payload))
	}
	if err := s.within(ctx, func(txCtx context.Context) error {
		for _, envelope := range envelopes {
			if err := s.publisher.Publish(txCtx, envelope); err != nil {
				return fmt.Errorf("%w: %v", ErrPublish, err)
			}
		}
		return nil
	}); err != nil {
		return RecordDelayResult{}, err
	}
	state.aggregate = aggregate
	s.mu.Lock()
	s.scheduledServices[state.view.ScheduledServiceRef] = state
	s.mu.Unlock()
	return result, nil
}

func (s *Service) CancelForDate(ctx context.Context, command CancelServiceCommand) (ServiceDateStatusResult, error) {
	if err := validateCancelService(command); err != nil {
		return ServiceDateStatusResult{}, err
	}
	state, err := s.loadScheduledServiceState(ctx, strings.TrimSpace(command.ScheduledServiceRef))
	if err != nil {
		return ServiceDateStatusResult{}, err
	}
	plan, event, err := state.plan.CancelForDate(domain.ScheduledServiceID(command.ScheduledServiceRef), command.Date, command.Reason, s.now().UTC())
	if err != nil {
		return ServiceDateStatusResult{}, domainRule(err)
	}
	payload, err := json.Marshal(map[string]any{"serviceRef": event.ServiceRef, "date": event.Date, "reason": event.Reason})
	if err != nil {
		return ServiceDateStatusResult{}, err
	}
	envelope := s.newEnvelope(ctx, "TrainCancelled", command.CorrelationID, command.CausationID, payload)
	state.plan = plan
	if serviceDate(command.Date).Equal(serviceDate(state.view.DepartureTime)) {
		state.view.Status = StatusCancelled
		state.view.Bookable = false
	}
	if err := s.persistStateAndPublish(ctx, state, envelope); err != nil {
		return ServiceDateStatusResult{}, err
	}
	return ServiceDateStatusResult{ScheduledServiceRef: state.view.ScheduledServiceRef, Date: dateString(command.Date), Bookable: false}, nil
}

func (s *Service) RestoreForDate(ctx context.Context, command RestoreServiceCommand) (ServiceDateStatusResult, error) {
	if err := validateRestoreService(command); err != nil {
		return ServiceDateStatusResult{}, err
	}
	state, err := s.loadScheduledServiceState(ctx, strings.TrimSpace(command.ScheduledServiceRef))
	if err != nil {
		return ServiceDateStatusResult{}, err
	}
	plan, event, err := state.plan.RestoreForDate(domain.ScheduledServiceID(command.ScheduledServiceRef), command.Date, s.now().UTC())
	if err != nil {
		return ServiceDateStatusResult{}, domainRule(err)
	}
	payload, err := json.Marshal(map[string]any{"serviceRef": event.ServiceRef, "date": event.Date})
	if err != nil {
		return ServiceDateStatusResult{}, err
	}
	envelope := s.newEnvelope(ctx, "TrainRestored", command.CorrelationID, command.CausationID, payload)
	state.plan = plan
	if serviceDate(command.Date).Equal(serviceDate(state.view.DepartureTime)) {
		state.view.Status = StatusActive
		state.view.Bookable = true
	}
	if err := s.persistStateAndPublish(ctx, state, envelope); err != nil {
		return ServiceDateStatusResult{}, err
	}
	return ServiceDateStatusResult{ScheduledServiceRef: state.view.ScheduledServiceRef, Date: dateString(command.Date), Bookable: true}, nil
}

func (s *Service) ExpireTemporaryServices(ctx context.Context, serviceRef string, asOf time.Time, correlationID, causationID string) (int, error) {
	state, err := s.loadScheduledServiceState(ctx, strings.TrimSpace(serviceRef))
	if err != nil {
		return 0, err
	}
	plan, events, err := state.plan.ExpireTemporaryServices(asOf)
	if err != nil {
		return 0, domainRule(err)
	}
	envelopes := make([]EventEnvelope, 0, len(events))
	for _, event := range events {
		payload, err := json.Marshal(map[string]any{"serviceRef": event.ServiceRef, "periodEndDate": event.PeriodEndDate})
		if err != nil {
			return 0, err
		}
		envelopes = append(envelopes, s.newEnvelope(ctx, "TemporaryServiceExpired", correlationID, causationID, payload))
	}
	state.plan = plan
	if err := s.within(ctx, func(txCtx context.Context) error {
		for _, envelope := range envelopes {
			if err := s.publisher.Publish(txCtx, envelope); err != nil {
				return fmt.Errorf("%w: %v", ErrPublish, err)
			}
		}
		return nil
	}); err != nil {
		return 0, err
	}
	s.mu.Lock()
	s.scheduledServices[state.view.ScheduledServiceRef] = state
	s.mu.Unlock()
	return len(events), nil
}

func (s *Service) persistStateAndPublish(ctx context.Context, state scheduledServiceState, envelope EventEnvelope) error {
	if err := s.within(ctx, func(txCtx context.Context) error {
		if s.repository != nil {
			if err := s.repository.SaveScheduledService(txCtx, state.view); err != nil {
				return err
			}
		}
		if err := s.publisher.Publish(txCtx, envelope); err != nil {
			return fmt.Errorf("%w: %v", ErrPublish, err)
		}
		return nil
	}); err != nil {
		return err
	}
	s.mu.Lock()
	s.scheduledServices[state.view.ScheduledServiceRef] = state
	s.mu.Unlock()
	return nil
}

func (s *Service) loadScheduledServiceState(ctx context.Context, serviceRef string) (scheduledServiceState, error) {
	if s.repository != nil {
		service, err := s.repository.FindScheduledService(ctx, serviceRef)
		if err != nil {
			return scheduledServiceState{}, err
		}
		state, err := s.stateFromScheduledService(service)
		if err != nil {
			return scheduledServiceState{}, err
		}
		s.mu.Lock()
		s.scheduledServices[service.ScheduledServiceRef] = state
		s.mu.Unlock()
		return state, nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	state, exists := s.scheduledServices[serviceRef]
	if !exists {
		return scheduledServiceState{}, fmt.Errorf("%w: scheduled service", ErrNotFound)
	}
	return state, nil
}

func (s *Service) stateFromScheduledService(service ScheduledService) (scheduledServiceState, error) {
	command := CreateScheduledServiceCommand{
		ServiceRef:        service.ScheduledServiceRef,
		CarrierID:         service.CarrierID,
		ServiceNumber:     service.ServiceNumber,
		DepartureTime:     service.DepartureTime,
		ArrivalTime:       service.ArrivalTime,
		OriginNodeID:      service.OriginNodeID,
		DestinationNodeID: service.DestinationNodeID,
		Status:            service.Status,
	}
	state, _, err := s.buildScheduledService(command)
	if err != nil {
		return scheduledServiceState{}, err
	}
	state.view = normalizeScheduledServiceView(service)
	return state, nil
}

func (s *Service) resolveDomainSegmentRef(segmentRef string, state scheduledServiceState) domain.ServiceSegmentID {
	segmentRef = strings.TrimSpace(segmentRef)
	for _, segment := range state.aggregate.Segments() {
		if string(segment.ID) == segmentRef {
			return segment.ID
		}
	}
	s.mu.Lock()
	apiSegment, exists := s.segments[segmentRef]
	s.mu.Unlock()
	if exists && apiSegment.ScheduledServiceRef == state.view.ScheduledServiceRef {
		for _, segment := range state.aggregate.Segments() {
			if string(segment.FromNodeID) == apiSegment.OriginStopRef && string(segment.ToNodeID) == apiSegment.DestinationStopRef {
				return segment.ID
			}
		}
	}
	if parts := strings.Split(segmentRef, ":"); len(parts) == 2 {
		for _, segment := range state.aggregate.Segments() {
			if string(segment.FromNodeID) == strings.TrimSpace(parts[0]) && string(segment.ToNodeID) == strings.TrimSpace(parts[1]) {
				return segment.ID
			}
		}
	}
	return domain.ServiceSegmentID(segmentRef)
}

func serviceViewHasSegment(service ScheduledService, originStopRef, destinationStopRef string) bool {
	return strings.TrimSpace(service.OriginNodeID) == strings.TrimSpace(originStopRef) && strings.TrimSpace(service.DestinationNodeID) == strings.TrimSpace(destinationStopRef)
}

func normalizeScheduledServiceView(service ScheduledService) ScheduledService {
	if service.Status == "" {
		service.Status = StatusActive
	}
	if service.SchedulePeriod == "" {
		service.SchedulePeriod = string(domain.SchedulePeriodRegular)
	}
	if service.CapacityMultiplier == 0 {
		service.CapacityMultiplier = 1
	}
	if service.Status != StatusCancelled {
		service.Bookable = true
	}
	return service
}

func (s *Service) removePendingEvent(eventID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.removePendingEventLocked(eventID)
}

func (s *Service) removePendingEventLocked(eventID string) {
	for i, pending := range s.pendingEvents {
		if pending.EventID == eventID {
			s.pendingEvents = append(s.pendingEvents[:i], s.pendingEvents[i+1:]...)
			return
		}
	}
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

func (s *Service) newEnvelope(ctx context.Context, eventType, correlationID, causationID string, payload []byte) EventEnvelope {
	options := []kitmsg.EnvelopeOptions{{Now: s.now().UTC(), Context: ctx}}
	if strings.TrimSpace(causationID) != "" {
		options[0].CausationID = causationID
	}
	envelope, err := kitmsg.NewEventEnvelope(eventType, ProducerServicePlan, correlationID, json.RawMessage(payload), options...)
	if err != nil {
		return kitmsg.EventEnvelope{}
	}
	return envelope
}

func validateCreateScheduledService(command CreateScheduledServiceCommand) error {
	if strings.TrimSpace(command.CarrierID) == "" || strings.TrimSpace(command.ServiceNumber) == "" || strings.TrimSpace(command.OriginNodeID) == "" || strings.TrimSpace(command.DestinationNodeID) == "" {
		return fmt.Errorf("%w: required field missing", ErrValidation)
	}
	if !ids.ValidPrefixedUUIDv7(strings.TrimSpace(command.CarrierID), "car") {
		return fmt.Errorf("%w: carrierId must be car-prefixed UUID v7", ErrValidation)
	}
	if strings.TrimSpace(command.ServiceRef) != "" && !ids.ValidPrefixedUUIDv7(strings.TrimSpace(command.ServiceRef), "ss") {
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
	if strings.TrimSpace(command.ScheduledServiceRef) == "" || strings.TrimSpace(command.OriginStopRef) == "" || strings.TrimSpace(command.DestinationStopRef) == "" {
		return fmt.Errorf("%w: required field missing", ErrValidation)
	}
	if !ids.ValidPrefixedUUIDv7(strings.TrimSpace(command.ScheduledServiceRef), "ss") {
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

func validateAddTemporaryService(command AddTemporaryServiceCommand) error {
	if strings.TrimSpace(command.BaseServiceRef) == "" || strings.TrimSpace(command.TempTrainNumber) == "" || strings.TrimSpace(command.Period) == "" {
		return fmt.Errorf("%w: required field missing", ErrValidation)
	}
	if !ids.ValidPrefixedUUIDv7(strings.TrimSpace(command.BaseServiceRef), "ss") {
		return fmt.Errorf("%w: baseServiceRef must be ss-prefixed UUID v7", ErrValidation)
	}
	if strings.TrimSpace(command.TempServiceRef) != "" && !ids.ValidPrefixedUUIDv7(strings.TrimSpace(command.TempServiceRef), "ss") {
		return fmt.Errorf("%w: tempServiceRef must be ss-prefixed UUID v7", ErrValidation)
	}
	if len(command.StopsSubset) < 2 || len(command.AvailableClasses) == 0 {
		return fmt.Errorf("%w: stopsSubset and availableClasses are required", ErrValidation)
	}
	return nil
}

func validateRecordDelay(command RecordDelayCommand) error {
	if strings.TrimSpace(command.ScheduledServiceRef) == "" || strings.TrimSpace(command.SegmentRef) == "" {
		return fmt.Errorf("%w: required field missing", ErrValidation)
	}
	if !ids.ValidPrefixedUUIDv7(strings.TrimSpace(command.ScheduledServiceRef), "ss") {
		return fmt.Errorf("%w: scheduledServiceRef must be ss-prefixed UUID v7", ErrValidation)
	}
	if command.DelayMinutes < 0 {
		return fmt.Errorf("%w: delayMinutes cannot be negative", ErrValidation)
	}
	return nil
}

func validateCancelService(command CancelServiceCommand) error {
	if strings.TrimSpace(command.ScheduledServiceRef) == "" || strings.TrimSpace(command.Reason) == "" || command.Date.IsZero() {
		return fmt.Errorf("%w: required field missing", ErrValidation)
	}
	if !ids.ValidPrefixedUUIDv7(strings.TrimSpace(command.ScheduledServiceRef), "ss") {
		return fmt.Errorf("%w: scheduledServiceRef must be ss-prefixed UUID v7", ErrValidation)
	}
	return nil
}

func validateRestoreService(command RestoreServiceCommand) error {
	if strings.TrimSpace(command.ScheduledServiceRef) == "" || command.Date.IsZero() {
		return fmt.Errorf("%w: required field missing", ErrValidation)
	}
	if !ids.ValidPrefixedUUIDv7(strings.TrimSpace(command.ScheduledServiceRef), "ss") {
		return fmt.Errorf("%w: scheduledServiceRef must be ss-prefixed UUID v7", ErrValidation)
	}
	return nil
}

func toNodeIDs(values []string) []domain.TransportNodeID {
	nodes := make([]domain.TransportNodeID, len(values))
	for i, value := range values {
		nodes[i] = domain.TransportNodeID(strings.TrimSpace(value))
	}
	return nodes
}

func departureForSequence(start, end time.Time, sequence, segmentCount int) time.Time {
	if sequence <= 1 || segmentCount <= 1 {
		return start.UTC()
	}
	// Derived domain segments include every origin-destination pair; use a stable
	// interpolation only as an API estimate when no detailed stop times are stored.
	span := end.Sub(start)
	if span <= 0 {
		return start.UTC()
	}
	step := span / time.Duration(segmentCount)
	return start.Add(time.Duration(sequence-1) * step).UTC()
}

func dateString(value time.Time) string { return serviceDate(value).Format("2006-01-02") }

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
