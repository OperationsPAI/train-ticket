package application

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/payment-channel/internal/domain"
)

type DomainError struct{ Code, Message string }

func (e *DomainError) Error() string { return e.Code + ": " + e.Message }
func derr(c, m string) error         { return &DomainError{c, m} }

type UnitOfWork func(context.Context, func(context.Context) error) error
type Publisher interface {
	Publish(context.Context, kitmsg.EventEnvelope) error
}
type Repository interface {
	SaveOrder(context.Context, *domain.ChannelOrder) error
	UpdateOrder(context.Context, *domain.ChannelOrder, int64) error
	GetOrder(context.Context, string) (*domain.ChannelOrder, error)
	ListOrdersForStatement(context.Context, string, string, string) ([]domain.ChannelOrder, error)
	SaveRefund(context.Context, *domain.ChannelRefund) error
	UpdateRefund(context.Context, *domain.ChannelRefund, int64) error
	GetRefund(context.Context, string) (*domain.ChannelRefund, error)
	ListRefundsForStatement(context.Context, string, string, string) ([]domain.ChannelRefund, error)
	SumRefundsForOrder(context.Context, string) (int64, error)
	SaveStatement(context.Context, *domain.ChannelStatement) error
	UpdateStatement(context.Context, *domain.ChannelStatement, int64) error
	GetStatement(context.Context, string) (*domain.ChannelStatement, error)
	ListStatements(context.Context, StatementFilter) ([]domain.ChannelStatement, int, error)
	SaveDiscrepancy(context.Context, *domain.ReconciliationDiscrepancy) error
	UpdateDiscrepancy(context.Context, *domain.ReconciliationDiscrepancy, int64) error
	GetDiscrepancy(context.Context, string) (*domain.ReconciliationDiscrepancy, error)
	ListDiscrepancies(context.Context, DiscrepancyFilter) ([]domain.ReconciliationDiscrepancy, int, error)
}
type Service struct {
	repo      Repository
	publisher Publisher
	tx        UnitOfWork
}

func New(repo Repository, p Publisher, tx UnitOfWork) *Service {
	if tx == nil {
		tx = func(ctx context.Context, fn func(context.Context) error) error { return fn(ctx) }
	}
	return &Service{repo: repo, publisher: p, tx: tx}
}

type StatementFilter struct {
	Channel, StatementDate, Currency, Status string
	Limit, Offset                            int
}
type DiscrepancyFilter struct {
	ChannelStatementID, Status, DifferenceType string
	Limit, Offset                              int
}

type CreateOrderRequest struct {
	PaymentIntentID, BusinessRef, Purpose, Channel, IdempotencyKey, SourceCommandID, CorrelationID string
	Amount                                                                                         domain.Money
	FaultSeed                                                                                      *domain.FaultSeed
}

func (s *Service) CreateOrder(ctx context.Context, r CreateOrderRequest) (*domain.ChannelOrder, error) {
	now := time.Now().UTC()
	o, err := domain.NewChannelOrder("", r.PaymentIntentID, r.BusinessRef, r.Purpose, r.Channel, r.Amount, r.IdempotencyKey, r.SourceCommandID, r.CorrelationID, r.FaultSeed, now)
	if err != nil {
		return nil, derr("VALIDATION_FAILED", err.Error())
	}
	err = s.tx(ctx, func(tx context.Context) error {
		if err := s.repo.SaveOrder(tx, o); err != nil {
			return derr("CONFLICT", err.Error())
		}
		return s.publish(tx, o.PullEvents())
	})
	if err != nil {
		return nil, err
	}
	return o, nil
}
func (s *Service) SubmitOrder(ctx context.Context, id string, expected int64, fp string) (*domain.ChannelOrder, error) {
	o, err := s.repo.GetOrder(ctx, id)
	if err != nil {
		return nil, derr("NOT_FOUND", "channel order not found")
	}
	old := o.Version
	if err := o.Submit(expected, fp, time.Now().UTC()); err != nil {
		return nil, mapRule(err)
	}
	err = s.tx(ctx, func(tx context.Context) error {
		if err := s.repo.UpdateOrder(tx, o, old); err != nil {
			return derr("CONFLICT", err.Error())
		}
		return s.publish(tx, o.PullEvents())
	})
	if err != nil {
		return nil, err
	}
	return o, nil
}
func (s *Service) QueryOrder(ctx context.Context, id string, expected int64, reason string) (*domain.ChannelOrder, error) {
	o, err := s.repo.GetOrder(ctx, id)
	if err != nil {
		return nil, derr("NOT_FOUND", "channel order not found")
	}
	old := o.Version
	if err := o.Query(expected, reason, time.Now().UTC()); err != nil {
		return nil, mapRule(err)
	}
	err = s.tx(ctx, func(tx context.Context) error {
		if err := s.repo.UpdateOrder(tx, o, old); err != nil {
			return derr("CONFLICT", err.Error())
		}
		return s.publish(tx, o.PullEvents())
	})
	if err != nil {
		return nil, err
	}
	return o, nil
}
func (s *Service) GetOrder(ctx context.Context, id string) (*domain.ChannelOrder, error) {
	o, err := s.repo.GetOrder(ctx, id)
	if err != nil {
		return nil, derr("NOT_FOUND", "channel order not found")
	}
	return o, nil
}

type CreateRefundRequest struct {
	RefundID, PaymentIntentID, ChannelOrderID, OriginalChannelTransactionID, Channel, RefundReasonCode, IdempotencyKey, SourceCommandID, CorrelationID string
	Amount                                                                                                                                             domain.Money
	FaultSeed                                                                                                                                          *domain.FaultSeed
}

func (s *Service) CreateRefund(ctx context.Context, r CreateRefundRequest) (*domain.ChannelRefund, error) {
	order, err := s.repo.GetOrder(ctx, r.ChannelOrderID)
	if err != nil {
		return nil, derr("NOT_FOUND", "original channel order not found")
	}
	if order.Status != domain.StatusSucceeded {
		return nil, derr("DOMAIN_RULE_VIOLATION", "original channel order is not successful")
	}
	if order.Channel != r.Channel {
		return nil, derr("DOMAIN_RULE_VIOLATION", "refund channel must match original order")
	}
	sum, _ := s.repo.SumRefundsForOrder(ctx, r.ChannelOrderID)
	if sum+r.Amount.MinorUnits > order.Amount.MinorUnits {
		return nil, derr("DOMAIN_RULE_VIOLATION", "refund amount exceeds original order")
	}
	now := time.Now().UTC()
	rf, err := domain.NewChannelRefund("", r.RefundID, r.PaymentIntentID, r.ChannelOrderID, r.OriginalChannelTransactionID, r.Channel, r.Amount, r.RefundReasonCode, r.IdempotencyKey, r.SourceCommandID, r.CorrelationID, r.FaultSeed, now)
	if err != nil {
		return nil, derr("VALIDATION_FAILED", err.Error())
	}
	err = s.tx(ctx, func(tx context.Context) error {
		if err := s.repo.SaveRefund(tx, rf); err != nil {
			return derr("CONFLICT", err.Error())
		}
		return s.publish(tx, rf.PullEvents())
	})
	if err != nil {
		return nil, err
	}
	return rf, nil
}
func (s *Service) SubmitRefund(ctx context.Context, id string, expected int64, fp string) (*domain.ChannelRefund, error) {
	r, err := s.repo.GetRefund(ctx, id)
	if err != nil {
		return nil, derr("NOT_FOUND", "channel refund not found")
	}
	old := r.Version
	if err := r.Submit(expected, fp, time.Now().UTC()); err != nil {
		return nil, mapRule(err)
	}
	err = s.tx(ctx, func(tx context.Context) error {
		if err := s.repo.UpdateRefund(tx, r, old); err != nil {
			return derr("CONFLICT", err.Error())
		}
		return s.publish(tx, r.PullEvents())
	})
	if err != nil {
		return nil, err
	}
	return r, nil
}
func (s *Service) QueryRefund(ctx context.Context, id string, expected int64, reason string) (*domain.ChannelRefund, error) {
	r, err := s.repo.GetRefund(ctx, id)
	if err != nil {
		return nil, derr("NOT_FOUND", "channel refund not found")
	}
	old := r.Version
	if err := r.Query(expected, reason, time.Now().UTC()); err != nil {
		return nil, mapRule(err)
	}
	err = s.tx(ctx, func(tx context.Context) error {
		if err := s.repo.UpdateRefund(tx, r, old); err != nil {
			return derr("CONFLICT", err.Error())
		}
		return s.publish(tx, r.PullEvents())
	})
	if err != nil {
		return nil, err
	}
	return r, nil
}
func (s *Service) GetRefund(ctx context.Context, id string) (*domain.ChannelRefund, error) {
	r, err := s.repo.GetRefund(ctx, id)
	if err != nil {
		return nil, derr("NOT_FOUND", "channel refund not found")
	}
	return r, nil
}

type GenerateStatementRequest struct {
	Channel, StatementDate, Currency, SeedVersion, OperatorRef, ReasonCode, CorrelationID, SourceCommandID string
	ScenarioCodes                                                                                          []string
}

func (s *Service) GenerateStatement(ctx context.Context, r GenerateStatementRequest) (*domain.ChannelStatement, error) {
	orders, _ := s.repo.ListOrdersForStatement(ctx, r.Channel, r.StatementDate, r.Currency)
	refunds, _ := s.repo.ListRefundsForStatement(ctx, r.Channel, r.StatementDate, r.Currency)
	lines := []domain.StatementLine{}
	for _, o := range orders {
		if o.Status == domain.StatusSucceeded {
			actual := o.Amount
			fault := false
			lineStatus := "PRESENT"
			seedRef := ""
			for _, sc := range r.ScenarioCodes {
				if sc == "AMOUNT_MISMATCH" {
					actual.MinorUnits++
					fault = true
					lineStatus = "AMOUNT_MISMATCH"
					seedRef = r.SeedVersion + ":" + sc
				}
			}
			l := domain.StatementLine{StatementLineID: domain.NewID("csl"), LineType: "PAYMENT", LineStatus: lineStatus, ChannelOrderID: o.ChannelOrderID, PaymentIntentID: o.PaymentIntentID, ChannelTransactionID: o.ChannelTransactionID, ExpectedAmount: o.Amount, ActualAmount: actual, OccurredAt: o.UpdatedAt, FaultInjected: fault, FaultSeedRef: seedRef}
			l.EvidenceHash = domain.Hash(l)
			lines = append(lines, l)
		}
	}
	for _, rf := range refunds {
		if rf.Status == domain.StatusSucceeded {
			l := domain.StatementLine{StatementLineID: domain.NewID("csl"), LineType: "REFUND", LineStatus: "PRESENT", ChannelOrderID: rf.ChannelOrderID, ChannelRefundID: rf.ChannelRefundID, PaymentIntentID: rf.PaymentIntentID, RefundID: rf.RefundID, ChannelRefundTransactionID: rf.ChannelRefundTransactionID, ExpectedAmount: rf.Amount, ActualAmount: rf.Amount, OccurredAt: rf.UpdatedAt}
			l.EvidenceHash = domain.Hash(l)
			lines = append(lines, l)
		}
	}
	st, err := domain.NewStatement("", r.Channel, r.StatementDate, r.Currency, r.SeedVersion, lines, time.Now().UTC(), r.CorrelationID, r.SourceCommandID)
	if err != nil {
		return nil, derr("VALIDATION_FAILED", err.Error())
	}
	err = s.tx(ctx, func(tx context.Context) error {
		if err := s.repo.SaveStatement(tx, st); err != nil {
			return derr("CONFLICT", err.Error())
		}
		return s.publish(tx, st.PullEvents())
	})
	if err != nil {
		return nil, err
	}
	return st, nil
}
func (s *Service) FreezeStatement(ctx context.Context, id, hash string, expected int64, operator, reason, corr, cause string) (*domain.ChannelStatement, error) {
	st, err := s.repo.GetStatement(ctx, id)
	if err != nil {
		return nil, derr("NOT_FOUND", "channel statement not found")
	}
	old := st.Version
	if err := st.Freeze(hash, expected, time.Now().UTC(), operator, reason, corr, cause); err != nil {
		return nil, mapRule(err)
	}
	err = s.tx(ctx, func(tx context.Context) error {
		if err := s.repo.UpdateStatement(tx, st, old); err != nil {
			return derr("CONFLICT", err.Error())
		}
		return s.publish(tx, st.PullEvents())
	})
	if err != nil {
		return nil, err
	}
	return st, nil
}
func (s *Service) GetStatement(ctx context.Context, id string) (*domain.ChannelStatement, error) {
	st, err := s.repo.GetStatement(ctx, id)
	if err != nil {
		return nil, derr("NOT_FOUND", "channel statement not found")
	}
	return st, nil
}
func (s *Service) ListStatements(ctx context.Context, f StatementFilter) ([]domain.ChannelStatement, int, error) {
	if f.Channel == "" && f.StatementDate == "" {
		return nil, 0, derr("VALIDATION_FAILED", "channel or statementDate is required")
	}
	return s.repo.ListStatements(ctx, normalizeStatementFilter(f))
}

type OpenDiscrepancyRequest struct {
	ChannelStatementID, StatementLineID, ChannelOrderID, ChannelRefundID, FinanceReconciliationCaseID, DifferenceType, EvidenceRef, CorrelationID, SourceCommandID string
	ExpectedAmount, ActualAmount                                                                                                                                   domain.Money
}

func (s *Service) OpenDiscrepancy(ctx context.Context, r OpenDiscrepancyRequest) (*domain.ReconciliationDiscrepancy, error) {
	if _, err := s.repo.GetStatement(ctx, r.ChannelStatementID); err != nil {
		return nil, derr("NOT_FOUND", "channel statement not found")
	}
	d, err := domain.NewDiscrepancy("", r.ChannelStatementID, r.StatementLineID, r.ChannelOrderID, r.ChannelRefundID, r.FinanceReconciliationCaseID, r.DifferenceType, r.ExpectedAmount, r.ActualAmount, r.EvidenceRef, time.Now().UTC(), r.CorrelationID, r.SourceCommandID)
	if err != nil {
		return nil, derr("VALIDATION_FAILED", err.Error())
	}
	err = s.tx(ctx, func(tx context.Context) error {
		if err := s.repo.SaveDiscrepancy(tx, d); err != nil {
			return derr("CONFLICT", err.Error())
		}
		return s.publish(tx, d.PullEvents())
	})
	if err != nil {
		return nil, err
	}
	return d, nil
}
func (s *Service) ResolveDiscrepancy(ctx context.Context, id, status, ref, operator, reason string, expected int64, corr, cause string) (*domain.ReconciliationDiscrepancy, error) {
	d, err := s.repo.GetDiscrepancy(ctx, id)
	if err != nil {
		return nil, derr("NOT_FOUND", "discrepancy not found")
	}
	old := d.Version
	if err := d.Resolve(status, ref, operator, reason, expected, time.Now().UTC(), corr, cause); err != nil {
		return nil, mapRule(err)
	}
	err = s.tx(ctx, func(tx context.Context) error {
		if err := s.repo.UpdateDiscrepancy(tx, d, old); err != nil {
			return derr("CONFLICT", err.Error())
		}
		return s.publish(tx, d.PullEvents())
	})
	if err != nil {
		return nil, err
	}
	return d, nil
}
func (s *Service) GetDiscrepancy(ctx context.Context, id string) (*domain.ReconciliationDiscrepancy, error) {
	d, err := s.repo.GetDiscrepancy(ctx, id)
	if err != nil {
		return nil, derr("NOT_FOUND", "discrepancy not found")
	}
	return d, nil
}
func (s *Service) ListDiscrepancies(ctx context.Context, f DiscrepancyFilter) ([]domain.ReconciliationDiscrepancy, int, error) {
	return s.repo.ListDiscrepancies(ctx, normalizeDiscrepancyFilter(f))
}

func (s *Service) publish(ctx context.Context, evs []domain.Event) error {
	for _, ev := range evs {
		env, err := kitmsg.NewEventEnvelope(ev.EventType, domain.Producer, ev.CorrelationID, ev.Payload, kitmsg.EnvelopeOptions{Now: ev.OccurredAt, CausationID: ev.CausationID, Context: ctx})
		if err == nil {
			env.EventID = domain.DeterministicEventID(ev.EventType, ev.AggregateID, ev.Version, ev.MaterialHash)
			if body, ok := ev.Payload.(json.RawMessage); ok {
				env.Payload = body
			}
		}
		if err != nil {
			return derr("UNAVAILABLE", err.Error())
		}
		if err := s.publisher.Publish(ctx, env); err != nil {
			return derr("UNAVAILABLE", "event publisher unavailable")
		}
	}
	return nil
}
func mapRule(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, context.Canceled) {
		return err
	}
	msg := err.Error()
	if msg == "version conflict" {
		return derr("PRECONDITION_FAILED", msg)
	}
	return derr("DOMAIN_RULE_VIOLATION", msg)
}
func normalizeStatementFilter(f StatementFilter) StatementFilter {
	if f.Limit <= 0 {
		f.Limit = 20
	}
	if f.Limit > 100 {
		f.Limit = 100
	}
	if f.Offset < 0 {
		f.Offset = 0
	}
	return f
}
func normalizeDiscrepancyFilter(f DiscrepancyFilter) DiscrepancyFilter {
	if f.Limit <= 0 {
		f.Limit = 20
	}
	if f.Limit > 100 {
		f.Limit = 100
	}
	if f.Offset < 0 {
		f.Offset = 0
	}
	return f
}

var _ = fmt.Sprintf
