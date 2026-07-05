package application

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
)

var (
	ErrValidation          = errors.New("validation failed")
	ErrNotFound            = errors.New("not found")
	ErrUnavailable         = errors.New("unavailable")
	ErrIdempotencyConflict = errors.New("idempotency key reused")
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
	IdempotencyKey     string         `json:"idempotencyKey"`
	HeaderKey          string         `json:"-"`
	CorrelationID      string         `json:"-"`
}

type ProviderReservationResult struct {
	SegmentBookingID   string            `json:"segmentBookingId"`
	Status             ReservationStatus `json:"status"`
	ProviderReference  string            `json:"providerReference,omitempty"`
	NormalizedEvidence string            `json:"-"`
}

type CancelProviderReservationCommand struct {
	SegmentBookingID string `json:"segmentBookingId"`
	IdempotencyKey   string `json:"-"`
	CorrelationID    string `json:"-"`
}

type CancelProviderReservationResult struct {
	SegmentBookingID   string             `json:"segmentBookingId"`
	CancellationStatus CancellationStatus `json:"cancellationStatus"`
}

type ProviderReservationService interface {
	RequestReservation(context.Context, RequestProviderReservationCommand) (ProviderReservationResult, error)
	CancelReservation(context.Context, CancelProviderReservationCommand) (CancelProviderReservationResult, error)
}

type idempotencyRecord struct {
	requestHash string
	statusCode  int
	body        []byte
}

type IdempotencyStore struct {
	mu      sync.Mutex
	records map[string]idempotencyRecord
}

func NewIdempotencyStore() *IdempotencyStore {
	return &IdempotencyStore{records: map[string]idempotencyRecord{}}
}

func (s *IdempotencyStore) Replay(key, requestHash string) (int, []byte, bool, error) {
	if s == nil {
		return 0, nil, false, nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	record, ok := s.records[key]
	if !ok {
		return 0, nil, false, nil
	}
	if record.requestHash != requestHash {
		return 0, nil, false, ErrIdempotencyConflict
	}
	return record.statusCode, append([]byte(nil), record.body...), true, nil
}

func (s *IdempotencyStore) Save(key, requestHash string, statusCode int, body []byte) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, exists := s.records[key]; exists {
		return
	}
	s.records[key] = idempotencyRecord{requestHash: requestHash, statusCode: statusCode, body: append([]byte(nil), body...)}
}

func HashJSON(value any) (string, error) {
	bytes, err := json.Marshal(value)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(bytes)
	return hex.EncodeToString(sum[:]), nil
}

type InMemoryReservationService struct {
	mu            sync.Mutex
	publisher     EventPublisher
	results       map[string]ProviderReservationResult
	cancellations map[string]CancelProviderReservationResult
}

func NewInMemoryReservationService(publisher EventPublisher) *InMemoryReservationService {
	return &InMemoryReservationService{
		publisher:     publisher,
		results:       map[string]ProviderReservationResult{},
		cancellations: map[string]CancelProviderReservationResult{},
	}
}

func (s *InMemoryReservationService) RequestReservation(ctx context.Context, cmd RequestProviderReservationCommand) (ProviderReservationResult, error) {
	if err := validateReservationCommand(cmd); err != nil {
		return ProviderReservationResult{}, err
	}
	result := ProviderReservationResult{
		SegmentBookingID:   strings.TrimSpace(cmd.SegmentBookingID),
		Status:             ReservationConfirmed,
		ProviderReference:  "prv-" + strings.TrimPrefix(strings.TrimSpace(cmd.SegmentBookingID), "sb-"),
		NormalizedEvidence: "normalized provider confirmation",
	}
	s.mu.Lock()
	s.results[result.SegmentBookingID] = result
	s.mu.Unlock()
	if err := s.publish(ctx, "ProviderReservationConfirmed", cmd.CorrelationID, cmd.HeaderKey, map[string]any{
		"segmentBookingId":   result.SegmentBookingID,
		"providerReference":  result.ProviderReference,
		"normalizedEvidence": result.NormalizedEvidence,
	}); err != nil {
		return ProviderReservationResult{}, ErrUnavailable
	}
	return result, nil
}

func (s *InMemoryReservationService) CancelReservation(ctx context.Context, cmd CancelProviderReservationCommand) (CancelProviderReservationResult, error) {
	if strings.TrimSpace(cmd.SegmentBookingID) == "" || !strings.HasPrefix(strings.TrimSpace(cmd.SegmentBookingID), "sb-") {
		return CancelProviderReservationResult{}, fmt.Errorf("%w: segmentBookingId is required", ErrValidation)
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
	if err := s.publish(ctx, "ProviderReservationCancelled", cmd.CorrelationID, cmd.IdempotencyKey, map[string]any{
		"segmentBookingId":   result.SegmentBookingID,
		"cancellationStatus": result.CancellationStatus,
	}); err != nil {
		return CancelProviderReservationResult{}, ErrUnavailable
	}
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

func validateReservationCommand(cmd RequestProviderReservationCommand) error {
	if strings.TrimSpace(cmd.SegmentBookingID) == "" || !strings.HasPrefix(strings.TrimSpace(cmd.SegmentBookingID), "sb-") {
		return fmt.Errorf("%w: segmentBookingId is required", ErrValidation)
	}
	if strings.TrimSpace(cmd.ProviderConfigRef) == "" {
		return fmt.Errorf("%w: providerConfigRef is required", ErrValidation)
	}
	if len(cmd.ReservationPayload) == 0 {
		return fmt.Errorf("%w: reservationPayload is required", ErrValidation)
	}
	if strings.TrimSpace(cmd.IdempotencyKey) == "" {
		return fmt.Errorf("%w: idempotencyKey is required", ErrValidation)
	}
	if strings.TrimSpace(cmd.HeaderKey) == "" {
		return fmt.Errorf("%w: Idempotency-Key header is required", ErrValidation)
	}
	if cmd.IdempotencyKey != cmd.HeaderKey {
		return fmt.Errorf("%w: idempotencyKey must match Idempotency-Key header", ErrValidation)
	}
	return nil
}
