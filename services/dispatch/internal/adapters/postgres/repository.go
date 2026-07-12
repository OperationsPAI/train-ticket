package postgres

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/dispatch/internal/domain"
	"github.com/trainticket/greenfield/services/dispatch/internal/domain/ports"
)

type ContextDBProvider interface {
	DBFor(context.Context) storage.DBTX
}
type staticDB struct{ db storage.DBTX }

func (s staticDB) DBFor(context.Context) storage.DBTX { return s.db }

type RideRequestRepository struct{ db ContextDBProvider }

func NewRideRequestRepository(db storage.DBTX) *RideRequestRepository {
	return NewRideRequestRepositoryWithProvider(staticDB{db: db})
}
func NewRideRequestRepositoryWithProvider(db ContextDBProvider) *RideRequestRepository {
	return &RideRequestRepository{db: db}
}
func (r *RideRequestRepository) Save(ctx context.Context, ride domain.RideRequest) error {
	data, err := json.Marshal(snapshotFromDomain(ride))
	if err != nil {
		return err
	}
	return storage.NewSnapshotRepository(r.db.DBFor(ctx), "ride_request_snapshots").Insert(ctx, string(ride.ID), data)
}
func (r *RideRequestRepository) Update(ctx context.Context, ride domain.RideRequest) error {
	data, err := json.Marshal(snapshotFromDomain(ride))
	if err != nil {
		return err
	}
	_, err = storage.NewSnapshotRepository(r.db.DBFor(ctx), "ride_request_snapshots").Save(ctx, string(ride.ID), ride.Version, data)
	return err
}
func (r *RideRequestRepository) FindByID(ctx context.Context, id domain.RideRequestID) (*domain.RideRequest, error) {
	snap, ok, err := storage.NewSnapshotRepository(r.db.DBFor(ctx), "ride_request_snapshots").Get(ctx, string(id))
	if err != nil || !ok {
		return nil, err
	}
	ride, err := decodeRide(snap.Data, snap.Version)
	if err != nil {
		return nil, err
	}
	return &ride, nil
}
func (r *RideRequestRepository) FindActiveByIntent(ctx context.Context, rider, fp string) (*domain.RideRequest, error) {
	rows, err := r.db.DBFor(ctx).Query(ctx, `SELECT id, version, data FROM ride_request_snapshots WHERE data->>'riderAccountId'=$1 AND data->>'intentFingerprint'=$2 AND data->>'status' IN ('REQUESTED','MATCHING','ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','PICKED_UP','DRIVER_CANCELLED') LIMIT 1`, strings.TrimSpace(rider), strings.TrimSpace(fp))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	if !rows.Next() {
		return nil, rows.Err()
	}
	var id string
	var ver int64
	var raw json.RawMessage
	if err := rows.Scan(&id, &ver, &raw); err != nil {
		return nil, err
	}
	ride, err := decodeRide(raw, ver)
	if err != nil {
		return nil, err
	}
	return &ride, nil
}
func (r *RideRequestRepository) FindPage(ctx context.Context, f ports.RideRequestListFilter) (ports.RideRequestPage, error) {
	where := "WHERE data->>'riderAccountId'=$1"
	args := []any{f.RiderAccountID}
	if strings.TrimSpace(f.Status) != "" {
		where += " AND data->>'status'=$2"
		args = append(args, f.Status)
	}
	var total int
	if err := r.db.DBFor(ctx).QueryRow(ctx, "SELECT count(*)::int FROM ride_request_snapshots "+where, args...).Scan(&total); err != nil {
		return ports.RideRequestPage{}, err
	}
	selectArgs := append([]any{}, args...)
	selectArgs = append(selectArgs, f.Limit, f.Offset)
	limitPos := len(selectArgs) - 1
	offsetPos := len(selectArgs)
	q := fmt.Sprintf("SELECT id, version, data FROM ride_request_snapshots %s ORDER BY data->>'createdAt', data->>'rideRequestId' LIMIT $%d OFFSET $%d", where, limitPos, offsetPos)
	rows, err := r.db.DBFor(ctx).Query(ctx, q, selectArgs...)
	if err != nil {
		return ports.RideRequestPage{}, err
	}
	defer rows.Close()
	items := []domain.RideRequest{}
	for rows.Next() {
		var id string
		var ver int64
		var raw json.RawMessage
		if err := rows.Scan(&id, &ver, &raw); err != nil {
			return ports.RideRequestPage{}, err
		}
		ride, err := decodeRide(raw, ver)
		if err != nil {
			return ports.RideRequestPage{}, err
		}
		items = append(items, ride)
	}
	return ports.RideRequestPage{Items: items, Total: total}, rows.Err()
}
func (r *RideRequestRepository) FindTimedOut(ctx context.Context, now time.Time, requestTimeout, matchingTimeout time.Duration, limit int) ([]domain.RideRequest, error) {
	rows, err := r.db.DBFor(ctx).Query(ctx, `SELECT id, version, data FROM ride_request_snapshots WHERE (data->>'status'='REQUESTED' AND (data->>'updatedAt')::timestamptz < $1) OR (data->>'status'='MATCHING' AND (data->>'updatedAt')::timestamptz < $2) ORDER BY data->>'updatedAt' LIMIT $3`, now.Add(-requestTimeout), now.Add(-matchingTimeout), limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []domain.RideRequest{}
	for rows.Next() {
		var id string
		var ver int64
		var raw json.RawMessage
		if err := rows.Scan(&id, &ver, &raw); err != nil {
			return nil, err
		}
		ride, err := decodeRide(raw, ver)
		if err != nil {
			return nil, err
		}
		out = append(out, ride)
	}
	return out, rows.Err()
}
