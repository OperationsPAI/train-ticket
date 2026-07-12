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

type ServiceProfile struct {
	ServiceID    string   `json:"serviceId"`
	Domain       string   `json:"domain"`
	Language     string   `json:"language"`
	Phase        string   `json:"phase"`
	WorkPackages []string `json:"workPackages"`
	Owns         []string `json:"owns"`
}

func Profile() ServiceProfile {
	return ServiceProfile{ServiceID: "seat-assignment", Domain: "Seat Assignment", Language: "golang", Phase: "phase-1-activation", WorkPackages: []string{"REQ-304"}, Owns: []string{"TrainConfig", "SeatInventory", "SeatAssignment"}}
}
func Health() string { return "ok" }
