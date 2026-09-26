package application

import (
	"context"
	"errors"
	"time"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

var (
	ErrNotFound          = errors.New("not found")
	ErrConflict          = errors.New("conflict")
	ErrValidation        = errors.New("validation failed")
	ErrDomainRule        = errors.New("domain rule violation")
	// ErrNotChangeAdjustment says a journey adjustment belongs to a case type this
	// service does not adjust: a refund or a cancellation ends the coverage the order
	// carried, and that is told to this service by PostSalesApplied, RefundApproved and
	// AncillaryOrderItemRefunded. It is not a failure, which is the whole point of
	// naming it -- see ResolveJourneyCoverage.
	ErrNotChangeAdjustment = errors.New("not a change adjustment")
	ErrPublish           = errors.New("publish failed")
	ErrPayoutUnavailable = errors.New("payout unavailable")
)

type Repository interface {
	SaveProduct(context.Context, domain.InsuranceProduct) error
	FindPublishedProduct(context.Context, domain.ProductCode, string) (domain.InsuranceProduct, error)
	SavePolicy(context.Context, domain.Policy) error
	FindPolicy(context.Context, string) (domain.Policy, error)
	FindPolicyByUniqueness(context.Context, domain.ProductCode, string, string, string, string) (domain.Policy, error)
	FindIssuedPoliciesForSegment(context.Context, domain.ProductCode, string, time.Time) ([]domain.Policy, error)
	FindIssuedPolicyByJourneyOrder(context.Context, string) (domain.Policy, error)
	FindIssuedPoliciesByJourneyOrder(context.Context, string) ([]domain.Policy, error)
	FindPoliciesByAncillaryItem(context.Context, string) ([]domain.Policy, error)
	SaveClaim(context.Context, domain.Claim) error
	FindClaim(context.Context, string) (domain.Claim, error)
	LockClaim(context.Context, string) error
	FindActiveClaim(context.Context, string, domain.ClaimType, string) (domain.Claim, error)
	SavePayoutAdvice(context.Context, domain.PayoutAdvice) error
	FindPayoutAdvice(context.Context, string) (domain.PayoutAdvice, error)
	FindPayoutAdviceByClaim(context.Context, string) (domain.PayoutAdvice, error)
}

type PayoutReceipt struct {
	ExecutionRef string
	AccountID    string
	Amount       domain.Money
	Target       domain.PayoutTarget
}

type PayoutExecutor interface {
	Disburse(context.Context, domain.Policy, domain.Claim, domain.PayoutAdvice, string) (PayoutReceipt, error)
}

type JourneyCoverage struct {
	OrderID             string
	AccountID           string
	Status              string
	TravelerRefs        []string
	ReplacedSegmentRefs []string
	SegmentRefs         []string
	StartsAt            time.Time
	EndsAt              time.Time
}

type JourneyCoverageResolver interface {
	ResolveJourneyCoverage(context.Context, string, string) (JourneyCoverage, error)
}

type EventPublisher interface {
	Publish(context.Context, kitmsg.EventEnvelope) error
}

type UnitOfWork func(context.Context, func(context.Context) error) error

type NoopPublisher struct{}

func (NoopPublisher) Publish(context.Context, kitmsg.EventEnvelope) error { return nil }
