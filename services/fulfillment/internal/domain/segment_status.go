package domain

import (
	"fmt"
	"regexp"
	"strings"
	"time"
)

type SegmentStatusRecordID string

type SegmentOperationalStatus string

const (
	SegmentOperationalStatusDelay     SegmentOperationalStatus = "DELAY"
	SegmentOperationalStatusArrival   SegmentOperationalStatus = "ARRIVAL"
	SegmentOperationalStatusCancelled SegmentOperationalStatus = "CANCELLED"
)

type SegmentStatusSourceSystem string

const (
	SegmentStatusSourceSystemSystem SegmentStatusSourceSystem = "SYSTEM"
	SegmentStatusSourceSystemOps    SegmentStatusSourceSystem = "OPS"
)

type SegmentStatusRecord struct {
	SegmentStatusRecordID SegmentStatusRecordID
	CommandID             string
	SegmentRef            SegmentRef
	ScheduledServiceRef   string
	ServiceDate           string
	Status                SegmentOperationalStatus
	EstimatedArrivalAt    *time.Time
	ArrivedAt             *time.Time
	CancelledAt           *time.Time
	ObservedAt            time.Time
	SourceSystem          SegmentStatusSourceSystem
	CreatedAt             time.Time
	event                 DomainEvent
}

func (id SegmentStatusRecordID) Validate() error {
	if matched, _ := regexp.MatchString(`^ssr-[a-zA-Z0-9\-]+$`, string(id)); !matched {
		return fmt.Errorf("invalid SegmentStatusRecordID: %q", string(id))
	}
	return nil
}

func NewSegmentStatusRecord(id SegmentStatusRecordID, commandID string, segmentRef SegmentRef, scheduledServiceRef string, serviceDate string, status SegmentOperationalStatus, estimatedArrivalAt, arrivedAt, cancelledAt *time.Time, observedAt time.Time, sourceSystem SegmentStatusSourceSystem) (*SegmentStatusRecord, error) {
	if err := id.Validate(); err != nil {
		return nil, err
	}
	commandID = strings.TrimSpace(commandID)
	if commandID == "" {
		return nil, fmt.Errorf("commandId is required")
	}
	if err := segmentRef.Validate(); err != nil {
		return nil, err
	}
	scheduledServiceRef = strings.TrimSpace(scheduledServiceRef)
	if scheduledServiceRef == "" {
		return nil, fmt.Errorf("scheduledServiceRef is required")
	}
	if !validServiceDate(serviceDate) {
		return nil, fmt.Errorf("serviceDate must be YYYY-MM-DD")
	}
	if observedAt.IsZero() {
		return nil, fmt.Errorf("observedAt is required")
	}
	switch sourceSystem {
	case SegmentStatusSourceSystemSystem, SegmentStatusSourceSystemOps:
	default:
		return nil, fmt.Errorf("sourceSystem is invalid")
	}
	if estimatedArrivalAt != nil {
		t := estimatedArrivalAt.UTC()
		estimatedArrivalAt = &t
	}
	if arrivedAt != nil {
		t := arrivedAt.UTC()
		arrivedAt = &t
	}
	if cancelledAt != nil {
		t := cancelledAt.UTC()
		cancelledAt = &t
	}
	record := &SegmentStatusRecord{SegmentStatusRecordID: id, CommandID: commandID, SegmentRef: segmentRef, ScheduledServiceRef: scheduledServiceRef, ServiceDate: serviceDate, Status: status, EstimatedArrivalAt: estimatedArrivalAt, ArrivedAt: arrivedAt, CancelledAt: cancelledAt, ObservedAt: observedAt.UTC(), SourceSystem: sourceSystem, CreatedAt: timeNow().UTC()}
	switch status {
	case SegmentOperationalStatusDelay:
		if record.EstimatedArrivalAt == nil {
			return nil, fmt.Errorf("estimatedArrivalAt is required for DELAY")
		}
		record.event = SegmentDelayedEvent{SegmentStatusRecordID: id, SegmentRef: segmentRef, ScheduledServiceRef: scheduledServiceRef, ServiceDate: serviceDate, EstimatedArrivalAt: *record.EstimatedArrivalAt, ObservedAt: record.ObservedAt, SourceSystem: sourceSystem, CommandID: commandID}
	case SegmentOperationalStatusArrival:
		if record.ArrivedAt == nil {
			return nil, fmt.Errorf("arrivedAt is required for ARRIVAL")
		}
		record.event = SegmentArrivedEvent{SegmentStatusRecordID: id, SegmentRef: segmentRef, ScheduledServiceRef: scheduledServiceRef, ServiceDate: serviceDate, ArrivedAt: *record.ArrivedAt, ObservedAt: record.ObservedAt, SourceSystem: sourceSystem, CommandID: commandID}
	case SegmentOperationalStatusCancelled:
		if record.CancelledAt == nil {
			return nil, fmt.Errorf("cancelledAt is required for CANCELLED")
		}
		record.event = SegmentCancelledEvent{SegmentStatusRecordID: id, SegmentRef: segmentRef, ScheduledServiceRef: scheduledServiceRef, ServiceDate: serviceDate, CancelledAt: *record.CancelledAt, ObservedAt: record.ObservedAt, SourceSystem: sourceSystem, CommandID: commandID}
	default:
		return nil, fmt.Errorf("status is invalid")
	}
	return record, nil
}

func (r *SegmentStatusRecord) PendingEvent() DomainEvent { return r.event }
func (r *SegmentStatusRecord) ClearEvent()               { r.event = nil }

func validServiceDate(value string) bool {
	if len(value) != len("2006-01-02") {
		return false
	}
	_, err := time.Parse("2006-01-02", value)
	return err == nil
}

type SegmentDelayedEvent struct {
	SegmentStatusRecordID SegmentStatusRecordID
	SegmentRef            SegmentRef
	ScheduledServiceRef   string
	ServiceDate           string
	EstimatedArrivalAt    time.Time
	ObservedAt            time.Time
	SourceSystem          SegmentStatusSourceSystem
	CommandID             string
}

func (e SegmentDelayedEvent) EventType() string     { return "SegmentDelayed" }
func (e SegmentDelayedEvent) OccurredAt() time.Time { return e.ObservedAt }

type SegmentArrivedEvent struct {
	SegmentStatusRecordID SegmentStatusRecordID
	SegmentRef            SegmentRef
	ScheduledServiceRef   string
	ServiceDate           string
	ArrivedAt             time.Time
	ObservedAt            time.Time
	SourceSystem          SegmentStatusSourceSystem
	CommandID             string
}

func (e SegmentArrivedEvent) EventType() string     { return "SegmentArrived" }
func (e SegmentArrivedEvent) OccurredAt() time.Time { return e.ObservedAt }

type SegmentCancelledEvent struct {
	SegmentStatusRecordID SegmentStatusRecordID
	SegmentRef            SegmentRef
	ScheduledServiceRef   string
	ServiceDate           string
	CancelledAt           time.Time
	ObservedAt            time.Time
	SourceSystem          SegmentStatusSourceSystem
	CommandID             string
}

func (e SegmentCancelledEvent) EventType() string     { return "SegmentCancelled" }
func (e SegmentCancelledEvent) OccurredAt() time.Time { return e.ObservedAt }
