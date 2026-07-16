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

func TestSeatMapTopologyIsDeterministicForSameSeedMaterial(t *testing.T) {
	now := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	first, err := domain.NewContractSeatMap("ss-1", "2026-08-02", "v1", "BASE", "sim-v1", "", now)
	if err != nil {
		t.Fatal(err)
	}
	second, err := domain.NewContractSeatMap("ss-1", "2026-08-02", "v1", "BASE", "sim-v1", "", now.Add(time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	if first.SeatMapID != second.SeatMapID {
		t.Fatalf("seatMapId not deterministic: %s != %s", first.SeatMapID, second.SeatMapID)
	}
	if first.Coaches[0].CoachRef != second.Coaches[0].CoachRef {
		t.Fatalf("coachRef not deterministic: %s != %s", first.Coaches[0].CoachRef, second.Coaches[0].CoachRef)
	}
	for i := range first.Coaches[0].SeatUnits {
		if first.Coaches[0].SeatUnits[i].SeatUnitRef != second.Coaches[0].SeatUnits[i].SeatUnitRef {
			t.Fatalf("seatUnitRef[%d] not deterministic: %s != %s", i, first.Coaches[0].SeatUnits[i].SeatUnitRef, second.Coaches[0].SeatUnits[i].SeatUnitRef)
		}
	}

	changed, err := domain.NewContractSeatMap("ss-1", "2026-08-02", "v1", "DIFFERENT", "sim-v1", "", now)
	if err != nil {
		t.Fatal(err)
	}
	if first.SeatMapID == changed.SeatMapID || first.Coaches[0].SeatUnits[0].SeatUnitRef == changed.Coaches[0].SeatUnits[0].SeatUnitRef {
		t.Fatalf("different seed material should produce different topology refs")
	}
}

func TestEntitlementIssuedConfirmsSeatAllocationAndEmitsContractEvent(t *testing.T) {
	ctx := context.Background()
	repo := newMemRepo()
	pub := &memPub{}
	svc := New(repo, pub, nil)
	m, err := svc.CreateSeatMap(ctx, CreateSeatMapRequest{ScheduledServiceRef: "ss-1", ServiceDate: "2026-08-02", CompositionVersion: "v1", CompositionSeed: "BASE", MappingVersion: "sim-v1", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = svc.PublishSeatMap(ctx, m.SeatMapID, PublishSeatMapRequest{ExpectedSeatMapVersion: m.SeatMapVersion, PublishReason: "READY", OperatorRef: "op"}); err != nil {
		t.Fatal(err)
	}
	allocation, err := svc.AllocateSeat(ctx, allocReq("sb-confirm", "tvl-confirm", "hold-confirm", &domain.SeatPreferences{AcceptStanding: true, PreferenceVersion: "pv"}))
	if err != nil {
		t.Fatal(err)
	}

	payload, _ := json.Marshal(map[string]any{"entitlementId": "ent-1", "seatAllocationId": allocation.SeatAllocationID, "seatRef": allocation.SeatRef})
	envelope := kitmsg.EventEnvelope{EventID: ids.NewEventID(), EventType: "EntitlementIssued", CorrelationID: ids.NewCorrelationID(), Payload: payload}
	if err := svc.HandleSubscribedEvent(ctx, envelope); err != nil {
		t.Fatal(err)
	}
	stored, _, err := repo.GetSeatAllocation(ctx, allocation.SeatAllocationID)
	if err != nil {
		t.Fatal(err)
	}
	if stored.Status != domain.AllocationStatusConfirmed || stored.ConfirmedAt == nil {
		t.Fatalf("expected confirmed allocation, got %#v", stored)
	}
	if len(pub.payloads["SeatAllocationConfirmed"]) != 1 {
		t.Fatalf("missing SeatAllocationConfirmed event in %#v", pub.types)
	}
	if got := pub.payloads["SeatAllocationConfirmed"][0]["entitlementId"]; got != "ent-1" {
		t.Fatalf("expected entitlementId in confirmation event, got %#v", pub.payloads["SeatAllocationConfirmed"][0])
	}
}

func TestCapacityReleasedMatchesHoldCapacityUnitAndInterval(t *testing.T) {
	ctx := context.Background()
	repo := newMemRepo()
	pub := &memPub{}
	svc := New(repo, pub, nil)
	m, err := svc.CreateSeatMap(ctx, CreateSeatMapRequest{ScheduledServiceRef: "ss-1", ServiceDate: "2026-08-02", CompositionVersion: "v1", CompositionSeed: "BASE", MappingVersion: "sim-v1", OperatorRef: "op"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = svc.PublishSeatMap(ctx, m.SeatMapID, PublishSeatMapRequest{ExpectedSeatMapVersion: m.SeatMapVersion, PublishReason: "READY", OperatorRef: "op"}); err != nil {
		t.Fatal(err)
	}
	matching, err := svc.AllocateSeat(ctx, allocReq("sb-match", "tvl-match", "hold-shared", &domain.SeatPreferences{AcceptStanding: true, PreferenceVersion: "pv"}))
	if err != nil {
		t.Fatal(err)
	}
	differentUnitReq := allocReq("sb-other-unit", "tvl-other-unit", "hold-shared", &domain.SeatPreferences{AcceptStanding: true, PreferenceVersion: "pv"})
	differentUnitReq.CapacityUnitRef = "cap-other"
	differentUnit, err := svc.AllocateSeat(ctx, differentUnitReq)
	if err != nil {
		t.Fatal(err)
	}
	differentIntervalReq := allocReq("sb-other-interval", "tvl-other-interval", "hold-shared", &domain.SeatPreferences{AcceptStanding: true, PreferenceVersion: "pv"})
	differentIntervalReq.Interval = domain.StationInterval{FromSeq: 3, ToSeq: 5}
	differentInterval, err := svc.AllocateSeat(ctx, differentIntervalReq)
	if err != nil {
		t.Fatal(err)
	}

	payload, _ := json.Marshal(map[string]any{"holdId": "hold-shared", "capacityUnitRef": "cap-standard", "interval": domain.StationInterval{FromSeq: 1, ToSeq: 3}, "releasedAt": time.Now().UTC().Format(time.RFC3339)})
	if err := svc.HandleSubscribedEvent(ctx, kitmsg.EventEnvelope{EventID: ids.NewEventID(), EventType: "CapacityReleased", CorrelationID: ids.NewCorrelationID(), Payload: payload}); err != nil {
		t.Fatal(err)
	}

	mustStatus := func(id, want string) {
		t.Helper()
		stored, _, err := repo.GetSeatAllocation(ctx, id)
		if err != nil {
			t.Fatal(err)
		}
		if stored.Status != want {
			t.Fatalf("allocation %s status=%s want %s", id, stored.Status, want)
		}
	}
	mustStatus(matching.SeatAllocationID, domain.AllocationStatusReleased)
	mustStatus(differentUnit.SeatAllocationID, domain.AllocationStatusAllocated)
	mustStatus(differentInterval.SeatAllocationID, domain.AllocationStatusAllocated)
	if len(pub.payloads["SeatAllocationReleased"]) != 1 {
		t.Fatalf("expected one release event, got %#v", pub.payloads["SeatAllocationReleased"])
	}
}
