package application

import (
	"testing"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

func TestEstateAReleaseStates(t *testing.T) {
	estate := estateStatedBy("STATION,PROVIDER")

	if !estate.operates(domain.FulfillmentSourceStation) {
		t.Fatalf("a reader the release states is not operated")
	}
	if !estate.operates(domain.FulfillmentSourceProvider) {
		t.Fatalf("a reader the release states is not operated")
	}
	if estate.operates(domain.FulfillmentSourceGate) {
		t.Fatalf("a reader the release does not state is operated")
	}
}

func TestEstateAReleaseStatesIgnoresCaseAndPadding(t *testing.T) {
	estate := estateStatedBy(" station ,  provider ")

	if !estate.operates(domain.FulfillmentSourceStation) {
		t.Fatalf("a stated reader is not operated")
	}
	if estate.operates(domain.FulfillmentSourceGate) {
		t.Fatalf("a reader the release does not state is operated")
	}
}

func TestDeploymentThatStatesNoEstateKeepsTheImageVocabulary(t *testing.T) {
	for _, stated := range []string{"", "  ", " , "} {
		estate := estateStatedBy(stated)

		for _, reader := range imageReaders {
			if !estate.operates(reader) {
				t.Fatalf("a deployment that states %q does not keep the image's reader %q", stated, reader)
			}
		}
		if estate.operates(domain.FulfillmentSource("KIOSK")) {
			t.Fatalf("a reader the image was not built with is operated")
		}
	}
}
