package domain

import "time"

const ProducerDispatch = "dispatch"

type EventEnvelope struct {
	EventID       string `json:"eventId"`
	EventType     string `json:"eventType"`
	SchemaVersion int    `json:"schemaVersion"`
	Producer      string `json:"producer"`
	CausationID   string `json:"causationId,omitempty"`
	CorrelationID string `json:"correlationId"`
	OccurredAt    string `json:"occurredAt"`
	Traceparent   string `json:"traceparent,omitempty"`
	Tracestate    string `json:"tracestate,omitempty"`
	Payload       any    `json:"payload"`
}

func NewEventEnvelope(eventType string, occurredAt time.Time, correlationID, causationID string, rideRequestID RideRequestID, aggregateVersion int64, payload any) EventEnvelope {
	return EventEnvelope{EventID: DeterministicEventID(eventType, rideRequestID, aggregateVersion), EventType: eventType, SchemaVersion: 1, Producer: ProducerDispatch, CausationID: causationID, CorrelationID: correlationID, OccurredAt: FormatTimestamp(occurredAt), Payload: payload}
}

type TimeWindowPayload struct {
	StartAt string `json:"startAt"`
	EndAt   string `json:"endAt"`
}

type DispatchRequestedEvent struct {
	RideRequestID     RideRequestID     `json:"rideRequestId"`
	RiderAccountID    string            `json:"riderAccountId"`
	TravelerRef       string            `json:"travelerRef"`
	PickupRef         string            `json:"pickupRef"`
	DropoffRef        string            `json:"dropoffRef"`
	TimeWindow        TimeWindowPayload `json:"timeWindow"`
	EstimatedFareRef  string            `json:"estimatedFareRef,omitempty"`
	IntentFingerprint string            `json:"intentFingerprint"`
	Status            RideStatus        `json:"status"`
	RequestedAt       string            `json:"requestedAt"`
}

type DriverAssignedEvent struct {
	RideRequestID    RideRequestID    `json:"rideRequestId"`
	RideAssignmentID RideAssignmentID `json:"rideAssignmentId"`
	RiderAccountID   string           `json:"riderAccountId"`
	TravelerRef      string           `json:"travelerRef"`
	PickupRef        string           `json:"pickupRef"`
	DropoffRef       string           `json:"dropoffRef"`
	DriverRef        string           `json:"driverRef"`
	VehicleRef       string           `json:"vehicleRef"`
	ETASeconds       int              `json:"etaSeconds"`
	AssignedAt       string           `json:"assignedAt"`
	Status           RideStatus       `json:"status"`
}

type DriverEtaUpdatedEvent struct {
	RideRequestID    RideRequestID    `json:"rideRequestId"`
	RideAssignmentID RideAssignmentID `json:"rideAssignmentId"`
	RiderAccountID   string           `json:"riderAccountId"`
	TravelerRef      string           `json:"travelerRef"`
	DriverRef        string           `json:"driverRef"`
	VehicleRef       string           `json:"vehicleRef"`
	ETASeconds       int              `json:"etaSeconds"`
	UpdatedAt        string           `json:"updatedAt"`
	Status           RideStatus       `json:"status"`
}

type DriverArrivedEvent struct {
	RideRequestID    RideRequestID    `json:"rideRequestId"`
	RideAssignmentID RideAssignmentID `json:"rideAssignmentId"`
	RiderAccountID   string           `json:"riderAccountId"`
	TravelerRef      string           `json:"travelerRef"`
	PickupRef        string           `json:"pickupRef"`
	DropoffRef       string           `json:"dropoffRef"`
	DriverRef        string           `json:"driverRef"`
	VehicleRef       string           `json:"vehicleRef"`
	ArrivedAt        string           `json:"arrivedAt"`
	Status           RideStatus       `json:"status"`
}
type RideStartedEvent struct {
	RideRequestID    RideRequestID    `json:"rideRequestId"`
	RideAssignmentID RideAssignmentID `json:"rideAssignmentId"`
	RiderAccountID   string           `json:"riderAccountId"`
	TravelerRef      string           `json:"travelerRef"`
	PickupRef        string           `json:"pickupRef"`
	DropoffRef       string           `json:"dropoffRef"`
	DriverRef        string           `json:"driverRef"`
	VehicleRef       string           `json:"vehicleRef"`
	StartedAt        string           `json:"startedAt"`
	Status           RideStatus       `json:"status"`
}
type RideEndedEvent struct {
	RideRequestID    RideRequestID    `json:"rideRequestId"`
	RideAssignmentID RideAssignmentID `json:"rideAssignmentId"`
	RiderAccountID   string           `json:"riderAccountId"`
	TravelerRef      string           `json:"travelerRef"`
	PickupRef        string           `json:"pickupRef"`
	DropoffRef       string           `json:"dropoffRef"`
	DriverRef        string           `json:"driverRef"`
	VehicleRef       string           `json:"vehicleRef"`
	StartedAt        string           `json:"startedAt"`
	EndedAt          string           `json:"endedAt"`
	FinalFareRef     string           `json:"finalFareRef,omitempty"`
	Status           RideStatus       `json:"status"`
}
type DriverCancelledEvent struct {
	RideRequestID    RideRequestID    `json:"rideRequestId"`
	RideAssignmentID RideAssignmentID `json:"rideAssignmentId"`
	RiderAccountID   string           `json:"riderAccountId"`
	TravelerRef      string           `json:"travelerRef"`
	DriverRef        string           `json:"driverRef"`
	VehicleRef       string           `json:"vehicleRef"`
	CancelledAt      string           `json:"cancelledAt"`
	Reason           string           `json:"reason"`
	Status           RideStatus       `json:"status"`
	NextStatus       RideStatus       `json:"nextStatus"`
}
type DispatchUserCancelledEvent struct {
	RideRequestID    RideRequestID    `json:"rideRequestId"`
	RideAssignmentID RideAssignmentID `json:"rideAssignmentId,omitempty"`
	RiderAccountID   string           `json:"riderAccountId"`
	TravelerRef      string           `json:"travelerRef"`
	PickupRef        string           `json:"pickupRef"`
	DropoffRef       string           `json:"dropoffRef"`
	CancelledAt      string           `json:"cancelledAt"`
	Reason           string           `json:"reason"`
	Status           RideStatus       `json:"status"`
}
type DispatchNoShowRecordedEvent struct {
	RideRequestID    RideRequestID    `json:"rideRequestId"`
	RideAssignmentID RideAssignmentID `json:"rideAssignmentId"`
	RiderAccountID   string           `json:"riderAccountId"`
	TravelerRef      string           `json:"travelerRef"`
	PickupRef        string           `json:"pickupRef"`
	DropoffRef       string           `json:"dropoffRef"`
	DriverRef        string           `json:"driverRef"`
	VehicleRef       string           `json:"vehicleRef"`
	RecordedAt       string           `json:"recordedAt"`
	Reason           string           `json:"reason,omitempty"`
	Status           RideStatus       `json:"status"`
}
