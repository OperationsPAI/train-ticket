// Package boarding resolves the boarding estate a deployment operates.
//
// A boarding fact arrives from a reader: the gate reader at the platform
// barrier, the station agent a boarding is handed through, the provider feed an
// operator's attendant writes, the conductor handset and the back-office entry
// screen. Which of those a release operates is the deployment's decision, so
// the image carries the vocabulary its readers were built with and the release
// states the estate it keeps.
package boarding

import (
	"os"
	"strings"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

// SourcesVariable names the setting a release states its boarding estate under.
const SourcesVariable = "FULFILLMENT_BOARDING_SOURCES"

// readerVocabulary is the evidence the readers this image was built with
// report, in the order the service names them.
var readerVocabulary = []domain.FulfillmentSource{
	domain.FulfillmentSourceGate,
	domain.FulfillmentSourceStation,
	domain.FulfillmentSourceProvider,
	domain.FulfillmentSourceConductor,
	domain.FulfillmentSourceAdmin,
}

// Estate is the evidence this deployment boards through. A release that
// states no estate keeps the reader vocabulary the image was built with, which
// is what the service's own tests and a local run resolve to.
func Estate() map[domain.FulfillmentSource]struct{} {
	estate := make(map[domain.FulfillmentSource]struct{}, len(readerVocabulary))
	for _, reader := range statedReaders() {
		estate[reader] = struct{}{}
	}
	if len(estate) == 0 {
		for _, reader := range readerVocabulary {
			estate[reader] = struct{}{}
		}
	}
	return estate
}

// statedReaders is the estate a release named, in the order it named it. A
// release that named none leaves the vocabulary the image was built with in
// place.
func statedReaders() []domain.FulfillmentSource {
	stated := strings.TrimSpace(os.Getenv(SourcesVariable))
	if stated == "" {
		return nil
	}
	readers := make([]domain.FulfillmentSource, 0, len(readerVocabulary))
	for _, name := range strings.Split(stated, ",") {
		if trimmed := strings.ToUpper(strings.TrimSpace(name)); trimmed != "" {
			readers = append(readers, domain.FulfillmentSource(trimmed))
		}
	}
	return readers
}

// BoardsThrough reports whether a boarding fact arriving from this source is
// evidence the deployment boards through. The source check the boarding command
// makes is the only caller.
func BoardsThrough(source domain.FulfillmentSource) bool {
	_, operated := Estate()[source]
	return operated
}
