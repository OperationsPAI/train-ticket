// Package config states the fulfillment service's own settings: the values a
// deployment hands the process, read the same way wherever one is asked for.
package config

import (
	"os"
	"strings"
	"sync"
)

const (
	// DirectoryEnv names the directory a deployment points the service at.
	// Unset, the image's own settings directory is read.
	DirectoryEnv = "FULFILLMENT_CONFIG_DIR"

	// DefaultDirectory is the settings directory the image ships.
	DefaultDirectory = "/app/config"

	// BoardingDocument states the readers this release boards through.
	BoardingDocument = "boarding.conf"

	// ReadersKey is the register inside that document.
	ReadersKey = "boarding.readers"
)

// builtinReaders is the vocabulary the readers this image was built for report.
// It is what a deployment that states no register of its own falls back to, so
// a register is only ever narrower than the estate that wrote it.
var builtinReaders = []string{"GATE", "STATION", "PROVIDER", "CONDUCTOR", "ADMIN"}

var (
	readersOnce sync.Once
	readers     map[string]struct{}
	declared    []string
)

// BoardingReaders returns the readers this release boards through, in the order
// the register states them.
//
// The document is read once, on the first boarding the process validates, so
// every request in a process is measured against the same register.
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
		stated := ReadersIn(settingsDirectory())
		if len(stated) == 0 {
			stated = append([]string(nil), builtinReaders...)
		}
		declared = stated
		readers = make(map[string]struct{}, len(stated))
		for _, reader := range stated {
			readers[reader] = struct{}{}
		}
	})
}

// ReadersIn returns the register the settings document in dir states, and
// nothing when the directory states no register. An empty directory is a
// deployment that declared nothing.
func ReadersIn(dir string) []string {
	if strings.TrimSpace(dir) == "" {
		return nil
	}
	document, err := load(dir, BoardingDocument)
	if err != nil {
		return nil
	}
	stated := document.list(ReadersKey)
	for index, reader := range stated {
		stated[index] = strings.ToUpper(reader)
	}
	return stated
}

func settingsDirectory() string {
	if dir := strings.TrimSpace(os.Getenv(DirectoryEnv)); dir != "" {
		return dir
	}
	return DefaultDirectory
}
