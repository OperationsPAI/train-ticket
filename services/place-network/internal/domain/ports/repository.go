package ports

import (
	"context"

	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

type PlaceRepository interface {
	Save(ctx context.Context, place domain.Place) error
	FindByID(ctx context.Context, id domain.PlaceID) (*domain.Place, error)
	FindPage(ctx context.Context, filter PlaceListFilter) (PlacePage, error)
}

type TransportNodeRepository interface {
	Save(ctx context.Context, node domain.TransportNode) error
	FindByID(ctx context.Context, id domain.TransportNodeID) (*domain.TransportNode, error)
	FindByPlaceID(ctx context.Context, placeID domain.PlaceID) ([]domain.TransportNode, error)
}

type PlaceListFilter struct {
	Limit  int
	Offset int
	Status string
}

type PlacePage struct {
	Items []domain.Place
	Total int
}
