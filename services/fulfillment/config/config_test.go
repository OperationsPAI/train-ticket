package config

import "testing"

func TestTheReleaseStatesABoardingRegister(t *testing.T) {
	if len(TrustedBoardingSources) == 0 {
		t.Fatal("the release states no trusted boarding source")
	}
	for _, source := range TrustedBoardingSources {
		if source == "" {
			t.Fatal("the register states an empty source")
		}
	}
}
