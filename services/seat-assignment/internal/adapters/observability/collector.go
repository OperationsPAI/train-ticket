package observability

import (
	"context"
	"log"
	"time"
)

// AllocationSource reads the durable rows the gauge is folded from. The
// production implementation is the Postgres repository; a test supplies a fake,
// which is how the restart case is covered without a database.
type AllocationSource interface {
	LoadSeatAllocationsForMetrics(ctx context.Context) ([]DurableSeatAllocation, error)
}

// RefreshInterval is how often the level is rebuilt. Allocations live for the
// life of a reservation, so a minute of staleness is far below the resolution
// the conservation check is read at, and the fold is a single indexed scan.
const RefreshInterval = time.Minute

// Collector rebuilds the seat allocation level from durable rows on a timer.
type Collector struct {
	recorder *Recorder
	source   AllocationSource
}

func NewCollector(recorder *Recorder, source AllocationSource) *Collector {
	return &Collector{recorder: recorder, source: source}
}

// Refresh reads the durable rows and publishes the folded level.
func (c *Collector) Refresh(ctx context.Context) error {
	if c == nil || c.recorder == nil || c.source == nil {
		return nil
	}
	allocations, err := c.source.LoadSeatAllocationsForMetrics(ctx)
	if err != nil {
		return err
	}
	c.recorder.Record(BuildSeatAllocationRows(allocations))
	return nil
}

// Run refreshes on a timer until the context ends.
//
// A failed refresh leaves the previous level in place and logs: an unreadable
// table is unknown, not zero, and publishing zero would read as "every seat
// freed", which is the one signal a conservation check must not fabricate.
func (c *Collector) Run(ctx context.Context) {
	if c == nil {
		return
	}
	if err := c.Refresh(ctx); err != nil {
		log.Printf("seat-assignment business metric refresh failed: %v", err)
	}
	ticker := time.NewTicker(RefreshInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := c.Refresh(ctx); err != nil {
				log.Printf("seat-assignment business metric refresh failed: %v", err)
			}
		}
	}
}
