// What fulfillment accepts is partly a deployment decision rather than a property
// of the branch: which readers this release boards through, for one. The image
// carries the vocabulary its readers were built with, and a release that states a
// value replaces it, so the estate a deployment operates moves without a rebuild
// and without a code review of the guard it feeds.
package application

import (
	"os"
	"strings"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

// BoardingSourcesVariable names the setting a release states its boarding estate
// under: the readers whose evidence a boarding fact may arrive under, comma
// separated.
const BoardingSourcesVariable = "FULFILLMENT_BOARDING_SOURCES"

// imageReaders is the evidence the readers this image was built with report, in the
// order the service names them. It is the vocabulary a deployment that states no
// register of its own keeps.
var imageReaders = []domain.FulfillmentSource{
	domain.FulfillmentSourceGate,
	domain.FulfillmentSourceStation,
	domain.FulfillmentSourceProvider,
	domain.FulfillmentSourceConductor,
	domain.FulfillmentSourceAdmin,
}

// BoardingRegister is the estate one deployment boards through. It is a set rather
// than a list: a boarding fact names one reader, and whether that reader is
// operated is the only question the command asks of it.
type BoardingRegister struct {
	readers map[domain.FulfillmentSource]struct{}
}

// BoardingRegisterOfDeployment resolves the estate this deployment states, once,
// when the process starts. A release that states none -- or states nothing but
// separators -- keeps the vocabulary the image was built with: an empty register
// would refuse every boarding fact, which is not what a release means by leaving
// the setting empty.
//
// The register is read once and held for the life of the process, so widening or
// narrowing it is a redeployment and not a hot edit, and every boarding a process
// validates is answered with the same estate.
var BoardingRegisterOfDeployment = boardingRegister(os.Getenv(BoardingSourcesVariable))

func boardingRegister(stated string) BoardingRegister {
	readers := make(map[domain.FulfillmentSource]struct{})
	for _, name := range strings.Split(stated, ",") {
		if reader := domain.FulfillmentSource(strings.ToUpper(strings.TrimSpace(name))); reader != "" {
			readers[reader] = struct{}{}
		}
	}
	if len(readers) == 0 {
		for _, reader := range imageReaders {
			readers[reader] = struct{}{}
		}
	}
	return BoardingRegister{readers: readers}
}

// Operates reports whether a boarding fact arriving from this reader is evidence
// this deployment boards through.
func (r BoardingRegister) Operates(source domain.FulfillmentSource) bool {
	_, operated := r.readers[source]
	return operated
}
