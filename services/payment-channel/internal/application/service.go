package application

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
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
	ListPaymentFactsForStatement(context.Context, string, string, string, string, int, time.Time) ([]StatementPaymentFact, error)
	SaveRefund(context.Context, *domain.ChannelRefund) error
	UpdateRefund(context.Context, *domain.ChannelRefund, int64) error
	GetRefund(context.Context, string) (*domain.ChannelRefund, error)
	FindRefundForOrder(context.Context, string, string) (*domain.ChannelRefund, error)
	ListRefundFactsForStatement(context.Context, string, string, string, string, int, time.Time) ([]StatementRefundFact, error)
	SumRefundsForOrder(context.Context, string) (int64, error)
	SaveStatement(context.Context, *domain.ChannelStatement) error
	UpdateStatement(context.Context, *domain.ChannelStatement, int64) error
	GetStatement(context.Context, string) (*domain.ChannelStatement, error)
	LockStatement(context.Context, string) (*domain.ChannelStatement, error)
	ClaimStatementForGeneration(context.Context, string, string, string, string, string) (*domain.ChannelStatement, error)
	SaveStatementLines(context.Context, string, int64, []domain.StatementLine) error
	ListStatementLines(context.Context, string, int64, int) ([]domain.StatementLine, error)
	GetStatementLine(context.Context, string, string) (*domain.StatementLine, error)
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

type StatementLinePage struct {
	Items      []domain.StatementLine `json:"items"`
	LineCount  int                    `json:"lineCount"`
	Limit      int                    `json:"limit"`
	After      int64                  `json:"after"`
	NextCursor *int64                 `json:"nextCursor,omitempty"`
}

type StatementPaymentFact struct {
	ChannelOrderID       string
	PaymentIntentID      string
	ChannelTransactionID string
	Amount               domain.Money
	CompletedAt          time.Time
}

type StatementRefundFact struct {
	ChannelOrderID             string
	ChannelRefundID            string
	PaymentIntentID            string
	RefundID                   string
	ChannelRefundTransactionID string
	Amount                     domain.Money
	CompletedAt                time.Time
}

const statementGenerationBatchSize = 5000

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
	var rf *domain.ChannelRefund
	err := s.tx(ctx, func(tx context.Context) error {
		order, err := s.repo.GetOrder(tx, r.ChannelOrderID)
		if err != nil {
			return derr("NOT_FOUND", "original channel order not found")
		}
		if order.Status != domain.StatusSucceeded {
			return derr("DOMAIN_RULE_VIOLATION", "original channel order is not successful")
		}
		if order.Channel != r.Channel {
			return derr("DOMAIN_RULE_VIOLATION", "refund channel must match original order")
		}
		if order.PaymentIntentID != r.PaymentIntentID || order.ChannelTransactionID != r.OriginalChannelTransactionID {
			return derr("DOMAIN_RULE_VIOLATION", "refund must reference the original payment intent and transaction")
		}
		if order.Amount.Currency != r.Amount.Currency {
			return derr("DOMAIN_RULE_VIOLATION", "refund currency must match original order")
		}
		if err := r.Amount.ValidatePositive(); err != nil {
			return derr("VALIDATION_FAILED", err.Error())
		}
		existing, err := s.repo.FindRefundForOrder(tx, r.ChannelOrderID, r.RefundID)
		if err != nil {
			return err
		}
		if existing != nil {
			if !existing.MatchesBusinessRequest(r.RefundID, r.PaymentIntentID, r.ChannelOrderID,
				r.OriginalChannelTransactionID, r.Channel, r.Amount, r.RefundReasonCode, r.FaultSeed) {
				return derr("DOMAIN_RULE_VIOLATION", "refund identity was reused with different request details")
			}
			rf = existing
			return nil
		}
		sum, err := s.repo.SumRefundsForOrder(tx, r.ChannelOrderID)
		if err != nil {
			return err
		}
		if r.Amount.MinorUnits > order.Amount.MinorUnits-sum {
			return derr("DOMAIN_RULE_VIOLATION", "refund amount exceeds original order")
		}
		rf, err = domain.NewChannelRefund("", r.RefundID, r.PaymentIntentID, r.ChannelOrderID, r.OriginalChannelTransactionID, r.Channel, r.Amount, r.RefundReasonCode, r.IdempotencyKey, r.SourceCommandID, r.CorrelationID, r.FaultSeed, time.Now().UTC())
		if err != nil {
			return derr("VALIDATION_FAILED", err.Error())
		}
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
	var r *domain.ChannelRefund
	err := s.tx(ctx, func(tx context.Context) error {
		var err error
		r, err = s.repo.GetRefund(tx, id)
		if err != nil {
			return derr("NOT_FOUND", "channel refund not found")
		}
		terminal := domain.IsTerminal(r.Status)
		old := r.Version
		if err := r.Submit(expected, fp, time.Now().UTC()); err != nil {
			return mapRule(err)
		}
		if terminal {
			return nil
		}
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
	statementIdentity := domain.StatementIdentityKey(r.Channel, r.StatementDate, r.Currency, r.SeedVersion)
	if _, err := domain.NewStatementBuilder(r.Channel, r.StatementDate, r.Currency, r.SeedVersion); err != nil {
		return nil, derr("VALIDATION_FAILED", err.Error())
	}
	scenarios := normalizeStatementScenarios(r.ScenarioCodes)
	generationFingerprint := domain.Hash("statement-generation-v1", scenarios)
	generatedAt := time.Now().UTC()
	statementID := domain.NewID("chs")
	var statement *domain.ChannelStatement
	var err error
	for attempt := 0; attempt < 3; attempt++ {
		statement = nil
		err = s.tx(ctx, func(tx context.Context) error {
			var buildErr error
			statement, buildErr = s.generateStatementInTransaction(tx, r, statementIdentity, generationFingerprint, generatedAt, statementID, scenarios)
			return buildErr
		})
		if err == nil {
			return statement, nil
		}
		if !isSerializationFailure(err) {
			return nil, err
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(time.Duration(attempt+1) * 10 * time.Millisecond):
		}
	}
	return nil, err
}

func (s *Service) generateStatementInTransaction(ctx context.Context, r GenerateStatementRequest, statementIdentity, generationFingerprint string, generatedAt time.Time, statementID string, scenarios []string) (*domain.ChannelStatement, error) {
	builder, err := domain.NewStatementBuilder(r.Channel, r.StatementDate, r.Currency, r.SeedVersion)
	if err != nil {
		return nil, derr("VALIDATION_FAILED", err.Error())
	}
	existing, err := s.repo.ClaimStatementForGeneration(ctx, r.Channel, r.StatementDate, r.Currency, r.SeedVersion, statementID)
	if err != nil {
		return nil, derr("UNAVAILABLE", "lock channel statement identity: "+err.Error())
	}
	if existing != nil {
		if existing.GenerationFingerprint != "" && existing.GenerationFingerprint != generationFingerprint {
			return nil, derr("DOMAIN_RULE_VIOLATION", "statement identity was reused with different generation scenarios")
		}
		return existing, nil
	}

	var ordinal int64
	afterID := ""
	for {
		orders, err := s.repo.ListPaymentFactsForStatement(ctx, r.Channel, r.StatementDate, r.Currency, afterID, statementGenerationBatchSize, generatedAt)
		if err != nil {
			return nil, derr("UNAVAILABLE", "load channel orders for statement: "+err.Error())
		}
		lines := make([]domain.StatementLine, 0, len(orders))
		for _, order := range orders {
			line, err := paymentStatementLine(statementIdentity, r.SeedVersion, scenarios, order)
			if err != nil {
				return nil, derr("UNAVAILABLE", err.Error())
			}
			if err := builder.Add(line); err != nil {
				return nil, derr("UNAVAILABLE", err.Error())
			}
			lines = append(lines, line)
		}
		if len(lines) > 0 {
			if err := s.repo.SaveStatementLines(ctx, statementID, ordinal+1, lines); err != nil {
				return nil, derr("UNAVAILABLE", "save payment statement lines: "+err.Error())
			}
			ordinal += int64(len(lines))
			afterID = orders[len(orders)-1].ChannelOrderID
		}
		if len(orders) < statementGenerationBatchSize {
			break
		}
	}

	afterID = ""
	for {
		refunds, err := s.repo.ListRefundFactsForStatement(ctx, r.Channel, r.StatementDate, r.Currency, afterID, statementGenerationBatchSize, generatedAt)
		if err != nil {
			return nil, derr("UNAVAILABLE", "load channel refunds for statement: "+err.Error())
		}
		lines := make([]domain.StatementLine, 0, len(refunds))
		for _, refund := range refunds {
			line, err := refundStatementLine(statementIdentity, refund)
			if err != nil {
				return nil, derr("UNAVAILABLE", err.Error())
			}
			if err := builder.Add(line); err != nil {
				return nil, derr("UNAVAILABLE", err.Error())
			}
			lines = append(lines, line)
		}
		if len(lines) > 0 {
			if err := s.repo.SaveStatementLines(ctx, statementID, ordinal+1, lines); err != nil {
				return nil, derr("UNAVAILABLE", "save refund statement lines: "+err.Error())
			}
			ordinal += int64(len(lines))
			afterID = refunds[len(refunds)-1].ChannelRefundID
		}
		if len(refunds) < statementGenerationBatchSize {
			break
		}
	}

	statement, err := builder.Build(statementID, generatedAt, r.CorrelationID, r.SourceCommandID)
	if err != nil {
		return nil, derr("VALIDATION_FAILED", err.Error())
	}
	statement.GenerationFingerprint = generationFingerprint
	statement.LineStorage = domain.StatementLineStorageNormalized
	if err := s.repo.SaveStatement(ctx, statement); err != nil {
		return nil, derr("CONFLICT", err.Error())
	}
	if err := s.publish(ctx, statement.PullEvents()); err != nil {
		return nil, err
	}
	return statement, nil
}

func isSerializationFailure(err error) bool {
	if err == nil {
		return false
	}
	message := err.Error()
	return strings.Contains(message, "SQLSTATE 40001") || strings.Contains(message, "could not serialize access")
}

func paymentStatementLine(statementIdentity, seedVersion string, scenarios []string, order StatementPaymentFact) (domain.StatementLine, error) {
	actual := order.Amount
	fault := false
	lineStatus := "PRESENT"
	seedRef := ""
	for _, scenario := range scenarios {
		if scenario == "AMOUNT_MISMATCH" {
			actual.MinorUnits++
			fault = true
			lineStatus = "AMOUNT_MISMATCH"
			seedRef = seedVersion + ":" + scenario
		}
	}
	return domain.FinalizeStatementLine(statementIdentity, 0, domain.StatementLine{
		LineType: "PAYMENT", LineStatus: lineStatus, ChannelOrderID: order.ChannelOrderID,
		PaymentIntentID: order.PaymentIntentID, ChannelTransactionID: order.ChannelTransactionID,
		ExpectedAmount: order.Amount, ActualAmount: actual, OccurredAt: order.CompletedAt.UTC(),
		FaultInjected: fault, FaultSeedRef: seedRef,
	}), nil
}

func refundStatementLine(statementIdentity string, refund StatementRefundFact) (domain.StatementLine, error) {
	return domain.FinalizeStatementLine(statementIdentity, 0, domain.StatementLine{
		LineType: "REFUND", LineStatus: "PRESENT", ChannelOrderID: refund.ChannelOrderID,
		ChannelRefundID: refund.ChannelRefundID, PaymentIntentID: refund.PaymentIntentID, RefundID: refund.RefundID,
		ChannelRefundTransactionID: refund.ChannelRefundTransactionID,
		ExpectedAmount:             refund.Amount, ActualAmount: refund.Amount, OccurredAt: refund.CompletedAt.UTC(),
	}), nil
}

func normalizeStatementScenarios(values []string) []string {
	seen := make(map[string]struct{}, len(values))
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value != "" {
			seen[value] = struct{}{}
		}
	}
	out := make([]string, 0, len(seen))
	for value := range seen {
		out = append(out, value)
	}
	sort.Strings(out)
	return out
}
func (s *Service) FreezeStatement(ctx context.Context, id, hash string, expected int64, operator, reason, corr, cause string) (*domain.ChannelStatement, error) {
	var st *domain.ChannelStatement
	err := s.tx(ctx, func(tx context.Context) error {
		var err error
		st, err = s.repo.LockStatement(tx, id)
		if err != nil {
			return derr("NOT_FOUND", "channel statement not found")
		}
		alreadyFrozen := st.Status == "FROZEN"
		old := st.Version
		if err := st.Freeze(hash, expected, time.Now().UTC(), operator, reason, corr, cause); err != nil {
			return mapRule(err)
		}
		if alreadyFrozen {
			return nil
		}
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

func (s *Service) ListStatementLines(ctx context.Context, statementID string, after int64, limit int) (*StatementLinePage, error) {
	statement, err := s.repo.GetStatement(ctx, statementID)
	if err != nil {
		return nil, derr("NOT_FOUND", "channel statement not found")
	}
	if after < 0 {
		after = 0
	}
	if limit <= 0 {
		limit = 100
	}
	if limit > 500 {
		limit = 500
	}
	lines, err := s.repo.ListStatementLines(ctx, statementID, after, limit)
	if err != nil {
		return nil, derr("UNAVAILABLE", "load channel statement lines: "+err.Error())
	}
	page := &StatementLinePage{Items: lines, LineCount: statement.LineCount, Limit: limit, After: after}
	if len(lines) == limit && after+int64(len(lines)) < int64(statement.LineCount) {
		next := after + int64(len(lines))
		page.NextCursor = &next
	}
	return page, nil
}

type OpenDiscrepancyRequest struct {
	ChannelStatementID, StatementLineID, ChannelOrderID, ChannelRefundID, FinanceReconciliationCaseID, DifferenceType, EvidenceRef, CorrelationID, SourceCommandID string
	ExpectedAmount, ActualAmount                                                                                                                                   domain.Money
}

func (s *Service) OpenDiscrepancy(ctx context.Context, r OpenDiscrepancyRequest) (*domain.ReconciliationDiscrepancy, error) {
	var d *domain.ReconciliationDiscrepancy
	err := s.tx(ctx, func(tx context.Context) error {
		if _, err := s.repo.GetStatement(tx, r.ChannelStatementID); err != nil {
			return derr("NOT_FOUND", "channel statement not found")
		}
		if r.StatementLineID != "" {
			line, err := s.repo.GetStatementLine(tx, r.ChannelStatementID, r.StatementLineID)
			if err != nil {
				return derr("NOT_FOUND", "statement line not found in channel statement")
			}
			if r.ChannelOrderID != "" && r.ChannelOrderID != line.ChannelOrderID {
				return derr("DOMAIN_RULE_VIOLATION", "channel order does not match statement line")
			}
			if r.ChannelRefundID != "" && r.ChannelRefundID != line.ChannelRefundID {
				return derr("DOMAIN_RULE_VIOLATION", "channel refund does not match statement line")
			}
			if r.ExpectedAmount != line.ExpectedAmount || r.ActualAmount != line.ActualAmount {
				return derr("DOMAIN_RULE_VIOLATION", "discrepancy amounts do not match statement line")
			}
			r.ChannelOrderID = line.ChannelOrderID
			r.ChannelRefundID = line.ChannelRefundID
		}
		var err error
		d, err = domain.NewDiscrepancy("", r.ChannelStatementID, r.StatementLineID, r.ChannelOrderID, r.ChannelRefundID, r.FinanceReconciliationCaseID, r.DifferenceType, r.ExpectedAmount, r.ActualAmount, r.EvidenceRef, time.Now().UTC(), r.CorrelationID, r.SourceCommandID)
		if err != nil {
			return derr("VALIDATION_FAILED", err.Error())
		}
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
	var d *domain.ReconciliationDiscrepancy
	err := s.tx(ctx, func(tx context.Context) error {
		var err error
		d, err = s.repo.GetDiscrepancy(tx, id)
		if err != nil {
			return derr("NOT_FOUND", "discrepancy not found")
		}
		terminal := d.Status == "RESOLVED" || d.Status == "REJECTED"
		old := d.Version
		if err := d.Resolve(status, ref, operator, reason, expected, time.Now().UTC(), corr, cause); err != nil {
			return mapRule(err)
		}
		if terminal {
			return nil
		}
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
