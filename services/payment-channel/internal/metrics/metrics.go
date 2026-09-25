// Package metrics publishes the bounded train_ticket.payment.* business
// contract from the channel boundary.
//
// Payment Channel is where the simulated provider actually moves money, so it
// reports the capture and refund operations it settles. The label vocabulary is
// the same closed set the Java payment and post-sales services use, which is
// what lets one recording rule aggregate every service. No channel order id,
// refund id, transaction id or provider message is ever a label: an
// unrecognized value collapses to "other".
package metrics

import (
	"context"
	"strings"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

// Other is the fallback label value for anything outside the closed vocabulary.
const Other = "other"

// Operation is one of the four money movements the contract distinguishes.
type Operation string

const (
	OperationCapture  Operation = "capture"
	OperationRefund   Operation = "refund"
	OperationReversal Operation = "reversal"
)

// Outcome is the bounded disposition of an operation.
type Outcome string

const (
	OutcomeSucceeded Outcome = "succeeded"
	OutcomeFailed    Outcome = "failed"
	OutcomeRejected  Outcome = "rejected"
	OutcomeMissed    Outcome = "missed"
	OutcomePending   Outcome = "pending"
	OutcomeVerified  Outcome = "verified"
	OutcomeOther     Outcome = Other
)

// Recorder is the seam the service records through.
type Recorder interface {
	Operation(ctx context.Context, operation Operation, outcome Outcome, currency, channel string)
	Amount(ctx context.Context, operation Operation, outcome Outcome, currency, channel string, minorUnits int64)
}

// Noop drops every reading; it is the default when metrics are not installed.
type Noop struct{}

func (Noop) Operation(context.Context, Operation, Outcome, string, string)     {}
func (Noop) Amount(context.Context, Operation, Outcome, string, string, int64) {}

// OTel records through the process meter. Amounts are integer minor units and
// carry the currency they belong to, so a query can never sum two currencies.
type OTel struct {
	operations metric.Int64Counter
	amount     metric.Int64Counter
}

// New builds the contract instruments on meter.
func New(meter metric.Meter) (*OTel, error) {
	operations, err := meter.Int64Counter("train_ticket.payment.operations",
		metric.WithUnit("{operation}"),
		metric.WithDescription("Capture, refund, reversal and compensation operation outcomes."))
	if err != nil {
		return nil, err
	}
	amount, err := meter.Int64Counter("train_ticket.payment.amount",
		metric.WithUnit("minor_units"),
		metric.WithDescription("Amount processed by payment operations, in integer minor units."))
	if err != nil {
		return nil, err
	}
	return &OTel{operations: operations, amount: amount}, nil
}

func (m *OTel) Operation(ctx context.Context, operation Operation, outcome Outcome, currency, channel string) {
	m.operations.Add(ctx, 1, metric.WithAttributes(labels(operation, outcome, currency, channel)...))
}

func (m *OTel) Amount(ctx context.Context, operation Operation, outcome Outcome, currency, channel string, minorUnits int64) {
	if minorUnits <= 0 {
		return
	}
	m.amount.Add(ctx, minorUnits, metric.WithAttributes(labels(operation, outcome, currency, channel)...))
}

func labels(operation Operation, outcome Outcome, currency, channel string) []attribute.KeyValue {
	return []attribute.KeyValue{
		attribute.String("operation", string(operation)),
		attribute.String("outcome", string(outcome)),
		attribute.String("currency", Currency(currency)),
		attribute.String("channel", Channel(channel)),
	}
}

// Currency keeps a three-letter ISO code and collapses anything else, so a
// malformed value cannot create a series while two currencies stay separable.
func Currency(code string) string {
	normalized := strings.ToUpper(strings.TrimSpace(code))
	if len(normalized) != 3 {
		return Other
	}
	for _, r := range normalized {
		if r < 'A' || r > 'Z' {
			return Other
		}
	}
	return normalized
}

// Channel maps both the internal and the channel-facing simulated ids onto one
// bounded value.
func Channel(channelID string) string {
	switch strings.ToUpper(strings.TrimSpace(channelID)) {
	case "ALIPAY", "ALIPAY_SIM":
		return "alipay"
	case "WECHAT", "WECHAT_PAY", "WECHAT_SIM":
		return "wechat_pay"
	case "UNIONPAY", "UNIONPAY_SIM":
		return "unionpay"
	case "WALLET", "PROMOTION_CREDIT":
		return "wallet"
	default:
		return Other
	}
}
