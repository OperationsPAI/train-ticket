// Package observability owns Seat Assignment's low-cardinality business
// telemetry. The contract is docs/07-observability/business-metrics.md.
//
// One raw instrument leaves this service:
//
//	train_ticket.seat.allocations (gauge) by state, travel_class, seat_mode
//
// Two properties are deliberate.
//
// The level comes from the durable seat_allocations table, not from process
// memory, so a restart republishes the same allocation counts instead of
// resetting them to zero.
//
// No identifying value is a label. Allocation, segment, seat map, coach, seat
// number, booking and traveler references are unbounded and stay out of the
// attribute set; a class or mode outside the allowlist collapses to "other".
package observability

import (
	"context"
	"sort"
	"strings"
	"sync"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

// RawSeatAllocations is the metric name before the Collector rewrites it into
// train_ticket_seat_allocations.
const RawSeatAllocations = "train_ticket.seat.allocations"

// Allocation states. The durable statuses ALLOCATED and STANDING are both live,
// unconfirmed physical placements, so they publish as one "allocated" state --
// which is the series the capacity/seat conservation check subtracts from.
const (
	AllocationStateAllocated = "allocated"
	AllocationStateConfirmed = "confirmed"
	AllocationStateReleased  = "released"
	AllocationStateExpired   = "expired"
)

// Travel classes and seat modes are closed vocabularies; anything else is
// "other".
var (
	travelClasses = map[string]string{
		"FIRST_CLASS":    "first_class",
		"FIRST":          "first_class",
		"SECOND_CLASS":   "second_class",
		"SECOND":         "second_class",
		"SLEEPER_HARD":   "sleeper_hard",
		"HARD_SLEEPER":   "sleeper_hard",
		"SLEEPER_SOFT":   "sleeper_soft",
		"SOFT_SLEEPER":   "sleeper_soft",
		"BUSINESS":       "business",
		"BUSINESS_CLASS": "business",
		"STANDING":       "standing",
		"STANDARD":       "standard",
	}
	seatModes = map[string]string{
		"SEAT":           "seat",
		"RECLINER":       "seat",
		"CHAIR":          "seat",
		"BERTH":          "berth",
		"SLEEPER":        "berth",
		"COUCHETTE":      "berth",
		"STANDING":       "standing",
		"STANDING_QUOTA": "standing",
	}
)

// TravelClass normalises a class reference into the bounded vocabulary.
func TravelClass(ref string) string {
	if class, ok := travelClasses[strings.ToUpper(strings.TrimSpace(ref))]; ok {
		return class
	}
	return "other"
}

// SeatMode normalises an allocation type into the bounded vocabulary.
func SeatMode(allocationType string) string {
	if mode, ok := seatModes[strings.ToUpper(strings.TrimSpace(allocationType))]; ok {
		return mode
	}
	return "other"
}

// DurableSeatAllocation is the projection of one seat_allocations row the gauge
// reads. The identifiers are carried so the collector can key rows; they never
// reach an attribute.
type DurableSeatAllocation struct {
	SeatAllocationID string
	Status           string
	RequestedClass   string
	OccupancyClass   string
	AllocationType   string
}

// SeatAllocationRow is one published series: closed enums only.
type SeatAllocationRow struct {
	State       string
	TravelClass string
	SeatMode    string
	Units       int64
}

// BuildSeatAllocationRows folds durable allocations into the published series.
//
// Pure, so the restart test can fold the same durable rows twice and prove the
// level repeats rather than resetting.
func BuildSeatAllocationRows(allocations []DurableSeatAllocation) []SeatAllocationRow {
	type key struct{ state, travelClass, seatMode string }
	counts := map[key]int64{}
	for _, allocation := range allocations {
		state, ok := allocationState(allocation.Status)
		if !ok {
			continue
		}
		// The requested class is what the traveler bought; a standing
		// substitution keeps it and changes only the seat mode, which is what
		// makes the capacity/seat conservation check joinable.
		travelClass := allocation.RequestedClass
		if strings.TrimSpace(travelClass) == "" {
			travelClass = allocation.OccupancyClass
		}
		k := key{state: state, travelClass: TravelClass(travelClass), seatMode: SeatMode(allocation.AllocationType)}
		counts[k]++
	}
	rows := make([]SeatAllocationRow, 0, len(counts))
	for k, units := range counts {
		rows = append(rows, SeatAllocationRow{State: k.state, TravelClass: k.travelClass, SeatMode: k.seatMode, Units: units})
	}
	sort.Slice(rows, func(i, j int) bool {
		if rows[i].State != rows[j].State {
			return rows[i].State < rows[j].State
		}
		if rows[i].TravelClass != rows[j].TravelClass {
			return rows[i].TravelClass < rows[j].TravelClass
		}
		return rows[i].SeatMode < rows[j].SeatMode
	})
	return rows
}

func allocationState(status string) (string, bool) {
	switch strings.ToUpper(strings.TrimSpace(status)) {
	case "ALLOCATED", "STANDING":
		return AllocationStateAllocated, true
	case "CONFIRMED":
		return AllocationStateConfirmed, true
	case "RELEASED":
		return AllocationStateReleased, true
	case "EXPIRED":
		return AllocationStateExpired, true
	default:
		return "", false
	}
}

// Recorder publishes a rebuilt level and, when metrics export is configured,
// exposes it as an observable gauge.
type Recorder struct {
	mu   sync.RWMutex
	rows []SeatAllocationRow
}

// NewRecorder builds an empty recorder. It holds no database handle: the
// collector hands it a level whenever it has read one.
func NewRecorder() *Recorder { return &Recorder{} }

// Record publishes a freshly reconstructed level.
func (r *Recorder) Record(rows []SeatAllocationRow) {
	if r == nil {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.rows = append([]SeatAllocationRow(nil), rows...)
}

// Published returns the level the gauge would observe right now.
func (r *Recorder) Published() []SeatAllocationRow {
	if r == nil {
		return nil
	}
	r.mu.RLock()
	defer r.mu.RUnlock()
	return append([]SeatAllocationRow(nil), r.rows...)
}

// Register installs the observable gauge. A service that has not enabled metric
// export passes a no-op meter and pays only the registration.
func (r *Recorder) Register(meter metric.Meter) (metric.Registration, error) {
	if r == nil {
		return nil, nil
	}
	gauge, err := meter.Int64ObservableGauge(
		RawSeatAllocations,
		metric.WithDescription("Physical seat and standing allocations by state, bounded travel class and seat mode."),
		metric.WithUnit("{unit}"),
	)
	if err != nil {
		return nil, err
	}
	return meter.RegisterCallback(func(_ context.Context, observer metric.Observer) error {
		for _, row := range r.Published() {
			observer.ObserveInt64(gauge, row.Units, metric.WithAttributes(
				attribute.String("state", row.State),
				attribute.String("travel_class", row.TravelClass),
				attribute.String("seat_mode", row.SeatMode),
			))
		}
		return nil
	}, gauge)
}
