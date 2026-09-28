package config

import (
	_ "embed"
	"encoding/json"
	"fmt"
)

// wayfindingJSON is this service's own settings file for the wayfinding the
// timetable does not state per node. It is embedded rather than mounted so the
// allowance an operator reads in the repository is the allowance the image
// runs, and the release carries no extra volume for a value it ships.
//
//go:embed wayfinding.json
var wayfindingJSON []byte

// Wayfinding is the allowance place-network gives a node whose operator
// declared no access time of its own. Zero leaves the field absent, which is
// the unknown the API contract documents; a positive value is the allowance in
// whole minutes.
type Wayfinding struct {
	StationAccessTimeMinutes int `json:"stationAccessTimeMinutes"`
}

// LoadWayfinding reads the embedded settings file. A negative allowance is a
// typo in an operator decision, so it is refused at startup rather than clamped
// to zero and hidden.
func LoadWayfinding() (Wayfinding, error) {
	var wayfinding Wayfinding
	if err := json.Unmarshal(wayfindingJSON, &wayfinding); err != nil {
		return Wayfinding{}, fmt.Errorf("wayfinding settings: %w", err)
	}
	if wayfinding.StationAccessTimeMinutes < 0 {
		return Wayfinding{}, fmt.Errorf("wayfinding settings: stationAccessTimeMinutes must not be negative")
	}
	return wayfinding, nil
}
