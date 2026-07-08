package domain

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
)

type RideRequestID string
type RideAssignmentID string

type RideStatus string

const (
	StatusRequested       RideStatus = "REQUESTED"
	StatusMatching        RideStatus = "MATCHING"
	StatusAssigned        RideStatus = "ASSIGNED"
	StatusDriverArriving  RideStatus = "DRIVER_ARRIVING"
	StatusDriverArrived   RideStatus = "DRIVER_ARRIVED"
	StatusPickedUp        RideStatus = "PICKED_UP"
	StatusDriverCancelled RideStatus = "DRIVER_CANCELLED"
	StatusUserCancelled   RideStatus = "USER_CANCELLED"
	StatusNoShow          RideStatus = "NO_SHOW"
	StatusCompleted       RideStatus = "COMPLETED"
)

type TimeWindow struct{ StartAt, EndAt time.Time }

type RideAssignment struct {
	ID         RideAssignmentID
	DriverRef  string
	VehicleRef string
	ETASeconds int
	AssignedAt time.Time
	ArrivedAt  *time.Time
	StartedAt  *time.Time
	EndedAt    *time.Time
}

type DriverCancellation struct {
	AssignmentID RideAssignmentID
	DriverRef    string
	VehicleRef   string
	Reason       string
	CancelledAt  time.Time
}

type RideRequest struct {
	ID                RideRequestID
	RiderAccountID    string
	TravelerRef       string
	PickupRef         string
	DropoffRef        string
	TimeWindow        TimeWindow
	EstimatedFareRef  string
	FinalFareRef      string
	IntentFingerprint string
	Status            RideStatus
	Assignment        *RideAssignment
	Cancellations     []DriverCancellation
	CreatedAt         time.Time
	UpdatedAt         time.Time
	Version           int64
}

func NewRideRequest(id RideRequestID, riderAccountID, travelerRef, pickupRef, dropoffRef string, window TimeWindow, estimatedFareRef, intentFingerprint string, now time.Time) (RideRequest, error) {
	r := RideRequest{ID: id, RiderAccountID: strings.TrimSpace(riderAccountID), TravelerRef: strings.TrimSpace(travelerRef), PickupRef: strings.TrimSpace(pickupRef), DropoffRef: strings.TrimSpace(dropoffRef), TimeWindow: TimeWindow{StartAt: window.StartAt.UTC(), EndAt: window.EndAt.UTC()}, EstimatedFareRef: strings.TrimSpace(estimatedFareRef), IntentFingerprint: strings.TrimSpace(intentFingerprint), Status: StatusRequested, CreatedAt: now.UTC(), UpdatedAt: now.UTC()}
	if err := r.Validate(); err != nil {
		return RideRequest{}, err
	}
	return r, nil
}

func (r *RideRequest) EnterMatching(now time.Time) error {
	if r.Status != StatusRequested && r.Status != StatusDriverCancelled {
		return fmt.Errorf("cannot enter matching from %s", r.Status)
	}
	r.Assignment = nil
	r.Status = StatusMatching
	r.touch(now)
	return nil
}

func (r *RideRequest) AssignDriver(driverRef, vehicleRef string, etaSeconds int, now time.Time) error {
	if r.Status != StatusRequested && r.Status != StatusMatching {
		return fmt.Errorf("cannot assign driver from %s", r.Status)
	}
	a, err := NewRideAssignment(NewRideAssignmentID(), driverRef, vehicleRef, etaSeconds, now)
	if err != nil {
		return err
	}
	r.Assignment = &a
	r.Status = StatusAssigned
	r.touch(now)
	return nil
}

func NewRideAssignment(id RideAssignmentID, driverRef, vehicleRef string, etaSeconds int, now time.Time) (RideAssignment, error) {
	a := RideAssignment{ID: id, DriverRef: strings.TrimSpace(driverRef), VehicleRef: strings.TrimSpace(vehicleRef), ETASeconds: etaSeconds, AssignedAt: now.UTC()}
	if strings.TrimSpace(string(a.ID)) == "" {
		return RideAssignment{}, fmt.Errorf("rideAssignmentId is required")
	}
	if a.DriverRef == "" {
		return RideAssignment{}, fmt.Errorf("driverRef is required")
	}
	if a.VehicleRef == "" {
		return RideAssignment{}, fmt.Errorf("vehicleRef is required")
	}
	if a.ETASeconds < 0 {
		return RideAssignment{}, fmt.Errorf("etaSeconds must be non-negative")
	}
	return a, nil
}

func (r *RideRequest) UpdateETA(etaSeconds int, now time.Time) error {
	if r.Assignment == nil || (r.Status != StatusAssigned && r.Status != StatusDriverArriving) {
		return fmt.Errorf("eta can only be updated for an active assigned driver")
	}
	if etaSeconds < 0 {
		return fmt.Errorf("etaSeconds must be non-negative")
	}
	r.Assignment.ETASeconds = etaSeconds
	r.touch(now)
	return nil
}
func (r *RideRequest) MarkDriverArrived(now time.Time) error {
	if r.Assignment == nil || (r.Status != StatusAssigned && r.Status != StatusDriverArriving) {
		return fmt.Errorf("driver arrival requires an active assignment")
	}
	t := now.UTC()
	r.Assignment.ArrivedAt = &t
	r.Status = StatusDriverArrived
	r.touch(now)
	return nil
}
func (r *RideRequest) StartRide(now time.Time) error {
	if r.Assignment == nil || r.Status != StatusDriverArrived {
		return fmt.Errorf("ride can only start after driver arrival")
	}
	t := now.UTC()
	r.Assignment.StartedAt = &t
	r.Status = StatusPickedUp
	r.touch(now)
	return nil
}
func (r *RideRequest) Complete(finalFareRef string, now time.Time) error {
	if r.Assignment == nil || r.Status != StatusPickedUp {
		return fmt.Errorf("dispatch can only complete after pickup")
	}
	t := now.UTC()
	r.Assignment.EndedAt = &t
	r.FinalFareRef = strings.TrimSpace(finalFareRef)
	r.Status = StatusCompleted
	r.touch(now)
	return nil
}
func (r *RideRequest) CancelByDriver(reason string, now time.Time) (DriverCancellation, error) {
	if r.Assignment == nil || (r.Status != StatusAssigned && r.Status != StatusDriverArriving) {
		return DriverCancellation{}, fmt.Errorf("driver cancellation requires an active pre-arrival assignment")
	}
	reason = strings.TrimSpace(reason)
	if reason == "" {
		return DriverCancellation{}, fmt.Errorf("reason is required")
	}
	c := DriverCancellation{AssignmentID: r.Assignment.ID, DriverRef: r.Assignment.DriverRef, VehicleRef: r.Assignment.VehicleRef, Reason: reason, CancelledAt: now.UTC()}
	r.Cancellations = append(r.Cancellations, c)
	r.Status = StatusDriverCancelled
	r.touch(now)
	r.Assignment = nil
	r.Status = StatusMatching
	r.UpdatedAt = now.UTC()
	return c, nil
}
func (r *RideRequest) CancelByUser(reason string, now time.Time) (RideAssignmentID, error) {
	if !r.Active() {
		return "", fmt.Errorf("only an active dispatch can be user-cancelled")
	}
	if strings.TrimSpace(reason) == "" {
		return "", fmt.Errorf("reason is required")
	}
	var assignmentID RideAssignmentID
	if r.Assignment != nil {
		assignmentID = r.Assignment.ID
	}
	r.Status = StatusUserCancelled
	r.touch(now)
	return assignmentID, nil
}
func (r *RideRequest) RecordNoShow(reason string, now time.Time) error {
	if r.Assignment == nil || r.Status != StatusDriverArrived {
		return fmt.Errorf("no-show can only be recorded after driver arrival")
	}
	r.Status = StatusNoShow
	r.touch(now)
	return nil
}
func (r RideRequest) Active() bool {
	switch r.Status {
	case StatusRequested, StatusMatching, StatusAssigned, StatusDriverArriving, StatusDriverArrived, StatusPickedUp, StatusDriverCancelled:
		return true
	default:
		return false
	}
}
func (r RideRequest) Validate() error {
	if strings.TrimSpace(string(r.ID)) == "" {
		return fmt.Errorf("rideRequestId is required")
	}
	for name, value := range map[string]string{"riderAccountId": r.RiderAccountID, "travelerRef": r.TravelerRef, "pickupRef": r.PickupRef, "dropoffRef": r.DropoffRef, "intentFingerprint": r.IntentFingerprint} {
		if strings.TrimSpace(value) == "" {
			return fmt.Errorf("%s is required", name)
		}
	}
	if strings.TrimSpace(r.PickupRef) == strings.TrimSpace(r.DropoffRef) {
		return fmt.Errorf("pickupRef and dropoffRef must differ")
	}
	if r.TimeWindow.StartAt.IsZero() || r.TimeWindow.EndAt.IsZero() || !r.TimeWindow.EndAt.After(r.TimeWindow.StartAt) {
		return fmt.Errorf("timeWindow.endAt must be after startAt")
	}
	if !validStatus(r.Status) {
		return fmt.Errorf("unsupported status: %s", r.Status)
	}
	return nil
}
func (r *RideRequest) touch(now time.Time) { r.UpdatedAt = now.UTC() }
func validStatus(s RideStatus) bool {
	switch s {
	case StatusRequested, StatusMatching, StatusAssigned, StatusDriverArriving, StatusDriverArrived, StatusPickedUp, StatusDriverCancelled, StatusUserCancelled, StatusNoShow, StatusCompleted:
		return true
	default:
		return false
	}
}
func NewRideRequestID() RideRequestID        { return RideRequestID(ids.NewPrefixed("rrq")) }
func NewRideAssignmentID() RideAssignmentID  { return RideAssignmentID(ids.NewPrefixed("ras")) }
func FormatTimestamp(value time.Time) string { return value.UTC().Format(time.RFC3339Nano) }
func DeterministicEventID(eventType string, rideRequestID RideRequestID, aggregateVersion int64) string {
	sum := sha256.Sum256([]byte(fmt.Sprintf("dispatch:%s:%s:%d", strings.TrimSpace(eventType), rideRequestID, aggregateVersion)))
	b := append([]byte(nil), sum[:16]...)
	b[6] = (b[6] & 0x0f) | 0x70
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("evt-%s-%s-%s-%s-%s", hex.EncodeToString(b[0:4]), hex.EncodeToString(b[4:6]), hex.EncodeToString(b[6:8]), hex.EncodeToString(b[8:10]), hex.EncodeToString(b[10:16]))
}

type Clock interface{ Now() time.Time }
type RealClock struct{}

func (RealClock) Now() time.Time { return time.Now().UTC() }
