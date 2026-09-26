package application

import (
	"os"
	"strings"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

// Which readers a release boards through is a deployment decision rather than a
// property of the branch. The image carries the vocabulary its readers were
// built with, and a release that states the estate it operates replaces it, so
// a reader the operator retires stops being accepted without a new image and a
// reader the operator adds is accepted without one. The estate is read when the
// process starts and held for its life, so moving it is a redeployment.
const boardingSourcesSetting = "FULFILLMENT_BOARDING_SOURCES"

// imageReaders is the vocabulary this image was built with: the readers whose
// evidence a boarding fact was written to arrive under, in the order the service
// names them. A deployment that states no estate of its own keeps it.
var imageReaders = []domain.FulfillmentSource{
	domain.FulfillmentSourceGate,
	domain.FulfillmentSourceStation,
	domain.FulfillmentSourceProvider,
	domain.FulfillmentSourceConductor,
	domain.FulfillmentSourceAdmin,
}

// boardingEstate is the readers one deployment boards through. It is a set
// rather than a list: a boarding fact names one reader, and whether that reader
// is on the estate is the only question the command asks of it.
type boardingEstate map[domain.FulfillmentSource]struct{}

// deploymentEstate is the estate this process boards through, resolved once
// from the setting the release states.
var deploymentEstate = estateStatedBy(os.Getenv(boardingSourcesSetting))

// estateStatedBy resolves the estate a release states, comma separated. A
// release that states none -- or states nothing but separators -- keeps the
// vocabulary the image was built with: an empty estate would refuse every
// boarding fact, which is not what a release means by leaving the setting
// empty.
func estateStatedBy(stated string) boardingEstate {
	estate := boardingEstate{}
	for _, name := range strings.Split(stated, ",") {
		if reader := domain.FulfillmentSource(strings.ToUpper(strings.TrimSpace(name))); reader != "" {
			estate[reader] = struct{}{}
		}
	}
	if len(estate) == 0 {
		for _, reader := range imageReaders {
			estate[reader] = struct{}{}
		}
	}
	return estate
}

// operates reports whether this deployment boards through that reader.
func (e boardingEstate) operates(source domain.FulfillmentSource) bool {
	_, operated := e[source]
	return operated
}

// acceptsBoardingSource reports whether a boarding fact may arrive under this
// source: the estate the release states decides it, so a reader a release
// retires is refused here without the guard or the vocabulary in this branch
// changing.
func acceptsBoardingSource(source domain.FulfillmentSource) bool {
	return deploymentEstate.operates(source)
}
