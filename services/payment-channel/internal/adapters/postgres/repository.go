package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/payment-channel/internal/application"
	"github.com/trainticket/greenfield/services/payment-channel/internal/domain"
	"strings"
)

type Repository struct{ tx *Transactor }

func NewRepository(tx *Transactor) *Repository { return &Repository{tx: tx} }
func (r *Repository) snap(ctx context.Context, table string) *storage.SnapshotRepository {
	return storage.NewSnapshotRepository(r.tx.DBFor(ctx), table)
}
func (r *Repository) save(ctx context.Context, table, id string, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return r.snap(ctx, table).Insert(ctx, id, b)
}
func (r *Repository) update(ctx context.Context, table, id string, expected int64, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	_, err = r.snap(ctx, table).Save(ctx, id, expected, b)
	return err
}
func decode[T any](raw json.RawMessage) (*T, error) {
	var v T
	err := json.Unmarshal(raw, &v)
	return &v, err
}
func get[T any](ctx context.Context, s *storage.SnapshotRepository, id string) (*T, error) {
	snap, ok, err := s.Get(ctx, id)
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, fmt.Errorf("not found")
	}
	return decode[T](snap.Data)
}
func conflict(err error) error {
	if errors.Is(err, storage.ErrConflict) {
		return fmt.Errorf("optimistic concurrency conflict")
	}
	return err
}

func (r *Repository) SaveOrder(ctx context.Context, o *domain.ChannelOrder) error {
	return conflict(r.save(ctx, "channel_order_snapshots", o.ChannelOrderID, o))
}
func (r *Repository) UpdateOrder(ctx context.Context, o *domain.ChannelOrder, e int64) error {
	return conflict(r.update(ctx, "channel_order_snapshots", o.ChannelOrderID, e, o))
}
func (r *Repository) GetOrder(ctx context.Context, id string) (*domain.ChannelOrder, error) {
	return get[domain.ChannelOrder](ctx, r.snap(ctx, "channel_order_snapshots"), id)
}
func (r *Repository) SaveRefund(ctx context.Context, o *domain.ChannelRefund) error {
	return conflict(r.save(ctx, "channel_refund_snapshots", o.ChannelRefundID, o))
}
func (r *Repository) UpdateRefund(ctx context.Context, o *domain.ChannelRefund, e int64) error {
	return conflict(r.update(ctx, "channel_refund_snapshots", o.ChannelRefundID, e, o))
}
func (r *Repository) GetRefund(ctx context.Context, id string) (*domain.ChannelRefund, error) {
	return get[domain.ChannelRefund](ctx, r.snap(ctx, "channel_refund_snapshots"), id)
}
func (r *Repository) SaveStatement(ctx context.Context, o *domain.ChannelStatement) error {
	return conflict(r.save(ctx, "channel_statement_snapshots", o.ChannelStatementID, o))
}
func (r *Repository) UpdateStatement(ctx context.Context, o *domain.ChannelStatement, e int64) error {
	return conflict(r.update(ctx, "channel_statement_snapshots", o.ChannelStatementID, e, o))
}
func (r *Repository) GetStatement(ctx context.Context, id string) (*domain.ChannelStatement, error) {
	return get[domain.ChannelStatement](ctx, r.snap(ctx, "channel_statement_snapshots"), id)
}
func (r *Repository) SaveDiscrepancy(ctx context.Context, o *domain.ReconciliationDiscrepancy) error {
	return conflict(r.save(ctx, "channel_discrepancy_snapshots", o.DiscrepancyID, o))
}
func (r *Repository) UpdateDiscrepancy(ctx context.Context, o *domain.ReconciliationDiscrepancy, e int64) error {
	return conflict(r.update(ctx, "channel_discrepancy_snapshots", o.DiscrepancyID, e, o))
}
func (r *Repository) GetDiscrepancy(ctx context.Context, id string) (*domain.ReconciliationDiscrepancy, error) {
	return get[domain.ReconciliationDiscrepancy](ctx, r.snap(ctx, "channel_discrepancy_snapshots"), id)
}

func (r *Repository) ListOrdersForStatement(ctx context.Context, channel, date, currency string) ([]domain.ChannelOrder, error) {
	rows, err := r.tx.DBFor(ctx).Query(ctx, `SELECT data FROM channel_order_snapshots WHERE data->>'channel'=$1 AND data->'amount'->>'currency'=$2 ORDER BY id`, channel, currency)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []domain.ChannelOrder{}
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		o, err := decode[domain.ChannelOrder](raw)
		if err != nil {
			return nil, err
		}
		out = append(out, *o)
	}
	return out, rows.Err()
}
func (r *Repository) ListRefundsForStatement(ctx context.Context, channel, date, currency string) ([]domain.ChannelRefund, error) {
	rows, err := r.tx.DBFor(ctx).Query(ctx, `SELECT data FROM channel_refund_snapshots WHERE data->>'channel'=$1 AND data->'amount'->>'currency'=$2 ORDER BY id`, channel, currency)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []domain.ChannelRefund{}
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		o, err := decode[domain.ChannelRefund](raw)
		if err != nil {
			return nil, err
		}
		out = append(out, *o)
	}
	return out, rows.Err()
}
func (r *Repository) SumRefundsForOrder(ctx context.Context, orderID string) (int64, error) {
	rows, err := r.tx.DBFor(ctx).Query(ctx, `SELECT data FROM channel_refund_snapshots WHERE data->>'channelOrderId'=$1`, orderID)
	if err != nil {
		return 0, err
	}
	defer rows.Close()
	var sum int64
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return 0, err
		}
		rf, err := decode[domain.ChannelRefund](raw)
		if err != nil {
			return 0, err
		}
		if rf.Status != domain.StatusFailed {
			sum += rf.Amount.MinorUnits
		}
	}
	return sum, rows.Err()
}

func (r *Repository) ListStatements(ctx context.Context, f application.StatementFilter) ([]domain.ChannelStatement, int, error) {
	where, args := where([]cond{{"data->>'channel'", f.Channel}, {"data->>'statementDate'", f.StatementDate}, {"data->>'currency'", f.Currency}, {"data->>'status'", f.Status}})
	db := r.tx.DBFor(ctx)
	var total int
	if err := db.QueryRow(ctx, "SELECT count(*)::int FROM channel_statement_snapshots "+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	args = append(args, f.Limit, f.Offset)
	rows, err := db.Query(ctx, "SELECT data FROM channel_statement_snapshots "+where+fmt.Sprintf(" ORDER BY data->>'statementDate' DESC, id LIMIT $%d OFFSET $%d", len(args)-1, len(args)), args...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []domain.ChannelStatement{}
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return nil, 0, err
		}
		o, err := decode[domain.ChannelStatement](raw)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, *o)
	}
	return out, total, rows.Err()
}
func (r *Repository) ListDiscrepancies(ctx context.Context, f application.DiscrepancyFilter) ([]domain.ReconciliationDiscrepancy, int, error) {
	where, args := where([]cond{{"data->>'channelStatementId'", f.ChannelStatementID}, {"data->>'status'", f.Status}, {"data->>'differenceType'", f.DifferenceType}})
	db := r.tx.DBFor(ctx)
	var total int
	if err := db.QueryRow(ctx, "SELECT count(*)::int FROM channel_discrepancy_snapshots "+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	args = append(args, f.Limit, f.Offset)
	rows, err := db.Query(ctx, "SELECT data FROM channel_discrepancy_snapshots "+where+fmt.Sprintf(" ORDER BY data->>'openedAt' DESC, id LIMIT $%d OFFSET $%d", len(args)-1, len(args)), args...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []domain.ReconciliationDiscrepancy{}
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return nil, 0, err
		}
		o, err := decode[domain.ReconciliationDiscrepancy](raw)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, *o)
	}
	return out, total, rows.Err()
}

type cond struct{ expr, val string }

func where(cs []cond) (string, []any) {
	parts := []string{}
	args := []any{}
	for _, c := range cs {
		if strings.TrimSpace(c.val) != "" {
			args = append(args, c.val)
			parts = append(parts, fmt.Sprintf("%s=$%d", c.expr, len(args)))
		}
	}
	if len(parts) == 0 {
		return "", args
	}
	return "WHERE " + strings.Join(parts, " AND "), args
}

var _ application.Repository = (*Repository)(nil)
