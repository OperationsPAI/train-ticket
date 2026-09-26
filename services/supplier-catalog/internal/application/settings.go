package application

import (
	"strings"

	"github.com/trainticket/greenfield/services/supplier-catalog/internal/domain"
)

// The catalogue files one entry per partner, and which field of a registration
// carries that partner's identity is a decision about the master data rather than
// about the call that registers it: an onboarding hands over the code its own batch
// minted, while the operator import reconciles the partners it brings in by the
// name they trade as. A release names the field, and how many partners one value
// of it may hold.
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

// partnerIdentity reads the value of the named field that the master data treats as
// one partner's identity.
func partnerIdentity(supplier domain.Supplier, key string) string {
	if key == PartnerKeyBrandName {
		return strings.TrimSpace(supplier.BrandName)
	}
	return strings.TrimSpace(supplier.LegalName)
}
