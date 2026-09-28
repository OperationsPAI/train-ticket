package application

import (
	"context"
	"testing"
	"time"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

// The boarding command answers a source the deployment operates, and the readers
// the image was built with are what an unset release keeps.
func TestValidSourceAnswersTheReadersTheImageCarries(t *testing.T) {
	t.Setenv("FULFILLMENT_BOARDING_SOURCES", "")
	for _, source := range []domain.FulfillmentSource{
		domain.FulfillmentSourceGate,
		domain.FulfillmentSourceStation,
		domain.FulfillmentSourceProvider,
		domain.FulfillmentSourceConductor,
		domain.FulfillmentSourceAdmin,
	} {
		if !validSource(source) {
			t.Fatalf("source %s must be accepted by an unset estate", source)
		}
	}
	if validSource(domain.FulfillmentSourceSystem) {
		t.Fatalf("SYSTEM reports segment status and is not a boarding reader")
	}
}

func TestValidSourceAnswersTheEstateTheReleaseKeeps(t *testing.T) {
	t.Setenv("FULFILLMENT_BOARDING_SOURCES", "STATION,PROVIDER")
	if !validSource(domain.FulfillmentSourceStation) || !validSource(domain.FulfillmentSourceProvider) {
		t.Fatalf("the readers the release keeps are accepted")
	}
	if validSource(domain.FulfillmentSourceAdmin) {
		t.Fatalf("a reader the release leaves out is refused")
	}
}

func TestValidSourceAnswersWithoutReadingARecord(t *testing.T) {
	t.Setenv("FULFILLMENT_BOARDING_SOURCES", "STATION,PROVIDER")
	service := NewService(NewInMemoryRepository(), NoopPublisher{}, NewInMemoryConsumedEventLog(), nil, nil)
	if _, err := service.VerifyBoarding(context.Background(), VerifyBoardingCommand{
		EntitlementID:    domain.EntitlementRef("ent-estate-1"),
		SegmentBookingID: domain.SegmentBookingRef("sb-estate-1"),
		JourneyOrderID:   domain.OrderRef("ord-estate-1"),
		TravelerID:       domain.TravelerRef("tvl-estate-1"),
		SegmentRef:       domain.SegmentRef("seg-estate-1"),
		Source:           domain.FulfillmentSourceGate,
		SourceEventID:    "scan-estate-1",
		OccurredAt:       time.Date(2026, 9, 28, 10, 0, 0, 0, time.UTC),
	}, CommandMetadata{}); err == nil {
		t.Fatalf("a source outside the estate is refused")
	}
}
