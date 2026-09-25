package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/jackc/pgx/v5"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/payment-channel/internal/application"
	"github.com/trainticket/greenfield/services/payment-channel/internal/domain"
	"strings"
	"time"
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
	if _, inTransaction := ctx.Value(txKey{}).(storage.DBTX); inTransaction {
		var locked string
		if err := r.tx.DBFor(ctx).QueryRow(ctx, "SELECT id FROM channel_order_snapshots WHERE id=$1 FOR UPDATE", id).Scan(&locked); err != nil {
			return nil, err
		}
	}
	return get[domain.ChannelOrder](ctx, r.snap(ctx, "channel_order_snapshots"), id)
}
func (r *Repository) SaveRefund(ctx context.Context, o *domain.ChannelRefund) error {
	return conflict(r.save(ctx, "channel_refund_snapshots", o.ChannelRefundID, o))
}
func (r *Repository) UpdateRefund(ctx context.Context, o *domain.ChannelRefund, e int64) error {
	return conflict(r.update(ctx, "channel_refund_snapshots", o.ChannelRefundID, e, o))
}
func (r *Repository) GetRefund(ctx context.Context, id string) (*domain.ChannelRefund, error) {
	if _, inTransaction := ctx.Value(txKey{}).(storage.DBTX); inTransaction {
		var locked string
		if err := r.tx.DBFor(ctx).QueryRow(ctx, "SELECT id FROM channel_refund_snapshots WHERE id=$1 FOR UPDATE", id).Scan(&locked); err != nil {
			return nil, err
		}
	}
	return get[domain.ChannelRefund](ctx, r.snap(ctx, "channel_refund_snapshots"), id)
}
func (r *Repository) FindRefundForOrder(ctx context.Context, orderID, refundID string) (*domain.ChannelRefund, error) {
	rows, err := r.tx.DBFor(ctx).Query(ctx, `SELECT data FROM channel_refund_snapshots WHERE data->>'channelOrderId'=$1 AND data->>'refundId'=$2 ORDER BY id LIMIT 2`, orderID, refundID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var found *domain.ChannelRefund
	for rows.Next() {
		if found != nil {
			return nil, fmt.Errorf("multiple channel refunds share business refund identity")
		}
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		found, err = decode[domain.ChannelRefund](raw)
		if err != nil {
			return nil, err
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return found, nil
}
func (r *Repository) SaveStatement(ctx context.Context, o *domain.ChannelStatement) error {
	summary := *o
	summary.Lines = nil
	return conflict(r.save(ctx, "channel_statement_snapshots", o.ChannelStatementID, &summary))
}
func (r *Repository) UpdateStatement(ctx context.Context, o *domain.ChannelStatement, e int64) error {
	summary := *o
	summary.Lines = nil
	b, err := json.Marshal(&summary)
	if err != nil {
		return err
	}
	tag, err := r.tx.DBFor(ctx).Exec(ctx, `UPDATE channel_statement_snapshots
		SET version=version+1,
		    data=$2::jsonb || CASE WHEN data ? 'lines' THEN jsonb_build_object('lines', data->'lines') ELSE '{}'::jsonb END,
		    updated_at=now()
		WHERE id=$1 AND version=$3`, o.ChannelStatementID, b, e)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return conflict(storage.ErrConflict)
	}
	return nil
}
func (r *Repository) GetStatement(ctx context.Context, id string) (*domain.ChannelStatement, error) {
	var raw json.RawMessage
	if err := r.tx.DBFor(ctx).QueryRow(ctx, `SELECT data - 'lines' FROM channel_statement_snapshots WHERE id=$1`, id).Scan(&raw); err != nil {
		return nil, err
	}
	return decode[domain.ChannelStatement](raw)
}

func (r *Repository) LockStatement(ctx context.Context, id string) (*domain.ChannelStatement, error) {
	if _, inTransaction := ctx.Value(txKey{}).(storage.DBTX); !inTransaction {
		return nil, fmt.Errorf("locking a channel statement requires a transaction")
	}
	var raw json.RawMessage
	if err := r.tx.DBFor(ctx).QueryRow(ctx, `SELECT data - 'lines' FROM channel_statement_snapshots WHERE id=$1 FOR UPDATE`, id).Scan(&raw); err != nil {
		return nil, err
	}
	return decode[domain.ChannelStatement](raw)
}

func (r *Repository) ClaimStatementForGeneration(ctx context.Context, channel, date, currency, seed, candidateID string) (*domain.ChannelStatement, error) {
	identity := domain.StatementIdentityKey(channel, date, currency, seed)
	if _, inTransaction := ctx.Value(txKey{}).(storage.DBTX); !inTransaction {
		return nil, fmt.Errorf("statement generation requires a transaction")
	}
	if _, err := r.tx.DBFor(ctx).Exec(ctx, `SET TRANSACTION ISOLATION LEVEL REPEATABLE READ`); err != nil {
		return nil, err
	}
	tag, err := r.tx.DBFor(ctx).Exec(ctx, `INSERT INTO channel_statement_identities (identity_key, statement_id)
		VALUES ($1, $2) ON CONFLICT (identity_key) DO NOTHING`, identity, candidateID)
	if err != nil {
		return nil, err
	}
	if tag.RowsAffected() == 1 {
		return nil, nil
	}
	var raw json.RawMessage
	err = r.tx.DBFor(ctx).QueryRow(ctx, `SELECT statement.data - 'lines'
		FROM channel_statement_identities identity
		JOIN channel_statement_snapshots statement ON statement.id=identity.statement_id
		WHERE identity.identity_key=$1`, identity).Scan(&raw)
	if err != nil {
		return nil, err
	}
	return decode[domain.ChannelStatement](raw)
}

func (r *Repository) SaveStatementLines(ctx context.Context, statementID string, firstOrdinal int64, lines []domain.StatementLine) error {
	if len(lines) == 0 {
		return nil
	}
	payload, err := json.Marshal(lines)
	if err != nil {
		return err
	}
	tag, err := r.tx.DBFor(ctx).Exec(ctx, `INSERT INTO channel_statement_lines
		(statement_id, line_ordinal, statement_line_id, line_type, source_id, evidence_hash, occurred_at, data)
		SELECT $1, $2 + ordinal - 1, line->>'statementLineId', line->>'lineType',
		       CASE WHEN line->>'lineType'='REFUND' THEN line->>'channelRefundId' ELSE line->>'channelOrderId' END,
		       line->>'evidenceHash', (line->>'occurredAt')::timestamptz, line
		FROM jsonb_array_elements($3::jsonb) WITH ORDINALITY AS input(line, ordinal)`, statementID, firstOrdinal, payload)
	if err != nil {
		return err
	}
	if tag.RowsAffected() != int64(len(lines)) {
		return fmt.Errorf("stored %d of %d statement lines", tag.RowsAffected(), len(lines))
	}
	return nil
}

func (r *Repository) ListStatementLines(ctx context.Context, statementID string, after int64, limit int) ([]domain.StatementLine, error) {
	rows, err := r.tx.DBFor(ctx).Query(ctx, `SELECT line FROM (
		SELECT normalized.data AS line, normalized.line_ordinal AS ordinal
		FROM channel_statement_snapshots snapshot
		JOIN channel_statement_lines normalized ON normalized.statement_id=snapshot.id
		WHERE snapshot.id=$1 AND snapshot.data->>'lineStorage'='NORMALIZED_V1' AND normalized.line_ordinal>$2
		UNION ALL
		SELECT legacy.line, legacy.ordinal
		FROM channel_statement_snapshots snapshot,
		     LATERAL jsonb_array_elements(COALESCE(snapshot.data->'lines', '[]'::jsonb)) WITH ORDINALITY AS legacy(line, ordinal)
		WHERE snapshot.id=$1 AND COALESCE(snapshot.data->>'lineStorage', '')<>'NORMALIZED_V1' AND legacy.ordinal>$2
	) statement_lines ORDER BY ordinal LIMIT $3`, statementID, after, limit)
	if err != nil {
		return nil, err
	}
	return decodeStatementLineRows(rows)
}

func (r *Repository) GetStatementLine(ctx context.Context, statementID, lineID string) (*domain.StatementLine, error) {
	var raw json.RawMessage
	err := r.tx.DBFor(ctx).QueryRow(ctx, `SELECT line FROM (
		SELECT normalized.data AS line
		FROM channel_statement_snapshots snapshot
		JOIN channel_statement_lines normalized ON normalized.statement_id=snapshot.id
		WHERE snapshot.id=$1 AND snapshot.data->>'lineStorage'='NORMALIZED_V1'
		  AND normalized.statement_line_id=$2
		UNION ALL
		SELECT legacy.line
		FROM channel_statement_snapshots snapshot,
		     LATERAL jsonb_array_elements(COALESCE(snapshot.data->'lines', '[]'::jsonb)) AS legacy(line)
		WHERE snapshot.id=$1 AND COALESCE(snapshot.data->>'lineStorage', '')<>'NORMALIZED_V1'
		  AND legacy.line->>'statementLineId'=$2
	) statement_line LIMIT 1`, statementID, lineID).Scan(&raw)
	if err != nil {
		return nil, err
	}
	return decode[domain.StatementLine](raw)
}

func decodeStatementLineRows(rows pgx.Rows) ([]domain.StatementLine, error) {
	defer rows.Close()
	out := []domain.StatementLine{}
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		line, err := decode[domain.StatementLine](raw)
		if err != nil {
			return nil, err
		}
		out = append(out, *line)
	}
	return out, rows.Err()
}
func (r *Repository) SaveDiscrepancy(ctx context.Context, o *domain.ReconciliationDiscrepancy) error {
	return conflict(r.save(ctx, "channel_discrepancy_snapshots", o.DiscrepancyID, o))
}
func (r *Repository) UpdateDiscrepancy(ctx context.Context, o *domain.ReconciliationDiscrepancy, e int64) error {
	return conflict(r.update(ctx, "channel_discrepancy_snapshots", o.DiscrepancyID, e, o))
}
func (r *Repository) GetDiscrepancy(ctx context.Context, id string) (*domain.ReconciliationDiscrepancy, error) {
	if _, inTransaction := ctx.Value(txKey{}).(storage.DBTX); inTransaction {
		var locked string
		if err := r.tx.DBFor(ctx).QueryRow(ctx, "SELECT id FROM channel_discrepancy_snapshots WHERE id=$1 FOR UPDATE", id).Scan(&locked); err != nil {
			return nil, err
		}
	}
	return get[domain.ReconciliationDiscrepancy](ctx, r.snap(ctx, "channel_discrepancy_snapshots"), id)
}

func (r *Repository) ListPaymentFactsForStatement(ctx context.Context, channel, date, currency, afterID string, limit int, cutoff time.Time) ([]application.StatementPaymentFact, error) {
	rows, err := r.tx.DBFor(ctx).Query(ctx, `SELECT id, data->>'paymentIntentId', data->>'channelTransactionId',
		       data->'amount'->>'currency', (data->'amount'->>'minorUnits')::bigint,
		       (data->>'completedAt')::timestamptz
		FROM channel_order_snapshots
		WHERE data->>'status'='SUCCEEDED' AND data->>'channel'=$1 AND data->'amount'->>'currency'=$2
		  AND left(data->>'completedAt', 10)=$3 AND id>$4 AND (data->>'completedAt')::timestamptz <= $5
		ORDER BY id LIMIT $6`, channel, currency, date, afterID, cutoff, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []application.StatementPaymentFact{}
	for rows.Next() {
		var fact application.StatementPaymentFact
		if err := rows.Scan(&fact.ChannelOrderID, &fact.PaymentIntentID, &fact.ChannelTransactionID,
			&fact.Amount.Currency, &fact.Amount.MinorUnits, &fact.CompletedAt); err != nil {
			return nil, err
		}
		out = append(out, fact)
	}
	return out, rows.Err()
}
func (r *Repository) ListRefundFactsForStatement(ctx context.Context, channel, date, currency, afterID string, limit int, cutoff time.Time) ([]application.StatementRefundFact, error) {
	rows, err := r.tx.DBFor(ctx).Query(ctx, `SELECT data->>'channelOrderId', id, data->>'paymentIntentId', data->>'refundId',
		       data->>'channelRefundTransactionId', data->'amount'->>'currency',
		       (data->'amount'->>'minorUnits')::bigint, (data->>'completedAt')::timestamptz
		FROM channel_refund_snapshots
		WHERE data->>'status'='SUCCEEDED' AND data->>'channel'=$1 AND data->'amount'->>'currency'=$2
		  AND left(data->>'completedAt', 10)=$3 AND id>$4 AND (data->>'completedAt')::timestamptz <= $5
		ORDER BY id LIMIT $6`, channel, currency, date, afterID, cutoff, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []application.StatementRefundFact{}
	for rows.Next() {
		var fact application.StatementRefundFact
		if err := rows.Scan(&fact.ChannelOrderID, &fact.ChannelRefundID, &fact.PaymentIntentID, &fact.RefundID,
			&fact.ChannelRefundTransactionID, &fact.Amount.Currency, &fact.Amount.MinorUnits, &fact.CompletedAt); err != nil {
			return nil, err
		}
		out = append(out, fact)
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
	rows, err := db.Query(ctx, "SELECT data - 'lines' FROM channel_statement_snapshots "+where+fmt.Sprintf(" ORDER BY data->>'statementDate' DESC, id LIMIT $%d OFFSET $%d", len(args)-1, len(args)), args...)
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
