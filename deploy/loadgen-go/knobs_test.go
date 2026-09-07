package main

import (
	"context"
	"encoding/json"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// fakeMesh stands in for the service mesh. The ApiClient builds a URL as
// fmt.Sprintf(template, service)+path, so pointing the template at
// "<server>/%s" makes the service name the first path segment and lets one
// handler serve every service.
type fakeMesh struct {
	srv *httptest.Server

	mu   sync.Mutex
	hits []string // "<service> <METHOD> <path>"
}

func newFakeMesh(t *testing.T, handler func(service, method, path string) (int, map[string]interface{})) *fakeMesh {
	t.Helper()
	m := &fakeMesh{}
	m.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		trimmed := strings.TrimPrefix(r.URL.Path, "/")
		service, path, _ := strings.Cut(trimmed, "/")

		m.mu.Lock()
		m.hits = append(m.hits, service+" "+r.Method+" /"+path)
		m.mu.Unlock()

		code, body := 200, map[string]interface{}{}
		if handler != nil {
			if c, b := handler(service, r.Method, "/"+path); c != 0 {
				code, body = c, b
			}
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(code)
		_ = json.NewEncoder(w).Encode(body)
	}))
	t.Cleanup(m.srv.Close)
	return m
}

// servicesHit returns the distinct services that received at least one request.
func (m *fakeMesh) servicesHit() map[string]int {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := map[string]int{}
	for _, h := range m.hits {
		svc, _, _ := strings.Cut(h, " ")
		out[svc]++
	}
	return out
}

func (m *fakeMesh) hitsFor(service string) []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []string
	for _, h := range m.hits {
		if strings.HasPrefix(h, service+" ") {
			out = append(out, h)
		}
	}
	return out
}

// newTestProviders wires a Providers against the fake mesh. cfg is mutated to
// point at the fake server, so callers only supply the knobs under test.
func newTestProviders(t *testing.T, m *fakeMesh, cfg *Config) *Providers {
	t.Helper()
	cfg.Target.BaseURLTemplate = m.srv.URL + "/%s"
	cfg.Target.RequestTimeoutSeconds = 5
	applyDefaults(cfg)
	stats := NewStats()
	return NewProviders(cfg, NewApiClient(cfg, stats), NewRegistry(), stats,
		rand.New(rand.NewSource(1)))
}

// ---------------------------------------------------------------------------
// behavior.p_ancillary_purchase
// ---------------------------------------------------------------------------

func ancillaryMeshHandler(service, method, path string) (int, map[string]interface{}) {
	if service != "ancillary-service" {
		return 0, nil
	}
	switch {
	case method == "POST" && path == "/api/v1/ancillary-catalog-items":
		return 201, map[string]interface{}{"catalogItemId": "aci-1", "version": 1}
	case strings.HasSuffix(path, "/publish"):
		return 200, map[string]interface{}{"catalogItemId": "aci-1", "version": 2}
	case method == "POST" && path == "/api/v1/ancillary-offers":
		return 201, map[string]interface{}{"ancillaryOfferId": "aof-1", "offerVersion": 1}
	case strings.HasSuffix(path, "/quote"):
		return 200, map[string]interface{}{"ancillaryOfferId": "aof-1", "offerVersion": 2}
	case strings.HasSuffix(path, "/select"):
		return 201, map[string]interface{}{"ancillaryOrderItemId": "aoi-1"}
	case strings.HasSuffix(path, "/confirm"):
		return 200, map[string]interface{}{"ancillaryOrderItemId": "aoi-1", "status": "CONFIRMED"}
	}
	return 0, nil
}

func TestAncillaryPurchaseKnobDrivesTraffic(t *testing.T) {
	// p = 1.0 -> the branch must fire and hit ancillary-service.
	on := newFakeMesh(t, ancillaryMeshHandler)
	pOn := newTestProviders(t, on, &Config{
		Behavior: map[string]interface{}{"p_ancillary_purchase": 1.0},
	})
	cat, offer, item := MaybePurchaseAncillary(context.Background(), pOn, "ord-1", "tvl-1", "seg-1")
	if cat == "" || offer == "" || item == "" {
		t.Fatalf("p=1.0 produced no ancillary refs: %q %q %q", cat, offer, item)
	}
	if on.servicesHit()["ancillary-service"] == 0 {
		t.Fatal("p=1.0: ancillary-service received no traffic")
	}

	// p = 0.0 -> the branch must not fire. This is the half that makes the
	// test meaningful: without it the test would pass even if the knob were
	// ignored and the branch ran unconditionally.
	off := newFakeMesh(t, ancillaryMeshHandler)
	pOff := newTestProviders(t, off, &Config{
		Behavior: map[string]interface{}{"p_ancillary_purchase": 0.0},
	})
	if cat, _, _ := MaybePurchaseAncillary(context.Background(), pOff, "ord-1", "tvl-1", "seg-1"); cat != "" {
		t.Error("p=0.0: ancillary branch fired anyway")
	}
	if n := off.servicesHit()["ancillary-service"]; n != 0 {
		t.Errorf("p=0.0: ancillary-service got %d requests, want 0", n)
	}
}

func TestAncillaryConfirmIsTwoPhase(t *testing.T) {
	// ancillary-service moves SELECTED -> PENDING_CONFIRMATION -> CONFIRMED,
	// so a single confirm would strand the item and never exercise the
	// terminal transition.
	m := newFakeMesh(t, ancillaryMeshHandler)
	p := newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{"p_ancillary_purchase": 1.0},
	})
	MaybePurchaseAncillary(context.Background(), p, "ord-1", "tvl-1", "seg-1")

	confirms := 0
	for _, h := range m.hitsFor("ancillary-service") {
		if strings.HasSuffix(h, "/confirm") {
			confirms++
		}
	}
	if confirms != 2 {
		t.Errorf("got %d confirm calls, want 2 (pending then confirmed)", confirms)
	}
}

// ---------------------------------------------------------------------------
// behavior.p_invoice_after_purchase
// ---------------------------------------------------------------------------

func invoicingMeshHandler(service, method, path string) (int, map[string]interface{}) {
	if service != "invoicing" {
		return 0, nil
	}
	switch {
	case method == "POST" && path == "/api/v1/invoice-titles":
		return 201, map[string]interface{}{"titleId": "itl-1", "version": 1, "status": "ACTIVE"}
	case method == "GET" && strings.HasPrefix(path, "/api/v1/invoice-titles/"):
		return 200, map[string]interface{}{"titleId": "itl-1", "version": 1, "status": "ACTIVE"}
	case method == "POST" && path == "/api/v1/e-invoice-requests":
		return 201, map[string]interface{}{
			"invoiceRequestId": "ivr-1", "eInvoiceId": "inv-1", "status": "ISSUED",
		}
	}
	return 0, nil
}

func testPurchase() *Purchase {
	return &Purchase{Order: "ord-12345678", Account: "acc-1", TotalMinor: 10750}
}

func TestInvoiceAfterPurchaseKnobDrivesTraffic(t *testing.T) {
	on := newFakeMesh(t, invoicingMeshHandler)
	pOn := newTestProviders(t, on, &Config{
		Behavior: map[string]interface{}{"p_invoice_after_purchase": 1.0},
	})
	title, req, inv := MaybeRequestInvoice(context.Background(), pOn, testPurchase())
	if title == "" || req == "" || inv == "" {
		t.Fatalf("p=1.0 produced no invoice refs: %q %q %q", title, req, inv)
	}
	if on.servicesHit()["invoicing"] == 0 {
		t.Fatal("p=1.0: invoicing received no traffic")
	}

	off := newFakeMesh(t, invoicingMeshHandler)
	pOff := newTestProviders(t, off, &Config{
		Behavior: map[string]interface{}{"p_invoice_after_purchase": 0.0},
	})
	if title, _, _ := MaybeRequestInvoice(context.Background(), pOff, testPurchase()); title != "" {
		t.Error("p=0.0: invoice branch fired anyway")
	}
	if n := off.servicesHit()["invoicing"]; n != 0 {
		t.Errorf("p=0.0: invoicing got %d requests, want 0", n)
	}
}

func TestInvoiceTitleIsCachedAndRevalidated(t *testing.T) {
	m := newFakeMesh(t, invoicingMeshHandler)
	p := newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{"p_invoice_after_purchase": 1.0},
	})
	MaybeRequestInvoice(context.Background(), p, testPurchase())
	if got := p.Reg.CachedInvoiceTitle("acc-1"); got != "itl-1" {
		t.Fatalf("title not cached on the registry: %q", got)
	}
	MaybeRequestInvoice(context.Background(), p, testPurchase())

	creates, revalidates := 0, 0
	for _, h := range m.hitsFor("invoicing") {
		switch {
		case h == "invoicing POST /api/v1/invoice-titles":
			creates++
		case strings.HasPrefix(h, "invoicing GET /api/v1/invoice-titles/"):
			revalidates++
		}
	}
	if creates != 1 {
		t.Errorf("created %d titles across two invoices, want 1 (cache unused)", creates)
	}
	if revalidates != 1 {
		t.Errorf("revalidated the cached title %d times, want 1", revalidates)
	}
}

func TestInvoiceSkippedForNonPositiveTotal(t *testing.T) {
	// invoicing rejects amountBasis.totalAmount <= 0 outright, so the branch
	// must not spend a title creation on it.
	m := newFakeMesh(t, invoicingMeshHandler)
	p := newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{"p_invoice_after_purchase": 1.0},
	})
	purchase := testPurchase()
	purchase.TotalMinor = 0
	if title, _, _ := MaybeRequestInvoice(context.Background(), p, purchase); title != "" {
		t.Error("requested an invoice for a zero-amount purchase")
	}
	if n := m.servicesHit()["invoicing"]; n != 0 {
		t.Errorf("invoicing got %d requests for a zero-amount purchase, want 0", n)
	}
}

func TestInvoiceRequestBodyIsAcceptableToInvoicing(t *testing.T) {
	// Pin the fields invoicing's RequestEInvoiceCommand requires; a missing
	// one is a 422 in the cluster but would be invisible to a mesh fake that
	// accepts anything.
	var body map[string]interface{}
	m := newFakeMesh(t, invoicingMeshHandler)
	m.srv.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/e-invoice-requests") {
			_ = json.NewDecoder(r.Body).Decode(&body)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(201)
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"titleId": "itl-1", "version": 1, "status": "ACTIVE",
			"invoiceRequestId": "ivr-1", "eInvoiceId": "inv-1",
		})
	})
	p := newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{"p_invoice_after_purchase": 1.0},
	})
	MaybeRequestInvoice(context.Background(), p, testPurchase())

	for _, k := range []string{"accountId", "orderId", "titleId", "titleVersion",
		"invoiceScope", "amountBasis"} {
		if _, ok := body[k]; !ok {
			t.Errorf("e-invoice request body is missing required field %q", k)
		}
	}
	basis, _ := body["amountBasis"].(map[string]interface{})
	hash, _ := basis["amountBasisHash"].(string)
	if !strings.HasPrefix(hash, "sha256:") || len(hash) < 20 {
		t.Errorf("amountBasisHash %q is not a usable digest; invoicing rejects an empty one", hash)
	}
	total, _ := basis["totalAmount"].(map[string]interface{})
	if total["minorUnits"] == nil {
		t.Error("amountBasis.totalAmount.minorUnits missing")
	}
}

// ---------------------------------------------------------------------------
// wallet_promotion.*
// ---------------------------------------------------------------------------

func walletMeshHandler(service, method, path string) (int, map[string]interface{}) {
	if service != "wallet-promotion" {
		return 0, nil
	}
	switch {
	case method == "POST" && path == "/api/v1/benefits":
		return 201, map[string]interface{}{
			"benefitId":       "ben-1",
			"availableAmount": map[string]interface{}{"currency": "CNY", "minorUnits": 250},
		}
	case strings.HasSuffix(path, "/reserve"), strings.HasSuffix(path, "/redeem"):
		return 200, map[string]interface{}{"benefitId": "ben-1"}
	}
	return 0, nil
}

func TestWalletPurchaseReserveRedeemKnobDrivesTraffic(t *testing.T) {
	on := newFakeMesh(t, walletMeshHandler)
	pOn := newTestProviders(t, on, &Config{
		Wallet: WalletConfig{PPurchaseReserveRedeem: 1.0, PurchaseBenefitMinorUnits: 100},
	})
	benefit, account := MaybeWalletPurchaseBenefit(context.Background(), pOn, "acc-1")
	if benefit == "" || account == "" {
		t.Fatalf("p=1.0 produced no wallet refs: %q %q", benefit, account)
	}

	// The reserve and redeem transitions are what distinguish this branch from
	// the ops sweep, so assert them specifically rather than just "some
	// wallet traffic happened".
	var reserved, redeemed bool
	for _, h := range on.hitsFor("wallet-promotion") {
		reserved = reserved || strings.HasSuffix(h, "/reserve")
		redeemed = redeemed || strings.HasSuffix(h, "/redeem")
	}
	if !reserved || !redeemed {
		t.Errorf("reserve=%v redeem=%v, want both", reserved, redeemed)
	}

	off := newFakeMesh(t, walletMeshHandler)
	pOff := newTestProviders(t, off, &Config{
		Wallet: WalletConfig{PPurchaseReserveRedeem: -1, PurchaseBenefitMinorUnits: 100},
	})
	if b, _ := MaybeWalletPurchaseBenefit(context.Background(), pOff, "acc-1"); b != "" {
		t.Error("p<=0: wallet branch fired anyway")
	}
	if n := off.servicesHit()["wallet-promotion"]; n != 0 {
		t.Errorf("p<=0: wallet-promotion got %d requests, want 0", n)
	}
}

func TestWalletReserveUsesAvailableAmount(t *testing.T) {
	// wallet-promotion rejects a reservation above the available balance, so
	// the branch must reserve what the benefit actually carries (250 here),
	// not the configured issue amount (100).
	var reserveAmount float64
	m := newFakeMesh(t, walletMeshHandler)
	base := m.srv.Config.Handler
	m.srv.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/reserve") {
			var body map[string]interface{}
			_ = json.NewDecoder(r.Body).Decode(&body)
			if amt, ok := body["amount"].(map[string]interface{}); ok {
				reserveAmount, _ = amt["minorUnits"].(float64)
			}
		}
		base.ServeHTTP(w, r)
	})
	p := newTestProviders(t, m, &Config{
		Wallet: WalletConfig{PPurchaseReserveRedeem: 1.0, PurchaseBenefitMinorUnits: 100},
	})
	MaybeWalletPurchaseBenefit(context.Background(), p, "acc-1")
	if reserveAmount != 250 {
		t.Errorf("reserved %v minor units, want 250 (the benefit's availableAmount)", reserveAmount)
	}
}

func TestWalletDefaultsAreNonZero(t *testing.T) {
	// A probability wired but defaulted to 0 is indistinguishable from one
	// that is not wired at all.
	cfg := &Config{}
	applyDefaults(cfg)
	if cfg.Wallet.PPurchaseReserveRedeem <= 0 {
		t.Error("wallet_promotion.p_purchase_reserve_redeem defaults to 0: branch is dead")
	}
	if cfg.Wallet.PurchaseBenefitMinorUnits <= 0 {
		t.Error("wallet_promotion.purchase_benefit_minor_units defaults to 0: wallet rejects it")
	}
}

// ---------------------------------------------------------------------------
// long_tail.*
// ---------------------------------------------------------------------------

func TestLongTailReadProbeKnobDrivesReads(t *testing.T) {
	refs := ProbeRefs{Order: "ord-1", Account: "acc-1", Offer: "off-1"}

	on := newFakeMesh(t, nil)
	pOn := newTestProviders(t, on, &Config{
		LongTail: map[string]interface{}{"p_read_probe_after_journey": 1.0},
	})
	MaybeReadProbe(context.Background(), pOn, refs)
	if on.servicesHit()["journey-order"] == 0 {
		t.Error("p_read_probe_after_journey=1.0 produced no journey-order reads")
	}
	if on.servicesHit()["offer-management"] == 0 {
		t.Error("p_read_probe_after_journey=1.0 produced no offer-management reads")
	}

	off := newFakeMesh(t, nil)
	pOff := newTestProviders(t, off, &Config{
		LongTail: map[string]interface{}{"p_read_probe_after_journey": 0.0},
	})
	MaybeReadProbe(context.Background(), pOff, refs)
	if len(off.servicesHit()) != 0 {
		t.Errorf("p_read_probe_after_journey=0.0 still issued reads: %v", off.servicesHit())
	}
}

func TestLongTailEnabledFalseDisablesAllProbes(t *testing.T) {
	m := newFakeMesh(t, nil)
	p := newTestProviders(t, m, &Config{
		LongTail: map[string]interface{}{
			"enabled":                    false,
			"p_read_probe_after_journey": 1.0,
		},
	})
	MaybeReadProbe(context.Background(), p, ProbeRefs{Order: "ord-1", Account: "acc-1"})
	if len(m.servicesHit()) != 0 {
		t.Errorf("long_tail.enabled=false still issued reads: %v", m.servicesHit())
	}
}

func TestLongTailAncillaryReadProbeKnobIsSeparate(t *testing.T) {
	// p_ancillary_read_probe gates the ancillary reads independently of
	// p_read_probe_after_journey. With the outer knob on and this one off,
	// other services must still be read but ancillary-service must not be.
	refs := ProbeRefs{
		Order: "ord-1", Account: "acc-1",
		AncillaryCatalog: "aci-1", AncillaryOffer: "aof-1", AncillaryOrderItem: "aoi-1",
	}

	off := newFakeMesh(t, nil)
	pOff := newTestProviders(t, off, &Config{
		LongTail: map[string]interface{}{
			"p_read_probe_after_journey": 1.0,
			"p_ancillary_read_probe":     0.0,
		},
	})
	MaybeReadProbe(context.Background(), pOff, refs)
	if off.servicesHit()["journey-order"] == 0 {
		t.Fatal("outer probe knob did not fire, so this test proves nothing")
	}
	if n := off.servicesHit()["ancillary-service"]; n != 0 {
		t.Errorf("p_ancillary_read_probe=0.0: ancillary-service got %d reads, want 0", n)
	}

	on := newFakeMesh(t, nil)
	pOn := newTestProviders(t, on, &Config{
		LongTail: map[string]interface{}{
			"p_read_probe_after_journey": 1.0,
			"p_ancillary_read_probe":     1.0,
		},
	})
	MaybeReadProbe(context.Background(), pOn, refs)
	if on.servicesHit()["ancillary-service"] == 0 {
		t.Error("p_ancillary_read_probe=1.0: ancillary-service got no reads")
	}
}

func TestLongTailProbesUseRealIDs(t *testing.T) {
	// The whole point of these probes is that they GET ids the system actually
	// minted. Assert the request paths carry the supplied refs rather than
	// anything synthesised.
	m := newFakeMesh(t, nil)
	p := newTestProviders(t, m, &Config{
		LongTail: map[string]interface{}{
			"p_read_probe_after_journey": 1.0,
			"p_ancillary_read_probe":     1.0,
		},
	})
	refs := ProbeRefs{
		Order: "ord-real", Account: "acc-real", Offer: "off-real",
		Entitlement: "ent-real", Invoice: "inv-real", InvoiceRequest: "ivr-real",
		InvoiceTitle: "itl-real", Benefit: "ben-real", WalletAccount: "acc-real",
		AncillaryOrderItem: "aoi-real",
	}
	MaybeReadProbe(context.Background(), p, refs)

	var all string
	for _, svc := range []string{"journey-order", "offer-management", "invoicing",
		"wallet-promotion", "ancillary-service", "entitlement-ticketing"} {
		all += strings.Join(m.hitsFor(svc), "\n") + "\n"
	}

	for _, id := range []string{"ord-real", "off-real", "ivr-real", "inv-real",
		"itl-real", "ben-real", "aoi-real"} {
		if !strings.Contains(all, id) {
			t.Errorf("no probe read back the real id %q", id)
		}
	}
}

func TestProbeRefsFromPurchaseCarriesRegistryRefs(t *testing.T) {
	// The registry must retain the branch-created refs, otherwise probes on a
	// replayed purchase would have nothing real to read.
	purchase := &Purchase{
		Order: "ord-1", Account: "acc-1",
		AncillaryCatalog: "aci-1", AncillaryOffer: "aof-1", AncillaryOrderItem: "aoi-1",
		InvoiceTitle: "itl-1", InvoiceRequest: "ivr-1", Invoice: "inv-1",
		Benefit: "ben-1", WalletAccount: "acc-1",
		Service: "svc-1", OriginPlace: "plc-1", OriginNode: "nod-1",
	}
	refs := probeRefsFromPurchase(purchase)
	for name, got := range map[string]string{
		"AncillaryCatalog": refs.AncillaryCatalog, "AncillaryOffer": refs.AncillaryOffer,
		"AncillaryOrderItem": refs.AncillaryOrderItem, "InvoiceTitle": refs.InvoiceTitle,
		"InvoiceRequest": refs.InvoiceRequest, "Invoice": refs.Invoice,
		"Benefit": refs.Benefit, "WalletAccount": refs.WalletAccount,
		"Service": refs.Service, "Place": refs.Place, "Node": refs.Node,
	} {
		if got == "" {
			t.Errorf("probeRefsFromPurchase dropped %s", name)
		}
	}
}

func TestPurchaseRefsSurviveRegistryRoundTrip(t *testing.T) {
	// Refs are only useful to a later probe if they persist with the state
	// file; an omitted json tag would silently drop them on restart.
	reg := NewRegistry()
	reg.AddPurchase(&Purchase{
		Order: "ord-1", Status: "confirmed",
		AncillaryOrderItem: "aoi-1", Invoice: "inv-1", Benefit: "ben-1",
	})
	path := t.TempDir() + "/state.json"
	reg.Save(path)

	loaded := LoadRegistry(path)
	if len(loaded.Purchases) != 1 {
		t.Fatalf("got %d purchases after reload, want 1", len(loaded.Purchases))
	}
	got := loaded.Purchases[0]
	if got.AncillaryOrderItem != "aoi-1" || got.Invoice != "inv-1" || got.Benefit != "ben-1" {
		t.Errorf("branch refs lost across save/load: %+v", got)
	}
}

// ---------------------------------------------------------------------------
// behavior.p_payment_channel_read_probe
// ---------------------------------------------------------------------------

func TestPaymentChannelReadProbeKnobDrivesTraffic(t *testing.T) {
	on := newFakeMesh(t, nil)
	pOn := newTestProviders(t, on, &Config{
		Behavior: map[string]interface{}{"p_payment_channel_read_probe": 1.0},
		LongTail: map[string]interface{}{"p_read_probe_after_journey": 1.0},
	})
	MaybeReadProbe(context.Background(), pOn, ProbeRefs{Order: "ord-1", Account: "acc-1"})
	if on.servicesHit()["payment-channel"] == 0 {
		t.Error("p_payment_channel_read_probe=1.0 produced no payment-channel reads")
	}

	off := newFakeMesh(t, nil)
	pOff := newTestProviders(t, off, &Config{
		Behavior: map[string]interface{}{"p_payment_channel_read_probe": 0.0},
		LongTail: map[string]interface{}{"p_read_probe_after_journey": 1.0},
	})
	MaybeReadProbe(context.Background(), pOff, ProbeRefs{Order: "ord-1", Account: "acc-1"})
	if off.servicesHit()["journey-order"] == 0 {
		t.Fatal("outer probe knob did not fire, so this test proves nothing")
	}
	if n := off.servicesHit()["payment-channel"]; n != 0 {
		t.Errorf("p_payment_channel_read_probe=0.0: payment-channel got %d reads, want 0", n)
	}
}

func TestPaymentChannelReadProbeHonoursPersonaOverride(t *testing.T) {
	// The key lives under behavior:, so it is read through the persona-merged
	// context. A persona must be able to switch it off.
	m := newFakeMesh(t, nil)
	p := newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{"p_payment_channel_read_probe": 1.0},
		LongTail: map[string]interface{}{"p_read_probe_after_journey": 1.0},
	})
	p.ApplyPersona(&PersonaConfig{
		Overrides: map[string]interface{}{"p_payment_channel_read_probe": 0.0},
	})
	MaybeReadProbe(context.Background(), p, ProbeRefs{Order: "ord-1", Account: "acc-1"})
	if n := m.servicesHit()["payment-channel"]; n != 0 {
		t.Errorf("persona override ignored: payment-channel got %d reads, want 0", n)
	}
}

// ---------------------------------------------------------------------------
// Coverage guard
// ---------------------------------------------------------------------------

func TestAncillaryAndInvoicingAreInTheJourneyMix(t *testing.T) {
	// The regression this whole change exists to close: with the deployed
	// config's own probabilities forced on, a purchase must produce traffic to
	// ancillary-service and invoicing. Exercised through the same entry points
	// JourneyPurchase calls.
	m := newFakeMesh(t, func(service, method, path string) (int, map[string]interface{}) {
		if c, b := ancillaryMeshHandler(service, method, path); c != 0 {
			return c, b
		}
		return invoicingMeshHandler(service, method, path)
	})
	p := newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{
			"p_ancillary_purchase":     1.0,
			"p_invoice_after_purchase": 1.0,
		},
	})

	MaybePurchaseAncillary(context.Background(), p, "ord-1", "tvl-1", "seg-1")
	MaybeRequestInvoice(context.Background(), p, testPurchase())

	hits := m.servicesHit()
	for _, svc := range []string{"ancillary-service", "invoicing"} {
		if hits[svc] == 0 {
			t.Errorf("%s received no customer-journey traffic", svc)
		}
	}
}

func TestDeployedConfigProducesAncillaryAndInvoicingTraffic(t *testing.T) {
	// Same guard, but driven by the ACTUAL deployed ConfigMap rather than a
	// hand-written config: this fails if someone zeroes either knob there.
	cfg, err := LoadConfig(deployedConfigPath)
	if err != nil {
		t.Skipf("deployed config unavailable: %v", err)
	}
	if cfg.BehaviorFloat("p_ancillary_purchase", 0) <= 0 {
		t.Error("deployed p_ancillary_purchase <= 0: ancillary-service gets no journey traffic")
	}
	if cfg.BehaviorFloat("p_invoice_after_purchase", 0) <= 0 {
		t.Error("deployed p_invoice_after_purchase <= 0: invoicing gets no journey traffic")
	}
}
