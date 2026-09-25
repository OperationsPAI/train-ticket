package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/trainticket/greenfield/platform/go-kit/ids"
	kitstorage "github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/payment-channel/internal/application"
	"github.com/trainticket/greenfield/services/payment-channel/internal/domain"
)

const (
	// A recorded resident-load window produced 9,979 purchases and 2,643
	// refunds in 1,764.3 seconds. These rounded counts retain one full day at
	// that observed rate instead of testing a conveniently small fixture.
	liveStatementPaymentFacts = 490000
	liveStatementRefundFacts  = 130000
)

func TestLiveNormalizedStatementStorageIsBounded(t *testing.T) {
	pool := newStatementTestPool(t)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()

	seedStarted := time.Now()
	seedStatementFacts(t, ctx, pool, liveStatementPaymentFacts, liveStatementRefundFacts)
	t.Logf("seeded %d PostgreSQL statement facts in %s", liveStatementPaymentFacts+liveStatementRefundFacts, time.Since(seedStarted))

	transactor := NewTransactor(pool)
	repository := NewRepository(transactor)
	service := application.New(repository, NewOutboxPublisher(transactor), transactor.Within)
	request := application.GenerateStatementRequest{
		Channel: domain.ChannelAlipay, StatementDate: "2026-09-24", Currency: "CNY", SeedVersion: "retained-day-v1",
		OperatorRef: "ops", ReasonCode: "DAILY_CLOSE",
		CorrelationID: ids.NewCorrelationID(), SourceCommandID: ids.NewCommandID(),
	}

	generationStarted := time.Now()
	statement, err := service.GenerateStatement(ctx, request)
	if err != nil {
		t.Fatal(err)
	}
	generationDuration := time.Since(generationStarted)
	if generationDuration > 90*time.Second {
		t.Fatalf("statement generation exceeded bounded latency: %s", generationDuration)
	}
	if statement.LineCount != liveStatementPaymentFacts+liveStatementRefundFacts {
		t.Fatalf("line count=%d want %d", statement.LineCount, liveStatementPaymentFacts+liveStatementRefundFacts)
	}
	if len(statement.Lines) != 0 || statement.LineStorage != domain.StatementLineStorageNormalized {
		t.Fatalf("generated response must be a normalized summary: %+v", statement)
	}
	if statement.GrossPaymentAmount.MinorUnits != int64(liveStatementPaymentFacts)*1250 {
		t.Fatalf("gross payment=%d", statement.GrossPaymentAmount.MinorUnits)
	}
	if statement.GrossRefundAmount.MinorUnits != int64(liveStatementRefundFacts)*250 {
		t.Fatalf("gross refund=%d", statement.GrossRefundAmount.MinorUnits)
	}

	var snapshotBytes, storedLines int64
	if err := pool.QueryRow(ctx, `SELECT pg_column_size(data) FROM channel_statement_snapshots WHERE id=$1`, statement.ChannelStatementID).Scan(&snapshotBytes); err != nil {
		t.Fatal(err)
	}
	if snapshotBytes > 4096 {
		t.Fatalf("statement summary grew with lines: %d bytes", snapshotBytes)
	}
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM channel_statement_lines WHERE statement_id=$1`, statement.ChannelStatementID).Scan(&storedLines); err != nil {
		t.Fatal(err)
	}
	if storedLines != int64(statement.LineCount) {
		t.Fatalf("stored lines=%d want %d", storedLines, statement.LineCount)
	}

	assertBoundedStatementReads(t, ctx, service, statement)

	freezeStarted := time.Now()
	frozen, err := service.FreezeStatement(ctx, statement.ChannelStatementID, statement.StatementHash, statement.Version, "ops", "retained-day-close", ids.NewCorrelationID(), ids.NewCommandID())
	if err != nil {
		t.Fatal(err)
	}
	freezeDuration := time.Since(freezeStarted)
	if freezeDuration > time.Second {
		t.Fatalf("statement freeze exceeded bounded latency: %s", freezeDuration)
	}
	if frozen.Status != "FROZEN" || frozen.StatementHash != statement.StatementHash || frozen.LineCount != statement.LineCount {
		t.Fatalf("freeze changed immutable statement facts: before=%+v after=%+v", statement, frozen)
	}
	if _, err := pool.Exec(ctx, `UPDATE channel_statement_lines SET evidence_hash='changed' WHERE statement_id=$1 AND line_ordinal=1`, statement.ChannelStatementID); err == nil {
		t.Fatal("frozen statement line update was accepted")
	}
	if _, err := pool.Exec(ctx, `DELETE FROM channel_statement_lines WHERE statement_id=$1 AND line_ordinal=1`, statement.ChannelStatementID); err == nil {
		t.Fatal("frozen statement line delete was accepted")
	}

	replayStarted := time.Now()
	replayed, err := service.GenerateStatement(ctx, request)
	if err != nil {
		t.Fatal(err)
	}
	if time.Since(replayStarted) > time.Second {
		t.Fatalf("statement replay exceeded bounded latency: %s", time.Since(replayStarted))
	}
	if replayed.ChannelStatementID != statement.ChannelStatementID || replayed.StatementHash != statement.StatementHash || replayed.Version != frozen.Version {
		t.Fatalf("statement replay changed identity or frozen facts: original=%+v replay=%+v", frozen, replayed)
	}
	var statements, generationEvents int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM channel_statement_snapshots`).Scan(&statements); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM outbox WHERE envelope->>'eventType'='ChannelStatementGenerated'`).Scan(&generationEvents); err != nil {
		t.Fatal(err)
	}
	if statements != 1 || generationEvents != 1 {
		t.Fatalf("replay created duplicate statement facts: statements=%d generated_events=%d", statements, generationEvents)
	}
	t.Logf("generated %d lines in %s; summary=%d bytes; freeze=%s", statement.LineCount, generationDuration, snapshotBytes, freezeDuration)
}

func TestLiveStatementLinesAndSummaryCommitAtomically(t *testing.T) {
	pool := newStatementTestPool(t)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	transactor := NewTransactor(pool)
	repository := NewRepository(transactor)
	line := domain.FinalizeStatementLine(domain.StatementIdentityKey(domain.ChannelAlipay, "2026-09-24", "CNY", "atomic-v1"), 0, domain.StatementLine{
		LineType: "PAYMENT", LineStatus: "PRESENT", ChannelOrderID: "cho-orphan",
		ExpectedAmount: domain.Money{Currency: "CNY", MinorUnits: 100},
		ActualAmount:   domain.Money{Currency: "CNY", MinorUnits: 100},
		OccurredAt:     time.Date(2026, time.September, 24, 12, 0, 0, 0, time.UTC),
	})
	if err := transactor.Within(ctx, func(tx context.Context) error {
		return repository.SaveStatementLines(tx, "chs-missing", 1, []domain.StatementLine{line})
	}); err == nil {
		t.Fatal("orphan statement lines committed without their summary")
	}
	var count int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM channel_statement_lines`).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 0 {
		t.Fatalf("failed transaction left %d orphan statement lines", count)
	}
}

func TestLiveConcurrentStatementGenerationReplaysOneIdentity(t *testing.T) {
	pool := newStatementTestPool(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	seedStatementFacts(t, ctx, pool, 2000, 500)
	transactor := NewTransactor(pool)
	service := application.New(NewRepository(transactor), NewOutboxPublisher(transactor), transactor.Within)
	request := application.GenerateStatementRequest{
		Channel: domain.ChannelAlipay, StatementDate: "2026-09-24", Currency: "CNY", SeedVersion: "concurrent-v1",
		OperatorRef: "ops", ReasonCode: "DAILY_CLOSE",
		ScenarioCodes: []string{"AMOUNT_MISMATCH"}, CorrelationID: ids.NewCorrelationID(), SourceCommandID: ids.NewCommandID(),
	}

	const workers = 16
	results := make([]*domain.ChannelStatement, workers)
	errors := make([]error, workers)
	var wait sync.WaitGroup
	wait.Add(workers)
	for index := 0; index < workers; index++ {
		go func(index int) {
			defer wait.Done()
			results[index], errors[index] = service.GenerateStatement(ctx, request)
		}(index)
	}
	wait.Wait()
	for index, err := range errors {
		if err != nil {
			t.Fatalf("worker %d failed: %v", index, err)
		}
		if results[index].ChannelStatementID != results[0].ChannelStatementID || results[index].StatementHash != results[0].StatementHash {
			t.Fatalf("worker %d observed another statement identity: first=%+v got=%+v", index, results[0], results[index])
		}
	}
	var statements, lines, generatedEvents int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM channel_statement_snapshots`).Scan(&statements); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM channel_statement_lines`).Scan(&lines); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM outbox WHERE envelope->>'eventType'='ChannelStatementGenerated'`).Scan(&generatedEvents); err != nil {
		t.Fatal(err)
	}
	if statements != 1 || lines != 2500 || generatedEvents != 1 {
		t.Fatalf("concurrent replay duplicated facts: statements=%d lines=%d generated_events=%d", statements, lines, generatedEvents)
	}
	changed := request
	changed.ScenarioCodes = nil
	if _, err := service.GenerateStatement(ctx, changed); err == nil || err.Error() != "DOMAIN_RULE_VIOLATION: statement identity was reused with different generation scenarios" {
		t.Fatalf("changed generation replay was accepted: %v", err)
	}
}

func TestLiveStatementGenerationUsesOneDatabaseSnapshotAcrossPages(t *testing.T) {
	pool := newStatementTestPool(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	seedStatementFacts(t, ctx, pool, 6000, 0)
	transactor := NewTransactor(pool)
	repository := NewRepository(transactor)

	transactionStarted := make(chan struct{})
	inserted := make(chan error, 1)
	go func() {
		<-transactionStarted
		_, insertErr := pool.Exec(ctx, `INSERT INTO channel_order_snapshots (id, version, data) VALUES (
			'cho-000000005500a', 1, jsonb_build_object(
				'channelOrderId', 'cho-000000005500a', 'paymentIntentId', 'pi-concurrent',
				'businessRef', 'ord-concurrent', 'purpose', 'purchase', 'channel', 'ALIPAY_SIM',
				'amount', jsonb_build_object('currency', 'CNY', 'minorUnits', 1250),
				'status', 'SUCCEEDED', 'idempotencyKey', 'idem-concurrent',
				'requestFingerprint', 'fp-concurrent', 'sourceCommandId', 'cmd-source',
				'correlationId', 'corr-source', 'channelTransactionId', 'ctx-concurrent',
				'acceptedAt', '2026-09-24T12:00:00Z', 'completedAt', '2026-09-24T12:00:00Z',
				'attempts', '[]'::jsonb, 'createdAt', '2026-09-24T12:00:00Z',
				'updatedAt', '2026-09-24T12:00:00Z', 'version', 1
			)
		)`)
		inserted <- insertErr
	}()
	err := transactor.Within(ctx, func(tx context.Context) error {
		statement, err := repository.ClaimStatementForGeneration(tx, domain.ChannelAlipay, "2026-09-24", "CNY", "snapshot-v1", "chs-snapshot")
		if err != nil || statement != nil {
			return fmt.Errorf("claim statement: statement=%+v err=%w", statement, err)
		}
		first, err := repository.ListPaymentFactsForStatement(tx, domain.ChannelAlipay, "2026-09-24", "CNY", "", 5000, time.Date(2026, time.September, 25, 0, 0, 0, 0, time.UTC))
		if err != nil || len(first) != 5000 {
			return fmt.Errorf("first page: count=%d err=%w", len(first), err)
		}
		close(transactionStarted)
		if insertErr := <-inserted; insertErr != nil {
			return insertErr
		}
		second, err := repository.ListPaymentFactsForStatement(tx, domain.ChannelAlipay, "2026-09-24", "CNY", first[len(first)-1].ChannelOrderID, 5000, time.Date(2026, time.September, 25, 0, 0, 0, 0, time.UTC))
		if err != nil {
			return err
		}
		if len(second) != 1000 {
			return fmt.Errorf("repeatable-read page observed concurrent fact: count=%d want 1000", len(second))
		}
		return errors.New("rollback snapshot fixture")
	})
	if err == nil || err.Error() != "rollback snapshot fixture" {
		t.Fatalf("snapshot transaction failed unexpectedly: %v", err)
	}
}

func TestLiveStatementFreezeAndLineBoundDiscrepancyAreConsistent(t *testing.T) {
	pool := newStatementTestPool(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	seedStatementFacts(t, ctx, pool, 1, 0)
	transactor := NewTransactor(pool)
	service := application.New(NewRepository(transactor), NewOutboxPublisher(transactor), transactor.Within)
	statement, err := service.GenerateStatement(ctx, application.GenerateStatementRequest{
		Channel: domain.ChannelAlipay, StatementDate: "2026-09-24", Currency: "CNY", SeedVersion: "discrepancy-v1",
		OperatorRef: "ops", ReasonCode: "DAILY_CLOSE",
		ScenarioCodes: []string{"AMOUNT_MISMATCH"}, CorrelationID: ids.NewCorrelationID(), SourceCommandID: ids.NewCommandID(),
	})
	if err != nil {
		t.Fatal(err)
	}
	page, err := service.ListStatementLines(ctx, statement.ChannelStatementID, 0, 10)
	if err != nil || len(page.Items) != 1 {
		t.Fatalf("statement line page=%+v err=%v", page, err)
	}
	line := page.Items[0]

	const workers = 16
	errors := make(chan error, workers)
	for index := 0; index < workers; index++ {
		go func() {
			_, freezeErr := service.FreezeStatement(ctx, statement.ChannelStatementID, statement.StatementHash, statement.Version, "ops", "daily-close", ids.NewCorrelationID(), ids.NewCommandID())
			errors <- freezeErr
		}()
	}
	for index := 0; index < workers; index++ {
		if freezeErr := <-errors; freezeErr != nil {
			t.Fatalf("concurrent freeze %d failed: %v", index, freezeErr)
		}
	}
	var snapshotVersion, aggregateVersion int64
	var frozenEvents int
	if err := pool.QueryRow(ctx, `SELECT version, (data->>'version')::bigint FROM channel_statement_snapshots WHERE id=$1`, statement.ChannelStatementID).Scan(&snapshotVersion, &aggregateVersion); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM outbox WHERE envelope->>'eventType'='ChannelStatementFrozen'`).Scan(&frozenEvents); err != nil {
		t.Fatal(err)
	}
	if snapshotVersion != 2 || aggregateVersion != 2 || frozenEvents != 1 {
		t.Fatalf("freeze replay changed state more than once: row_version=%d aggregate_version=%d events=%d", snapshotVersion, aggregateVersion, frozenEvents)
	}

	valid := application.OpenDiscrepancyRequest{
		ChannelStatementID: statement.ChannelStatementID, StatementLineID: line.StatementLineID,
		DifferenceType: "AMOUNT_MISMATCH", ExpectedAmount: line.ExpectedAmount, ActualAmount: line.ActualAmount,
		EvidenceRef: line.EvidenceHash, CorrelationID: ids.NewCorrelationID(), SourceCommandID: ids.NewCommandID(),
	}
	discrepancy, err := service.OpenDiscrepancy(ctx, valid)
	if err != nil {
		t.Fatal(err)
	}
	if discrepancy.ChannelOrderID != line.ChannelOrderID {
		t.Fatalf("line-bound discrepancy did not inherit order identity: %+v", discrepancy)
	}
	invalid := valid
	invalid.ChannelOrderID = "cho-wrong"
	if _, err := service.OpenDiscrepancy(ctx, invalid); err == nil {
		t.Fatal("discrepancy with mismatched order was accepted")
	}
	invalid = valid
	invalid.ExpectedAmount.MinorUnits++
	if _, err := service.OpenDiscrepancy(ctx, invalid); err == nil {
		t.Fatal("discrepancy with mismatched amount was accepted")
	}
	var discrepancies int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM channel_discrepancy_snapshots`).Scan(&discrepancies); err != nil {
		t.Fatal(err)
	}
	if discrepancies != 1 {
		t.Fatalf("invalid discrepancies were persisted: %d", discrepancies)
	}

	resolved, err := service.ResolveDiscrepancy(ctx, discrepancy.DiscrepancyID, "RESOLVED", "resolution-evidence", "ops", "MATCHED", discrepancy.Version, ids.NewCorrelationID(), ids.NewCommandID())
	if err != nil {
		t.Fatal(err)
	}
	for index := 0; index < workers; index++ {
		go func() {
			_, resolveErr := service.ResolveDiscrepancy(ctx, discrepancy.DiscrepancyID, "RESOLVED", "resolution-evidence", "ops", "MATCHED", discrepancy.Version, ids.NewCorrelationID(), ids.NewCommandID())
			errors <- resolveErr
		}()
	}
	for index := 0; index < workers; index++ {
		if resolveErr := <-errors; resolveErr != nil {
			t.Fatalf("terminal discrepancy replay %d failed: %v", index, resolveErr)
		}
	}
	var resolvedEvents int
	if err := pool.QueryRow(ctx, `SELECT version, (data->>'version')::bigint FROM channel_discrepancy_snapshots WHERE id=$1`, discrepancy.DiscrepancyID).Scan(&snapshotVersion, &aggregateVersion); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM outbox WHERE envelope->>'eventType'='ReconciliationDiscrepancyResolved'`).Scan(&resolvedEvents); err != nil {
		t.Fatal(err)
	}
	if resolved.Status != "RESOLVED" || snapshotVersion != 2 || aggregateVersion != 2 || resolvedEvents != 1 {
		t.Fatalf("resolve replay changed state more than once: row_version=%d aggregate_version=%d events=%d", snapshotVersion, aggregateVersion, resolvedEvents)
	}
}

func TestLiveLegacyStatementBackfillPreservesIdentityAndFacts(t *testing.T) {
	pool := newStatementTestPool(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	transactor := NewTransactor(pool)
	repository := NewRepository(transactor)
	identity := domain.StatementIdentityKey(domain.ChannelAlipay, "2026-09-23", "CNY", "legacy-v1")
	first := domain.FinalizeStatementLine(identity, 0, domain.StatementLine{
		LineType: "PAYMENT", LineStatus: "AMOUNT_MISMATCH", ChannelOrderID: "cho-legacy",
		PaymentIntentID: "pi-legacy", ChannelTransactionID: "ctx-legacy",
		ExpectedAmount: domain.Money{Currency: "CNY", MinorUnits: 100},
		ActualAmount:   domain.Money{Currency: "CNY", MinorUnits: 101},
		OccurredAt:     time.Date(2026, time.September, 23, 12, 0, 0, 0, time.UTC),
		FaultInjected:  true, FaultSeedRef: "legacy-v1:AMOUNT_MISMATCH",
	})
	second := domain.FinalizeStatementLine(identity, 0, domain.StatementLine{
		LineType: "REFUND", LineStatus: "PRESENT", ChannelOrderID: "cho-legacy",
		ChannelRefundID: "chr-legacy", PaymentIntentID: "pi-legacy", RefundID: "rf-legacy",
		ChannelRefundTransactionID: "rtx-legacy",
		ExpectedAmount:             domain.Money{Currency: "CNY", MinorUnits: 40},
		ActualAmount:               domain.Money{Currency: "CNY", MinorUnits: 40},
		OccurredAt:                 time.Date(2026, time.September, 23, 13, 0, 0, 0, time.UTC),
	})
	legacy, err := domain.NewStatement("chs-legacy", domain.ChannelAlipay, "2026-09-23", "CNY", "legacy-v1", []domain.StatementLine{first, second}, time.Date(2026, time.September, 24, 0, 0, 0, 0, time.UTC), ids.NewCorrelationID(), ids.NewCommandID())
	if err != nil {
		t.Fatal(err)
	}
	legacy.Status = "FROZEN"
	frozenAt := legacy.GeneratedAt.Add(time.Minute)
	legacy.FrozenAt = &frozenAt
	legacy.Version = 2
	legacy.UpdatedAt = frozenAt
	legacyBytes, err := json.Marshal(legacy)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `INSERT INTO channel_statement_snapshots(id,version,data) VALUES($1,$2,$3)`, legacy.ChannelStatementID, legacy.Version, legacyBytes); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `INSERT INTO channel_discrepancy_snapshots(id,version,data) VALUES('pcd-legacy',1,$1)`, fmt.Sprintf(`{"discrepancyId":"pcd-legacy","channelStatementId":"%s","statementLineId":"%s","differenceType":"AMOUNT_MISMATCH","expectedAmount":{"currency":"CNY","minorUnits":100},"actualAmount":{"currency":"CNY","minorUnits":101},"status":"OPENED","evidenceRef":"%s","openedAt":"2026-09-24T00:01:00Z","updatedAt":"2026-09-24T00:01:00Z","version":1}`, legacy.ChannelStatementID, first.StatementLineID, first.EvidenceHash)); err != nil {
		t.Fatal(err)
	}

	result, err := repository.BackfillLegacyStatementLines(ctx, 1)
	if err != nil {
		t.Fatal(err)
	}
	if result.Statements != 1 || result.Lines != 2 || result.Complete {
		t.Fatalf("unexpected first backfill result: %+v", result)
	}
	complete, err := repository.BackfillLegacyStatementLines(ctx, 1)
	if err != nil {
		t.Fatal(err)
	}
	if !complete.Complete || complete.Statements != 0 {
		t.Fatalf("backfill was not resumably complete: %+v", complete)
	}
	var dataHasLines bool
	var lineStorage, generationFingerprint, statementHash, discrepancyLineID string
	var version int64
	if err := pool.QueryRow(ctx, `SELECT data ? 'lines', data->>'lineStorage', data->>'generationFingerprint', data->>'statementHash', version FROM channel_statement_snapshots WHERE id=$1`, legacy.ChannelStatementID).Scan(&dataHasLines, &lineStorage, &generationFingerprint, &statementHash, &version); err != nil {
		t.Fatal(err)
	}
	if dataHasLines || lineStorage != domain.StatementLineStorageNormalized || statementHash != legacy.StatementHash || version != legacy.Version {
		t.Fatalf("backfill changed statement identity/facts: has_lines=%t storage=%s hash=%s version=%d", dataHasLines, lineStorage, statementHash, version)
	}
	wantFingerprint := domain.Hash("statement-generation-v1", []string{"AMOUNT_MISMATCH"})
	if generationFingerprint != wantFingerprint {
		t.Fatalf("legacy scenario fingerprint=%s want %s", generationFingerprint, wantFingerprint)
	}
	if err := pool.QueryRow(ctx, `SELECT data->>'statementLineId' FROM channel_statement_lines WHERE statement_id=$1 ORDER BY line_ordinal LIMIT 1`, legacy.ChannelStatementID).Scan(&discrepancyLineID); err != nil {
		t.Fatal(err)
	}
	if discrepancyLineID != first.StatementLineID {
		t.Fatalf("statement line identity changed: got %s want %s", discrepancyLineID, first.StatementLineID)
	}
	if err := pool.QueryRow(ctx, `SELECT data->>'statementLineId' FROM channel_discrepancy_snapshots WHERE id='pcd-legacy'`).Scan(&discrepancyLineID); err != nil {
		t.Fatal(err)
	}
	if discrepancyLineID != first.StatementLineID {
		t.Fatalf("discrepancy reference changed: got %s want %s", discrepancyLineID, first.StatementLineID)
	}
	var canonicalID string
	if err := pool.QueryRow(ctx, `SELECT statement_id FROM channel_statement_identities WHERE identity_key=$1`, identity).Scan(&canonicalID); err != nil {
		t.Fatal(err)
	}
	if canonicalID != legacy.ChannelStatementID {
		t.Fatalf("legacy statement identity was not claimed: %s", canonicalID)
	}
	loaded, err := repository.GetStatementLine(ctx, legacy.ChannelStatementID, first.StatementLineID)
	if err != nil || loaded.EvidenceHash != first.EvidenceHash {
		t.Fatalf("normalized line was not queryable: line=%+v err=%v", loaded, err)
	}
}

func TestLiveLegacyStatementBackfillRollsBackInvalidContent(t *testing.T) {
	pool := newStatementTestPool(t)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	transactor := NewTransactor(pool)
	repository := NewRepository(transactor)
	invalid := `{"channelStatementId":"chs-invalid","channel":"ALIPAY_SIM","statementDate":"2026-09-23","currency":"CNY","seedVersion":"v1","status":"GENERATED","lineCount":1,"statementHash":"kept","lines":[{"statementLineId":"csl-invalid","lineType":"PAYMENT","lineStatus":"PRESENT","channelOrderId":"cho-invalid","expectedAmount":{"currency":"CNY","minorUnits":100},"actualAmount":{"currency":"CNY","minorUnits":100},"occurredAt":"not-a-time","faultInjected":false,"evidenceHash":"hash"}],"createdAt":"2026-09-24T00:00:00Z","updatedAt":"2026-09-24T00:00:00Z","version":1}`
	if _, err := pool.Exec(ctx, `INSERT INTO channel_statement_snapshots(id,version,data) VALUES('chs-invalid',1,$1)`, invalid); err != nil {
		t.Fatal(err)
	}
	if _, err := repository.BackfillLegacyStatementLines(ctx, 1); err == nil {
		t.Fatal("invalid legacy line was migrated")
	}
	var hasLines bool
	var normalized int
	if err := pool.QueryRow(ctx, `SELECT data ? 'lines' FROM channel_statement_snapshots WHERE id='chs-invalid'`).Scan(&hasLines); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM channel_statement_lines WHERE statement_id='chs-invalid'`).Scan(&normalized); err != nil {
		t.Fatal(err)
	}
	if !hasLines || normalized != 0 {
		t.Fatalf("failed backfill was not atomic: has_lines=%t normalized=%d", hasLines, normalized)
	}
}

func TestLiveStatementMigrationUpgradesLegacySchemaWithoutRewritingHistory(t *testing.T) {
	pool := newEmptyStatementTestPool(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	allMigrations, err := kitstorage.LoadMigrations(os.DirFS("../../../migrations"), ".")
	if err != nil {
		t.Fatal(err)
	}
	legacyMigrations := make([]kitstorage.Migration, 0, len(allMigrations))
	for _, migration := range allMigrations {
		if migration.Version < "005_" {
			legacyMigrations = append(legacyMigrations, migration)
		}
	}
	if err := kitstorage.NewMigrationRunner(pool).Run(ctx, legacyMigrations); err != nil {
		t.Fatal(err)
	}

	identity := domain.StatementIdentityKey(domain.ChannelAlipay, "2026-09-22", "CNY", "duplicate-v1")
	line := domain.FinalizeStatementLine(identity, 0, domain.StatementLine{
		LineType: "PAYMENT", LineStatus: "PRESENT", ChannelOrderID: "cho-migration",
		ExpectedAmount: domain.Money{Currency: "CNY", MinorUnits: 750},
		ActualAmount:   domain.Money{Currency: "CNY", MinorUnits: 750},
		OccurredAt:     time.Date(2026, time.September, 22, 12, 0, 0, 0, time.UTC),
	})
	older, err := domain.NewStatement("chs-older", domain.ChannelAlipay, "2026-09-22", "CNY", "duplicate-v1", []domain.StatementLine{line}, time.Date(2026, time.September, 23, 0, 0, 0, 0, time.UTC), ids.NewCorrelationID(), ids.NewCommandID())
	if err != nil {
		t.Fatal(err)
	}
	newer, err := domain.NewStatement("chs-newer", domain.ChannelAlipay, "2026-09-22", "CNY", "duplicate-v1", []domain.StatementLine{line}, time.Date(2026, time.September, 24, 0, 0, 0, 0, time.UTC), ids.NewCorrelationID(), ids.NewCommandID())
	if err != nil {
		t.Fatal(err)
	}
	before := make(map[string]string)
	for _, statement := range []*domain.ChannelStatement{newer, older} {
		payload, err := json.Marshal(statement)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := pool.Exec(ctx, `INSERT INTO channel_statement_snapshots(id,version,data) VALUES($1,$2,$3)`, statement.ChannelStatementID, statement.Version, payload); err != nil {
			t.Fatal(err)
		}
		var stored string
		if err := pool.QueryRow(ctx, `SELECT data::text FROM channel_statement_snapshots WHERE id=$1`, statement.ChannelStatementID).Scan(&stored); err != nil {
			t.Fatal(err)
		}
		before[statement.ChannelStatementID] = stored
	}

	if err := kitstorage.NewMigrationRunner(pool).Run(ctx, allMigrations); err != nil {
		t.Fatal(err)
	}
	if err := kitstorage.NewMigrationRunner(pool).Run(ctx, allMigrations); err != nil {
		t.Fatalf("migration replay failed: %v", err)
	}
	for id, original := range before {
		var after string
		if err := pool.QueryRow(ctx, `SELECT data::text FROM channel_statement_snapshots WHERE id=$1`, id).Scan(&after); err != nil {
			t.Fatal(err)
		}
		if after != original {
			t.Fatalf("schema migration rewrote historical statement %s", id)
		}
	}
	var statements int
	var canonicalID string
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM channel_statement_snapshots`).Scan(&statements); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `SELECT statement_id FROM channel_statement_identities WHERE identity_key=$1`, identity).Scan(&canonicalID); err != nil {
		t.Fatal(err)
	}
	if statements != 2 || canonicalID != older.ChannelStatementID {
		t.Fatalf("migration did not preserve duplicate history/canonical identity: statements=%d canonical=%s", statements, canonicalID)
	}

	repository := NewRepository(NewTransactor(pool))
	result, err := repository.BackfillLegacyStatementLines(ctx, 10)
	if err != nil {
		t.Fatal(err)
	}
	if result.Statements != 2 || result.Lines != 2 || !result.Complete {
		t.Fatalf("legacy migration backfill result=%+v", result)
	}
	var normalizedStatements, normalizedLines int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM channel_statement_snapshots WHERE data->>'lineStorage'=$1 AND NOT data ? 'lines'`, domain.StatementLineStorageNormalized).Scan(&normalizedStatements); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM channel_statement_lines`).Scan(&normalizedLines); err != nil {
		t.Fatal(err)
	}
	if normalizedStatements != 2 || normalizedLines != 2 {
		t.Fatalf("backfill did not normalize all preserved history: statements=%d lines=%d", normalizedStatements, normalizedLines)
	}
}

func assertBoundedStatementReads(t *testing.T, ctx context.Context, service *application.Service, statement *domain.ChannelStatement) {
	t.Helper()
	getStarted := time.Now()
	got, err := service.GetStatement(ctx, statement.ChannelStatementID)
	if err != nil {
		t.Fatal(err)
	}
	if time.Since(getStarted) > time.Second || len(got.Lines) != 0 {
		t.Fatalf("statement summary read was not bounded: duration=%s lines=%d", time.Since(getStarted), len(got.Lines))
	}
	listStarted := time.Now()
	items, total, err := service.ListStatements(ctx, application.StatementFilter{Channel: statement.Channel, StatementDate: statement.StatementDate, Limit: 20})
	if err != nil {
		t.Fatal(err)
	}
	if time.Since(listStarted) > time.Second || total != 1 || len(items) != 1 || len(items[0].Lines) != 0 {
		t.Fatalf("statement list was not bounded: duration=%s total=%d items=%d", time.Since(listStarted), total, len(items))
	}
	pageStarted := time.Now()
	page, err := service.ListStatementLines(ctx, statement.ChannelStatementID, 0, 500)
	if err != nil {
		t.Fatal(err)
	}
	if time.Since(pageStarted) > time.Second || len(page.Items) != 500 || page.NextCursor == nil || *page.NextCursor != 500 {
		t.Fatalf("statement line page was not bounded: duration=%s page=%+v", time.Since(pageStarted), page)
	}
}

func newStatementTestPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	pool := newEmptyStatementTestPool(t)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	migrations, err := kitstorage.LoadMigrations(os.DirFS("../../../migrations"), ".")
	if err != nil {
		t.Fatal(err)
	}
	if err := kitstorage.NewMigrationRunner(pool).Run(ctx, migrations); err != nil {
		t.Fatal(err)
	}
	return pool
}

func newEmptyStatementTestPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	databaseURL := os.Getenv("TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	admin, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	schema := "channel_statement_" + uuid.New().String()[:8]
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		admin.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = admin.Exec(context.Background(), "DROP SCHEMA "+schema+" CASCADE")
		admin.Close()
	})
	config, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	config.ConnConfig.RuntimeParams["search_path"] = schema
	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	return pool
}

func seedStatementFacts(t *testing.T, ctx context.Context, pool *pgxpool.Pool, payments, refunds int) {
	t.Helper()
	_, err := pool.Exec(ctx, `INSERT INTO channel_order_snapshots (id, version, data)
		SELECT id, 1, jsonb_build_object(
			'channelOrderId', id, 'paymentIntentId', 'pi-' || n, 'businessRef', 'ord-' || n,
			'purpose', 'purchase', 'channel', 'ALIPAY_SIM',
			'amount', jsonb_build_object('currency', 'CNY', 'minorUnits', 1250),
			'status', 'SUCCEEDED', 'idempotencyKey', 'idem-' || n,
			'requestFingerprint', 'fp-' || n, 'sourceCommandId', 'cmd-source',
			'correlationId', 'corr-source', 'channelTransactionId', 'ctx-' || n,
			'acceptedAt', '2026-09-24T12:00:00Z', 'completedAt', '2026-09-24T12:00:00Z',
			'attempts', '[]'::jsonb, 'createdAt', '2026-09-24T12:00:00Z',
			'updatedAt', '2026-09-25T12:00:00Z', 'version', 1)
		FROM (SELECT n, 'cho-' || lpad(n::text, 12, '0') AS id FROM generate_series(1, $1) AS n) facts`, payments)
	if err != nil {
		t.Fatal(err)
	}
	_, err = pool.Exec(ctx, `INSERT INTO channel_refund_snapshots (id, version, data)
		SELECT id, 1, jsonb_build_object(
			'channelRefundId', id, 'refundId', 'rf-' || n, 'paymentIntentId', 'pi-' || n,
			'channelOrderId', 'cho-' || lpad(n::text, 12, '0'),
			'originalChannelTransactionId', 'ctx-' || n, 'channel', 'ALIPAY_SIM',
			'amount', jsonb_build_object('currency', 'CNY', 'minorUnits', 250),
			'refundReasonCode', 'USER_CANCELLED', 'status', 'SUCCEEDED',
			'idempotencyKey', 'refund-idem-' || n, 'requestFingerprint', 'refund-fp-' || n,
			'sourceCommandId', 'cmd-source', 'correlationId', 'corr-source',
			'channelRefundTransactionId', 'rtx-' || n,
			'acceptedAt', '2026-09-24T13:00:00Z', 'completedAt', '2026-09-24T13:00:00Z',
			'attempts', '[]'::jsonb, 'createdAt', '2026-09-24T13:00:00Z',
			'updatedAt', '2026-09-25T13:00:00Z', 'version', 1)
		FROM (SELECT n, 'chr-' || lpad(n::text, 12, '0') AS id FROM generate_series(1, $1) AS n) facts`, refunds)
	if err != nil {
		t.Fatal(err)
	}
	var count int
	if err := pool.QueryRow(ctx, `SELECT (SELECT count(*) FROM channel_order_snapshots) + (SELECT count(*) FROM channel_refund_snapshots)`).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != payments+refunds {
		t.Fatal(fmt.Errorf("seeded facts=%d want %d", count, payments+refunds))
	}
}
