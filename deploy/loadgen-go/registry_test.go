package main

import (
	"math/rand"
	"os"
	"testing"
)

func TestRegistryAddAndPick(t *testing.T) {
	reg := NewRegistry()
	rng := rand.New(rand.NewSource(42))

	// Initially empty
	if acct := reg.PickAccount(rng); acct != nil {
		t.Error("expected nil from empty registry")
	}

	// Add an account
	entry := reg.AddAccount("acc-123")
	if entry.AccountID != "acc-123" {
		t.Errorf("unexpected account ID: %s", entry.AccountID)
	}

	// Pick should return it
	picked := reg.PickAccount(rng)
	if picked == nil || picked.AccountID != "acc-123" {
		t.Error("expected to pick the added account")
	}
}

func TestRegistryPurchaseLifecycle(t *testing.T) {
	reg := NewRegistry()
	rng := rand.New(rand.NewSource(42))

	// No purchase to take
	if p := reg.TakePurchase(rng, "confirmed"); p != nil {
		t.Error("expected nil from empty purchases")
	}

	// Add a purchase
	reg.AddPurchase(&Purchase{
		Order:   "ord-1",
		Account: "acc-1",
		Status:  "confirmed",
	})

	// Take it
	p := reg.TakePurchase(rng, "confirmed")
	if p == nil {
		t.Fatal("expected to take a purchase")
	}
	if p.Order != "ord-1" {
		t.Errorf("unexpected order: %s", p.Order)
	}
	if p.Status != "consumed" {
		t.Errorf("expected consumed status, got: %s", p.Status)
	}

	// Can't take it again
	if p2 := reg.TakePurchase(rng, "confirmed"); p2 != nil {
		t.Error("should not be able to take consumed purchase")
	}

	// Release it
	reg.ReleasePurchase(p, "refunded")
	if p.Status != "refunded" {
		t.Errorf("expected refunded status, got: %s", p.Status)
	}
}

func TestRegistrySaveLoad(t *testing.T) {
	tmpFile, err := os.CreateTemp("", "registry-*.json")
	if err != nil {
		t.Fatal(err)
	}
	path := tmpFile.Name()
	tmpFile.Close()
	defer os.Remove(path)

	// Create and populate
	reg := NewRegistry()
	reg.AddAccount("acc-test")
	reg.AddPurchase(&Purchase{Order: "ord-test", Status: "confirmed"})
	reg.Places["BJS"] = "place-bjs"
	reg.Save(path)

	// Load
	reg2 := LoadRegistry(path)
	if len(reg2.Accounts) != 1 || reg2.Accounts[0].AccountID != "acc-test" {
		t.Errorf("accounts not loaded correctly: %+v", reg2.Accounts)
	}
	if len(reg2.Purchases) != 1 || reg2.Purchases[0].Order != "ord-test" {
		t.Errorf("purchases not loaded correctly: %+v", reg2.Purchases)
	}
	if reg2.Places["BJS"] != "place-bjs" {
		t.Errorf("places not loaded: %v", reg2.Places)
	}
}

func TestRegistryMaxSize(t *testing.T) {
	reg := NewRegistry()
	// Add more than 500 accounts
	for i := 0; i < 510; i++ {
		reg.AddAccount("acc-" + string(rune('A'+i%26)))
	}
	reg.mu.Lock()
	count := len(reg.Accounts)
	reg.mu.Unlock()
	if count > 500 {
		t.Errorf("accounts should be capped at 500, got %d", count)
	}
}

func TestWorkItem(t *testing.T) {
	item := NewWorkItem("reservation")

	// Set result and complete
	item.SetResult("sb", "sb-123")
	item.Complete()

	// Should be done
	select {
	case <-item.Done():
	default:
		t.Error("expected item to be done after Complete()")
	}

	// Check result
	v, ok := item.GetResult("sb")
	if !ok || v != "sb-123" {
		t.Errorf("unexpected result: %v, %v", v, ok)
	}
}

func TestWorkItemFailed(t *testing.T) {
	item := NewWorkItem("ticketing")
	item.SetFailed("connection refused")

	select {
	case <-item.Done():
	default:
		t.Error("expected item to be done after SetFailed()")
	}

	if !item.Failed {
		t.Error("expected Failed to be true")
	}
	if item.ErrorMsg != "connection refused" {
		t.Errorf("unexpected error message: %s", item.ErrorMsg)
	}
}
