package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/provider-integration/internal/application"
)

type unitOfWork interface {
	Within(context.Context, func(context.Context) error) error
}

type ReservationService struct {
	db        ContextDBProvider
	publisher application.EventPublisher
	uow       unitOfWork
}

func NewReservationServiceWithProvider(db ContextDBProvider, publisher application.EventPublisher) *ReservationService {
	service := &ReservationService{db: db, publisher: publisher}
	if uow, ok := db.(unitOfWork); ok {
		service.uow = uow
	}
	return service
}

func (s *ReservationService) within(ctx context.Context, fn func(context.Context) error) error {
	if s.uow == nil {
		return fn(ctx)
	}
	return s.uow.Within(ctx, fn)
}

func (s *ReservationService) RequestReservation(ctx context.Context, cmd application.RequestProviderReservationCommand) (application.ProviderReservationResult, error) {
	if existing, err := s.findReservation(ctx, cmd.SegmentBookingID); err == nil {
		return existing, nil
	} else if !errors.Is(err, application.ErrNotFound) {
		return application.ProviderReservationResult{}, err
	}
	created, err := application.BuildProviderReservationResult(cmd)
	if err != nil {
		return application.ProviderReservationResult{}, err
	}
	var inserted bool
	if err := s.within(ctx, func(txCtx context.Context) error {
		data, err := json.Marshal(created)
		if err != nil {
			return err
		}
		repo := storage.NewSnapshotRepository(s.db.DBFor(txCtx), "provider_reservation_snapshots")
		if err := repo.Insert(txCtx, created.SegmentBookingID, data); err != nil {
			if errors.Is(err, storage.ErrConflict) {
				return nil
			}
			return err
		}
		inserted = true
		if s.publisher == nil {
			return nil
		}
		envelope, err := application.NewEventEnvelope("ProviderReservationConfirmed", cmd.CorrelationID, cmd.CausationID, map[string]any{
			"segmentBookingId":   created.SegmentBookingID,
			"providerReference":  created.ProviderReference,
			"normalizedEvidence": created.NormalizedEvidence,
		})
		if err != nil {
			return err
		}
		return s.publisher.Publish(txCtx, envelope)
	}); err != nil {
		return application.ProviderReservationResult{}, err
	}
	if !inserted {
		return s.findReservation(ctx, cmd.SegmentBookingID)
	}
	return created, nil
}

func (s *ReservationService) CancelReservation(ctx context.Context, cmd application.CancelProviderReservationCommand) (application.CancelProviderReservationResult, error) {
	if _, err := s.findReservation(ctx, strings.TrimSpace(cmd.SegmentBookingID)); err != nil {
		return application.CancelProviderReservationResult{}, err
	}
	result := application.CancelProviderReservationResult{SegmentBookingID: strings.TrimSpace(cmd.SegmentBookingID), CancellationStatus: application.CancellationCancelled}
	if err := s.within(ctx, func(txCtx context.Context) error {
		data, err := json.Marshal(result)
		if err != nil {
			return err
		}
		repo := storage.NewSnapshotRepository(s.db.DBFor(txCtx), "provider_cancellation_snapshots")
		if err := repo.Insert(txCtx, result.SegmentBookingID, data); err != nil {
			if errors.Is(err, storage.ErrConflict) {
				return nil
			}
			return err
		}
		return nil
	}); err != nil {
		return application.CancelProviderReservationResult{}, err
	}
	return result, nil
}

func (s *ReservationService) findReservation(ctx context.Context, id string) (application.ProviderReservationResult, error) {
	snap, ok, err := storage.NewSnapshotRepository(s.db.DBFor(ctx), "provider_reservation_snapshots").Get(ctx, strings.TrimSpace(id))
	if err != nil {
		return application.ProviderReservationResult{}, err
	}
	if !ok {
		return application.ProviderReservationResult{}, application.ErrNotFound
	}
	var result application.ProviderReservationResult
	if err := json.Unmarshal(snap.Data, &result); err != nil {
		return application.ProviderReservationResult{}, err
	}
	return result, nil
}

type ProcessedEvents struct {
	db  ContextDBProvider
	uow unitOfWork
}

func NewProcessedEventsWithProvider(db ContextDBProvider) *ProcessedEvents {
	log := &ProcessedEvents{db: db}
	if uow, ok := db.(unitOfWork); ok {
		log.uow = uow
	}
	return log
}
func (p *ProcessedEvents) AlreadyProcessed(eventID string) bool { return false }
func (p *ProcessedEvents) RecordProcessed(eventID string)       {}
func (p *ProcessedEvents) Claim(ctx context.Context, eventID, stream string) (bool, error) {
	return storage.NewProcessedEvents(p.db.DBFor(ctx)).TryRecord(ctx, eventID, stream)
}
func (p *ProcessedEvents) Within(ctx context.Context, fn func(context.Context) error) error {
	if p.uow == nil {
		return fn(ctx)
	}
	return p.uow.Within(ctx, fn)
}

func DeduplicatingHandler(log *ProcessedEvents, next application.EventHandler) application.EventHandler {
	return func(ctx context.Context, envelope application.EventEnvelope) error {
		if strings.TrimSpace(envelope.EventID) == "" {
			return application.FatalHandlerError(fmt.Errorf("eventId is required"))
		}
		return log.Within(ctx, func(txCtx context.Context) error {
			claimed, err := log.Claim(txCtx, envelope.EventID, envelope.Producer)
			if err != nil {
				return application.TransientHandlerError(err)
			}
			if !claimed {
				return nil
			}
			return next(txCtx, envelope)
		})
	}
}
