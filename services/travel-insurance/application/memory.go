package application

import (
	"context"
	"fmt"
	"sync"

	"github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

type InMemoryRepository struct {
	mu       sync.RWMutex
	products map[string]domain.InsuranceProduct
	policies map[string]domain.Policy
	claims   map[string]domain.Claim
	advices  map[string]domain.PayoutAdvice
	offers   map[string]domain.InsuranceOffer
}

func NewInMemoryRepository() *InMemoryRepository {
	repo := &InMemoryRepository{products: map[string]domain.InsuranceProduct{}, policies: map[string]domain.Policy{}, claims: map[string]domain.Claim{}, advices: map[string]domain.PayoutAdvice{}, offers: map[string]domain.InsuranceOffer{}}
	return repo
}

func productKey(code domain.ProductCode, version int) string {
	return fmt.Sprintf("%s:%d", code, version)
}

func (r *InMemoryRepository) SaveProduct(_ context.Context, p domain.InsuranceProduct) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.products[productKey(p.ProductCode, p.Version)] = p
	return nil
}
func (r *InMemoryRepository) ListProducts(_ context.Context) ([]domain.InsuranceProduct, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]domain.InsuranceProduct, 0, len(r.products))
	for _, p := range r.products {
		out = append(out, p)
	}
	return out, nil
}
func (r *InMemoryRepository) FindProduct(_ context.Context, code domain.ProductCode, version int) (domain.InsuranceProduct, bool, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	p, ok := r.products[productKey(code, version)]
	return p, ok, nil
}
func (r *InMemoryRepository) SavePolicy(_ context.Context, p domain.Policy) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.policies[p.ID] = p
	return nil
}
func (r *InMemoryRepository) GetPolicy(_ context.Context, id string) (domain.Policy, bool, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	p, ok := r.policies[id]
	return p, ok, nil
}
func (r *InMemoryRepository) FindPolicyBySelection(_ context.Context, candidate domain.Policy) (domain.Policy, bool, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	fp := candidate.SelectionFingerprint()
	for _, p := range r.policies {
		if p.SelectionFingerprint() == fp && p.Status != domain.PolicyClosed && p.Status != domain.PolicySurrendered && p.Status != domain.PolicyUnderwritingFailed {
			return p, true, nil
		}
	}
	return domain.Policy{}, false, nil
}
func (r *InMemoryRepository) ListPoliciesByOrder(_ context.Context, orderID string) ([]domain.Policy, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := []domain.Policy{}
	for _, p := range r.policies {
		if p.JourneyOrderID == orderID {
			out = append(out, p)
		}
	}
	return out, nil
}
func (r *InMemoryRepository) SaveClaim(_ context.Context, c domain.Claim) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.claims[c.ID] = c
	return nil
}
func (r *InMemoryRepository) GetClaim(_ context.Context, id string) (domain.Claim, bool, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	c, ok := r.claims[id]
	return c, ok, nil
}
func (r *InMemoryRepository) FindActiveClaim(_ context.Context, policyID string, claimType domain.ClaimType, triggerFactKey string) (domain.Claim, bool, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	for _, c := range r.claims {
		if c.PolicyID == policyID && c.ClaimType == claimType && c.TriggerFactKey == triggerFactKey && c.Status != domain.ClaimClosed && c.Status != domain.ClaimRejected && c.Status != domain.ClaimFailed {
			return c, true, nil
		}
	}
	return domain.Claim{}, false, nil
}
func (r *InMemoryRepository) SavePayoutAdvice(_ context.Context, a domain.PayoutAdvice) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.advices[a.ID] = a
	return nil
}
func (r *InMemoryRepository) SaveOffer(_ context.Context, o domain.InsuranceOffer) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.offers[o.OfferID] = o
	return nil
}
func (r *InMemoryRepository) ListOffersByOrder(_ context.Context, orderID string) ([]domain.InsuranceOffer, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := []domain.InsuranceOffer{}
	for _, o := range r.offers {
		if o.JourneyOrderID == orderID {
			out = append(out, o)
		}
	}
	return out, nil
}

type NoopPublisher struct{}

func (NoopPublisher) Publish(context.Context, messaging.EventEnvelope) error { return nil }
