from __future__ import annotations

import pytest

from transfer_management.topology import (
    PlaceNetworkUnavailable,
    TopologyNode,
    TopologySnapshot,
    WalkingEdge,
    _walking_edges,
)


def test_omitted_walking_edge_weight_is_unknown_and_allows_fallback() -> None:
    snapshot = TopologySnapshot(
        TopologyNode("tnd-a", "plc-a", "STATION", "v1", walkingEdges=(WalkingEdge("tnd-b"),)),
        TopologyNode("tnd-b", "plc-b", "STATION", "v1"),
    )

    assert snapshot.walkingTimeMinutes is None
    assert snapshot.mctAccessTimeMinutes is None


def test_explicit_zero_walking_edge_weight_is_valid_zero() -> None:
    snapshot = TopologySnapshot(
        TopologyNode(
            "tnd-a",
            "plc-a",
            "STATION",
            "v1",
            walkingEdges=(WalkingEdge("tnd-b"), WalkingEdge("tnd-b", 0), WalkingEdge("tnd-b", 5)),
        ),
        TopologyNode("tnd-b", "plc-b", "STATION", "v1"),
    )

    assert snapshot.walkingTimeMinutes == 0
    assert snapshot.mctAccessTimeMinutes == 0


def test_place_network_walking_edges_accept_omitted_and_zero_weights() -> None:
    edges = _walking_edges([
        {"toNodeId": "tnd-unknown"},
        {"toNodeId": "tnd-zero", "walkingTimeMinutes": 0},
    ])

    assert edges == (WalkingEdge("tnd-unknown"), WalkingEdge("tnd-zero", 0))


def test_place_network_walking_edges_reject_negative_weights() -> None:
    with pytest.raises(PlaceNetworkUnavailable):
        _walking_edges([{"toNodeId": "tnd-negative", "walkingTimeMinutes": -1}])
