package storage

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/jackc/pgx/v5/pgxpool"
	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	kitstorage "github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/seat-assignment/internal/domain"
)

type txKey struct{}
type Transactor struct{ pool *pgxpool.Pool }

func NewTransactor(pool *pgxpool.Pool) *Transactor { return &Transactor{pool: pool} }
func (t *Transactor) DBFor(ctx context.Context) kitstorage.DBTX {
	if tx, ok := ctx.Value(txKey{}).(kitstorage.DBTX); ok {
		return tx
	}
	return t.pool
}
func (t *Transactor) Within(ctx context.Context, fn func(context.Context) error) error {
	tx, err := t.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := fn(context.WithValue(ctx, txKey{}, tx)); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

type Repository struct{ tx *Transactor }

func NewRepository(tx *Transactor) *Repository { return &Repository{tx: tx} }
func (r *Repository) GetInventory(ctx context.Context, segmentRef, departureDate string) (*domain.SeatInventory, int64, error) {
	id := inventoryID(segmentRef, departureDate)
	snap, ok, err := kitstorage.NewSnapshotRepository(r.tx.DBFor(ctx), "seat_inventories").Get(ctx, id)
	if err != nil || !ok {
		return nil, 0, err
	}
	var inv domain.SeatInventory
	if err := json.Unmarshal(snap.Data, &inv); err != nil {
		return nil, 0, err
	}
	return &inv, snap.Version, nil
}
func (r *Repository) SaveInventory(ctx context.Context, inv *domain.SeatInventory) error {
	b, err := json.Marshal(inv)
	if err != nil {
		return err
	}
	return conflict(kitstorage.NewSnapshotRepository(r.tx.DBFor(ctx), "seat_inventories").Insert(ctx, inventoryID(inv.SegmentRef, inv.DepartureDate), b))
}
func (r *Repository) UpdateInventory(ctx context.Context, inv *domain.SeatInventory, expected int64) error {
	b, err := json.Marshal(inv)
	if err != nil {
		return err
	}
	_, err = kitstorage.NewSnapshotRepository(r.tx.DBFor(ctx), "seat_inventories").Save(ctx, inventoryID(inv.SegmentRef, inv.DepartureDate), expected, b)
	return conflict(err)
}
func (r *Repository) GetAssignment(ctx context.Context, id string) (*domain.SeatAssignment, int64, error) {
	snap, ok, err := kitstorage.NewSnapshotRepository(r.tx.DBFor(ctx), "seat_assignments").Get(ctx, id)
	if err != nil {
		return nil, 0, err
	}
	if !ok {
		return nil, 0, fmt.Errorf("not found")
	}
	var a domain.SeatAssignment
	if err := json.Unmarshal(snap.Data, &a); err != nil {
		return nil, 0, err
	}
	return &a, snap.Version, nil
}
func (r *Repository) SaveAssignment(ctx context.Context, a *domain.SeatAssignment) error {
	b, err := json.Marshal(a)
	if err != nil {
		return err
	}
	return conflict(kitstorage.NewSnapshotRepository(r.tx.DBFor(ctx), "seat_assignments").Insert(ctx, a.AssignmentId, b))
}
func (r *Repository) UpdateAssignment(ctx context.Context, a *domain.SeatAssignment, expected int64) error {
	b, err := json.Marshal(a)
	if err != nil {
		return err
	}
	_, err = kitstorage.NewSnapshotRepository(r.tx.DBFor(ctx), "seat_assignments").Save(ctx, a.AssignmentId, expected, b)
	return conflict(err)
}
func (r *Repository) FindAssignments(ctx context.Context, segmentRef, departureDate, travelerRef string) ([]domain.SeatAssignment, error) {
	parts := []string{"1=1"}
	args := []any{}
	if strings.TrimSpace(segmentRef) != "" {
		args = append(args, segmentRef)
		parts = append(parts, fmt.Sprintf("data->>'segmentRef'=$%d", len(args)))
	}
	if strings.TrimSpace(departureDate) != "" {
		args = append(args, departureDate)
		parts = append(parts, fmt.Sprintf("data->>'departureDate'=$%d", len(args)))
	}
	if strings.TrimSpace(travelerRef) != "" {
		args = append(args, travelerRef)
		parts = append(parts, fmt.Sprintf("data->>'travelerRef'=$%d", len(args)))
	}
	rows, err := r.tx.DBFor(ctx).Query(ctx, "SELECT data FROM seat_assignments WHERE "+strings.Join(parts, " AND ")+" ORDER BY id", args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []domain.SeatAssignment{}
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		var a domain.SeatAssignment
		if err := json.Unmarshal(raw, &a); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

func (r *Repository) SaveSeatMap(ctx context.Context, m *domain.ContractSeatMap) error {
	b, err := json.Marshal(m)
	if err != nil {
		return err
	}
	return conflict(kitstorage.NewSnapshotRepository(r.tx.DBFor(ctx), "seat_maps").Insert(ctx, m.SeatMapID, b))
}
func (r *Repository) UpdateSeatMap(ctx context.Context, m *domain.ContractSeatMap, expected int64) error {
	b, err := json.Marshal(m)
	if err != nil {
		return err
	}
	_, err = kitstorage.NewSnapshotRepository(r.tx.DBFor(ctx), "seat_maps").Save(ctx, m.SeatMapID, expected, b)
	return conflict(err)
}
func (r *Repository) GetSeatMap(ctx context.Context, id string) (*domain.ContractSeatMap, int64, error) {
	snap, ok, err := kitstorage.NewSnapshotRepository(r.tx.DBFor(ctx), "seat_maps").Get(ctx, id)
	if err != nil {
		return nil, 0, err
	}
	if !ok {
		return nil, 0, fmt.Errorf("not found")
	}
	var m domain.ContractSeatMap
	if err := json.Unmarshal(snap.Data, &m); err != nil {
		return nil, 0, err
	}
	return &m, snap.Version, nil
}
func (r *Repository) FindSeatMaps(ctx context.Context, scheduledServiceRef, serviceDate, status string, limit, offset int) ([]domain.ContractSeatMap, int, error) {
	parts := []string{"data->>'scheduledServiceRef'=$1", "data->>'serviceDate'=$2"}
	args := []any{scheduledServiceRef, serviceDate}
	if strings.TrimSpace(status) != "" {
		args = append(args, status)
		parts = append(parts, fmt.Sprintf("data->>'status'=$%d", len(args)))
	}
	where := strings.Join(parts, " AND ")
	var total int
	if err := r.tx.DBFor(ctx).QueryRow(ctx, "SELECT count(*) FROM seat_maps WHERE "+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	args = append(args, limit, offset)
	rows, err := r.tx.DBFor(ctx).Query(ctx, "SELECT data FROM seat_maps WHERE "+where+fmt.Sprintf(" ORDER BY updated_at DESC LIMIT $%d OFFSET $%d", len(args)-1, len(args)), args...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []domain.ContractSeatMap{}
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return nil, 0, err
		}
		var m domain.ContractSeatMap
		if err := json.Unmarshal(raw, &m); err != nil {
			return nil, 0, err
		}
		out = append(out, m)
	}
	return out, total, rows.Err()
}
func (r *Repository) SaveSeatAllocation(ctx context.Context, a *domain.ContractSeatAllocation) error {
	b, err := json.Marshal(a)
	if err != nil {
		return err
	}
	return conflict(kitstorage.NewSnapshotRepository(r.tx.DBFor(ctx), "seat_allocations").Insert(ctx, a.SeatAllocationID, b))
}
func (r *Repository) UpdateSeatAllocation(ctx context.Context, a *domain.ContractSeatAllocation, expected int64) error {
	b, err := json.Marshal(a)
	if err != nil {
		return err
	}
	_, err = kitstorage.NewSnapshotRepository(r.tx.DBFor(ctx), "seat_allocations").Save(ctx, a.SeatAllocationID, expected, b)
	return conflict(err)
}
func (r *Repository) GetSeatAllocation(ctx context.Context, id string) (*domain.ContractSeatAllocation, int64, error) {
	snap, ok, err := kitstorage.NewSnapshotRepository(r.tx.DBFor(ctx), "seat_allocations").Get(ctx, id)
	if err != nil {
		return nil, 0, err
	}
	if !ok {
		return nil, 0, fmt.Errorf("not found")
	}
	var a domain.ContractSeatAllocation
	if err := json.Unmarshal(snap.Data, &a); err != nil {
		return nil, 0, err
	}
	return &a, snap.Version, nil
}
func (r *Repository) FindSeatAllocations(ctx context.Context, segmentBookingID, status string, limit, offset int) ([]domain.ContractSeatAllocation, int, error) {
	parts := []string{"data->>'segmentBookingId'=$1"}
	args := []any{segmentBookingID}
	if strings.TrimSpace(status) != "" {
		args = append(args, status)
		parts = append(parts, fmt.Sprintf("data->>'status'=$%d", len(args)))
	}
	where := strings.Join(parts, " AND ")
	var total int
	if err := r.tx.DBFor(ctx).QueryRow(ctx, "SELECT count(*) FROM seat_allocations WHERE "+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	args = append(args, limit, offset)
	rows, err := r.tx.DBFor(ctx).Query(ctx, "SELECT data FROM seat_allocations WHERE "+where+fmt.Sprintf(" ORDER BY updated_at DESC LIMIT $%d OFFSET $%d", len(args)-1, len(args)), args...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []domain.ContractSeatAllocation{}
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return nil, 0, err
		}
		var a domain.ContractSeatAllocation
		if err := json.Unmarshal(raw, &a); err != nil {
			return nil, 0, err
		}
		out = append(out, a)
	}
	return out, total, rows.Err()
}
func (r *Repository) FindActiveSeatAllocations(ctx context.Context, scheduledServiceRef, serviceDate string) ([]domain.ContractSeatAllocation, error) {
	rows, err := r.tx.DBFor(ctx).Query(ctx, "SELECT data FROM seat_allocations WHERE data->>'scheduledServiceRef'=$1 AND data->>'serviceDate'=$2 AND data->>'status' IN ('ALLOCATED','STANDING','CONFIRMED') ORDER BY id", scheduledServiceRef, serviceDate)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []domain.ContractSeatAllocation{}
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		var a domain.ContractSeatAllocation
		if err := json.Unmarshal(raw, &a); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}
func (r *Repository) FindSeatAllocationsByHold(ctx context.Context, holdID string) ([]domain.ContractSeatAllocation, error) {
	rows, err := r.tx.DBFor(ctx).Query(ctx, "SELECT data FROM seat_allocations WHERE data->>'capacityHoldId'=$1 ORDER BY id", holdID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []domain.ContractSeatAllocation{}
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		var a domain.ContractSeatAllocation
		if err := json.Unmarshal(raw, &a); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

func inventoryID(segmentRef, date string) string { return segmentRef + "|" + date }
func conflict(err error) error {
	if errors.Is(err, kitstorage.ErrConflict) {
		return fmt.Errorf("optimistic concurrency conflict")
	}
	return err
}

type OutboxPublisher struct{ tx *Transactor }

func NewOutboxPublisher(tx *Transactor) *OutboxPublisher { return &OutboxPublisher{tx: tx} }
func (p *OutboxPublisher) Publish(ctx context.Context, env kitmsg.EventEnvelope) error {
	b, err := json.Marshal(env)
	if err != nil {
		return err
	}
	return kitstorage.NewOutboxAppender(p.tx.DBFor(ctx)).Append(ctx, kitmsg.StreamName(env.Producer), env.EventID, b)
}
