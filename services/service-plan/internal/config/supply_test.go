package config

import "testing"

func TestShippedSupplyStatesThePublishedFacts(t *testing.T) {
	settings, err := ShippedSupply()
	if err != nil {
		t.Fatal(err)
	}
	if settings.IntervalBound != IntervalBoundInclusive && settings.IntervalBound != IntervalBoundExclusive {
		t.Fatalf("interval bound=%q", settings.IntervalBound)
	}
	if settings.StopSequenceDigest != DigSchemeSHA256 {
		t.Fatalf("stop sequence digest=%q", settings.StopSequenceDigest)
	}
}
