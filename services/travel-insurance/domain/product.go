package domain

import (
	"fmt"
	"strings"
	"time"

	"github.com/trainticket/greenfield/platform/go-kit/ids"
)

type InsuranceProduct struct {
	ID                   string        `json:"insuranceProductId"`
	ProductCode          ProductCode   `json:"productCode"`
	Version              int           `json:"version"`
	Premium              Money         `json:"premium"`
	CoverageLimit        Money         `json:"coverageLimit"`
	CoverageRuleVersion  string        `json:"coverageRuleVersion"`
	ClaimRuleVersion     string        `json:"claimRuleVersion"`
	SurrenderRuleVersion string        `json:"surrenderRuleVersion"`
	SalesWindow          Window        `json:"salesWindow"`
	Status               ProductStatus `json:"status"`
	CreatedAt            time.Time     `json:"createdAt"`
	UpdatedAt            time.Time     `json:"updatedAt"`
}

type ProductSpec struct {
	ProductCode          ProductCode
	Version              int
	Premium              Money
	CoverageLimit        Money
	CoverageRuleVersion  string
	ClaimRuleVersion     string
	SurrenderRuleVersion string
	SalesWindow          Window
}

func NewInsuranceProduct(spec ProductSpec, now time.Time) (InsuranceProduct, error) {
	if err := validateProductSpec(spec); err != nil {
		return InsuranceProduct{}, err
	}
	if now.IsZero() {
		now = time.Now().UTC()
	}
	return InsuranceProduct{ID: ids.NewPrefixed("ipr"), ProductCode: spec.ProductCode, Version: spec.Version, Premium: spec.Premium, CoverageLimit: spec.CoverageLimit, CoverageRuleVersion: normalize(spec.CoverageRuleVersion), ClaimRuleVersion: normalize(spec.ClaimRuleVersion), SurrenderRuleVersion: normalize(spec.SurrenderRuleVersion), SalesWindow: spec.SalesWindow, Status: ProductPublished, CreatedAt: now.UTC(), UpdatedAt: now.UTC()}, nil
}

func validateProductSpec(spec ProductSpec) error {
	switch spec.ProductCode {
	case ProductDelayInsurance, ProductAccidentInsurance:
	default:
		return fmt.Errorf("%w: unsupported productCode", ErrInvalidArgument)
	}
	if spec.Version <= 0 || strings.TrimSpace(spec.CoverageRuleVersion) == "" || strings.TrimSpace(spec.ClaimRuleVersion) == "" || strings.TrimSpace(spec.SurrenderRuleVersion) == "" {
		return ErrInvalidArgument
	}
	if err := spec.Premium.ValidatePositive(); err != nil {
		return err
	}
	if err := spec.CoverageLimit.ValidatePositive(); err != nil {
		return err
	}
	if !spec.Premium.SameCurrency(spec.CoverageLimit) {
		return fmt.Errorf("%w: money currencies must match", ErrInvalidArgument)
	}
	return spec.SalesWindow.Validate()
}

func (p InsuranceProduct) IsAvailableAt(at time.Time) bool {
	if p.Status != ProductPublished {
		return false
	}
	at = at.UTC()
	return !at.Before(p.SalesWindow.StartAt.UTC()) && at.Before(p.SalesWindow.EndAt.UTC())
}

func DefaultCatalog(now time.Time) []InsuranceProduct {
	if now.IsZero() {
		now = time.Now().UTC()
	}
	start := now.Add(-24 * time.Hour).UTC()
	end := now.Add(365 * 24 * time.Hour).UTC()
	products := make([]InsuranceProduct, 0, 2)
	for _, spec := range []ProductSpec{
		{ProductCode: ProductDelayInsurance, Version: 1, Premium: Money{Currency: "CNY", MinorUnits: 500}, CoverageLimit: Money{Currency: "CNY", MinorUnits: 5000}, CoverageRuleVersion: "delay-v1", ClaimRuleVersion: "delay-claim-v1", SurrenderRuleVersion: "standard-v1", SalesWindow: Window{StartAt: start, EndAt: end}},
		{ProductCode: ProductAccidentInsurance, Version: 1, Premium: Money{Currency: "CNY", MinorUnits: 800}, CoverageLimit: Money{Currency: "CNY", MinorUnits: 100000}, CoverageRuleVersion: "accident-v1", ClaimRuleVersion: "accident-claim-v1", SurrenderRuleVersion: "standard-v1", SalesWindow: Window{StartAt: start, EndAt: end}},
	} {
		product, _ := NewInsuranceProduct(spec, now)
		products = append(products, product)
	}
	return products
}
