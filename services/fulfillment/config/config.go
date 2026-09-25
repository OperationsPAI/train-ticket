package config

import (
	_ "embed"

	"github.com/trainticket/greenfield/platform/go-kit/settings"
	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

// boardingSettings is the settings file the release ships for boarding facts.
//
//go:embed boarding.properties
var boardingSettings string

var boarding = settings.Parse(boardingSettings)

// TrustedBoardingSources is the register of evidence sources a boarding fact
// may arrive from, as the release states it.
//
// It is read once, when the process starts: the register belongs to the
// revision the image was built from, so a change to it is a change to the
// release rather than something a running process may be told.
var TrustedBoardingSources = boardingSources(boarding)

func boardingSources(document settings.Document) []domain.FulfillmentSource {
	entries := document.List(SourceRegisterKey)
	sources := make([]domain.FulfillmentSource, 0, len(entries))
	for _, entry := range entries {
		sources = append(sources, domain.FulfillmentSource(entry))
	}
	return sources
}

// SourceRegisterKey is the setting the trusted boarding sources are stated
// under.
const SourceRegisterKey = "boarding.trustedSources"
