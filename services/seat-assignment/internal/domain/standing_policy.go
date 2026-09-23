package domain

// Standing quota is derived from assignable second class seats in the published composition.
const StandingPolicyVersion = "standing-second-class-10pct-v1"

type StandingPolicy struct {
	Version    string `json:"version"`
	BasisClass string `json:"basisClass"`
	BasisUnits int    `json:"basisUnits"`
	Percent    int    `json:"percent"`
	Limit      int    `json:"limit"`
}

func (m ContractSeatMap) StandingPolicy() StandingPolicy {
	basis := len(m.AssignableUnits("SECOND_CLASS"))
	return StandingPolicy{Version: StandingPolicyVersion, BasisClass: "SECOND_CLASS", BasisUnits: basis, Percent: 10, Limit: basis / 10}
}

func StandingPolicyMap(maps []ContractSeatMap) *ContractSeatMap {
	for i := range maps {
		for _, coach := range maps[i].Coaches {
			if coach.ClassRef == "SECOND_CLASS" {
				return &maps[i]
			}
		}
	}
	return nil
}

// Maximum simultaneous occupancy within the requested interval includes historical standing allocations.
func StandingOccupancy(active []ContractSeatAllocation, interval StationInterval) int {
	points := []int{interval.FromSeq}
	for _, a := range active {
		if a.SeatRef.AllocationType == AllocationTypeStanding && a.Interval.Overlaps(interval) && a.Interval.FromSeq > interval.FromSeq {
			points = append(points, a.Interval.FromSeq)
		}
	}
	peak := 0
	for _, point := range points {
		count := 0
		for _, a := range active {
			if a.SeatRef.AllocationType == AllocationTypeStanding && a.Interval.FromSeq <= point && point < a.Interval.ToSeq {
				count++
			}
		}
		if count > peak {
			peak = count
		}
	}
	return peak
}
