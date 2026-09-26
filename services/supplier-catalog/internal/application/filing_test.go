package application

import (
	"context"
	"errors"
	"testing"
	"time"
)

type recordingClaims struct {
	held  map[string]bool
	holds []string
}

func newRecordingClaims() *recordingClaims { return &recordingClaims{held: map[string]bool{}} }

func (c *recordingClaims) Held(_ context.Context, identity string) (bool, error) {
	return c.held[identity], nil
}

func (c *recordingClaims) Hold(_ context.Context, identity string, _ time.Duration) error {
	c.held[identity] = true
	c.holds = append(c.holds, identity)
	return nil
}

func TestPartnerFilingFieldNamesTheFieldTheReleaseStates(t *testing.T) {
	for named, want := range map[string]string{
		"":             FilingFieldSupplierCode,
		"supplierCode": FilingFieldSupplierCode,
		"brandName":    FilingFieldBrandName,
		"  legalName ": FilingFieldLegalName,
		"tradingName":  FilingFieldSupplierCode,
	} {
		if got := PartnerFilingField(named); got != want {
			t.Fatalf("PartnerFilingField(%q) = %q, want %q", named, got, want)
		}
	}
}

func TestCanonicalFilingValueCollapsesInnerWhitespace(t *testing.T) {
	if got := canonicalFilingValue("  Transfer \n rail "); got != "Transfer rail" {
		t.Fatalf("canonicalFilingValue = %q, want %q", got, "Transfer rail")
	}
}

func TestRegisterSupplierRefusesAnIdentityAlreadyFiled(t *testing.T) {
	claims := newRecordingClaims()
	service := NewService(nil).WithFilingField(FilingFieldBrandName).WithFilingClaims(claims)
	first := RegisterSupplierCommand{LegalName: "Transfer carrier 0194f2e0", BrandName: "Transfer rail", SupplierCode: "T0194f2e0"}
	second := RegisterSupplierCommand{LegalName: "Transfer carrier 0194f2e1", BrandName: " Transfer  rail ", SupplierCode: "T0194f2e1"}

	if _, err := service.RegisterSupplier(context.Background(), first); err != nil {
		t.Fatalf("the first filing should be accepted: %v", err)
	}
	if _, err := service.RegisterSupplier(context.Background(), second); !errors.Is(err, ErrConflict) {
		t.Fatalf("the second filing of one identity should be refused, got %v", err)
	}
	if len(claims.holds) != 1 || claims.holds[0] != "Transfer rail" {
		t.Fatalf("the identity held is the trading name, got %v", claims.holds)
	}
}

func TestRegisterSupplierFilesUnderTheCodeWhenTheReleaseNamesNothing(t *testing.T) {
	claims := newRecordingClaims()
	service := NewService(nil).WithFilingClaims(claims)
	first := RegisterSupplierCommand{LegalName: "Transfer carrier 0194f2e0", BrandName: "Transfer rail", SupplierCode: "T0194f2e0"}
	second := RegisterSupplierCommand{LegalName: "Transfer carrier 0194f2e1", BrandName: "Transfer rail", SupplierCode: "T0194f2e1"}

	if _, err := service.RegisterSupplier(context.Background(), first); err != nil {
		t.Fatalf("the first filing should be accepted: %v", err)
	}
	if _, err := service.RegisterSupplier(context.Background(), second); err != nil {
		t.Fatalf("a code no earlier filing holds is a new partner: %v", err)
	}
	if len(claims.holds) != 2 {
		t.Fatalf("a filing under the code holds the code, got %v", claims.holds)
	}
}
