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

type nodeSnapshot struct {
	ID           string                 `json:"id"`
	PlaceID      string                 `json:"placeId"`
	DisplayName  string                 `json:"displayName"`
	ServingModes []domain.TransportMode `json:"servingModes"`
	CreatedAt    time.Time              `json:"createdAt"`
}

func placeSnapshotFromDomain(place domain.Place) placeSnapshot {
	var coordinate *coordinateSnapshot
	if place.Coordinate != nil {
		coordinate = &coordinateSnapshot{Latitude: place.Coordinate.Latitude, Longitude: place.Coordinate.Longitude}
	}
	return placeSnapshot{ID: string(place.ID), Type: place.Type, CanonicalName: place.CanonicalName, Code: place.Code, Timezone: place.Timezone, Status: place.Status, Coordinate: coordinate, CreatedAt: place.CreatedAt.UTC()}
}

func nodeSnapshotFromDomain(node domain.TransportNode) nodeSnapshot {
	return nodeSnapshot{ID: string(node.ID), PlaceID: string(node.PlaceID), DisplayName: node.DisplayName, ServingModes: append([]domain.TransportMode(nil), node.ServingModes...), CreatedAt: node.CreatedAt.UTC()}
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
	node.MarkCreatedAt(snap.CreatedAt)
	return node, nil
}
