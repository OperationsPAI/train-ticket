package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"

	"github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

type DBProvider interface{ DB() storage.DBTX }
type staticDB struct{ db storage.DBTX }

func (s staticDB) DB() storage.DBTX { return s.db }

type PlaceRepository struct{ db DBProvider }

func NewPlaceRepository(db storage.DBTX) *PlaceRepository {
	return NewPlaceRepositoryWithProvider(staticDB{db: db})
}
func NewPlaceRepositoryWithProvider(db DBProvider) *PlaceRepository { return &PlaceRepository{db: db} }

func (r *PlaceRepository) Save(place domain.Place) error {
	data, err := json.Marshal(placeSnapshotFromDomain(place))
	if err != nil {
		return err
	}
	if err := storage.NewSnapshotRepository(r.db.DB(), "place_snapshots").Insert(context.Background(), string(place.ID), data); err != nil {
		if errors.Is(err, storage.ErrConflict) {
			return fmt.Errorf("place already exists: %s", place.ID)
		}
		return err
	}
	return nil
}

func (r *PlaceRepository) FindByID(id domain.PlaceID) (*domain.Place, error) {
	snap, ok, err := storage.NewSnapshotRepository(r.db.DB(), "place_snapshots").Get(context.Background(), string(id))
	if err != nil || !ok {
		return nil, err
	}
	place, err := decodePlace(snap.Data)
	if err != nil {
		return nil, err
	}
	return &place, nil
}

func (r *PlaceRepository) FindAll() ([]domain.Place, error) {
	rows, err := r.db.DB().Query(context.Background(), `SELECT data FROM place_snapshots ORDER BY (data->>'createdAt'), id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	places := []domain.Place{}
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		place, err := decodePlace(raw)
		if err != nil {
			return nil, err
		}
		places = append(places, place)
	}
	return places, rows.Err()
}

type TransportNodeRepository struct{ db DBProvider }

func NewTransportNodeRepository(db storage.DBTX) *TransportNodeRepository {
	return NewTransportNodeRepositoryWithProvider(staticDB{db: db})
}
func NewTransportNodeRepositoryWithProvider(db DBProvider) *TransportNodeRepository {
	return &TransportNodeRepository{db: db}
}

func (r *TransportNodeRepository) Save(node domain.TransportNode) error {
	data, err := json.Marshal(nodeSnapshotFromDomain(node))
	if err != nil {
		return err
	}
	if err := storage.NewSnapshotRepository(r.db.DB(), "transport_node_snapshots").Insert(context.Background(), string(node.ID), data); err != nil {
		if errors.Is(err, storage.ErrConflict) {
			return fmt.Errorf("transport node already exists: %s", node.ID)
		}
		return err
	}
	return nil
}

func (r *TransportNodeRepository) FindByID(id domain.TransportNodeID) (*domain.TransportNode, error) {
	snap, ok, err := storage.NewSnapshotRepository(r.db.DB(), "transport_node_snapshots").Get(context.Background(), string(id))
	if err != nil || !ok {
		return nil, err
	}
	node, err := decodeNode(snap.Data)
	if err != nil {
		return nil, err
	}
	return &node, nil
}

func (r *TransportNodeRepository) FindByPlaceID(placeID domain.PlaceID) ([]domain.TransportNode, error) {
	rows, err := r.db.DB().Query(context.Background(), `SELECT data FROM transport_node_snapshots WHERE data->>'placeId' = $1 ORDER BY id`, string(placeID))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	nodes := []domain.TransportNode{}
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		node, err := decodeNode(raw)
		if err != nil {
			return nil, err
		}
		nodes = append(nodes, node)
	}
	sort.Slice(nodes, func(i, j int) bool { return nodes[i].ID < nodes[j].ID })
	return nodes, rows.Err()
}
