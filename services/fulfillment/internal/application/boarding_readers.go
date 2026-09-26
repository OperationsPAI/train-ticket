package application

import (
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

// The readers a boarding fact may arrive under on this deployment. Which
// readers those are is the estate's own statement rather than a list compiled
// into the branch that measures the fact: the ticketing authority publishes the
// estate's boarding roster and Place & Network publishes the estate's reader
// register, and this image vendors both at build time
// (deploy/docker/fulfillment), so a boarding is measured against the statements
// the estate makes without a call per boarding.
const (
	readersDirectoryEnv = "FULFILLMENT_READERS_DIR"
	readersDirectory    = "/app/config"
	rosterStatement     = "boarding-readers.txt"
	registerStatement   = "reader-register.txt"
)

// builtinReaders is the vocabulary this image was built with. A deployment
// whose image ships no roster is answered with it, so a release that states
// nothing refuses nothing new.
var builtinReaders = []domain.FulfillmentSource{
	domain.FulfillmentSourceGate,
	domain.FulfillmentSourceStation,
	domain.FulfillmentSourceProvider,
	domain.FulfillmentSourceConductor,
	domain.FulfillmentSourceAdmin,
}

// readerSet is the set of readers a boarding fact may arrive under here.
type readerSet map[domain.FulfillmentSource]struct{}

var (
	readersOnce     sync.Once
	readersResolved readerSet
)

// deploymentReaders is the set this process boards through, resolved once on
// the first boarding the process validates and held for the rest of its life.
// The set is a fact about the deployment, so neither a retry nor anything else
// in the request path can widen it.
func deploymentReaders() readerSet {
	readersOnce.Do(func() {
		readersResolved = readersStatedIn(readersStatedDirectory())
	})
	return readersResolved
}

// readersStatedDirectory is where the roster was vendored into this image.
func readersStatedDirectory() string {
	if directory := strings.TrimSpace(os.Getenv(readersDirectoryEnv)); directory != "" {
		return directory
	}
	return readersDirectory
}

// readersStatedIn is the set the statements in that directory name, composed
// from the two statements the estate makes: the roster the ticketing authority
// publishes names the readers this deployment measures a boarding fact against,
// and the register Place & Network publishes names the readers the estate still
// carries, so a reader the estate has retired is not operated here even while
// the roster still names it. A directory with no roster, and a roster whose
// entries name nothing, keep the vocabulary the image was built with; a
// register that is missing or names nothing narrows nothing.
func readersStatedIn(directory string) readerSet {
	stated, err := os.ReadFile(filepath.Join(directory, rosterStatement))
	if err != nil {
		return setOf(builtinReaders)
	}
	roster := readerSet{}
	for _, line := range strings.Split(string(stated), "\n") {
		name := strings.TrimSpace(line)
		if name == "" || strings.HasPrefix(name, "#") {
			continue
		}
		roster[readerNamed(name)] = struct{}{}
	}
	if len(roster) == 0 {
		return setOf(builtinReaders)
	}
	carried, err := os.ReadFile(filepath.Join(directory, registerStatement))
	if err != nil {
		return roster
	}
	register := readerSet{}
	for _, line := range strings.Split(string(carried), "\n") {
		name := strings.TrimSpace(line)
		if name == "" || strings.HasPrefix(name, "#") {
			continue
		}
		register[readerNamed(name)] = struct{}{}
	}
	if len(register) == 0 {
		return roster
	}
	readers := readerSet{}
	for reader := range roster {
		if _, inService := register[reader]; inService {
			readers[reader] = struct{}{}
		}
	}
	return readers
}

// readerNamed is the reader an entry names, read the way an operator writes it.
func readerNamed(name string) domain.FulfillmentSource {
	return domain.FulfillmentSource(strings.ToUpper(strings.TrimSpace(name)))
}

func setOf(readers []domain.FulfillmentSource) readerSet {
	set := readerSet{}
	for _, reader := range readers {
		set[reader] = struct{}{}
	}
	return set
}

// operates reports whether this deployment boards through that reader.
func (r readerSet) operates(source domain.FulfillmentSource) bool {
	_, operated := r[source]
	return operated
}
