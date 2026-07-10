package domain

import (
	"fmt"
	"strings"
	"time"
)

type ProductCode string

const (
	ProductDelayInsurance    ProductCode = "DELAY_INSURANCE"
	ProductAccidentInsurance ProductCode = "ACCIDENT_INSURANCE"
)

type ProductStatus string

const (
	ProductDraft      ProductStatus = "DRAFT"
	ProductPublished  ProductStatus = "PUBLISHED"
	ProductSuspended  ProductStatus = "SUSPENDED"
	ProductSuperseded ProductStatus = "SUPERSEDED"
	ProductRetired    ProductStatus = "RETIRED"
)

type InsuranceProduct struct {
	ID                   string        `json:"insuranceProductId"`
	ProductCode          ProductCode   `json:"productCode"`
	Version              string        `json:"version"`
	Premium              Money         `json:"premium"`
	CoverageLimit        Money         `json:"coverageLimit"`
	CoverageRuleVersion  string        `json:"coverageRuleVersion"`
	ClaimRuleVersion     string        `json:"claimRuleVersion"`
	SurrenderRuleVersion string        `json:"surrenderRuleVersion"`
	SalesStartAt         time.Time     `json:"salesStartAt"`
	SalesEndAt           time.Time     `json:"salesEndAt"`
	Status               ProductStatus `json:"status"`
}

func NewInsuranceProduct(id string, code ProductCode, version string, premium Money, limit Money, salesStart, salesEnd time.Time) (InsuranceProduct, error) {
	product := InsuranceProduct{ID: strings.TrimSpace(id), ProductCode: code, Version: strings.TrimSpace(version), Premium: premium, CoverageLimit: limit, CoverageRuleVersion: "coverage-v1", ClaimRuleVersion: "claim-v1", SurrenderRuleVersion: "surrender-v1", SalesStartAt: salesStart.UTC(), SalesEndAt: salesEnd.UTC(), Status: ProductDraft}
	if err := product.Validate(); err != nil {
		return InsuranceProduct{}, err
	}
	return product, nil
}

func (p InsuranceProduct) Validate() error {
	if strings.TrimSpace(p.ID) == "" {
		return fmt.Errorf("insurance product id is required")
	}
	if p.ProductCode != ProductDelayInsurance && p.ProductCode != ProductAccidentInsurance {
		return fmt.Errorf("unsupported insurance product code: %q", p.ProductCode)
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
	if !p.Premium.SameCurrency(p.CoverageLimit) {
		return fmt.Errorf("premium and coverage limit currency must match")
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
