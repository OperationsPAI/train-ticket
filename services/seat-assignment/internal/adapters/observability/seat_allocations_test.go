package observability

import (
	"context"
	"testing"

	metricsdk "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
)

// The durable rows differ in every identifying field. The published label sets
// must not.
func TestDifferentIdentifiersProduceAnIdenticalLabelSet(t *testing.T) {
	first := []DurableSeatAllocation{{
		SeatAllocationID: "salloc-aaaa",
		Status:           "ALLOCATED",
		RequestedClass:   "SECOND_CLASS",
		OccupancyClass:   "SECOND_CLASS",
		AllocationType:   "SEAT",
	}}
	second := []DurableSeatAllocation{{
		SeatAllocationID: "salloc-bbbb",
		Status:           "ALLOCATED",
		RequestedClass:   "SECOND_CLASS",
		OccupancyClass:   "SECOND_CLASS",
		AllocationType:   "SEAT",
	}}

	firstRows := BuildSeatAllocationRows(first)
	secondRows := BuildSeatAllocationRows(second)
	if len(firstRows) != 1 || len(secondRows) != 1 {
		t.Fatalf("expected one series each, got %#v and %#v", firstRows, secondRows)
	}
	labels := func(row SeatAllocationRow) [3]string {
		return [3]string{row.State, row.TravelClass, row.SeatMode}
	}
	if labels(firstRows[0]) != labels(secondRows[0]) {
		t.Fatalf("identifiers leaked into the label set: %v vs %v", labels(firstRows[0]), labels(secondRows[0]))
	}
}

// A restart must republish the same level, not a reset to zero.
func TestRestartReconstructsTheSameLevel(t *testing.T) {
	durable := []DurableSeatAllocation{
		{SeatAllocationID: "salloc-1", Status: "ALLOCATED", RequestedClass: "SECOND_CLASS", OccupancyClass: "SECOND_CLASS", AllocationType: "SEAT"},
		{SeatAllocationID: "salloc-2", Status: "ALLOCATED", RequestedClass: "SECOND_CLASS", OccupancyClass: "SECOND_CLASS", AllocationType: "SEAT"},
		{SeatAllocationID: "salloc-3", Status: "STANDING", RequestedClass: "SECOND_CLASS", OccupancyClass: "STANDING", AllocationType: "STANDING"},
		{SeatAllocationID: "salloc-4", Status: "CONFIRMED", RequestedClass: "SLEEPER_HARD", OccupancyClass: "SLEEPER_HARD", AllocationType: "BERTH"},
		{SeatAllocationID: "salloc-5", Status: "RELEASED", RequestedClass: "SLEEPER_HARD", OccupancyClass: "SLEEPER_HARD", AllocationType: "BERTH"},
	}

	before := BuildSeatAllocationRows(durable)
	// A second process folding the same durable rows is a restart.
	after := BuildSeatAllocationRows(durable)
	if len(before) != len(after) {
		t.Fatalf("restart changed the series set: %#v vs %#v", before, after)
	}
	for i := range before {
		if before[i] != after[i] {
			t.Fatalf("restart changed series %d: %#v vs %#v", i, before[i], after[i])
		}
	}
	if units(before, AllocationStateAllocated, "second_class", "seat") != 2 {
		t.Fatalf("allocated seats did not survive the restart: %#v", before)
	}
	if units(before, AllocationStateAllocated, "second_class", "standing") != 1 {
		t.Fatalf("standing substitution did not survive the restart: %#v", before)
	}
	if units(before, AllocationStateConfirmed, "sleeper_hard", "berth") != 1 {
		t.Fatalf("confirmed berth did not survive the restart: %#v", before)
	}
}

// The standing-substitution case the capacity/seat conservation check exists
// for: the traveler bought SECOND_CLASS and the unit delivered is standing.
func TestStandingSubstitutionKeepsTheRequestedClass(t *testing.T) {
	rows := BuildSeatAllocationRows([]DurableSeatAllocation{{
		SeatAllocationID: "salloc-standing",
		Status:           "STANDING",
		RequestedClass:   "SECOND_CLASS",
		OccupancyClass:   "STANDING",
		AllocationType:   "STANDING",
	}})
	if len(rows) != 1 {
		t.Fatalf("expected one series, got %#v", rows)
	}
	if rows[0].TravelClass != "second_class" || rows[0].SeatMode != "standing" {
		t.Fatalf("standing substitutions must publish the accepted class and standing mode: %#v", rows[0])
	}
}

func TestUnexpectedClassAndModeCollapseToOther(t *testing.T) {
	rows := BuildSeatAllocationRows([]DurableSeatAllocation{{
		SeatAllocationID: "salloc-weird",
		Status:           "ALLOCATED",
		RequestedClass:   "MYSTERY-CLASS-42",
		OccupancyClass:   "MYSTERY-CLASS-42",
		AllocationType:   "HAMMOCK",
	}})
	if len(rows) != 1 || rows[0].TravelClass != "other" || rows[0].SeatMode != "other" {
		t.Fatalf("an unknown class or mode has to collapse to other: %#v", rows)
	}
}

// The observed attributes are read back from the SDK, so a seat, coach or
// segment reference in a label would fail here rather than merely look odd.
func TestEmittedAttributesAreOnlyTheClosedEnums(t *testing.T) {
	reader := metricsdk.NewManualReader()
	provider := metricsdk.NewMeterProvider(metricsdk.WithReader(reader))
	defer func() { _ = provider.Shutdown(context.Background()) }()

	recorder := NewRecorder()
	registration, err := recorder.Register(provider.Meter("seat-assignment-test"))
	if err != nil {
		t.Fatalf("register gauge: %v", err)
	}
	defer func() { _ = registration.Unregister() }()

	recorder.Record(BuildSeatAllocationRows([]DurableSeatAllocation{
		{SeatAllocationID: "salloc-1", Status: "ALLOCATED", RequestedClass: "SECOND_CLASS", OccupancyClass: "SECOND_CLASS", AllocationType: "SEAT"},
		{SeatAllocationID: "salloc-2", Status: "STANDING", RequestedClass: "SECOND_CLASS", OccupancyClass: "STANDING", AllocationType: "STANDING"},
	}))

	metricName := RawSeatAllocations
	var collected metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &collected); err != nil {
		t.Fatalf("collect: %v", err)
	}
	points := gaugePoints(t, collected, metricName)
	if len(points) != 2 {
		t.Fatalf("expected two series, got %#v", points)
	}
	allowed := map[string]bool{"state": true, "travel_class": true, "seat_mode": true}
	for _, point := range points {
		for _, kv := range point.Attributes.ToSlice() {
			if !allowed[string(kv.Key)] {
				t.Fatalf("unexpected attribute %q", kv.Key)
			}
			value := kv.Value.AsString()
			if value == "salloc-1" || value == "salloc-2" || value == "SECOND_CLASS" {
				t.Fatalf("an identifier reached a label: %s=%s", kv.Key, value)
			}
		}
	}
}

// An unreadable table is unknown, not empty: the previous level has to stay.
func TestCollectorKeepsThePreviousLevelWhenTheSourceFails(t *testing.T) {
	recorder := NewRecorder()
	recorder.Record([]SeatAllocationRow{{State: AllocationStateAllocated, TravelClass: "second_class", SeatMode: "seat", Units: 3}})

	collector := NewCollector(recorder, failingSource{})
	if err := collector.Refresh(context.Background()); err == nil {
		t.Fatal("expected the source failure to surface")
	}
	if got := recorder.Published(); len(got) != 1 || got[0].Units != 3 {
		t.Fatalf("a failed refresh must not blank the level: %#v", got)
	}
}

type failingSource struct{}

func (failingSource) LoadSeatAllocationsForMetrics(context.Context) ([]DurableSeatAllocation, error) {
	return nil, context.DeadlineExceeded
}

func units(rows []SeatAllocationRow, state, travelClass, seatMode string) int64 {
	for _, row := range rows {
		if row.State == state && row.TravelClass == travelClass && row.SeatMode == seatMode {
			return row.Units
		}
	}
	return -1
}

func gaugePoints(t *testing.T, collected metricdata.ResourceMetrics, name string) []metricdata.DataPoint[int64] {
	t.Helper()
	for _, scope := range collected.ScopeMetrics {
		for _, metric := range scope.Metrics {
			if metric.Name != name {
				continue
			}
			gauge, ok := metric.Data.(metricdata.Gauge[int64])
			if !ok {
				t.Fatalf("%s is %T, not an int gauge", name, metric.Data)
			}
			return gauge.DataPoints
		}
	}
	t.Fatalf("missing %s", name)
	return nil
}
