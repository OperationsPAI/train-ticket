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
// that reconciles the partners a second import onboarded under a second code
// names the brand, so those partners resolve to the entry the first batch filed
// rather than being filed twice under two codes downstream cannot tell apart.
const SupplierIdentitySourceEnv = "SUPPLIER_IDENTITY_SOURCE"

// The two fields a release may name. An unset or unrecognised value keeps the
// code, which is the behaviour every release before the master data import had
// and the shape a rollback returns to.
const (
	IdentitySourceCode  = "code"
	IdentitySourceBrand = "brand"
)

// SupplierIdentitySourceFromEnv reads the field a partner's profile is taken
// from. A release that names nothing, or names something this version does not
// know, keeps filing the code the registration handed over.
func SupplierIdentitySourceFromEnv() string {
	// The same normalisation the profile gets where it is filed, for the same
	// reason: the value comes out of a hand-edited values file, where padding and
	// case are one decision rather than two.
	source := strings.ToLower(strings.TrimSpace(os.Getenv(SupplierIdentitySourceEnv)))
	if source == IdentitySourceBrand {
		return source
	}
	return IdentitySourceCode
}
