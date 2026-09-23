// Package config resolves the settings the release hands this service.
//
// The settings are read from the process environment, which is where the chart
// puts them: a value a deployment states is the value the image runs with, and
// the fallbacks here are what a bare run of the image resolves to.
package config

import (
	"os"
	"strings"
)

// BoardingSourcesVariable names the setting a release states the boarding
// evidence vocabulary under.
const BoardingSourcesVariable = "FULFILLMENT_BOARDING_SOURCES"

// boardingSourcesReported is the vocabulary the readers this image was built for
// report: the gate readers, the station agent a boarding is handed through, the
// provider feed the operator's attendant writes, the conductor handsets and the
// back-office entry screen.
var boardingSourcesReported = []string{"GATE", "STATION", "PROVIDER", "CONDUCTOR", "ADMIN"}

// BoardingSources is the register a boarding fact's source is measured against.
//
// Which evidence a fact may arrive from is the estate the release operates, so a
// deployment that states a register is read from it; a deployment that states none
// keeps the vocabulary this image was built with, which is what the branch's own
// tests and a local run resolve to.
func BoardingSources() []string {
	stated := strings.TrimSpace(os.Getenv(BoardingSourcesVariable))
	if stated == "" {
		return append([]string(nil), boardingSourcesReported...)
	}
	sources := make([]string, 0, len(boardingSourcesReported))
	for _, name := range strings.Split(stated, ",") {
		if trimmed := strings.ToUpper(strings.TrimSpace(name)); trimmed != "" {
			sources = append(sources, trimmed)
		}
	}
	if len(sources) == 0 {
		return append([]string(nil), boardingSourcesReported...)
	}
	return sources
}
