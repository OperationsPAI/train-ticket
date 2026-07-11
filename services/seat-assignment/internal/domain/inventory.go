package domain

import "time"

type SeatInventory struct {
	SegmentRef     string                     `json:"segmentRef"`
	DepartureDate  string                     `json:"departureDate"`
	TrainConfigRef string                     `json:"trainConfigRef"`
	Assignments    map[string]*SeatAssignment `json:"assignments"`
	Version        int64                      `json:"version"`
}

func NewSeatInventory(segmentRef, departureDate, trainConfigRef string) *SeatInventory {
	return &SeatInventory{SegmentRef: segmentRef, DepartureDate: departureDate, TrainConfigRef: trainConfigRef, Assignments: map[string]*SeatAssignment{}, Version: 1}
}

func (i *SeatInventory) HoldSeat(seatId, travelerRef, holdId string, now time.Time) (*SeatAssignment, error) {
	i.ExpireHolds(now)
	if existing := i.Assignments[seatId]; existing != nil && existing.IsActive(now) {
		return nil, ErrSeatUnavailable
	}
	a, err := NewSeatAssignment("", i.SegmentRef, i.DepartureDate, travelerRef, seatId, holdId, now)
	if err != nil {
		return nil, err
	}
	i.Assignments[seatId] = a
	i.Version++
	return a, nil
}

func (i *SeatInventory) ConfirmSeat(seatId, holdId string, now time.Time) (*SeatAssignment, error) {
	i.ExpireHolds(now)
	a := i.Assignments[seatId]
	if a == nil || a.Status == StatusReleased {
		return nil, ErrSeatUnavailable
	}
	if err := a.Confirm(holdId, now); err != nil {
		return nil, err
	}
	i.Version++
	return a, nil
}

func (i *SeatInventory) ReleaseSeat(seatId string, now time.Time) (*SeatAssignment, error) {
	a := i.Assignments[seatId]
	if a == nil {
		return nil, nil
	}
	if err := a.Release(now); err != nil {
		return nil, err
	}
	i.Version++
	return a, nil
}

func (i *SeatInventory) ExpireHolds(olderThan time.Time) []SeatAssignment {
	expired := []SeatAssignment{}
	for _, a := range i.Assignments {
		if a.Status == StatusHeld && !a.ExpiresAt.After(olderThan.UTC()) {
			_ = a.Release(olderThan)
			expired = append(expired, *a)
			i.Version++
		}
	}
	return expired
}

func (i *SeatInventory) OccupiedSeatIDs(now time.Time) []string {
	i.ExpireHolds(now)
	ids := []string{}
	for seatId, a := range i.Assignments {
		if a.IsActive(now) {
			ids = append(ids, seatId)
		}
	}
	return ids
}
