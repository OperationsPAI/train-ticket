package postgres

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

func TestNodeSnapshotPreservesOmittedAndZeroWalkingTimeMinutes(t *testing.T) {
	zero := 0
	node, err := domain.NewTransportNode("node-sha-hongqiao", "place-sha-hongqiao", "Shanghai Hongqiao Railway Station", []domain.TransportMode{domain.TransportModeTrain})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	node.SetAccessWeights(nil, []domain.WalkingEdge{{ToNodeID: "node-fallback"}, {ToNodeID: "node-zero", WalkingTimeMinutes: &zero}})
	node.MarkCreatedAt(time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC))

	data, err := json.Marshal(nodeSnapshotFromDomain(node))
	if err != nil {
		t.Fatal(err)
	}
	assertSnapshotWalkingTimePresence(t, data, []bool{false, true})

	decoded, err := decodeNode(data)
	if err != nil {
		t.Fatal(err)
	}
	if len(decoded.WalkingEdges) != 2 || decoded.WalkingEdges[0].WalkingTimeMinutes != nil || decoded.WalkingEdges[1].WalkingTimeMinutes == nil || *decoded.WalkingEdges[1].WalkingTimeMinutes != 0 {
		t.Fatalf("unexpected decoded walking edges: %#v", decoded.WalkingEdges)
	}
}

func assertSnapshotWalkingTimePresence(t *testing.T, data []byte, want []bool) {
	t.Helper()
	var snap struct {
		WalkingEdges []map[string]json.RawMessage `json:"walkingEdges"`
	}
	if err := json.Unmarshal(data, &snap); err != nil {
		t.Fatal(err)
	}
	if len(snap.WalkingEdges) != len(want) {
		t.Fatalf("walking edge count mismatch: got %d want %d data=%s", len(snap.WalkingEdges), len(want), data)
	}
	for i, edge := range snap.WalkingEdges {
		_, ok := edge["walkingTimeMinutes"]
		if ok != want[i] {
			t.Fatalf("walking edge %d walkingTimeMinutes presence mismatch: got %t want %t data=%s", i, ok, want[i], data)
		}
	}
}
