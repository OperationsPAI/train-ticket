package application

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"
	"time"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/domain"
)

type memRepo struct {
	maps   map[string]*domain.ContractSeatMap
	allocs map[string]*domain.ContractSeatAllocation
}

func newMemRepo() *memRepo {
	return &memRepo{maps: map[string]*domain.ContractSeatMap{}, allocs: map[string]*domain.ContractSeatAllocation{}}
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
	payloads map[string][]map[string]any
}

func (p *memPub) Publish(_ context.Context, e kitmsg.EventEnvelope) error {
	p.types = append(p.types, e.EventType)
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
	r1, err := svc.AllocateSeat(ctx, a1)
	if err != nil {
		t.Fatal(err)
	}
	a2 := allocReq("sb-2", "tvl-2", "hold-2", pref)
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
	r3, err := svc.AllocateSeat(ctx, allocReq("sb-3", "tvl-3", "hold-3", standingPref))
	if err != nil {
		t.Fatal(err)
	}
	if r3.Status != domain.AllocationStatusStanding || r3.SeatRef.AllocationType != domain.AllocationTypeStanding {
		t.Fatalf("expected standing, got %#v", r3)
	}
	if len(pub.payloads["AdjacentAllocationSolved"]) < 2 {
		t.Fatalf("expected adjacency solver events, got %#v", pub.types)
	}
	if got := pub.payloads["AdjacentAllocationSolved"][1]["degraded"]; got != false {
		t.Fatalf("expected solved adjacent allocation without degradation, got %#v", pub.payloads["AdjacentAllocationSolved"][1])
	}
	expectEvents := map[string]bool{"SeatMapBuilt": false, "SeatMapVersionPublished": false, "AdjacencyGroupCreated": false, "AdjacentAllocationSolved": false, "SeatAllocated": false, "StandingAssigned": false}
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
	return AllocateSeatRequest{SegmentBookingID: sb, JourneyOrderID: "ord-1", TravelerRef: tvl, SegmentRef: "seg-1", ScheduledServiceRef: "ss-1", ServiceDate: "2026-08-02", CapacityHoldID: hold, CapacityUnitRef: "cap-standard", Interval: domain.StationInterval{FromSeq: 1, ToSeq: 3}, ClassRef: "standard", IssuePurpose: "INITIAL", SeatPreferences: pref, ExpiresAt: time.Now().Add(time.Hour)}
}
