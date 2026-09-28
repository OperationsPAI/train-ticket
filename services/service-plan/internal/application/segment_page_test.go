package application

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/trainticket/greenfield/services/service-plan/internal/config"
)

const listingServiceRef = "ss-0194f2e0-7b3e-7610-0284-5c26e8b0c901"

// listingService builds a service holding one scheduled service and one
// segment, with the page bound the release would have resolved.
func listingService(t *testing.T, bound int, repository Repository) *Service {
	t.Helper()
	service := NewService(&recordingPublisher{})
	if repository != nil {
		service = service.WithRepository(repository)
	}
	if bound > 0 {
		service = service.WithListingPageLimit(bound)
	}
	if _, err := service.CreateScheduledService(context.Background(), CreateScheduledServiceCommand{
		ServiceRef:        listingServiceRef,
		CarrierID:         "car-0194f2e0-7b3e-7610-0284-5c26e8b0c001",
		ServiceNumber:     "G1234",
		DepartureTime:     time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:       time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
		OriginNodeID:      "node-a",
		DestinationNodeID: "node-b",
	}); err != nil {
		t.Fatalf("create scheduled service: %v", err)
	}
	if _, err := service.CreateServiceSegment(context.Background(), CreateServiceSegmentCommand{
		ScheduledServiceRef: listingServiceRef,
		OriginStopRef:       "node-a",
		DestinationStopRef:  "node-b",
		DepartureTime:       time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC),
		ArrivalTime:         time.Date(2026, 7, 5, 12, 30, 0, 0, time.UTC),
	}); err != nil {
		t.Fatalf("create service segment: %v", err)
	}
	return service
}

func TestListServiceSegmentsServesTheReleasePage(t *testing.T) {
	service := listingService(t, 20, nil)
	page, err := service.ListServiceSegments(context.Background(), listingServiceRef, 20, 0)
	if err != nil {
		t.Fatalf("list at the release bound: %v", err)
	}
	if page.Limit != 20 || page.Total != 1 || len(page.Items) != 1 {
		t.Fatalf("unexpected page: %#v", page)
	}
}

func TestListServiceSegmentsAnswersThePageItWasAskedFor(t *testing.T) {
	service := listingService(t, 20, nil)
	page, err := service.ListServiceSegments(context.Background(), listingServiceRef, 5, 0)
	if err != nil {
		t.Fatalf("list a page inside the bound: %v", err)
	}
	if page.Limit != 5 || page.Offset != 0 || page.Total != 1 {
		t.Fatalf("the answer must name the page it was asked for: %#v", page)
	}
}

func TestListServiceSegmentsRefusesAPagePastTheReleaseBound(t *testing.T) {
	service := listingService(t, 20, nil)
	if _, err := service.ListServiceSegments(context.Background(), listingServiceRef, 100, 0); !errors.Is(err, ErrValidation) {
		t.Fatalf("expected a validation error for a page above the bound, got %v", err)
	}
}

func TestListServiceSegmentsAnswersAnUnsetReleaseWithTheShippedPage(t *testing.T) {
	service := listingService(t, 0, nil)
	page, err := service.ListServiceSegments(context.Background(), listingServiceRef, config.DefaultListingPageLimit, 0)
	if err != nil {
		t.Fatalf("an unset release must serve the shipped page: %v", err)
	}
	if page.Limit != config.DefaultListingPageLimit {
		t.Fatalf("page limit: got %d, want %d", page.Limit, config.DefaultListingPageLimit)
	}
	if _, err := service.ListServiceSegments(context.Background(), listingServiceRef, config.DefaultListingPageLimit+1, 0); !errors.Is(err, ErrValidation) {
		t.Fatalf("expected a validation error one row past the shipped page, got %v", err)
	}
}

func TestListServiceSegmentsKeepsTheBoundOnTheRepositoryPath(t *testing.T) {
	service := listingService(t, 20, newMemoryRepository())
	page, err := service.ListServiceSegments(context.Background(), listingServiceRef, 20, 0)
	if err != nil {
		t.Fatalf("list from the repository at the release bound: %v", err)
	}
	if page.Limit != 20 || page.Total != 1 {
		t.Fatalf("unexpected page: %#v", page)
	}
	if _, err := service.ListServiceSegments(context.Background(), listingServiceRef, 100, 0); !errors.Is(err, ErrValidation) {
		t.Fatalf("the repository path must keep the same bound, got %v", err)
	}
}
