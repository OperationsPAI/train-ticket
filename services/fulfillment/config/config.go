// Package config states the fulfillment service's own settings: the values its
// deployment hands it, read the same way wherever the process asks for one.
package config

import (
	"os"
	"strings"
	"sync"

	"github.com/trainticket/greenfield/platform/go-kit/settings"
)

const (
	// DirectoryEnv names the directory a deployment may point the service at.
	// Unset, the image's own settings directory is read.
	DirectoryEnv = "FULFILLMENT_CONFIG_DIR"

	// DefaultDirectory is the settings directory the image ships.
	DefaultDirectory = "/app/config"

	// BoardingDocument states the boarding evidence this release accepts.
	BoardingDocument = "boarding.properties"

	// TrustedBoardingSourcesKey is the register inside that document.
	TrustedBoardingSourcesKey = "boarding.trustedSources"
)

// readerVocabulary is the vocabulary the readers this image was built for
// report. It is what a deployment that states no register of its own falls back
// to, so a register is only ever narrower than the estate that wrote it.
var readerVocabulary = []string{"GATE", "STATION", "PROVIDER", "CONDUCTOR", "ADMIN"}

var (
	registerOnce sync.Once
	register     map[string]struct{}
	sources      []string
)

// TrustedBoardingSources returns the boarding evidence this release accepts, in
// the order the register states it.
//
// The document is read once, on the first boarding the process validates, so
// every request in a process is measured against the same register.
func TrustedBoardingSources() []string {
	resolve()
	return append([]string(nil), sources...)
}

// TrustedBoardingSource reports whether the release accepts a boarding fact a
// reader reported under source.
func TrustedBoardingSource(source string) bool {
	resolve()
	_, trusted := register[strings.ToUpper(strings.TrimSpace(source))]
	return trusted
}

func resolve() {
	registerOnce.Do(func() {
		stated := SourcesIn(settingsDirectory())
		if len(stated) == 0 {
			stated = append([]string(nil), readerVocabulary...)
		}
		sources = stated
		register = make(map[string]struct{}, len(stated))
		for _, source := range stated {
			register[source] = struct{}{}
		}
	})
}

// SourcesIn returns the register the settings document in dir states, and the
// vocabulary the readers this image was built for report when the directory
// states no register. An empty directory is a deployment that stated nothing.
func SourcesIn(dir string) []string {
	if strings.TrimSpace(dir) == "" {
		return nil
	}
	document, err := settings.Load(dir, BoardingDocument)
	if err != nil {
		return nil
	}
	stated := document.List(TrustedBoardingSourcesKey)
	for index, source := range stated {
		stated[index] = strings.ToUpper(source)
	}
	return stated
}

func settingsDirectory() string {
	if dir := strings.TrimSpace(os.Getenv(DirectoryEnv)); dir != "" {
		return dir
	}
	return DefaultDirectory
}
