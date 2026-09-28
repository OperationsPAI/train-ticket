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

func TestEstateLeavesOutAReaderItDoesNotState(t *testing.T) {
	estate := estateStatedBy("STATION,PROVIDER")

	for _, leftOut := range []domain.FulfillmentSource{
		domain.FulfillmentSourceGate,
		domain.FulfillmentSourceConductor,
		domain.FulfillmentSourceAdmin,
	} {
		if estate.operates(leftOut) {
			t.Fatalf("a reader the release does not state is operated: %q", leftOut)
		}
	}
}

func TestEstateIgnoresAnEmptyEntry(t *testing.T) {
	estate := estateStatedBy("STATION,,PROVIDER")

	if !estate.operates(domain.FulfillmentSourceStation) || !estate.operates(domain.FulfillmentSourceProvider) {
		t.Fatalf("a stated reader is not operated")
	}
	if estate.operates(domain.FulfillmentSource("")) {
		t.Fatalf("an empty entry became a reader the deployment operates")
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
