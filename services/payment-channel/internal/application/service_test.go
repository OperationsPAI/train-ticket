package application

import (
	"context"
	"github.com/trainticket/greenfield/platform/go-kit/ids"
	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/payment-channel/internal/domain"
	"testing"
)

type memRepo struct {
	orders  map[string]*domain.ChannelOrder
	refunds map[string]*domain.ChannelRefund
	stmts   map[string]*domain.ChannelStatement
	disc    map[string]*domain.ReconciliationDiscrepancy
}

func newMem() *memRepo {
	return &memRepo{map[string]*domain.ChannelOrder{}, map[string]*domain.ChannelRefund{}, map[string]*domain.ChannelStatement{}, map[string]*domain.ReconciliationDiscrepancy{}}
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
func (m *memRepo) ListOrdersForStatement(context.Context, string, string, string) ([]domain.ChannelOrder, error) {
	out := []domain.ChannelOrder{}
	for _, o := range m.orders {
		out = append(out, *o)
	}
	return out, nil
}
func (m *memRepo) SaveRefund(_ context.Context, o *domain.ChannelRefund) error {
	m.refunds[o.ChannelRefundID] = o
	return nil
}
func (m *memRepo) UpdateRefund(_ context.Context, o *domain.ChannelRefund, _ int64) error {
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
func (m *memRepo) ListRefundsForStatement(context.Context, string, string, string) ([]domain.ChannelRefund, error) {
	out := []domain.ChannelRefund{}
	for _, o := range m.refunds {
		out = append(out, *o)
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
	o, err := svc.CreateOrder(ctx, CreateOrderRequest{PaymentIntentID: "pi-1", BusinessRef: "ord-1", Purpose: "purchase", Channel: domain.ChannelAlipay, Amount: domain.Money{"CNY", 100}, IdempotencyKey: ids.NewUUIDv7(), SourceCommandID: cmd, CorrelationID: corr})
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
	rf, err := svc.CreateRefund(ctx, CreateRefundRequest{RefundID: "rf-1", PaymentIntentID: "pi-1", ChannelOrderID: o.ChannelOrderID, OriginalChannelTransactionID: o.ChannelTransactionID, Channel: o.Channel, Amount: domain.Money{"CNY", 40}, RefundReasonCode: "USER", IdempotencyKey: ids.NewUUIDv7(), SourceCommandID: cmd, CorrelationID: corr})
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
	st, err := svc.GenerateStatement(ctx, GenerateStatementRequest{Channel: o.Channel, StatementDate: "2026-07-10", Currency: "CNY", SeedVersion: "v1", CorrelationID: corr, SourceCommandID: cmd, ScenarioCodes: []string{"AMOUNT_MISMATCH"}})
	if err != nil {
		t.Fatal(err)
	}
	if st.LineCount < 2 {
		t.Fatalf("want statement lines")
	}
	d, err := svc.OpenDiscrepancy(ctx, OpenDiscrepancyRequest{ChannelStatementID: st.ChannelStatementID, DifferenceType: "AMOUNT_MISMATCH", ExpectedAmount: domain.Money{"CNY", 100}, ActualAmount: domain.Money{"CNY", 101}, EvidenceRef: "hash", CorrelationID: corr, SourceCommandID: cmd})
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
	o, err := svc.CreateOrder(context.Background(), CreateOrderRequest{PaymentIntentID: "pi-2", BusinessRef: "ord-2", Purpose: "purchase", Channel: domain.ChannelWechat, Amount: domain.Money{"CNY", 101}, IdempotencyKey: ids.NewUUIDv7(), SourceCommandID: ids.NewCommandID(), CorrelationID: ids.NewCorrelationID(), FaultSeed: seed})
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
