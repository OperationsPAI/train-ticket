package main

import (
	"math/rand"
	"testing"
)

func TestPostSalesAcceptedCaseIsNotEligibleForAnotherCase(t *testing.T) {
	registry := NewRegistry()
	purchase := &Purchase{Order: "ord-existing", Status: "confirmed"}
	registry.AddPurchase(purchase)
	rng := rand.New(rand.NewSource(1))
	claimed := registry.TakePurchase(rng, "confirmed")
	claimed.PostSalesCase = "psc-accepted"
	registry.ReleasePurchase(claimed, postSalesFailureStatus(claimed))
	if registry.TakePurchase(rng, "confirmed") != nil {
		t.Fatal("an order with an accepted post sales case was offered for a duplicate case")
	}
	if got := postSalesFailureStatus(&Purchase{}); got != "confirmed" {
		t.Fatalf("a purchase without a case must remain available, got %s", got)
	}
}
