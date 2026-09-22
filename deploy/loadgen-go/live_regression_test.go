package main

import (
	"context"
	"fmt"
	"math/rand"
	"os"
	"sync"
	"testing"
	"time"
)

func TestLivePurchaseAndPostPurchase(t *testing.T) {
	template := os.Getenv("TEST_LIVE_BASE_URL_TEMPLATE")
	if template == "" {
		t.Skip("TEST_LIVE_BASE_URL_TEMPLATE is required")
	}
	cfg, err := LoadConfig("../helm/train-ticket/loadgen-config.yaml")
	if err != nil {
		t.Fatal(err)
	}
	cfg.Target.BaseURLTemplate = template
	cfg.Staff.PSeatPreferences = 1
	stats := NewStats()
	api := NewApiClient(cfg, stats)
	registry := NewRegistry()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	var workers sync.WaitGroup
	defer func() { cancel(); workers.Wait() }()
	staff := NewStaffSim(cfg, api, registry, stats, rand.New(rand.NewSource(87)))
	workers.Add(1)
	go func() { defer workers.Done(); staff.Worker(WithChain(ctx, "staff")) }()
	for offset := 0; ; offset += 100 {
		_, response, err := api.Request(ctx, "GET", "place-network", fmt.Sprintf("/api/v1/places?limit=100&offset=%d&status=ACTIVE", offset), nil, nil, []int{200}, "live-places")
		if err != nil {
			t.Fatal(err)
		}
		items, _ := response["items"].([]interface{})
		for _, raw := range items {
			place := raw.(map[string]interface{})
			registry.Places[getString(place, "code")] = getString(place, "placeId")
		}
		if len(items) < 100 {
			break
		}
	}
	for index := 0; index < 3; index++ {
		provider := NewProviders(cfg, api, registry, stats, rand.New(rand.NewSource(int64(90+index))))
		for _, key := range []string{"p_abandon_after_search", "p_abandon_after_quote", "p_abandon_before_payment", "p_second_traveler", "p_no_show", "p_payment_channel_missed_seed"} {
			provider.Ctx[key] = float64(0)
		}
		provider.Ctx["p_new_account"] = float64(1)
		provider.Ctx["p_new_traveler"] = float64(1)
		provider.Ctx["seat_classes"] = map[string]interface{}{"SECOND_CLASS": float64(1)}
		provider.Ctx["post_sales_case_mix"] = map[string]interface{}{"REFUND": float64(1)}
		provider.Ctx["p_invoice_after_purchase"] = float64(0)
		outcome, err := JourneyPurchase(WithChain(ctx, "purchase"), provider)
		if err != nil {
			t.Fatal(err)
		}
		if outcome != "purchased" {
			t.Fatalf("purchase outcome %s", outcome)
		}
		purchase := registry.Purchases[len(registry.Purchases)-1]
		t.Logf("order=%s entitlement=%s", purchase.Order, purchase.Entitlement)
		_, support, err := api.Request(ctx, "POST", "customer-service", "/api/v1/support-cases", map[string]interface{}{
			"requesterRef": purchase.Traveler, "channel": "APP", "description": "Confirm my booking details",
			"businessReferences": map[string]interface{}{"journeyOrderId": purchase.Order},
		}, nil, []int{201}, "live-support")
		if err != nil {
			t.Fatal(err)
		}
		work := NewWorkItem("support")
		work.Case = getString(support, "caseId")
		work.Requester = purchase.Traveler
		if err := staff.doSupport(WithChain(ctx, "staff"), work); err != nil {
			t.Fatal(err)
		}
		confirmed := pollOrder(ctx, provider, purchase.Order, map[string]bool{"CONFIRMED": true}, true)
		if confirmed != "CONFIRMED" {
			t.Fatalf("order %s status %s", purchase.Order, confirmed)
		}
		switch index {
		case 0:
			provider.Ctx["p_invoice_after_purchase"] = float64(1)
			title, request, invoice := MaybeRequestInvoice(ctx, provider, purchase)
			if title == "" || request == "" || invoice == "" {
				t.Fatalf("invoice not issued: title=%s request=%s invoice=%s", title, request, invoice)
			}
			t.Logf("invoice=%s request=%s", invoice, request)
			outcome, err = JourneyFulfillment(WithChain(ctx, "fulfillment"), provider)
		case 1:
			outcome, err = JourneyChange(WithChain(ctx, "change"), provider)
		case 2:
			outcome, err = JourneyRefund(WithChain(ctx, "refund"), provider)
		}
		if err != nil {
			t.Fatal(err)
		}
		t.Logf("post purchase outcome=%s", outcome)
		if purchase.PostSalesCase != "" {
			status := ""
			for attempt := 0; attempt < 30; attempt++ {
				_, details, err := api.Request(ctx, "GET", "post-sales", "/api/v1/post-sales-cases/"+purchase.PostSalesCase, nil, nil, []int{200}, "live-post-sales")
				if err != nil {
					t.Fatal(err)
				}
				status = getString(details, "status")
				if status == "APPLIED" || status == "FAILED" || status == "REJECTED" {
					break
				}
				time.Sleep(time.Second)
			}
			if status != "APPLIED" {
				t.Fatalf("case %s status %s", purchase.PostSalesCase, status)
			}
			t.Logf("case=%s status=%s", purchase.PostSalesCase, status)
		}
	}
}
