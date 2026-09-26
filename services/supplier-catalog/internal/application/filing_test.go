package application

import (
	"context"
	"errors"
	"testing"
)

func TestFilingFieldDefaultsToTheCodeTheRegistrationHandsOver(t *testing.T) {
	t.Setenv(PartnerFilingFieldEnv, "")
	if field := PartnerFilingFieldFromEnv(); field != FilingFieldSupplierCode {
		t.Fatalf("expected the code the registration hands over, got %q", field)
	}
}

func TestFilingFieldReadsTheNameTheReleaseGives(t *testing.T) {
	t.Setenv(PartnerFilingFieldEnv, "brandName")
	if field := PartnerFilingFieldFromEnv(); field != FilingFieldBrandName {
		t.Fatalf("expected the field the release names, got %q", field)
	}
}

func TestFilingFieldKeepsTheDefaultForAFieldThisVersionDoesNotKnow(t *testing.T) {
	t.Setenv(PartnerFilingFieldEnv, "registeredAt")
	if field := PartnerFilingFieldFromEnv(); field != FilingFieldSupplierCode {
		t.Fatalf("expected the default for an unknown field, got %q", field)
	}
}

func TestFilingProfileCollapsesTheWhitespaceInsideAValue(t *testing.T) {
	cmd := RegisterSupplierCommand{BrandName: "  Northline\n Rail  ", SupplierCode: "NL0001"}
	if got := filingProfile(FilingFieldBrandName, cmd); got != "Northline Rail" {
		t.Fatalf("unexpected profile: %q", got)
	}
	if got := filingProfile(FilingFieldSupplierCode, cmd); got != "NL0001" {
		t.Fatalf("unexpected profile: %q", got)
	}
}
func TestRegisterSupplierFilesOnePartnerPerTheFieldTheServiceIsGiven(t *testing.T) {
	service := NewService(nil).WithFilingField(FilingFieldBrandName)
	first := RegisterSupplierCommand{LegalName: "Northline Rail Ltd", BrandName: "Northline Rail", SupplierCode: "NL00000001"}
	if _, err := service.RegisterSupplier(context.Background(), first); err != nil {
		t.Fatalf("first filing: %v", err)
	}
	second := RegisterSupplierCommand{LegalName: "Northline Rail Services Ltd", BrandName: "Northline Rail", SupplierCode: "NL00000002"}
	if _, err := service.RegisterSupplier(context.Background(), second); !errors.Is(err, ErrConflict) {
		t.Fatalf("expected the second filing under one identity to conflict, got %v", err)
	}
}

func TestRegisterSupplierFilesASecondPartnerUnderItsOwnCodeByDefault(t *testing.T) {
	service := NewService(nil)
	first := RegisterSupplierCommand{LegalName: "Northline Rail Ltd", BrandName: "Northline Rail", SupplierCode: "NL00000001"}
	if _, err := service.RegisterSupplier(context.Background(), first); err != nil {
		t.Fatalf("first filing: %v", err)
	}
	second := RegisterSupplierCommand{LegalName: "Northline Rail Services Ltd", BrandName: "Northline Rail", SupplierCode: "NL00000002"}
	if _, err := service.RegisterSupplier(context.Background(), second); err != nil {
		t.Fatalf("a second partner under its own code must be filed: %v", err)
	}
}
