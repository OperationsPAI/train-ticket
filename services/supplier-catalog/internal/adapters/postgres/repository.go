package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/supplier-catalog/internal/application"
	"github.com/trainticket/greenfield/services/supplier-catalog/internal/domain"
)

type Repository struct{ db ContextDBProvider }

func NewRepositoryWithProvider(db ContextDBProvider) *Repository { return &Repository{db: db} }
func (r *Repository) SaveSupplier(ctx context.Context, supplier domain.Supplier) error {
	data, err := json.Marshal(supplier)
	if err != nil {
		return err
	}
	return insertOrUpdate(ctx, r.db.DBFor(ctx), "supplier_snapshots", string(supplier.SupplierID), data)
}
func (r *Repository) SaveCarrier(ctx context.Context, carrier domain.Carrier) error {
	data, err := json.Marshal(carrier)
	if err != nil {
		return err
	}
	return insertOrUpdate(ctx, r.db.DBFor(ctx), "carrier_snapshots", string(carrier.CarrierID), data)
}
func (r *Repository) SaveContract(ctx context.Context, contract domain.Contract) error {
	data, err := json.Marshal(contract)
	if err != nil {
		return err
	}
	return insertOrUpdate(ctx, r.db.DBFor(ctx), "contract_snapshots", string(contract.ContractID), data)
}
func (r *Repository) FindSupplier(ctx context.Context, id string) (domain.Supplier, error) {
	var s domain.Supplier
	err := find(ctx, r.db.DBFor(ctx), "supplier_snapshots", strings.TrimSpace(id), &s)
	return s, err
}
func (r *Repository) FindCarrier(ctx context.Context, id string) (domain.Carrier, error) {
	var c domain.Carrier
	err := find(ctx, r.db.DBFor(ctx), "carrier_snapshots", strings.TrimSpace(id), &c)
	return c, err
}
func (r *Repository) ListSuppliers(ctx context.Context, status string, limit, offset int) (application.SupplierList, error) {
	if limit <= 0 {
		limit = 20
	}
	if limit > 100 {
		limit = 100
	}
	if offset < 0 {
		offset = 0
	}
	where := ""
	countArgs := []any{}
	selectArgs := []any{limit, offset}
	if strings.TrimSpace(status) != "" {
		where = "WHERE data->>'status' = $1"
		countArgs = append(countArgs, strings.TrimSpace(status))
		selectArgs = append(selectArgs, strings.TrimSpace(status))
	}
	db := r.db.DBFor(ctx)
	result := application.SupplierList{Limit: limit, Offset: offset}
	if err := db.QueryRow(ctx, "SELECT count(*)::int FROM supplier_snapshots "+where, countArgs...).Scan(&result.Total); err != nil {
		return result, err
	}
	selectWhere := where
	if where != "" {
		selectWhere = "WHERE data->>'status' = $3"
	}
	rows, err := db.Query(ctx, fmt.Sprintf("SELECT data FROM supplier_snapshots %s ORDER BY id LIMIT $1 OFFSET $2", selectWhere), selectArgs...)
	if err != nil {
		return result, err
	}
	defer rows.Close()
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return result, err
		}
		var supplier domain.Supplier
		if err := json.Unmarshal(raw, &supplier); err != nil {
			return result, err
		}
		result.Items = append(result.Items, application.SupplierView{SupplierID: string(supplier.SupplierID), LegalName: supplier.LegalName, BrandName: supplier.BrandName, Status: supplier.Status, RegisteredAt: supplier.RegisteredAt.UTC()})
	}
	return result, rows.Err()
}
func (r *Repository) FindSupplierByProfile(ctx context.Context, profile string) (domain.Supplier, error) {
	return findByData(ctx, r.db.DBFor(ctx), "supplier_snapshots", "profile", profile, domain.Supplier{})
}
func (r *Repository) FindCarrierByCode(ctx context.Context, code string) (domain.Carrier, error) {
	return findByData(ctx, r.db.DBFor(ctx), "carrier_snapshots", "code", code, domain.Carrier{})
}
func (r *Repository) FindContractByRef(ctx context.Context, ref string) (domain.Contract, error) {
	return findByData(ctx, r.db.DBFor(ctx), "contract_snapshots", "contractNo", ref, domain.Contract{})
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
func find(ctx context.Context, db storage.DBTX, table, id string, out any) error {
	snap, ok, err := storage.NewSnapshotRepository(db, table).Get(ctx, id)
	if err != nil {
		return err
	}
	if !ok {
		return application.ErrNotFound
	}
	return json.Unmarshal(snap.Data, out)
}
func findByData[T any](ctx context.Context, db storage.DBTX, table, field, value string, zero T) (T, error) {
	rows, err := db.Query(ctx, fmt.Sprintf("SELECT data FROM %s WHERE lower(data->>'%s') = lower($1) LIMIT 1", table, field), strings.TrimSpace(value))
	if err != nil {
		return zero, err
	}
	defer rows.Close()
	if !rows.Next() {
		return zero, application.ErrNotFound
	}
	var raw json.RawMessage
	if err := rows.Scan(&raw); err != nil {
		return zero, err
	}
	var out T
	return out, json.Unmarshal(raw, &out)
}
