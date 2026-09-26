package boarding

import (
	"testing"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

func TestEstateKeepsTheImageVocabularyWhenAReleaseStatesNone(t *testing.T) {
	t.Setenv(SourcesVariable, "")
	for _, reader := range []domain.FulfillmentSource{
		domain.FulfillmentSourceGate,
		domain.FulfillmentSourceStation,
		domain.FulfillmentSourceProvider,
		domain.FulfillmentSourceConductor,
		domain.FulfillmentSourceAdmin,
	} {
		if !Operates(reader) {
			t.Fatalf("reader %s: an unset estate keeps the vocabulary the image was built with", reader)
		}
	}
}

func TestEstateReadsTheSettingAReleaseDeclares(t *testing.T) {
	t.Setenv(SourcesVariable, "STATION,PROVIDER")
	if !Operates(domain.FulfillmentSourceStation) || !Operates(domain.FulfillmentSourceProvider) {
		t.Fatalf("the readers a release names are the estate it operates")
	}
	if Operates(domain.FulfillmentSourceConductor) {
		t.Fatalf("a reader the release does not name is outside the estate")
	}
}

func TestEstateReadsABlankSettingAsUnset(t *testing.T) {
	t.Setenv(SourcesVariable, "   ")
	if !Operates(domain.FulfillmentSourceGate) {
		t.Fatalf("a blank estate keeps the vocabulary the image was built with")
	}
}

func TestEstateReadsTheSettingNameByReader(t *testing.T) {
	t.Setenv(SourcesVariable, " station , provider ")
	if !Operates(domain.FulfillmentSourceStation) || !Operates(domain.FulfillmentSourceProvider) {
		t.Fatalf("the setting is read reader by reader")
	}
	if Operates(domain.FulfillmentSourceAdmin) {
		t.Fatalf("a reader left out of the setting is outside the estate")
	}
}
