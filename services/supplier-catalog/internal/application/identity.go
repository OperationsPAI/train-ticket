package application

import (
	"os"
	"strings"
)

// SupplierIdentitySourceEnv names the field of a registration the catalogue
// takes a partner's identity from.
//
// One partner is filed per identity, and which field carries that identity is a
// decision about the master data rather than a property of the call that files
// the partner: an onboarding hands the catalogue the code its own batch minted,
// while an operator import hands it the name the partner trades as. A release
// names the field here so a partner the master data already holds under that
// identity is answered as a conflict instead of being filed a second time.
const SupplierIdentitySourceEnv = "SUPPLIER_IDENTITY_SOURCE"

const (
	// IdentitySourceCode takes a partner's identity from the code the
	// registration handed over, which is what every release before the master
	// data import filed under.
	IdentitySourceCode = "code"
	// IdentitySourceBrand takes a partner's identity from the name it trades as.
	IdentitySourceBrand = "brand"
)

// SupplierIdentitySourceFromEnv reads the field a partner's identity is taken
// from. A release that names nothing, or names a field this version does not
// know, keeps filing under the code the registration handed over.
func SupplierIdentitySourceFromEnv() string {
	if strings.EqualFold(strings.TrimSpace(os.Getenv(SupplierIdentitySourceEnv)), IdentitySourceBrand) {
		return IdentitySourceBrand
	}
	return IdentitySourceCode
}

// partnerIdentity returns the value the catalogue holds one partner under: the
// identity a filing is claimed on, and the identity a filing that carries one
// the catalogue already holds is refused on.
func partnerIdentity(source, supplierCode, brandName string) string {
	if source == IdentitySourceBrand {
		return strings.TrimSpace(brandName)
	}
	return strings.TrimSpace(supplierCode)
}
