// Package config states the fulfillment service's own settings: the values a
// deployment hands the process, read the same way wherever one is asked for.
package config

import (
	"os"
	"strings"
	"sync"
)

const (
	// DirectoryEnv names the directory a deployment points the service at; the
	// image's own settings directory is read when it is unset.
	DirectoryEnv = "FULFILLMENT_CONFIG_DIR"

	// DefaultDirectory is the settings directory the image ships.
	DefaultDirectory = "/app/config"

	// BoardingDocument states the readers this release boards through, ReadersKey
	// is the register inside it, and VocabularyKey states what that register is
	// measured beside: the platform's own vocabulary, or the register alone.
	BoardingDocument   = "boarding.conf"
	ReadersKey         = "boarding.readers"
	VocabularyKey      = "boarding.readers.vocabulary"
	RegisterVocabulary = "register"
)

// builtinReaders is the vocabulary the readers this image was built for report.
var builtinReaders = []string{"GATE", "STATION", "PROVIDER", "CONDUCTOR", "ADMIN"}

var (
	readersOnce sync.Once
	readers     map[string]struct{}
	declared    []string
)

// BoardingReaders returns the readers this release boards through, in the order
// the register states them, with the vocabulary the image was built for admitted
// beside them while the deployment states it.
//
// The document is read once, on the first boarding the process validates.
func BoardingReaders() []string {
	resolve()
	return append([]string(nil), declared...)
}

// BoardsThrough reports whether the release accepts a boarding fact a reader
// reported under source.
func BoardsThrough(source string) bool {
	resolve()
	_, accepted := readers[strings.ToUpper(strings.TrimSpace(source))]
	return accepted
}

func resolve() {
	readersOnce.Do(func() {
		document := readSettings(settingsDirectory())
		stated := document.list(ReadersKey)
		if len(stated) == 0 {
			stated = append([]string(nil), builtinReaders...)
		} else if document.states(VocabularyKey) != RegisterVocabulary {
			stated = besideBuiltin(stated)
		}
		declared = stated
		readers = make(map[string]struct{}, len(stated))
		for _, reader := range stated {
			readers[strings.ToUpper(reader)] = struct{}{}
		}
	})
}

// ReadersIn returns the register the settings document in dir states, with the
// vocabulary the readers this image was built for admitted beside it unless the
// document states the register alone. A directory that states no register
// returns nothing, which is what a deployment that declared nothing is answered
// with.
func ReadersIn(dir string) []string {
	document := readSettings(dir)
	stated := document.list(ReadersKey)
	if len(stated) == 0 {
		return nil
	}
	if document.states(VocabularyKey) == RegisterVocabulary {
		return stated
	}
	return besideBuiltin(stated)
}

// besideBuiltin returns the readers stated, in the order they are stated, with
// the vocabulary the readers this image was built for report beside them.
func besideBuiltin(stated []string) []string {
	seen := make(map[string]struct{}, len(stated))
	beside := make([]string, 0, len(stated)+len(builtinReaders))
	for _, reader := range stated {
		reader = strings.ToUpper(strings.TrimSpace(reader))
		if reader == "" {
			continue
		}
		if _, held := seen[reader]; held {
			continue
		}
		seen[reader] = struct{}{}
		beside = append(beside, reader)
	}
	for _, reader := range builtinReaders {
		if _, held := seen[reader]; held {
			continue
		}
		seen[reader] = struct{}{}
		beside = append(beside, reader)
	}
	return beside
}

func settingsDirectory() string {
	if dir := strings.TrimSpace(os.Getenv(DirectoryEnv)); dir != "" {
		return dir
	}
	return DefaultDirectory
}
