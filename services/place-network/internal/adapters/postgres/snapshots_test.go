package postgres

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

func TestNodeSnapshotPreservesOptionalWalkingEdgeWeight(t *testing.T) {
	node, err := domain.NewTransportNode("tnd-source", "plc-source", "Source", []domain.TransportMode{domain.TransportModeTrain})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	zero := 0
	node.SetAccessWeights(nil, []domain.WalkingEdge{{ToNodeID: "tnd-unknown"}, {ToNodeID: "tnd-zero", WalkingTimeMinutes: &zero}})
	node.MarkCreatedAt(time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC))

	data, err := json.Marshal(nodeSnapshotFromDomain(node))
	if err != nil {
		t.Fatalf("marshal snapshot: %v", err)
	}
	var raw map[string]any
	if err := json.Unmarshal(data, &raw); err != nil {
		t.Fatalf("unmarshal raw snapshot: %v", err)
	}
	assertRawWalkingEdgesOmitUnknownAndKeepZero(t, raw)

	decoded, err := decodeNode(data)
	if err != nil {
		t.Fatalf("decode node: %v", err)
	}
	if len(decoded.WalkingEdges) != 2 {
		t.Fatalf("expected two walking edges, got %#v", decoded.WalkingEdges)
	}
	if decoded.WalkingEdges[0].WalkingTimeMinutes != nil {
		t.Fatalf("omitted walkingTimeMinutes must decode as nil: %#v", decoded.WalkingEdges[0])
	}
	if decoded.WalkingEdges[1].WalkingTimeMinutes == nil || *decoded.WalkingEdges[1].WalkingTimeMinutes != 0 {
		t.Fatalf("explicit zero walkingTimeMinutes must decode as zero: %#v", decoded.WalkingEdges[1])
	}
}

func assertRawWalkingEdgesOmitUnknownAndKeepZero(t *testing.T, body map[string]any) {
	t.Helper()
	edges, ok := body["walkingEdges"].([]any)
	if !ok || len(edges) != 2 {
		t.Fatalf("expected two walking edges, got %#v", body["walkingEdges"])
	}
	unknown, ok := edges[0].(map[string]any)
	if !ok {
		t.Fatalf("expected first walking edge object, got %#v", edges[0])
	}
	if unknown["toNodeId"] != "tnd-unknown" {
		t.Fatalf("unexpected first walking edge: %#v", unknown)
	}
	if _, exists := unknown["walkingTimeMinutes"]; exists {
		t.Fatalf("omitted walkingTimeMinutes must stay omitted, got %#v", unknown)
	}
	zero, ok := edges[1].(map[string]any)
	if !ok {
		t.Fatalf("expected second walking edge object, got %#v", edges[1])
	}
	if zero["toNodeId"] != "tnd-zero" || zero["walkingTimeMinutes"] != float64(0) {
		t.Fatalf("explicit zero walkingTimeMinutes must stay zero, got %#v", zero)
	}
}
