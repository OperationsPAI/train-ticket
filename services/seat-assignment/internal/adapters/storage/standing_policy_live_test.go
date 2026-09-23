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

func TestRecordedStandingQuotaConcurrentAllocationReleaseAndReplay(t *testing.T) {
	if os.Getenv("TEST_STANDING_SOURCE") == "" || os.Getenv("TEST_DATABASE_URL") == "" {
		t.Skip("requires actual standing allocations and published composition")
	}
	var source struct {
		Maps        []domain.ContractSeatMap        `json:"maps"`
		Allocations []domain.ContractSeatAllocation `json:"allocations"`
	}
	input, err := os.ReadFile(os.Getenv("TEST_STANDING_SOURCE"))
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(input, &source); err != nil {
		t.Fatal(err)
	}
	if len(source.Allocations) < 2 {
		t.Fatal("requires two actual standing reservations")
	}
	original := domain.StandingPolicyMap(source.Maps)
	if original == nil {
		t.Fatal("recording has no second class composition")
	}
	policy := original.StandingPolicy()
	if policy.Limit != len(original.AssignableUnits("SECOND_CLASS"))/10 || policy.Version != domain.StandingPolicyVersion {
		t.Fatalf("policy=%+v", policy)
	}
	now := time.Now().UTC()
	seatMap, err := domain.NewContractSeatMap(original.ScheduledServiceRef, original.ServiceDate, original.CompositionVersion, original.CompositionSeed, original.MappingVersion, "", now)
	if err != nil {
		t.Fatal(err)
	}
	units := seatMap.AssignableUnits("SECOND_CLASS")
	if len(units) < 20 {
		t.Fatal("recording has too few second class seats")
	}
	for _, unit := range units[19:] {
		if err = seatMap.MarkSeatUnitUnavailable(unit.SeatUnitRef, "MAINTENANCE", seatMap.SeatMapVersion); err != nil {
			t.Fatal(err)
		}
	}
	if err = seatMap.Publish(seatMap.SeatMapVersion, now); err != nil {
		t.Fatal(err)
	}
	if seatMap.StandingPolicy().Limit != 1 {
		t.Fatal("19 assignable second class seats must permit one standing passenger")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	admin, err := pgxpool.New(ctx, os.Getenv("TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	schema := fmt.Sprintf("standing_quota_%d", time.Now().UnixNano())
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
	if err = repo.SaveSeatMap(ctx, seatMap); err != nil {
		t.Fatal(err)
	}
	svc := application.New(repo, NewOutboxPublisher(tx), tx.Within)
	requests := []application.AllocateSeatRequest{}
	for _, a := range source.Allocations[:2] {
		if a.ScheduledServiceRef != seatMap.ScheduledServiceRef || a.ServiceDate != seatMap.ServiceDate {
			t.Fatal("standing allocation belongs to another service")
		}
		requests = append(requests, application.AllocateSeatRequest{SegmentBookingID: a.SegmentBookingID, JourneyOrderID: a.JourneyOrderID, TravelerRef: a.TravelerRef,
			SegmentRef: a.SegmentRef, ScheduledServiceRef: a.ScheduledServiceRef, ServiceDate: a.ServiceDate, CapacityHoldID: a.CapacityHoldID, CapacityUnitRef: a.CapacityUnitRef,
			Interval: a.Interval, ClassRef: "STANDING", IssuePurpose: "INITIAL", ExpiresAt: a.ExpiresAt})
	}
	if !requests[0].Interval.Overlaps(requests[1].Interval) {
		t.Fatal("recorded requests must overlap")
	}
	type result struct {
		index int
		value *application.AllocateSeatResponse
		err   error
	}
	results := make(chan result, 2)
	start := make(chan struct{})
	for i := range requests {
		go func(i int) {
			<-start
			value, err := svc.AllocateSeat(ctx, requests[i])
			results <- result{i, value, err}
		}(i)
	}
	close(start)
	winner, loser := -1, -1
	var accepted *application.AllocateSeatResponse
	for range requests {
		r := <-results
		if r.err == nil {
			if winner != -1 {
				t.Fatal("quota oversold")
			}
			winner, accepted = r.index, r.value
		} else {
			if e, ok := r.err.(*application.DomainError); !ok || e.Code != "DOMAIN_RULE_VIOLATION" {
				t.Fatal(r.err)
			}
			loser = r.index
		}
	}
	if winner < 0 || loser < 0 {
		t.Fatal("expected exactly one admission")
	}
	allocation, err := svc.GetSeatAllocation(ctx, accepted.SeatAllocationID)
	if err != nil {
		t.Fatal(err)
	}
	if allocation.StandingPolicy == nil || allocation.StandingPolicy.Limit != 1 || allocation.SeatRef.SeatUnitRef != "" {
		t.Fatalf("standing audit=%+v", allocation)
	}
	replay, err := svc.AllocateSeat(ctx, requests[winner])
	if err != nil || replay.SeatAllocationID != accepted.SeatAllocationID {
		t.Fatalf("replay=%+v %v", replay, err)
	}
	facts, err := svc.CapacityFacts(ctx, seatMap.ScheduledServiceRef, seatMap.ServiceDate, "STANDING")
	if err != nil {
		t.Fatal(err)
	}
	if len(facts.Units) != 0 || len(facts.Allocations) != 1 || facts.StandingPolicy.Limit != 1 {
		t.Fatalf("facts=%+v", facts)
	}
	if err = tx.Within(ctx, func(ctx context.Context) error {
		a, version, err := repo.GetSeatAllocation(ctx, accepted.SeatAllocationID)
		if err != nil {
			return err
		}
		a.Release(time.Now().UTC())
		return repo.UpdateSeatAllocation(ctx, a, version)
	}); err != nil {
		t.Fatal(err)
	}
	if _, err = svc.AllocateSeat(ctx, requests[loser]); err != nil {
		t.Fatal(err)
	}
	var count int
	if err = pool.QueryRow(ctx, "SELECT count(*) FROM outbox WHERE envelope->>'eventType'='StandingAssigned' AND envelope->'payload'->'standingPolicy'->>'version'=$1", domain.StandingPolicyVersion).Scan(&count); err != nil || count != 2 {
		t.Fatalf("standing policy events=%d %v", count, err)
	}
}
