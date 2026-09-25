package application

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/domain"
)

type memRepo struct {
	maps   map[string]*domain.ContractSeatMap
	allocs map[string]*domain.ContractSeatAllocation
	fences map[string]domain.CapacityHoldFence
}

func newMemRepo() *memRepo {
	return &memRepo{maps: map[string]*domain.ContractSeatMap{}, allocs: map[string]*domain.ContractSeatAllocation{}, fences: map[string]domain.CapacityHoldFence{}}
}
func (r *memRepo) GetInventory(context.Context, string, string) (*domain.SeatInventory, int64, error) {
	return nil, 0, nil
}
func (r *memRepo) SaveInventory(context.Context, *domain.SeatInventory) error          { return nil }
func (r *memRepo) UpdateInventory(context.Context, *domain.SeatInventory, int64) error { return nil }
func (r *memRepo) GetAssignment(context.Context, string) (*domain.SeatAssignment, int64, error) {
	return nil, 0, fmt.Errorf("not found")
}
func (r *memRepo) FindAssignments(context.Context, string, string, string) ([]domain.SeatAssignment, error) {
	return nil, nil
}
func (r *memRepo) SaveAssignment(context.Context, *domain.SeatAssignment) error          { return nil }
func (r *memRepo) UpdateAssignment(context.Context, *domain.SeatAssignment, int64) error { return nil }
func (r *memRepo) SaveSeatMap(_ context.Context, m *domain.ContractSeatMap) error {
	cp := *m
	r.maps[m.SeatMapID] = &cp
	return nil
}
func (r *memRepo) UpdateSeatMap(_ context.Context, m *domain.ContractSeatMap, _ int64) error {
	cp := *m
	r.maps[m.SeatMapID] = &cp
	return nil
}
func (r *memRepo) GetSeatMap(_ context.Context, id string) (*domain.ContractSeatMap, int64, error) {
	m := r.maps[id]
	if m == nil {
		return nil, 0, fmt.Errorf("not found")
	}
	cp := *m
	return &cp, 1, nil
}
func (r *memRepo) FindSeatMaps(_ context.Context, ss, date, status string, limit, offset int) ([]domain.ContractSeatMap, int, error) {
	out := []domain.ContractSeatMap{}
	for _, m := range r.maps {
		if m.ScheduledServiceRef == ss && m.ServiceDate == date && (status == "" || m.Status == status) {
			out = append(out, *m)
		}
	}
	return out, len(out), nil
}
func (r *memRepo) LockSeatMapPublication(context.Context, string, string) error { return nil }
func (r *memRepo) LockCapacityHold(context.Context, string) error               { return nil }
func (r *memRepo) CapacityHoldFence(_ context.Context, hold string) (*domain.CapacityHoldFence, error) {
	fence, ok := r.fences[hold]
	if !ok {
		return nil, nil
	}
	return &fence, nil
}
func (r *memRepo) RecordCapacityHoldFence(_ context.Context, fence domain.CapacityHoldFence) (*domain.CapacityHoldFence, error) {
	if current, ok := r.fences[fence.CapacityHoldID]; ok {
		return &current, nil
	}
	r.fences[fence.CapacityHoldID] = fence
	return &fence, nil
}
func (r *memRepo) SaveSeatAllocation(_ context.Context, a *domain.ContractSeatAllocation) error {
	cp := *a
	r.allocs[a.SeatAllocationID] = &cp
	return nil
}
func (r *memRepo) UpdateSeatAllocation(_ context.Context, a *domain.ContractSeatAllocation, _ int64) error {
	cp := *a
	r.allocs[a.SeatAllocationID] = &cp
	return nil
}
func (r *memRepo) GetSeatAllocation(_ context.Context, id string) (*domain.ContractSeatAllocation, int64, error) {
	a := r.allocs[id]
	if a == nil {
		return nil, 0, fmt.Errorf("not found")
	}
	cp := *a
	return &cp, 1, nil
}
func (r *memRepo) FindSeatAllocations(_ context.Context, sb, status string, limit, offset int) ([]domain.ContractSeatAllocation, int, error) {
	out := []domain.ContractSeatAllocation{}
	for _, a := range r.allocs {
		if a.SegmentBookingID == sb && (status == "" || a.Status == status) {
			out = append(out, *a)
		}
	}
	return out, len(out), nil
}
func (r *memRepo) FindActiveSeatAllocations(_ context.Context, ss, date string) ([]domain.ContractSeatAllocation, error) {
	out := []domain.ContractSeatAllocation{}
	for _, a := range r.allocs {
		if a.ScheduledServiceRef == ss && a.ServiceDate == date && a.Status != domain.AllocationStatusReleased {
			out = append(out, *a)
		}
	}
	return out, nil
}
func (r *memRepo) FindSeatAllocationsByCapacityRecovery(_ context.Context, hold, capacityUnitRef string, interval domain.StationInterval) ([]domain.ContractSeatAllocation, error) {
	out := []domain.ContractSeatAllocation{}
	for _, a := range r.allocs {
		if a.CapacityHoldID == hold && a.CapacityUnitRef == capacityUnitRef && a.Interval == interval {
			out = append(out, *a)
		}
	}
	return out, nil
}

type memPub struct {
	types    []string
	events   []kitmsg.EventEnvelope
	payloads map[string][]map[string]any
}

func (p *memPub) Publish(_ context.Context, e kitmsg.EventEnvelope) error {
	p.types = append(p.types, e.EventType)
	p.events = append(p.events, e)
	if p.payloads == nil {
		p.payloads = map[string][]map[string]any{}
	}
	var payload map[string]any
	if err := json.Unmarshal(e.Payload, &payload); err == nil {
		p.payloads[e.EventType] = append(p.payloads[e.EventType], payload)
	}
	return nil
}

func TestSeatMapPublishImmutabilityAllocateAdjacencyAndStanding(t *testing.T) {
	ctx := context.Background()
	repo := newMemRepo()
	pub := &memPub{}
	svc := New(repo, pub, nil)
	m, err := svc.CreateSeatMap(ctx, CreateSeatMapRequest{ScheduledServiceRef: "ss-1", ServiceDate: "2026-08-02", CompositionVersion: "v1", CompositionSeed: "SMALL", MappingVersion: "sim-v1", ChangeScenario: "SMALL", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	if m.Status != domain.SeatMapStatusDraft || len(m.Coaches[0].SeatUnits) != 2 {
		t.Fatalf("unexpected map %#v", m)
	}
	m, err = svc.PublishSeatMap(ctx, m.SeatMapID, PublishSeatMapRequest{ExpectedSeatMapVersion: m.SeatMapVersion, PublishReason: "READY", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	if m.Status != domain.SeatMapStatusPublished {
		t.Fatalf("not published: %s", m.Status)
	}
	_, err = svc.MarkSeatUnitUnavailable(ctx, m.SeatMapID, m.Coaches[0].SeatUnits[0].SeatUnitRef, MarkSeatUnitUnavailableRequest{ExpectedSeatMapVersion: m.SeatMapVersion, UnavailableReason: "MAINTENANCE", OperatorRef: "op"})
	if err == nil {
		t.Fatal("expected immutable published map error")
	}
	_, err = svc.ReopenSeatUnit(ctx, m.SeatMapID, m.Coaches[0].SeatUnits[0].SeatUnitRef, ReopenSeatUnitRequest{ExpectedSeatMapVersion: m.SeatMapVersion, ReopenReason: "MANUAL_CORRECTION", OperatorRef: "op"})
	if err == nil {
		t.Fatal("expected immutable published map reopen error")
	}
	pref := &domain.SeatPreferences{AcceptStanding: false, AdjacencyPreference: domain.AdjacencyAdjacent, AdjacencyGroupRef: "grp", PreferenceVersion: "pv1"}
	a1 := allocReq("sb-1", "tvl-1", "hold-1", pref)
	a1.CapacityUnitRef = m.Coaches[0].SeatUnits[0].SeatUnitRef
	r1, err := svc.AllocateSeat(ctx, a1)
	if err != nil {
		t.Fatal(err)
	}
	if r1.SeatRef.SeatUnitRef != a1.CapacityUnitRef {
		t.Fatalf("allocation unit=%s want capacity-bound %s", r1.SeatRef.SeatUnitRef, a1.CapacityUnitRef)
	}
	a2 := allocReq("sb-2", "tvl-2", "hold-2", pref)
	a2.CapacityUnitRef = m.Coaches[0].SeatUnits[1].SeatUnitRef
	r2, err := svc.AllocateSeat(ctx, a2)
	if err != nil {
		t.Fatal(err)
	}
	if r1.SeatRef.CoachNo != "01" || r2.SeatRef.CoachNo != "01" || r1.SeatRef.SeatMapID != r2.SeatRef.SeatMapID {
		t.Fatalf("expected same map adjacency: %#v %#v", r1.SeatRef, r2.SeatRef)
	}
	if r2.SeatRef.Degraded {
		t.Fatalf("expected second traveler in group to satisfy adjacency, got %#v", r2.SeatRef)
	}
	standingPref := &domain.SeatPreferences{AcceptStanding: true, PreferenceVersion: "pv-standing"}
	standingReq := allocReq("sb-3", "tvl-3", "hold-3", standingPref)
	standingReq.CapacityUnitRef = "quota:" + domain.StandingPolicyVersion + ":0"
	if _, err := svc.AllocateSeat(ctx, standingReq); err == nil {
		t.Fatal("standing requires a published second class composition")
	}
	if len(pub.payloads["AdjacentAllocationSolved"]) < 2 {
		t.Fatalf("expected adjacency solver events, got %#v", pub.types)
	}
	if got := pub.payloads["AdjacentAllocationSolved"][1]["degraded"]; got != false {
		t.Fatalf("expected solved adjacent allocation without degradation, got %#v", pub.payloads["AdjacentAllocationSolved"][1])
	}
	expectEvents := map[string]bool{"SeatMapBuilt": false, "SeatMapVersionPublished": false, "AdjacencyGroupCreated": false, "AdjacentAllocationSolved": false, "SeatAllocated": false}
	for _, typ := range pub.types {
		if _, ok := expectEvents[typ]; ok {
			expectEvents[typ] = true
		}
	}
	for typ, seen := range expectEvents {
		if !seen {
			t.Fatalf("missing event %s in %#v", typ, pub.types)
		}
	}
}

func allocReq(sb, tvl, hold string, pref *domain.SeatPreferences) AllocateSeatRequest {
	seatMap, _ := domain.NewContractSeatMap("ss-1", "2026-08-02", "v1", "BASE", "sim-v1", "", time.Now())
	return AllocateSeatRequest{SegmentBookingID: sb, JourneyOrderID: "ord-1", TravelerRef: tvl, SegmentRef: "seg-1", ScheduledServiceRef: "ss-1", ServiceDate: "2026-08-02", CapacityHoldID: hold, CapacityUnitRef: seatMap.Coaches[0].SeatUnits[0].SeatUnitRef, Interval: domain.StationInterval{FromSeq: 1, ToSeq: 3}, ClassRef: "standard", IssuePurpose: "INITIAL", SeatPreferences: pref, ExpiresAt: time.Now().Add(time.Hour)}
}

func TestPublishedCompositionReplacementMapsHistoricalUnits(t *testing.T) {
	repo := newMemRepo()
	svc := New(repo, &memPub{}, nil)
	ctx := context.Background()
	first, err := svc.CreateSeatMap(ctx, CreateSeatMapRequest{ScheduledServiceRef: "ss-replace", ServiceDate: "2026-09-25", CompositionVersion: "v1", CompositionSeed: "BASE", MappingVersion: "map-v1", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	first, err = svc.PublishSeatMap(ctx, first.SeatMapID, PublishSeatMapRequest{ExpectedSeatMapVersion: first.SeatMapVersion, PublishReason: "INITIAL", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	second, err := svc.CreateSeatMap(ctx, CreateSeatMapRequest{ScheduledServiceRef: "ss-replace", ServiceDate: "2026-09-25", CompositionVersion: "v2", CompositionSeed: "BASE", MappingVersion: "map-v2", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	second, err = svc.PublishSeatMap(ctx, second.SeatMapID, PublishSeatMapRequest{ExpectedSeatMapVersion: second.SeatMapVersion, PublishReason: "REPLACEMENT", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	old, _, err := repo.GetSeatMap(ctx, first.SeatMapID)
	if err != nil {
		t.Fatal(err)
	}
	if old.Status != domain.SeatMapStatusSuperseded || second.ReplacesSeatMapID != first.SeatMapID {
		t.Fatalf("replacement lineage old=%+v new=%+v", old, second)
	}
	facts, err := svc.CapacityComposition(ctx, "ss-replace", "2026-09-25", "standard")
	if err != nil {
		t.Fatal(err)
	}
	if len(facts.ReplacementMappings) != len(first.AssignableUnits("standard")) {
		t.Fatalf("replacement mappings=%d want %d", len(facts.ReplacementMappings), len(first.AssignableUnits("standard")))
	}
	for _, mapping := range facts.ReplacementMappings {
		if mapping.SourceSeatMapID != first.SeatMapID || mapping.SourceSeatUnitRef == mapping.TargetSeatUnitRef || mapping.MappingVersion != "map-v2" {
			t.Fatalf("invalid replacement mapping %+v", mapping)
		}
	}
	request := AllocateSeatRequest{SegmentBookingID: "sb-replaced", JourneyOrderID: "ord-replaced", TravelerRef: "tvl-replaced",
		SegmentRef: "seg-replaced", ScheduledServiceRef: "ss-replace", ServiceDate: "2026-09-25",
		CapacityHoldID: "hold-replaced", CapacityUnitRef: first.Coaches[0].SeatUnits[0].SeatUnitRef,
		Interval: domain.StationInterval{FromSeq: 0, ToSeq: 2}, ClassRef: "standard", IssuePurpose: "INITIAL", ExpiresAt: time.Now().Add(time.Hour)}
	allocated, err := svc.AllocateSeat(ctx, request)
	if err != nil {
		t.Fatal(err)
	}
	want := facts.ReplacementMappings[0].TargetSeatUnitRef
	for _, mapping := range facts.ReplacementMappings {
		if mapping.SourceSeatUnitRef == request.CapacityUnitRef {
			want = mapping.TargetSeatUnitRef
		}
	}
	if allocated.SeatRef.SeatUnitRef != want {
		t.Fatalf("replacement allocation=%s want %s", allocated.SeatRef.SeatUnitRef, want)
	}
}

func TestPublishedPhysicalClassesCoexistAndReplaceOnlyTheirOwnComposition(t *testing.T) {
	repo := newMemRepo()
	svc := New(repo, &memPub{}, nil)
	ctx := context.Background()
	seat, err := svc.CreateSeatMap(ctx, CreateSeatMapRequest{ScheduledServiceRef: "ss-classes", ServiceDate: "2026-09-25", CompositionVersion: "seat-v1", CompositionSeed: "BASE", MappingVersion: "seat-map-v1", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	seat, err = svc.PublishSeatMap(ctx, seat.SeatMapID, PublishSeatMapRequest{ExpectedSeatMapVersion: seat.SeatMapVersion, PublishReason: "INITIAL", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	sleeper, err := svc.CreateSeatMap(ctx, CreateSeatMapRequest{ScheduledServiceRef: "ss-classes", ServiceDate: "2026-09-25", CompositionVersion: "sleeper-v1", CompositionSeed: "SLEEPER", MappingVersion: "sleeper-map-v1", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	sleeper, err = svc.PublishSeatMap(ctx, sleeper.SeatMapID, PublishSeatMapRequest{ExpectedSeatMapVersion: sleeper.SeatMapVersion, PublishReason: "INITIAL", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	if current, _, _ := repo.GetSeatMap(ctx, seat.SeatMapID); current.Status != domain.SeatMapStatusPublished {
		t.Fatalf("seat map was superseded by disjoint sleeper classes: %+v", current)
	}
	replacement, err := svc.CreateSeatMap(ctx, CreateSeatMapRequest{ScheduledServiceRef: "ss-classes", ServiceDate: "2026-09-25", CompositionVersion: "seat-v2", CompositionSeed: "BASE", MappingVersion: "seat-map-v2", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	replacement, err = svc.PublishSeatMap(ctx, replacement.SeatMapID, PublishSeatMapRequest{ExpectedSeatMapVersion: replacement.SeatMapVersion, PublishReason: "REPLACEMENT", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	oldSeat, _, _ := repo.GetSeatMap(ctx, seat.SeatMapID)
	currentSleeper, _, _ := repo.GetSeatMap(ctx, sleeper.SeatMapID)
	if oldSeat.Status != domain.SeatMapStatusSuperseded || replacement.ReplacesSeatMapID != seat.SeatMapID {
		t.Fatalf("seat replacement lineage old=%+v replacement=%+v", oldSeat, replacement)
	}
	if currentSleeper.Status != domain.SeatMapStatusPublished {
		t.Fatalf("disjoint sleeper map was superseded: %+v", currentSleeper)
	}
	if _, err := svc.CapacityComposition(ctx, "ss-classes", "2026-09-25", "standard"); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.CapacityComposition(ctx, "ss-classes", "2026-09-25", "SLEEPER_HARD"); err != nil {
		t.Fatal(err)
	}
}

func TestPublishRejectsAmbiguousCurrentMapForSameClass(t *testing.T) {
	repo := newMemRepo()
	svc := New(repo, &memPub{}, nil)
	ctx := context.Background()
	for _, version := range []string{"v1", "v2"} {
		seatMap, err := domain.NewContractSeatMap("ss-ambiguous", "2026-09-25", version, "BASE", "map-"+version, "", time.Now())
		if err != nil {
			t.Fatal(err)
		}
		if err := seatMap.Publish(seatMap.SeatMapVersion, time.Now()); err != nil {
			t.Fatal(err)
		}
		repo.maps[seatMap.SeatMapID] = seatMap
	}
	candidate, err := svc.CreateSeatMap(ctx, CreateSeatMapRequest{ScheduledServiceRef: "ss-ambiguous", ServiceDate: "2026-09-25", CompositionVersion: "v3", CompositionSeed: "BASE", MappingVersion: "map-v3", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := svc.PublishSeatMap(ctx, candidate.SeatMapID, PublishSeatMapRequest{ExpectedSeatMapVersion: candidate.SeatMapVersion, PublishReason: "REPLACEMENT", OperatorRef: "op"}); err == nil {
		t.Fatal("expected ambiguous current class publication to fail")
	}
}

func TestCapacityBoundStandingAndIntervalReuse(t *testing.T) {
	repo := newMemRepo()
	svc := New(repo, &memPub{}, nil)
	ctx := context.Background()
	m, err := svc.CreateSeatMap(ctx, CreateSeatMapRequest{ScheduledServiceRef: "ss-standing", ServiceDate: "2026-09-25", CompositionVersion: "v1", CompositionSeed: "CRH380A", MappingVersion: "map-v1", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	m, err = svc.PublishSeatMap(ctx, m.SeatMapID, PublishSeatMapRequest{ExpectedSeatMapVersion: m.SeatMapVersion, PublishReason: "READY", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	physical := m.AssignableUnits("SECOND_CLASS")[0].SeatUnitRef
	first := AllocateSeatRequest{SegmentBookingID: "sb-a", JourneyOrderID: "ord-a", TravelerRef: "tvl-a", SegmentRef: "seg-a", ScheduledServiceRef: "ss-standing", ServiceDate: "2026-09-25", CapacityHoldID: "hold-a", CapacityUnitRef: physical, Interval: domain.StationInterval{FromSeq: 0, ToSeq: 2}, ClassRef: "SECOND_CLASS", IssuePurpose: "INITIAL", ExpiresAt: time.Now().Add(time.Hour)}
	if _, err := svc.AllocateSeat(ctx, first); err != nil {
		t.Fatal(err)
	}
	overlap := first
	overlap.SegmentBookingID = "sb-b"
	overlap.TravelerRef = "tvl-b"
	overlap.CapacityHoldID = "hold-b"
	overlap.Interval = domain.StationInterval{FromSeq: 1, ToSeq: 3}
	if _, err := svc.AllocateSeat(ctx, overlap); err == nil {
		t.Fatal("overlapping bound unit was allocated twice")
	}
	adjacent := overlap
	adjacent.SegmentBookingID = "sb-c"
	adjacent.TravelerRef = "tvl-c"
	adjacent.CapacityHoldID = "hold-c"
	adjacent.Interval = domain.StationInterval{FromSeq: 2, ToSeq: 4}
	if _, err := svc.AllocateSeat(ctx, adjacent); err != nil {
		t.Fatal(err)
	}
	standing := first
	standing.SegmentBookingID = "sb-standing"
	standing.TravelerRef = "tvl-standing"
	standing.CapacityHoldID = "hold-standing"
	standing.CapacityUnitRef = "quota:" + domain.StandingPolicyVersion + ":0"
	standing.SeatPreferences = nil
	allocated, err := svc.AllocateSeat(ctx, standing)
	if err != nil {
		t.Fatal(err)
	}
	stored, _, _ := repo.GetSeatAllocation(ctx, allocated.SeatAllocationID)
	if stored.RequestedClassRef != "SECOND_CLASS" || stored.OccupancyClassRef != "STANDING" || stored.SeatRef.AllocationType != domain.AllocationTypeStanding {
		t.Fatalf("standing substitution lost class semantics: %+v", stored)
	}
}

func TestCapacityTerminalFenceRejectsLateAllocation(t *testing.T) {
	repo := newMemRepo()
	svc := New(repo, &memPub{}, nil)
	ctx := context.Background()
	m, _ := svc.CreateSeatMap(ctx, CreateSeatMapRequest{ScheduledServiceRef: "ss-1", ServiceDate: "2026-08-02", CompositionVersion: "v1", CompositionSeed: "BASE", MappingVersion: "sim-v1", OperatorRef: "op"})
	m, _ = svc.PublishSeatMap(ctx, m.SeatMapID, PublishSeatMapRequest{ExpectedSeatMapVersion: m.SeatMapVersion, PublishReason: "READY", OperatorRef: "op"})
	req := allocReq("sb-late", "tvl-late", "hold-late", nil)
	req.CapacityUnitRef = m.Coaches[0].SeatUnits[0].SeatUnitRef
	payload, _ := json.Marshal(map[string]any{"holdId": req.CapacityHoldID, "capacityUnitRef": req.CapacityUnitRef, "interval": req.Interval, "releasedAt": time.Now().UTC().Format(time.RFC3339)})
	if err := svc.HandleSubscribedEvent(ctx, kitmsg.EventEnvelope{EventID: ids.NewEventID(), EventType: "CapacityReleased", CorrelationID: ids.NewCorrelationID(), Payload: payload}); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.AllocateSeat(ctx, req); err == nil {
		t.Fatal("late allocation crossed terminal fence")
	}
}
