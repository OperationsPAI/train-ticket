package domain

import (
	"testing"
	"time"
)

func TestContractSeatAllocationReleasePreservesStandingSeatRef(t *testing.T) {
	allocation := &ContractSeatAllocation{
		SeatAllocationID: "salloc-standing",
		Status:           AllocationStatusStanding,
		SeatRef: SeatRef{
			SeatAllocationID: "salloc-standing",
			AllocationType:   AllocationTypeStanding,
			DisplayLabel:     "STANDING",
		},
	}
	releasedAt := time.Date(2026, 8, 2, 12, 45, 0, 0, time.UTC)

	allocation.Release(releasedAt)

	if allocation.Status != AllocationStatusReleased {
		t.Fatalf("status=%s want %s", allocation.Status, AllocationStatusReleased)
	}
	if allocation.ReleasedAt == nil || !allocation.ReleasedAt.Equal(releasedAt) {
		t.Fatalf("releasedAt=%v want %v", allocation.ReleasedAt, releasedAt)
	}
	if allocation.SeatRef.AllocationType != AllocationTypeStanding {
		t.Fatalf("allocationType=%s want %s", allocation.SeatRef.AllocationType, AllocationTypeStanding)
	}
	if allocation.SeatRef.DisplayLabel != "STANDING" {
		t.Fatalf("displayLabel=%s want STANDING", allocation.SeatRef.DisplayLabel)
	}
}
