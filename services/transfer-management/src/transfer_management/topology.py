from __future__ import annotations

from dataclasses import dataclass
import os
from typing import Any, Mapping

import httpx

from transfer_management.domain import NodeType


class PlaceNetworkUnavailable(RuntimeError):
    pass


class PlaceNetworkValidationError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class WalkingEdge:
    toNodeId: str
    walkingTimeMinutes: int


@dataclass(frozen=True, slots=True)
class TopologyNode:
    nodeId: str
    placeId: str
    placeType: str | None
    createdAt: str | None
    accessTimeMinutes: int | None = None
    walkingEdges: tuple[WalkingEdge, ...] = ()


@dataclass(frozen=True, slots=True)
class TopologySnapshot:
    fromNode: TopologyNode
    toNode: TopologyNode

    @property
    def placeGraphVersion(self) -> str:
        from_version = self.fromNode.createdAt or "unversioned"
        to_version = self.toNode.createdAt or "unversioned"
        return f"place-network:{self.fromNode.nodeId}@{from_version}:{self.toNode.nodeId}@{to_version}"

    @property
    def mctAccessTimeMinutes(self) -> int | None:
        values = [self.fromNode.accessTimeMinutes, self.toNode.accessTimeMinutes]
        if any(value is not None for value in values):
            return sum(value or 0 for value in values)
        edge_minutes = self.walkingTimeMinutes
        return edge_minutes

    @property
    def walkingTimeMinutes(self) -> int | None:
        direct_edges = [edge.walkingTimeMinutes for edge in self.fromNode.walkingEdges if edge.toNodeId == self.toNode.nodeId]
        return min(direct_edges) if direct_edges else None


class PlaceNetworkClient:
    def __init__(self, base_url: str | None = None, timeout: float = 2.0, retries: int = 1) -> None:
        if base_url is None:
            base_url = os.getenv("PLACE_NETWORK_URL")
        self.base_url = ("http://place-network:8080" if base_url is None else base_url).rstrip("/")
        self.timeout = timeout
        self.retries = max(0, retries)

    @property
    def enabled(self) -> bool:
        return bool(self.base_url)

    def fetch_snapshot(self, from_node_ref: str, to_node_ref: str) -> TopologySnapshot | None:
        if not self.enabled:
            return None
        return TopologySnapshot(self._get_node(from_node_ref), self._get_node(to_node_ref))

    def validate_connection_nodes(self, from_node_ref: str, to_node_ref: str, from_node_type: NodeType, to_node_type: NodeType) -> TopologySnapshot | None:
        snapshot = self.fetch_snapshot(from_node_ref, to_node_ref)
        if snapshot is None:
            return None
        self._validate_node_type(snapshot.fromNode, from_node_type, "fromNodeType")
        self._validate_node_type(snapshot.toNode, to_node_type, "toNodeType")
        return snapshot

    def _get_json(self, path: str) -> Mapping[str, Any]:
        last_error: Exception | None = None
        for _ in range(self.retries + 1):
            try:
                with httpx.Client(timeout=self.timeout) as client:
                    response = client.get(f"{self.base_url}{path}", headers={"Accept": "application/json"})
                if response.status_code == 404:
                    raise PlaceNetworkValidationError(f"place-network resource not found: {path}")
                if response.status_code >= 500:
                    raise PlaceNetworkUnavailable(f"place-network returned {response.status_code}")
                if response.status_code >= 400:
                    raise PlaceNetworkValidationError(f"place-network rejected lookup {response.status_code}")
                payload = response.json()
                if not isinstance(payload, Mapping):
                    raise PlaceNetworkUnavailable("place-network returned invalid body")
                return payload
            except PlaceNetworkValidationError:
                raise
            except (httpx.HTTPError, ValueError) as exc:
                last_error = exc
        raise PlaceNetworkUnavailable(str(last_error) if last_error else "place-network unavailable")

    def _get_node(self, node_ref: str) -> TopologyNode:
        node = self._get_json(f"/api/v1/transport-nodes/{node_ref}")
        node_id = str(node.get("nodeId") or "")
        place_id = str(node.get("placeId") or "")
        if not node_id or not place_id:
            raise PlaceNetworkUnavailable("place-network node response missing nodeId or placeId")
        place = self._get_json(f"/api/v1/places/{place_id}")
        place_type = str(place.get("placeType") or "") or None
        access_time = _optional_non_negative_int(node.get("accessTimeMinutes"), "accessTimeMinutes")
        walking_edges = _walking_edges(node.get("walkingEdges"))
        return TopologyNode(node_id, place_id, place_type, str(node.get("createdAt") or "") or None, access_time, walking_edges)

    def _validate_node_type(self, node: TopologyNode, expected: NodeType, field_name: str) -> None:
        if node.placeType is None:
            return
        allowed = {
            NodeType.STATION: {"STATION"},
            NodeType.AIRPORT_TERMINAL: {"AIRPORT"},
            NodeType.PORT_TERMINAL: {"PORT"},
        }.get(expected)
        if allowed is not None and node.placeType not in allowed:
            raise PlaceNetworkValidationError(f"{field_name} does not match place-network placeType {node.placeType}")


def _optional_non_negative_int(value: Any, field_name: str) -> int | None:
    if value is None:
        return None
    try:
        minutes = int(value)
    except (TypeError, ValueError) as exc:
        raise PlaceNetworkUnavailable(f"place-network node response has invalid {field_name}") from exc
    if minutes < 0:
        raise PlaceNetworkUnavailable(f"place-network node response has negative {field_name}")
    return minutes


def _walking_edges(value: Any) -> tuple[WalkingEdge, ...]:
    if value is None:
        return ()
    if not isinstance(value, list):
        raise PlaceNetworkUnavailable("place-network node response has invalid walkingEdges")
    edges: list[WalkingEdge] = []
    for item in value:
        if not isinstance(item, Mapping):
            raise PlaceNetworkUnavailable("place-network node response has invalid walkingEdges item")
        to_node_id = str(item.get("toNodeId") or "").strip()
        if not to_node_id:
            raise PlaceNetworkUnavailable("place-network walkingEdges item missing toNodeId")
        walking_time = _optional_non_negative_int(item.get("walkingTimeMinutes"), "walkingTimeMinutes")
        if walking_time is None:
            raise PlaceNetworkUnavailable("place-network walkingEdges item missing walkingTimeMinutes")
        edges.append(WalkingEdge(to_node_id, walking_time))
    return tuple(edges)
