package metrics

import (
	"context"
	"testing"

	"go.opentelemetry.io/otel/attribute"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
)

func TestPublishesContractNamesUnitsAndOnlyBoundedLabels(t *testing.T) {
	reader := sdkmetric.NewManualReader()
	provider := sdkmetric.NewMeterProvider(sdkmetric.WithReader(reader))
	defer func() { _ = provider.Shutdown(context.Background()) }()

	recorder, err := New(provider.Meter("payment-channel-test"))
	if err != nil {
		t.Fatalf("build recorder: %v", err)
	}
	ctx := context.Background()
	recorder.Operation(ctx, OperationCapture, OutcomeSucceeded, "CNY", "ALIPAY_SIM")
	recorder.Amount(ctx, OperationCapture, OutcomeSucceeded, "CNY", "ALIPAY_SIM", 1234)
	recorder.Operation(ctx, OperationRefund, OutcomeMissed, "USD", "not-a-rail")

	var rm metricdata.ResourceMetrics
	if err := reader.Collect(ctx, &rm); err != nil {
		t.Fatalf("collect: %v", err)
	}

	operations := findMetric(t, rm, "train_ticket.payment.operations")
	if operations.Unit != "{operation}" {
		t.Fatalf("operations unit = %q, want {operation}", operations.Unit)
	}
	amount := findMetric(t, rm, "train_ticket.payment.amount")
	if amount.Unit != "minor_units" {
		t.Fatalf("amount unit = %q, want minor_units", amount.Unit)
	}

	operationsSum := operations.Data.(metricdata.Sum[int64])
	if len(operationsSum.DataPoints) != 2 {
		t.Fatalf("operations data points = %d, want 2", len(operationsSum.DataPoints))
	}
	for _, point := range operationsSum.DataPoints {
		assertBoundedLabels(t, point.Attributes)
	}
	if got := channelOf(t, operationsSum.DataPoints, "USD"); got != "other" {
		t.Fatalf("unknown channel recorded as %q, want other", got)
	}

	amountSum := amount.Data.(metricdata.Sum[int64])
	if len(amountSum.DataPoints) != 1 || amountSum.DataPoints[0].Value != 1234 {
		t.Fatalf("amount data points = %+v, want a single 1234 minor unit value", amountSum.DataPoints)
	}
}

// The recorder has no parameter for a channel order id, refund id or
// transaction id, and the label set is exactly the four contract keys. Two
// different business identifiers therefore cannot produce two series.
func TestSameLabelsCollapseToASinglePoint(t *testing.T) {
	reader := sdkmetric.NewManualReader()
	provider := sdkmetric.NewMeterProvider(sdkmetric.WithReader(reader))
	defer func() { _ = provider.Shutdown(context.Background()) }()

	recorder, err := New(provider.Meter("payment-channel-test"))
	if err != nil {
		t.Fatalf("build recorder: %v", err)
	}
	ctx := context.Background()
	recorder.Operation(ctx, OperationCapture, OutcomeSucceeded, "CNY", "ALIPAY_SIM")
	recorder.Operation(ctx, OperationCapture, OutcomeSucceeded, "CNY", "ALIPAY_SIM")

	var rm metricdata.ResourceMetrics
	if err := reader.Collect(ctx, &rm); err != nil {
		t.Fatalf("collect: %v", err)
	}
	sum := findMetric(t, rm, "train_ticket.payment.operations").Data.(metricdata.Sum[int64])
	if len(sum.DataPoints) != 1 || sum.DataPoints[0].Value != 2 {
		t.Fatalf("want one point of value 2, got %+v", sum.DataPoints)
	}
	assertBoundedLabels(t, sum.DataPoints[0].Attributes)
}

func TestCurrencyAndChannelNormalizeUnknownValues(t *testing.T) {
	if got := Currency("cny"); got != "CNY" {
		t.Fatalf("Currency(cny) = %q", got)
	}
	if got := Currency("dollars"); got != Other {
		t.Fatalf("Currency(dollars) = %q, want other", got)
	}
	if got := Channel("ALIPAY"); got != "alipay" {
		t.Fatalf("Channel(ALIPAY) = %q", got)
	}
	if got := Channel("WECHAT_SIM"); got != "wechat_pay" {
		t.Fatalf("Channel(WECHAT_SIM) = %q", got)
	}
	if got := Channel("rail-7"); got != Other {
		t.Fatalf("Channel(rail-7) = %q, want other", got)
	}
}

func findMetric(t *testing.T, rm metricdata.ResourceMetrics, name string) metricdata.Metrics {
	t.Helper()
	for _, scope := range rm.ScopeMetrics {
		for _, metric := range scope.Metrics {
			if metric.Name == name {
				return metric
			}
		}
	}
	t.Fatalf("metric %s was not exported", name)
	return metricdata.Metrics{}
}

func assertBoundedLabels(t *testing.T, attrs attribute.Set) {
	t.Helper()
	want := map[string]bool{"operation": false, "outcome": false, "currency": false, "channel": false}
	for _, kv := range attrs.ToSlice() {
		if _, ok := want[string(kv.Key)]; !ok {
			t.Fatalf("unexpected label %q in %v", kv.Key, attrs.ToSlice())
		}
		want[string(kv.Key)] = true
	}
	for key, seen := range want {
		if !seen {
			t.Fatalf("missing label %q in %v", key, attrs.ToSlice())
		}
	}
}

func channelOf(t *testing.T, points []metricdata.DataPoint[int64], currency string) string {
	t.Helper()
	for _, point := range points {
		if value, ok := point.Attributes.Value(attribute.Key("currency")); ok && value.AsString() == currency {
			channel, _ := point.Attributes.Value(attribute.Key("channel"))
			return channel.AsString()
		}
	}
	t.Fatalf("no data point for currency %s", currency)
	return ""
}
