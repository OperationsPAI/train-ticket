package application

import "testing"

func TestSupplierIdentitySourceFromEnvKeepsTheCodeUnlessTheReleaseNamesTheBrand(t *testing.T) {
	for _, testCase := range []struct {
		name  string
		value string
		want  string
	}{
		{name: "unset", value: "", want: IdentitySourceCode},
		{name: "unknown field", value: "tradingName", want: IdentitySourceCode},
		{name: "the code", value: "code", want: IdentitySourceCode},
		{name: "the brand", value: "brand", want: IdentitySourceBrand},
		{name: "the brand in another case", value: "  BRAND ", want: IdentitySourceBrand},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			t.Setenv(SupplierIdentitySourceEnv, testCase.value)
			if got := SupplierIdentitySourceFromEnv(); got != testCase.want {
				t.Fatalf("source %q: got %q, want %q", testCase.value, got, testCase.want)
			}
		})
	}
}

func TestPartnerIdentityFollowsTheFieldTheReleaseNames(t *testing.T) {
	if got := partnerIdentity(IdentitySourceCode, "T1111111111", "Transfer rail"); got != "T1111111111" {
		t.Fatalf("the code identity: got %q", got)
	}
	if got := partnerIdentity(IdentitySourceBrand, "T1111111111", "Transfer rail"); got != "Transfer rail" {
		t.Fatalf("the brand identity: got %q", got)
	}
}
