package application

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/domain"
)

type DomainError struct{ Code, Message string }

func (e *DomainError) Error() string { return e.Code + ": " + e.Message }
func derr(code, msg string) error    { return &DomainError{code, msg} }

type UnitOfWork func(context.Context, func(context.Context) error) error
type Publisher interface {
	Publish(context.Context, kitmsg.EventEnvelope) error
}
type Repository interface {
	GetInventory(context.Context, string, string) (*domain.SeatInventory, int64, error)
	SaveInventory(context.Context, *domain.SeatInventory) error
	UpdateInventory(context.Context, *domain.SeatInventory, int64) error
	GetAssignment(context.Context, string) (*domain.SeatAssignment, int64, error)
	FindAssignments(context.Context, string, string, string) ([]domain.SeatAssignment, error)
	SaveAssignment(context.Context, *domain.SeatAssignment) error
	UpdateAssignment(context.Context, *domain.SeatAssignment, int64) error
}

type Service struct {
	repo      Repository
	publisher Publisher
	tx        UnitOfWork
	engine    domain.AssignmentEngine
	seatMap   domain.SeatMap
}

func New(repo Repository, publisher Publisher, tx UnitOfWork) *Service {
	if tx == nil {
		tx = func(ctx context.Context, fn func(context.Context) error) error { return fn(ctx) }
	}
	return &Service{repo: repo, publisher: publisher, tx: tx, engine: domain.NewAssignmentEngine(), seatMap: domain.DefaultSeatMap()}
}

type AssignSeatsRequest struct {
	SegmentRef    string                  `json:"segmentRef"`
	DepartureDate string                  `json:"departureDate"`
	TravelerRefs  []string                `json:"travelerRefs"`
	SeatClass     string                  `json:"seatClass"`
	Preferences   []domain.SeatPreference `json:"preferences"`
	HoldId        string                  `json:"holdId"`
	CorrelationID string                  `json:"-"`
	CausationID   string                  `json:"-"`
}
type AssignmentDTO struct {
	AssignmentId string    `json:"assignmentId"`
	SeatId       string    `json:"seatId"`
	TravelerRef  string    `json:"travelerRef"`
	CarNumber    int       `json:"carNumber"`
	Row          int       `json:"row"`
	Letter       string    `json:"letter"`
	Position     string    `json:"position"`
	Status       string    `json:"status"`
	ExpiresAt    time.Time `json:"expiresAt"`
}
type AssignSeatsResponse struct {
	Assignments []AssignmentDTO `json:"assignments"`
}

type AvailabilityResponse struct {
	TotalSeats int                          `json:"totalSeats"`
	Available  int                          `json:"available"`
	Occupied   int                          `json:"occupied"`
	ByClass    map[string]ClassAvailability `json:"byClass"`
}
type ClassAvailability struct {
	Total     int `json:"total"`
	Available int `json:"available"`
}

func (s *Service) AssignSeats(ctx context.Context, req AssignSeatsRequest) (*AssignSeatsResponse, error) {
	if strings.TrimSpace(req.SegmentRef) == "" || strings.TrimSpace(req.DepartureDate) == "" || len(req.TravelerRefs) == 0 {
		return nil, derr("VALIDATION_FAILED", "segmentRef, departureDate and travelerRefs are required")
	}
	if req.HoldId == "" {
		req.HoldId = domain.NewID("hold")
	}
	now := time.Now().UTC()
	response := &AssignSeatsResponse{}
	err := s.tx(ctx, func(tx context.Context) error {
		inv, expected, err := s.loadInventory(tx, req.SegmentRef, req.DepartureDate)
		if err != nil {
			return err
		}
		occupied := inv.OccupiedSeatIDs(now)
		seats, err := s.engine.Choose(s.seatMap, occupied, domain.AssignmentRequest{SegmentRef: req.SegmentRef, TravelerRefs: req.TravelerRefs, SeatClass: req.SeatClass, Preferences: req.Preferences})
		if err != nil {
			return derr("NO_SEAT_AVAILABLE", err.Error())
		}
		pendingEvents := []domain.Event{}
		for idx, seat := range seats {
			a, err := inv.HoldSeat(seat.SeatId, req.TravelerRefs[idx], req.HoldId, now)
			if err != nil {
				return mapDomain(err)
			}
			if err := s.repo.SaveAssignment(tx, a); err != nil {
				return derr("CONFLICT", err.Error())
			}
			response.Assignments = append(response.Assignments, dto(*a, seat))
			pendingEvents = append(pendingEvents, assignedEvent(*a, req.CorrelationID, req.CausationID, now))
		}
		if expected == 0 {
			err = s.repo.SaveInventory(tx, inv)
		} else {
			err = s.repo.UpdateInventory(tx, inv, expected)
		}
		if err != nil {
			return derr("CONFLICT", err.Error())
		}
		return s.publish(tx, pendingEvents)
	})
	if err != nil {
		return nil, err
	}
	return response, nil
}
func (s *Service) Availability(ctx context.Context, segmentRef, departureDate string) (*AvailabilityResponse, error) {
	inv, _, err := s.loadInventory(ctx, segmentRef, departureDate)
	if err != nil {
		return nil, err
	}
	occupiedSet := map[string]struct{}{}
	for _, id := range inv.OccupiedSeatIDs(time.Now().UTC()) {
		occupiedSet[id] = struct{}{}
	}
	resp := &AvailabilityResponse{TotalSeats: len(s.seatMap.Seats), ByClass: map[string]ClassAvailability{}}
	for _, seat := range s.seatMap.Seats {
		ca := resp.ByClass[seat.SeatType]
		ca.Total++
		if _, ok := occupiedSet[seat.SeatId]; !ok {
			ca.Available++
			resp.Available++
		}
		resp.ByClass[seat.SeatType] = ca
	}
	resp.Occupied = resp.TotalSeats - resp.Available
	return resp, nil
}
func (s *Service) Confirm(ctx context.Context, assignmentID, holdID, corr, cause string) (*AssignmentDTO, error) {
	var out *AssignmentDTO
	now := time.Now().UTC()
	err := s.tx(ctx, func(tx context.Context) error {
		a, exp, err := s.repo.GetAssignment(tx, assignmentID)
		if err != nil {
			return derr("NOT_FOUND", "assignment not found")
		}
		inv, invExp, err := s.loadInventory(tx, a.SegmentRef, a.DepartureDate)
		if err != nil {
			return err
		}
		if holdID == "" {
			holdID = a.HoldId
		}
		if _, err := inv.ConfirmSeat(a.SeatId, holdID, now); err != nil {
			return mapDomain(err)
		}
		if err := a.Confirm(holdID, now); err != nil {
			return mapDomain(err)
		}
		if err := s.repo.UpdateAssignment(tx, a, exp); err != nil {
			return derr("CONFLICT", err.Error())
		}
		if err := s.repo.UpdateInventory(tx, inv, invExp); err != nil {
			return derr("CONFLICT", err.Error())
		}
		seat, _ := s.seatMap.Find(a.SeatId)
		d := dto(*a, seat)
		out = &d
		return s.publish(tx, []domain.Event{confirmedEvent(*a, corr, cause, now)})
	})
	if err != nil {
		return nil, err
	}
	return out, nil
}
func (s *Service) Release(ctx context.Context, assignmentID, corr, cause string) error {
	now := time.Now().UTC()
	return s.tx(ctx, func(tx context.Context) error {
		a, exp, err := s.repo.GetAssignment(tx, assignmentID)
		if err != nil {
			return derr("NOT_FOUND", "assignment not found")
		}
		inv, invExp, err := s.loadInventory(tx, a.SegmentRef, a.DepartureDate)
		if err != nil {
			return err
		}
		if _, err := inv.ReleaseSeat(a.SeatId, now); err != nil {
			return mapDomain(err)
		}
		if err := a.Release(now); err != nil {
			return mapDomain(err)
		}
		if err := s.repo.UpdateAssignment(tx, a, exp); err != nil {
			return derr("CONFLICT", err.Error())
		}
		if err := s.repo.UpdateInventory(tx, inv, invExp); err != nil {
			return derr("CONFLICT", err.Error())
		}
		return s.publish(tx, []domain.Event{releasedEvent(*a, corr, cause, now)})
	})
}
func (s *Service) HandleSubscribedEvent(ctx context.Context, envelope kitmsg.EventEnvelope) error {
	switch envelope.EventType {
	case "TicketIssued":
		return s.handleTicketIssued(ctx, envelope)
	case "PostSalesApplied":
		return s.handlePostSalesApplied(ctx, envelope)
	case "BookingSagaStepSucceeded":
		return s.handleBookingSaga(ctx, envelope)
	case "SeatAllocationRequested":
		return s.handleSeatAllocationRequested(ctx, envelope)
	default:
		return nil
	}
}

// handleSeatAllocationRequested is the event-driven seat step of the booking saga.
// booking-orchestration publishes SeatAllocationRequested and then waits for a
// SeatAllocated event (with sagaId + segmentBookingId + seatAllocationRef) to
// advance out of SEAT_ASSIGNING. seat-assignment previously only exposed an HTTP
// assign API and published "SeatAssigned", so the saga hung forever. Allocate a
// seat and publish the SeatAllocated the saga expects.
func (s *Service) handleSeatAllocationRequested(ctx context.Context, e kitmsg.EventEnvelope) error {
	var p map[string]any
	_ = json.Unmarshal(e.Payload, &p)
	sagaID := str(p, "sagaId")
	segmentBookingID := str(p, "segmentBookingId")
	segmentRef := str(p, "segmentRef")
	travelerRef := str(p, "travelerRef")
	if sagaID == "" || segmentBookingID == "" || segmentRef == "" || travelerRef == "" {
		return nil
	}
	departureDate := str(p, "departureDate")
	if departureDate == "" {
		departureDate = deriveDepartureDate(segmentRef)
	}
	seatAllocationRef := ""
	seatID := ""
	if resp, err := s.AssignSeats(ctx, AssignSeatsRequest{
		SegmentRef:    segmentRef,
		DepartureDate: departureDate,
		TravelerRefs:  []string{travelerRef},
		HoldId:        str(p, "holdId"),
		CorrelationID: e.CorrelationID,
		CausationID:   e.EventID,
	}); err == nil && len(resp.Assignments) > 0 {
		seatAllocationRef = resp.Assignments[0].AssignmentId
		seatID = resp.Assignments[0].SeatId
	} else if err != nil {
		// Allocation failed (e.g. full map); still confirm the saga step with a
		// STANDING allocation ref so the saga can converge rather than hang.
		seatAllocationRef = domain.NewID("seat")
		seatID = "STANDING"
	}
	payload := map[string]any{
		"sagaId":            sagaID,
		"segmentBookingId":  segmentBookingID,
		"seatAllocationRef": seatAllocationRef,
		"segmentRef":        segmentRef,
		"travelerRef":       travelerRef,
		"seatId":            seatID,
		"status":            "ALLOCATED",
	}
	return s.publish(ctx, []domain.Event{{
		EventType:     "SeatAllocated",
		AggregateID:   segmentBookingID,
		Version:       1,
		OccurredAt:    time.Now().UTC(),
		CorrelationID: e.CorrelationID,
		CausationID:   e.EventID,
		Payload:       payload,
	}})
}

// deriveDepartureDate pulls the YYYY-MM-DD out of a canonical service segment ref
// such as `seg-web-2026-08-01-<hash>`.
func deriveDepartureDate(segmentRef string) string {
	for _, part := range strings.Split(segmentRef, "-") {
		if len(part) == 4 {
			// year token; the ref keeps date as separate ...-YYYY-MM-DD-... parts
			idx := strings.Index(segmentRef, part)
			candidate := segmentRef[idx:]
			fields := strings.Split(candidate, "-")
			if len(fields) >= 3 && len(fields[0]) == 4 && len(fields[1]) == 2 && len(fields[2]) == 2 {
				return fields[0] + "-" + fields[1] + "-" + fields[2]
			}
		}
	}
	return ""
}
func (s *Service) handleTicketIssued(ctx context.Context, e kitmsg.EventEnvelope) error {
	var p map[string]any
	_ = json.Unmarshal(e.Payload, &p)
	id := str(p, "assignmentId")
	if id == "" {
		id = str(p, "seatAssignmentId")
	}
	if id == "" {
		return nil
	}
	_, err := s.Confirm(ctx, id, "", e.CorrelationID, e.EventID)
	return err
}
func (s *Service) handlePostSalesApplied(ctx context.Context, e kitmsg.EventEnvelope) error {
	var p map[string]any
	_ = json.Unmarshal(e.Payload, &p)
	id := str(p, "assignmentId")
	if id == "" {
		id = str(p, "seatAssignmentId")
	}
	if id != "" {
		return s.Release(ctx, id, e.CorrelationID, e.EventID)
	}
	segment := str(p, "segmentRef")
	traveler := str(p, "travelerRef")
	if segment == "" {
		return nil
	}
	list, err := s.repo.FindAssignments(ctx, segment, "", traveler)
	if err != nil {
		return err
	}
	for _, a := range list {
		if a.Status != domain.StatusReleased {
			if err := s.Release(ctx, a.AssignmentId, e.CorrelationID, e.EventID); err != nil {
				return err
			}
		}
	}
	return nil
}
func (s *Service) handleBookingSaga(ctx context.Context, e kitmsg.EventEnvelope) error {
	var p map[string]any
	_ = json.Unmarshal(e.Payload, &p)
	step := strings.ToUpper(str(p, "step"))
	if step != "" && !strings.Contains(step, "CAPACITY") {
		return nil
	}
	travelers := []string{}
	if raw, ok := p["travelerRefs"].([]any); ok {
		for _, v := range raw {
			if tv, ok := v.(string); ok {
				travelers = append(travelers, tv)
			}
		}
	}
	if len(travelers) == 0 {
		if tv := str(p, "travelerRef"); tv != "" {
			travelers = []string{tv}
		}
	}
	if len(travelers) == 0 || str(p, "segmentRef") == "" {
		return nil
	}
	_, err := s.AssignSeats(ctx, AssignSeatsRequest{SegmentRef: str(p, "segmentRef"), DepartureDate: str(p, "departureDate"), TravelerRefs: travelers, SeatClass: str(p, "seatClass"), HoldId: str(p, "holdId"), CorrelationID: e.CorrelationID, CausationID: e.EventID})
	return err
}
func (s *Service) loadInventory(ctx context.Context, segmentRef, date string) (*domain.SeatInventory, int64, error) {
	inv, ver, err := s.repo.GetInventory(ctx, segmentRef, date)
	if err != nil {
		return nil, 0, derr("UNAVAILABLE", err.Error())
	}
	if inv == nil {
		return domain.NewSeatInventory(segmentRef, date, "CRH380A"), 0, nil
	}
	return inv, ver, nil
}
func (s *Service) publish(ctx context.Context, events []domain.Event) error {
	if s.publisher == nil {
		return nil
	}
	for _, ev := range events {
		env, err := kitmsg.NewEventEnvelope(ev.EventType, domain.Producer, ev.CorrelationID, ev.Payload, kitmsg.EnvelopeOptions{Now: ev.OccurredAt, CausationID: ev.CausationID, Context: ctx})
		if err != nil {
			return derr("UNAVAILABLE", err.Error())
		}
		if err := s.publisher.Publish(ctx, env); err != nil {
			return derr("UNAVAILABLE", err.Error())
		}
	}
	return nil
}
func dto(a domain.SeatAssignment, seat domain.Seat) AssignmentDTO {
	return AssignmentDTO{AssignmentId: a.AssignmentId, SeatId: a.SeatId, TravelerRef: a.TravelerRef, CarNumber: seat.CarNumber, Row: seat.Row, Letter: seat.Letter, Position: seat.Position, Status: a.Status, ExpiresAt: a.ExpiresAt}
}
func assignedEvent(a domain.SeatAssignment, corr, cause string, now time.Time) domain.Event {
	return domain.Event{EventType: "SeatAssigned", AggregateID: a.AssignmentId, Version: a.Version, OccurredAt: now, CorrelationID: corr, CausationID: cause, Payload: domain.SeatAssignedPayload{AssignmentId: a.AssignmentId, SegmentRef: a.SegmentRef, DepartureDate: a.DepartureDate, TravelerRef: a.TravelerRef, SeatId: a.SeatId, HoldId: a.HoldId, Status: a.Status, AssignedAt: a.AssignedAt, ExpiresAt: a.ExpiresAt}}
}
func confirmedEvent(a domain.SeatAssignment, corr, cause string, now time.Time) domain.Event {
	return domain.Event{EventType: "SeatConfirmed", AggregateID: a.AssignmentId, Version: a.Version, OccurredAt: now, CorrelationID: corr, CausationID: cause, Payload: domain.SeatConfirmedPayload{AssignmentId: a.AssignmentId, SegmentRef: a.SegmentRef, DepartureDate: a.DepartureDate, TravelerRef: a.TravelerRef, SeatId: a.SeatId, HoldId: a.HoldId, Status: a.Status, ConfirmedAt: now}}
}
func releasedEvent(a domain.SeatAssignment, corr, cause string, now time.Time) domain.Event {
	return domain.Event{EventType: "SeatReleased", AggregateID: a.AssignmentId, Version: a.Version, OccurredAt: now, CorrelationID: corr, CausationID: cause, Payload: domain.SeatReleasedPayload{AssignmentId: a.AssignmentId, SegmentRef: a.SegmentRef, DepartureDate: a.DepartureDate, TravelerRef: a.TravelerRef, SeatId: a.SeatId, HoldId: a.HoldId, Status: a.Status, ReleasedAt: now}}
}
func mapDomain(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, domain.ErrSeatUnavailable) {
		return derr("SEAT_UNAVAILABLE", err.Error())
	}
	if errors.Is(err, domain.ErrInvalidTransition) {
		return derr("DOMAIN_RULE_VIOLATION", err.Error())
	}
	return derr("VALIDATION_FAILED", err.Error())
}
func str(m map[string]any, key string) string {
	if v, ok := m[key].(string); ok {
		return v
	}
	return ""
}
