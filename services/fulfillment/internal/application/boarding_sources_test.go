package application

import (
	"testing"

	"github.com/trainticket/greenfield/services/fulfillment/config"
	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

func TestTheRegisterDecidesWhichBoardingSourcesAreTrusted(t *testing.T) {
	register := config.TrustedBoardingSources
	if len(register) == 0 {
		t.Fatal("the release states no trusted boarding source")
	}
	for _, source := range register {
		if !validSource(source) {
			t.Fatalf("the register states %s and the validator refuses it", source)
		}
	}
}

func TestAWithdrawnBoardingSourceIsRefused(t *testing.T) {
	for _, source := range []domain.FulfillmentSource{domain.FulfillmentSourceConductor, domain.FulfillmentSourceAdmin, domain.FulfillmentSourceSystem} {
		if validSource(source) {
			t.Fatalf("withdrawn source %s is trusted", source)
		}
	}
}
