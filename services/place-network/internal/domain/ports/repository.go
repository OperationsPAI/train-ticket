package ports

import "github.com/trainticket/greenfield/services/place-network/internal/domain"

type PlaceRepository interface {
	Save(place domain.Place) error
	FindByID(id domain.PlaceID) (*domain.Place, error)
	FindAll() ([]domain.Place, error)
}

type TransportNodeRepository interface {
	Save(node domain.TransportNode) error
	FindByID(id domain.TransportNodeID) (*domain.TransportNode, error)
	FindByPlaceID(placeID domain.PlaceID) ([]domain.TransportNode, error)
}

type IdempotencyRecord struct {
	RequestHash string
	StatusCode  int
	Response    any
}

type IdempotencyStore interface {
	Get(key string) (*IdempotencyRecord, bool)
	Put(key string, record IdempotencyRecord) error
}
