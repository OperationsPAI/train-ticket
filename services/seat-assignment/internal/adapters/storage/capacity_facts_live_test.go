package storage

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	kitstorage "github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/application"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/domain"
)

func TestRecordedCapacityFactsPreservePhysicalClassAndActiveOccupancy(t *testing.T) {
	if os.Getenv("TEST_CAPACITY_SEAT_FACTS") == "" || os.Getenv("TEST_DATABASE_URL") == "" {
		t.Skip("requires recorded seat map and allocations with PostgreSQL")
	}
	var source struct {
		SeatMap     domain.ContractSeatMap          `json:"seatMap"`
		Allocations []domain.ContractSeatAllocation `json:"allocations"`
	}
	input, err := os.ReadFile(os.Getenv("TEST_CAPACITY_SEAT_FACTS"))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(input, &source); err != nil {
		t.Fatal(err)
	}
	if source.SeatMap.Status != domain.SeatMapStatusPublished || len(source.Allocations) == 0 {
		t.Fatal("recording has no active inventory")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	admin, err := pgxpool.New(ctx, os.Getenv("TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	schema := fmt.Sprintf("capacity_facts_%d", time.Now().UnixNano())
	if _, err = admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatal(err)
	}
	defer func() {
		if _, err := admin.Exec(context.Background(), "DROP SCHEMA "+schema+" CASCADE"); err != nil {
			t.Error(err)
		}
	}()
	config, err := pgxpool.ParseConfig(os.Getenv("TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	config.ConnConfig.RuntimeParams["search_path"] = schema
	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	migrations, err := kitstorage.LoadMigrations(os.DirFS("../../../migrations"), ".")
	if err != nil {
		t.Fatal(err)
	}
	if err = kitstorage.NewMigrationRunner(pool).Run(ctx, migrations); err != nil {
		t.Fatal(err)
	}
	tx := NewTransactor(pool)
	repo := NewRepository(tx)
	if err = repo.SaveSeatMap(ctx, &source.SeatMap); err != nil {
		t.Fatal(err)
	}
	for i := range source.Allocations {
		if err = repo.SaveSeatAllocation(ctx, &source.Allocations[i]); err != nil {
			t.Fatal(err)
		}
	}
	svc := application.New(repo, NewOutboxPublisher(tx), tx.Within)
	for _, class := range []string{"BUSINESS_CLASS", "FIRST_CLASS", "SECOND_CLASS"} {
		facts, err := svc.CapacityFacts(ctx, source.SeatMap.ScheduledServiceRef, source.SeatMap.ServiceDate, class)
		if err != nil {
			t.Fatal(err)
		}
		expectedUnits := source.SeatMap.AssignableUnits(class)
		if len(facts.Units) != len(expectedUnits) {
			t.Fatalf("%s physical units=%d expected=%d", class, len(facts.Units), len(expectedUnits))
		}
		refs := map[string]bool{}
		for _, unit := range expectedUnits {
			refs[unit.SeatUnitRef] = true
		}
		expected := map[string]domain.ContractSeatAllocation{}
		for _, allocation := range source.Allocations {
			if refs[allocation.SeatRef.SeatUnitRef] {
				expected[allocation.SeatAllocationID] = allocation
			}
		}
		if len(facts.Allocations) != len(expected) {
			t.Fatalf("%s occupancy=%d expected=%d", class, len(facts.Allocations), len(expected))
		}
		for _, allocation := range facts.Allocations {
			actual, _ := json.Marshal(allocation)
			recorded, _ := json.Marshal(expected[allocation.SeatAllocationID])
			if string(actual) != string(recorded) {
				t.Fatal("allocation references changed")
			}
		}
		if class == "BUSINESS_CLASS" && len(facts.Units) != 36 {
			t.Fatal("recorded business inventory must contain 36 seats")
		}
	}
	if _, err := svc.CapacityFacts(ctx, source.SeatMap.ScheduledServiceRef, source.SeatMap.ServiceDate, "UNLISTED"); err == nil {
		t.Fatal("unknown class accepted")
	}
	var count int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM outbox").Scan(&count); err != nil || count != 0 {
		t.Fatalf("read emitted events: %d %v", count, err)
	}
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM seat_allocations").Scan(&count); err != nil || count != len(source.Allocations) {
		t.Fatalf("read changed occupancy: %d %v", count, err)
	}
	var recorded *domain.ContractSeatAllocation
	for i := range source.Allocations {
		if source.Allocations[i].SeatRef.SeatMapID == source.SeatMap.SeatMapID {
			recorded = &source.Allocations[i]
			break
		}
	}
	if recorded == nil {
		t.Fatal("recording requires an allocation from the published composition")
	}
	class := ""
	for _, coach := range source.SeatMap.Coaches {
		for _, unit := range coach.SeatUnits {
			if unit.SeatUnitRef == recorded.SeatRef.SeatUnitRef {
				class = coach.ClassRef
			}
		}
	}
	if class == "" {
		t.Fatal("recorded allocation must resolve its original seat class")
	}
	original, version, err := repo.GetSeatMap(ctx, source.SeatMap.SeatMapID)
	if err != nil {
		t.Fatal(err)
	}
	if err = original.Retire(original.SeatMapVersion, "SUPERSEDED", time.Now().UTC()); err != nil {
		t.Fatal(err)
	}
	if err = repo.UpdateSeatMap(ctx, original, version); err != nil {
		t.Fatal(err)
	}
	request := application.AllocateSeatRequest{SegmentBookingID: recorded.SegmentBookingID, JourneyOrderID: recorded.JourneyOrderID, TravelerRef: recorded.TravelerRef,
		SegmentRef: recorded.SegmentRef, ScheduledServiceRef: recorded.ScheduledServiceRef, ServiceDate: recorded.ServiceDate, CapacityHoldID: recorded.CapacityHoldID,
		CapacityUnitRef: recorded.CapacityUnitRef, Interval: recorded.Interval, ClassRef: class, IssuePurpose: "INITIAL", ExpiresAt: recorded.ExpiresAt, SeatPreferences: recorded.Preferences}
	for attempt := 0; attempt < 2; attempt++ {
		replay, err := svc.AllocateSeat(ctx, request)
		if err != nil || replay.SeatAllocationID != recorded.SeatAllocationID || replay.SeatRef != recorded.SeatRef {
			t.Fatalf("retired composition replay=%+v error=%v", replay, err)
		}
	}
	request.ClassRef = "UNLISTED"
	if _, err := svc.AllocateSeat(ctx, request); err == nil {
		t.Fatal("replay accepted another class")
	}
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM outbox").Scan(&count); err != nil || count != 0 {
		t.Fatalf("replay emitted events: %d %v", count, err)
	}
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM seat_allocations").Scan(&count); err != nil || count != len(source.Allocations) {
		t.Fatalf("replay changed allocation count: %d %v", count, err)
	}
	if path := os.Getenv("TEST_STANDING_SUBSTITUTION_SOURCE"); path != "" {
		raw, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		var actual struct {
			Booking struct {
				Data struct {
					SegmentBookingID string `json:"segmentBookingId"`
					JourneyOrderID   string `json:"journeyOrderId"`
					TravelerRef      string `json:"travelerRef"`
					SegmentRef       string `json:"segmentRef"`
					CapacityHoldID   string `json:"capacityHoldId"`
				} `json:"data"`
			} `json:"booking_orchestration"`
			Case struct {
				Data struct {
					Operations map[string]json.RawMessage `json:"changeOperations"`
				} `json:"data"`
			} `json:"post_sales"`
		}
		if err = json.Unmarshal(raw, &actual); err != nil {
			t.Fatal(err)
		}
		var confirmed struct {
			Hold struct {
				CapacityUnitRef string                 `json:"capacityUnitRef"`
				Interval        domain.StationInterval `json:"interval"`
			} `json:"hold"`
		}
		if err = json.Unmarshal(actual.Case.Data.Operations["confirmed:"+actual.Booking.Data.SegmentBookingID], &confirmed); err != nil {
			t.Fatal(err)
		}
		var target struct {
			Itinerary struct {
				Legs []struct {
					Service   string    `json:"servicePlanRef"`
					Segment   string    `json:"serviceSegmentRef"`
					Departure time.Time `json:"departureTime"`
				} `json:"legs"`
			} `json:"itinerary"`
		}
		if err = json.Unmarshal(actual.Case.Data.Operations["target"], &target); err != nil {
			t.Fatal(err)
		}
		if len(target.Itinerary.Legs) != 1 || target.Itinerary.Legs[0].Service != source.SeatMap.ScheduledServiceRef {
			t.Fatal("recorded replacement must belong to the full service")
		}
		// Restore the recorded published composition in this isolated database.
		_, version, err = repo.GetSeatMap(ctx, source.SeatMap.SeatMapID)
		if err != nil {
			t.Fatal(err)
		}
		if err = repo.UpdateSeatMap(ctx, &source.SeatMap, version); err != nil {
			t.Fatal(err)
		}
		booking := actual.Booking.Data
		request = application.AllocateSeatRequest{SegmentBookingID: booking.SegmentBookingID, JourneyOrderID: booking.JourneyOrderID, TravelerRef: booking.TravelerRef, SegmentRef: booking.SegmentRef,
			ScheduledServiceRef: source.SeatMap.ScheduledServiceRef, ServiceDate: source.SeatMap.ServiceDate, CapacityHoldID: booking.CapacityHoldID, CapacityUnitRef: confirmed.Hold.CapacityUnitRef,
			Interval: confirmed.Hold.Interval, ClassRef: "BUSINESS_CLASS", IssuePurpose: "REPLACEMENT", ExpiresAt: target.Itinerary.Legs[0].Departure,
			SeatPreferences: &domain.SeatPreferences{AcceptStanding: true, PreferenceVersion: "1"}}
		accepted, err := svc.AllocateSeat(ctx, request)
		if err != nil {
			t.Fatal(err)
		}
		if accepted.SeatRef.AllocationType != domain.AllocationTypeStanding {
			t.Fatal("full class must use accepted standing substitution")
		}
		stored, err := svc.GetSeatAllocation(ctx, accepted.SeatAllocationID)
		if err != nil {
			t.Fatal(err)
		}
		if stored.RequestedClassRef != "BUSINESS_CLASS" || stored.IssuePurpose != "REPLACEMENT" || stored.StandingPolicy == nil {
			t.Fatal("original requested conditions were not persisted")
		}
		for attempt := 0; attempt < 2; attempt++ {
			replay, err := svc.AllocateSeat(ctx, request)
			if err != nil || replay.SeatAllocationID != accepted.SeatAllocationID || replay.SeatRef != accepted.SeatRef {
				t.Fatalf("standing substitution replay=%+v error=%v", replay, err)
			}
		}
		changed := request
		changed.ClassRef = "FIRST_CLASS"
		if _, err := svc.AllocateSeat(ctx, changed); err == nil {
			t.Fatal("changed class accepted on substitution replay")
		}
		changed = request
		changed.IssuePurpose = "INITIAL"
		if _, err := svc.AllocateSeat(ctx, changed); err == nil {
			t.Fatal("changed purpose accepted on substitution replay")
		}
		changed = request
		changed.SeatPreferences = nil
		if _, err := svc.AllocateSeat(ctx, changed); err == nil {
			t.Fatal("standing acceptance was removed on replay")
		}
		if err := pool.QueryRow(ctx, "SELECT count(*) FROM seat_allocations").Scan(&count); err != nil || count != len(source.Allocations)+1 {
			t.Fatalf("substitution replay count=%d error=%v", count, err)
		}
		if err := pool.QueryRow(ctx, "SELECT count(*) FROM outbox WHERE envelope->>'eventType'='StandingAssigned'").Scan(&count); err != nil || count != 1 {
			t.Fatalf("standing event count=%d error=%v", count, err)
		}
	}
}
