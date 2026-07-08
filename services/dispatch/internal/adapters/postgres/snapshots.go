package postgres

import (
	"encoding/json"
	"time"

	"github.com/trainticket/greenfield/services/dispatch/internal/domain"
)

type timeWindowSnapshot struct {
	StartAt time.Time `json:"startAt"`
	EndAt   time.Time `json:"endAt"`
}
type assignmentSnapshot struct {
	ID         string     `json:"rideAssignmentId"`
	DriverRef  string     `json:"driverRef"`
	VehicleRef string     `json:"vehicleRef"`
	ETASeconds int        `json:"etaSeconds"`
	AssignedAt time.Time  `json:"assignedAt"`
	ArrivedAt  *time.Time `json:"arrivedAt,omitempty"`
	StartedAt  *time.Time `json:"startedAt,omitempty"`
	EndedAt    *time.Time `json:"endedAt,omitempty"`
}
type cancellationSnapshot struct {
	AssignmentID string    `json:"rideAssignmentId"`
	DriverRef    string    `json:"driverRef"`
	VehicleRef   string    `json:"vehicleRef"`
	Reason       string    `json:"reason"`
	CancelledAt  time.Time `json:"cancelledAt"`
}
type rideSnapshot struct {
	ID                string                 `json:"rideRequestId"`
	RiderAccountID    string                 `json:"riderAccountId"`
	TravelerRef       string                 `json:"travelerRef"`
	PickupRef         string                 `json:"pickupRef"`
	DropoffRef        string                 `json:"dropoffRef"`
	TimeWindow        timeWindowSnapshot     `json:"timeWindow"`
	EstimatedFareRef  string                 `json:"estimatedFareRef,omitempty"`
	FinalFareRef      string                 `json:"finalFareRef,omitempty"`
	IntentFingerprint string                 `json:"intentFingerprint"`
	Status            domain.RideStatus      `json:"status"`
	Assignment        *assignmentSnapshot    `json:"assignment,omitempty"`
	Cancellations     []cancellationSnapshot `json:"cancellations,omitempty"`
	CreatedAt         time.Time              `json:"createdAt"`
	UpdatedAt         time.Time              `json:"updatedAt"`
}

func snapshotFromDomain(r domain.RideRequest) rideSnapshot {
	var a *assignmentSnapshot
	if r.Assignment != nil {
		x := r.Assignment
		a = &assignmentSnapshot{ID: string(x.ID), DriverRef: x.DriverRef, VehicleRef: x.VehicleRef, ETASeconds: x.ETASeconds, AssignedAt: x.AssignedAt, ArrivedAt: x.ArrivedAt, StartedAt: x.StartedAt, EndedAt: x.EndedAt}
	}
	cs := make([]cancellationSnapshot, 0, len(r.Cancellations))
	for _, c := range r.Cancellations {
		cs = append(cs, cancellationSnapshot{AssignmentID: string(c.AssignmentID), DriverRef: c.DriverRef, VehicleRef: c.VehicleRef, Reason: c.Reason, CancelledAt: c.CancelledAt})
	}
	return rideSnapshot{ID: string(r.ID), RiderAccountID: r.RiderAccountID, TravelerRef: r.TravelerRef, PickupRef: r.PickupRef, DropoffRef: r.DropoffRef, TimeWindow: timeWindowSnapshot{StartAt: r.TimeWindow.StartAt, EndAt: r.TimeWindow.EndAt}, EstimatedFareRef: r.EstimatedFareRef, FinalFareRef: r.FinalFareRef, IntentFingerprint: r.IntentFingerprint, Status: r.Status, Assignment: a, Cancellations: cs, CreatedAt: r.CreatedAt, UpdatedAt: r.UpdatedAt}
}
func decodeRide(raw []byte, version int64) (domain.RideRequest, error) {
	var s rideSnapshot
	if err := json.Unmarshal(raw, &s); err != nil {
		return domain.RideRequest{}, err
	}
	var a *domain.RideAssignment
	if s.Assignment != nil {
		x := s.Assignment
		a = &domain.RideAssignment{ID: domain.RideAssignmentID(x.ID), DriverRef: x.DriverRef, VehicleRef: x.VehicleRef, ETASeconds: x.ETASeconds, AssignedAt: x.AssignedAt, ArrivedAt: x.ArrivedAt, StartedAt: x.StartedAt, EndedAt: x.EndedAt}
	}
	cs := make([]domain.DriverCancellation, 0, len(s.Cancellations))
	for _, c := range s.Cancellations {
		cs = append(cs, domain.DriverCancellation{AssignmentID: domain.RideAssignmentID(c.AssignmentID), DriverRef: c.DriverRef, VehicleRef: c.VehicleRef, Reason: c.Reason, CancelledAt: c.CancelledAt})
	}
	return domain.RideRequest{ID: domain.RideRequestID(s.ID), RiderAccountID: s.RiderAccountID, TravelerRef: s.TravelerRef, PickupRef: s.PickupRef, DropoffRef: s.DropoffRef, TimeWindow: domain.TimeWindow{StartAt: s.TimeWindow.StartAt, EndAt: s.TimeWindow.EndAt}, EstimatedFareRef: s.EstimatedFareRef, FinalFareRef: s.FinalFareRef, IntentFingerprint: s.IntentFingerprint, Status: s.Status, Assignment: a, Cancellations: cs, CreatedAt: s.CreatedAt, UpdatedAt: s.UpdatedAt, Version: version}, nil
}
