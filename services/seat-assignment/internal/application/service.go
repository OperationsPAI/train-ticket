package application

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/domain"
	"go.opentelemetry.io/otel/propagation"
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
	SaveSeatMap(context.Context, *domain.ContractSeatMap) error
	UpdateSeatMap(context.Context, *domain.ContractSeatMap, int64) error
	GetSeatMap(context.Context, string) (*domain.ContractSeatMap, int64, error)
	FindSeatMaps(context.Context, string, string, string, int, int) ([]domain.ContractSeatMap, int, error)
	SaveSeatAllocation(context.Context, *domain.ContractSeatAllocation) error
	UpdateSeatAllocation(context.Context, *domain.ContractSeatAllocation, int64) error
	GetSeatAllocation(context.Context, string) (*domain.ContractSeatAllocation, int64, error)
	FindSeatAllocations(context.Context, string, string, int, int) ([]domain.ContractSeatAllocation, int, error)
	FindActiveSeatAllocations(context.Context, string, string) ([]domain.ContractSeatAllocation, error)
	FindSeatAllocationsByCapacityRecovery(context.Context, string, string, domain.StationInterval) ([]domain.ContractSeatAllocation, error)
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
	case "TicketIssued", "EntitlementIssued":
		return s.handleTicketIssued(ctx, envelope)
	case "PostSalesApplied", "EntitlementVoided", "EntitlementIssueFailed":
		return s.handlePostSalesApplied(ctx, envelope)
	case "CapacityReleased":
		return s.handleCapacityReleased(ctx, envelope)
	case "CapacityHoldExpired":
		return s.handleCapacityHoldExpired(ctx, envelope)
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
	if allocationID := str(p, "seatAllocationId"); allocationID != "" {
		return s.confirmSeatAllocation(ctx, allocationID, str(p, "entitlementId"), e)
	}
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

func (s *Service) confirmSeatAllocation(ctx context.Context, allocationID, entitlementID string, e kitmsg.EventEnvelope) error {
	if entitlementID == "" {
		return nil
	}
	now := time.Now().UTC()
	return s.tx(ctx, func(tx context.Context) error {
		allocation, expected, err := s.repo.GetSeatAllocation(tx, allocationID)
		if err != nil {
			return nil
		}
		if allocation.Status == domain.AllocationStatusConfirmed {
			return nil
		}
		if allocation.Status != domain.AllocationStatusAllocated && allocation.Status != domain.AllocationStatusStanding {
			return nil
		}
		t := now
		allocation.ConfirmedAt = &t
		allocation.Status = domain.AllocationStatusConfirmed
		if err := s.repo.UpdateSeatAllocation(tx, allocation, expected); err != nil {
			return derr("CONFLICT", err.Error())
		}
		payload := map[string]any{"seatAllocationId": allocation.SeatAllocationID, "segmentBookingId": allocation.SegmentBookingID, "journeyOrderId": allocation.JourneyOrderID, "travelerRef": allocation.TravelerRef, "entitlementId": entitlementID, "seatRef": allocation.SeatRef, "confirmedAt": now, "sourceEventId": e.EventID, "status": allocation.Status}
		return s.publish(tx, []domain.Event{{EventType: "SeatAllocationConfirmed", AggregateID: allocation.SeatAllocationID, Version: 2, OccurredAt: now, CorrelationID: e.CorrelationID, CausationID: e.EventID, Payload: payload}})
	})
}

func (s *Service) handlePostSalesApplied(ctx context.Context, e kitmsg.EventEnvelope) error {
	var p map[string]any
	_ = json.Unmarshal(e.Payload, &p)
	reason := releaseReasonForEntitlementEvent(e.EventType, str(p, "reason"))
	releasedAt := releaseTimeForEntitlementEvent(e.EventType, p)
	if allocationID := str(p, "seatAllocationId"); allocationID != "" {
		return s.releaseSeatAllocation(ctx, allocationID, reason, releasedAt, e)
	}
	if segmentBookingID := str(p, "segmentBookingId"); segmentBookingID != "" {
		return s.releaseSeatAllocationsBySegmentBooking(ctx, segmentBookingID, reason, releasedAt, e)
	}
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

func (s *Service) releaseSeatAllocation(ctx context.Context, allocationID, releaseReason string, releasedAt time.Time, e kitmsg.EventEnvelope) error {
	return s.tx(ctx, func(tx context.Context) error {
		allocation, expected, err := s.repo.GetSeatAllocation(tx, allocationID)
		if err != nil {
			return nil
		}
		return s.releaseStoredSeatAllocation(tx, allocation, expected, releaseReason, releasedAt, e)
	})
}

func (s *Service) releaseSeatAllocationsBySegmentBooking(ctx context.Context, segmentBookingID, releaseReason string, releasedAt time.Time, e kitmsg.EventEnvelope) error {
	return s.tx(ctx, func(tx context.Context) error {
		const pageSize = 100
		for offset := 0; ; offset += pageSize {
			allocations, total, err := s.repo.FindSeatAllocations(tx, segmentBookingID, "", pageSize, offset)
			if err != nil {
				return err
			}
			for i := range allocations {
				allocation, expected, err := s.repo.GetSeatAllocation(tx, allocations[i].SeatAllocationID)
				if err != nil {
					continue
				}
				if err := s.releaseStoredSeatAllocation(tx, allocation, expected, releaseReason, releasedAt, e); err != nil {
					return err
				}
			}
			if offset+len(allocations) >= total || len(allocations) == 0 {
				return nil
			}
		}
	})
}

func (s *Service) releaseStoredSeatAllocation(ctx context.Context, allocation *domain.ContractSeatAllocation, expected int64, releaseReason string, releasedAt time.Time, e kitmsg.EventEnvelope) error {
	if allocation.Status == domain.AllocationStatusReleased || allocation.Status == domain.AllocationStatusExpired {
		return nil
	}
	if releaseReason == "" {
		releaseReason = "MANUAL_CORRECTION"
	}
	if releasedAt.IsZero() {
		releasedAt = time.Now().UTC()
	}
	allocation.Release(releasedAt)
	if err := s.repo.UpdateSeatAllocation(ctx, allocation, expected); err != nil {
		return derr("CONFLICT", err.Error())
	}
	payload := map[string]any{"seatAllocationId": allocation.SeatAllocationID, "segmentBookingId": allocation.SegmentBookingID, "journeyOrderId": allocation.JourneyOrderID, "travelerRef": allocation.TravelerRef, "capacityHoldId": allocation.CapacityHoldID, "seatRef": allocation.SeatRef, "releaseReason": releaseReason, "releasedAt": releasedAt.UTC(), "sourceEventId": e.EventID, "status": allocation.Status}
	return s.publish(ctx, []domain.Event{{EventType: "SeatAllocationReleased", AggregateID: allocation.SeatAllocationID, Version: 2, OccurredAt: releasedAt.UTC(), CorrelationID: e.CorrelationID, CausationID: e.EventID, Payload: payload}})
}

func releaseReasonForEntitlementEvent(eventType, entitlementReason string) string {
	switch eventType {
	case "EntitlementIssueFailed":
		return "ISSUE_FAILED"
	case "EntitlementVoided":
		switch strings.ToUpper(strings.TrimSpace(entitlementReason)) {
		case "CHANGE":
			return "CHANGED"
		case "DISRUPTION":
			return "DISRUPTION"
		case "MANUAL_CORRECTION":
			return "MANUAL_CORRECTION"
		default:
			return "VOIDED"
		}
	default:
		return "MANUAL_CORRECTION"
	}
}

func releaseTimeForEntitlementEvent(eventType string, payload map[string]any) time.Time {
	keys := []string{"releasedAt"}
	switch eventType {
	case "EntitlementVoided":
		keys = append([]string{"voidedAt"}, keys...)
	case "EntitlementIssueFailed":
		keys = append([]string{"failedAt"}, keys...)
	}
	for _, key := range keys {
		if raw := str(payload, key); raw != "" {
			if parsed, err := time.Parse(time.RFC3339, raw); err == nil {
				return parsed.UTC()
			}
		}
	}
	return time.Now().UTC()
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
		body, err := json.Marshal(ev.Payload)
		if err != nil {
			return derr("UNAVAILABLE", err.Error())
		}
		env := kitmsg.EventEnvelope{EventID: deterministicSeatAssignmentEventID(ev.EventType, ev.AggregateID, ev.Version), EventType: strings.TrimSpace(ev.EventType), OccurredAt: ev.OccurredAt.UTC(), CorrelationID: ids.CanonicalCorrelationID(ev.CorrelationID), Producer: domain.Producer, SchemaVersion: kitmsg.SchemaVersion, Payload: body}
		if strings.TrimSpace(ev.CausationID) != "" {
			env.CausationID = ids.CanonicalCausationID(ev.CausationID)
		}
		if traceparent, tracestate := traceContextFromContext(ctx); traceparent != "" {
			env.Traceparent = traceparent
			env.Tracestate = tracestate
		}
		if err := env.Validate(); err != nil {
			return derr("UNAVAILABLE", err.Error())
		}
		if err := s.publisher.Publish(ctx, env); err != nil {
			return derr("UNAVAILABLE", err.Error())
		}
	}
	return nil
}

func deterministicSeatAssignmentEventID(eventType, aggregateID string, aggregateVersion int64) string {
	seed := fmt.Sprintf("seat-assignment:%s:%s:%d", strings.TrimSpace(eventType), strings.TrimSpace(aggregateID), aggregateVersion)
	sum := sha256.Sum256([]byte(seed))
	b := sum[:16]
	b[6] = (b[6] & 0x0f) | 0x70
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("evt-%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func traceContextFromContext(ctx context.Context) (string, string) {
	carrier := propagation.MapCarrier{}
	propagation.TraceContext{}.Inject(ctx, carrier)
	return strings.TrimSpace(carrier.Get("traceparent")), strings.TrimSpace(carrier.Get("tracestate"))
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
