package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
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

type ProcessedEvents struct{ db ContextDBProvider }

func NewProcessedEventsWithProvider(db ContextDBProvider) *ProcessedEvents {
	return &ProcessedEvents{db: db}
}
func (p *ProcessedEvents) Claim(ctx context.Context, eventID string) (bool, error) {
	return storage.NewProcessedEvents(p.db.DBFor(ctx)).TryRecord(ctx, eventID, "")
}

func (r *FulfillmentRepository) SaveExternalFulfillmentHandoff(ctx context.Context, handoff *domain.ExternalFulfillmentHandoff) error {
	data, err := json.Marshal(handoff)
	if err != nil {
		return err
	}
	_, err = r.db.DBFor(ctx).Exec(ctx, `INSERT INTO external_fulfillment_handoffs(producer, source_ref, data, updated_at) VALUES ($1, $2, $3, now()) ON CONFLICT (producer, source_ref) DO UPDATE SET data = EXCLUDED.data, updated_at = now()`, handoff.Producer, handoff.SourceRef, data)
	return err
}

func (r *FulfillmentRepository) FindExternalFulfillmentHandoff(ctx context.Context, producer string, sourceRef string) (*domain.ExternalFulfillmentHandoff, error) {
	rows, err := r.db.DBFor(ctx).Query(ctx, `SELECT data FROM external_fulfillment_handoffs WHERE producer = $1 AND source_ref = $2 LIMIT 1`, producer, sourceRef)
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
	var handoff domain.ExternalFulfillmentHandoff
	if err := json.Unmarshal(raw, &handoff); err != nil {
		return nil, err
	}
	return &handoff, nil
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
