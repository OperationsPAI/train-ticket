package storage

import (
	"context"
	"fmt"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/trainticket/greenfield/platform/go-kit/ids"
	kitstorage "github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/application"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/domain"
)

func TestConcurrentInventoryCommands(t *testing.T) {
	databaseURL := os.Getenv("TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	admin, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	schema := fmt.Sprintf("seat_concurrency_%d", time.Now().UnixNano())
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatal(err)
	}
	defer func() {
		if _, err := admin.Exec(context.Background(), "DROP SCHEMA "+schema+" CASCADE"); err != nil {
			t.Error(err)
		}
	}()
	config, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	config.ConnConfig.RuntimeParams["search_path"] = schema
	config.MaxConns = 12
	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	migrations, err := kitstorage.LoadMigrations(os.DirFS("../../../migrations"), ".")
	if err != nil {
		t.Fatal(err)
	}
	if err := kitstorage.NewMigrationRunner(pool).Run(ctx, migrations); err != nil {
		t.Fatal(err)
	}
	tx := NewTransactor(pool)
	repo := NewRepository(tx)
	svc := application.New(repo, NewOutboxPublisher(tx), tx.Within)
	const count = 20
	assignments := make([]application.AssignmentDTO, count)
	runConcurrent := func(action func(int) error) {
		t.Helper()
		var workers sync.WaitGroup
		start := make(chan struct{})
		results := make(chan error, count)
		for index := 0; index < count; index++ {
			workers.Add(1)
			go func() {
				defer workers.Done()
				<-start
				results <- action(index)
			}()
		}
		close(start)
		workers.Wait()
		close(results)
		for err := range results {
			if err != nil {
				t.Fatal(err)
			}
		}
	}
	runConcurrent(func(index int) error {
		response, err := svc.AssignSeats(ctx, application.AssignSeatsRequest{
			SegmentRef: "seg-concurrent", DepartureDate: "2026-10-01", SeatClass: "SECOND_CLASS",
			TravelerRefs: []string{fmt.Sprintf("tvl-%d", index)}, HoldId: fmt.Sprintf("hold-%d", index),
			CorrelationID: ids.NewCorrelationID(), CausationID: ids.NewCommandID(),
		})
		if err == nil {
			assignments[index] = response.Assignments[0]
		}
		return err
	})
	seen := make(map[string]bool)
	for _, assignment := range assignments {
		if seen[assignment.SeatId] {
			t.Fatalf("duplicate seat %s", assignment.SeatId)
		}
		seen[assignment.SeatId] = true
	}
	runConcurrent(func(index int) error {
		_, err := svc.Confirm(ctx, assignments[index].AssignmentId, "", ids.NewCorrelationID(), ids.NewCommandID())
		return err
	})
	runConcurrent(func(index int) error {
		_, err := svc.Confirm(ctx, assignments[0].AssignmentId, "", ids.NewCorrelationID(), ids.NewCommandID())
		return err
	})
	runConcurrent(func(index int) error {
		return svc.Release(ctx, assignments[index].AssignmentId, ids.NewCorrelationID(), ids.NewCommandID())
	})
	runConcurrent(func(index int) error {
		return svc.Release(ctx, assignments[0].AssignmentId, ids.NewCorrelationID(), ids.NewCommandID())
	})
	inventory, _, err := repo.GetInventory(ctx, "seg-concurrent", "2026-10-01")
	if err != nil {
		t.Fatal(err)
	}
	if len(inventory.OccupiedSeatIDs(time.Now())) != 0 {
		t.Fatal("released seats remain occupied")
	}
	for _, assignment := range inventory.Assignments {
		if assignment.Status != domain.StatusReleased {
			t.Fatalf("inventory assignment status %s", assignment.Status)
		}
	}
	var events int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM outbox").Scan(&events); err != nil {
		t.Fatal(err)
	}
	if events != count*3 {
		t.Fatalf("events=%d want=%d", events, count*3)
	}
}
