package config

import (
	_ "embed"
	"encoding/json"
	"fmt"
)

// servedGraphJSON is this service's own settings file for the graph its read
// API serves. It is embedded rather than mounted so the window an operator
// reads in the repository is the window the image runs, and the release carries
// no extra volume for a value it ships.
//
//go:embed served_graph.json
var servedGraphJSON []byte

// ServedGraph states what the read API keeps between a registration and the
// graph it serves. SettleSeconds is that window in whole seconds; zero serves a
// registration as soon as the write commits, which is what a release that
// states no window does.
type ServedGraph struct {
	SettleSeconds int `json:"settleSeconds"`
}

// LoadServedGraph reads the embedded settings file. A negative window is a typo
// in a release decision, so it is refused at startup rather than clamped to
// zero and hidden.
func LoadServedGraph() (ServedGraph, error) {
	var served ServedGraph
	if err := json.Unmarshal(servedGraphJSON, &served); err != nil {
		return ServedGraph{}, fmt.Errorf("served graph settings: %w", err)
	}
	if served.SettleSeconds < 0 {
		return ServedGraph{}, fmt.Errorf("served graph settings: settleSeconds must not be negative")
	}
	return served, nil
}
