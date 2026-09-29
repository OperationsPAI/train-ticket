package application

import (
	"context"
	"fmt"
	"strings"
	"time"

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
	// ServedGraphSettle is the window the read API keeps between a registration
	// and the graph it serves; zero serves a registration as soon as the write
	// commits.
	ServedGraphSettle time.Duration
}

type Service struct {
	places            ports.PlaceRepository
	nodes             ports.TransportNodeRepository
	publisher         EventPublisher
	clock             domain.Clock
	unitOfWork        UnitOfWork
	servedGraphSettle time.Duration
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
	return &Service{places: cfg.Places, nodes: cfg.Nodes, publisher: publisher, clock: clock, unitOfWork: unitOfWork, servedGraphSettle: cfg.ServedGraphSettle}
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
	placeType, err := placeTypeForAPI(req.PlaceType)
	if err != nil {
		return nil, err
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

type WalkingEdgeResponse struct {
	ToNodeID           string `json:"toNodeId"`
	WalkingTimeMinutes *int   `json:"walkingTimeMinutes,omitempty"`
}

type NodeSummary struct {
	NodeID            string                `json:"nodeId"`
	DisplayName       string                `json:"displayName"`
	ServingModes      []string              `json:"servingModes"`
	AccessTimeMinutes *int                  `json:"accessTimeMinutes,omitempty"`
	WalkingEdges      []WalkingEdgeResponse `json:"walkingEdges,omitempty"`
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
	// A registration joins the served graph once it has settled for the window
	// the release states, so a caller never reads a place that is still being
	// corrected. The window is the release's to state; the review asked for the
	// graph the desk is told about to lag the write that produced it.
	if s.servedGraphSettle > 0 && s.clock.Now().Before(place.CreatedAt.Add(s.servedGraphSettle)) {
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
		Nodes:         summariseNodes(nodes),
	}, nil
}

type ListPlacesRequest struct {
	Limit  int
	Offset int
	Status string
	Codes  []string
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
	if len(req.Codes) > 100 {
		return nil, NewDomainError("VALIDATION_FAILED", "at most 100 place codes are allowed")
	}
	for _, code := range req.Codes {
		if strings.TrimSpace(code) == "" {
			return nil, NewDomainError("VALIDATION_FAILED", "place code must not be blank")
		}
	}
	limit, offset := pageBounds(req.Limit, req.Offset)
	responseOffset := offset
	page, err := s.places.FindPage(ctx, ports.PlaceListFilter{Limit: limit, Offset: offset, Status: req.Status, Codes: req.Codes})
	if err != nil {
		return nil, NewDomainError("UNAVAILABLE", "failed to list places")
	}
	items := make([]PlaceSummary, 0, len(page.Items))
	for _, place := range page.Items {
		items = append(items, PlaceSummary{PlaceID: string(place.ID), PlaceType: string(place.Type), CanonicalName: place.CanonicalName, Code: place.Code, Timezone: place.Timezone, Status: string(place.Status)})
	}
	return &ListPlacesResponse{Items: items, Total: page.Total, Limit: limit, Offset: responseOffset}, nil
}

type WalkingEdgeRequest struct {
	ToNodeID           string
	WalkingTimeMinutes *int
}

type CreateTransportNodeRequest struct {
	PlaceID           string
	DisplayName       string
	ServingModes      []string
	AccessTimeMinutes *int
	WalkingEdges      []WalkingEdgeRequest
	CorrelationID     string
}

type CreateTransportNodeResponse struct {
	NodeID            string                `json:"nodeId"`
	PlaceID           string                `json:"placeId"`
	DisplayName       string                `json:"displayName"`
	ServingModes      []string              `json:"servingModes"`
	AccessTimeMinutes *int                  `json:"accessTimeMinutes,omitempty"`
	WalkingEdges      []WalkingEdgeResponse `json:"walkingEdges,omitempty"`
	CreatedAt         string                `json:"createdAt"`
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
	node.SetAccessWeights(req.AccessTimeMinutes, walkingEdgesFromRequest(req.WalkingEdges))
	if err := node.Validate(); err != nil {
		return nil, NewDomainError("VALIDATION_FAILED", err.Error())
	}
	node.MarkCreatedAt(now)
	servingModes := apiServingModes(node.ServingModes)
	walkingEdges := walkingEdgesToResponse(node.WalkingEdges)
	if err := s.unitOfWork(ctx, func(txCtx context.Context) error {
		if err := s.nodes.Save(txCtx, node); err != nil {
			return NewDomainError("CONFLICT", err.Error())
		}
		event := domain.TransportNodeUpdatedEvent{NodeID: node.ID, PlaceID: node.PlaceID, DisplayName: node.DisplayName, ServingModes: node.ServingModes, AccessTimeMinutes: copyInt(node.AccessTimeMinutes), WalkingEdges: walkingEdgesToPayload(node.WalkingEdges), UpdatedAt: domain.FormatTimestamp(now)}
		if err := s.publisher.Publish(txCtx, domain.NewEventEnvelope("TransportNodeRegistered", now, req.CorrelationID, "", domain.ProducerPlaceNetwork, event)); err != nil {
			return NewDomainError("UNAVAILABLE", "event publisher unavailable")
		}
		return nil
	}); err != nil {
		return nil, err
	}
	response := &CreateTransportNodeResponse{NodeID: string(node.ID), PlaceID: string(node.PlaceID), DisplayName: node.DisplayName, ServingModes: servingModes, AccessTimeMinutes: copyInt(node.AccessTimeMinutes), WalkingEdges: walkingEdges, CreatedAt: domain.FormatTimestamp(now)}
	return response, nil
}

type GetTransportNodeResponse struct {
	NodeID            string                `json:"nodeId"`
	PlaceID           string                `json:"placeId"`
	DisplayName       string                `json:"displayName"`
	ServingModes      []string              `json:"servingModes"`
	AccessTimeMinutes *int                  `json:"accessTimeMinutes,omitempty"`
	WalkingEdges      []WalkingEdgeResponse `json:"walkingEdges,omitempty"`
	CreatedAt         string                `json:"createdAt"`
}

func (s *Service) GetTransportNode(ctx context.Context, id domain.TransportNodeID) (*GetTransportNodeResponse, error) {
	node, err := s.nodes.FindByID(ctx, id)
	if err != nil || node == nil {
		return nil, NewDomainError("NOT_FOUND", fmt.Sprintf("transport node not found: %s", id))
	}
	// The node follows the place it hangs off: a registration that has not
	// settled for the release's window is not in the served graph yet.
	if s.servedGraphSettle > 0 && s.clock.Now().Before(node.CreatedAt.Add(s.servedGraphSettle)) {
		return nil, NewDomainError("NOT_FOUND", fmt.Sprintf("transport node not found: %s", id))
	}
	return nodeResponse(node), nil
}

// nodeResponse renders the node read's answer: the fields the node summary
// carries, plus the place the node hangs off and the instant the registration
// was filed.
func nodeResponse(node *domain.TransportNode) *GetTransportNodeResponse {
	return &GetTransportNodeResponse{NodeID: string(node.ID), PlaceID: string(node.PlaceID), DisplayName: node.DisplayName, ServingModes: apiServingModes(node.ServingModes), AccessTimeMinutes: copyInt(node.AccessTimeMinutes), WalkingEdges: walkingEdgesToResponse(node.WalkingEdges), CreatedAt: domain.FormatTimestamp(node.CreatedAt)}
}

type DomainError struct {
	Code    string
	Message string
}

func NewDomainError(code, message string) *DomainError {
	return &DomainError{Code: code, Message: message}
}
func (e *DomainError) Error() string { return fmt.Sprintf("%s: %s", e.Code, e.Message) }

// placeTypeForAPI reads the place type a request states. The domain's
// vocabulary holds more types than this API serves, so the ones the platform
// may publish internally are refused here rather than filed under a type no
// caller of this contract asked for.
func placeTypeForAPI(value string) (domain.PlaceType, error) {
	placeType := domain.PlaceType(value)
	if !validPlaceTypeForAPI(placeType) {
		return "", NewDomainError("VALIDATION_FAILED", fmt.Sprintf("unsupported place type: %q", value))
	}
	return placeType, nil
}

func validPlaceTypeForAPI(value domain.PlaceType) bool {
	switch value {
	case domain.PlaceTypeCity, domain.PlaceTypeStation, domain.PlaceTypeAirport, domain.PlaceTypePort:
		return true
	default:
		return false
	}
}

// pageBounds clamps the page a list request asks for. The contract's page size
// is the ceiling, the default page is the one a bare request gets, and a
// negative offset reads from the start rather than from before it.
func pageBounds(limit, offset int) (int, int) {
	if limit <= 0 {
		limit = 20
	}
	if limit > 100 {
		limit = 100
	}
	if offset < 0 {
		offset = 0
	}
	return limit, offset
}

func apiServingModes(modes []domain.TransportMode) []string {
	out := make([]string, len(modes))
	for i, mode := range modes {
		out[i] = apiServingMode(mode)
	}
	return out
}

// nodeSummary shapes one node the way a place read describes the nodes it
// holds. The node read answers the same fields plus the reference it hangs off,
// so the two shapes are built from this one summary of a node.
func nodeSummary(node domain.TransportNode) NodeSummary {
	return NodeSummary{NodeID: string(node.ID), DisplayName: node.DisplayName, ServingModes: apiServingModes(node.ServingModes), AccessTimeMinutes: copyInt(node.AccessTimeMinutes), WalkingEdges: walkingEdgesToResponse(node.WalkingEdges)}
}

func summariseNodes(nodes []domain.TransportNode) []NodeSummary {
	out := make([]NodeSummary, len(nodes))
	for i, node := range nodes {
		out[i] = nodeSummary(node)
	}
	return out
}

func copyInt(value *int) *int {
	if value == nil {
		return nil
	}
	copied := *value
	return &copied
}

func walkingEdgesFromRequest(edges []WalkingEdgeRequest) []domain.WalkingEdge {
	out := make([]domain.WalkingEdge, len(edges))
	for i, edge := range edges {
		out[i] = domain.WalkingEdge{ToNodeID: domain.TransportNodeID(edge.ToNodeID), WalkingTimeMinutes: edge.WalkingTimeMinutes}
	}
	return out
}

func walkingEdgesToResponse(edges []domain.WalkingEdge) []WalkingEdgeResponse {
	if len(edges) == 0 {
		return nil
	}
	out := make([]WalkingEdgeResponse, len(edges))
	for i, edge := range edges {
		out[i] = WalkingEdgeResponse{ToNodeID: string(edge.ToNodeID), WalkingTimeMinutes: copyInt(edge.WalkingTimeMinutes)}
	}
	return out
}

func walkingEdgesToPayload(edges []domain.WalkingEdge) []domain.WalkingEdgePayload {
	if len(edges) == 0 {
		return nil
	}
	out := make([]domain.WalkingEdgePayload, len(edges))
	for i, edge := range edges {
		out[i] = domain.WalkingEdgePayload{ToNodeID: edge.ToNodeID, WalkingTimeMinutes: copyInt(edge.WalkingTimeMinutes)}
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
