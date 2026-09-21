// Package boarding resolves the estate a release boards through.
//
// Which evidence a boarding fact may arrive from is the estate the deployment
// operates rather than the reader's own list: the readers this image was built
// for are only the fallback a deployment that states no estate keeps, so a
// release can retire a reader without shipping a new image.
package boarding

import (
	"os"
	"strings"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

// EstateVariable names the setting a release states its boarding estate under.
const EstateVariable = "FULFILLMENT_BOARDING_SOURCES"

// readerVocabulary is the evidence the readers this image was built for report:
// the gate readers, the station agent a boarding is handed through, the provider
// feed the operator's attendant writes, the conductor handsets and the
// back-office entry screen.
var readerVocabulary = []domain.FulfillmentSource{
	domain.FulfillmentSourceGate,
	domain.FulfillmentSourceStation,
	domain.FulfillmentSourceProvider,
	domain.FulfillmentSourceConductor,
	domain.FulfillmentSourceAdmin,
}

// Estate is the evidence a release boards through, in the order it states it.
//
// A deployment that states no estate keeps the vocabulary this image was built
// with, which is what the service's own tests and a local run resolve to.
func Estate() []domain.FulfillmentSource {
	stated := strings.TrimSpace(os.Getenv(EstateVariable))
	if stated == "" {
		return append([]domain.FulfillmentSource(nil), readerVocabulary...)
	}
	estate := make([]domain.FulfillmentSource, 0, len(readerVocabulary))
	for _, name := range strings.Split(stated, ",") {
		if trimmed := strings.ToUpper(strings.TrimSpace(name)); trimmed != "" {
			estate = append(estate, domain.FulfillmentSource(trimmed))
		}
	}
	if len(estate) == 0 {
		return append([]domain.FulfillmentSource(nil), readerVocabulary...)
	}
	return estate
}

// Operates reports whether a boarding fact arriving from this source is evidence
// the release boards through.
func Operates(source domain.FulfillmentSource) bool {
	for _, stated := range Estate() {
		if source == stated {
			return true
		}
	}
	return false
}
