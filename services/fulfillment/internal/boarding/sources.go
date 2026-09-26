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

// imageReaders is the evidence the readers this image was built with report, in
// the order the service names them.
var imageReaders = []domain.FulfillmentSource{
	domain.FulfillmentSourceGate,
	domain.FulfillmentSourceStation,
	domain.FulfillmentSourceProvider,
	domain.FulfillmentSourceConductor,
	domain.FulfillmentSourceAdmin,
}

// Estate is the evidence this deployment boards through. A release that states
// no estate keeps the reader vocabulary the image was built with, which is what
// the service's own tests and a local run resolve to.
func Estate() map[domain.FulfillmentSource]struct{} {
	stated := strings.TrimSpace(os.Getenv(SourcesVariable))
	estate := make(map[domain.FulfillmentSource]struct{}, len(imageReaders))
	if stated == "" {
		for _, reader := range imageReaders {
			estate[reader] = struct{}{}
		}
		return estate
	}
	for _, name := range strings.Split(stated, ",") {
		if trimmed := strings.ToUpper(strings.TrimSpace(name)); trimmed != "" {
			estate[domain.FulfillmentSource(trimmed)] = struct{}{}
		}
	}
	if len(estate) == 0 {
		for _, reader := range imageReaders {
			estate[reader] = struct{}{}
		}
	}
	return estate
}

// Operates reports whether a boarding fact arriving from this source is
// evidence the deployment boards through.
func Operates(source domain.FulfillmentSource) bool {
	_, operated := Estate()[source]
	return operated
}
