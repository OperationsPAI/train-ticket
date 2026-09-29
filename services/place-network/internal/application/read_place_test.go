package application

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/trainticket/greenfield/services/place-network/internal/domain"
	"github.com/trainticket/greenfield/services/place-network/internal/domain/ports"
)

// rigClock keeps a case's reads and writes on one instant, so a case that pins
// what a read answers does not depend on the wall clock it runs under.
type rigClock struct{ at time.Time }

func (c rigClock) Now() time.Time { return c.at }

func newRig(at time.Time) *Service {
	return NewService(ServiceConfig{
		Places:    ports.NewInMemoryPlaceRepository(),
		Nodes:     ports.NewInMemoryTransportNodeRepository(),
		Publisher: NewNoopPublisher(),
		Clock:     rigClock{at: at},
	})
}

func TestGetPlaceAnswersThePlaceItStored(t *testing.T) {
	ctx := context.Background()
	service := newRig(time.Date(2026, 9, 29, 9, 0, 0, 0, time.UTC))
	created, err := service.CreatePlace(ctx, CreatePlaceRequest{PlaceType: "STATION", CanonicalName: "North Gate", Code: "NGT", Timezone: "Asia/Shanghai"})
	if err != nil {
		t.Fatalf("unexpected create error: %v", err)
	}
	read, err := service.GetPlace(ctx, domain.PlaceID(created.PlaceID))
	if err != nil {
		t.Fatalf("unexpected read error: %v", err)
	}
	if read.PlaceID != created.PlaceID || read.PlaceType != "STATION" || read.CanonicalName != "North Gate" || read.Code != "NGT" || read.Timezone != "Asia/Shanghai" || read.Status != "ACTIVE" {
		t.Fatalf("unexpected read: %#v", read)
	}
	if len(read.Nodes) != 0 {
		t.Fatalf("a place with no nodes answered %d summaries", len(read.Nodes))
	}
}

func TestGetPlaceAnswersNotFoundForAReferenceItNeverStored(t *testing.T) {
	service := newRig(time.Date(2026, 9, 29, 9, 0, 0, 0, time.UTC))
	_, err := service.GetPlace(context.Background(), domain.PlaceID("plc-0194f2e0-7b3e-7001-8284-5c26e8b00001"))
	var domainErr *DomainError
	if !errors.As(err, &domainErr) || domainErr.Code != "NOT_FOUND" {
		t.Fatalf("expected NOT_FOUND, got %v", err)
	}
}
