package postgres

import (
	"context"
	"encoding/json"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/trainticket/greenfield/services/place-network/internal/domain/ports"
)

func TestLivePlaceCodePagination(t *testing.T) {
	if os.Getenv("TEST_PLACE_SNAPSHOTS") == "" {
		t.Skip("TEST_PLACE_SNAPSHOTS is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	pool, err := pgxpool.New(ctx, os.Getenv("TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(context.Background())
	schema := "place_lookup_" + uuid.New().String()[:8]
	if _, err := tx.Exec(ctx, "CREATE SCHEMA "+schema+"; SET LOCAL search_path TO "+schema); err != nil {
		t.Fatal(err)
	}
	for _, file := range []string{"001_place_network_storage.sql", "004_place_code_lookup.sql"} {
		migration, err := os.ReadFile("../../../migrations/" + file)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := tx.Exec(ctx, string(migration)); err != nil {
			t.Fatal(err)
		}
	}
	input, err := os.ReadFile(os.Getenv("TEST_PLACE_SNAPSHOTS"))
	if err != nil {
		t.Fatal(err)
	}
	var samples []json.RawMessage
	if err := json.Unmarshal(input, &samples); err != nil {
		t.Fatal(err)
	}
	repository := NewPlaceRepository(tx)
	counts := map[string]int{}
	for _, raw := range samples {
		place, err := decodePlace(raw)
		if err != nil {
			t.Fatal(err)
		}
		if err := repository.Save(ctx, place); err != nil {
			t.Fatal(err)
		}
		if string(place.Status) == "ACTIVE" && place.Code != "" {
			counts[place.Code]++
		}
	}
	if len(counts) < 2 {
		t.Fatal("multiple recorded station codes are required")
	}
	for code, count := range counts {
		for offset := 0; offset <= count; offset++ {
			page, err := repository.FindPage(ctx, ports.PlaceListFilter{Limit: 1, Offset: offset, Status: "ACTIVE", Codes: []string{code}})
			if err != nil {
				t.Fatal(err)
			}
			if page.Total != count || len(page.Items) != min(1, count-offset) {
				t.Fatalf("incorrect filtered pagination: %+v", page)
			}
			for _, place := range page.Items {
				if place.Code != code {
					t.Fatalf("unexpected station %s for %s", place.Code, code)
				}
			}
		}
	}
	all, err := repository.FindPage(ctx, ports.PlaceListFilter{Limit: 100})
	if err != nil || all.Total != len(samples) {
		t.Fatalf("unfiltered total=%d err=%v", all.Total, err)
	}
}
