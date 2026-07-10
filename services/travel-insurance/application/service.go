package application

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
	"github.com/trainticket/greenfield/platform/go-kit/messaging"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

const producer = "travel-insurance"

type Clock func() time.Time

type InsuranceService struct {
	repo        Repository
	publisher   Publisher
	underwriter Underwriter
	clock       Clock
	uow         UnitOfWork
}

func NewInsuranceService(repo Repository, publisher Publisher, underwriter Underwriter, clock Clock) *InsuranceService {
	if clock == nil {
		clock = func() time.Time { return time.Now().UTC() }
	}
	if underwriter == nil {
		underwriter = DeterministicUnderwriter{}
	}
	return &InsuranceService{repo: repo, publisher: publisher, underwriter: underwriter, clock: clock}
}

func (s *InsuranceService) WithUnitOfWork(uow UnitOfWork) *InsuranceService { s.uow = uow; return s }

func (s *InsuranceService) EnsureDefaultCatalog(ctx context.Context) error {
	products, err := s.repo.ListProducts(ctx)
	if err != nil || len(products) > 0 {
		return err
	}
	for _, product := range domain.DefaultCatalog(s.clock()) {
		if err := s.repo.SaveProduct(ctx, product); err != nil {
			return err
		}
	}
	return nil
}

type IssuePolicyCommand struct {
	ProductCode          domain.ProductCode
	ProductVersion       int
	JourneyOrderID       string
	AncillaryOrderItemID string
	AccountID            string
	TravelerRef          string
	SegmentRefs          []string
	PaymentIntentID      string
	CoverageStartAt      time.Time
	CoverageEndAt        time.Time
}

type ClaimCommand struct {
	PolicyID       string
	ClaimType      domain.ClaimType
	TriggerFactKey string
	SupportCaseID  string
	EvidenceRefs   []string
	ClaimedAmount  domain.Money
}

type SettleClaimCommand struct {
	ClaimID      string
	Amount       domain.Money
	PayoutTarget domain.PayoutTarget
	ReasonCode   string
}

func (s *InsuranceService) Issue(ctx context.Context, cmd IssuePolicyCommand) (domain.Policy, error) {
	var result domain.Policy
	err := s.within(ctx, func(txCtx context.Context) error {
		if err := s.EnsureDefaultCatalog(txCtx); err != nil {
			return err
		}
		version := cmd.ProductVersion
		if version == 0 {
			version = 1
		}
		product, ok, err := s.repo.FindProduct(txCtx, cmd.ProductCode, version)
		if err != nil {
			return err
		}
		if !ok {
			return domain.ErrNotFound
		}
		policy, err := domain.NewPolicy(domain.IssuePolicySpec{Product: product, JourneyOrderID: cmd.JourneyOrderID, AncillaryOrderItemID: cmd.AncillaryOrderItemID, AccountID: cmd.AccountID, TravelerRef: cmd.TravelerRef, SegmentRefs: cmd.SegmentRefs, PaymentIntentID: cmd.PaymentIntentID, CoverageStartAt: cmd.CoverageStartAt, CoverageEndAt: cmd.CoverageEndAt}, s.clock())
		if err != nil {
			return err
		}
		if existing, ok, err := s.repo.FindPolicyBySelection(txCtx, policy); err != nil {
			return err
		} else if ok {
			result = existing
			return nil
		}
		if err := policy.RequestUnderwriting(s.clock()); err != nil {
			return err
		}
		policyNumber, err := s.underwriter.IssuePolicy(txCtx, policy)
		if err != nil {
			return err
		}
		if err := policy.ApplyUnderwritingSucceeded(policyNumber, s.clock()); err != nil {
			return err
		}
		if err := s.repo.SavePolicy(txCtx, policy); err != nil {
			return err
		}
		if err := s.publishPolicyIssued(txCtx, policy); err != nil {
			return err
		}
		result = policy
		return nil
	})
	return result, err
}

func (s *InsuranceService) GetPolicy(ctx context.Context, id string) (domain.Policy, bool, error) {
	return s.repo.GetPolicy(ctx, strings.TrimSpace(id))
}

func (s *InsuranceService) FileClaim(ctx context.Context, cmd ClaimCommand) (domain.Claim, error) {
	var result domain.Claim
	err := s.within(ctx, func(txCtx context.Context) error {
		policy, ok, err := s.repo.GetPolicy(txCtx, strings.TrimSpace(cmd.PolicyID))
		if err != nil {
			return err
		}
		if !ok {
			return domain.ErrNotFound
		}
		if !policy.CanFileClaim(s.clock()) {
			return fmt.Errorf("%w: policy is not claimable", domain.ErrRuleViolation)
		}
		if existing, ok, err := s.repo.FindActiveClaim(txCtx, policy.ID, cmd.ClaimType, strings.TrimSpace(cmd.TriggerFactKey)); err != nil {
			return err
		} else if ok {
			return fmt.Errorf("%w: %s", domain.ErrDuplicateActiveClaim, existing.ID)
		}
		claim, err := domain.NewClaim(domain.ClaimSpec{Policy: policy, ClaimType: cmd.ClaimType, TriggerFactKey: cmd.TriggerFactKey, SupportCaseID: cmd.SupportCaseID, EvidenceRefs: cmd.EvidenceRefs, ClaimedAmount: cmd.ClaimedAmount}, s.clock())
		if err != nil {
			return err
		}
		if err := s.repo.SaveClaim(txCtx, claim); err != nil {
			return err
		}
		if err := s.publishClaimFiled(txCtx, claim); err != nil {
			return err
		}
		result = claim
		return nil
	})
	return result, err
}

func (s *InsuranceService) Settle(ctx context.Context, cmd SettleClaimCommand) (domain.PayoutAdvice, error) {
	var result domain.PayoutAdvice
	err := s.within(ctx, func(txCtx context.Context) error {
		claim, ok, err := s.repo.GetClaim(txCtx, strings.TrimSpace(cmd.ClaimID))
		if err != nil {
			return err
		}
		if !ok {
			return domain.ErrNotFound
		}
		if claim.Status != domain.ClaimApproved {
			if err := claim.Approve(cmd.Amount, s.clock()); err != nil {
				return err
			}
		}
		advice, err := domain.NewPayoutAdvice(claim, cmd.PayoutTarget, cmd.ReasonCode, s.clock())
		if err != nil {
			return err
		}
		if err := claim.RecommendPayout(advice.ID, s.clock()); err != nil {
			return err
		}
		if err := s.repo.SavePayoutAdvice(txCtx, advice); err != nil {
			return err
		}
		if err := s.repo.SaveClaim(txCtx, claim); err != nil {
			return err
		}
		if err := s.publishClaimSettled(txCtx, claim, advice); err != nil {
			return err
		}
		result = advice
		return nil
	})
	return result, err
}

func (s *InsuranceService) HandleEvent(ctx context.Context, envelope messaging.EventEnvelope) error {
	switch envelope.EventType {
	case "JourneyOrderConfirmed":
		var payload JourneyOrderConfirmedPayload
		if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
			return messaging.FatalHandlerError(err)
		}
		return s.CreateOfferForConfirmedOrder(ctx, payload)
	case "PostSalesApproved", "RefundApproved":
		return s.HandleRefundTrigger(ctx, envelope)
	default:
		return nil
	}
}

type RefundApprovedPayload struct {
	PolicyID       string       `json:"policyId"`
	ClaimType      string       `json:"claimType"`
	RefundID       string       `json:"refundId"`
	CaseID         string       `json:"caseId"`
	EvidenceRefs   []string     `json:"evidenceRefs"`
	ApprovedAmount domain.Money `json:"approvedAmount"`
}

func (s *InsuranceService) HandleRefundTrigger(ctx context.Context, envelope messaging.EventEnvelope) error {
	var payload RefundApprovedPayload
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return messaging.FatalHandlerError(err)
	}
	if strings.TrimSpace(payload.PolicyID) == "" {
		return nil
	}
	claimType := domain.ClaimServiceFailureManual
	if strings.TrimSpace(payload.ClaimType) != "" {
		claimType = domain.ClaimType(payload.ClaimType)
	}
	factKey := strings.TrimSpace(payload.RefundID)
	if factKey == "" {
		factKey = envelope.EventID
	}
	evidenceRefs := append([]string(nil), payload.EvidenceRefs...)
	if len(evidenceRefs) == 0 {
		evidenceRefs = []string{envelope.EventID}
	}
	_, err := s.FileClaim(ctx, ClaimCommand{PolicyID: payload.PolicyID, ClaimType: claimType, TriggerFactKey: factKey, SupportCaseID: payload.CaseID, EvidenceRefs: evidenceRefs, ClaimedAmount: payload.ApprovedAmount})
	if errors.Is(err, domain.ErrDuplicateActiveClaim) {
		return nil
	}
	return err
}

type JourneyOrderConfirmedPayload struct {
	OrderID         string          `json:"orderId"`
	AccountID       string          `json:"accountId"`
	MonetarySummary json.RawMessage `json:"monetarySummary"`
	ConfirmedAt     time.Time       `json:"confirmedAt"`
}

func (s *InsuranceService) CreateOfferForConfirmedOrder(ctx context.Context, payload JourneyOrderConfirmedPayload) error {
	if strings.TrimSpace(payload.OrderID) == "" || strings.TrimSpace(payload.AccountID) == "" {
		return domain.ErrInvalidArgument
	}
	return s.within(ctx, func(txCtx context.Context) error {
		if err := s.EnsureDefaultCatalog(txCtx); err != nil {
			return err
		}
		product, ok, err := s.repo.FindProduct(txCtx, domain.ProductDelayInsurance, 1)
		if err != nil || !ok {
			if err != nil {
				return err
			}
			return domain.ErrNotFound
		}
		now := s.clock()
		offerID := deterministicOfferID(payload.OrderID, string(product.ProductCode), product.Version)
		return s.repo.SaveOffer(txCtx, domain.InsuranceOffer{OfferID: offerID, JourneyOrderID: strings.TrimSpace(payload.OrderID), AccountID: strings.TrimSpace(payload.AccountID), ProductCode: product.ProductCode, ProductVersion: product.Version, Premium: product.Premium, CoverageLimit: product.CoverageLimit, OfferedAt: now, ExpiresAt: now.Add(24 * time.Hour)})
	})
}

func (s *InsuranceService) publishPolicyIssued(ctx context.Context, p domain.Policy) error {
	payload := domain.PolicyIssuedPayload{PolicyID: p.ID, PolicyNumber: p.PolicyNumber, ProductCode: p.ProductCode, ProductVersion: p.ProductVersion, JourneyOrderID: p.JourneyOrderID, AncillaryOrderItemID: p.AncillaryOrderItemID, AccountID: p.AccountID, TravelerRef: p.TravelerRef, SegmentRefs: p.SegmentRefs, Premium: p.Premium, CoverageLimit: p.CoverageLimit, CoverageStartAt: p.CoverageStartAt, CoverageEndAt: p.CoverageEndAt, IssuedAt: p.UpdatedAt}
	return s.publish(ctx, domain.EventPolicyIssued, p.JourneyOrderID, payload)
}

func (s *InsuranceService) publishClaimFiled(ctx context.Context, c domain.Claim) error {
	payload := domain.ClaimFiledPayload{ClaimID: c.ID, PolicyID: c.PolicyID, ClaimType: c.ClaimType, TriggerFactKey: c.TriggerFactKey, ClaimedAmount: c.ClaimedAmount, EvidenceRefs: c.EvidenceRefs, FiledAt: c.CreatedAt}
	return s.publish(ctx, domain.EventClaimFiled, c.PolicyID, payload)
}

func (s *InsuranceService) publishClaimSettled(ctx context.Context, c domain.Claim, a domain.PayoutAdvice) error {
	payload := domain.ClaimSettledPayload{ClaimID: c.ID, PolicyID: c.PolicyID, PayoutAdviceID: a.ID, PayoutTarget: a.PayoutTarget, Amount: a.Amount, ReasonCode: a.ReasonCode, SettledAt: a.CreatedAt}
	return s.publish(ctx, domain.EventClaimSettled, c.PolicyID, payload)
}

func (s *InsuranceService) publish(ctx context.Context, eventType, correlationMaterial string, payload any) error {
	if s.publisher == nil {
		return nil
	}
	correlationID := goruntime.CorrelationID(ctx)
	if correlationID == "" {
		correlationID = ids.CanonicalCorrelationID(correlationMaterial)
	}
	envelope, err := messaging.NewEventEnvelope(eventType, producer, correlationID, payload, messaging.EnvelopeOptions{Context: ctx, Now: s.clock()})
	if err != nil {
		return err
	}
	return s.publisher.Publish(ctx, envelope)
}

func (s *InsuranceService) within(ctx context.Context, fn func(context.Context) error) error {
	if s.uow == nil {
		return fn(ctx)
	}
	return s.uow(ctx, fn)
}

type DeterministicUnderwriter struct{}

func (DeterministicUnderwriter) IssuePolicy(_ context.Context, p domain.Policy) (string, error) {
	if strings.TrimSpace(p.ID) == "" {
		return "", domain.ErrInvalidArgument
	}
	sum := sha256.Sum256([]byte(p.ID + "|" + p.UnderwritingSeedRef))
	return "SIM-TI-" + strings.ToUpper(hex.EncodeToString(sum[:6])), nil
}

func deterministicOfferID(orderID, productCode string, version int) string {
	sum := sha256.Sum256([]byte(strings.Join([]string{orderID, productCode, fmt.Sprint(version)}, "|")))
	return "iof-" + hex.EncodeToString(sum[:16])
}

func ErrorKind(err error) string {
	switch {
	case errors.Is(err, domain.ErrNotFound):
		return "not_found"
	case errors.Is(err, domain.ErrInvalidArgument):
		return "validation"
	case errors.Is(err, domain.ErrConflict), errors.Is(err, domain.ErrDuplicateActiveClaim):
		return "conflict"
	case errors.Is(err, domain.ErrInvalidTransition), errors.Is(err, domain.ErrRuleViolation):
		return "rule"
	default:
		return "internal"
	}
}
