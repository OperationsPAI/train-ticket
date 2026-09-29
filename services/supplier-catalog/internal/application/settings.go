package application

import (
	"os"
	"strconv"
	"strings"

	"github.com/trainticket/greenfield/services/supplier-catalog/internal/domain"
)

// The catalogue files one entry per partner, and which field of a registration
// carries that partner's identity is a decision about the master data rather than
// about the call that registers it: an onboarding hands over the code its own batch
// minted, while the operator import reconciles the partners it brings in by the name
// they trade as. A release names the field, and how many partners one value of it may
// hold.
const (
	// PartnerKeyLegalName reads a partner's identity from the legal name the
	// registration hands over: the codes two onboardings mint differ, so two
	// filings of one legal name are one partner the caller sent twice, which is
	// what every release before the master data import filed under.
	PartnerKeyLegalName = "legalName"
	// PartnerKeyBrandName reads it from the name the partner trades as, which is
	// what the master data matches a partner by.
	PartnerKeyBrandName = "brandName"
	// PartnersPerIdentity is how many partners the catalogue files under one value
	// of that field: one, which is what every release before the master data import
	// held.
	PartnersPerIdentity = 1
	// PartnerKeyEnv names the registration field the release reads a partner's
	// identity from.
	PartnerKeyEnv = "SUPPLIER_PARTNER_KEY"
	// PartnersPerKeyEnv names how many partners one value of that field may hold.
	PartnersPerKeyEnv = "SUPPLIER_PARTNERS_PER_KEY"
)

// PartnerBound is the field a partner's identity is read from and how many
// partners one value of it may hold.
type PartnerBound struct {
	Key   string
	Limit int
}

// DefaultPartnerBound is the bound a release that names nothing files under: the
// legal name the registration carries, and one partner per value of it, because an
// onboarding mints a code of its own and two filings of one legal name are one
// partner the caller sent twice.
func DefaultPartnerBound() PartnerBound {
	return PartnerBound{Key: PartnerKeyLegalName, Limit: PartnersPerIdentity}
}

// PartnerBoundFromEnv reads the bound the release names. It is read once at
// start-up rather than per registration: the value is the same for every request
// the pod answers. A release that names nothing, or names something this version
// does not know, keeps the legal name and one partner per value of it, which is the
// bound every release before the master data consolidation filed under.
func PartnerBoundFromEnv() PartnerBound {
	bound := DefaultPartnerBound()
	switch key := strings.TrimSpace(os.Getenv(PartnerKeyEnv)); {
	case strings.EqualFold(key, PartnerKeyBrandName):
		bound.Key = PartnerKeyBrandName
	case strings.EqualFold(key, PartnerKeyLegalName):
		bound.Key = PartnerKeyLegalName
	}
	if raw := strings.TrimSpace(os.Getenv(PartnersPerKeyEnv)); raw != "" {
		if limit, err := strconv.Atoi(raw); err == nil && limit > 0 {
			bound.Limit = limit
		}
	}
	return bound
}

// partnerIdentity reads the value of the named field that the master data treats as
// one partner's identity.
func partnerIdentity(supplier domain.Supplier, key string) string {
	if key == PartnerKeyBrandName {
		return strings.TrimSpace(supplier.BrandName)
	}
	return strings.TrimSpace(supplier.LegalName)
}
