package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/service-plan/internal/application"
)

type Repository struct{ db ContextDBProvider }

func NewRepositoryWithProvider(db ContextDBProvider) *Repository { return &Repository{db: db} }

func (r *Repository) SaveScheduledService(ctx context.Context, service application.ScheduledService) error {
	data, err := json.Marshal(service)
	if err != nil {
		return err
	}
	return insertOrUpdate(ctx, r.db.DBFor(ctx), "scheduled_service_snapshots", service.ScheduledServiceRef, data)
}
func (r *Repository) FindScheduledService(ctx context.Context, ref string) (application.ScheduledService, error) {
	snap, ok, err := storage.NewSnapshotRepository(r.db.DBFor(ctx), "scheduled_service_snapshots").Get(ctx, strings.TrimSpace(ref))
	if err != nil {
		return application.ScheduledService{}, err
	}
	if !ok {
		return application.ScheduledService{}, application.ErrNotFound
	}
	var service application.ScheduledService
	return service, json.Unmarshal(snap.Data, &service)
}
func (r *Repository) ListScheduledServices(ctx context.Context, q application.ListScheduledServicesQuery) (application.PaginatedScheduledServices, error) {
	if q.Limit <= 0 {
		q.Limit = 20
	}
	if q.Limit > 100 {
		q.Limit = 100
	}
	if q.Offset < 0 {
		q.Offset = 0
	}
	where := ""
	countArgs := []any{}
	selectArgs := []any{q.Limit, q.Offset}
	if strings.TrimSpace(q.CarrierID) != "" {
		where = "WHERE data->>'carrierId' = $1"
		countArgs = append(countArgs, strings.TrimSpace(q.CarrierID))
		selectArgs = append(selectArgs, strings.TrimSpace(q.CarrierID))
	}
	db := r.db.DBFor(ctx)
	var page application.PaginatedScheduledServices
	page.Limit, page.Offset = q.Limit, q.Offset
	if err := db.QueryRow(ctx, "SELECT count(*)::int FROM scheduled_service_snapshots "+where, countArgs...).Scan(&page.Total); err != nil {
		return page, err
	}
	selectWhere := where
	if where != "" {
		selectWhere = "WHERE data->>'carrierId' = $3"
	}
	rows, err := db.Query(ctx, fmt.Sprintf("SELECT data FROM scheduled_service_snapshots %s ORDER BY id LIMIT $1 OFFSET $2", selectWhere), selectArgs...)
	if err != nil {
		return page, err
	}
	defer rows.Close()
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return page, err
		}
		var service application.ScheduledService
		if err := json.Unmarshal(raw, &service); err != nil {
			return page, err
		}
		page.Items = append(page.Items, service)
	}
	return page, rows.Err()
}
func (r *Repository) SaveServiceSegment(ctx context.Context, segment application.ServiceSegment) error {
	data, err := json.Marshal(segment)
	if err != nil {
		return err
	}
	return insertOrUpdate(ctx, r.db.DBFor(ctx), "service_segment_snapshots", segment.SegmentRef, data)
}

func insertOrUpdate(ctx context.Context, db storage.DBTX, table, id string, data []byte) error {
	repo := storage.NewSnapshotRepository(db, table)
	snap, ok, err := repo.Get(ctx, id)
	if err != nil {
		return err
	}
	if !ok {
		err = repo.Insert(ctx, id, data)
	} else {
		_, err = repo.Save(ctx, id, snap.Version, data)
	}
	if errors.Is(err, storage.ErrConflict) {
		return fmt.Errorf("%w: snapshot conflict", application.ErrConflict)
	}
	return err
}
