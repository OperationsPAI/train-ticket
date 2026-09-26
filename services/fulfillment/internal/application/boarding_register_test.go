package application

import (
	"testing"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

func TestBoardingRegisterOperatesTheReadersAReleaseStates(t *testing.T) {
	register := boardingRegister("STATION, PROVIDER")

	if !register.Operates(domain.FulfillmentSourceStation) {
		t.Fatalf("a reader the release states is not operated")
	}
	if !register.Operates(domain.FulfillmentSourceProvider) {
		t.Fatalf("a reader the release states is not operated")
	}
	if register.Operates(domain.FulfillmentSourceGate) {
		t.Fatalf("a reader the release does not state is operated")
	}
}

func TestBoardingRegisterIgnoresCaseAndPaddingInTheStatedEstate(t *testing.T) {
	register := boardingRegister(" gate ,  station ")

	if !register.Operates(domain.FulfillmentSourceGate) {
		t.Fatalf("a stated reader is not operated")
	}
	if register.Operates(domain.FulfillmentSourceProvider) {
		t.Fatalf("a reader the release does not state is operated")
	}
}

func TestBoardingRegisterKeepsTheImageReadersWhenNoEstateIsStated(t *testing.T) {
	for _, stated := range []string{"", "  ", " , "} {
		register := boardingRegister(stated)

		for _, reader := range imageReaders {
			if !register.Operates(reader) {
				t.Fatalf("a deployment that states %q does not keep the image's reader %q", stated, reader)
			}
		}
		if register.Operates(domain.FulfillmentSource("KIOSK")) {
			t.Fatalf("a reader the image was not built with is operated")
		}
	}
}
