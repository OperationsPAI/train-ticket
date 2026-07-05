package application

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"
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

type Service struct {
	mu                 sync.Mutex
	now                func() time.Time
	idGenerator        func(prefix string) string
	publisher          EventPublisher
	scheduledServices  map[string]ScheduledService
	segments           map[string]ServiceSegment
	idempotencyRecords map[string]idempotencyRecord
}

func NewService(publisher EventPublisher) *Service {
	if publisher == nil {
		publisher = NoopPublisher{}
	}
	return &Service{
		now:                func() time.Time { return time.Now().UTC() },
		idGenerator:        NewPrefixedID,
		publisher:          publisher,
		scheduledServices:  map[string]ScheduledService{},
		segments:           map[string]ServiceSegment{},
		idempotencyRecords: map[string]idempotencyRecord{},
	}
}

func (s *Service) CreateScheduledService(ctx context.Context, command CreateScheduledServiceCommand) (CreateScheduledServiceResult, *IdempotencyResult, error) {
	if err := validateCreateScheduledService(command); err != nil {
		return CreateScheduledServiceResult{}, nil, err
	}
	replay, err := s.idempotencyReplay(command.IdempotencyKey, command.RequestHash)
	if err != nil || replay != nil {
		return CreateScheduledServiceResult{}, replay, err
	}
	ref := strings.TrimSpace(command.ServiceRef)
	if ref == "" {
		ref = s.idGenerator("ss")
	}
	service := ScheduledService{
		ScheduledServiceRef: ref,
		ServiceNumber:       strings.TrimSpace(command.ServiceNumber),
		Status:              normalizedStatus(command.Status),
		CarrierID:           strings.TrimSpace(command.CarrierID),
		DepartureTime:       command.DepartureTime.UTC(),
		ArrivalTime:         command.ArrivalTime.UTC(),
		OriginNodeID:        strings.TrimSpace(command.OriginNodeID),
		DestinationNodeID:   strings.TrimSpace(command.DestinationNodeID),
	}
	result := CreateScheduledServiceResult{ScheduledServiceRef: service.ScheduledServiceRef, ServiceNumber: service.ServiceNumber, Status: service.Status}
	payload, err := json.Marshal(map[string]any{
		"scheduledServiceRef": service.ScheduledServiceRef,
		"serviceNumber":       service.ServiceNumber,
		"status":              service.Status,
		"carrierId":           service.CarrierID,
		"departureTime":       service.DepartureTime,
		"arrivalTime":         service.ArrivalTime,
		"originNodeId":        service.OriginNodeID,
		"destinationNodeId":   service.DestinationNodeID,
	})
	if err != nil {
		return CreateScheduledServiceResult{}, nil, err
	}
	envelope := s.newEnvelope("ServicePlanPublished", command.CorrelationID, command.CausationID, payload)

	s.mu.Lock()
	if _, exists := s.scheduledServices[service.ScheduledServiceRef]; exists {
		s.mu.Unlock()
		return CreateScheduledServiceResult{}, nil, fmt.Errorf("%w: scheduled service already exists", ErrConflict)
	}
	s.mu.Unlock()

	if err := s.publisher.Publish(ctx, envelope); err != nil {
		return CreateScheduledServiceResult{}, nil, fmt.Errorf("%w: %v", ErrPublish, err)
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	if _, exists := s.scheduledServices[service.ScheduledServiceRef]; exists {
		return CreateScheduledServiceResult{}, nil, fmt.Errorf("%w: scheduled service already exists", ErrConflict)
	}
	s.scheduledServices[service.ScheduledServiceRef] = service
	s.idempotencyRecords[command.IdempotencyKey] = idempotencyRecord{requestHash: command.RequestHash, statusCode: 201, body: result}
	return result, nil, nil
}

func (s *Service) GetScheduledService(serviceRef string) (ScheduledService, error) {
	serviceRef = strings.TrimSpace(serviceRef)
	s.mu.Lock()
	defer s.mu.Unlock()
	service, exists := s.scheduledServices[serviceRef]
	if !exists {
		return ScheduledService{}, fmt.Errorf("%w: scheduled service", ErrNotFound)
	}
	return service, nil
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
	for _, service := range s.scheduledServices {
		if query.CarrierID == "" || service.CarrierID == query.CarrierID {
			items = append(items, service)
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
	replay, err := s.idempotencyReplay(command.IdempotencyKey, command.RequestHash)
	if err != nil || replay != nil {
		return CreateServiceSegmentResult{}, replay, err
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
		return CreateServiceSegmentResult{}, nil, err
	}
	envelope := s.newEnvelope("ServicePlanChanged", command.CorrelationID, command.CausationID, payload)

	s.mu.Lock()
	if _, exists := s.scheduledServices[segment.ScheduledServiceRef]; !exists {
		s.mu.Unlock()
		return CreateServiceSegmentResult{}, nil, fmt.Errorf("%w: scheduled service", ErrNotFound)
	}
	s.mu.Unlock()

	if err := s.publisher.Publish(ctx, envelope); err != nil {
		return CreateServiceSegmentResult{}, nil, fmt.Errorf("%w: %v", ErrPublish, err)
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	if _, exists := s.scheduledServices[segment.ScheduledServiceRef]; !exists {
		return CreateServiceSegmentResult{}, nil, fmt.Errorf("%w: scheduled service", ErrNotFound)
	}
	s.segments[segment.SegmentRef] = segment
	s.idempotencyRecords[command.IdempotencyKey] = idempotencyRecord{requestHash: command.RequestHash, statusCode: 201, body: result}
	return result, nil, nil
}

func (s *Service) idempotencyReplay(key, requestHash string) (*IdempotencyResult, error) {
	key = strings.TrimSpace(key)
	if key == "" {
		return nil, ErrIdempotencyKeyRequired
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	record, exists := s.idempotencyRecords[key]
	if !exists {
		return nil, nil
	}
	if record.requestHash != requestHash {
		return nil, ErrIdempotencyKeyReused
	}
	return &IdempotencyResult{StatusCode: record.statusCode, Body: record.body}, nil
}

func (s *Service) newEnvelope(eventType, correlationID, causationID string, payload []byte) EventEnvelope {
	now := s.now().UTC()
	return EventEnvelope{
		EventID:       s.idGenerator("evt"),
		EventType:     eventType,
		OccurredAt:    now,
		CorrelationID: strings.TrimSpace(correlationID),
		CausationID:   strings.TrimSpace(causationID),
		Producer:      ProducerServicePlan,
		SchemaVersion: SchemaVersion,
		Payload:       append(json.RawMessage(nil), payload...),
	}
}

func validateCreateScheduledService(command CreateScheduledServiceCommand) error {
	if strings.TrimSpace(command.IdempotencyKey) == "" {
		return ErrIdempotencyKeyRequired
	}
	if strings.TrimSpace(command.CarrierID) == "" || strings.TrimSpace(command.ServiceNumber) == "" || strings.TrimSpace(command.OriginNodeID) == "" || strings.TrimSpace(command.DestinationNodeID) == "" {
		return fmt.Errorf("%w: required field missing", ErrValidation)
	}
	if command.DepartureTime.IsZero() || command.ArrivalTime.IsZero() {
		return fmt.Errorf("%w: departureTime and arrivalTime are required", ErrValidation)
	}
	if !command.ArrivalTime.After(command.DepartureTime) {
		return fmt.Errorf("%w: arrivalTime must be after departureTime", ErrValidation)
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
	if strings.TrimSpace(command.ScheduledServiceRef) == "" || strings.TrimSpace(command.OriginStopRef) == "" || strings.TrimSpace(command.DestinationStopRef) == "" {
		return fmt.Errorf("%w: required field missing", ErrValidation)
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
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return fmt.Sprintf("%s-%d", prefix, time.Now().UTC().UnixNano())
	}
	return prefix + "-" + hex.EncodeToString(b[:4]) + "-" + hex.EncodeToString(b[4:6]) + "-" + hex.EncodeToString(b[6:8]) + "-" + hex.EncodeToString(b[8:10]) + "-" + hex.EncodeToString(b[10:])
}
