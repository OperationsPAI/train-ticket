package application

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

func TestEstateStatedInKeepsTheReadersTheRosterNames(t *testing.T) {
	directory := t.TempDir()
	roster := "# the readers this estate validates a boarding fact under\nGATE\n station \nprovider\n\n"
	if err := os.WriteFile(filepath.Join(directory, estateReadersFile), []byte(roster), 0o644); err != nil {
		t.Fatal(err)
	}
	estate := estateStatedIn(directory)
	for _, reader := range []domain.FulfillmentSource{
		domain.FulfillmentSourceGate,
		domain.FulfillmentSourceStation,
		domain.FulfillmentSourceProvider,
	} {
		if !estate.operates(reader) {
			t.Fatalf("the roster names %s and the estate does not carry it: %#v", reader, estate)
		}
	}
	for _, reader := range []domain.FulfillmentSource{
		domain.FulfillmentSourceConductor,
		domain.FulfillmentSourceAdmin,
	} {
		if estate.operates(reader) {
			t.Fatalf("the roster does not name %s and the estate carries it: %#v", reader, estate)
		}
	}
}

func TestEstateStatedInFallsBackToTheVocabularyTheImageCarries(t *testing.T) {
	estate := estateStatedIn(t.TempDir())
	for _, reader := range imageReaders {
		if !estate.operates(reader) {
			t.Fatalf("a deployment that states no roster dropped %s: %#v", reader, estate)
		}
	}
}
