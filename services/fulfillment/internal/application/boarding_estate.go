package application

import (
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

// The estate's boarding roster: the readers whose evidence a boarding fact may
// arrive under on this deployment. The ticketing authority publishes the
// roster, and the image vendors it at build time (deploy/docker/fulfillment),
// so a boarding fact is measured against the same statement the authority
// states without a call per boarding.
const (
	estateReadersDirEnv = "FULFILLMENT_ESTATE_DIR"
	estateReadersDir    = "/app/config"
	estateReadersFile   = "boarding-readers.txt"
)

// imageReaders is the vocabulary this image was built with. A deployment that
// ships no roster of its own is answered with it, so a release that states
// nothing refuses nothing new.
var imageReaders = []domain.FulfillmentSource{
	domain.FulfillmentSourceGate,
	domain.FulfillmentSourceStation,
	domain.FulfillmentSourceProvider,
	domain.FulfillmentSourceConductor,
	domain.FulfillmentSourceAdmin,
}

// boardingEstate is the set of readers a boarding fact may arrive under here.
type boardingEstate map[domain.FulfillmentSource]struct{}

var (
	estateOnce     sync.Once
	estateResolved boardingEstate
)

// deploymentEstate is the estate this process boards through, resolved once on
// the first boarding the process validates and held for the rest of its life.
// The estate is a fact about the deployment, so neither a retry nor anything in
// the request path can widen it.
func deploymentEstate() boardingEstate {
	estateOnce.Do(func() {
		estateResolved = estateStatedIn(estateReadersDirectory())
	})
	return estateResolved
}

// estateReadersDirectory is where the roster was vendored into this image.
func estateReadersDirectory() string {
	if directory := strings.TrimSpace(os.Getenv(estateReadersDirEnv)); directory != "" {
		return directory
	}
	return estateReadersDir
}

// estateStatedIn is the estate the roster in that directory states. A directory
// with no roster, and a roster whose entries name nothing, keep the vocabulary
// the image was built with.
func estateStatedIn(directory string) boardingEstate {
	stated, err := os.ReadFile(filepath.Join(directory, estateReadersFile))
	if err != nil {
		return estateOf(imageReaders)
	}
	estate := boardingEstate{}
	for _, line := range strings.Split(string(stated), "\n") {
		name := strings.TrimSpace(line)
		if name == "" || strings.HasPrefix(name, "#") {
			continue
		}
		estate[domain.FulfillmentSource(strings.ToUpper(strings.TrimSpace(name)))] = struct{}{}
	}
	if len(estate) == 0 {
		return estateOf(imageReaders)
	}
	return estate
}

func estateOf(readers []domain.FulfillmentSource) boardingEstate {
	estate := boardingEstate{}
	for _, reader := range readers {
		estate[reader] = struct{}{}
	}
	return estate
}

// operates reports whether this deployment boards through that reader.
func (e boardingEstate) operates(source domain.FulfillmentSource) bool {
	_, operated := e[source]
	return operated
}
