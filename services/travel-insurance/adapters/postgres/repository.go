package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/trainticket/greenfield/platform/go-kit/storage"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

type DBProvider interface {
	DBFor(context.Context) storage.DBTX
}

type staticProvider struct{ db storage.DBTX }

func (p staticProvider) DBFor(context.Context) storage.DBTX { return p.db }

type Repository struct{ provider DBProvider }

func NewRepository(db storage.DBTX) *Repository { return &Repository{provider: staticProvider{db: db}} }
func NewRepositoryWithProvider(provider DBProvider) *Repository {
	return &Repository{provider: provider}
}
func (r *Repository) db(ctx context.Context) storage.DBTX { return r.provider.DBFor(ctx) }

func (r *Repository) SaveProduct(ctx context.Context, p domain.InsuranceProduct) error {
	data, err := json.Marshal(p)
	if err != nil {
		return err
	}
	_, err = r.db(ctx).Exec(ctx, `INSERT INTO insurance_products (id, product_code, version, status, data) VALUES ($1,$2,$3,$4,$5) ON CONFLICT (product_code, version) DO UPDATE SET status=EXCLUDED.status, data=EXCLUDED.data, updated_at=now()`, p.ID, p.ProductCode, p.Version, p.Status, json.RawMessage(data))
	return err
}
func (r *Repository) ListProducts(ctx context.Context) ([]domain.InsuranceProduct, error) {
	rows, err := r.db(ctx).Query(ctx, `SELECT data FROM insurance_products ORDER BY product_code, version`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.InsuranceProduct
	for rows.Next() {
		var data []byte
		if err := rows.Scan(&data); err != nil {
			return nil, err
		}
		var p domain.InsuranceProduct
		if err := json.Unmarshal(data, &p); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}
func (r *Repository) FindProduct(ctx context.Context, code domain.ProductCode, version int) (domain.InsuranceProduct, bool, error) {
	var data []byte
	err := r.db(ctx).QueryRow(ctx, `SELECT data FROM insurance_products WHERE product_code=$1 AND version=$2`, code, version).Scan(&data)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.InsuranceProduct{}, false, nil
	}
	if err != nil {
		return domain.InsuranceProduct{}, false, err
	}
	var p domain.InsuranceProduct
	return p, true, json.Unmarshal(data, &p)
}
func (r *Repository) SavePolicy(ctx context.Context, p domain.Policy) error {
	data, err := json.Marshal(p)
	if err != nil {
		return err
	}
	_, err = r.db(ctx).Exec(ctx, `INSERT INTO policies (id, journey_order_id, account_id, status, selection_fingerprint, data) VALUES ($1,$2,$3,$4,$5,$6) ON CONFLICT (id) DO UPDATE SET status=EXCLUDED.status, data=EXCLUDED.data, updated_at=now()`, p.ID, p.JourneyOrderID, p.AccountID, p.Status, p.SelectionFingerprint(), json.RawMessage(data))
	return err
}
func (r *Repository) GetPolicy(ctx context.Context, id string) (domain.Policy, bool, error) {
	var data []byte
	err := r.db(ctx).QueryRow(ctx, `SELECT data FROM policies WHERE id=$1`, strings.TrimSpace(id)).Scan(&data)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Policy{}, false, nil
	}
	if err != nil {
		return domain.Policy{}, false, err
	}
	var p domain.Policy
	return p, true, json.Unmarshal(data, &p)
}
func (r *Repository) FindPolicyBySelection(ctx context.Context, candidate domain.Policy) (domain.Policy, bool, error) {
	var data []byte
	err := r.db(ctx).QueryRow(ctx, `SELECT data FROM policies WHERE selection_fingerprint=$1 AND status NOT IN ('CLOSED','SURRENDERED','UNDERWRITING_FAILED') LIMIT 1`, candidate.SelectionFingerprint()).Scan(&data)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Policy{}, false, nil
	}
	if err != nil {
		return domain.Policy{}, false, err
	}
	var p domain.Policy
	return p, true, json.Unmarshal(data, &p)
}
func (r *Repository) ListPoliciesByOrder(ctx context.Context, orderID string) ([]domain.Policy, error) {
	rows, err := r.db(ctx).Query(ctx, `SELECT data FROM policies WHERE journey_order_id=$1 ORDER BY created_at`, strings.TrimSpace(orderID))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.Policy
	for rows.Next() {
		var data []byte
		if err := rows.Scan(&data); err != nil {
			return nil, err
		}
		var p domain.Policy
		if err := json.Unmarshal(data, &p); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}
func (r *Repository) SaveClaim(ctx context.Context, c domain.Claim) error {
	data, err := json.Marshal(c)
	if err != nil {
		return err
	}
	_, err = r.db(ctx).Exec(ctx, `INSERT INTO claims (id, policy_id, claim_type, trigger_fact_key, status, data) VALUES ($1,$2,$3,$4,$5,$6) ON CONFLICT (id) DO UPDATE SET status=EXCLUDED.status, data=EXCLUDED.data, updated_at=now()`, c.ID, c.PolicyID, c.ClaimType, c.TriggerFactKey, c.Status, json.RawMessage(data))
	return err
}
func (r *Repository) GetClaim(ctx context.Context, id string) (domain.Claim, bool, error) {
	var data []byte
	err := r.db(ctx).QueryRow(ctx, `SELECT data FROM claims WHERE id=$1`, strings.TrimSpace(id)).Scan(&data)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Claim{}, false, nil
	}
	if err != nil {
		return domain.Claim{}, false, err
	}
	var c domain.Claim
	return c, true, json.Unmarshal(data, &c)
}
func (r *Repository) FindActiveClaim(ctx context.Context, policyID string, claimType domain.ClaimType, triggerFactKey string) (domain.Claim, bool, error) {
	var data []byte
	err := r.db(ctx).QueryRow(ctx, `SELECT data FROM claims WHERE policy_id=$1 AND claim_type=$2 AND trigger_fact_key=$3 AND status NOT IN ('CLOSED','REJECTED','FAILED') LIMIT 1`, policyID, claimType, triggerFactKey).Scan(&data)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Claim{}, false, nil
	}
	if err != nil {
		return domain.Claim{}, false, err
	}
	var c domain.Claim
	return c, true, json.Unmarshal(data, &c)
}
func (r *Repository) SavePayoutAdvice(ctx context.Context, a domain.PayoutAdvice) error {
	data, err := json.Marshal(a)
	if err != nil {
		return err
	}
	_, err = r.db(ctx).Exec(ctx, `INSERT INTO payout_advices (id, claim_id, policy_id, status, data) VALUES ($1,$2,$3,$4,$5) ON CONFLICT (id) DO UPDATE SET status=EXCLUDED.status, data=EXCLUDED.data, updated_at=now()`, a.ID, a.ClaimID, a.PolicyID, a.Status, json.RawMessage(data))
	return err
}
func (r *Repository) SaveOffer(ctx context.Context, o domain.InsuranceOffer) error {
	data, err := json.Marshal(o)
	if err != nil {
		return err
	}
	_, err = r.db(ctx).Exec(ctx, `INSERT INTO insurance_offers (id, journey_order_id, account_id, data) VALUES ($1,$2,$3,$4) ON CONFLICT (id) DO UPDATE SET data=EXCLUDED.data, updated_at=now()`, o.OfferID, o.JourneyOrderID, o.AccountID, json.RawMessage(data))
	return err
}
func (r *Repository) ListOffersByOrder(ctx context.Context, orderID string) ([]domain.InsuranceOffer, error) {
	rows, err := r.db(ctx).Query(ctx, `SELECT data FROM insurance_offers WHERE journey_order_id=$1 ORDER BY created_at`, strings.TrimSpace(orderID))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.InsuranceOffer
	for rows.Next() {
		var data []byte
		if err := rows.Scan(&data); err != nil {
			return nil, err
		}
		var o domain.InsuranceOffer
		if err := json.Unmarshal(data, &o); err != nil {
			return nil, err
		}
		out = append(out, o)
	}
	return out, rows.Err()
}
