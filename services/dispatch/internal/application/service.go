package application

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/trainticket/greenfield/services/dispatch/internal/domain"
	"github.com/trainticket/greenfield/services/dispatch/internal/domain/ports"
)

type UnitOfWork func(context.Context, func(context.Context) error) error

type ServiceConfig struct {
	Rides           ports.RideRequestRepository
	Publisher       EventPublisher
	Clock           domain.Clock
	UnitOfWork      UnitOfWork
	RequestTimeout  time.Duration
	MatchingTimeout time.Duration
}
type Service struct {
	rides                           ports.RideRequestRepository
	publisher                       EventPublisher
	clock                           domain.Clock
	unitOfWork                      UnitOfWork
	requestTimeout, matchingTimeout time.Duration
}

func NewService(cfg ServiceConfig) *Service {
	p := cfg.Publisher
	if p == nil {
		p = NewNoopPublisher()
	}
	c := cfg.Clock
	if c == nil {
		c = domain.RealClock{}
	}
	u := cfg.UnitOfWork
	if u == nil {
		u = func(ctx context.Context, fn func(context.Context) error) error { return fn(ctx) }
	}
	rt := cfg.RequestTimeout
	if rt == 0 {
		rt = 10 * time.Minute
	}
	mt := cfg.MatchingTimeout
	if mt == 0 {
		mt = 10 * time.Minute
	}
	return &Service{rides: cfg.Rides, publisher: p, clock: c, unitOfWork: u, requestTimeout: rt, matchingTimeout: mt}
}

type TimeWindowDTO struct {
	StartAt string `json:"startAt"`
	EndAt   string `json:"endAt"`
}
type AssignmentDTO struct {
	RideAssignmentID string `json:"rideAssignmentId"`
	DriverRef        string `json:"driverRef"`
	VehicleRef       string `json:"vehicleRef"`
	ETASeconds       int    `json:"etaSeconds"`
	AssignedAt       string `json:"assignedAt"`
	ArrivedAt        string `json:"arrivedAt,omitempty"`
	StartedAt        string `json:"startedAt,omitempty"`
	EndedAt          string `json:"endedAt,omitempty"`
}
type RideRequestDTO struct {
	RideRequestID     string         `json:"rideRequestId"`
	RiderAccountID    string         `json:"riderAccountId"`
	TravelerRef       string         `json:"travelerRef"`
	PickupRef         string         `json:"pickupRef"`
	DropoffRef        string         `json:"dropoffRef"`
	TimeWindow        TimeWindowDTO  `json:"timeWindow"`
	EstimatedFareRef  string         `json:"estimatedFareRef,omitempty"`
	IntentFingerprint string         `json:"intentFingerprint"`
	Status            string         `json:"status"`
	Assignment        *AssignmentDTO `json:"assignment,omitempty"`
	CreatedAt         string         `json:"createdAt"`
	UpdatedAt         string         `json:"updatedAt"`
}
type Page struct {
	Items  []RideRequestDTO `json:"items"`
	Total  int              `json:"total"`
	Limit  int              `json:"limit"`
	Offset int              `json:"offset"`
}

type CreateRideRequest struct {
	PickupRef, DropoffRef, RiderAccountID, TravelerRef, EstimatedFareRef, IntentFingerprint, CorrelationID, CausationID string
	TimeWindow                                                                                                          TimeWindowDTO
}
type AssignDriverRequest struct {
	RideRequestID, DriverRef, VehicleRef, CorrelationID, CausationID string
	ETASeconds                                                       int
}
type ETARequest struct {
	RideRequestID, CorrelationID, CausationID string
	ETASeconds                                int
}
type ReasonRequest struct{ RideRequestID, Reason, CorrelationID, CausationID string }
type CompleteRequest struct{ RideRequestID, FinalFareRef, CorrelationID, CausationID string }

func (s *Service) CreateRideRequest(ctx context.Context, req CreateRideRequest) (*RideRequestDTO, error) {
	window, err := parseWindow(req.TimeWindow)
	if err != nil {
		return nil, NewError("VALIDATION_FAILED", err.Error())
	}
	now := s.clock.Now()
	ride, err := domain.NewRideRequest(domain.NewRideRequestID(), req.RiderAccountID, req.TravelerRef, req.PickupRef, req.DropoffRef, window, req.EstimatedFareRef, req.IntentFingerprint, now)
	if err != nil {
		return nil, NewError("DOMAIN_RULE_VIOLATION", err.Error())
	}
	var saved domain.RideRequest
	err = s.unitOfWork(ctx, func(tx context.Context) error {
		active, err := s.rides.FindActiveByIntent(tx, ride.RiderAccountID, ride.IntentFingerprint)
		if err != nil {
			return err
		}
		if active != nil {
			return NewError("CONFLICT", "active dispatch already exists for rider intent")
		}
		if err := s.rides.Save(tx, ride); err != nil {
			return NewError("CONFLICT", err.Error())
		}
		ride.Version = 1
		if err := s.publisher.Publish(tx, domain.NewEventEnvelope("DispatchRequested", now, req.CorrelationID, req.CausationID, ride.ID, 1, requestedPayload(ride))); err != nil {
			return NewError("UNAVAILABLE", "event publisher unavailable")
		}
		if err := ride.EnterMatching(now); err != nil {
			return err
		}
		if err := s.rides.Update(tx, ride); err != nil {
			return err
		}
		saved = ride
		return nil
	})
	if err != nil {
		return nil, err
	}
	return toDTO(saved), nil
}
func (s *Service) GetRideRequest(ctx context.Context, id string) (*RideRequestDTO, error) {
	r, err := s.rides.FindByID(ctx, domain.RideRequestID(id))
	if err != nil {
		return nil, err
	}
	if r == nil {
		return nil, NewError("NOT_FOUND", "ride request not found")
	}
	return toDTO(*r), nil
}
func (s *Service) ListRideRequests(ctx context.Context, rider, status string, limit, offset int) (Page, error) {
	if strings.TrimSpace(rider) == "" {
		return Page{}, NewError("VALIDATION_FAILED", "riderAccountId is required")
	}
	if limit <= 0 {
		limit = 20
	}
	if limit > 100 {
		limit = 100
	}
	if offset < 0 {
		offset = 0
	}
	page, err := s.rides.FindPage(ctx, ports.RideRequestListFilter{RiderAccountID: rider, Status: status, Limit: limit, Offset: offset})
	if err != nil {
		return Page{}, err
	}
	items := make([]RideRequestDTO, 0, len(page.Items))
	for _, r := range page.Items {
		items = append(items, *toDTO(r))
	}
	return Page{Items: items, Total: page.Total, Limit: limit, Offset: offset}, nil
}
func (s *Service) AssignDriver(ctx context.Context, req AssignDriverRequest) (*RideRequestDTO, error) {
	return s.change(ctx, req.RideRequestID, req.CorrelationID, req.CausationID, func(r *domain.RideRequest, now time.Time) (string, any, error) {
		if err := r.AssignDriver(req.DriverRef, req.VehicleRef, req.ETASeconds, now); err != nil {
			return "", nil, err
		}
		return "DriverAssigned", assignedPayload(*r), nil
	})
}
func (s *Service) UpdateETA(ctx context.Context, req ETARequest) (*RideRequestDTO, error) {
	return s.change(ctx, req.RideRequestID, req.CorrelationID, req.CausationID, func(r *domain.RideRequest, now time.Time) (string, any, error) {
		if err := r.UpdateETA(req.ETASeconds, now); err != nil {
			return "", nil, err
		}
		return "DriverEtaUpdated", etaPayload(*r), nil
	})
}
func (s *Service) MarkDriverArrived(ctx context.Context, id, corr, caus string) (*RideRequestDTO, error) {
	return s.change(ctx, id, corr, caus, func(r *domain.RideRequest, now time.Time) (string, any, error) {
		if err := r.MarkDriverArrived(now); err != nil {
			return "", nil, err
		}
		return "DriverArrived", arrivedPayload(*r), nil
	})
}
func (s *Service) StartRide(ctx context.Context, id, corr, caus string) (*RideRequestDTO, error) {
	return s.change(ctx, id, corr, caus, func(r *domain.RideRequest, now time.Time) (string, any, error) {
		if err := r.StartRide(now); err != nil {
			return "", nil, err
		}
		return "RideStarted", startedPayload(*r), nil
	})
}
func (s *Service) Complete(ctx context.Context, req CompleteRequest) (*RideRequestDTO, error) {
	return s.change(ctx, req.RideRequestID, req.CorrelationID, req.CausationID, func(r *domain.RideRequest, now time.Time) (string, any, error) {
		if err := r.Complete(req.FinalFareRef, now); err != nil {
			return "", nil, err
		}
		return "RideEnded", endedPayload(*r), nil
	})
}
func (s *Service) DriverCancel(ctx context.Context, req ReasonRequest) (*RideRequestDTO, error) {
	return s.change(ctx, req.RideRequestID, req.CorrelationID, req.CausationID, func(r *domain.RideRequest, now time.Time) (string, any, error) {
		c, err := r.CancelByDriver(req.Reason, now)
		if err != nil {
			return "", nil, err
		}
		return "DriverCancelled", domain.DriverCancelledEvent{RideRequestID: r.ID, RideAssignmentID: c.AssignmentID, RiderAccountID: r.RiderAccountID, TravelerRef: r.TravelerRef, DriverRef: c.DriverRef, VehicleRef: c.VehicleRef, CancelledAt: domain.FormatTimestamp(c.CancelledAt), Reason: c.Reason, Status: domain.StatusDriverCancelled, NextStatus: domain.StatusMatching}, nil
	})
}
func (s *Service) UserCancel(ctx context.Context, req ReasonRequest) (*RideRequestDTO, error) {
	return s.change(ctx, req.RideRequestID, req.CorrelationID, req.CausationID, func(r *domain.RideRequest, now time.Time) (string, any, error) {
		aid, err := r.CancelByUser(req.Reason, now)
		if err != nil {
			return "", nil, err
		}
		return "DispatchUserCancelled", domain.DispatchUserCancelledEvent{RideRequestID: r.ID, RideAssignmentID: aid, RiderAccountID: r.RiderAccountID, TravelerRef: r.TravelerRef, PickupRef: r.PickupRef, DropoffRef: r.DropoffRef, CancelledAt: domain.FormatTimestamp(now), Reason: req.Reason, Status: r.Status}, nil
	})
}
func (s *Service) NoShow(ctx context.Context, req ReasonRequest) (*RideRequestDTO, error) {
	return s.change(ctx, req.RideRequestID, req.CorrelationID, req.CausationID, func(r *domain.RideRequest, now time.Time) (string, any, error) {
		if err := r.RecordNoShow(req.Reason, now); err != nil {
			return "", nil, err
		}
		return "DispatchNoShowRecorded", noShowPayload(*r, req.Reason, now), nil
	})
}

func (s *Service) change(ctx context.Context, id, corr, caus string, fn func(*domain.RideRequest, time.Time) (string, any, error)) (*RideRequestDTO, error) {
	now := s.clock.Now()
	var out domain.RideRequest
	err := s.unitOfWork(ctx, func(tx context.Context) error {
		r, err := s.rides.FindByID(tx, domain.RideRequestID(id))
		if err != nil {
			return err
		}
		if r == nil {
			return NewError("NOT_FOUND", "ride request not found")
		}
		eventType, payload, err := fn(r, now)
		if err != nil {
			return NewError("PRECONDITION_FAILED", err.Error())
		}
		if err := s.rides.Update(tx, *r); err != nil {
			return err
		}
		if err := s.publisher.Publish(tx, domain.NewEventEnvelope(eventType, now, corr, caus, r.ID, r.Version+1, payload)); err != nil {
			return NewError("UNAVAILABLE", "event publisher unavailable")
		}
		out = *r
		return nil
	})
	if err != nil {
		return nil, err
	}
	return toDTO(out), nil
}

func parseWindow(dto TimeWindowDTO) (domain.TimeWindow, error) {
	s, err := time.Parse(time.RFC3339, dto.StartAt)
	if err != nil {
		return domain.TimeWindow{}, fmt.Errorf("timeWindow.startAt must be RFC3339 UTC")
	}
	e, err := time.Parse(time.RFC3339, dto.EndAt)
	if err != nil {
		return domain.TimeWindow{}, fmt.Errorf("timeWindow.endAt must be RFC3339 UTC")
	}
	return domain.TimeWindow{StartAt: s.UTC(), EndAt: e.UTC()}, nil
}
func toDTO(r domain.RideRequest) *RideRequestDTO {
	dto := &RideRequestDTO{RideRequestID: string(r.ID), RiderAccountID: r.RiderAccountID, TravelerRef: r.TravelerRef, PickupRef: r.PickupRef, DropoffRef: r.DropoffRef, TimeWindow: TimeWindowDTO{StartAt: domain.FormatTimestamp(r.TimeWindow.StartAt), EndAt: domain.FormatTimestamp(r.TimeWindow.EndAt)}, EstimatedFareRef: r.EstimatedFareRef, IntentFingerprint: r.IntentFingerprint, Status: string(r.Status), CreatedAt: domain.FormatTimestamp(r.CreatedAt), UpdatedAt: domain.FormatTimestamp(r.UpdatedAt)}
	if r.Assignment != nil {
		a := r.Assignment
		dto.Assignment = &AssignmentDTO{RideAssignmentID: string(a.ID), DriverRef: a.DriverRef, VehicleRef: a.VehicleRef, ETASeconds: a.ETASeconds, AssignedAt: domain.FormatTimestamp(a.AssignedAt)}
		if a.ArrivedAt != nil {
			dto.Assignment.ArrivedAt = domain.FormatTimestamp(*a.ArrivedAt)
		}
		if a.StartedAt != nil {
			dto.Assignment.StartedAt = domain.FormatTimestamp(*a.StartedAt)
		}
		if a.EndedAt != nil {
			dto.Assignment.EndedAt = domain.FormatTimestamp(*a.EndedAt)
		}
	}
	return dto
}
func requestedPayload(r domain.RideRequest) domain.DispatchRequestedEvent {
	return domain.DispatchRequestedEvent{RideRequestID: r.ID, RiderAccountID: r.RiderAccountID, TravelerRef: r.TravelerRef, PickupRef: r.PickupRef, DropoffRef: r.DropoffRef, TimeWindow: domain.TimeWindowPayload{StartAt: domain.FormatTimestamp(r.TimeWindow.StartAt), EndAt: domain.FormatTimestamp(r.TimeWindow.EndAt)}, EstimatedFareRef: r.EstimatedFareRef, IntentFingerprint: r.IntentFingerprint, Status: r.Status, RequestedAt: domain.FormatTimestamp(r.CreatedAt)}
}
func assignedPayload(r domain.RideRequest) domain.DriverAssignedEvent {
	a := r.Assignment
	return domain.DriverAssignedEvent{RideRequestID: r.ID, RideAssignmentID: a.ID, RiderAccountID: r.RiderAccountID, TravelerRef: r.TravelerRef, PickupRef: r.PickupRef, DropoffRef: r.DropoffRef, DriverRef: a.DriverRef, VehicleRef: a.VehicleRef, ETASeconds: a.ETASeconds, AssignedAt: domain.FormatTimestamp(a.AssignedAt), Status: r.Status}
}
func etaPayload(r domain.RideRequest) domain.DriverEtaUpdatedEvent {
	a := r.Assignment
	return domain.DriverEtaUpdatedEvent{RideRequestID: r.ID, RideAssignmentID: a.ID, RiderAccountID: r.RiderAccountID, TravelerRef: r.TravelerRef, DriverRef: a.DriverRef, VehicleRef: a.VehicleRef, ETASeconds: a.ETASeconds, UpdatedAt: domain.FormatTimestamp(r.UpdatedAt), Status: r.Status}
}
func arrivedPayload(r domain.RideRequest) domain.DriverArrivedEvent {
	a := r.Assignment
	return domain.DriverArrivedEvent{RideRequestID: r.ID, RideAssignmentID: a.ID, RiderAccountID: r.RiderAccountID, TravelerRef: r.TravelerRef, PickupRef: r.PickupRef, DropoffRef: r.DropoffRef, DriverRef: a.DriverRef, VehicleRef: a.VehicleRef, ArrivedAt: domain.FormatTimestamp(*a.ArrivedAt), Status: r.Status}
}
func startedPayload(r domain.RideRequest) domain.RideStartedEvent {
	a := r.Assignment
	return domain.RideStartedEvent{RideRequestID: r.ID, RideAssignmentID: a.ID, RiderAccountID: r.RiderAccountID, TravelerRef: r.TravelerRef, PickupRef: r.PickupRef, DropoffRef: r.DropoffRef, DriverRef: a.DriverRef, VehicleRef: a.VehicleRef, StartedAt: domain.FormatTimestamp(*a.StartedAt), Status: r.Status}
}
func endedPayload(r domain.RideRequest) domain.RideEndedEvent {
	a := r.Assignment
	return domain.RideEndedEvent{RideRequestID: r.ID, RideAssignmentID: a.ID, RiderAccountID: r.RiderAccountID, TravelerRef: r.TravelerRef, PickupRef: r.PickupRef, DropoffRef: r.DropoffRef, DriverRef: a.DriverRef, VehicleRef: a.VehicleRef, StartedAt: domain.FormatTimestamp(*a.StartedAt), EndedAt: domain.FormatTimestamp(*a.EndedAt), FinalFareRef: r.FinalFareRef, Status: r.Status}
}
func noShowPayload(r domain.RideRequest, reason string, now time.Time) domain.DispatchNoShowRecordedEvent {
	a := r.Assignment
	return domain.DispatchNoShowRecordedEvent{RideRequestID: r.ID, RideAssignmentID: a.ID, RiderAccountID: r.RiderAccountID, TravelerRef: r.TravelerRef, PickupRef: r.PickupRef, DropoffRef: r.DropoffRef, DriverRef: a.DriverRef, VehicleRef: a.VehicleRef, RecordedAt: domain.FormatTimestamp(now), Reason: reason, Status: r.Status}
}
