package domain

import (
	"fmt"
	"time"
)

type SeatAssignment struct {
	AssignmentId  string     `json:"assignmentId"`
	SegmentRef    string     `json:"segmentRef"`
	DepartureDate string     `json:"departureDate"`
	TravelerRef   string     `json:"travelerRef"`
	SeatId        string     `json:"seatId"`
	HoldId        string     `json:"holdId"`
	Status        string     `json:"status"`
	AssignedAt    time.Time  `json:"assignedAt"`
	ExpiresAt     time.Time  `json:"expiresAt"`
	ConfirmedAt   *time.Time `json:"confirmedAt,omitempty"`
	ReleasedAt    *time.Time `json:"releasedAt,omitempty"`
	Version       int64      `json:"version"`
}

func NewSeatAssignment(id, segmentRef, departureDate, travelerRef, seatId, holdId string, now time.Time) (*SeatAssignment, error) {
	if Trim(id) == "" {
		id = NewID("sa")
	}
	if Trim(segmentRef) == "" || Trim(departureDate) == "" || Trim(travelerRef) == "" || Trim(seatId) == "" || Trim(holdId) == "" {
		return nil, fmt.Errorf("required assignment fields are missing")
	}
	return &SeatAssignment{AssignmentId: id, SegmentRef: segmentRef, DepartureDate: departureDate, TravelerRef: travelerRef, SeatId: seatId, HoldId: holdId, Status: StatusHeld, AssignedAt: now.UTC(), ExpiresAt: now.UTC().Add(HoldDuration), Version: 1}, nil
}

func (a *SeatAssignment) IsActive(now time.Time) bool {
	return a.Status == StatusConfirmed || (a.Status == StatusHeld && a.ExpiresAt.After(now.UTC()))
}

func (a *SeatAssignment) Confirm(holdId string, now time.Time) error {
	if a.Status == StatusConfirmed {
		return nil
	}
	if a.Status != StatusHeld || a.HoldId != holdId {
		return ErrInvalidTransition
	}
	t := now.UTC()
	a.Status = StatusConfirmed
	a.ConfirmedAt = &t
	a.Version++
	return nil
}

func (a *SeatAssignment) Release(now time.Time) error {
	if a.Status == StatusReleased {
		return nil
	}
	if a.Status != StatusHeld && a.Status != StatusConfirmed {
		return ErrInvalidTransition
	}
	t := now.UTC()
	a.Status = StatusReleased
	a.ReleasedAt = &t
	a.Version++
	return nil
}
