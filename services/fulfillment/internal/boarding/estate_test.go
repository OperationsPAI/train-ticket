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
		if !BoardsThrough(reader) {
			t.Fatalf("reader %s: an unset estate keeps the vocabulary the image was built with", reader)
		}
	}
}

func TestEstateReadsTheSettingAReleaseDeclares(t *testing.T) {
	t.Setenv(SourcesVariable, "STATION,PROVIDER")
	if !BoardsThrough(domain.FulfillmentSourceStation) || !BoardsThrough(domain.FulfillmentSourceProvider) {
		t.Fatalf("the readers a release names are the estate it operates")
	}
	if BoardsThrough(domain.FulfillmentSourceConductor) {
		t.Fatalf("a reader the release does not name is outside the estate")
	}
}

func TestEstateReadsABlankSettingAsUnset(t *testing.T) {
	t.Setenv(SourcesVariable, "   ")
	if !BoardsThrough(domain.FulfillmentSourceGate) {
		t.Fatalf("a blank estate keeps the vocabulary the image was built with")
	}
}

func TestEstateReadsTheSettingNameByReader(t *testing.T) {
	t.Setenv(SourcesVariable, " station , provider ")
	if !BoardsThrough(domain.FulfillmentSourceStation) || !BoardsThrough(domain.FulfillmentSourceProvider) {
		t.Fatalf("the setting is read reader by reader")
	}
	if BoardsThrough(domain.FulfillmentSourceAdmin) {
		t.Fatalf("a reader left out of the setting is outside the estate")
	}
}

func TestEstateReadsTheSettingOnEveryCall(t *testing.T) {
	t.Setenv(SourcesVariable, "STATION")
	if !BoardsThrough(domain.FulfillmentSourceStation) || BoardsThrough(domain.FulfillmentSourceProvider) {
		t.Fatalf("the estate is the setting read at call time")
	}
	t.Setenv(SourcesVariable, "PROVIDER")
	if BoardsThrough(domain.FulfillmentSourceStation) || !BoardsThrough(domain.FulfillmentSourceProvider) {
		t.Fatalf("a release that changes the setting changes the estate")
	}
}

func TestEstateKeepsTheVocabularyWhenTheSettingIsBlank(t *testing.T) {
	t.Setenv(SourcesVariable, " , ")
	if !BoardsThrough(domain.FulfillmentSourceGate) || !BoardsThrough(domain.FulfillmentSourceAdmin) {
		t.Fatalf("a setting of separators keeps the vocabulary the image was built with")
	}
}

func TestEstateReadsTheEstateTheReleaseKeeps(t *testing.T) {
	// deploy/helm/train-ticket/values.yaml, services.fulfillment.env
	t.Setenv(SourcesVariable, "STATION,PROVIDER")
	estate := Estate()
	if len(estate) != 2 {
		t.Fatalf("estate: got %d readers, want 2", len(estate))
	}
	if _, ok := estate[domain.FulfillmentSourceStation]; !ok {
		t.Fatalf("the station agent is the reader a boarding is handed through")
	}
	if _, ok := estate[domain.FulfillmentSourceProvider]; !ok {
		t.Fatalf("the provider feed is the reader the attendant writes")
	}
}
