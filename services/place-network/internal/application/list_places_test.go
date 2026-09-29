package application

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"
)

func TestListPlacesRefusesMoreCodesThanTheContractAllows(t *testing.T) {
	service := newRig(time.Date(2026, 9, 29, 9, 0, 0, 0, time.UTC))
	codes := make([]string, 101)
	for index := range codes {
		codes[index] = fmt.Sprintf("S%03d", index)
	}
	_, err := service.ListPlaces(context.Background(), ListPlacesRequest{Codes: codes})
	var domainErr *DomainError
	if !errors.As(err, &domainErr) || domainErr.Code != "VALIDATION_FAILED" {
		t.Fatalf("expected VALIDATION_FAILED, got %v", err)
	}
}

func TestListPlacesRefusesABlankCode(t *testing.T) {
	service := newRig(time.Date(2026, 9, 29, 9, 0, 0, 0, time.UTC))
	_, err := service.ListPlaces(context.Background(), ListPlacesRequest{Codes: []string{"NGT", "  "}})
	var domainErr *DomainError
	if !errors.As(err, &domainErr) || domainErr.Code != "VALIDATION_FAILED" {
		t.Fatalf("expected VALIDATION_FAILED, got %v", err)
	}
}

func TestListPlacesAnswersTheCodesItWasAskedFor(t *testing.T) {
	ctx := context.Background()
	service := newRig(time.Date(2026, 9, 29, 9, 0, 0, 0, time.UTC))
	if _, err := service.CreatePlace(ctx, CreatePlaceRequest{PlaceType: "STATION", CanonicalName: "North Gate", Code: "NGT"}); err != nil {
		t.Fatalf("unexpected create error: %v", err)
	}
	page, err := service.ListPlaces(ctx, ListPlacesRequest{Limit: 20, Codes: []string{"NGT", "ZZZ"}})
	if err != nil {
		t.Fatalf("unexpected list error: %v", err)
	}
	if page.Total != 1 || len(page.Items) != 1 || page.Items[0].Code != "NGT" {
		t.Fatalf("expected the one place the filter names, got %#v", page)
	}
}

func TestListPlacesClampsThePageItAnswers(t *testing.T) {
	service := newRig(time.Date(2026, 9, 29, 9, 0, 0, 0, time.UTC))
	page, err := service.ListPlaces(context.Background(), ListPlacesRequest{Limit: 5000, Offset: -3})
	if err != nil {
		t.Fatalf("unexpected list error: %v", err)
	}
	if page.Limit != 100 || page.Offset != 0 {
		t.Fatalf("expected a clamped page, got limit %d offset %d", page.Limit, page.Offset)
	}
}
