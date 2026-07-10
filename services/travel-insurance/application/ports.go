package application

import (
	"context"

	"github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

type Repository interface {
	SaveProduct(context.Context, domain.InsuranceProduct) error
	ListProducts(context.Context) ([]domain.InsuranceProduct, error)
	FindProduct(context.Context, domain.ProductCode, int) (domain.InsuranceProduct, bool, error)
	SavePolicy(context.Context, domain.Policy) error
	GetPolicy(context.Context, string) (domain.Policy, bool, error)
	FindPolicyBySelection(context.Context, domain.Policy) (domain.Policy, bool, error)
	ListPoliciesByOrder(context.Context, string) ([]domain.Policy, error)
	SaveClaim(context.Context, domain.Claim) error
	GetClaim(context.Context, string) (domain.Claim, bool, error)
	FindActiveClaim(context.Context, string, domain.ClaimType, string) (domain.Claim, bool, error)
	SavePayoutAdvice(context.Context, domain.PayoutAdvice) error
	SaveOffer(context.Context, domain.InsuranceOffer) error
	ListOffersByOrder(context.Context, string) ([]domain.InsuranceOffer, error)
}

type Publisher interface {
	Publish(context.Context, messaging.EventEnvelope) error
}

type Underwriter interface {
	IssuePolicy(context.Context, domain.Policy) (string, error)
}

type UnitOfWork func(context.Context, func(context.Context) error) error
