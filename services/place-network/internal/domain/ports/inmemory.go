package ports

import (
	"fmt"
	"sort"
	"sync"

	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

type InMemoryPlaceRepository struct {
	mu     sync.RWMutex
	places map[domain.PlaceID]domain.Place
}

func NewInMemoryPlaceRepository() *InMemoryPlaceRepository {
	return &InMemoryPlaceRepository{places: make(map[domain.PlaceID]domain.Place)}
}

func (r *InMemoryPlaceRepository) Save(place domain.Place) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, exists := r.places[place.ID]; exists {
		return fmt.Errorf("place already exists: %s", place.ID)
	}
	r.places[place.ID] = place
	return nil
}

func (r *InMemoryPlaceRepository) FindByID(id domain.PlaceID) (*domain.Place, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	place, exists := r.places[id]
	if !exists {
		return nil, nil
	}
	return &place, nil
}

func (r *InMemoryPlaceRepository) FindAll() ([]domain.Place, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	places := make([]domain.Place, 0, len(r.places))
	for _, place := range r.places {
		places = append(places, place)
	}
	sort.Slice(places, func(i, j int) bool { return places[i].ID < places[j].ID })
	return places, nil
}

type InMemoryTransportNodeRepository struct {
	mu    sync.RWMutex
	nodes map[domain.TransportNodeID]domain.TransportNode
}

func NewInMemoryTransportNodeRepository() *InMemoryTransportNodeRepository {
	return &InMemoryTransportNodeRepository{nodes: make(map[domain.TransportNodeID]domain.TransportNode)}
}

func (r *InMemoryTransportNodeRepository) Save(node domain.TransportNode) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, exists := r.nodes[node.ID]; exists {
		return fmt.Errorf("transport node already exists: %s", node.ID)
	}
	r.nodes[node.ID] = node
	return nil
}

func (r *InMemoryTransportNodeRepository) FindByID(id domain.TransportNodeID) (*domain.TransportNode, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	node, exists := r.nodes[id]
	if !exists {
		return nil, nil
	}
	return &node, nil
}

func (r *InMemoryTransportNodeRepository) FindByPlaceID(placeID domain.PlaceID) ([]domain.TransportNode, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	nodes := make([]domain.TransportNode, 0)
	for _, node := range r.nodes {
		if node.PlaceID == placeID {
			nodes = append(nodes, node)
		}
	}
	sort.Slice(nodes, func(i, j int) bool { return nodes[i].ID < nodes[j].ID })
	return nodes, nil
}

type InMemoryIdempotencyStore struct {
	mu      sync.RWMutex
	records map[string]IdempotencyRecord
}

func NewInMemoryIdempotencyStore() *InMemoryIdempotencyStore {
	return &InMemoryIdempotencyStore{records: make(map[string]IdempotencyRecord)}
}

func (s *InMemoryIdempotencyStore) Get(key string) (*IdempotencyRecord, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	record, exists := s.records[key]
	if !exists {
		return nil, false
	}
	return &record, true
}

func (s *InMemoryIdempotencyStore) Put(key string, record IdempotencyRecord) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if existing, exists := s.records[key]; exists && existing.RequestHash != record.RequestHash {
		return fmt.Errorf("idempotency key reused with different request")
	}
	s.records[key] = record
	return nil
}

type NoopPublisher struct{}

func NewNoopPublisher() *NoopPublisher { return &NoopPublisher{} }

func (p *NoopPublisher) Publish(envelope domain.EventEnvelope) error { return nil }
