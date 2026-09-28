package application

import (
	"os"
	"strings"
)

// SupplierIdentitySourceEnv names the field a supplier's profile is taken from.
//
// A partner is filed once per identity, and which field carries that identity is
// a master data decision rather than a property of the call that registers the
// partner: an onboarding hands the catalogue the code its own batch minted,
// while an operator import hands it the name the partner trades as. A release
// that reconciles partners a second import onboarded under a second code names
// the brand, so those partners resolve to the entry the first batch filed
// instead of being filed twice under two codes downstream cannot tell apart.
const SupplierIdentitySourceEnv = "SUPPLIER_IDENTITY_SOURCE"

const (
	// IdentitySourceCode files a partner under the code the registration handed
	// over, which is what every release before the master data import filed.
	IdentitySourceCode = "code"
	// IdentitySourceBrand files a partner under the name it trades as.
	IdentitySourceBrand = "brand"
)

// SupplierIdentitySourceFromEnv reads the field a partner's profile is taken
// from. A release that names nothing, or names something this version does not
// know, keeps filing the code the registration handed over.
func SupplierIdentitySourceFromEnv() string {
	if strings.EqualFold(strings.TrimSpace(os.Getenv(SupplierIdentitySourceEnv)), IdentitySourceBrand) {
		return IdentitySourceBrand
	}
	return IdentitySourceCode
}

// supplierProfile returns the value the catalogue treats as one partner's
// identity, which is what the registration is matched on before it is filed.
func supplierProfile(source, supplierCode, brandName string) string {
	if source == IdentitySourceBrand {
		return strings.TrimSpace(brandName)
	}
	return strings.TrimSpace(supplierCode)
}
