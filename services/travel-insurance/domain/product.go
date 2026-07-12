package domain

import (
	"fmt"
	"strings"
	"time"
)

type ProductCode string

const (
	ProductDelayInsurance        ProductCode = "DELAY_INSURANCE"
	ProductCancellationInsurance ProductCode = "CANCELLATION_INSURANCE"
	ProductAccidentInsurance     ProductCode = "ACCIDENT_INSURANCE"
	ProductBaggageInsurance      ProductCode = "BAGGAGE_INSURANCE"
)

type ProductStatus string

const (
	ProductDraft      ProductStatus = "DRAFT"
	ProductPublished  ProductStatus = "PUBLISHED"
	ProductSuspended  ProductStatus = "SUSPENDED"
	ProductSuperseded ProductStatus = "SUPERSEDED"
	ProductRetired    ProductStatus = "RETIRED"
)

type PremiumCalculation string

const (
	PremiumFlat3CNY           PremiumCalculation = "FLAT_3_CNY"
	PremiumTicketPriceFivePct PremiumCalculation = "TICKET_PRICE_5_PERCENT"
	PremiumFlat5CNY           PremiumCalculation = "FLAT_5_CNY"
	PremiumFlat2CNY           PremiumCalculation = "FLAT_2_CNY"
)

type InsuranceProduct struct {
	ID                   string             `json:"insuranceProductId"`
	ProductID            string             `json:"productId"`
	ProductCode          ProductCode        `json:"productCode"`
	ProductType          ProductCode        `json:"productType"`
	Version              string             `json:"version"`
	Premium              Money              `json:"premium"`
	PremiumCalculation   PremiumCalculation `json:"premiumCalculation"`
	CoverageDescription  string             `json:"coverageDescription"`
	CoverageLimit        Money              `json:"coverageLimit"`
	MaxPayout            Money              `json:"maxPayout"`
	AutoPayoutEnabled    bool               `json:"autoPayoutEnabled"`
	CoverageRuleVersion  string             `json:"coverageRuleVersion"`
	ClaimRuleVersion     string             `json:"claimRuleVersion"`
	SurrenderRuleVersion string             `json:"surrenderRuleVersion"`
	SalesStartAt         time.Time          `json:"salesStartAt"`
	SalesEndAt           time.Time          `json:"salesEndAt"`
	Status               ProductStatus      `json:"status"`
}

func NewInsuranceProduct(id string, code ProductCode, version string, premium Money, limit Money, salesStart, salesEnd time.Time) (InsuranceProduct, error) {
	product := InsuranceProduct{ID: strings.TrimSpace(id), ProductID: strings.TrimSpace(id), ProductCode: code, ProductType: code, Version: strings.TrimSpace(version), Premium: premium, PremiumCalculation: DefaultPremiumCalculation(code), CoverageDescription: DefaultCoverageDescription(code), CoverageLimit: limit, MaxPayout: limit, AutoPayoutEnabled: code == ProductDelayInsurance, CoverageRuleVersion: "coverage-v1", ClaimRuleVersion: "claim-v1", SurrenderRuleVersion: "surrender-v1", SalesStartAt: salesStart.UTC(), SalesEndAt: salesEnd.UTC(), Status: ProductDraft}
	if err := product.Validate(); err != nil {
		return InsuranceProduct{}, err
	}
	return product, nil
}

func (p InsuranceProduct) Validate() error {
	if strings.TrimSpace(p.ID) == "" {
		return fmt.Errorf("insurance product id is required")
	}
	if p.ProductID == "" {
		p.ProductID = p.ID
	}
	if p.ProductType == "" {
		p.ProductType = p.ProductCode
	}
	if !SupportedProductCode(p.ProductCode) {
		return fmt.Errorf("unsupported insurance product code: %q", p.ProductCode)
	}
	if p.ProductType != p.ProductCode {
		return fmt.Errorf("product type must match product code")
	}
	if strings.TrimSpace(p.Version) == "" {
		return fmt.Errorf("product version is required")
	}
	if err := p.Premium.ValidatePositive(); err != nil {
		return fmt.Errorf("invalid premium: %w", err)
	}
	if err := p.CoverageLimit.ValidatePositive(); err != nil {
		return fmt.Errorf("invalid coverage limit: %w", err)
	}
	if p.MaxPayout.MinorUnits == 0 {
		p.MaxPayout = p.CoverageLimit
	}
	if err := p.MaxPayout.ValidatePositive(); err != nil {
		return fmt.Errorf("invalid max payout: %w", err)
	}
	if !p.Premium.SameCurrency(p.CoverageLimit) || !p.Premium.SameCurrency(p.MaxPayout) {
		return fmt.Errorf("premium and coverage limit currency must match")
	}
	if p.PremiumCalculation == "" {
		p.PremiumCalculation = DefaultPremiumCalculation(p.ProductCode)
	}
	if p.PremiumCalculation != DefaultPremiumCalculation(p.ProductCode) {
		return fmt.Errorf("premium calculation does not match product code")
	}
	if strings.TrimSpace(p.CoverageDescription) == "" {
		return fmt.Errorf("coverage description is required")
	}
	if !p.AutoPayoutEnabled && p.ProductCode == ProductDelayInsurance {
		return fmt.Errorf("delay insurance must have auto payout enabled")
	}
	if p.AutoPayoutEnabled && p.ProductCode != ProductDelayInsurance {
		return fmt.Errorf("only delay insurance can enable auto payout")
	}
	if p.SalesStartAt.IsZero() || p.SalesEndAt.IsZero() || p.SalesEndAt.Before(p.SalesStartAt) {
		return fmt.Errorf("sales window must be present and not inverted")
	}
	return nil
}

func (p InsuranceProduct) Publish(now time.Time) (InsuranceProduct, error) {
	if p.Status != ProductDraft && p.Status != ProductSuspended {
		return p, fmt.Errorf("product cannot be published from %s", p.Status)
	}
	if now.UTC().After(p.SalesEndAt) {
		return p, fmt.Errorf("product sales window has ended")
	}
	p.Status = ProductPublished
	return p, p.Validate()
}

func (p InsuranceProduct) AvailableAt(now time.Time) bool {
	now = now.UTC()
	return p.Status == ProductPublished && !now.Before(p.SalesStartAt) && !now.After(p.SalesEndAt)
}

func SupportedProductCode(code ProductCode) bool {
	switch code {
	case ProductDelayInsurance, ProductCancellationInsurance, ProductAccidentInsurance, ProductBaggageInsurance:
		return true
	default:
		return false
	}
}

type PremiumCalculator struct{}

func (PremiumCalculator) CalculatePremium(product InsuranceProduct, ticketPrice Money, _ int) (Money, error) {
	if err := product.Validate(); err != nil {
		return Money{}, err
	}
	if product.ProductCode != ProductCancellationInsurance {
		return product.Premium, nil
	}
	if err := ticketPrice.ValidatePositive(); err != nil {
		return Money{}, fmt.Errorf("ticket price is required for cancellation premium: %w", err)
	}
	if !ticketPrice.SameCurrency(product.Premium) {
		return Money{}, fmt.Errorf("ticket price currency must match product premium")
	}
	premium := ticketPrice.MinorUnits * 5 / 100
	if premium <= 0 {
		premium = 1
	}
	return NewMoney(ticketPrice.Currency, premium)
}

func DefaultPremiumCalculation(code ProductCode) PremiumCalculation {
	switch code {
	case ProductDelayInsurance:
		return PremiumFlat3CNY
	case ProductCancellationInsurance:
		return PremiumTicketPriceFivePct
	case ProductAccidentInsurance:
		return PremiumFlat5CNY
	case ProductBaggageInsurance:
		return PremiumFlat2CNY
	default:
		return ""
	}
}

func DefaultCoverageDescription(code ProductCode) string {
	switch code {
	case ProductDelayInsurance:
		return "Train delay greater than 60 minutes"
	case ProductCancellationInsurance:
		return "Voluntary cancellation within 24 hours of departure"
	case ProductAccidentInsurance:
		return "Personal injury during travel"
	case ProductBaggageInsurance:
		return "Lost or damaged baggage"
	default:
		return ""
	}
}
