package application

import (
	"context"
	"errors"
	"github.com/trainticket/greenfield/platform/go-kit/ids"
	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/payment-channel/internal/domain"
	"sort"
	"sync"
	"testing"
	"time"
)

type memRepo struct {
	orders         map[string]*domain.ChannelOrder
	refunds        map[string]*domain.ChannelRefund
	stmts          map[string]*domain.ChannelStatement
	statementLines map[string][]domain.StatementLine
	disc           map[string]*domain.ReconciliationDiscrepancy
	refundUpdates  int
	ordersErr      error
	refundsErr     error
}

func newMem() *memRepo {
	return &memRepo{
		orders:         map[string]*domain.ChannelOrder{},
		refunds:        map[string]*domain.ChannelRefund{},
		stmts:          map[string]*domain.ChannelStatement{},
		statementLines: map[string][]domain.StatementLine{},
		disc:           map[string]*domain.ReconciliationDiscrepancy{},
	}
}
func (m *memRepo) SaveOrder(_ context.Context, o *domain.ChannelOrder) error {
	m.orders[o.ChannelOrderID] = o
	return nil
}
func (m *memRepo) UpdateOrder(_ context.Context, o *domain.ChannelOrder, _ int64) error {
	m.orders[o.ChannelOrderID] = o
	return nil
}
func (m *memRepo) GetOrder(_ context.Context, id string) (*domain.ChannelOrder, error) {
	o := m.orders[id]
	if o == nil {
		return nil, assertErr{}
	}
	return o, nil
}
func (m *memRepo) ListPaymentFactsForStatement(_ context.Context, channel, date, currency, afterID string, limit int, cutoff time.Time) ([]StatementPaymentFact, error) {
	if m.ordersErr != nil {
		return nil, m.ordersErr
	}
	out := []StatementPaymentFact{}
	start, _ := time.Parse("2006-01-02", date)
	end := start.Add(24 * time.Hour)
	for _, o := range m.orders {
		if o.Status == domain.StatusSucceeded && o.CompletedAt != nil && o.ChannelOrderID > afterID && !o.CompletedAt.After(cutoff) && o.Channel == channel && o.Amount.Currency == currency && !o.CompletedAt.Before(start) && o.CompletedAt.Before(end) {
			out = append(out, StatementPaymentFact{ChannelOrderID: o.ChannelOrderID, PaymentIntentID: o.PaymentIntentID, ChannelTransactionID: o.ChannelTransactionID, Amount: o.Amount, CompletedAt: o.CompletedAt.UTC()})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ChannelOrderID < out[j].ChannelOrderID })
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}
func (m *memRepo) SaveRefund(_ context.Context, o *domain.ChannelRefund) error {
	m.refunds[o.ChannelRefundID] = o
	return nil
}
func (m *memRepo) UpdateRefund(_ context.Context, o *domain.ChannelRefund, _ int64) error {
	m.refundUpdates++
	m.refunds[o.ChannelRefundID] = o
	return nil
}
func (m *memRepo) GetRefund(_ context.Context, id string) (*domain.ChannelRefund, error) {
	o := m.refunds[id]
	if o == nil {
		return nil, assertErr{}
	}
	return o, nil
}
func (m *memRepo) FindRefundForOrder(_ context.Context, orderID, refundID string) (*domain.ChannelRefund, error) {
	for _, refund := range m.refunds {
		if refund.ChannelOrderID == orderID && refund.RefundID == refundID {
			return refund, nil
		}
	}
	return nil, nil
}
func (m *memRepo) ListRefundFactsForStatement(_ context.Context, channel, date, currency, afterID string, limit int, cutoff time.Time) ([]StatementRefundFact, error) {
	if m.refundsErr != nil {
		return nil, m.refundsErr
	}
	out := []StatementRefundFact{}
	start, _ := time.Parse("2006-01-02", date)
	end := start.Add(24 * time.Hour)
	for _, o := range m.refunds {
		if o.Status == domain.StatusSucceeded && o.CompletedAt != nil && o.ChannelRefundID > afterID && !o.CompletedAt.After(cutoff) && o.Channel == channel && o.Amount.Currency == currency && !o.CompletedAt.Before(start) && o.CompletedAt.Before(end) {
			out = append(out, StatementRefundFact{ChannelOrderID: o.ChannelOrderID, ChannelRefundID: o.ChannelRefundID, PaymentIntentID: o.PaymentIntentID, RefundID: o.RefundID, ChannelRefundTransactionID: o.ChannelRefundTransactionID, Amount: o.Amount, CompletedAt: o.CompletedAt.UTC()})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ChannelRefundID < out[j].ChannelRefundID })
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}
func (m *memRepo) SumRefundsForOrder(_ context.Context, id string) (int64, error) {
	var s int64
	for _, r := range m.refunds {
		if r.ChannelOrderID == id {
			s += r.Amount.MinorUnits
		}
	}
	return s, nil
}
func (m *memRepo) SaveStatement(_ context.Context, o *domain.ChannelStatement) error {
	m.stmts[o.ChannelStatementID] = o
	return nil
}
func (m *memRepo) UpdateStatement(_ context.Context, o *domain.ChannelStatement, _ int64) error {
	m.stmts[o.ChannelStatementID] = o
	return nil
}
func (m *memRepo) GetStatement(_ context.Context, id string) (*domain.ChannelStatement, error) {
	o := m.stmts[id]
	if o == nil {
		return nil, assertErr{}
	}
	return o, nil
}
func (m *memRepo) ClaimStatementForGeneration(_ context.Context, channel, date, currency, seed, _ string) (*domain.ChannelStatement, error) {
	for _, statement := range m.stmts {
		if statement.Channel == channel && statement.StatementDate == date && statement.Currency == currency && statement.SeedVersion == seed {
			return statement, nil
		}
	}
	return nil, nil
}
func (m *memRepo) SaveStatementLines(_ context.Context, statementID string, firstOrdinal int64, lines []domain.StatementLine) error {
	if firstOrdinal != int64(len(m.statementLines[statementID]))+1 {
		return errors.New("non-contiguous statement line ordinal")
	}
	m.statementLines[statementID] = append(m.statementLines[statementID], lines...)
	return nil
}
func (m *memRepo) ListStatementLines(_ context.Context, statementID string, after int64, limit int) ([]domain.StatementLine, error) {
	lines := m.statementLines[statementID]
	if after >= int64(len(lines)) {
		return []domain.StatementLine{}, nil
	}
	end := after + int64(limit)
	if end > int64(len(lines)) {
		end = int64(len(lines))
	}
	return append([]domain.StatementLine(nil), lines[after:end]...), nil
}
func (m *memRepo) GetStatementLine(_ context.Context, statementID, lineID string) (*domain.StatementLine, error) {
	for _, line := range m.statementLines[statementID] {
		if line.StatementLineID == lineID {
			copy := line
			return &copy, nil
		}
	}
	return nil, assertErr{}
}
func (m *memRepo) ListStatements(context.Context, StatementFilter) ([]domain.ChannelStatement, int, error) {
	out := []domain.ChannelStatement{}
	for _, o := range m.stmts {
		out = append(out, *o)
	}
	return out, len(out), nil
}
func (m *memRepo) SaveDiscrepancy(_ context.Context, o *domain.ReconciliationDiscrepancy) error {
	m.disc[o.DiscrepancyID] = o
	return nil
}
func (m *memRepo) UpdateDiscrepancy(_ context.Context, o *domain.ReconciliationDiscrepancy, _ int64) error {
	m.disc[o.DiscrepancyID] = o
	return nil
}
func (m *memRepo) GetDiscrepancy(_ context.Context, id string) (*domain.ReconciliationDiscrepancy, error) {
	o := m.disc[id]
	if o == nil {
		return nil, assertErr{}
	}
	return o, nil
}
func (m *memRepo) ListDiscrepancies(context.Context, DiscrepancyFilter) ([]domain.ReconciliationDiscrepancy, int, error) {
	out := []domain.ReconciliationDiscrepancy{}
	for _, o := range m.disc {
		out = append(out, *o)
	}
	return out, len(out), nil
}

type assertErr struct{}

func (assertErr) Error() string { return "not found" }

type pub struct{ events []kitmsg.EventEnvelope }

func (p *pub) Publish(_ context.Context, e kitmsg.EventEnvelope) error {
	p.events = append(p.events, e)
	return nil
}
func TestOrderRefundStatementDiscrepancyFlow(t *testing.T) {
	repo := newMem()
	p := &pub{}
	svc := New(repo, p, nil)
	ctx := context.Background()
	cmd := ids.NewCommandID()
	corr := ids.NewCorrelationID()
	o, err := svc.CreateOrder(ctx, CreateOrderRequest{PaymentIntentID: "pi-1", BusinessRef: "ord-1", Purpose: "purchase", Channel: domain.ChannelAlipay, Amount: domain.Money{Currency: "CNY", MinorUnits: 100}, IdempotencyKey: ids.NewUUIDv7(), SourceCommandID: cmd, CorrelationID: corr})
	if err != nil {
		t.Fatal(err)
	}
	o, err = svc.SubmitOrder(ctx, o.ChannelOrderID, o.Version, o.RequestFingerprint)
	if err != nil {
		t.Fatal(err)
	}
	if o.Status != domain.StatusSucceeded || o.ChannelTransactionID == "" {
		t.Fatalf("order not succeeded: %+v", o)
	}
	rf, err := svc.CreateRefund(ctx, CreateRefundRequest{RefundID: "rf-1", PaymentIntentID: "pi-1", ChannelOrderID: o.ChannelOrderID, OriginalChannelTransactionID: o.ChannelTransactionID, Channel: o.Channel, Amount: domain.Money{Currency: "CNY", MinorUnits: 40}, RefundReasonCode: "USER", IdempotencyKey: ids.NewUUIDv7(), SourceCommandID: cmd, CorrelationID: corr})
	if err != nil {
		t.Fatal(err)
	}
	rf, err = svc.SubmitRefund(ctx, rf.ChannelRefundID, rf.Version, rf.RequestFingerprint)
	if err != nil {
		t.Fatal(err)
	}
	if rf.Status != domain.StatusSucceeded {
		t.Fatalf("refund not succeeded")
	}
	statementDate := o.UpdatedAt.Format("2006-01-02")
	st, err := svc.GenerateStatement(ctx, GenerateStatementRequest{Channel: o.Channel, StatementDate: statementDate, Currency: "CNY", SeedVersion: "v1", CorrelationID: corr, SourceCommandID: cmd, ScenarioCodes: []string{"AMOUNT_MISMATCH"}})
	if err != nil {
		t.Fatal(err)
	}
	if st.LineCount < 2 {
		t.Fatalf("want statement lines")
	}
	page, err := svc.ListStatementLines(ctx, st.ChannelStatementID, 0, 100)
	if err != nil || len(page.Items) != st.LineCount {
		t.Fatalf("statement lines were not queryable: page=%+v err=%v", page, err)
	}
	d, err := svc.OpenDiscrepancy(ctx, OpenDiscrepancyRequest{ChannelStatementID: st.ChannelStatementID, DifferenceType: "AMOUNT_MISMATCH", ExpectedAmount: domain.Money{Currency: "CNY", MinorUnits: 100}, ActualAmount: domain.Money{Currency: "CNY", MinorUnits: 101}, EvidenceRef: "hash", CorrelationID: corr, SourceCommandID: cmd})
	if err != nil {
		t.Fatal(err)
	}
	d, err = svc.ResolveDiscrepancy(ctx, d.DiscrepancyID, "RESOLVED", "res", "ops", "OK", d.Version, corr, cmd)
	if err != nil {
		t.Fatal(err)
	}
	if d.Status != "RESOLVED" {
		t.Fatalf("not resolved")
	}
	if len(p.events) == 0 {
		t.Fatalf("expected events")
	}
}
func TestMissedOrderRecovery(t *testing.T) {
	repo := newMem()
	svc := New(repo, &pub{}, nil)
	seed := &domain.FaultSeed{SeedVersion: "v1", ScenarioCode: "MISSED_ORDER", SeedMaterialHash: "abc"}
	o, err := svc.CreateOrder(context.Background(), CreateOrderRequest{PaymentIntentID: "pi-2", BusinessRef: "ord-2", Purpose: "purchase", Channel: domain.ChannelWechat, Amount: domain.Money{Currency: "CNY", MinorUnits: 101}, IdempotencyKey: ids.NewUUIDv7(), SourceCommandID: ids.NewCommandID(), CorrelationID: ids.NewCorrelationID(), FaultSeed: seed})
	if err != nil {
		t.Fatal(err)
	}
	o, err = svc.SubmitOrder(context.Background(), o.ChannelOrderID, o.Version, o.RequestFingerprint)
	if err != nil {
		t.Fatal(err)
	}
	if o.Status != domain.StatusMissed {
		t.Fatalf("want missed got %s", o.Status)
	}
	o, err = svc.QueryOrder(context.Background(), o.ChannelOrderID, o.Version, "PAYMENT_TIMEOUT")
	if err != nil {
		t.Fatal(err)
	}
	if o.Status != domain.StatusSucceeded {
		t.Fatalf("want recovered success got %s", o.Status)
	}
}

func TestStatementGenerationFiltersByStatementDate(t *testing.T) {
	repo := newMem()
	svc := New(repo, &pub{}, nil)
	ctx := context.Background()
	cmd := ids.NewCommandID()
	corr := ids.NewCorrelationID()
	o, err := svc.CreateOrder(ctx, CreateOrderRequest{PaymentIntentID: "pi-date", BusinessRef: "ord-date", Purpose: "purchase", Channel: domain.ChannelAlipay, Amount: domain.Money{Currency: "CNY", MinorUnits: 100}, IdempotencyKey: ids.NewUUIDv7(), SourceCommandID: cmd, CorrelationID: corr})
	if err != nil {
		t.Fatal(err)
	}
	o, err = svc.SubmitOrder(ctx, o.ChannelOrderID, o.Version, o.RequestFingerprint)
	if err != nil {
		t.Fatal(err)
	}
	today := o.UpdatedAt.Format("2006-01-02")
	yesterday := o.UpdatedAt.Add(-24 * time.Hour).Format("2006-01-02")
	st, err := svc.GenerateStatement(ctx, GenerateStatementRequest{Channel: o.Channel, StatementDate: yesterday, Currency: "CNY", SeedVersion: "v1", CorrelationID: corr, SourceCommandID: cmd})
	if err != nil {
		t.Fatal(err)
	}
	if st.LineCount != 0 {
		t.Fatalf("expected no prior-day lines, got %d", st.LineCount)
	}
	st, err = svc.GenerateStatement(ctx, GenerateStatementRequest{Channel: o.Channel, StatementDate: today, Currency: "CNY", SeedVersion: "v2", CorrelationID: corr, SourceCommandID: cmd})
	if err != nil {
		t.Fatal(err)
	}
	if st.LineCount != 1 {
		t.Fatalf("expected one same-day line, got %d", st.LineCount)
	}
}

func TestStatementGenerationUsesTerminalOccurrenceInsteadOfSnapshotUpdate(t *testing.T) {
	repo := newMem()
	svc := New(repo, &pub{}, nil)
	ctx := context.Background()
	cmd := ids.NewCommandID()
	corr := ids.NewCorrelationID()
	order, err := svc.CreateOrder(ctx, CreateOrderRequest{
		PaymentIntentID: "pi-occurrence", BusinessRef: "ord-occurrence", Purpose: "purchase",
		Channel: domain.ChannelAlipay, Amount: domain.Money{Currency: "CNY", MinorUnits: 100},
		IdempotencyKey: ids.NewUUIDv7(), SourceCommandID: cmd, CorrelationID: corr,
	})
	if err != nil {
		t.Fatal(err)
	}
	order, err = svc.SubmitOrder(ctx, order.ChannelOrderID, order.Version, order.RequestFingerprint)
	if err != nil {
		t.Fatal(err)
	}
	settledAt := time.Date(2026, time.September, 23, 23, 59, 58, 0, time.UTC)
	queriedAt := settledAt.Add(48 * time.Hour)
	order.CompletedAt = &settledAt
	order.UpdatedAt = queriedAt

	statement, err := svc.GenerateStatement(ctx, GenerateStatementRequest{
		Channel: order.Channel, StatementDate: "2026-09-23", Currency: "CNY", SeedVersion: "v1",
		CorrelationID: corr, SourceCommandID: cmd,
	})
	if err != nil {
		t.Fatal(err)
	}
	if statement.LineCount != 1 || len(statement.Lines) != 0 {
		t.Fatalf("terminal occurrence must remain on its settlement day: %+v", statement)
	}
	page, err := svc.ListStatementLines(ctx, statement.ChannelStatementID, 0, 100)
	if err != nil || len(page.Items) != 1 || !page.Items[0].OccurredAt.Equal(settledAt) {
		t.Fatalf("line occurrence page=%+v err=%v want %s", page, err, settledAt)
	}
	wrongDay, err := svc.GenerateStatement(ctx, GenerateStatementRequest{
		Channel: order.Channel, StatementDate: "2026-09-25", Currency: "CNY", SeedVersion: "v2",
		CorrelationID: corr, SourceCommandID: cmd,
	})
	if err != nil {
		t.Fatal(err)
	}
	if wrongDay.LineCount != 0 {
		t.Fatalf("later snapshot update moved terminal fact into another statement: %+v", wrongDay)
	}
}

func TestStatementGenerationPropagatesFactReadFailures(t *testing.T) {
	for _, test := range []struct {
		name string
		set  func(*memRepo)
		want string
	}{
		{name: "orders", set: func(repo *memRepo) { repo.ordersErr = errors.New("orders unavailable") }, want: "UNAVAILABLE: load channel orders for statement: orders unavailable"},
		{name: "refunds", set: func(repo *memRepo) { repo.refundsErr = errors.New("refunds unavailable") }, want: "UNAVAILABLE: load channel refunds for statement: refunds unavailable"},
	} {
		t.Run(test.name, func(t *testing.T) {
			repo := newMem()
			test.set(repo)
			svc := New(repo, &pub{}, nil)
			_, err := svc.GenerateStatement(context.Background(), GenerateStatementRequest{
				Channel: domain.ChannelAlipay, StatementDate: "2026-09-25", Currency: "CNY", SeedVersion: "v1",
				CorrelationID: ids.NewCorrelationID(), SourceCommandID: ids.NewCommandID(),
			})
			if err == nil || err.Error() != test.want {
				t.Fatalf("generation error=%v want %q", err, test.want)
			}
		})
	}
}

func TestConcurrentRefundReplayReusesBusinessRefundAndTerminalSubmission(t *testing.T) {
	repo := newMem()
	publisher := &pub{}
	var transaction sync.Mutex
	svc := New(repo, publisher, func(ctx context.Context, work func(context.Context) error) error {
		transaction.Lock()
		defer transaction.Unlock()
		return work(ctx)
	})
	ctx := context.Background()
	commandID := ids.NewCommandID()
	correlationID := ids.NewCorrelationID()
	order, err := svc.CreateOrder(ctx, CreateOrderRequest{
		PaymentIntentID: "pi-replay", BusinessRef: "ord-replay", Purpose: "purchase",
		Channel: domain.ChannelAlipay, Amount: domain.Money{Currency: "CNY", MinorUnits: 8783},
		IdempotencyKey: ids.NewUUIDv7(), SourceCommandID: commandID, CorrelationID: correlationID,
	})
	if err != nil {
		t.Fatal(err)
	}
	order, err = svc.SubmitOrder(ctx, order.ChannelOrderID, order.Version, order.RequestFingerprint)
	if err != nil {
		t.Fatal(err)
	}
	request := CreateRefundRequest{
		RefundID: "rf-business-replay", PaymentIntentID: order.PaymentIntentID,
		ChannelOrderID: order.ChannelOrderID, OriginalChannelTransactionID: order.ChannelTransactionID,
		Channel: order.Channel, Amount: order.Amount, RefundReasonCode: "POST_SALES_APPROVED",
		IdempotencyKey: ids.NewUUIDv7(), SourceCommandID: commandID, CorrelationID: correlationID,
	}

	const workers = 16
	results := make(chan *domain.ChannelRefund, workers)
	errors := make(chan error, workers)
	for index := 0; index < workers; index++ {
		go func() {
			refund, createErr := svc.CreateRefund(ctx, request)
			results <- refund
			errors <- createErr
		}()
	}
	var refund *domain.ChannelRefund
	for index := 0; index < workers; index++ {
		if createErr := <-errors; createErr != nil {
			t.Fatalf("identical refund replay failed: %v", createErr)
		}
		result := <-results
		if refund == nil {
			refund = result
		} else if result.ChannelRefundID != refund.ChannelRefundID {
			t.Fatalf("replay created another channel refund: %s != %s", result.ChannelRefundID, refund.ChannelRefundID)
		}
	}
	if len(repo.refunds) != 1 {
		t.Fatalf("expected one channel refund, got %d", len(repo.refunds))
	}

	changed := request
	changed.Amount.MinorUnits--
	if _, err := svc.CreateRefund(ctx, changed); err == nil || err.Error() != "DOMAIN_RULE_VIOLATION: refund identity was reused with different request details" {
		t.Fatalf("changed replay must be rejected by identity, got %v", err)
	}
	second := request
	second.RefundID = "rf-another-business-refund"
	second.IdempotencyKey = ids.NewUUIDv7()
	if _, err := svc.CreateRefund(ctx, second); err == nil || err.Error() != "DOMAIN_RULE_VIOLATION: refund amount exceeds original order" {
		t.Fatalf("a real second full refund must still be rejected, got %v", err)
	}

	createdVersion := refund.Version
	refund, err = svc.SubmitRefund(ctx, refund.ChannelRefundID, createdVersion, refund.RequestFingerprint)
	if err != nil || refund.Status != domain.StatusSucceeded {
		t.Fatalf("initial refund submission failed: refund=%+v err=%v", refund, err)
	}
	for index := 0; index < workers; index++ {
		replayed, replayErr := svc.SubmitRefund(ctx, refund.ChannelRefundID, createdVersion, refund.RequestFingerprint)
		if replayErr != nil || replayed.Status != domain.StatusSucceeded || replayed.ChannelRefundTransactionID != refund.ChannelRefundTransactionID {
			t.Fatalf("terminal submission replay changed outcome: refund=%+v err=%v", replayed, replayErr)
		}
	}
	if repo.refundUpdates != 1 {
		t.Fatalf("terminal submission replays must not update the refund, updates=%d", repo.refundUpdates)
	}
	if _, replayErr := svc.SubmitRefund(ctx, refund.ChannelRefundID, createdVersion, "changed-fingerprint"); replayErr == nil || replayErr.Error() != "DOMAIN_RULE_VIOLATION: request fingerprint mismatch" {
		t.Fatalf("terminal replay with changed request must be rejected, got %v", replayErr)
	}
}
