package application

import (
	"context"
	"testing"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
	"github.com/trainticket/greenfield/services/payment-channel/internal/domain"
	"github.com/trainticket/greenfield/services/payment-channel/internal/metrics"
)

type recordedOperation struct {
	operation metrics.Operation
	outcome   metrics.Outcome
	currency  string
	channel   string
}

type recordedAmount struct {
	operation  metrics.Operation
	outcome    metrics.Outcome
	currency   string
	channel    string
	minorUnits int64
}

type recordingMetrics struct {
	operations []recordedOperation
	amounts    []recordedAmount
}

func (m *recordingMetrics) Operation(_ context.Context, operation metrics.Operation, outcome metrics.Outcome, currency, channel string) {
	m.operations = append(m.operations, recordedOperation{operation, outcome, metrics.Currency(currency), metrics.Channel(channel)})
}

func (m *recordingMetrics) Amount(_ context.Context, operation metrics.Operation, outcome metrics.Outcome, currency, channel string, minorUnits int64) {
	m.amounts = append(m.amounts, recordedAmount{operation, outcome, metrics.Currency(currency), metrics.Channel(channel), minorUnits})
}

// Two channel orders with different ids move the same currency through the same
// channel, so they must produce one label set: a per-order series would make the
// metric unbounded.
func TestTwoOrdersRecordOneBoundedCaptureLabelSet(t *testing.T) {
	repo := newMem()
	recorder := &recordingMetrics{}
	svc := NewWithMetrics(repo, &pub{}, nil, recorder)
	ctx := context.Background()

	for _, businessRef := range []string{"ord-a", "ord-b"} {
		order, err := svc.CreateOrder(ctx, CreateOrderRequest{
			PaymentIntentID: "pi-" + businessRef, BusinessRef: businessRef, Purpose: "purchase",
			Channel: domain.ChannelAlipay, Amount: domain.Money{Currency: "CNY", MinorUnits: 100},
			IdempotencyKey: ids.NewUUIDv7(), SourceCommandID: ids.NewCommandID(), CorrelationID: ids.NewCorrelationID(),
		})
		if err != nil {
			t.Fatalf("create order %s: %v", businessRef, err)
		}
		if _, err := svc.SubmitOrder(ctx, order.ChannelOrderID, order.Version, order.RequestFingerprint); err != nil {
			t.Fatalf("submit order %s: %v", businessRef, err)
		}
	}

	captures := operationsOf(recorder, metrics.OperationCapture)
	if len(captures) != 2 {
		t.Fatalf("capture operations = %d, want 2", len(captures))
	}
	if captures[0] != captures[1] {
		t.Fatalf("two orders produced different label sets: %+v vs %+v", captures[0], captures[1])
	}
	if captures[0].outcome != metrics.OutcomeSucceeded || captures[0].currency != "CNY" || captures[0].channel != "alipay" {
		t.Fatalf("unexpected capture labels: %+v", captures[0])
	}
	amounts := amountsOf(recorder, metrics.OperationCapture)
	if len(amounts) != 2 || amounts[0].minorUnits != 100 || amounts[0].currency != "CNY" {
		t.Fatalf("unexpected capture amounts: %+v", amounts)
	}
}

// A missed order is one missed attempt and one recovery. Re-querying a terminal
// order must not record a second capture.
func TestMissedOrderRecordsMissedThenRecoveredOnce(t *testing.T) {
	repo := newMem()
	recorder := &recordingMetrics{}
	svc := NewWithMetrics(repo, &pub{}, nil, recorder)
	ctx := context.Background()
	seed := &domain.FaultSeed{SeedVersion: "v1", ScenarioCode: "MISSED_ORDER", SeedMaterialHash: "abc"}

	order, err := svc.CreateOrder(ctx, CreateOrderRequest{
		PaymentIntentID: "pi-missed", BusinessRef: "ord-missed", Purpose: "purchase",
		Channel: domain.ChannelWechat, Amount: domain.Money{Currency: "CNY", MinorUnits: 250},
		IdempotencyKey: ids.NewUUIDv7(), SourceCommandID: ids.NewCommandID(), CorrelationID: ids.NewCorrelationID(),
		FaultSeed: seed,
	})
	if err != nil {
		t.Fatalf("create order: %v", err)
	}
	if _, err := svc.SubmitOrder(ctx, order.ChannelOrderID, order.Version, order.RequestFingerprint); err != nil {
		t.Fatalf("submit order: %v", err)
	}
	order, err = svc.QueryOrder(ctx, order.ChannelOrderID, order.Version, "PAYMENT_TIMEOUT")
	if err != nil {
		t.Fatalf("recover order: %v", err)
	}
	if _, err := svc.QueryOrder(ctx, order.ChannelOrderID, order.Version, "STILL_TERMINAL"); err != nil {
		t.Fatalf("re-query terminal order: %v", err)
	}

	if got := len(capturesWith(recorder, metrics.OutcomeMissed)); got != 1 {
		t.Fatalf("missed captures = %d, want 1", got)
	}
	if got := len(capturesWith(recorder, metrics.OutcomeSucceeded)); got != 1 {
		t.Fatalf("succeeded captures = %d, want exactly the one recovery", got)
	}
}

// Refund amounts carry their own currency label, so CNY and USD can never be
// added together, and a declined refund is a failed outcome rather than money
// that moved.
func TestRefundOutcomesAreRecordedPerCurrency(t *testing.T) {
	repo := newMem()
	recorder := &recordingMetrics{}
	svc := NewWithMetrics(repo, &pub{}, nil, recorder)

	refund(t, svc, "ord-cny", "CNY", 400, nil)
	refund(t, svc, "ord-usd", "USD", 900, &domain.FaultSeed{SeedVersion: "v1", ScenarioCode: "DECLINED_REFUND", SeedMaterialHash: "abc"})

	amounts := amountsOf(recorder, metrics.OperationRefund)
	if len(amounts) != 2 {
		t.Fatalf("refund amounts = %d, want 2", len(amounts))
	}
	byCurrency := map[string]recordedAmount{}
	for _, amount := range amounts {
		byCurrency[amount.currency] = amount
	}
	if byCurrency["CNY"].minorUnits != 400 || byCurrency["CNY"].outcome != metrics.OutcomeSucceeded {
		t.Fatalf("CNY refund = %+v", byCurrency["CNY"])
	}
	if byCurrency["USD"].minorUnits != 900 || byCurrency["USD"].outcome != metrics.OutcomeFailed {
		t.Fatalf("USD refund = %+v", byCurrency["USD"])
	}
	for _, amount := range amounts {
		if amount.minorUnits == 1300 {
			t.Fatalf("currencies were summed: %+v", amount)
		}
	}
}

func refund(t *testing.T, svc *Service, businessRef, currency string, minorUnits int64, seed *domain.FaultSeed) {
	t.Helper()
	ctx := context.Background()
	amount := domain.Money{Currency: currency, MinorUnits: minorUnits}
	order, err := svc.CreateOrder(ctx, CreateOrderRequest{
		PaymentIntentID: "pi-" + businessRef, BusinessRef: businessRef, Purpose: "purchase",
		Channel: domain.ChannelAlipay, Amount: amount,
		IdempotencyKey: ids.NewUUIDv7(), SourceCommandID: ids.NewCommandID(), CorrelationID: ids.NewCorrelationID(),
	})
	if err != nil {
		t.Fatalf("create order: %v", err)
	}
	order, err = svc.SubmitOrder(ctx, order.ChannelOrderID, order.Version, order.RequestFingerprint)
	if err != nil {
		t.Fatalf("submit order: %v", err)
	}
	created, err := svc.CreateRefund(ctx, CreateRefundRequest{
		RefundID: "rf-" + businessRef, PaymentIntentID: order.PaymentIntentID,
		ChannelOrderID: order.ChannelOrderID, OriginalChannelTransactionID: order.ChannelTransactionID,
		Channel: order.Channel, Amount: amount, RefundReasonCode: "USER",
		IdempotencyKey: ids.NewUUIDv7(), SourceCommandID: ids.NewCommandID(), CorrelationID: ids.NewCorrelationID(),
		FaultSeed: seed,
	})
	if err != nil {
		t.Fatalf("create refund: %v", err)
	}
	if _, err := svc.SubmitRefund(ctx, created.ChannelRefundID, created.Version, created.RequestFingerprint); err != nil {
		t.Fatalf("submit refund: %v", err)
	}
}

func operationsOf(recorder *recordingMetrics, operation metrics.Operation) []recordedOperation {
	out := []recordedOperation{}
	for _, recorded := range recorder.operations {
		if recorded.operation == operation {
			out = append(out, recorded)
		}
	}
	return out
}

func capturesWith(recorder *recordingMetrics, outcome metrics.Outcome) []recordedOperation {
	out := []recordedOperation{}
	for _, recorded := range operationsOf(recorder, metrics.OperationCapture) {
		if recorded.outcome == outcome {
			out = append(out, recorded)
		}
	}
	return out
}

func amountsOf(recorder *recordingMetrics, operation metrics.Operation) []recordedAmount {
	out := []recordedAmount{}
	for _, recorded := range recorder.amounts {
		if recorded.operation == operation {
			out = append(out, recorded)
		}
	}
	return out
}
