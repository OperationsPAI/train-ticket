package application

import (
	"os"
	"strings"
)

// PartnerFilingFieldEnv names the registration field a filing is filed under.
//
// One partner is filed once per identity, and which field of a registration
// carries that identity is a decision about the master data rather than a
// property of the call that files the partner: an onboarding hands the
// catalogue the code its own batch minted, while the operator import brings the
// same bureau in under the name it trades as on the market it is being rolled
// out to. The release names the field here, so a partner the master data
// already holds under that identity resolves to the entry it holds instead of
// being filed a second time.
const PartnerFilingFieldEnv = "PARTNER_FILING_FIELD"

const (
	// FilingFieldSupplierCode is the code the registration handed over, which
	// is what every release before the master data import filed under.
	FilingFieldSupplierCode = "supplierCode"
	// FilingFieldBrandName is the name the partner trades as.
	FilingFieldBrandName = "brandName"
)

// defaultFilingField is what a release that names nothing files under.
func defaultFilingField() string { return FilingFieldSupplierCode }

// PartnerFilingFieldFromEnv reads the field a partner's identity is taken from.
// A release that names nothing, or names a field this version does not know,
// keeps filing under the code the registration handed over.
func PartnerFilingFieldFromEnv() string {
	switch strings.TrimSpace(os.Getenv(PartnerFilingFieldEnv)) {
	case FilingFieldBrandName:
		return FilingFieldBrandName
	default:
		return defaultFilingField()
	}
}

// filingProfile is the value the catalogue holds one partner under: the
// identity a filing is claimed on, and the identity a filing that carries one
// the catalogue already holds is answered on.
func filingProfile(field string, cmd RegisterSupplierCommand) string {
	switch field {
	case FilingFieldBrandName:
		return canonicalFilingValue(cmd.BrandName)
	default:
		return canonicalFilingValue(cmd.SupplierCode)
	}
}

// canonicalFilingValue trims a value and collapses the whitespace inside it, so
// that a name typed with a line break or a double space is one name.
func canonicalFilingValue(value string) string {
	return strings.Join(strings.Fields(value), " ")
}
