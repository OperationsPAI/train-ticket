package postgres

import (
	"encoding/json"
	"time"

	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

type coordinateSnapshot struct {
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
}

type placeSnapshot struct {
	ID            string              `json:"id"`
	Type          domain.PlaceType    `json:"type"`
	CanonicalName string              `json:"canonicalName"`
	Code          string              `json:"code,omitempty"`
	Timezone      string              `json:"timezone,omitempty"`
	Status        domain.PlaceStatus  `json:"status"`
	Coordinate    *coordinateSnapshot `json:"coordinate,omitempty"`
	CreatedAt     time.Time           `json:"createdAt"`
}

type walkingEdgeSnapshot struct {
	ToNodeID           string `json:"toNodeId"`
	WalkingTimeMinutes int    `json:"walkingTimeMinutes"`
}

type nodeSnapshot struct {
	ID                string                 `json:"id"`
	PlaceID           string                 `json:"placeId"`
	DisplayName       string                 `json:"displayName"`
	ServingModes      []domain.TransportMode `json:"servingModes"`
	AccessTimeMinutes *int                   `json:"accessTimeMinutes,omitempty"`
	WalkingEdges      []walkingEdgeSnapshot  `json:"walkingEdges,omitempty"`
	CreatedAt         time.Time              `json:"createdAt"`
}

func placeSnapshotFromDomain(place domain.Place) placeSnapshot {
	var coordinate *coordinateSnapshot
	if place.Coordinate != nil {
		coordinate = &coordinateSnapshot{Latitude: place.Coordinate.Latitude, Longitude: place.Coordinate.Longitude}
	}
	return placeSnapshot{ID: string(place.ID), Type: place.Type, CanonicalName: place.CanonicalName, Code: place.Code, Timezone: place.Timezone, Status: place.Status, Coordinate: coordinate, CreatedAt: place.CreatedAt.UTC()}
}

func nodeSnapshotFromDomain(node domain.TransportNode) nodeSnapshot {
	walkingEdges := make([]walkingEdgeSnapshot, len(node.WalkingEdges))
	for i, edge := range node.WalkingEdges {
		walkingEdges[i] = walkingEdgeSnapshot{ToNodeID: string(edge.ToNodeID), WalkingTimeMinutes: edge.WalkingTimeMinutes}
	}
	return nodeSnapshot{ID: string(node.ID), PlaceID: string(node.PlaceID), DisplayName: node.DisplayName, ServingModes: append([]domain.TransportMode(nil), node.ServingModes...), AccessTimeMinutes: copyInt(node.AccessTimeMinutes), WalkingEdges: walkingEdges, CreatedAt: node.CreatedAt.UTC()}
}

func decodePlace(raw []byte) (domain.Place, error) {
	var snap placeSnapshot
	if err := json.Unmarshal(raw, &snap); err != nil {
		return domain.Place{}, err
	}
	var coordinate *domain.Coordinate
	if snap.Coordinate != nil {
		coordinate = &domain.Coordinate{Latitude: snap.Coordinate.Latitude, Longitude: snap.Coordinate.Longitude}
	}
	place, err := domain.NewPlace(domain.PlaceID(snap.ID), snap.Type, snap.CanonicalName, snap.Status, coordinate)
	if err != nil {
		return domain.Place{}, err
	}
	place.SetOptionalReferenceData(snap.Code, snap.Timezone)
	place.MarkCreatedAt(snap.CreatedAt)
	return place, nil
}

func decodeNode(raw []byte) (domain.TransportNode, error) {
	var snap nodeSnapshot
	if err := json.Unmarshal(raw, &snap); err != nil {
		return domain.TransportNode{}, err
	}
	node, err := domain.NewTransportNode(domain.TransportNodeID(snap.ID), domain.PlaceID(snap.PlaceID), snap.DisplayName, snap.ServingModes)
	if err != nil {
		return domain.TransportNode{}, err
	}
	walkingEdges := make([]domain.WalkingEdge, len(snap.WalkingEdges))
	for i, edge := range snap.WalkingEdges {
		walkingEdges[i] = domain.WalkingEdge{ToNodeID: domain.TransportNodeID(edge.ToNodeID), WalkingTimeMinutes: edge.WalkingTimeMinutes}
	}
	node.SetAccessWeights(snap.AccessTimeMinutes, walkingEdges)
	if err := node.Validate(); err != nil {
		return domain.TransportNode{}, err
	}
	node.MarkCreatedAt(snap.CreatedAt)
	return node, nil
}

func copyInt(value *int) *int {
	if value == nil {
		return nil
	}
	copied := *value
	return &copied
}
