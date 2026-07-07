package application

import (
	"context"
	"fmt"

	"github.com/trainticket/greenfield/services/place-network/internal/domain"
	"github.com/trainticket/greenfield/services/place-network/internal/domain/ports"
)

type UnitOfWork func(context.Context, func(context.Context) error) error

type ServiceConfig struct {
	Places     ports.PlaceRepository
	Nodes      ports.TransportNodeRepository
	Publisher  EventPublisher
	Clock      domain.Clock
	UnitOfWork UnitOfWork
}

type Service struct {
	places     ports.PlaceRepository
	nodes      ports.TransportNodeRepository
	publisher  EventPublisher
	clock      domain.Clock
	unitOfWork UnitOfWork
}

func NewService(cfg ServiceConfig) *Service {
	publisher := cfg.Publisher
	if publisher == nil {
		publisher = NewNoopPublisher()
	}
	clock := cfg.Clock
	if clock == nil {
		clock = domain.RealClock{}
	}
	unitOfWork := cfg.UnitOfWork
	if unitOfWork == nil {
		unitOfWork = func(ctx context.Context, fn func(context.Context) error) error { return fn(ctx) }
	}
	return &Service{places: cfg.Places, nodes: cfg.Nodes, publisher: publisher, clock: clock, unitOfWork: unitOfWork}
}

type CreatePlaceRequest struct {
	PlaceType     string
	CanonicalName string
	Code          string
	Timezone      string
	CorrelationID string
}

type CreatePlaceResponse struct {
	PlaceID       string `json:"placeId"`
	PlaceType     string `json:"placeType"`
	CanonicalName string `json:"canonicalName"`
	Status        string `json:"status"`
	CreatedAt     string `json:"createdAt"`
}

func (s *Service) CreatePlace(ctx context.Context, req CreatePlaceRequest) (*CreatePlaceResponse, error) {
	placeType := domain.PlaceType(req.PlaceType)
	if !validPlaceTypeForAPI(placeType) {
		return nil, NewDomainError("VALIDATION_FAILED", fmt.Sprintf("unsupported place type: %q", req.PlaceType))
	}

	now := s.clock.Now()
	place, err := domain.NewPlace(domain.NewPlaceID(), placeType, req.CanonicalName, domain.PlaceStatusActive, nil)
	if err != nil {
		return nil, NewDomainError("VALIDATION_FAILED", err.Error())
	}
	place.SetOptionalReferenceData(req.Code, req.Timezone)
	place.MarkCreatedAt(now)
	if err := s.unitOfWork(ctx, func(txCtx context.Context) error {
		if err := s.places.Save(txCtx, place); err != nil {
			return NewDomainError("CONFLICT", err.Error())
		}
		event := domain.PlaceUpdatedEvent{
			PlaceID:       place.ID,
			PlaceType:     place.Type,
			CanonicalName: place.CanonicalName,
			Code:          req.Code,
			Timezone:      req.Timezone,
			Status:        place.Status,
			UpdatedAt:     domain.FormatTimestamp(now),
		}
		if err := s.publisher.Publish(txCtx, domain.NewEventEnvelope("PlaceRegistered", now, req.CorrelationID, "", domain.ProducerPlaceNetwork, event)); err != nil {
			return NewDomainError("UNAVAILABLE", "event publisher unavailable")
		}
		return nil
	}); err != nil {
		return nil, err
	}

	response := &CreatePlaceResponse{
		PlaceID:       string(place.ID),
		PlaceType:     string(place.Type),
		CanonicalName: place.CanonicalName,
		Status:        string(place.Status),
		CreatedAt:     domain.FormatTimestamp(now),
	}

	return response, nil
}

type NodeSummary struct {
	NodeID       string   `json:"nodeId"`
	DisplayName  string   `json:"displayName"`
	ServingModes []string `json:"servingModes"`
}

type GetPlaceResponse struct {
	PlaceID       string        `json:"placeId"`
	PlaceType     string        `json:"placeType"`
	CanonicalName string        `json:"canonicalName"`
	Code          string        `json:"code,omitempty"`
	Timezone      string        `json:"timezone,omitempty"`
	Status        string        `json:"status"`
	Nodes         []NodeSummary `json:"nodes"`
}

func (s *Service) GetPlace(ctx context.Context, id domain.PlaceID) (*GetPlaceResponse, error) {
	place, err := s.places.FindByID(ctx, id)
	if err != nil || place == nil {
		return nil, NewDomainError("NOT_FOUND", fmt.Sprintf("place not found: %s", id))
	}
	nodes, err := s.nodes.FindByPlaceID(ctx, id)
	if err != nil {
		return nil, NewDomainError("UNAVAILABLE", "failed to load transport nodes")
	}
	return &GetPlaceResponse{
		PlaceID:       string(place.ID),
		PlaceType:     string(place.Type),
		CanonicalName: place.CanonicalName,
		Code:          place.Code,
		Timezone:      place.Timezone,
		Status:        string(place.Status),
		Nodes:         nodeSummaries(nodes),
	}, nil
}

type ListPlacesRequest struct {
	Limit  int
	Offset int
	Status string
}

type PlaceSummary struct {
	PlaceID       string `json:"placeId"`
	PlaceType     string `json:"placeType"`
	CanonicalName string `json:"canonicalName"`
	Code          string `json:"code,omitempty"`
	Timezone      string `json:"timezone,omitempty"`
	Status        string `json:"status"`
}

type ListPlacesResponse struct {
	Items  []PlaceSummary `json:"items"`
	Total  int            `json:"total"`
	Limit  int            `json:"limit"`
	Offset int            `json:"offset"`
}

func (s *Service) ListPlaces(ctx context.Context, req ListPlacesRequest) (*ListPlacesResponse, error) {
	limit := req.Limit
	if limit <= 0 {
		limit = 20
	}
	if limit > 100 {
		limit = 100
	}
	offset := req.Offset
	if offset < 0 {
		offset = 0
	}
	responseOffset := offset
	page, err := s.places.FindPage(ctx, ports.PlaceListFilter{Limit: limit, Offset: offset, Status: req.Status})
	if err != nil {
		return nil, NewDomainError("UNAVAILABLE", "failed to list places")
	}
	items := make([]PlaceSummary, 0, len(page.Items))
	for _, place := range page.Items {
		items = append(items, PlaceSummary{PlaceID: string(place.ID), PlaceType: string(place.Type), CanonicalName: place.CanonicalName, Code: place.Code, Timezone: place.Timezone, Status: string(place.Status)})
	}
	return &ListPlacesResponse{Items: items, Total: page.Total, Limit: limit, Offset: responseOffset}, nil
}

type CreateTransportNodeRequest struct {
	PlaceID       string
	DisplayName   string
	ServingModes  []string
	CorrelationID string
}

type CreateTransportNodeResponse struct {
	NodeID       string   `json:"nodeId"`
	PlaceID      string   `json:"placeId"`
	DisplayName  string   `json:"displayName"`
	ServingModes []string `json:"servingModes"`
	CreatedAt    string   `json:"createdAt"`
}

func (s *Service) CreateTransportNode(ctx context.Context, req CreateTransportNodeRequest) (*CreateTransportNodeResponse, error) {
	placeID := domain.PlaceID(req.PlaceID)
	place, err := s.places.FindByID(ctx, placeID)
	if err != nil || place == nil {
		return nil, NewDomainError("NOT_FOUND", fmt.Sprintf("place not found: %s", req.PlaceID))
	}
	modes := make([]domain.TransportMode, len(req.ServingModes))
	for i, mode := range req.ServingModes {
		modes[i] = apiTransportMode(mode)
	}
	now := s.clock.Now()
	node, err := domain.NewTransportNode(domain.NewTransportNodeID(), placeID, req.DisplayName, modes)
	if err != nil {
		return nil, NewDomainError("VALIDATION_FAILED", err.Error())
	}
	node.MarkCreatedAt(now)
	servingModes := stringModes(node.ServingModes)
	if err := s.unitOfWork(ctx, func(txCtx context.Context) error {
		if err := s.nodes.Save(txCtx, node); err != nil {
			return NewDomainError("CONFLICT", err.Error())
		}
		event := domain.TransportNodeUpdatedEvent{NodeID: node.ID, PlaceID: node.PlaceID, DisplayName: node.DisplayName, ServingModes: node.ServingModes, UpdatedAt: domain.FormatTimestamp(now)}
		if err := s.publisher.Publish(txCtx, domain.NewEventEnvelope("TransportNodeRegistered", now, req.CorrelationID, "", domain.ProducerPlaceNetwork, event)); err != nil {
			return NewDomainError("UNAVAILABLE", "event publisher unavailable")
		}
		return nil
	}); err != nil {
		return nil, err
	}
	response := &CreateTransportNodeResponse{NodeID: string(node.ID), PlaceID: string(node.PlaceID), DisplayName: node.DisplayName, ServingModes: servingModes, CreatedAt: domain.FormatTimestamp(now)}
	return response, nil
}

type GetTransportNodeResponse struct {
	NodeID       string   `json:"nodeId"`
	PlaceID      string   `json:"placeId"`
	DisplayName  string   `json:"displayName"`
	ServingModes []string `json:"servingModes"`
	CreatedAt    string   `json:"createdAt"`
}

func (s *Service) GetTransportNode(ctx context.Context, id domain.TransportNodeID) (*GetTransportNodeResponse, error) {
	node, err := s.nodes.FindByID(ctx, id)
	if err != nil || node == nil {
		return nil, NewDomainError("NOT_FOUND", fmt.Sprintf("transport node not found: %s", id))
	}
	return &GetTransportNodeResponse{NodeID: string(node.ID), PlaceID: string(node.PlaceID), DisplayName: node.DisplayName, ServingModes: stringModes(node.ServingModes), CreatedAt: domain.FormatTimestamp(node.CreatedAt)}, nil
}

type DomainError struct {
	Code    string
	Message string
}

func NewDomainError(code, message string) *DomainError {
	return &DomainError{Code: code, Message: message}
}
func (e *DomainError) Error() string { return fmt.Sprintf("%s: %s", e.Code, e.Message) }

func validPlaceTypeForAPI(value domain.PlaceType) bool {
	switch value {
	case domain.PlaceTypeCity, domain.PlaceTypeStation, domain.PlaceTypeAirport, domain.PlaceTypePort:
		return true
	default:
		return false
	}
}

func stringModes(modes []domain.TransportMode) []string {
	out := make([]string, len(modes))
	for i, mode := range modes {
		out[i] = apiServingMode(mode)
	}
	return out
}

func nodeSummaries(nodes []domain.TransportNode) []NodeSummary {
	out := make([]NodeSummary, len(nodes))
	for i, node := range nodes {
		out[i] = NodeSummary{NodeID: string(node.ID), DisplayName: node.DisplayName, ServingModes: stringModes(node.ServingModes)}
	}
	return out
}

func apiTransportMode(value string) domain.TransportMode {
	if value == "RAIL" {
		return domain.TransportModeTrain
	}
	return domain.TransportMode(value)
}

func apiServingMode(value domain.TransportMode) string {
	if value == domain.TransportModeTrain {
		return "RAIL"
	}
	return string(value)
}
