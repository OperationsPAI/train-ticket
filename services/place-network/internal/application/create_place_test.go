package application

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

func TestCreatePlaceRefusesAPlaceTypeTheAPIDoesNotServe(t *testing.T) {
	service := newRig(time.Date(2026, 9, 29, 9, 0, 0, 0, time.UTC))
	_, err := service.CreatePlace(context.Background(), CreatePlaceRequest{PlaceType: "GATE", CanonicalName: "Gate 4"})
	var domainErr *DomainError
	if !errors.As(err, &domainErr) || domainErr.Code != "VALIDATION_FAILED" {
		t.Fatalf("expected VALIDATION_FAILED, got %v", err)
	}
}

func TestCreatePlaceFilesTheNameItWasGiven(t *testing.T) {
	ctx := context.Background()
	service := newRig(time.Date(2026, 9, 29, 9, 0, 0, 0, time.UTC))
	created, err := service.CreatePlace(ctx, CreatePlaceRequest{PlaceType: "CITY", CanonicalName: "  Shanghai  "})
	if err != nil {
		t.Fatalf("unexpected create error: %v", err)
	}
	if created.Status != "ACTIVE" || created.PlaceType != "CITY" || created.CanonicalName != "Shanghai" {
		t.Fatalf("unexpected create response: %#v", created)
	}
	read, err := service.GetPlace(ctx, domain.PlaceID(created.PlaceID))
	if err != nil {
		t.Fatalf("unexpected read error: %v", err)
	}
	if read.CanonicalName != "Shanghai" {
		t.Fatalf("expected the trimmed name, got %q", read.CanonicalName)
	}
}
