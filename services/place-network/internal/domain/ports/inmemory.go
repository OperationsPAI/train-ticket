package ports

import (
	"context"
	"fmt"
	"sort"
	"sync"

	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

type InMemoryPlaceRepository struct {
	mu     sync.RWMutex
	places map[domain.PlaceID]domain.Place
	// seq preserves insertion order: map iteration is randomized and
	// same-millisecond UUIDv7 ids do not sort by creation, but paginated
	// listings need a stable oldest-first order.
	seq     map[domain.PlaceID]uint64
	nextSeq uint64
}

func NewInMemoryPlaceRepository() *InMemoryPlaceRepository {
	return &InMemoryPlaceRepository{
		places: make(map[domain.PlaceID]domain.Place),
		seq:    make(map[domain.PlaceID]uint64),
	}
}

func (r *InMemoryPlaceRepository) Save(_ context.Context, place domain.Place) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, exists := r.places[place.ID]; exists {
		return fmt.Errorf("place already exists: %s", place.ID)
	}
	r.places[place.ID] = place
	r.seq[place.ID] = r.nextSeq
	r.nextSeq++
	return nil
}

func (r *InMemoryPlaceRepository) FindByID(_ context.Context, id domain.PlaceID) (*domain.Place, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	place, exists := r.places[id]
	if !exists {
		return nil, nil
	}
	return &place, nil
}

func (r *InMemoryPlaceRepository) FindPage(_ context.Context, filter PlaceListFilter) (PlacePage, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	places := make([]domain.Place, 0, len(r.places))
	for _, place := range r.places {
		if filter.Status == "" || string(place.Status) == filter.Status {
			places = append(places, place)
		}
	}
	sort.Slice(places, func(i, j int) bool { return r.seq[places[i].ID] < r.seq[places[j].ID] })
	limit := filter.Limit
	if limit < 0 {
		limit = 0
	}
	offset := filter.Offset
	if offset < 0 {
		offset = 0
	}
	if offset > len(places) {
		offset = len(places)
	}
	end := offset + limit
	if end > len(places) {
		end = len(places)
	}
	return PlacePage{Items: append([]domain.Place(nil), places[offset:end]...), Total: len(places)}, nil
}

type InMemoryTransportNodeRepository struct {
	mu    sync.RWMutex
	nodes map[domain.TransportNodeID]domain.TransportNode
}

func NewInMemoryTransportNodeRepository() *InMemoryTransportNodeRepository {
	return &InMemoryTransportNodeRepository{nodes: make(map[domain.TransportNodeID]domain.TransportNode)}
}

func (r *InMemoryTransportNodeRepository) Save(_ context.Context, node domain.TransportNode) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, exists := r.nodes[node.ID]; exists {
		return fmt.Errorf("transport node already exists: %s", node.ID)
	}
	r.nodes[node.ID] = node
	return nil
}

func (r *InMemoryTransportNodeRepository) FindByID(_ context.Context, id domain.TransportNodeID) (*domain.TransportNode, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	node, exists := r.nodes[id]
	if !exists {
		return nil, nil
	}
	return &node, nil
}

func (r *InMemoryTransportNodeRepository) FindByPlaceID(_ context.Context, placeID domain.PlaceID) ([]domain.TransportNode, error) {
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
