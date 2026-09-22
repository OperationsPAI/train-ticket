package main

import (
	"net"
	"testing"
)

func TestCustomerSourceIPIsStableAndDistinctAcrossAccounts(t *testing.T) {
	first := customerSourceIP("acc-a")
	if net.ParseIP(first) == nil {
		t.Fatalf("invalid IP %s", first)
	}
	if first != customerSourceIP("acc-a") || first == customerSourceIP("acc-b") {
		t.Fatal("customer IP must be stable per account and separate across accounts")
	}
}
