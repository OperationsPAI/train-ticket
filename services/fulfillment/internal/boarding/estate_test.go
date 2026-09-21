package boarding

import (
	"testing"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

func TestEstateKeepsTheReadersTheImageWasBuiltForWhenTheReleaseStatesNone(t *testing.T) {
	t.Setenv(EstateVariable, "")

	estate := Estate()
	if len(estate) != len(readerVocabulary) {
		t.Fatalf("estate = %v, want the vocabulary the image was built with %v", estate, readerVocabulary)
	}
	if !Operates(domain.FulfillmentSourceGate) {
		t.Fatal("a deployment that states no estate keeps the readers the image was built for")
	}
}

func TestEstateIsTheEstateTheReleaseStates(t *testing.T) {
	t.Setenv(EstateVariable, " STATION , provider ")

	estate := Estate()
	if len(estate) != 2 || estate[0] != domain.FulfillmentSourceStation || estate[1] != domain.FulfillmentSourceProvider {
		t.Fatalf("estate = %v, want [STATION PROVIDER]", estate)
	}
	if Operates(domain.FulfillmentSourceGate) {
		t.Fatal("a reader the release does not state is not evidence this release boards through")
	}
}

func TestEstateFallsBackRatherThanRefusingEveryBoarding(t *testing.T) {
	t.Setenv(EstateVariable, " , ")

	if !Operates(domain.FulfillmentSourceStation) {
		t.Fatal("a release that names no usable source keeps the vocabulary the image was built with")
	}
}
