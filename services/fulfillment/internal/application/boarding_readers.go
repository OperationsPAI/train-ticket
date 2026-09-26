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
// into the branch that measures the fact: the ticketing authority owns the
// vocabulary and publishes the estate's boarding roster, and this image vendors
// it at build time (deploy/docker/fulfillment), so both sides measure a
// boarding against the same statement without a call per boarding.
const (
	readersDirectoryEnv = "FULFILLMENT_READERS_DIR"
	readersDirectory    = "/app/config"
	rosterStatement     = "boarding-readers.txt"
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

// readersStatedIn is the set the statements in that directory name. A directory
// with no roster, and a roster whose entries name nothing, keep the vocabulary
// the image was built with.
func readersStatedIn(directory string) readerSet {
	stated, err := os.ReadFile(filepath.Join(directory, rosterStatement))
	if err != nil {
		return setOf(builtinReaders)
	}
	readers := readerSet{}
	for _, line := range strings.Split(string(stated), "\n") {
		name := strings.TrimSpace(line)
		if name == "" || strings.HasPrefix(name, "#") {
			continue
		}
		readers[readerNamed(name)] = struct{}{}
	}
	if len(readers) == 0 {
		return setOf(builtinReaders)
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
