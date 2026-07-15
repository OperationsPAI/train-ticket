package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/fulfillment/internal/application"
	"github.com/trainticket/greenfield/services/fulfillment/internal/domain"
)

const fulfillmentRecordTable = "fulfillment_record_snapshots"
const segmentStatusTable = "segment_status_records"

type FulfillmentRepository struct{ db ContextDBProvider }

func NewFulfillmentRepository(db storage.DBTX) *FulfillmentRepository {
	return &FulfillmentRepository{db: staticDB{db: db}}
}
func NewFulfillmentRepositoryWithProvider(db ContextDBProvider) *FulfillmentRepository {
	return &FulfillmentRepository{db: db}
}

func (r *FulfillmentRepository) Save(ctx context.Context, record *domain.FulfillmentRecord) error {
	data, err := json.Marshal(recordSnapshotFromDomain(record))
	if err != nil {
		return err
	}
	repo := storage.NewSnapshotRepository(r.db.DBFor(ctx), fulfillmentRecordTable)
	snap, ok, err := repo.Get(ctx, string(record.FulfillmentRecordID))
	if err != nil {
		return err
	}
	if !ok {
		if err := repo.Insert(ctx, string(record.FulfillmentRecordID), data); err != nil {
			if errors.Is(err, storage.ErrConflict) {
				return fmt.Errorf("%w: fulfillment record already exists", application.ErrConflict)
			}
			return err
		}
		return nil
	}
	_, err = repo.Save(ctx, string(record.FulfillmentRecordID), snap.Version, data)
	if errors.Is(err, storage.ErrConflict) {
		return fmt.Errorf("%w: fulfillment record changed", application.ErrConflict)
	}
	return err
}

func (r *FulfillmentRepository) FindByID(ctx context.Context, id domain.FulfillmentRecordID) (*domain.FulfillmentRecord, error) {
	snap, ok, err := storage.NewSnapshotRepository(r.db.DBFor(ctx), fulfillmentRecordTable).Get(ctx, string(id))
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, application.ErrNotFound
	}
	return decodeRecord(snap.Data)
}

func (r *FulfillmentRepository) FindByEntitlementSegment(ctx context.Context, entitlementID domain.EntitlementRef, segmentBookingID domain.SegmentBookingRef, segmentRef domain.SegmentRef) (*domain.FulfillmentRecord, error) {
	rows, err := r.db.DBFor(ctx).Query(ctx, `SELECT data FROM fulfillment_record_snapshots WHERE data->>'entitlementId' = $1 AND data->>'segmentBookingId' = $2 AND data->>'segmentRef' = $3 LIMIT 1`, string(entitlementID), string(segmentBookingID), string(segmentRef))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	if !rows.Next() {
		return nil, application.ErrNotFound
	}
	var raw json.RawMessage
	if err := rows.Scan(&raw); err != nil {
		return nil, err
	}
	return decodeRecord(raw)
}

func (r *FulfillmentRepository) SaveSegmentStatus(ctx context.Context, record *domain.SegmentStatusRecord) (bool, error) {
	data, err := json.Marshal(segmentStatusSnapshotFromDomain(record))
	if err != nil {
		return false, err
	}
	result, err := r.db.DBFor(ctx).Exec(ctx, `INSERT INTO segment_status_records(id, command_id, data) VALUES ($1, $2, $3) ON CONFLICT (command_id) DO NOTHING`, string(record.SegmentStatusRecordID), record.CommandID, data)
	if err != nil {
		return false, err
	}
	return result.RowsAffected() > 0, nil
}

func (r *FulfillmentRepository) FindSegmentStatusByCommandID(ctx context.Context, commandID string) (*domain.SegmentStatusRecord, error) {
	rows, err := r.db.DBFor(ctx).Query(ctx, `SELECT data FROM segment_status_records WHERE command_id = $1 LIMIT 1`, commandID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	if !rows.Next() {
		return nil, application.ErrNotFound
	}
	var raw json.RawMessage
	if err := rows.Scan(&raw); err != nil {
		return nil, err
	}
	return decodeSegmentStatus(raw)
}

func (r *FulfillmentRepository) SaveAncillaryHandoff(ctx context.Context, handoff *domain.AncillaryFulfillmentHandoff) error {
	data, err := json.Marshal(ancillaryHandoffSnapshotFromDomain(handoff))
	if err != nil {
		return err
	}
	_, err = r.db.DBFor(ctx).Exec(ctx, `INSERT INTO ancillary_fulfillment_handoffs(ancillary_order_item_id, data) VALUES ($1, $2) ON CONFLICT (ancillary_order_item_id) DO UPDATE SET data = EXCLUDED.data, updated_at = now()`, handoff.AncillaryOrderItemID, data)
	return err
}

func (r *FulfillmentRepository) FindAncillaryHandoff(ctx context.Context, ancillaryOrderItemID string) (*domain.AncillaryFulfillmentHandoff, error) {
	rows, err := r.db.DBFor(ctx).Query(ctx, `SELECT data FROM ancillary_fulfillment_handoffs WHERE ancillary_order_item_id = $1 LIMIT 1`, strings.TrimSpace(ancillaryOrderItemID))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	if !rows.Next() {
		return nil, application.ErrNotFound
	}
	var raw json.RawMessage
	if err := rows.Scan(&raw); err != nil {
		return nil, err
	}
	return decodeAncillaryHandoff(raw)
}

func (r *FulfillmentRepository) SaveRideExecution(ctx context.Context, view *domain.RideExecutionView) error {
	data, err := json.Marshal(rideExecutionSnapshotFromDomain(view))
	if err != nil {
		return err
	}
	_, err = r.db.DBFor(ctx).Exec(ctx, `INSERT INTO ride_execution_views(ride_request_id, data) VALUES ($1, $2) ON CONFLICT (ride_request_id) DO UPDATE SET data = EXCLUDED.data, updated_at = now()`, view.RideRequestID, data)
	return err
}

func (r *FulfillmentRepository) FindRideExecution(ctx context.Context, rideRequestID string) (*domain.RideExecutionView, error) {
	rows, err := r.db.DBFor(ctx).Query(ctx, `SELECT data FROM ride_execution_views WHERE ride_request_id = $1 LIMIT 1`, strings.TrimSpace(rideRequestID))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	if !rows.Next() {
		return nil, application.ErrNotFound
	}
	var raw json.RawMessage
	if err := rows.Scan(&raw); err != nil {
		return nil, err
	}
	return decodeRideExecution(raw)
}

type segmentStatusSnapshot struct {
	SegmentStatusRecordID string                           `json:"segmentStatusRecordId"`
	CommandID             string                           `json:"commandId"`
	SegmentRef            string                           `json:"segmentRef"`
	ScheduledServiceRef   string                           `json:"scheduledServiceRef"`
	ServiceDate           string                           `json:"serviceDate"`
	Status                string                           `json:"status"`
	EstimatedArrivalAt    *time.Time                       `json:"estimatedArrivalAt,omitempty"`
	ArrivedAt             *time.Time                       `json:"arrivedAt,omitempty"`
	CancelledAt           *time.Time                       `json:"cancelledAt,omitempty"`
	ObservedAt            time.Time                        `json:"observedAt"`
	SourceSystem          domain.SegmentStatusSourceSystem `json:"sourceSystem"`
	CreatedAt             time.Time                        `json:"createdAt"`
}

func segmentStatusSnapshotFromDomain(r *domain.SegmentStatusRecord) segmentStatusSnapshot {
	return segmentStatusSnapshot{SegmentStatusRecordID: string(r.SegmentStatusRecordID), CommandID: r.CommandID, SegmentRef: string(r.SegmentRef), ScheduledServiceRef: r.ScheduledServiceRef, ServiceDate: r.ServiceDate, Status: string(r.Status), EstimatedArrivalAt: utcPtr(r.EstimatedArrivalAt), ArrivedAt: utcPtr(r.ArrivedAt), CancelledAt: utcPtr(r.CancelledAt), ObservedAt: r.ObservedAt.UTC(), SourceSystem: r.SourceSystem, CreatedAt: r.CreatedAt.UTC()}
}

func utcPtr(value *time.Time) *time.Time {
	if value == nil {
		return nil
	}
	t := value.UTC()
	return &t
}

func decodeSegmentStatus(raw []byte) (*domain.SegmentStatusRecord, error) {
	var s segmentStatusSnapshot
	if err := json.Unmarshal(raw, &s); err != nil {
		return nil, err
	}
	return &domain.SegmentStatusRecord{SegmentStatusRecordID: domain.SegmentStatusRecordID(s.SegmentStatusRecordID), CommandID: s.CommandID, SegmentRef: domain.SegmentRef(s.SegmentRef), ScheduledServiceRef: s.ScheduledServiceRef, ServiceDate: s.ServiceDate, Status: domain.SegmentOperationalStatus(s.Status), EstimatedArrivalAt: utcPtr(s.EstimatedArrivalAt), ArrivedAt: utcPtr(s.ArrivedAt), CancelledAt: utcPtr(s.CancelledAt), ObservedAt: s.ObservedAt.UTC(), SourceSystem: s.SourceSystem, CreatedAt: s.CreatedAt.UTC()}, nil
}

type ancillaryHandoffSnapshot struct {
	AncillaryOrderItemID string                            `json:"ancillaryOrderItemId"`
	JourneyOrderID       string                            `json:"journeyOrderId"`
	TravelerRef          string                            `json:"travelerRef"`
	SegmentRef           string                            `json:"segmentRef,omitempty"`
	CatalogItemID        string                            `json:"catalogItemId"`
	ServiceType          string                            `json:"serviceType"`
	EntitlementRef       string                            `json:"entitlementRef,omitempty"`
	ProviderRef          string                            `json:"providerRef,omitempty"`
	Status               string                            `json:"status"`
	ReadyAt              *time.Time                        `json:"readyAt,omitempty"`
	Facts                []domain.AncillaryFulfillmentFact `json:"facts"`
	CreatedAt            time.Time                         `json:"createdAt"`
	UpdatedAt            time.Time                         `json:"updatedAt"`
}

func ancillaryHandoffSnapshotFromDomain(h *domain.AncillaryFulfillmentHandoff) ancillaryHandoffSnapshot {
	return ancillaryHandoffSnapshot{AncillaryOrderItemID: h.AncillaryOrderItemID, JourneyOrderID: string(h.JourneyOrderID), TravelerRef: string(h.TravelerRef), SegmentRef: string(h.SegmentRef), CatalogItemID: h.CatalogItemID, ServiceType: h.ServiceType, EntitlementRef: string(h.EntitlementRef), ProviderRef: h.ProviderRef, Status: h.Status, ReadyAt: utcPtr(h.ReadyAt), Facts: append([]domain.AncillaryFulfillmentFact(nil), h.Facts...), CreatedAt: h.CreatedAt.UTC(), UpdatedAt: h.UpdatedAt.UTC()}
}

func decodeAncillaryHandoff(raw []byte) (*domain.AncillaryFulfillmentHandoff, error) {
	var s ancillaryHandoffSnapshot
	if err := json.Unmarshal(raw, &s); err != nil {
		return nil, err
	}
	return &domain.AncillaryFulfillmentHandoff{AncillaryOrderItemID: s.AncillaryOrderItemID, JourneyOrderID: domain.OrderRef(s.JourneyOrderID), TravelerRef: domain.TravelerRef(s.TravelerRef), SegmentRef: domain.SegmentRef(s.SegmentRef), CatalogItemID: s.CatalogItemID, ServiceType: s.ServiceType, EntitlementRef: domain.EntitlementRef(s.EntitlementRef), ProviderRef: s.ProviderRef, Status: s.Status, ReadyAt: utcPtr(s.ReadyAt), Facts: append([]domain.AncillaryFulfillmentFact(nil), s.Facts...), CreatedAt: s.CreatedAt.UTC(), UpdatedAt: s.UpdatedAt.UTC()}, nil
}

type rideExecutionSnapshot struct {
	RideRequestID    string                         `json:"rideRequestId"`
	RideAssignmentID string                         `json:"rideAssignmentId"`
	RiderAccountID   string                         `json:"riderAccountId"`
	TravelerRef      string                         `json:"travelerRef"`
	PickupRef        string                         `json:"pickupRef"`
	DropoffRef       string                         `json:"dropoffRef"`
	DriverRef        string                         `json:"driverRef"`
	VehicleRef       string                         `json:"vehicleRef"`
	Status           string                         `json:"status"`
	DriverArrivedAt  *time.Time                     `json:"driverArrivedAt,omitempty"`
	RideStartedAt    *time.Time                     `json:"rideStartedAt,omitempty"`
	RideEndedAt      *time.Time                     `json:"rideEndedAt,omitempty"`
	FinalFareRef     string                         `json:"finalFareRef,omitempty"`
	Evidence         []domain.RideExecutionEvidence `json:"evidence"`
	CreatedAt        time.Time                      `json:"createdAt"`
	UpdatedAt        time.Time                      `json:"updatedAt"`
}

func rideExecutionSnapshotFromDomain(v *domain.RideExecutionView) rideExecutionSnapshot {
	return rideExecutionSnapshot{RideRequestID: v.RideRequestID, RideAssignmentID: v.RideAssignmentID, RiderAccountID: v.RiderAccountID, TravelerRef: string(v.TravelerRef), PickupRef: v.PickupRef, DropoffRef: v.DropoffRef, DriverRef: v.DriverRef, VehicleRef: v.VehicleRef, Status: v.Status, DriverArrivedAt: utcPtr(v.DriverArrivedAt), RideStartedAt: utcPtr(v.RideStartedAt), RideEndedAt: utcPtr(v.RideEndedAt), FinalFareRef: v.FinalFareRef, Evidence: append([]domain.RideExecutionEvidence(nil), v.Evidence...), CreatedAt: v.CreatedAt.UTC(), UpdatedAt: v.UpdatedAt.UTC()}
}

func decodeRideExecution(raw []byte) (*domain.RideExecutionView, error) {
	var s rideExecutionSnapshot
	if err := json.Unmarshal(raw, &s); err != nil {
		return nil, err
	}
	return &domain.RideExecutionView{RideRequestID: s.RideRequestID, RideAssignmentID: s.RideAssignmentID, RiderAccountID: s.RiderAccountID, TravelerRef: domain.TravelerRef(s.TravelerRef), PickupRef: s.PickupRef, DropoffRef: s.DropoffRef, DriverRef: s.DriverRef, VehicleRef: s.VehicleRef, Status: s.Status, DriverArrivedAt: utcPtr(s.DriverArrivedAt), RideStartedAt: utcPtr(s.RideStartedAt), RideEndedAt: utcPtr(s.RideEndedAt), FinalFareRef: s.FinalFareRef, Evidence: append([]domain.RideExecutionEvidence(nil), s.Evidence...), CreatedAt: s.CreatedAt.UTC(), UpdatedAt: s.UpdatedAt.UTC()}, nil
}

type ProcessedEvents struct{ db ContextDBProvider }

func NewProcessedEventsWithProvider(db ContextDBProvider) *ProcessedEvents {
	return &ProcessedEvents{db: db}
}
func (p *ProcessedEvents) Claim(ctx context.Context, eventID string) (bool, error) {
	return storage.NewProcessedEvents(p.db.DBFor(ctx)).TryRecord(ctx, eventID, "")
}

type recordSnapshot struct {
	FulfillmentRecordID string                   `json:"fulfillmentRecordId"`
	EntitlementID       string                   `json:"entitlementId"`
	SegmentBookingID    string                   `json:"segmentBookingId"`
	JourneyOrderID      string                   `json:"journeyOrderId"`
	TravelerID          string                   `json:"travelerId"`
	SegmentRef          string                   `json:"segmentRef"`
	Status              string                   `json:"status"`
	BoardingFact        *boardingFactSnapshot    `json:"boardingFact,omitempty"`
	NoShowReason        *domain.NoShowReason     `json:"noShowReason,omitempty"`
	NoShowAssessedAt    *time.Time               `json:"noShowAssessedAt,omitempty"`
	CompletedAt         *time.Time               `json:"completedAt,omitempty"`
	CompletionSource    *domain.CompletionSource `json:"completionSource,omitempty"`
	AuditTrail          []domain.AuditEntry      `json:"auditTrail"`
	CreatedAt           time.Time                `json:"createdAt"`
	UpdatedAt           time.Time                `json:"updatedAt"`
}

type boardingFactSnapshot struct {
	EntitlementID    string    `json:"entitlementId"`
	SegmentBookingID string    `json:"segmentBookingId"`
	SegmentRef       string    `json:"segmentRef"`
	Source           string    `json:"source"`
	SourceEventID    string    `json:"sourceEventId"`
	OccurredAt       time.Time `json:"occurredAt"`
	ReceivedAt       time.Time `json:"receivedAt"`
}

func recordSnapshotFromDomain(r *domain.FulfillmentRecord) recordSnapshot {
	s := recordSnapshot{FulfillmentRecordID: string(r.FulfillmentRecordID), EntitlementID: string(r.EntitlementID), SegmentBookingID: string(r.SegmentBookingID), JourneyOrderID: string(r.JourneyOrderID), TravelerID: string(r.TravelerID), SegmentRef: string(r.SegmentRef), Status: string(r.Status), NoShowReason: r.NoShowReason, NoShowAssessedAt: r.NoShowAssessedAt, CompletedAt: r.CompletedAt, CompletionSource: r.CompletionSource, AuditTrail: append([]domain.AuditEntry(nil), r.AuditTrail...), CreatedAt: r.CreatedAt.UTC(), UpdatedAt: r.UpdatedAt.UTC()}
	if r.BoardingFact != nil {
		s.BoardingFact = &boardingFactSnapshot{EntitlementID: string(r.BoardingFact.EntitlementID), SegmentBookingID: string(r.BoardingFact.SegmentBookingID), SegmentRef: string(r.BoardingFact.SegmentRef), Source: string(r.BoardingFact.Source), SourceEventID: r.BoardingFact.SourceEventID, OccurredAt: r.BoardingFact.OccurredAt.UTC(), ReceivedAt: r.BoardingFact.ReceivedAt.UTC()}
	}
	return s
}

func decodeRecord(raw []byte) (*domain.FulfillmentRecord, error) {
	var s recordSnapshot
	if err := json.Unmarshal(raw, &s); err != nil {
		return nil, err
	}
	r := &domain.FulfillmentRecord{FulfillmentRecordID: domain.FulfillmentRecordID(s.FulfillmentRecordID), EntitlementID: domain.EntitlementRef(s.EntitlementID), SegmentBookingID: domain.SegmentBookingRef(s.SegmentBookingID), JourneyOrderID: domain.OrderRef(s.JourneyOrderID), TravelerID: domain.TravelerRef(s.TravelerID), SegmentRef: domain.SegmentRef(s.SegmentRef), Status: domain.FulfillmentStatus(s.Status), NoShowReason: s.NoShowReason, NoShowAssessedAt: s.NoShowAssessedAt, CompletedAt: s.CompletedAt, CompletionSource: s.CompletionSource, AuditTrail: append([]domain.AuditEntry(nil), s.AuditTrail...), CreatedAt: s.CreatedAt.UTC(), UpdatedAt: s.UpdatedAt.UTC()}
	if s.BoardingFact != nil {
		r.BoardingFact = &domain.BoardingFact{EntitlementID: domain.EntitlementRef(s.BoardingFact.EntitlementID), SegmentBookingID: domain.SegmentBookingRef(s.BoardingFact.SegmentBookingID), SegmentRef: domain.SegmentRef(s.BoardingFact.SegmentRef), Source: domain.FulfillmentSource(s.BoardingFact.Source), SourceEventID: s.BoardingFact.SourceEventID, OccurredAt: s.BoardingFact.OccurredAt.UTC(), ReceivedAt: s.BoardingFact.ReceivedAt.UTC()}
	}
	return r, nil
}
