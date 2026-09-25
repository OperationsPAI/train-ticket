package postgres

import (
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
	"github.com/trainticket/greenfield/services/payment-channel/internal/domain"
)

type StatementLineBackfillResult struct {
	Statements int
	Lines      int64
	Complete   bool
}

// BackfillLegacyStatementLines moves at most maxStatements legacy JSON line
// arrays into the immutable normalized table. Each statement is committed in a
// separate transaction so the operation is resumable and does not hold locks
// across an entire retained history.
func (r *Repository) BackfillLegacyStatementLines(ctx context.Context, maxStatements int) (StatementLineBackfillResult, error) {
	if maxStatements <= 0 {
		maxStatements = 10
	}
	if maxStatements > 100 {
		maxStatements = 100
	}
	result := StatementLineBackfillResult{}
	for result.Statements < maxStatements {
		var migrated bool
		var lineCount int64
		err := r.tx.Within(ctx, func(tx context.Context) error {
			var statementID string
			var expectedLines int64
			var expectedDigest string
			var generationFingerprint string
			err := r.tx.DBFor(tx).QueryRow(tx, `SELECT id, jsonb_array_length(data->'lines')::bigint,
				       md5((data->'lines')::text),
				       CASE WHEN EXISTS (
				           SELECT 1 FROM jsonb_array_elements(data->'lines') line
				           WHERE line->>'faultSeedRef'=(data->>'seedVersion') || ':AMOUNT_MISMATCH'
				       ) THEN $2 ELSE $3 END
				FROM channel_statement_snapshots
				WHERE data ? 'lines' AND COALESCE(data->>'lineStorage', '')<>$1
				ORDER BY data->>'createdAt', id FOR UPDATE SKIP LOCKED LIMIT 1`,
				domain.StatementLineStorageNormalized,
				domain.Hash("statement-generation-v1", []string{"AMOUNT_MISMATCH"}),
				domain.Hash("statement-generation-v1", []string{}),
			).Scan(&statementID, &expectedLines, &expectedDigest, &generationFingerprint)
			if errors.Is(err, pgx.ErrNoRows) {
				return nil
			}
			if err != nil {
				return err
			}
			migrated = true
			if _, err := r.tx.DBFor(tx).Exec(tx, `INSERT INTO channel_statement_lines
				(statement_id, line_ordinal, statement_line_id, line_type, source_id, evidence_hash, occurred_at, data)
				SELECT snapshot.id, legacy.ordinal, legacy.line->>'statementLineId', legacy.line->>'lineType',
				       COALESCE(
				           NULLIF(CASE WHEN legacy.line->>'lineType'='REFUND' THEN legacy.line->>'channelRefundId' ELSE legacy.line->>'channelOrderId' END, ''),
				           legacy.line->>'statementLineId'
				       ),
				       legacy.line->>'evidenceHash', (legacy.line->>'occurredAt')::timestamptz, legacy.line
				FROM channel_statement_snapshots snapshot,
				     LATERAL jsonb_array_elements(snapshot.data->'lines') WITH ORDINALITY AS legacy(line, ordinal)
				WHERE snapshot.id=$1
				ON CONFLICT (statement_id, line_ordinal) DO NOTHING`, statementID); err != nil {
				return fmt.Errorf("copy statement %s lines: %w", statementID, err)
			}
			if err := r.tx.DBFor(tx).QueryRow(tx, `SELECT count(*) FROM channel_statement_lines WHERE statement_id=$1`, statementID).Scan(&lineCount); err != nil {
				return err
			}
			if lineCount != expectedLines {
				return fmt.Errorf("statement %s normalized line count %d does not match JSON line count %d", statementID, lineCount, expectedLines)
			}
			var normalizedDigest string
			if err := r.tx.DBFor(tx).QueryRow(tx, `SELECT md5(COALESCE(jsonb_agg(data ORDER BY line_ordinal), '[]'::jsonb)::text)
				FROM channel_statement_lines WHERE statement_id=$1`, statementID).Scan(&normalizedDigest); err != nil {
				return err
			}
			if normalizedDigest != expectedDigest {
				return fmt.Errorf("statement %s normalized line content digest %s does not match JSON digest %s", statementID, normalizedDigest, expectedDigest)
			}
			tag, err := r.tx.DBFor(tx).Exec(tx, `UPDATE channel_statement_snapshots
				SET data=jsonb_set(
				        jsonb_set(data - 'lines', '{lineStorage}', to_jsonb($2::text), true),
				        '{generationFingerprint}', to_jsonb($3::text), true
				    )
				WHERE id=$1 AND data ? 'lines' AND COALESCE(data->>'lineStorage', '')<>$2`, statementID, domain.StatementLineStorageNormalized, generationFingerprint)
			if err != nil {
				return err
			}
			if tag.RowsAffected() != 1 {
				return fmt.Errorf("statement %s changed during line normalization", statementID)
			}
			if _, err := r.tx.DBFor(tx).Exec(tx, `INSERT INTO channel_statement_identities (identity_key, statement_id)
				SELECT concat_ws(chr(31), data->>'channel', data->>'statementDate', data->>'currency', data->>'seedVersion'), id
				FROM channel_statement_snapshots WHERE id=$1
				ON CONFLICT (identity_key) DO NOTHING`, statementID); err != nil {
				return err
			}
			return nil
		})
		if err != nil {
			return result, err
		}
		if !migrated {
			result.Complete = true
			return result, nil
		}
		result.Statements++
		result.Lines += lineCount
	}
	return result, nil
}
