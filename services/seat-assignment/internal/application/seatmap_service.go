package application

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/domain"
)

type CreateSeatMapRequest struct {
	ScheduledServiceRef string `json:"scheduledServiceRef"`
	ServiceDate         string `json:"serviceDate"`
	CompositionVersion  string `json:"compositionVersion"`
	CompositionSeed     string `json:"compositionSeed"`
	MappingVersion      string `json:"mappingVersion"`
	ChangeScenario      string `json:"changeScenario"`
	OperatorRef         string `json:"operatorRef"`
	CorrelationID       string `json:"-"`
	CausationID         string `json:"-"`
}

type PublishSeatMapRequest struct {
	ExpectedSeatMapVersion int    `json:"expectedSeatMapVersion"`
	PublishReason          string `json:"publishReason"`
	OperatorRef            string `json:"operatorRef"`
	CorrelationID          string `json:"-"`
	CausationID            string `json:"-"`
}

type RetireSeatMapRequest struct {
	ExpectedSeatMapVersion int    `json:"expectedSeatMapVersion"`
	RetireReason           string `json:"retireReason"`
	OperatorRef            string `json:"operatorRef"`
	CorrelationID          string `json:"-"`
	CausationID            string `json:"-"`
}

type MarkSeatUnitUnavailableRequest struct {
	ExpectedSeatMapVersion int    `json:"expectedSeatMapVersion"`
	UnavailableReason      string `json:"unavailableReason"`
	OperatorRef            string `json:"operatorRef"`
	CorrelationID          string `json:"-"`
	CausationID            string `json:"-"`
}

type ReopenSeatUnitRequest struct {
	ExpectedSeatMapVersion int    `json:"expectedSeatMapVersion"`
	ReopenReason           string `json:"reopenReason"`
	OperatorRef            string `json:"operatorRef"`
	CorrelationID          string `json:"-"`
	CausationID            string `json:"-"`
}

type AllocateSeatRequest struct {
	SegmentBookingID    string                  `json:"segmentBookingId"`
	JourneyOrderID      string                  `json:"journeyOrderId"`
	TravelerRef         string                  `json:"travelerRef"`
	SegmentRef          string                  `json:"segmentRef"`
	ScheduledServiceRef string                  `json:"scheduledServiceRef"`
	ServiceDate         string                  `json:"serviceDate"`
	CapacityHoldID      string                  `json:"capacityHoldId"`
	CapacityUnitRef     string                  `json:"capacityUnitRef"`
	Interval            domain.StationInterval  `json:"interval"`
	ClassRef            string                  `json:"classRef"`
	IssuePurpose        string                  `json:"issuePurpose"`
	SeatPreferences     *domain.SeatPreferences `json:"seatPreferences"`
	ExpiresAt           time.Time               `json:"expiresAt"`
	CorrelationID       string                  `json:"-"`
	CausationID         string                  `json:"-"`
}

type AllocateSeatResponse struct {
	SeatAllocationID string         `json:"seatAllocationId"`
	Status           string         `json:"status"`
	SeatRef          domain.SeatRef `json:"seatRef"`
	ExpiresAt        time.Time      `json:"expiresAt"`
}

type Page[T any] struct {
	Items  []T `json:"items"`
	Total  int `json:"total"`
	Limit  int `json:"limit"`
	Offset int `json:"offset"`
}

func (s *Service) CreateSeatMap(ctx context.Context, req CreateSeatMapRequest) (*domain.ContractSeatMap, error) {
	if strings.TrimSpace(req.OperatorRef) == "" {
		return nil, derr("VALIDATION_FAILED", "operatorRef is required")
	}
	now := time.Now().UTC()
	seatMap, err := domain.NewContractSeatMap(req.ScheduledServiceRef, req.ServiceDate, req.CompositionVersion, req.CompositionSeed, req.MappingVersion, req.ChangeScenario, now)
	if err != nil {
		return nil, derr("VALIDATION_FAILED", err.Error())
	}
	err = s.tx(ctx, func(tx context.Context) error {
		if err := s.repo.SaveSeatMap(tx, seatMap); err != nil {
			return derr("CONFLICT", err.Error())
		}
		return s.publish(tx, []domain.Event{{EventType: "SeatMapBuilt", AggregateID: seatMap.SeatMapID, Version: int64(seatMap.SeatMapVersion), OccurredAt: now, CorrelationID: req.CorrelationID, CausationID: req.CausationID, Payload: seatMapBuiltPayload(*seatMap, now)}})
	})
	if err != nil {
		return nil, err
	}
	return seatMap, nil
}

func (s *Service) PublishSeatMap(ctx context.Context, id string, req PublishSeatMapRequest) (*domain.ContractSeatMap, error) {
	if strings.TrimSpace(req.OperatorRef) == "" || strings.TrimSpace(req.PublishReason) == "" {
		return nil, derr("VALIDATION_FAILED", "publishReason and operatorRef are required")
	}
	now := time.Now().UTC()
	var out *domain.ContractSeatMap
	err := s.tx(ctx, func(tx context.Context) error {
		seatMap, exp, err := s.repo.GetSeatMap(tx, id)
		if err != nil {
			return derr("NOT_FOUND", "seat map not found")
		}
		if seatMap.SeatMapVersion != req.ExpectedSeatMapVersion {
			return derr("PRECONDITION_FAILED", "seatMapVersion does not match")
		}
		if err := seatMap.Publish(req.ExpectedSeatMapVersion, now); err != nil {
			return derr("DOMAIN_RULE_VIOLATION", "seat map cannot be published from its current state")
		}
		if err := s.repo.UpdateSeatMap(tx, seatMap, exp); err != nil {
			return derr("CONFLICT", err.Error())
		}
		out = seatMap
		return s.publish(tx, []domain.Event{{EventType: "SeatMapVersionPublished", AggregateID: seatMap.SeatMapID, Version: int64(seatMap.SeatMapVersion), OccurredAt: now, CorrelationID: req.CorrelationID, CausationID: req.CausationID, Payload: map[string]any{"seatMapId": seatMap.SeatMapID, "scheduledServiceRef": seatMap.ScheduledServiceRef, "serviceDate": seatMap.ServiceDate, "compositionVersion": seatMap.CompositionVersion, "seatMapVersion": seatMap.SeatMapVersion, "publishedAt": now, "operatorRef": req.OperatorRef, "status": seatMap.Status}}})
	})
	return out, err
}

func (s *Service) RetireSeatMap(ctx context.Context, id string, req RetireSeatMapRequest) (*domain.ContractSeatMap, error) {
	if strings.TrimSpace(req.OperatorRef) == "" || strings.TrimSpace(req.RetireReason) == "" {
		return nil, derr("VALIDATION_FAILED", "retireReason and operatorRef are required")
	}
	now := time.Now().UTC()
	var out *domain.ContractSeatMap
	err := s.tx(ctx, func(tx context.Context) error {
		seatMap, exp, err := s.repo.GetSeatMap(tx, id)
		if err != nil {
			return derr("NOT_FOUND", "seat map not found")
		}
		if seatMap.SeatMapVersion != req.ExpectedSeatMapVersion {
			return derr("PRECONDITION_FAILED", "seatMapVersion does not match")
		}
		if err := seatMap.Retire(req.ExpectedSeatMapVersion, req.RetireReason, now); err != nil {
			return derr("DOMAIN_RULE_VIOLATION", "seat map cannot be retired from its current state")
		}
		if err := s.repo.UpdateSeatMap(tx, seatMap, exp); err != nil {
			return derr("CONFLICT", err.Error())
		}
		out = seatMap
		return s.publish(tx, []domain.Event{{EventType: "SeatMapVersionRetired", AggregateID: seatMap.SeatMapID, Version: int64(seatMap.SeatMapVersion), OccurredAt: now, CorrelationID: req.CorrelationID, CausationID: req.CausationID, Payload: map[string]any{"seatMapId": seatMap.SeatMapID, "scheduledServiceRef": seatMap.ScheduledServiceRef, "serviceDate": seatMap.ServiceDate, "seatMapVersion": seatMap.SeatMapVersion, "retireReason": req.RetireReason, "retiredAt": now, "operatorRef": req.OperatorRef, "status": seatMap.Status}}})
	})
	return out, err
}

func (s *Service) MarkSeatUnitUnavailable(ctx context.Context, id, seatUnitRef string, req MarkSeatUnitUnavailableRequest) (*domain.ContractSeatMap, error) {
	if strings.TrimSpace(req.OperatorRef) == "" || strings.TrimSpace(req.UnavailableReason) == "" {
		return nil, derr("VALIDATION_FAILED", "unavailableReason and operatorRef are required")
	}
	var out *domain.ContractSeatMap
	now := time.Now().UTC()
	err := s.tx(ctx, func(tx context.Context) error {
		seatMap, exp, err := s.repo.GetSeatMap(tx, id)
		if err != nil {
			return derr("NOT_FOUND", "seat map not found")
		}
		if seatMap.Status == domain.SeatMapStatusPublished {
			return derr("DOMAIN_RULE_VIOLATION", "published SeatMaps are immutable")
		}
		if seatMap.SeatMapVersion != req.ExpectedSeatMapVersion {
			return derr("PRECONDITION_FAILED", "seatMapVersion does not match")
		}
		unit, ok := seatMap.FindUnit(seatUnitRef)
		if !ok {
			return derr("NOT_FOUND", "seat unit not found")
		}
		coachNo, seatNo := unit.CoachNo, unit.SeatNo
		if err := seatMap.MarkSeatUnitUnavailable(seatUnitRef, req.UnavailableReason, req.ExpectedSeatMapVersion); err != nil {
			return mapDomain(err)
		}
		if err := s.repo.UpdateSeatMap(tx, seatMap, exp); err != nil {
			return derr("CONFLICT", err.Error())
		}
		out = seatMap
		return s.publish(tx, []domain.Event{{EventType: "SeatUnitUnavailableMarked", AggregateID: seatMap.SeatMapID, Version: int64(seatMap.SeatMapVersion), OccurredAt: now, CorrelationID: req.CorrelationID, CausationID: req.CausationID, Payload: map[string]any{"seatMapId": seatMap.SeatMapID, "seatMapVersion": seatMap.SeatMapVersion, "seatUnitRef": seatUnitRef, "coachNo": coachNo, "seatNo": seatNo, "unavailableReason": req.UnavailableReason, "markedAt": now, "operatorRef": req.OperatorRef}}})
	})
	return out, err
}

func (s *Service) ReopenSeatUnit(ctx context.Context, id, seatUnitRef string, req ReopenSeatUnitRequest) (*domain.ContractSeatMap, error) {
	if strings.TrimSpace(req.OperatorRef) == "" || strings.TrimSpace(req.ReopenReason) == "" {
		return nil, derr("VALIDATION_FAILED", "reopenReason and operatorRef are required")
	}
	var out *domain.ContractSeatMap
	now := time.Now().UTC()
	err := s.tx(ctx, func(tx context.Context) error {
		seatMap, exp, err := s.repo.GetSeatMap(tx, id)
		if err != nil {
			return derr("NOT_FOUND", "seat map not found")
		}
		if seatMap.Status == domain.SeatMapStatusPublished {
			return derr("DOMAIN_RULE_VIOLATION", "published SeatMaps are immutable")
		}
		if seatMap.SeatMapVersion != req.ExpectedSeatMapVersion {
			return derr("PRECONDITION_FAILED", "seatMapVersion does not match")
		}
		unit, ok := seatMap.FindUnit(seatUnitRef)
		if !ok {
			return derr("NOT_FOUND", "seat unit not found")
		}
		coachNo, seatNo := unit.CoachNo, unit.SeatNo
		if err := seatMap.ReopenSeatUnit(seatUnitRef, req.ExpectedSeatMapVersion); err != nil {
			return mapDomain(err)
		}
		if err := s.repo.UpdateSeatMap(tx, seatMap, exp); err != nil {
			return derr("CONFLICT", err.Error())
		}
		out = seatMap
		return s.publish(tx, []domain.Event{{EventType: "SeatUnitReopened", AggregateID: seatMap.SeatMapID, Version: int64(seatMap.SeatMapVersion), OccurredAt: now, CorrelationID: req.CorrelationID, CausationID: req.CausationID, Payload: map[string]any{"seatMapId": seatMap.SeatMapID, "seatMapVersion": seatMap.SeatMapVersion, "seatUnitRef": seatUnitRef, "coachNo": coachNo, "seatNo": seatNo, "reopenReason": req.ReopenReason, "reopenedAt": now, "operatorRef": req.OperatorRef}}})
	})
	return out, err
}

func (s *Service) GetSeatMap(ctx context.Context, id string) (*domain.ContractSeatMap, error) {
	m, _, err := s.repo.GetSeatMap(ctx, id)
	if err != nil {
		return nil, derr("NOT_FOUND", "seat map not found")
	}
	return m, nil
}
func (s *Service) ListSeatMaps(ctx context.Context, ss, date, status string, limit, offset int) (*Page[domain.ContractSeatMap], error) {
	if ss == "" || date == "" {
		return nil, derr("VALIDATION_FAILED", "scheduledServiceRef and serviceDate are required")
	}
	if limit <= 0 {
		limit = 20
	}
	if limit > 100 {
		limit = 100
	}
	items, total, err := s.repo.FindSeatMaps(ctx, ss, date, status, limit, offset)
	if err != nil {
		return nil, derr("UNAVAILABLE", err.Error())
	}
	return &Page[domain.ContractSeatMap]{Items: items, Total: total, Limit: limit, Offset: offset}, nil
}

func (s *Service) AllocateSeat(ctx context.Context, req AllocateSeatRequest) (*AllocateSeatResponse, error) {
	if err := validateAllocate(req); err != nil {
		return nil, err
	}
	now := time.Now().UTC()
	var resp *AllocateSeatResponse
	err := s.tx(ctx, func(tx context.Context) error {
		maps, _, err := s.repo.FindSeatMaps(tx, req.ScheduledServiceRef, req.ServiceDate, domain.SeatMapStatusPublished, 100, 0)
		if err != nil {
			return derr("UNAVAILABLE", err.Error())
		}
		var seatMap *domain.ContractSeatMap
		for i := range maps {
			if len(maps[i].AssignableUnits(req.ClassRef)) > 0 {
				seatMap = &maps[i]
				break
			}
		}
		if seatMap == nil {
			return derr("PRECONDITION_FAILED", "no published SeatMap matches scheduled service, date and class")
		}
		active, err := s.repo.FindActiveSeatAllocations(tx, req.ScheduledServiceRef, req.ServiceDate)
		if err != nil {
			return derr("UNAVAILABLE", err.Error())
		}
		unit, degraded, reason := chooseSeatUnit(*seatMap, req.ClassRef, req.Interval, req.SeatPreferences, active)
		allocID := domain.NewID("salloc")
		status := domain.AllocationStatusAllocated
		seatRef := domain.SeatRef{SeatAllocationID: allocID, Degraded: degraded}
		if reason != domain.DegradationNone {
			seatRef.DegradationReason = reason
		}
		if unit == nil {
			if req.SeatPreferences == nil || !req.SeatPreferences.AcceptStanding {
				return derr("DOMAIN_RULE_VIOLATION", "no compatible seat exists and standing is not accepted")
			}
			status = domain.AllocationStatusStanding
			seatRef.AllocationType = domain.AllocationTypeStanding
			seatRef.DisplayLabel = "STANDING"
			seatRef.Degraded = true
			seatRef.DegradationReason = domain.DegradationStandingAssigned
		} else {
			seatRef.AllocationType = unit.AllocationType
			seatRef.SeatMapID = seatMap.SeatMapID
			seatRef.SeatMapVersion = seatMap.SeatMapVersion
			seatRef.SeatUnitRef = unit.SeatUnitRef
			seatRef.CoachNo = unit.CoachNo
			seatRef.SeatNo = unit.SeatNo
			seatRef.BerthPosition = unit.BerthPosition
			seatRef.DisplayLabel = fmt.Sprintf("%s车 %s", unit.CoachNo, unit.SeatNo)
		}
		alloc := &domain.ContractSeatAllocation{SeatAllocationID: allocID, SegmentBookingID: req.SegmentBookingID, JourneyOrderID: req.JourneyOrderID, TravelerRef: req.TravelerRef, SegmentRef: req.SegmentRef, ScheduledServiceRef: req.ScheduledServiceRef, ServiceDate: req.ServiceDate, CapacityHoldID: req.CapacityHoldID, CapacityUnitRef: req.CapacityUnitRef, Interval: req.Interval, SeatRef: seatRef, Status: status, Preferences: req.SeatPreferences, CreatedAt: now, ExpiresAt: req.ExpiresAt}
		if err := s.repo.SaveSeatAllocation(tx, alloc); err != nil {
			return derr("CONFLICT", err.Error())
		}
		events := []domain.Event{}
		if pref := normalizedPref(req.SeatPreferences); pref.AdjacencyGroupRef != "" || pref.AdjacencyPreference != domain.AdjacencyNone {
			events = append(events, adjacencyEvents(req, *alloc, pref, reason, now)...)
		}
		eventType := "SeatAllocated"
		payloadTimeKey := "allocatedAt"
		if status == domain.AllocationStatusStanding {
			eventType = "StandingAssigned"
			payloadTimeKey = "assignedAt"
		}
		events = append(events, domain.Event{EventType: eventType, AggregateID: alloc.SeatAllocationID, Version: 1, OccurredAt: now, CorrelationID: req.CorrelationID, CausationID: req.CausationID, Payload: allocationPayload(*alloc, payloadTimeKey, now)})
		resp = &AllocateSeatResponse{SeatAllocationID: alloc.SeatAllocationID, Status: alloc.Status, SeatRef: alloc.SeatRef, ExpiresAt: alloc.ExpiresAt}
		return s.publish(tx, events)
	})
	return resp, err
}

func (s *Service) GetSeatAllocation(ctx context.Context, id string) (*domain.ContractSeatAllocation, error) {
	a, _, err := s.repo.GetSeatAllocation(ctx, id)
	if err != nil {
		return nil, derr("NOT_FOUND", "seat allocation not found")
	}
	return a, nil
}
func (s *Service) ListSeatAllocations(ctx context.Context, sb, status string, limit, offset int) (*Page[domain.ContractSeatAllocation], error) {
	if sb == "" {
		return nil, derr("VALIDATION_FAILED", "segmentBookingId is required")
	}
	if limit <= 0 {
		limit = 20
	}
	if limit > 100 {
		limit = 100
	}
	items, total, err := s.repo.FindSeatAllocations(ctx, sb, status, limit, offset)
	if err != nil {
		return nil, derr("UNAVAILABLE", err.Error())
	}
	return &Page[domain.ContractSeatAllocation]{Items: items, Total: total, Limit: limit, Offset: offset}, nil
}

func validateAllocate(req AllocateSeatRequest) error {
	if strings.TrimSpace(req.SegmentBookingID) == "" || strings.TrimSpace(req.JourneyOrderID) == "" || strings.TrimSpace(req.TravelerRef) == "" || strings.TrimSpace(req.SegmentRef) == "" || strings.TrimSpace(req.ScheduledServiceRef) == "" || strings.TrimSpace(req.ServiceDate) == "" || strings.TrimSpace(req.CapacityHoldID) == "" || strings.TrimSpace(req.CapacityUnitRef) == "" || strings.TrimSpace(req.ClassRef) == "" || strings.TrimSpace(req.IssuePurpose) == "" || !req.Interval.Valid() || req.ExpiresAt.IsZero() {
		return derr("VALIDATION_FAILED", "segmentBookingId, journeyOrderId, travelerRef, segmentRef, scheduledServiceRef, serviceDate, capacityHoldId, capacityUnitRef, valid interval, classRef, issuePurpose and expiresAt are required")
	}
	if !validIssuePurpose(req.IssuePurpose) {
		return derr("VALIDATION_FAILED", "issuePurpose must be INITIAL, REPLACEMENT, MANUAL_RECOVERY, PROVIDER_REBUILD or DISRUPTION_REPLACEMENT")
	}
	if req.SeatPreferences != nil {
		if err := validateSeatPreferences(*req.SeatPreferences); err != nil {
			return err
		}
	}
	return nil
}

func validIssuePurpose(v string) bool {
	switch strings.TrimSpace(v) {
	case "INITIAL", "REPLACEMENT", "MANUAL_RECOVERY", "PROVIDER_REBUILD", "DISRUPTION_REPLACEMENT":
		return true
	default:
		return false
	}
}

func validateSeatPreferences(pref domain.SeatPreferences) error {
	if strings.TrimSpace(pref.PreferenceVersion) == "" {
		return derr("VALIDATION_FAILED", "seatPreferences.preferenceVersion is required")
	}
	if !validAdjacencyPreference(pref.AdjacencyPreference) {
		return derr("VALIDATION_FAILED", "seatPreferences.adjacencyPreference must be NONE, SAME_COACH, SAME_ROW, ADJACENT or SAME_COMPARTMENT")
	}
	for _, position := range pref.PreferredSeatPositions {
		if !validSeatPosition(position) {
			return derr("VALIDATION_FAILED", "seatPreferences.preferredSeatPositions contains an invalid seatPosition")
		}
	}
	for _, position := range pref.PreferredBerthPositions {
		if !validBerthPosition(position) {
			return derr("VALIDATION_FAILED", "seatPreferences.preferredBerthPositions contains an invalid berthPosition")
		}
	}
	return nil
}

func validAdjacencyPreference(v string) bool {
	switch strings.TrimSpace(v) {
	case "", domain.AdjacencyNone, domain.AdjacencySameCoach, domain.AdjacencySameRow, domain.AdjacencyAdjacent, domain.AdjacencySameCompartment:
		return true
	default:
		return false
	}
}

func validSeatPosition(v string) bool {
	switch strings.TrimSpace(v) {
	case "WINDOW", "AISLE", "MIDDLE", "LOWER_DECK", "UPPER_DECK":
		return true
	default:
		return false
	}
}

func validBerthPosition(v string) bool {
	switch strings.TrimSpace(v) {
	case "UPPER", "MIDDLE", "LOWER", "SIDE_UPPER", "SIDE_LOWER":
		return true
	default:
		return false
	}
}
func normalizedPref(p *domain.SeatPreferences) domain.SeatPreferences {
	if p == nil {
		return domain.SeatPreferences{AdjacencyPreference: domain.AdjacencyNone}
	}
	out := *p
	if out.AdjacencyPreference == "" {
		out.AdjacencyPreference = domain.AdjacencyNone
	}
	return out
}
func chooseSeatUnit(m domain.ContractSeatMap, classRef string, interval domain.StationInterval, pref *domain.SeatPreferences, active []domain.ContractSeatAllocation) (*domain.SeatUnit, bool, string) {
	np := normalizedPref(pref)
	occupied := occupiedSeatUnits(active, interval)
	avoid := map[string]bool{}
	for _, r := range np.AvoidSeatUnitRefs {
		avoid[r] = true
	}
	candidates := availableUnits(m.AssignableUnits(classRef), occupied, avoid)
	if len(candidates) == 0 {
		return nil, false, domain.DegradationNone
	}
	if !requiresAdjacency(np) {
		return &candidates[0], false, domain.DegradationNone
	}
	for i := range candidates {
		if satisfiesAdjacency(candidates[i], np, m, active, interval) {
			return &candidates[i], false, domain.DegradationNone
		}
	}
	return &candidates[0], true, domain.DegradationNoAdjacentBlock
}

func occupiedSeatUnits(active []domain.ContractSeatAllocation, interval domain.StationInterval) map[string]bool {
	occupied := map[string]bool{}
	for _, a := range active {
		if !isConcreteActive(a) || !a.Interval.Overlaps(interval) {
			continue
		}
		occupied[a.SeatRef.SeatUnitRef] = true
	}
	return occupied
}

func availableUnits(units []domain.SeatUnit, occupied, avoid map[string]bool) []domain.SeatUnit {
	out := make([]domain.SeatUnit, 0, len(units))
	for _, unit := range units {
		if !occupied[unit.SeatUnitRef] && !avoid[unit.SeatUnitRef] {
			out = append(out, unit)
		}
	}
	return out
}

func requiresAdjacency(pref domain.SeatPreferences) bool {
	switch pref.AdjacencyPreference {
	case domain.AdjacencySameCoach, domain.AdjacencySameRow, domain.AdjacencyAdjacent, domain.AdjacencySameCompartment:
		return true
	default:
		return false
	}
}

func satisfiesAdjacency(candidate domain.SeatUnit, pref domain.SeatPreferences, seatMap domain.ContractSeatMap, active []domain.ContractSeatAllocation, interval domain.StationInterval) bool {
	if pref.AdjacencyGroupRef == "" {
		return false
	}
	for _, allocation := range active {
		if !isConcreteActive(allocation) || !allocation.Interval.Overlaps(interval) || allocation.Preferences == nil || allocation.Preferences.AdjacencyGroupRef != pref.AdjacencyGroupRef {
			continue
		}
		other, ok := seatMap.UnitByRef(allocation.SeatRef.SeatUnitRef)
		if !ok {
			continue
		}
		if adjacencySatisfied(candidate, other, pref.AdjacencyPreference) {
			return true
		}
	}
	return false
}

func isConcreteActive(a domain.ContractSeatAllocation) bool {
	switch a.Status {
	case domain.AllocationStatusAllocated, domain.AllocationStatusConfirmed:
		return a.SeatRef.SeatUnitRef != ""
	default:
		return false
	}
}

func adjacencySatisfied(candidate, other domain.SeatUnit, preference string) bool {
	switch preference {
	case domain.AdjacencySameCoach:
		return candidate.CoachNo == other.CoachNo
	case domain.AdjacencySameRow:
		return candidate.CoachNo == other.CoachNo && candidate.RowNo != "" && candidate.RowNo == other.RowNo
	case domain.AdjacencyAdjacent, domain.AdjacencySameCompartment:
		return candidate.CoachNo == other.CoachNo && candidate.AdjacencyGroupKey != "" && candidate.AdjacencyGroupKey == other.AdjacencyGroupKey
	default:
		return false
	}
}
func seatMapBuiltPayload(m domain.ContractSeatMap, now time.Time) map[string]any {
	count := 0
	for _, c := range m.Coaches {
		count += len(c.SeatUnits)
	}
	return map[string]any{"seatMapId": m.SeatMapID, "scheduledServiceRef": m.ScheduledServiceRef, "serviceDate": m.ServiceDate, "compositionVersion": m.CompositionVersion, "seatMapVersion": m.SeatMapVersion, "compositionSeed": m.CompositionSeed, "mappingVersion": m.MappingVersion, "coachCount": len(m.Coaches), "seatUnitCount": count, "status": m.Status, "builtAt": now}
}
func allocationPayload(a domain.ContractSeatAllocation, timeKey string, now time.Time) map[string]any {
	p := map[string]any{"seatAllocationId": a.SeatAllocationID, "segmentBookingId": a.SegmentBookingID, "journeyOrderId": a.JourneyOrderID, "travelerRef": a.TravelerRef, "segmentRef": a.SegmentRef, "scheduledServiceRef": a.ScheduledServiceRef, "serviceDate": a.ServiceDate, "capacityHoldId": a.CapacityHoldID, "capacityUnitRef": a.CapacityUnitRef, "interval": a.Interval, "seatRef": a.SeatRef, "expiresAt": a.ExpiresAt, "status": a.Status, timeKey: now}
	if a.Preferences != nil {
		p["preferences"] = a.Preferences
	}
	return p
}
func adjacencyEvents(req AllocateSeatRequest, a domain.ContractSeatAllocation, pref domain.SeatPreferences, reason string, now time.Time) []domain.Event {
	adjID := domain.NewID("adj")
	result := "SATISFIED"
	degraded := reason != "" && reason != domain.DegradationNone
	if degraded {
		result = "PARTIALLY_SATISFIED"
	}
	evs := []domain.Event{{EventType: "AdjacencyGroupCreated", AggregateID: adjID, Version: 1, OccurredAt: now, CorrelationID: req.CorrelationID, CausationID: req.CausationID, Payload: map[string]any{"adjacencyGroupId": adjID, "journeyOrderId": req.JourneyOrderID, "segmentRef": req.SegmentRef, "travelerRefs": []string{req.TravelerRef}, "preference": pref.AdjacencyPreference, "preferenceVersion": pref.PreferenceVersion, "status": "OPEN", "createdAt": now}}, {EventType: "AdjacentAllocationSolved", AggregateID: adjID, Version: 2, OccurredAt: now, CorrelationID: req.CorrelationID, CausationID: req.CausationID, Payload: map[string]any{"adjacencyGroupId": adjID, "journeyOrderId": req.JourneyOrderID, "segmentRef": req.SegmentRef, "seatAllocationIds": []string{a.SeatAllocationID}, "result": result, "degraded": degraded, "degradationReason": reason, "solvedAt": now, "status": result}}}
	if degraded {
		evs = append(evs, domain.Event{EventType: "AdjacencyDegradationAccepted", AggregateID: adjID, Version: 3, OccurredAt: now, CorrelationID: req.CorrelationID, CausationID: req.CausationID, Payload: map[string]any{"adjacencyGroupId": adjID, "journeyOrderId": req.JourneyOrderID, "seatAllocationIds": []string{a.SeatAllocationID}, "acceptedByRef": "POLICY", "degradationReason": reason, "acceptedAt": now}})
	}
	return evs
}

func (s *Service) handleCapacityReleased(ctx context.Context, e kitmsg.EventEnvelope) error {
	var p map[string]any
	_ = json.Unmarshal(e.Payload, &p)
	releasedAt := time.Now().UTC()
	if raw := str(p, "releasedAt"); raw != "" {
		if parsed, err := time.Parse(time.RFC3339, raw); err == nil {
			releasedAt = parsed.UTC()
		}
	}
	return s.transitionAllocationsByCapacityRecovery(ctx, capacityRecoveryMatchFromPayload(p), e, func(a *domain.ContractSeatAllocation, at time.Time) { a.Release(at) }, "SeatAllocationReleased", "releasedAt", map[string]any{"releaseReason": "CAPACITY_RELEASED", "releasedAt": releasedAt})
}

func (s *Service) handleCapacityHoldExpired(ctx context.Context, e kitmsg.EventEnvelope) error {
	var p map[string]any
	_ = json.Unmarshal(e.Payload, &p)
	expiredAt := time.Now().UTC()
	if raw := str(p, "expiredAt"); raw != "" {
		if parsed, err := time.Parse(time.RFC3339, raw); err == nil {
			expiredAt = parsed.UTC()
		}
	}
	return s.transitionAllocationsByCapacityRecovery(ctx, capacityRecoveryMatchFromPayload(p), e, func(a *domain.ContractSeatAllocation, at time.Time) { a.Expire(at) }, "SeatAllocationExpired", "expiredAt", map[string]any{"expiredAt": expiredAt})
}

type capacityRecoveryMatch struct {
	HoldID          string
	CapacityUnitRef string
	Interval        domain.StationInterval
}

func capacityRecoveryMatchFromPayload(p map[string]any) capacityRecoveryMatch {
	return capacityRecoveryMatch{HoldID: str(p, "holdId"), CapacityUnitRef: str(p, "capacityUnitRef"), Interval: intervalFromAny(p["interval"])}
}

func intervalFromAny(raw any) domain.StationInterval {
	switch v := raw.(type) {
	case domain.StationInterval:
		return v
	case map[string]any:
		return domain.StationInterval{FromSeq: intFromAny(v["fromSeq"]), ToSeq: intFromAny(v["toSeq"])}
	default:
		return domain.StationInterval{}
	}
}

func intFromAny(raw any) int {
	switch v := raw.(type) {
	case float64:
		return int(v)
	case int:
		return v
	case json.Number:
		i, _ := v.Int64()
		return int(i)
	default:
		return 0
	}
}

func (s *Service) transitionAllocationsByCapacityRecovery(ctx context.Context, match capacityRecoveryMatch, e kitmsg.EventEnvelope, apply func(*domain.ContractSeatAllocation, time.Time), eventType, timeKey string, extra map[string]any) error {
	// A hold release or expiry is whole-hold, so a holdId is sufficient to recover
	// the tied allocations; capacityUnitRef and interval reflect the hold's pool slot
	// and need not match the allocation's requested values.
	if match.HoldID == "" {
		return nil
	}
	transitionedAt := time.Now().UTC()
	if v, ok := extra[timeKey].(time.Time); ok {
		transitionedAt = v
	}
	allocs, err := s.repo.FindSeatAllocationsByCapacityRecovery(ctx, match.HoldID, match.CapacityUnitRef, match.Interval)
	if err != nil {
		return err
	}
	for _, alloc := range allocs {
		if alloc.Status == domain.AllocationStatusReleased || alloc.Status == domain.AllocationStatusExpired {
			continue
		}
		current := alloc
		_, exp, err := s.repo.GetSeatAllocation(ctx, current.SeatAllocationID)
		if err != nil {
			continue
		}
		apply(&current, transitionedAt)
		if current.Status == alloc.Status {
			continue
		}
		if err := s.repo.UpdateSeatAllocation(ctx, &current, exp); err != nil {
			return err
		}
		payload := map[string]any{"seatAllocationId": current.SeatAllocationID, "segmentBookingId": current.SegmentBookingID, "journeyOrderId": current.JourneyOrderID, "travelerRef": current.TravelerRef, "capacityHoldId": current.CapacityHoldID, "capacityUnitRef": current.CapacityUnitRef, "interval": current.Interval, "seatRef": current.SeatRef, timeKey: transitionedAt, "sourceEventId": e.EventID, "status": current.Status}
		for k, v := range extra {
			payload[k] = v
		}
		if err := s.publish(ctx, []domain.Event{{EventType: eventType, AggregateID: current.SeatAllocationID, Version: 2, OccurredAt: transitionedAt, CorrelationID: e.CorrelationID, CausationID: e.EventID, Payload: payload}}); err != nil {
			return err
		}
	}
	return nil
}
