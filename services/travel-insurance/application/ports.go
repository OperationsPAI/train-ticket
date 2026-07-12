package application

import (
	"context"
	"errors"
	"time"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

var (
	ErrNotFound   = errors.New("not found")
	ErrConflict   = errors.New("conflict")
	ErrValidation = errors.New("validation failed")
	ErrDomainRule = errors.New("domain rule violation")
	ErrPublish    = errors.New("publish failed")
)

type Repository interface {
	SaveProduct(context.Context, domain.InsuranceProduct) error
	FindPublishedProduct(context.Context, domain.ProductCode, string) (domain.InsuranceProduct, error)
	SavePolicy(context.Context, domain.Policy) error
	FindPolicy(context.Context, string) (domain.Policy, error)
	FindPolicyByUniqueness(context.Context, domain.ProductCode, string, string, string, string) (domain.Policy, error)
	FindIssuedPoliciesForSegment(context.Context, domain.ProductCode, string, time.Time) ([]domain.Policy, error)
	FindIssuedPolicyByJourneyOrder(context.Context, string) (domain.Policy, error)
	SaveClaim(context.Context, domain.Claim) error
	FindClaim(context.Context, string) (domain.Claim, error)
	FindActiveClaim(context.Context, string, domain.ClaimType, string) (domain.Claim, error)
	SavePayoutAdvice(context.Context, domain.PayoutAdvice) error
}

type EventPublisher interface {
	Publish(context.Context, kitmsg.EventEnvelope) error
}

type UnitOfWork func(context.Context, func(context.Context) error) error

type NoopPublisher struct{}

func (NoopPublisher) Publish(context.Context, kitmsg.EventEnvelope) error { return nil }
