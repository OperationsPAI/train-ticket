package application

import "strings"

// publishedInterval names the stop positions the published interval covers.
// The contract writes the interval half open, so the end names the first
// position past the segment and a caller reads the bound the service states.
func (s *Service) publishedInterval(stops []SupplyStop) (int, int) {
	first, last := stops[0].Sequence, stops[len(stops)-1].Sequence
	if strings.EqualFold(strings.TrimSpace(s.supply.IntervalBound), SupplyIntervalBoundExclusive) {
		last = last + 1
	}
	return first, last
}

// stopSequenceDigest names the digest scheme the published stop sequence
// version carries, as the service states it.
func (s *Service) stopSequenceDigest() string {
	if strings.TrimSpace(s.supply.StopSequenceDigest) == "" {
		return "sha256"
	}
	return strings.TrimSpace(s.supply.StopSequenceDigest)
}
