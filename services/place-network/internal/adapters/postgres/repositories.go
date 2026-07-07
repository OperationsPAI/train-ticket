package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"

	"github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/place-network/internal/domain"
	"github.com/trainticket/greenfield/services/place-network/internal/domain/ports"
)

type staticDB struct{ db storage.DBTX }

func (s staticDB) DB() storage.DBTX                                      { return s.db }
func (s staticDB) DBFor(context.Context) storage.DBTX                    { return s.db }
func dbFor(ctx context.Context, provider ContextDBProvider) storage.DBTX { return provider.DBFor(ctx) }

type PlaceRepository struct{ db ContextDBProvider }

func NewPlaceRepository(db storage.DBTX) *PlaceRepository {
	return NewPlaceRepositoryWithProvider(staticDB{db: db})
}
func NewPlaceRepositoryWithProvider(db ContextDBProvider) *PlaceRepository {
	return &PlaceRepository{db: db}
}

func (r *PlaceRepository) Save(ctx context.Context, place domain.Place) error {
	data, err := json.Marshal(placeSnapshotFromDomain(place))
	if err != nil {
		return err
	}
	if err := storage.NewSnapshotRepository(dbFor(ctx, r.db), "place_snapshots").Insert(ctx, string(place.ID), data); err != nil {
		if errors.Is(err, storage.ErrConflict) {
			return fmt.Errorf("place already exists: %s", place.ID)
		}
		return err
	}
	return nil
}

func (r *PlaceRepository) FindByID(ctx context.Context, id domain.PlaceID) (*domain.Place, error) {
	snap, ok, err := storage.NewSnapshotRepository(dbFor(ctx, r.db), "place_snapshots").Get(ctx, string(id))
	if err != nil || !ok {
		return nil, err
	}
	place, err := decodePlace(snap.Data)
	if err != nil {
		return nil, err
	}
	return &place, nil
}

func (r *PlaceRepository) FindPage(ctx context.Context, filter ports.PlaceListFilter) (ports.PlacePage, error) {
	status := strings.TrimSpace(filter.Status)
	where := ""
	countArgs := []any{}
	selectArgs := []any{filter.Limit, filter.Offset}
	if status != "" {
		where = "WHERE data->>'status' = $1"
		countArgs = append(countArgs, status)
		selectArgs = append(selectArgs, status)
	}

	db := dbFor(ctx, r.db)
	var page ports.PlacePage
	if err := db.QueryRow(ctx, "SELECT count(*)::int FROM place_snapshots "+where, countArgs...).Scan(&page.Total); err != nil {
		return ports.PlacePage{}, err
	}

	selectWhere := where
	if status != "" {
		selectWhere = "WHERE data->>'status' = $3"
	}
	query := fmt.Sprintf(`SELECT data
FROM place_snapshots
%s
ORDER BY data->>'createdAt', data->>'id'
LIMIT $1 OFFSET $2`, selectWhere)
	rows, err := db.Query(ctx, query, selectArgs...)
	if err != nil {
		return ports.PlacePage{}, err
	}
	defer rows.Close()
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return ports.PlacePage{}, err
		}
		place, err := decodePlace(raw)
		if err != nil {
			return ports.PlacePage{}, err
		}
		page.Items = append(page.Items, place)
	}
	if err := rows.Err(); err != nil {
		return ports.PlacePage{}, err
	}
	return page, nil
}

type TransportNodeRepository struct{ db ContextDBProvider }

func NewTransportNodeRepository(db storage.DBTX) *TransportNodeRepository {
	return NewTransportNodeRepositoryWithProvider(staticDB{db: db})
}
func NewTransportNodeRepositoryWithProvider(db ContextDBProvider) *TransportNodeRepository {
	return &TransportNodeRepository{db: db}
}

func (r *TransportNodeRepository) Save(ctx context.Context, node domain.TransportNode) error {
	data, err := json.Marshal(nodeSnapshotFromDomain(node))
	if err != nil {
		return err
	}
	if err := storage.NewSnapshotRepository(dbFor(ctx, r.db), "transport_node_snapshots").Insert(ctx, string(node.ID), data); err != nil {
		if errors.Is(err, storage.ErrConflict) {
			return fmt.Errorf("transport node already exists: %s", node.ID)
		}
		return err
	}
	return nil
}

func (r *TransportNodeRepository) FindByID(ctx context.Context, id domain.TransportNodeID) (*domain.TransportNode, error) {
	snap, ok, err := storage.NewSnapshotRepository(dbFor(ctx, r.db), "transport_node_snapshots").Get(ctx, string(id))
	if err != nil || !ok {
		return nil, err
	}
	node, err := decodeNode(snap.Data)
	if err != nil {
		return nil, err
	}
	return &node, nil
}

func (r *TransportNodeRepository) FindByPlaceID(ctx context.Context, placeID domain.PlaceID) ([]domain.TransportNode, error) {
	rows, err := dbFor(ctx, r.db).Query(ctx, `SELECT data FROM transport_node_snapshots WHERE data->>'placeId' = $1 ORDER BY id`, string(placeID))
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
