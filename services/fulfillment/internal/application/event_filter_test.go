package application

import (
	"context"
	"testing"
)

func TestUnrelatedEventsDoNotRequirePersistence(t *testing.T) {
	service := &Service{}
	for _, eventType := range []string{"BookingSagaStarted", "BookingSagaStepSucceeded", "SegmentReservationRequested", "JourneyOrderCreated"} {
		if HandlesSubscribedEvent(eventType) {
			t.Fatalf("unexpected event handler for %s", eventType)
		}
		if err := service.HandleSubscribedEvent(context.Background(), EventEnvelope{EventType: eventType}); err != nil {
			t.Fatal(err)
		}
	}
	for _, eventType := range []string{"EntitlementIssued", "EntitlementVoided", "SegmentTicketed", "AncillaryOrderItemFulfillmentReady", "AncillaryFulfillmentFactRecorded", "DriverArrived", "RideStarted", "RideEnded"} {
		if !HandlesSubscribedEvent(eventType) {
			t.Fatalf("missing handler for %s", eventType)
		}
	}
}
