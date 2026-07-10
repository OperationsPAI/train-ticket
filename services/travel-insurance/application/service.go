package application

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

type Clock interface{ Now() time.Time }
type realClock struct{}

func (realClock) Now() time.Time { return time.Now().UTC() }

type ServiceConfig struct {
	Repository  Repository
	Publisher   EventPublisher
	UnitOfWork  UnitOfWork
	Clock       Clock
	IDGenerator func(prefix string) string
}

type InsuranceService struct {
	mu          sync.Mutex
	repository  Repository
	publisher   EventPublisher
	uow         UnitOfWork
	clock       Clock
	idGenerator func(prefix string) string
	products    map[string]domain.InsuranceProduct
	policies    map[string]domain.Policy
	claims      map[string]domain.Claim
	advices     map[string]domain.PayoutAdvice
}

func NewInsuranceService(cfg ServiceConfig) *InsuranceService {
	publisher := cfg.Publisher
	if publisher == nil {
		publisher = NoopPublisher{}
	}
	clock := cfg.Clock
	if clock == nil {
		clock = realClock{}
	}
	idgen := cfg.IDGenerator
	if idgen == nil {
		idgen = ids.NewPrefixed
	}
	s := &InsuranceService{repository: cfg.Repository, publisher: publisher, uow: cfg.UnitOfWork, clock: clock, idGenerator: idgen, products: map[string]domain.InsuranceProduct{}, policies: map[string]domain.Policy{}, claims: map[string]domain.Claim{}, advices: map[string]domain.PayoutAdvice{}}
	s.seedProducts()
	return s
}

func (s *InsuranceService) seedProducts() {
	now := s.clock.Now()
	start := now.Add(-24 * time.Hour)
	end := now.AddDate(2, 0, 0)
	premiumDelay, _ := domain.NewMoney("CNY", 500)
	limitDelay, _ := domain.NewMoney("CNY", 3000)
	premiumAccident, _ := domain.NewMoney("CNY", 1200)
	limitAccident, _ := domain.NewMoney("CNY", 200000)
	for _, p := range []domain.InsuranceProduct{
		mustPublishedProduct("ip-delay-v1", domain.ProductDelayInsurance, "v1", premiumDelay, limitDelay, start, end, now),
		mustPublishedProduct("ip-accident-v1", domain.ProductAccidentInsurance, "v1", premiumAccident, limitAccident, start, end, now),
	} {
		s.products[productKey(p.ProductCode, p.Version)] = p
	}
}

func mustPublishedProduct(id string, code domain.ProductCode, version string, premium, limit domain.Money, start, end, now time.Time) domain.InsuranceProduct {
	p, err := domain.NewInsuranceProduct(id, code, version, premium, limit, start, end)
	if err != nil {
		panic(err)
	}
	published, err := p.Publish(now)
	if err != nil {
		panic(err)
	}
	return published
}

type IssuePolicyCommand struct {
	ProductCode          string
	ProductVersion       string
	JourneyOrderID       string
	AncillaryOrderItemID string
	AccountID            string
	TravelerRef          string
	SegmentRefs          []string
	PaymentIntentID      string
	CoverageStartAt      time.Time
	CoverageEndAt        time.Time
	CorrelationID        string
	CausationID          string
}

type FileClaimCommand struct {
	PolicyID       string
	ClaimType      string
	TriggerFactKey string
	DelayFact      *domain.DelayFact
	SupportCaseID  string
	EvidenceRefs   []string
	ClaimedAmount  domain.Money
	CorrelationID  string
	CausationID    string
}

type SettleClaimCommand struct {
	ClaimID       string
	PayoutTarget  string
	ReasonCode    string
	CorrelationID string
	CausationID   string
}

func (s *InsuranceService) IssuePolicy(ctx context.Context, cmd IssuePolicyCommand) (domain.Policy, error) {
	if err := validateIssuePolicy(cmd); err != nil {
		return domain.Policy{}, err
	}
	product, err := s.findProduct(ctx, domain.ProductCode(strings.TrimSpace(cmd.ProductCode)), strings.TrimSpace(cmd.ProductVersion))
	if err != nil {
		return domain.Policy{}, err
	}
	policy, err := domain.NewIssuedPolicy(domain.PolicyIssueRequest{PolicyID: s.idGenerator("pol"), Product: product, JourneyOrderID: cmd.JourneyOrderID, AncillaryOrderItemID: cmd.AncillaryOrderItemID, AccountID: cmd.AccountID, TravelerRef: cmd.TravelerRef, SegmentRefs: cmd.SegmentRefs, PaymentIntentID: cmd.PaymentIntentID, CoverageStartAt: cmd.CoverageStartAt, CoverageEndAt: cmd.CoverageEndAt, Now: s.clock.Now()})
	if err != nil {
		return domain.Policy{}, fmt.Errorf("%w: %v", ErrDomainRule, err)
	}
	if existing, ok, err := s.findExistingPolicy(ctx, policy); err != nil {
		return domain.Policy{}, err
	} else if ok {
		return existing, nil
	}
	envelope, err := s.newEnvelope(ctx, "PolicyIssued", cmd.CorrelationID, cmd.CausationID, map[string]any{"policyId": policy.ID, "policyNumber": policy.PolicyNumber, "productCode": policy.ProductCode, "productVersion": policy.ProductVersion, "journeyOrderId": policy.JourneyOrderID, "ancillaryOrderItemId": policy.AncillaryOrderItemID, "accountId": policy.AccountID, "travelerRef": policy.TravelerRef, "segmentRefs": policy.SegmentRefs, "premium": policy.Premium, "coverageLimit": policy.CoverageLimit, "coverageStartAt": policy.CoverageStartAt, "coverageEndAt": policy.CoverageEndAt, "status": policy.Status})
	if err != nil {
		return domain.Policy{}, err
	}
	if err := s.within(ctx, func(tx context.Context) error {
		if s.repository != nil {
			if err := s.repository.SavePolicy(tx, policy); err != nil {
				return mapRepoErr(err)
			}
		}
		if err := s.publisher.Publish(tx, envelope); err != nil {
			return fmt.Errorf("%w: %v", ErrPublish, err)
		}
		return nil
	}); err != nil {
		return domain.Policy{}, err
	}
	s.mu.Lock()
	s.policies[policy.ID] = policy
	s.mu.Unlock()
	return policy, nil
}

func (s *InsuranceService) GetPolicy(ctx context.Context, id string) (domain.Policy, error) {
	id = strings.TrimSpace(id)
	if id == "" {
		return domain.Policy{}, fmt.Errorf("%w: policy id is required", ErrValidation)
	}
	if s.repository != nil {
		return s.repository.FindPolicy(ctx, id)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	policy, ok := s.policies[id]
	if !ok {
		return domain.Policy{}, ErrNotFound
	}
	return policy, nil
}

func (s *InsuranceService) FileClaim(ctx context.Context, cmd FileClaimCommand) (domain.Claim, error) {
	if err := validateFileClaim(cmd); err != nil {
		return domain.Claim{}, err
	}
	policy, err := s.GetPolicy(ctx, cmd.PolicyID)
	if err != nil {
		return domain.Claim{}, err
	}
	claimType := domain.ClaimType(strings.TrimSpace(cmd.ClaimType))
	if existing, ok, err := s.findActiveClaim(ctx, policy.ID, claimType, cmd.TriggerFactKey); err != nil {
		return domain.Claim{}, err
	} else if ok {
		return existing, nil
	}
	claim, err := domain.OpenClaim(s.idGenerator("clm"), policy, claimType, cmd.TriggerFactKey, cmd.DelayFact, cmd.SupportCaseID, cmd.EvidenceRefs, cmd.ClaimedAmount, s.clock.Now())
	if err != nil {
		return domain.Claim{}, fmt.Errorf("%w: %v", ErrDomainRule, err)
	}
	eventType := "ClaimFiled"
	payload := map[string]any{"claimId": claim.ID, "policyId": claim.PolicyID, "claimType": claim.ClaimType, "triggerFactKey": claim.TriggerFactKey, "status": claim.Status, "claimedAmount": claim.ClaimedAmount, "approvedAmount": claim.ApprovedAmount, "supportCaseId": claim.SupportCaseID, "evidenceRefs": claim.EvidenceRefs}
	if claim.DelayFact != nil {
		payload["delayFact"] = claim.DelayFact
	}
	envelope, err := s.newEnvelope(ctx, eventType, cmd.CorrelationID, cmd.CausationID, payload)
	if err != nil {
		return domain.Claim{}, err
	}
	if err := s.within(ctx, func(tx context.Context) error {
		if s.repository != nil {
			if err := s.repository.SaveClaim(tx, claim); err != nil {
				return mapRepoErr(err)
			}
		}
		if err := s.publisher.Publish(tx, envelope); err != nil {
			return fmt.Errorf("%w: %v", ErrPublish, err)
		}
		return nil
	}); err != nil {
		return domain.Claim{}, err
	}
	s.mu.Lock()
	s.claims[claim.ID] = claim
	s.mu.Unlock()
	return claim, nil
}

func (s *InsuranceService) SettleClaim(ctx context.Context, cmd SettleClaimCommand) (domain.PayoutAdvice, error) {
	claimID := strings.TrimSpace(cmd.ClaimID)
	if claimID == "" {
		return domain.PayoutAdvice{}, fmt.Errorf("%w: claim id is required", ErrValidation)
	}
	claim, err := s.findClaim(ctx, claimID)
	if err != nil {
		return domain.PayoutAdvice{}, err
	}
	advice, err := claim.Settle(s.idGenerator("pad"), domain.PayoutTarget(strings.TrimSpace(cmd.PayoutTarget)), strings.TrimSpace(cmd.ReasonCode))
	if err != nil {
		return domain.PayoutAdvice{}, fmt.Errorf("%w: %v", ErrDomainRule, err)
	}
	envelope, err := s.newEnvelope(ctx, "ClaimSettled", cmd.CorrelationID, cmd.CausationID, map[string]any{"claimId": claim.ID, "policyId": claim.PolicyID, "payoutAdviceId": advice.ID, "payoutTarget": advice.PayoutTarget, "amount": advice.Amount, "reasonCode": advice.ReasonCode, "status": advice.Status})
	if err != nil {
		return domain.PayoutAdvice{}, err
	}
	if err := s.within(ctx, func(tx context.Context) error {
		if s.repository != nil {
			if err := s.repository.SaveClaim(tx, claim); err != nil {
				return mapRepoErr(err)
			}
			if err := s.repository.SavePayoutAdvice(tx, advice); err != nil {
				return mapRepoErr(err)
			}
		}
		if err := s.publisher.Publish(tx, envelope); err != nil {
			return fmt.Errorf("%w: %v", ErrPublish, err)
		}
		return nil
	}); err != nil {
		return domain.PayoutAdvice{}, err
	}
	s.mu.Lock()
	s.claims[claim.ID] = claim
	s.advices[advice.ID] = advice
	s.mu.Unlock()
	return advice, nil
}

func (s *InsuranceService) findProduct(ctx context.Context, code domain.ProductCode, version string) (domain.InsuranceProduct, error) {
	if s.repository != nil {
		p, err := s.repository.FindPublishedProduct(ctx, code, version)
		if err == nil {
			return p, nil
		}
		if err != ErrNotFound {
			return domain.InsuranceProduct{}, err
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	p, ok := s.products[productKey(code, version)]
	if !ok {
		return domain.InsuranceProduct{}, ErrNotFound
	}
	return p, nil
}

func (s *InsuranceService) findExistingPolicy(ctx context.Context, policy domain.Policy) (domain.Policy, bool, error) {
	if s.repository != nil {
		p, err := s.repository.FindPolicyByUniqueness(ctx, policy.AncillaryOrderItemID, policy.TravelerRef, policy.SegmentScopeHash(), policy.ProductVersion)
		if err == nil {
			return p, true, nil
		}
		if err != ErrNotFound {
			return domain.Policy{}, false, err
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, existing := range s.policies {
		if existing.AncillaryOrderItemID == policy.AncillaryOrderItemID && existing.TravelerRef == policy.TravelerRef && existing.SegmentScopeHash() == policy.SegmentScopeHash() && existing.ProductVersion == policy.ProductVersion && !terminalPolicy(existing.Status) {
			return existing, true, nil
		}
	}
	return domain.Policy{}, false, nil
}

func (s *InsuranceService) findActiveClaim(ctx context.Context, policyID string, claimType domain.ClaimType, trigger string) (domain.Claim, bool, error) {
	if s.repository != nil {
		c, err := s.repository.FindActiveClaim(ctx, policyID, claimType, strings.TrimSpace(trigger))
		if err == nil {
			return c, true, nil
		}
		if err != ErrNotFound {
			return domain.Claim{}, false, err
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, c := range s.claims {
		if c.PolicyID == policyID && c.ClaimType == claimType && c.TriggerFactKey == strings.TrimSpace(trigger) && !terminalClaim(c.Status) {
			return c, true, nil
		}
	}
	return domain.Claim{}, false, nil
}

func (s *InsuranceService) findClaim(ctx context.Context, id string) (domain.Claim, error) {
	if s.repository != nil {
		return s.repository.FindClaim(ctx, id)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	claim, ok := s.claims[id]
	if !ok {
		return domain.Claim{}, ErrNotFound
	}
	return claim, nil
}

func (s *InsuranceService) newEnvelope(ctx context.Context, eventType, correlationID, causationID string, payload any) (kitmsg.EventEnvelope, error) {
	env, err := kitmsg.NewEventEnvelope(eventType, domain.ServiceID, correlationID, payload, kitmsg.EnvelopeOptions{Now: s.clock.Now(), CausationID: causationID, Context: ctx})
	if err != nil {
		return kitmsg.EventEnvelope{}, err
	}
	return env, nil
}

func (s *InsuranceService) within(ctx context.Context, fn func(context.Context) error) error {
	if s.uow == nil {
		return fn(ctx)
	}
	return s.uow(ctx, fn)
}

func validateIssuePolicy(cmd IssuePolicyCommand) error {
	required := map[string]string{"productCode": cmd.ProductCode, "journeyOrderId": cmd.JourneyOrderID, "ancillaryOrderItemId": cmd.AncillaryOrderItemID, "accountId": cmd.AccountID, "travelerRef": cmd.TravelerRef, "paymentIntentId": cmd.PaymentIntentID}
	for name, value := range required {
		if strings.TrimSpace(value) == "" {
			return fmt.Errorf("%w: %s is required", ErrValidation, name)
		}
	}
	if strings.TrimSpace(cmd.ProductVersion) == "" {
		cmd.ProductVersion = "v1"
	}
	if len(cmd.SegmentRefs) == 0 {
		return fmt.Errorf("%w: segmentRefs are required", ErrValidation)
	}
	if cmd.CoverageStartAt.IsZero() || cmd.CoverageEndAt.IsZero() || !cmd.CoverageEndAt.After(cmd.CoverageStartAt) {
		return fmt.Errorf("%w: coverage window is invalid", ErrValidation)
	}
	return nil
}

func validateFileClaim(cmd FileClaimCommand) error {
	if strings.TrimSpace(cmd.PolicyID) == "" || strings.TrimSpace(cmd.ClaimType) == "" || strings.TrimSpace(cmd.TriggerFactKey) == "" {
		return fmt.Errorf("%w: policyId, claimType, and triggerFactKey are required", ErrValidation)
	}
	if err := cmd.ClaimedAmount.ValidatePositive(); err != nil {
		return fmt.Errorf("%w: invalid claimedAmount: %v", ErrValidation, err)
	}
	return nil
}

func productKey(code domain.ProductCode, version string) string {
	if strings.TrimSpace(version) == "" {
		version = "v1"
	}
	return string(code) + ":" + strings.TrimSpace(version)
}
func terminalPolicy(status domain.PolicyStatus) bool {
	return status == domain.PolicySurrendered || status == domain.PolicyUnderwritingFailed || status == domain.PolicyExpired || status == domain.PolicyClosed
}
func terminalClaim(status domain.ClaimStatus) bool {
	return status == domain.ClaimRejected || status == domain.ClaimFailed || status == domain.ClaimClosed
}
func mapRepoErr(err error) error {
	if err == nil {
		return nil
	}
	return err
}

func EncodeEnvelope(env kitmsg.EventEnvelope) ([]byte, error) { return json.Marshal(env) }
