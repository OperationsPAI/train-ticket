package observability

import (
	"context"
	"testing"

	metricsdk "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
)

// Two different fulfillment records must publish into one series. The label set
// cannot grow with the number of travelers.
func TestDifferentIdentifiersProduceAnIdenticalLabelSet(t *testing.T) {
	reader := metricsdk.NewManualReader()
	provider := metricsdk.NewMeterProvider(metricsdk.WithReader(reader))
	defer func() { _ = provider.Shutdown(context.Background()) }()

	recorder, err := NewOpenTelemetryRecorder(provider.Meter("fulfillment-test"))
	if err != nil {
		t.Fatalf("build recorder: %v", err)
	}
	// Two different fulfillment records: different record, entitlement, segment
	// booking, journey order, traveler and segment references. The recorder never
	// sees them, which is the point of the seam.
	recorder.Transition(OutcomeAccepted, TicketTypeUnknown, ReasonNone)
	recorder.Transition(OutcomeAccepted, TicketTypeUnknown, ReasonNone)

	points := transitionPoints(t, reader)
	if len(points) != 1 {
		t.Fatalf("two records share one series, got %d: %#v", len(points), points)
	}
	if points[0].Value != 2 {
		t.Fatalf("expected the shared series to carry both transitions, got %d", points[0].Value)
	}
	allowed := map[string]bool{
		"stage": true, "outcome": true, "ticket_type": true, "reason_code": true,
	}
	for _, kv := range points[0].Attributes.ToSlice() {
		if !allowed[string(kv.Key)] {
			t.Fatalf("unexpected attribute %q", kv.Key)
		}
		value := kv.Value.AsString()
		if value == "fr-1" || value == "ent-1" || value == "sb-1" || value == "seg-1" {
			t.Fatalf("an identifier reached a label: %s=%s", kv.Key, value)
		}
	}
}

// The domain returns plain fmt.Errorf messages that carry record identifiers.
// None of that text may reach a label.
func TestReasonCodeNarrowsAnyValueToTheOwnedVocabulary(t *testing.T) {
	for _, reason := range []string{ReasonNone, ReasonPreconditionsUnmet, ReasonOther} {
		if got := ReasonCode(reason); got != reason {
			t.Fatalf("owned reason %q was rewritten to %q", reason, got)
		}
	}
	leaked := "cannot complete fulfillment fr-0194f2e0-7b3e-7610-8284-5c26e8b0aa01 in status READY"
	if got := ReasonCode(leaked); got != ReasonOther {
		t.Fatalf("an exception message has to collapse to other, got %q", got)
	}
	if got := Outcome("exploded"); got != OutcomeFailed {
		t.Fatalf("an unknown outcome has to collapse to failed, got %q", got)
	}
	if got := TicketType("fr-0194f2e0"); got != TicketTypeUnknown {
		t.Fatalf("an unknown ticket type has to collapse to unknown, got %q", got)
	}
}

func transitionPoints(t *testing.T, reader metricsdk.Reader) []metricdata.DataPoint[int64] {
	t.Helper()
	var collected metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &collected); err != nil {
		t.Fatalf("collect: %v", err)
	}
	for _, scope := range collected.ScopeMetrics {
		for _, metric := range scope.Metrics {
			if metric.Name != RawTicketingTransitions {
				continue
			}
			sum, ok := metric.Data.(metricdata.Sum[int64])
			if !ok {
				t.Fatalf("%s is %T, not an int sum", metric.Name, metric.Data)
			}
			return sum.DataPoints
		}
	}
	t.Fatalf("missing %s", RawTicketingTransitions)
	return nil
}
