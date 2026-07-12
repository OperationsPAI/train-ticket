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
	premiumDelay, _ := domain.NewMoney("CNY", 300)
	limitDelay, _ := domain.NewMoney("CNY", 3000)
	premiumCancellation, _ := domain.NewMoney("CNY", 1)
	limitCancellation, _ := domain.NewMoney("CNY", 1)
	premiumAccident, _ := domain.NewMoney("CNY", 500)
	limitAccident, _ := domain.NewMoney("CNY", 50000000)
	premiumBaggage, _ := domain.NewMoney("CNY", 200)
	limitBaggage, _ := domain.NewMoney("CNY", 200000)
	for _, p := range []domain.InsuranceProduct{
		mustPublishedProduct("ip-delay-v1", domain.ProductDelayInsurance, "v1", premiumDelay, limitDelay, start, end, now),
		mustPublishedProduct("ip-cancellation-v1", domain.ProductCancellationInsurance, "v1", premiumCancellation, limitCancellation, start, end, now),
		mustPublishedProduct("ip-accident-v1", domain.ProductAccidentInsurance, "v1", premiumAccident, limitAccident, start, end, now),
		mustPublishedProduct("ip-baggage-v1", domain.ProductBaggageInsurance, "v1", premiumBaggage, limitBaggage, start, end, now),
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
	TicketPrice          domain.Money
	RouteDistance        int
	CorrelationID        string
	CausationID          string
}

type FileClaimCommand struct {
	PolicyID       string
	ClaimType      string
	TriggerFactKey string
	Description    string
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

type RejectClaimCommand struct {
	ClaimID       string
	ReasonCode    string
	CorrelationID string
	CausationID   string
}

type TrainDelayedCommand struct {
	SegmentRef    string
	ServiceDate   string
	DelayMinutes  int
	OccurredAt    time.Time
	SourceEventID string
	CorrelationID string
	CausationID   string
}

type RefundAppliedCommand struct {
	PolicyID       string
	JourneyOrderID string
	RefundID       string
	CorrelationID  string
	CausationID    string
}

func (s *InsuranceService) IssuePolicy(ctx context.Context, cmd IssuePolicyCommand) (domain.Policy, error) {
	if err := validateIssuePolicy(cmd); err != nil {
		return domain.Policy{}, err
	}
	product, err := s.findProduct(ctx, domain.ProductCode(strings.TrimSpace(cmd.ProductCode)), strings.TrimSpace(cmd.ProductVersion))
	if err != nil {
		return domain.Policy{}, err
	}
	premium := product.Premium
	if product.ProductCode == domain.ProductCancellationInsurance {
		premium, err = (domain.PremiumCalculator{}).CalculatePremium(product, cmd.TicketPrice, cmd.RouteDistance)
		if err != nil {
			return domain.Policy{}, fmt.Errorf("%w: %v", ErrDomainRule, err)
		}
	}
	product.Premium = premium
	if product.ProductCode == domain.ProductCancellationInsurance {
		limitMinor := cmd.TicketPrice.MinorUnits * 80 / 100
		if limitMinor <= 0 {
			limitMinor = 1
		}
		product.CoverageLimit, _ = domain.NewMoney(cmd.TicketPrice.Currency, limitMinor)
		product.MaxPayout = product.CoverageLimit
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
	envelope, err := s.newEnvelope(ctx, "InsurancePolicyIssued", cmd.CorrelationID, cmd.CausationID, policyIssuedPayload(policy))
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
	claim.Description = strings.TrimSpace(cmd.Description)
	if err := policy.MarkClaimed(s.clock.Now()); err != nil {
		return domain.Claim{}, fmt.Errorf("%w: %v", ErrDomainRule, err)
	}
	envelopes := []kitmsg.EventEnvelope{}
	created, err := s.newEnvelope(ctx, "InsuranceClaimCreated", cmd.CorrelationID, cmd.CausationID, claimPayload(claim))
	if err != nil {
		return domain.Claim{}, err
	}
	envelopes = append(envelopes, created)
	if claim.Status == domain.ClaimApproved {
		approved, err := s.newEnvelope(ctx, "InsuranceClaimApproved", cmd.CorrelationID, claim.ID, claimPayload(claim))
		if err != nil {
			return domain.Claim{}, err
		}
		envelopes = append(envelopes, approved)
	}
	if err := s.savePolicyClaimAndPublish(ctx, policy, claim, domain.PayoutAdvice{}, envelopes); err != nil {
		return domain.Claim{}, err
	}
	return claim, nil
}

func (s *InsuranceService) GetClaim(ctx context.Context, id string) (domain.Claim, error) {
	id = strings.TrimSpace(id)
	if id == "" {
		return domain.Claim{}, fmt.Errorf("%w: claim id is required", ErrValidation)
	}
	return s.findClaim(ctx, id)
}

func (s *InsuranceService) ApproveClaim(ctx context.Context, cmd SettleClaimCommand) (domain.PayoutAdvice, error) {
	claim, policy, err := s.claimAndPolicy(ctx, cmd.ClaimID)
	if err != nil {
		return domain.PayoutAdvice{}, err
	}
	if claim.Status == domain.ClaimPending {
		if err := claim.Approve(claim.ClaimedAmount); err != nil {
			return domain.PayoutAdvice{}, fmt.Errorf("%w: %v", ErrDomainRule, err)
		}
	}
	advice, err := claim.Settle(s.idGenerator("pad"), payoutTargetOrDefault(cmd.PayoutTarget), strings.TrimSpace(cmd.ReasonCode))
	if err != nil {
		return domain.PayoutAdvice{}, fmt.Errorf("%w: %v", ErrDomainRule, err)
	}
	if err := policy.MarkPaidOut(); err != nil {
		return domain.PayoutAdvice{}, fmt.Errorf("%w: %v", ErrDomainRule, err)
	}
	approved, err := s.newEnvelope(ctx, "InsuranceClaimApproved", cmd.CorrelationID, cmd.CausationID, claimPayload(claim))
	if err != nil {
		return domain.PayoutAdvice{}, err
	}
	paid, err := s.newEnvelope(ctx, "InsurancePayoutCompleted", cmd.CorrelationID, claim.ID, payoutPayload(claim, advice))
	if err != nil {
		return domain.PayoutAdvice{}, err
	}
	if err := s.savePolicyClaimAndPublish(ctx, policy, claim, advice, []kitmsg.EventEnvelope{approved, paid}); err != nil {
		return domain.PayoutAdvice{}, err
	}
	return advice, nil
}

func (s *InsuranceService) SettleClaim(ctx context.Context, cmd SettleClaimCommand) (domain.PayoutAdvice, error) {
	return s.ApproveClaim(ctx, cmd)
}

func (s *InsuranceService) RejectClaim(ctx context.Context, cmd RejectClaimCommand) (domain.Claim, error) {
	claim, policy, err := s.claimAndPolicy(ctx, cmd.ClaimID)
	if err != nil {
		return domain.Claim{}, err
	}
	if err := claim.Reject(cmd.ReasonCode); err != nil {
		return domain.Claim{}, fmt.Errorf("%w: %v", ErrDomainRule, err)
	}
	if err := policy.MarkRejected(); err != nil {
		return domain.Claim{}, fmt.Errorf("%w: %v", ErrDomainRule, err)
	}
	env, err := s.newEnvelope(ctx, "InsuranceClaimRejected", cmd.CorrelationID, cmd.CausationID, claimPayload(claim))
	if err != nil {
		return domain.Claim{}, err
	}
	if err := s.savePolicyClaimAndPublish(ctx, policy, claim, domain.PayoutAdvice{}, []kitmsg.EventEnvelope{env}); err != nil {
		return domain.Claim{}, err
	}
	return claim, nil
}

func (s *InsuranceService) ProcessTrainDelayed(ctx context.Context, cmd TrainDelayedCommand) ([]domain.PayoutAdvice, error) {
	if strings.TrimSpace(cmd.SegmentRef) == "" || cmd.DelayMinutes <= 60 {
		return nil, nil
	}
	policies, err := s.findPoliciesForDelay(ctx, strings.TrimSpace(cmd.SegmentRef), cmd.OccurredAt)
	if err != nil {
		return nil, err
	}
	advices := []domain.PayoutAdvice{}
	for _, policy := range policies {
		amount, _ := domain.NewMoney(policy.CoverageLimit.Currency, 3000)
		factKey := firstNonBlank(cmd.SourceEventID, fmt.Sprintf("delay:%s:%s:%d", cmd.SegmentRef, cmd.ServiceDate, cmd.DelayMinutes))
		claim, err := s.FileClaim(ctx, FileClaimCommand{PolicyID: policy.ID, ClaimType: string(domain.ClaimDelayAuto), TriggerFactKey: factKey, DelayFact: &domain.DelayFact{SegmentRef: cmd.SegmentRef, ServiceDate: cmd.ServiceDate, SourceEventType: "TrainDelayed", SourceEventID: factKey, DelayMinutes: cmd.DelayMinutes, OccurredAt: cmd.OccurredAt}, ClaimedAmount: amount, CorrelationID: cmd.CorrelationID, CausationID: firstNonBlank(cmd.CausationID, cmd.SourceEventID)})
		if err != nil {
			return advices, err
		}
		advice, err := s.ApproveClaim(ctx, SettleClaimCommand{ClaimID: claim.ID, PayoutTarget: string(domain.PayoutPaymentRefund), ReasonCode: "TRAIN_DELAY_GT_60", CorrelationID: cmd.CorrelationID, CausationID: claim.ID})
		if err != nil {
			return advices, err
		}
		advices = append(advices, advice)
	}
	return advices, nil
}

func (s *InsuranceService) CancelPolicyForRefund(ctx context.Context, cmd RefundAppliedCommand) (domain.Policy, error) {
	policy, err := s.findPolicyForRefund(ctx, cmd.PolicyID, cmd.JourneyOrderID)
	if err != nil {
		return domain.Policy{}, err
	}
	refund, err := policy.CancelForRefund()
	if err != nil {
		return domain.Policy{}, fmt.Errorf("%w: %v", ErrDomainRule, err)
	}
	env, err := s.newEnvelope(ctx, "InsurancePolicyCancelled", cmd.CorrelationID, cmd.CausationID, map[string]any{"policyId": policy.ID, "journeyOrderId": policy.JourneyOrderID, "refundId": cmd.RefundID, "refundedPremium": refund, "status": policy.Status})
	if err != nil {
		return domain.Policy{}, err
	}
	if err := s.within(ctx, func(tx context.Context) error {
		if s.repository != nil {
			if err := s.repository.SavePolicy(tx, policy); err != nil {
				return mapRepoErr(err)
			}
		}
		return s.publisher.Publish(tx, env)
	}); err != nil {
		return domain.Policy{}, err
	}
	s.mu.Lock()
	s.policies[policy.ID] = policy
	s.mu.Unlock()
	return policy, nil
}

func (s *InsuranceService) savePolicyClaimAndPublish(ctx context.Context, policy domain.Policy, claim domain.Claim, advice domain.PayoutAdvice, envelopes []kitmsg.EventEnvelope) error {
	if err := s.within(ctx, func(tx context.Context) error {
		if s.repository != nil {
			if err := s.repository.SavePolicy(tx, policy); err != nil {
				return mapRepoErr(err)
			}
			if err := s.repository.SaveClaim(tx, claim); err != nil {
				return mapRepoErr(err)
			}
			if advice.ID != "" {
				if err := s.repository.SavePayoutAdvice(tx, advice); err != nil {
					return mapRepoErr(err)
				}
			}
		}
		for _, envelope := range envelopes {
			if err := s.publisher.Publish(tx, envelope); err != nil {
				return fmt.Errorf("%w: %v", ErrPublish, err)
			}
		}
		return nil
	}); err != nil {
		return err
	}
	s.mu.Lock()
	s.policies[policy.ID] = policy
	s.claims[claim.ID] = claim
	if advice.ID != "" {
		s.advices[advice.ID] = advice
	}
	s.mu.Unlock()
	return nil
}

func (s *InsuranceService) claimAndPolicy(ctx context.Context, claimID string) (domain.Claim, domain.Policy, error) {
	claim, err := s.findClaim(ctx, strings.TrimSpace(claimID))
	if err != nil {
		return domain.Claim{}, domain.Policy{}, err
	}
	policy, err := s.GetPolicy(ctx, claim.PolicyID)
	if err != nil {
		return domain.Claim{}, domain.Policy{}, err
	}
	return claim, policy, nil
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
		p, err := s.repository.FindPolicyByUniqueness(ctx, policy.ProductCode, policy.AncillaryOrderItemID, policy.TravelerRef, policy.SegmentScopeHash(), policy.ProductVersion)
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
		if existing.AncillaryOrderItemID == policy.AncillaryOrderItemID && existing.TravelerRef == policy.TravelerRef && existing.SegmentScopeHash() == policy.SegmentScopeHash() && existing.ProductVersion == policy.ProductVersion && existing.ProductCode == policy.ProductCode && !terminalPolicy(existing.Status) {
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

func (s *InsuranceService) findPoliciesForDelay(ctx context.Context, segmentRef string, occurredAt time.Time) ([]domain.Policy, error) {
	if s.repository != nil {
		return s.repository.FindIssuedPoliciesForSegment(ctx, domain.ProductDelayInsurance, segmentRef, occurredAt)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	policies := []domain.Policy{}
	for _, policy := range s.policies {
		if policy.ProductCode != domain.ProductDelayInsurance || !policy.CanClaim(occurredAt) {
			continue
		}
		for _, ref := range policy.SegmentRefs {
			if ref == segmentRef {
				policies = append(policies, policy)
				break
			}
		}
	}
	return policies, nil
}

func (s *InsuranceService) findPolicyForRefund(ctx context.Context, policyID, journeyOrderID string) (domain.Policy, error) {
	if strings.TrimSpace(policyID) != "" {
		return s.GetPolicy(ctx, policyID)
	}
	if strings.TrimSpace(journeyOrderID) == "" {
		return domain.Policy{}, fmt.Errorf("%w: policyId or journeyOrderId is required", ErrValidation)
	}
	if s.repository != nil {
		return s.repository.FindIssuedPolicyByJourneyOrder(ctx, strings.TrimSpace(journeyOrderID))
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, policy := range s.policies {
		if policy.JourneyOrderID == strings.TrimSpace(journeyOrderID) && policy.Status == domain.PolicyIssued {
			return policy, nil
		}
	}
	return domain.Policy{}, ErrNotFound
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
	if !domain.SupportedProductCode(domain.ProductCode(strings.TrimSpace(cmd.ProductCode))) {
		return fmt.Errorf("%w: unsupported productCode", ErrValidation)
	}
	if len(cmd.SegmentRefs) == 0 {
		return fmt.Errorf("%w: segmentRefs are required", ErrValidation)
	}
	if cmd.CoverageStartAt.IsZero() || cmd.CoverageEndAt.IsZero() || !cmd.CoverageEndAt.After(cmd.CoverageStartAt) {
		return fmt.Errorf("%w: coverage window is invalid", ErrValidation)
	}
	if domain.ProductCode(strings.TrimSpace(cmd.ProductCode)) == domain.ProductCancellationInsurance {
		if err := cmd.TicketPrice.ValidatePositive(); err != nil {
			return fmt.Errorf("%w: ticketPrice is required for cancellation insurance", ErrValidation)
		}
	}
	return nil
}

func validateFileClaim(cmd FileClaimCommand) error {
	if strings.TrimSpace(cmd.PolicyID) == "" || strings.TrimSpace(cmd.ClaimType) == "" || strings.TrimSpace(cmd.TriggerFactKey) == "" {
		return fmt.Errorf("%w: policyId, claimType, and triggerFactKey are required", ErrValidation)
	}
	if !domain.SupportedClaimType(domain.ClaimType(strings.TrimSpace(cmd.ClaimType))) {
		return fmt.Errorf("%w: unsupported claimType", ErrValidation)
	}
	if err := cmd.ClaimedAmount.ValidatePositive(); err != nil {
		return fmt.Errorf("%w: invalid claimedAmount: %v", ErrValidation, err)
	}
	return nil
}

func payoutTargetOrDefault(target string) domain.PayoutTarget {
	if strings.TrimSpace(target) == "" {
		return domain.PayoutPaymentRefund
	}
	return domain.PayoutTarget(strings.TrimSpace(target))
}

func policyIssuedPayload(policy domain.Policy) map[string]any {
	return map[string]any{"policyId": policy.ID, "policyNumber": policy.PolicyNumber, "productCode": policy.ProductCode, "productType": policy.ProductCode, "productVersion": policy.ProductVersion, "journeyOrderId": policy.JourneyOrderID, "orderId": policy.JourneyOrderID, "ancillaryOrderItemId": policy.AncillaryOrderItemID, "accountId": policy.AccountID, "travelerRef": policy.TravelerRef, "segmentRefs": policy.SegmentRefs, "premium": policy.Premium, "premiumPaid": policy.Premium, "coverageLimit": policy.CoverageLimit, "coverageStartAt": policy.CoverageStartAt, "coverageEndAt": policy.CoverageEndAt, "status": policy.Status}
}

func claimPayload(claim domain.Claim) map[string]any {
	payload := map[string]any{"claimId": claim.ID, "policyId": claim.PolicyID, "claimType": claim.ClaimType, "triggerFactKey": claim.TriggerFactKey, "description": claim.Description, "status": claim.Status, "amount": claim.ClaimedAmount, "claimedAmount": claim.ClaimedAmount, "approvedAmount": claim.ApprovedAmount, "supportCaseId": claim.SupportCaseID, "evidenceRefs": claim.EvidenceRefs}
	if claim.DelayFact != nil {
		payload["delayFact"] = claim.DelayFact
	}
	return payload
}

func payoutPayload(claim domain.Claim, advice domain.PayoutAdvice) map[string]any {
	return map[string]any{"claimId": claim.ID, "policyId": claim.PolicyID, "payoutAdviceId": advice.ID, "payoutTarget": advice.PayoutTarget, "amount": advice.Amount, "reasonCode": advice.ReasonCode, "status": advice.Status, "idempotencyKey": advice.IdempotencyKey}
}

func productKey(code domain.ProductCode, version string) string {
	if strings.TrimSpace(version) == "" {
		version = "v1"
	}
	return string(code) + ":" + strings.TrimSpace(version)
}
func terminalPolicy(status domain.PolicyStatus) bool {
	return status == domain.PolicyCancelled || status == domain.PolicySurrendered || status == domain.PolicyUnderwritingFailed || status == domain.PolicyExpired || status == domain.PolicyClosed || status == domain.PolicyPaidOut || status == domain.PolicyRejected
}
func terminalClaim(status domain.ClaimStatus) bool {
	return status == domain.ClaimRejected || status == domain.ClaimFailed || status == domain.ClaimClosed || status == domain.ClaimPaidOut
}
func mapRepoErr(err error) error {
	if err == nil {
		return nil
	}
	return err
}

func firstNonBlank(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}

func EncodeEnvelope(env kitmsg.EventEnvelope) ([]byte, error) { return json.Marshal(env) }
