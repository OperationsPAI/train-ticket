package application

import (
	"testing"

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
