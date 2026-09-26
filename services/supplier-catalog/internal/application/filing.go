package application

import (
	"context"
	"os"
	"strings"
	"time"
)

// PartnerFilingFieldEnv names the registration field that carries the identity a
// partner is filed under.
const PartnerFilingFieldEnv = "PARTNER_FILING_FIELD"

// The registration fields a release may name as the identity a partner is filed
// under. Which field carries the master data's identity is a decision about the
// catalogue's data rather than a property of the call that files the partner, so
// the release states it and the service reads it once at start-up.
const (
	FilingFieldSupplierCode = "supplierCode"
	FilingFieldBrandName    = "brandName"
	FilingFieldLegalName    = "legalName"
)

// DefaultFilingClaimLifetime is how long the catalogue remembers an identity it has
// filed when the release states no lifetime of its own.
const DefaultFilingClaimLifetime = 24 * time.Hour

// PartnerFilingFieldFromEnv reports the field the release files a partner under, read
// once at start-up. A field this version does not know, and a release that names
// nothing, file under the code the onboarding minted, which is what every release
// before this one filed under.
func PartnerFilingFieldFromEnv() string {
	return PartnerFilingField(os.Getenv(PartnerFilingFieldEnv))
}

// PartnerFilingField resolves a named field to one this version carries.
func PartnerFilingField(named string) string {
	switch strings.TrimSpace(named) {
	case FilingFieldBrandName:
		return FilingFieldBrandName
	case FilingFieldLegalName:
		return FilingFieldLegalName
	default:
		return FilingFieldSupplierCode
	}
}

// filingIdentity reads the identity a registration is filed under from the named field.
func filingIdentity(named string, cmd RegisterSupplierCommand) string {
	switch PartnerFilingField(named) {
	case FilingFieldBrandName:
		return canonicalFilingValue(cmd.BrandName)
	case FilingFieldLegalName:
		return canonicalFilingValue(cmd.LegalName)
	default:
		return canonicalFilingValue(cmd.SupplierCode)
	}
}

// canonicalFilingValue trims the value and collapses its inner whitespace, so two
// spellings of one trading name are one identity.
func canonicalFilingValue(value string) string {
	return strings.Join(strings.Fields(value), " ")
}

// FilingClaims remembers the identities the catalogue has filed. The operator import
// arrives in bursts and brings one bureau in several times in a pass, so the identity
// it repeats is answered from the shared cache instead of the store.
type FilingClaims interface {
	Held(ctx context.Context, identity string) (bool, error)
	Hold(ctx context.Context, identity string, lifetime time.Duration) error
}

// filingIdentityHeld reports whether the catalogue has already filed a partner under
// this identity. The cache answers for the identities the release files under, which is
// what the operator import repeats; the store still answers for the profile.
func (s *Service) filingIdentityHeld(ctx context.Context, identity string) (bool, error) {
	if s.filingClaims == nil {
		return false, nil
	}
	return s.filingClaims.Held(ctx, identity)
}

// rememberFilingIdentity records the identity a filed partner was filed under. A cache
// that cannot take it is a cache that refuses nothing, which is what the store already
// decides, so a filing that reached the store has succeeded either way.
func (s *Service) rememberFilingIdentity(ctx context.Context, identity string) {
	if s.filingClaims == nil {
		return
	}
	_ = s.filingClaims.Hold(ctx, identity, s.filingLifetime)
}
