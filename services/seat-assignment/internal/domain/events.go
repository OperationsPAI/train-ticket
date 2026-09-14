package domain

import "time"

type Event struct {
	EventType     string
	AggregateID   string
	Version       int64
	Payload       any
	OccurredAt    time.Time
	CorrelationID string
	CausationID   string
}

type SeatAssignedPayload struct {
	AssignmentId  string    `json:"assignmentId"`
	SegmentRef    string    `json:"segmentRef"`
	DepartureDate string    `json:"departureDate"`
	TravelerRef   string    `json:"travelerRef"`
	SeatId        string    `json:"seatId"`
	HoldId        string    `json:"holdId"`
	Status        string    `json:"status"`
	AssignedAt    time.Time `json:"assignedAt"`
	ExpiresAt     time.Time `json:"expiresAt"`
}
type SeatConfirmedPayload struct {
	AssignmentId  string    `json:"assignmentId"`
	SegmentRef    string    `json:"segmentRef"`
	DepartureDate string    `json:"departureDate"`
	TravelerRef   string    `json:"travelerRef"`
	SeatId        string    `json:"seatId"`
	HoldId        string    `json:"holdId"`
	Status        string    `json:"status"`
	ConfirmedAt   time.Time `json:"confirmedAt"`
}
type SeatReleasedPayload struct {
	AssignmentId  string    `json:"assignmentId"`
	SegmentRef    string    `json:"segmentRef"`
	DepartureDate string    `json:"departureDate"`
	TravelerRef   string    `json:"travelerRef"`
	SeatId        string    `json:"seatId"`
	HoldId        string    `json:"holdId"`
	Status        string    `json:"status"`
	ReleasedAt    time.Time `json:"releasedAt"`
}
