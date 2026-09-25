package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/travel-insurance/application"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

type Repository struct{ db ContextDBProvider }

func NewRepositoryWithProvider(db ContextDBProvider) *Repository { return &Repository{db: db} }

func (r *Repository) SaveProduct(ctx context.Context, product domain.InsuranceProduct) error {
	return saveJSON(ctx, r.db.DBFor(ctx), "insurance_product_snapshots", product.ID, product)
}
func (r *Repository) FindPublishedProduct(ctx context.Context, code domain.ProductCode, version string) (domain.InsuranceProduct, error) {
	row := r.db.DBFor(ctx).QueryRow(ctx, `SELECT data FROM insurance_product_snapshots WHERE data->>'productCode' = $1 AND data->>'version' = $2 AND data->>'status' = 'PUBLISHED' LIMIT 1`, string(code), strings.TrimSpace(version))
	var raw json.RawMessage
	if err := row.Scan(&raw); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return domain.InsuranceProduct{}, application.ErrNotFound
		}
		return domain.InsuranceProduct{}, err
	}
	var product domain.InsuranceProduct
	return product, json.Unmarshal(raw, &product)
}
func (r *Repository) SavePolicy(ctx context.Context, policy domain.Policy) error {
	return saveJSON(ctx, r.db.DBFor(ctx), "policy_snapshots", policy.ID, policy)
}
func (r *Repository) FindPolicy(ctx context.Context, id string) (domain.Policy, error) {
	snap, ok, err := storage.NewSnapshotRepository(r.db.DBFor(ctx), "policy_snapshots").Get(ctx, strings.TrimSpace(id))
	if err != nil {
		return domain.Policy{}, err
	}
	if !ok {
		return domain.Policy{}, application.ErrNotFound
	}
	var policy domain.Policy
	return policy, json.Unmarshal(snap.Data, &policy)
}
func (r *Repository) FindPolicyByUniqueness(ctx context.Context, productCode domain.ProductCode, ancillaryID, travelerRef, segmentScopeHash, productVersion string) (domain.Policy, error) {
	if _, err := r.db.DBFor(ctx).Exec(ctx, "SELECT pg_advisory_xact_lock(hashtextextended($1,0))", "insurance-purchase:"+ancillaryID+":"+travelerRef); err != nil {
		return domain.Policy{}, err
	}
	rows, err := r.db.DBFor(ctx).Query(ctx, `SELECT data FROM policy_snapshots WHERE data->>'productCode' = $1 AND data->>'ancillaryOrderItemId' = $2 AND data->>'travelerRef' = $3 AND data->>'productVersion' = $4`, string(productCode), strings.TrimSpace(ancillaryID), strings.TrimSpace(travelerRef), strings.TrimSpace(productVersion))
	if err != nil {
		return domain.Policy{}, err
	}
	defer rows.Close()
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return domain.Policy{}, err
		}
		var policy domain.Policy
		if err := json.Unmarshal(raw, &policy); err != nil {
			return domain.Policy{}, err
		}
		if policy.ProductCode == productCode && policy.SegmentScopeHash() == segmentScopeHash {
			return policy, nil
		}
	}
	if err := rows.Err(); err != nil {
		return domain.Policy{}, err
	}
	return domain.Policy{}, application.ErrNotFound
}

func (r *Repository) FindIssuedPoliciesForSegment(ctx context.Context, code domain.ProductCode, segmentRef string, occurredAt time.Time) ([]domain.Policy, error) {
	rows, err := r.db.DBFor(ctx).Query(ctx, `SELECT data FROM policy_snapshots WHERE data->>'productCode' = $1 AND data->>'status' = $2 AND data->'segmentRefs' ? $3 AND (data->>'coverageStartAt')::timestamptz <= $4 AND (data->>'coverageEndAt')::timestamptz >= $4`, string(code), string(domain.PolicyIssued), strings.TrimSpace(segmentRef), occurredAt.UTC())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	policies := []domain.Policy{}
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		var policy domain.Policy
		if err := json.Unmarshal(raw, &policy); err != nil {
			return nil, err
		}
		policies = append(policies, policy)
	}
	return policies, rows.Err()
}

func (r *Repository) FindIssuedPolicyByJourneyOrder(ctx context.Context, journeyOrderID string) (domain.Policy, error) {
	row := r.db.DBFor(ctx).QueryRow(ctx, `SELECT data FROM policy_snapshots WHERE data->>'journeyOrderId' = $1 AND data->>'status' = $2 LIMIT 1`, strings.TrimSpace(journeyOrderID), string(domain.PolicyIssued))
	var raw json.RawMessage
	if err := row.Scan(&raw); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return domain.Policy{}, application.ErrNotFound
		}
		return domain.Policy{}, err
	}
	var policy domain.Policy
	return policy, json.Unmarshal(raw, &policy)
}

func (r *Repository) FindIssuedPoliciesByJourneyOrder(ctx context.Context, journeyOrderID string) ([]domain.Policy, error) {
	if _, err := r.db.DBFor(ctx).Exec(ctx, "SELECT pg_advisory_xact_lock(hashtextextended($1,0))", "insurance-journey-change:"+strings.TrimSpace(journeyOrderID)); err != nil {
		return nil, err
	}
	rows, err := r.db.DBFor(ctx).Query(ctx, `SELECT data FROM policy_snapshots WHERE data->>'journeyOrderId'=$1 AND data->>'status'=$2 ORDER BY id`, strings.TrimSpace(journeyOrderID), string(domain.PolicyIssued))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var policies []domain.Policy
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		var policy domain.Policy
		if err := json.Unmarshal(raw, &policy); err != nil {
			return nil, err
		}
		policies = append(policies, policy)
	}
	return policies, rows.Err()
}

func (r *Repository) SaveClaim(ctx context.Context, claim domain.Claim) error {
	return saveJSON(ctx, r.db.DBFor(ctx), "claim_snapshots", claim.ID, claim)
}

func (r *Repository) FindPoliciesByAncillaryItem(ctx context.Context, itemID string) ([]domain.Policy, error) {
	if _, err := r.db.DBFor(ctx).Exec(ctx, "SELECT pg_advisory_xact_lock(hashtextextended($1,0))", "insurance-refund:"+itemID); err != nil {
		return nil, err
	}
	rows, err := r.db.DBFor(ctx).Query(ctx, "SELECT data FROM policy_snapshots WHERE data->>'ancillaryOrderItemId'=$1 ORDER BY id", itemID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var policies []domain.Policy
	for rows.Next() {
		var data json.RawMessage
		if err := rows.Scan(&data); err != nil {
			return nil, err
		}
		var policy domain.Policy
		if err := json.Unmarshal(data, &policy); err != nil {
			return nil, err
		}
		policies = append(policies, policy)
	}
	return policies, rows.Err()
}
func (r *Repository) FindClaim(ctx context.Context, id string) (domain.Claim, error) {
	snap, ok, err := storage.NewSnapshotRepository(r.db.DBFor(ctx), "claim_snapshots").Get(ctx, strings.TrimSpace(id))
	if err != nil {
		return domain.Claim{}, err
	}
	if !ok {
		return domain.Claim{}, application.ErrNotFound
	}
	var claim domain.Claim
	return claim, json.Unmarshal(snap.Data, &claim)
}
func (r *Repository) LockClaim(ctx context.Context, id string) error {
	_, err := r.db.DBFor(ctx).Exec(ctx, "SELECT pg_advisory_xact_lock(hashtextextended($1,0))", "insurance-claim:"+strings.TrimSpace(id))
	return err
}
func (r *Repository) FindActiveClaim(ctx context.Context, policyID string, claimType domain.ClaimType, triggerFactKey string) (domain.Claim, error) {
	rows, err := r.db.DBFor(ctx).Query(ctx, `SELECT data FROM claim_snapshots WHERE data->>'policyId' = $1 AND data->>'claimType' = $2 AND data->>'triggerFactKey' = $3`, strings.TrimSpace(policyID), string(claimType), strings.TrimSpace(triggerFactKey))
	if err != nil {
		return domain.Claim{}, err
	}
	defer rows.Close()
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return domain.Claim{}, err
		}
		var claim domain.Claim
		if err := json.Unmarshal(raw, &claim); err != nil {
			return domain.Claim{}, err
		}
		if claim.Status != domain.ClaimRejected && claim.Status != domain.ClaimFailed && claim.Status != domain.ClaimClosed {
			return claim, nil
		}
	}
	if err := rows.Err(); err != nil {
		return domain.Claim{}, err
	}
	return domain.Claim{}, application.ErrNotFound
}
func (r *Repository) SavePayoutAdvice(ctx context.Context, advice domain.PayoutAdvice) error {
	return saveJSON(ctx, r.db.DBFor(ctx), "payout_advice_snapshots", advice.ID, advice)
}
func (r *Repository) FindPayoutAdvice(ctx context.Context, id string) (domain.PayoutAdvice, error) {
	snap, ok, err := storage.NewSnapshotRepository(r.db.DBFor(ctx), "payout_advice_snapshots").Get(ctx, strings.TrimSpace(id))
	if err != nil {
		return domain.PayoutAdvice{}, err
	}
	if !ok {
		return domain.PayoutAdvice{}, application.ErrNotFound
	}
	var advice domain.PayoutAdvice
	return advice, json.Unmarshal(snap.Data, &advice)
}
func (r *Repository) FindPayoutAdviceByClaim(ctx context.Context, claimID string) (domain.PayoutAdvice, error) {
	row := r.db.DBFor(ctx).QueryRow(ctx, `SELECT data FROM payout_advice_snapshots WHERE data->>'claimId'=$1 ORDER BY updated_at DESC LIMIT 1`, strings.TrimSpace(claimID))
	var raw json.RawMessage
	if err := row.Scan(&raw); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return domain.PayoutAdvice{}, application.ErrNotFound
		}
		return domain.PayoutAdvice{}, err
	}
	var advice domain.PayoutAdvice
	return advice, json.Unmarshal(raw, &advice)
}

func saveJSON(ctx context.Context, db storage.DBTX, table, id string, value any) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
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
