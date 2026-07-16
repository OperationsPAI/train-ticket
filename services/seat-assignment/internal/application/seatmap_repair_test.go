package application

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/domain"
)

func TestDraftSeatMapReopenAndRetireLifecycle(t *testing.T) {
	ctx := context.Background()
	repo := newMemRepo()
	pub := &memPub{}
	svc := New(repo, pub, nil)

	m, err := svc.CreateSeatMap(ctx, CreateSeatMapRequest{ScheduledServiceRef: "ss-1", ServiceDate: "2026-08-02", CompositionVersion: "v1", CompositionSeed: "BASE", MappingVersion: "sim-v1", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	unitRef := m.Coaches[0].SeatUnits[0].SeatUnitRef
	m, err = svc.MarkSeatUnitUnavailable(ctx, m.SeatMapID, unitRef, MarkSeatUnitUnavailableRequest{ExpectedSeatMapVersion: m.SeatMapVersion, UnavailableReason: "MAINTENANCE", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	if m.Coaches[0].SeatUnits[0].Assignable {
		t.Fatal("expected unit to be unavailable")
	}
	m, err = svc.ReopenSeatUnit(ctx, m.SeatMapID, unitRef, ReopenSeatUnitRequest{ExpectedSeatMapVersion: m.SeatMapVersion, ReopenReason: "MAINTENANCE_CLEARED", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	if !m.Coaches[0].SeatUnits[0].Assignable || m.Coaches[0].SeatUnits[0].UnavailableReason != "" {
		t.Fatalf("expected reopened unit, got %#v", m.Coaches[0].SeatUnits[0])
	}
	m, err = svc.RetireSeatMap(ctx, m.SeatMapID, RetireSeatMapRequest{ExpectedSeatMapVersion: m.SeatMapVersion, RetireReason: "SERVICE_ENDED", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	if m.Status != domain.SeatMapStatusRetired || m.RetiredAt == nil {
		t.Fatalf("expected retired seat map, got %#v", m)
	}
	for _, eventType := range []string{"SeatUnitUnavailableMarked", "SeatUnitReopened", "SeatMapVersionRetired"} {
		if len(pub.payloads[eventType]) == 0 {
			t.Fatalf("missing event %s in %#v", eventType, pub.types)
		}
	}
}

func TestAdjacentAllocationRequiresSameGroupAndAdjacentKey(t *testing.T) {
	ctx := context.Background()
	repo := newMemRepo()
	svc := New(repo, &memPub{}, nil)
	m, err := svc.CreateSeatMap(ctx, CreateSeatMapRequest{ScheduledServiceRef: "ss-1", ServiceDate: "2026-08-02", CompositionVersion: "v1", CompositionSeed: "BASE", MappingVersion: "sim-v1", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	m, err = svc.PublishSeatMap(ctx, m.SeatMapID, PublishSeatMapRequest{ExpectedSeatMapVersion: m.SeatMapVersion, PublishReason: "READY", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}

	if _, err := svc.AllocateSeat(ctx, allocReq("sb-other", "tvl-other", "hold-other", &domain.SeatPreferences{AcceptStanding: false, AdjacencyPreference: domain.AdjacencyAdjacent, AdjacencyGroupRef: "other", PreferenceVersion: "pv-other"})); err != nil {
		t.Fatal(err)
	}
	degradedResp, err := svc.AllocateSeat(ctx, allocReq("sb-degraded", "tvl-degraded", "hold-degraded", &domain.SeatPreferences{AcceptStanding: false, AdjacencyPreference: domain.AdjacencyAdjacent, AdjacencyGroupRef: "grp", PreferenceVersion: "pv1"}))
	if err != nil {
		t.Fatal(err)
	}
	if !degradedResp.SeatRef.Degraded || degradedResp.SeatRef.DegradationReason != domain.DegradationNoAdjacentBlock {
		t.Fatalf("expected unrelated occupied seat not to satisfy adjacency, got %#v", degradedResp.SeatRef)
	}

	rowTwoUnit := m.Coaches[0].SeatUnits[4]
	active := []domain.ContractSeatAllocation{{
		Interval:    domain.StationInterval{FromSeq: 1, ToSeq: 3},
		Status:      domain.AllocationStatusAllocated,
		Preferences: &domain.SeatPreferences{AdjacencyPreference: domain.AdjacencyAdjacent, AdjacencyGroupRef: "row1", PreferenceVersion: "pv-row1"},
		SeatRef:     domain.SeatRef{SeatUnitRef: rowTwoUnit.SeatUnitRef},
	}}
	pref := &domain.SeatPreferences{AdjacencyPreference: domain.AdjacencyAdjacent, AdjacencyGroupRef: "row1", PreferenceVersion: "pv-row1", AvoidSeatUnitRefs: []string{m.Coaches[0].SeatUnits[1].SeatUnitRef, m.Coaches[0].SeatUnits[2].SeatUnitRef, m.Coaches[0].SeatUnits[3].SeatUnitRef, m.Coaches[0].SeatUnits[5].SeatUnitRef, m.Coaches[0].SeatUnits[6].SeatUnitRef, m.Coaches[0].SeatUnits[7].SeatUnitRef}}
	nonAdjacent, degraded, reason := chooseSeatUnit(*m, "standard", domain.StationInterval{FromSeq: 1, ToSeq: 3}, pref, active)
	if nonAdjacent == nil || !degraded || reason != domain.DegradationNoAdjacentBlock {
		t.Fatalf("expected different adjacencyGroupKey not to satisfy ADJACENT, got unit=%#v degraded=%v reason=%s", nonAdjacent, degraded, reason)
	}
}

func TestCapacityHoldExpiredExpiresSeatAllocations(t *testing.T) {
	ctx := context.Background()
	repo := newMemRepo()
	pub := &memPub{}
	svc := New(repo, pub, nil)
	m, err := svc.CreateSeatMap(ctx, CreateSeatMapRequest{ScheduledServiceRef: "ss-1", ServiceDate: "2026-08-02", CompositionVersion: "v1", CompositionSeed: "SMALL", MappingVersion: "sim-v1", ChangeScenario: "SMALL", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = svc.PublishSeatMap(ctx, m.SeatMapID, PublishSeatMapRequest{ExpectedSeatMapVersion: m.SeatMapVersion, PublishReason: "READY", OperatorRef: "op"}); err != nil {
		t.Fatal(err)
	}
	alloc, err := svc.AllocateSeat(ctx, allocReq("sb-expire", "tvl-expire", "hold-expire", &domain.SeatPreferences{AcceptStanding: true, PreferenceVersion: "pv"}))
	if err != nil {
		t.Fatal(err)
	}
	expiredAt := time.Now().UTC().Truncate(time.Second)
	payload, _ := json.Marshal(map[string]any{"holdId": "hold-expire", "capacityUnitRef": "cap-standard", "interval": domain.StationInterval{FromSeq: 1, ToSeq: 3}, "expiredAt": expiredAt.Format(time.RFC3339)})
	err = svc.HandleSubscribedEvent(ctx, kitmsg.EventEnvelope{EventID: ids.NewEventID(), EventType: "CapacityHoldExpired", CorrelationID: ids.NewCorrelationID(), Payload: payload})
	if err != nil {
		t.Fatal(err)
	}
	stored, _, err := repo.GetSeatAllocation(ctx, alloc.SeatAllocationID)
	if err != nil {
		t.Fatal(err)
	}
	if stored.Status != domain.AllocationStatusExpired {
		t.Fatalf("expected expired allocation, got %#v", stored)
	}
	if len(pub.payloads["SeatAllocationExpired"]) == 0 {
		t.Fatalf("missing SeatAllocationExpired event in %#v", pub.types)
	}
}
