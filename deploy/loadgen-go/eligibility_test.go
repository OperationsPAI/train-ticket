package main

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

// paymentChannelsAccepted is the set both payment-channel and payment accept.
// Sourced from services/payment-channel/internal/domain/types.go (ValidChannel)
// and ChannelRouter.normalize; anything else is a 400 before a request reaches
// a channel.
var paymentChannelsAccepted = map[string]bool{
	"ALIPAY_SIM": true, "WECHAT_SIM": true, "UNIONPAY_SIM": true,
}

// identityDocumentTypesAccepted is identity-verification's DocumentType enum
// (services/identity-verification/src/identity_verification/domain.py), gated
// at CredentialRecord.register.
var identityDocumentTypesAccepted = map[string]bool{
	"ID_CARD": true, "PASSPORT": true, "HK_MACAU_PERMIT": true,
	"TW_PERMIT": true, "RESIDENCE_PERMIT": true,
}

// travelerProfileDocumentTypesAccepted is traveler-profile's ApiDocumentType.
// Deliberately smaller than identity-verification's set, which is the reason
// the two mixes are separate knobs.
var travelerProfileDocumentTypesAccepted = map[string]bool{
	"ID_CARD": true, "PASSPORT": true, "OTHER": true,
}

// ---------------------------------------------------------------------------
// behavior.payment_channels
// ---------------------------------------------------------------------------

func TestPaymentChannelKnobDrivesTheMix(t *testing.T) {
	p := newTestProviders(t, newFakeMesh(t, nil), &Config{
		Behavior: map[string]interface{}{
			"payment_channels": map[string]interface{}{
				"WECHAT_SIM": 1.0, "ALIPAY_SIM": 0.0, "UNIONPAY_SIM": 0.0,
			},
		},
	})
	for i := 0; i < 50; i++ {
		if got := p.PaymentChannel(); got != "WECHAT_SIM" {
			t.Fatalf("PaymentChannel() = %q with only WECHAT_SIM weighted, want WECHAT_SIM", got)
		}
	}
}

func TestPaymentChannelHonoursPersonaOverride(t *testing.T) {
	// The knob lives under behavior:, so a persona must be able to shift the
	// whole population's channel mix. This is what per-persona differentiation
	// rests on.
	p := newTestProviders(t, newFakeMesh(t, nil), &Config{
		Behavior: map[string]interface{}{
			"payment_channels": map[string]interface{}{"ALIPAY_SIM": 1.0},
		},
	})
	p.ApplyPersona("business", &PersonaConfig{
		Overrides: map[string]interface{}{
			"payment_channels": map[string]interface{}{"UNIONPAY_SIM": 1.0},
		},
	})
	if got := p.PaymentChannel(); got != "UNIONPAY_SIM" {
		t.Errorf("persona override ignored: PaymentChannel() = %q, want UNIONPAY_SIM", got)
	}
}

func TestPaymentChannelFallbackIsSettleable(t *testing.T) {
	// With the knob absent every draw must still be a channel the platform can
	// settle. A value outside the three is a 400 from payment, which would be
	// a client-side error manufactured by the generator.
	p := newTestProviders(t, newFakeMesh(t, nil), &Config{})
	for i := 0; i < 200; i++ {
		if got := p.PaymentChannel(); !paymentChannelsAccepted[got] {
			t.Fatalf("PaymentChannel() = %q, which payment-channel rejects", got)
		}
	}
}

func TestDeployedPaymentChannelsAreAllSettleable(t *testing.T) {
	// Every channel the deployed config names, globally and in every persona,
	// must be one of the three. A typo here is a purchase that dies at capture
	// and a booking saga that ends in "payment failed".
	cfg := requireDeployedConfig(t)
	check := func(where string, mix map[string]float64) {
		if len(mix) == 0 {
			return
		}
		var total float64
		for channel, weight := range mix {
			if !paymentChannelsAccepted[channel] {
				t.Errorf("%s names payment channel %q, which payment rejects with 400", where, channel)
			}
			total += weight
		}
		if total <= 0 {
			t.Errorf("%s payment_channels weights sum to %v: WeightedChoice has nothing to pick", where, total)
		}
	}
	check("behavior", cfg.BehaviorMap("payment_channels", nil))
	if len(cfg.BehaviorMap("payment_channels", nil)) == 0 {
		t.Error("behavior.payment_channels is absent: every customer pays the same way")
	}
	for name, persona := range cfg.Personas {
		mix, ok := personaFloatMap(persona, "payment_channels")
		if !ok {
			t.Errorf("persona %q does not override payment_channels: its payment mix "+
				"is indistinguishable from every other persona's", name)
			continue
		}
		check("persona "+name, mix)
	}
}

func TestDeployedPersonasDifferInPaymentMix(t *testing.T) {
	// Three personas that override the knob to the SAME weights are not
	// differentiated; the override would be decoration.
	cfg := requireDeployedConfig(t)
	seen := map[string]string{}
	for name, persona := range cfg.Personas {
		mix, ok := personaFloatMap(persona, "payment_channels")
		if !ok {
			continue
		}
		key := ""
		for _, channel := range []string{"ALIPAY_SIM", "WECHAT_SIM", "UNIONPAY_SIM"} {
			key += channel + ":" + formatWeight(mix[channel]) + ";"
		}
		if other, dup := seen[key]; dup {
			t.Errorf("personas %q and %q have identical payment_channels weights", other, name)
		}
		seen[key] = name
	}
}

// ---------------------------------------------------------------------------
// behavior.identity_document_types
// ---------------------------------------------------------------------------

func identityMeshHandler(service, method, path string) (int, map[string]interface{}) {
	if service != "identity-verification" {
		return 0, nil
	}
	switch {
	case method == "POST" && strings.HasSuffix(path, "/credentials"):
		return 201, map[string]interface{}{"credentialRecordId": "crd-1", "status": "VERIFIED"}
	case method == "POST" && strings.HasSuffix(path, "/verification-cases"):
		return 201, map[string]interface{}{"verificationCaseId": "ivc-1", "status": "PASSED"}
	case method == "POST" && strings.HasSuffix(path, "/eligibility-certificates"):
		return 201, map[string]interface{}{"eligibilityCertificateId": "elc-1", "status": "ACTIVE"}
	}
	return 0, nil
}

func TestIdentityDocumentTypeKnobReachesTheCredential(t *testing.T) {
	var credential map[string]interface{}
	var mu sync.Mutex
	m := newFakeMesh(t, identityMeshHandler)
	base := m.srv.Config.Handler
	m.srv.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/credentials") {
			var body map[string]interface{}
			_ = json.NewDecoder(r.Body).Decode(&body)
			mu.Lock()
			credential = body
			mu.Unlock()
		}
		base.ServeHTTP(w, r)
	})
	p := newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{
			"identity_document_types": map[string]interface{}{"PASSPORT": 1.0},
		},
	})
	if _, err := p.Identity(context.Background(), "tvl-1"); err != nil {
		t.Fatalf("Identity: %v", err)
	}
	mu.Lock()
	defer mu.Unlock()
	if got := credential["documentType"]; got != "PASSPORT" {
		t.Errorf("credential documentType = %v, want PASSPORT: the knob is not read", got)
	}
}

func TestIdentityFingerprintFollowsTheDocumentType(t *testing.T) {
	// identity-verification derives the credential's materialFingerprint from
	// seven fields including the document type, and refuses a verification case
	// whose fingerprint does not match (start_verification_case in
	// .../identity_verification/application/service.py). A document type woven
	// into the body but not into the fingerprint would 422 every verification.
	var credential, verification map[string]interface{}
	var mu sync.Mutex
	m := newFakeMesh(t, identityMeshHandler)
	base := m.srv.Config.Handler
	m.srv.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]interface{}
		if strings.HasSuffix(r.URL.Path, "/credentials") ||
			strings.HasSuffix(r.URL.Path, "/verification-cases") {
			_ = json.NewDecoder(r.Body).Decode(&body)
		}
		mu.Lock()
		if strings.HasSuffix(r.URL.Path, "/credentials") {
			credential = body
		} else if strings.HasSuffix(r.URL.Path, "/verification-cases") {
			verification = body
		}
		mu.Unlock()
		base.ServeHTTP(w, r)
	})
	p := newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{
			"identity_document_types": map[string]interface{}{"TW_PERMIT": 1.0},
		},
	})
	if _, err := p.Identity(context.Background(), "tvl-1"); err != nil {
		t.Fatalf("Identity: %v", err)
	}

	mu.Lock()
	documentType, _ := credential["documentType"].(string)
	documentHash, _ := credential["documentHash"].(string)
	validUntil, _ := credential["validUntil"].(string)
	nameHash, _ := credential["canonicalNameHash"].(string)
	snapshot, _ := credential["profileSnapshotVersion"].(string)
	sent, _ := verification["materialFingerprint"].(string)
	mu.Unlock()

	want := sha256Hex(strings.Join(
		[]string{nameHash, documentType, documentHash, "", validUntil, "", snapshot}, "|"))
	if sent != want {
		t.Errorf("materialFingerprint = %q, want %q: the fingerprint does not carry "+
			"documentType %q, so identity-verification would reject the case",
			sent, want, documentType)
	}
}

func TestIdentityDocumentTypeFallbackIsAccepted(t *testing.T) {
	p := newTestProviders(t, newFakeMesh(t, nil), &Config{})
	for i := 0; i < 200; i++ {
		if got := p.IdentityDocumentType(); !identityDocumentTypesAccepted[got] {
			t.Fatalf("IdentityDocumentType() = %q, which identity-verification rejects", got)
		}
	}
}

func TestDeployedIdentityDocumentTypesAreAccepted(t *testing.T) {
	cfg := requireDeployedConfig(t)
	mix := cfg.BehaviorMap("identity_document_types", nil)
	if len(mix) == 0 {
		t.Fatal("behavior.identity_document_types is absent: every traveler proves " +
			"identity the same way")
	}
	for documentType := range mix {
		if !identityDocumentTypesAccepted[documentType] {
			t.Errorf("identity_document_types names %q, which identity-verification "+
				"rejects (documentType is invalid)", documentType)
		}
	}
	// The value that makes this knob worth having: at least one type beyond
	// ID_CARD must carry weight, otherwise the other four branches stay unrun.
	var beyondIDCard float64
	for documentType, weight := range mix {
		if documentType != "ID_CARD" {
			beyondIDCard += weight
		}
	}
	if beyondIDCard <= 0 {
		t.Error("identity_document_types weights everything on ID_CARD: the other four " +
			"DocumentType branches are still never exercised")
	}
}

func TestIdentityDocumentMixIsNotSentToTravelerProfile(t *testing.T) {
	// The site-to-service split, asserted rather than assumed. Every traveler
	// created goes to traveler-profile, which accepts only ID_CARD, PASSPORT
	// and OTHER. With the identity mix forced onto a type traveler-profile
	// rejects, the traveler request must still carry no such value.
	var bodies []map[string]interface{}
	var mu sync.Mutex
	m := newFakeMesh(t, func(service, method, path string) (int, map[string]interface{}) {
		if service == "traveler-profile" && method == "POST" {
			return 201, map[string]interface{}{"travelerId": "tvl-1", "travelerType": "ADULT"}
		}
		return identityMeshHandler(service, method, path)
	})
	base := m.srv.Config.Handler
	m.srv.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "traveler-profile") && r.Method == "POST" {
			var body map[string]interface{}
			_ = json.NewDecoder(r.Body).Decode(&body)
			mu.Lock()
			bodies = append(bodies, body)
			mu.Unlock()
		}
		base.ServeHTTP(w, r)
	})
	p := newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{
			"p_new_traveler":          1.0,
			"identity_document_types": map[string]interface{}{"HK_MACAU_PERMIT": 1.0},
		},
	})
	if _, _, err := p.Traveler(context.Background(), &AccountEntry{AccountID: "acc-1"}, nil); err != nil {
		t.Fatalf("Traveler: %v", err)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(bodies) == 0 {
		t.Fatal("no traveler-profile request was made, so this test proves nothing")
	}
	for _, body := range bodies {
		got, present := body["documentType"]
		if !present {
			continue
		}
		documentType, _ := got.(string)
		if !travelerProfileDocumentTypesAccepted[documentType] {
			t.Errorf("traveler-profile received documentType %q, which its ApiDocumentType "+
				"rejects with 400", documentType)
		}
	}
}

// ---------------------------------------------------------------------------
// behavior.p_eligibility_certificate and bootstrap.eligibility_fares
// ---------------------------------------------------------------------------

func eligibilityMeshHandler(service, method, path string) (int, map[string]interface{}) {
	switch service {
	case "identity-verification":
		return identityMeshHandler(service, method, path)
	case "fare-pricing":
		switch {
		case method == "POST" && path == "/api/v1/fare-rule-sets":
			return 201, map[string]interface{}{"ruleSetId": "frs-1", "status": "DRAFT"}
		case method == "POST" && strings.HasSuffix(path, "/publish"):
			return 200, map[string]interface{}{"ruleSetId": "frs-1", "status": "PUBLISHED"}
		case method == "POST" && path == "/api/v1/fare-quotes":
			return 201, map[string]interface{}{"quoteId": "fq-1", "status": "QUOTED"}
		}
	}
	return 0, nil
}

// eligibilityTestConfig builds a config with the branch on, and clears the
// process-wide publish-once state.
//
// That state is deliberately global in production: the deployed profile is
// open-loop, so a fresh Providers is built per arrival and a per-Providers
// memory would republish the rule set on every purchase. Each test therefore
// has to start from a clean one, or it would observe a rule set some earlier
// test already published.
func eligibilityTestConfig(probability float64) *Config {
	eligibilityRuleSets = sync.Map{}
	cfg := &Config{
		Behavior: map[string]interface{}{"p_eligibility_certificate": probability},
	}
	cfg.Bootstrap.EligibilityFares = EligibilityFaresConfig{Enabled: true}
	return cfg
}

func TestEligibilityCertificateKnobDrivesTraffic(t *testing.T) {
	// p = 1.0 -> a STUDENT traveler registers a certificate and the purchase
	// gets its own product code back.
	on := newFakeMesh(t, eligibilityMeshHandler)
	pOn := newTestProviders(t, on, eligibilityTestConfig(1.0))
	productCode := MaybeRegisterEligibility(context.Background(), pOn,
		"tvl-1", "STUDENT", "crd-1", "WEB", "2026-10-15")
	if productCode == "" {
		t.Fatal("p=1.0 for a STUDENT traveler produced no product code")
	}
	var registered bool
	for _, hit := range on.hitsFor("identity-verification") {
		registered = registered || strings.HasSuffix(hit, "/eligibility-certificates")
	}
	if !registered {
		t.Error("no certificate was posted to identity-verification/eligibility-certificates")
	}

	// p = 0.0 -> the branch must not fire. Without this half the test would
	// pass even if the knob were ignored entirely.
	off := newFakeMesh(t, eligibilityMeshHandler)
	pOff := newTestProviders(t, off, eligibilityTestConfig(0.0))
	if got := MaybeRegisterEligibility(context.Background(), pOff,
		"tvl-1", "STUDENT", "crd-1", "WEB", "2026-10-15"); got != "" {
		t.Errorf("p=0.0 returned product code %q: the branch fired anyway", got)
	}
	if n := off.servicesHit()["identity-verification"]; n != 0 {
		t.Errorf("p=0.0: identity-verification got %d requests, want 0", n)
	}
}

func TestEligibilityCertificateMatchesTheTravelerType(t *testing.T) {
	// A certificate for a type the traveler is not would be fake data. Only
	// the three traveler types with a real concession may produce one, and each
	// must produce the matching eligibility type.
	for travelerType, wantEligibility := range map[string]string{
		"STUDENT":  "STUDENT",
		"CHILD":    "CHILD",
		"MILITARY": "MILITARY_DISABLED",
	} {
		var body map[string]interface{}
		var mu sync.Mutex
		m := newFakeMesh(t, eligibilityMeshHandler)
		base := m.srv.Config.Handler
		m.srv.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if strings.HasSuffix(r.URL.Path, "/eligibility-certificates") {
				var decoded map[string]interface{}
				_ = json.NewDecoder(r.Body).Decode(&decoded)
				mu.Lock()
				body = decoded
				mu.Unlock()
			}
			base.ServeHTTP(w, r)
		})
		p := newTestProviders(t, m, eligibilityTestConfig(1.0))
		if got := MaybeRegisterEligibility(context.Background(), p,
			"tvl-1", travelerType, "crd-1", "WEB", "2026-10-15"); got == "" {
			t.Fatalf("traveler type %s produced no certificate", travelerType)
		}
		mu.Lock()
		got := body["eligibilityType"]
		mu.Unlock()
		if got != wantEligibility {
			t.Errorf("traveler type %s registered eligibilityType %v, want %s",
				travelerType, got, wantEligibility)
		}
	}

	// A traveler type with no concession must register nothing, even at p=1.0.
	for _, travelerType := range []string{"ADULT", "SENIOR", "INFANT"} {
		m := newFakeMesh(t, eligibilityMeshHandler)
		p := newTestProviders(t, m, eligibilityTestConfig(1.0))
		if got := MaybeRegisterEligibility(context.Background(), p,
			"tvl-1", travelerType, "crd-1", "WEB", "2026-10-15"); got != "" {
			t.Errorf("traveler type %s got product code %q: that certificate would be "+
				"an entitlement the traveler does not have", travelerType, got)
		}
		if n := m.servicesHit()["identity-verification"]; n != 0 {
			t.Errorf("traveler type %s posted %d identity requests, want 0", travelerType, n)
		}
	}
}

func TestEligibilityCertificateAlwaysCarriesACredential(t *testing.T) {
	// identity-verification refuses a certificate carrying neither a credential
	// nor an identity cluster, and refuses an unverified one
	// (register_certificate). A reused traveler skips the journey's identity
	// step, so no credential id reaches this call; the branch must register one
	// rather than post a certificate the service would refuse.
	var body map[string]interface{}
	var mu sync.Mutex
	m := newFakeMesh(t, eligibilityMeshHandler)
	base := m.srv.Config.Handler
	m.srv.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/eligibility-certificates") {
			var decoded map[string]interface{}
			_ = json.NewDecoder(r.Body).Decode(&decoded)
			mu.Lock()
			body = decoded
			mu.Unlock()
		}
		base.ServeHTTP(w, r)
	})
	p := newTestProviders(t, m, eligibilityTestConfig(1.0))
	if got := MaybeRegisterEligibility(context.Background(), p,
		"tvl-1", "STUDENT", "", "WEB", "2026-10-15"); got == "" {
		t.Fatal("a reused traveler with no credential id registered no certificate")
	}

	var credentials int
	for _, hit := range m.hitsFor("identity-verification") {
		if strings.HasSuffix(hit, "/credentials") {
			credentials++
		}
	}
	if credentials != 1 {
		t.Errorf("registered %d credentials for a traveler that had none, want 1", credentials)
	}
	mu.Lock()
	defer mu.Unlock()
	if got, _ := body["credentialRecordId"].(string); got == "" {
		t.Error("the certificate carried no credentialRecordId, which " +
			"identity-verification refuses outright")
	}
}

func TestEligibilityReusesAnExistingCredential(t *testing.T) {
	// When the journey already registered one, the branch must not register a
	// second: that would be a document the traveler did not present.
	m := newFakeMesh(t, eligibilityMeshHandler)
	p := newTestProviders(t, m, eligibilityTestConfig(1.0))
	if got := MaybeRegisterEligibility(context.Background(), p,
		"tvl-1", "STUDENT", "crd-existing", "WEB", "2026-10-15"); got == "" {
		t.Fatal("no certificate was registered")
	}
	for _, hit := range m.hitsFor("identity-verification") {
		if strings.HasSuffix(hit, "/credentials") {
			t.Error("registered a second credential although one was supplied")
		}
	}
}

func TestEligibilityCertificateCoversTheJourneyDate(t *testing.T) {
	// fare-pricing checks the certificate against the JOURNEY date, not against
	// today (is_active_for in fare-pricing/domain.py), and the deployed booking
	// window reaches 45 days out. A validity window that ends before then would
	// register a certificate that silently discounts nothing.
	var body map[string]interface{}
	var mu sync.Mutex
	m := newFakeMesh(t, eligibilityMeshHandler)
	base := m.srv.Config.Handler
	m.srv.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/eligibility-certificates") {
			var decoded map[string]interface{}
			_ = json.NewDecoder(r.Body).Decode(&decoded)
			mu.Lock()
			body = decoded
			mu.Unlock()
		}
		base.ServeHTTP(w, r)
	})
	p := newTestProviders(t, m, eligibilityTestConfig(1.0))
	MaybeRegisterEligibility(context.Background(), p, "tvl-1", "STUDENT", "crd-1",
		"WEB", "2026-10-15")

	mu.Lock()
	defer mu.Unlock()
	validFrom, _ := body["validFrom"].(string)
	validUntil, _ := body["validUntil"].(string)
	if validFrom == "" || validUntil == "" {
		t.Fatal("the certificate carried no validity window")
	}
	if !(validFrom < validUntil) {
		t.Errorf("validFrom %q is not before validUntil %q: identity-verification "+
			"rejects that outright", validFrom, validUntil)
	}
	// The window has to start in the past, so a journey booked for tomorrow is
	// inside it and no midnight rollover can put the journey date outside.
	if validFrom >= NowISO() {
		t.Errorf("validFrom %q is not in the past: a journey today or tomorrow falls "+
			"outside the window", validFrom)
	}
	productCodes, _ := body["applicableProductCodes"].([]interface{})
	var carriesProduct bool
	for _, code := range productCodes {
		if code == p.Cfg.Bootstrap.EligibilityFares.ProductCode {
			carriesProduct = true
		}
	}
	if !carriesProduct {
		t.Errorf("applicableProductCodes %v omits the quote's product code %q, so "+
			"fare-pricing would not treat the certificate as active",
			productCodes, p.Cfg.Bootstrap.EligibilityFares.ProductCode)
	}
}

func TestEligibilityRefusesAJourneyBeyondTheValidityWindow(t *testing.T) {
	// A journey after the certificate expires would quote at full fare under a
	// product code nothing else uses, which reads as a discount that silently
	// failed. The branch must decline instead.
	m := newFakeMesh(t, eligibilityMeshHandler)
	cfg := eligibilityTestConfig(1.0)
	cfg.Bootstrap.EligibilityFares.ValidityDays = 30
	applyEligibilityFareDefaults(&cfg.Bootstrap.EligibilityFares)
	p := newTestProviders(t, m, cfg)

	far := time.Now().UTC().Add(400 * 24 * time.Hour).Format("2006-01-02")
	if got := MaybeRegisterEligibility(context.Background(), p, "tvl-1", "STUDENT",
		"crd-1", "WEB", far); got != "" {
		t.Errorf("got product code %q for a journey on %s, past the 30-day validity",
			got, far)
	}
	for _, hit := range m.hitsFor("identity-verification") {
		if strings.HasSuffix(hit, "/eligibility-certificates") {
			t.Error("registered a certificate that expires before the journey")
		}
	}

	// A journey inside the window must still be certified, otherwise the guard
	// above would be indistinguishable from the branch being off.
	near := time.Now().UTC().Add(10 * 24 * time.Hour).Format("2006-01-02")
	if got := MaybeRegisterEligibility(context.Background(), p, "tvl-1", "STUDENT",
		"crd-1", "WEB", near); got == "" {
		t.Errorf("a journey on %s, inside the 30-day validity, was refused", near)
	}
}

func TestEligibilityRuleSetCarriesADiscountPerType(t *testing.T) {
	// The mechanism the whole branch depends on: fare-pricing derives the
	// eligibility types a quote may discount from the rule set's `discount`
	// rules and their explanation.parameters.eligibilityType
	// (_active_discount_types / _discount_allowed). A rule set without those
	// rules cannot discount anything, however many certificates exist.
	var body map[string]interface{}
	var mu sync.Mutex
	m := newFakeMesh(t, eligibilityMeshHandler)
	base := m.srv.Config.Handler
	m.srv.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" && strings.HasSuffix(r.URL.Path, "/fare-rule-sets") {
			var decoded map[string]interface{}
			_ = json.NewDecoder(r.Body).Decode(&decoded)
			mu.Lock()
			body = decoded
			mu.Unlock()
		}
		base.ServeHTTP(w, r)
	})
	cfg := eligibilityTestConfig(1.0)
	p := newTestProviders(t, m, cfg)
	if !EnsureEligibilityRuleSet(context.Background(), p, "WEB") {
		t.Fatal("EnsureEligibilityRuleSet reported failure")
	}

	mu.Lock()
	defer mu.Unlock()
	rules, _ := body["rules"].([]interface{})
	if len(rules) == 0 {
		t.Fatal("the published rule set carried no rules")
	}
	discountTypes := map[string]bool{}
	kinds := map[string]bool{}
	for _, raw := range rules {
		rule, _ := raw.(map[string]interface{})
		kind, _ := rule["kind"].(string)
		kinds[kind] = true
		if kind != "discount" {
			continue
		}
		explanation, _ := rule["explanation"].(map[string]interface{})
		parameters, _ := explanation["parameters"].(map[string]interface{})
		eligibility, _ := parameters["eligibilityType"].(string)
		if eligibility == "" {
			t.Errorf("discount rule %v names no eligibilityType, so fare-pricing "+
				"applies it unconditionally instead of on a certificate", rule["ruleId"])
			continue
		}
		discountTypes[eligibility] = true
	}
	for eligibility := range p.Cfg.Bootstrap.EligibilityFares.Discounts {
		if !discountTypes[eligibility] {
			t.Errorf("no discount rule for configured eligibility type %q: a certificate "+
				"of that type would discount nothing", eligibility)
		}
	}
	// refund_fee and change_fee must be carried: post-sales prices an
	// adjustment from the rule set the ORIGINAL quote's product code resolves
	// to, and a missing refund_fee yields a FAILED adjustment quote and a zero
	// refund (assess_refund in fare-pricing/domain.py).
	for _, kind := range []string{"base_fare", "refund_fee", "change_fee"} {
		if !kinds[kind] {
			t.Errorf("the eligibility rule set has no %s rule", kind)
		}
	}
}

func TestEligibilityRuleSetIsPublishedOncePerChannel(t *testing.T) {
	// Each publish supersedes the previously published set for the same channel
	// and product code, so republishing per purchase would churn the rule set
	// every in-flight quote resolves against.
	eligibilityRuleSets = sync.Map{}
	m := newFakeMesh(t, eligibilityMeshHandler)
	p := newTestProviders(t, m, eligibilityTestConfig(1.0))
	for i := 0; i < 5; i++ {
		MaybeRegisterEligibility(context.Background(), p, "tvl-1", "STUDENT", "crd-1",
			"WEB", "2026-10-15")
	}
	creates, publishes := 0, 0
	for _, hit := range m.hitsFor("fare-pricing") {
		switch {
		case hit == "fare-pricing POST /api/v1/fare-rule-sets":
			creates++
		case strings.HasSuffix(hit, "/publish"):
			publishes++
		}
	}
	if creates != 1 || publishes != 1 {
		t.Errorf("created %d and published %d rule sets across 5 purchases, want 1 and 1",
			creates, publishes)
	}
}

func TestEligibilityDisabledRegistersNothing(t *testing.T) {
	m := newFakeMesh(t, eligibilityMeshHandler)
	cfg := &Config{Behavior: map[string]interface{}{"p_eligibility_certificate": 1.0}}
	cfg.Bootstrap.EligibilityFares = EligibilityFaresConfig{Enabled: false}
	p := newTestProviders(t, m, cfg)
	if got := MaybeRegisterEligibility(context.Background(), p, "tvl-1", "STUDENT",
		"crd-1", "WEB", "2026-10-15"); got != "" {
		t.Errorf("got product code %q with eligibility_fares disabled, want none", got)
	}
	if len(m.servicesHit()) != 0 {
		t.Errorf("eligibility_fares disabled still issued requests: %v", m.servicesHit())
	}
}

func TestFareQuoteCarriesTheProductCode(t *testing.T) {
	// The link between the certificate and the price. fare-pricing takes no
	// eligibility on the request: productCode is what selects the rule set
	// whose discount rules drive the certificate lookup.
	var bodies []map[string]interface{}
	var mu sync.Mutex
	m := newFakeMesh(t, eligibilityMeshHandler)
	base := m.srv.Config.Handler
	m.srv.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/fare-quotes") {
			var body map[string]interface{}
			_ = json.NewDecoder(r.Body).Decode(&body)
			mu.Lock()
			bodies = append(bodies, body)
			mu.Unlock()
		}
		base.ServeHTTP(w, r)
	})
	p := newTestProviders(t, m, &Config{})
	trip := &SearchResult{Segment: "seg-1", Date: "2026-10-15"}

	if _, err := p.FareQuote(context.Background(), []string{"tvl-1"}, "WEB",
		[]string{"seg-1"}, trip, "rail-eligible"); err != nil {
		t.Fatalf("FareQuote: %v", err)
	}
	mu.Lock()
	first := bodies[0]
	mu.Unlock()
	if got := first["productCode"]; got != "rail-eligible" {
		t.Errorf("productCode = %v, want rail-eligible: the quote would resolve the "+
			"rule set with no discount rule", got)
	}

	// An ordinary purchase must send no productCode at all, leaving
	// fare-pricing on its own rail-standard default. Sending the eligibility
	// product code without a certificate would price at full fare under a rule
	// set nothing else uses.
	if _, err := p.FareQuote(context.Background(), []string{"tvl-1"}, "WEB",
		[]string{"seg-1"}, trip, ""); err != nil {
		t.Fatalf("FareQuote: %v", err)
	}
	mu.Lock()
	second := bodies[1]
	mu.Unlock()
	if _, present := second["productCode"]; present {
		t.Errorf("an ineligible quote sent productCode %v, want it omitted",
			second["productCode"])
	}
}

func TestDeployedEligibilityFaresAreUsable(t *testing.T) {
	cfg := requireDeployedConfig(t)
	ef := cfg.Bootstrap.EligibilityFares
	if !ef.Enabled {
		t.Fatal("bootstrap.eligibility_fares.enabled is false: the " +
			"eligibility-certificate path and the fare discount stay unexercised")
	}
	if ef.ProductCode == "" {
		t.Error("eligibility_fares.product_code did not decode")
	}
	if ef.ProductCode == "rail-standard" {
		t.Error("eligibility_fares.product_code is rail-standard: publishing over the " +
			"rule set every other quote uses would change the price of every purchase")
	}
	if ef.BaseFareMinor <= 0 {
		t.Error("eligibility_fares.base_fare_minor <= 0: every eligible trip would " +
			"price at nothing")
	}
	// Both are read by post-sales through the ORIGINAL quote's product code; a
	// missing refund_fee is a FAILED adjustment quote and a zero refund.
	if ef.RefundFeeMinor <= 0 || ef.ChangeFeeMinor <= 0 {
		t.Error("eligibility_fares refund_fee_minor / change_fee_minor <= 0: a refund " +
			"or change on an eligible purchase would be priced without a managed fee")
	}
	if len(ef.Discounts) == 0 {
		t.Fatal("eligibility_fares.discounts is empty: the rule set carries no discount " +
			"rule, so no certificate can ever change a price")
	}
	accepted := map[string]bool{"STUDENT": true, "CHILD": true, "MILITARY_DISABLED": true}
	for eligibility, amount := range ef.Discounts {
		if !accepted[eligibility] {
			t.Errorf("eligibility_fares.discounts names %q; identity-verification and "+
				"fare-pricing both accept only STUDENT, CHILD and MILITARY_DISABLED",
				eligibility)
		}
		if amount <= 0 {
			t.Errorf("discount for %s is %d: the quoted price would not differ from "+
				"an ineligible one", eligibility, amount)
		}
		if amount >= ef.BaseFareMinor {
			t.Errorf("discount for %s is %d against a base fare of %d: the trip would "+
				"price at or below zero", eligibility, amount, ef.BaseFareMinor)
		}
	}
	if ef.AnnualUsageLimit <= 0 {
		t.Error("eligibility_fares.annual_usage_limit <= 0: identity-verification " +
			"rejects a non-positive limit")
	}
	// The certificate is checked against the journey date, which reaches
	// departure_window.to_days ahead.
	if ef.ValidityDays <= cfg.Bootstrap.DepartureWindow.ToDays {
		t.Errorf("eligibility_fares.validity_days = %d does not cover the booking window "+
			"of %d days: a certificate would expire before the journeys it is for",
			ef.ValidityDays, cfg.Bootstrap.DepartureWindow.ToDays)
	}
}

func TestDeployedEligibilityHasTravelersToCertify(t *testing.T) {
	// The knob and the rule set are useless if no traveler type warrants a
	// certificate, and the certificate must agree with the traveler's type.
	cfg := requireDeployedConfig(t)
	travelerTypes := cfg.BehaviorMap("traveler_types", nil)
	if len(travelerTypes) == 0 {
		t.Fatal("behavior.traveler_types did not decode")
	}
	var eligibleWeight float64
	covered := map[string]bool{}
	for travelerType, weight := range travelerTypes {
		eligibility, ok := EligibilityForTravelerType(travelerType)
		if !ok || weight <= 0 {
			continue
		}
		eligibleWeight += weight
		covered[eligibility] = true
	}
	if eligibleWeight <= 0 {
		t.Error("no traveler_type carries an eligibility, so no certificate is ever " +
			"registered and the discount path stays unexercised")
	}
	// Every discount published must have a traveler type that can claim it,
	// otherwise that discount rule is dead configuration.
	for eligibility := range cfg.Bootstrap.EligibilityFares.Discounts {
		if !covered[eligibility] {
			t.Errorf("a %s discount is published but no traveler_type produces a %s "+
				"traveler, so that rule never applies", eligibility, eligibility)
		}
	}
	if cfg.BehaviorFloat("p_eligibility_certificate", 0) <= 0 {
		t.Error("behavior.p_eligibility_certificate = 0: no purchase registers a " +
			"certificate, so the eligibility aggregate and the discount are both dead")
	}
	if cfg.BehaviorFloat("p_eligibility_certificate", 0) > 1 {
		t.Error("behavior.p_eligibility_certificate > 1: every eligible purchase takes " +
			"the branch, which is what the knob exists to avoid")
	}
}

func TestDeployedPersonasDifferInEligibilityRate(t *testing.T) {
	cfg := requireDeployedConfig(t)
	seen := map[string]string{}
	for name, persona := range cfg.Personas {
		raw, ok := persona.Overrides["p_eligibility_certificate"]
		if !ok {
			t.Errorf("persona %q does not override p_eligibility_certificate", name)
			continue
		}
		value, _ := numberFromAny(raw)
		if value < 0 || value > 1 {
			t.Errorf("persona %q has p_eligibility_certificate %v, outside [0,1]", name, value)
		}
		key := formatWeight(value)
		if other, dup := seen[key]; dup {
			t.Errorf("personas %q and %q have the same p_eligibility_certificate %v",
				other, name, value)
		}
		seen[key] = name
	}
}

// ---------------------------------------------------------------------------
// Config parity
// ---------------------------------------------------------------------------

func TestLocalSampleConfigHasEveryDeployedKnob(t *testing.T) {
	// deploy/loadgen-go/config.yaml is documented as staying in sync key for
	// key with the deployed ConfigMap. A knob added to one and not the other is
	// a local run that silently exercises a different code path.
	deployed := requireDeployedConfig(t)
	local, err := LoadConfig("config.yaml")
	if err != nil {
		t.Fatalf("local sample config does not parse: %v", err)
	}
	for _, key := range []string{"payment_channels", "identity_document_types"} {
		if len(local.BehaviorMap(key, nil)) == 0 {
			t.Errorf("behavior.%s is in the deployed config but not in the local sample", key)
		}
	}
	if local.BehaviorFloat("p_eligibility_certificate", -1) < 0 {
		t.Error("behavior.p_eligibility_certificate is in the deployed config but not " +
			"in the local sample")
	}
	if !local.Bootstrap.EligibilityFares.Enabled {
		t.Error("bootstrap.eligibility_fares is not enabled in the local sample")
	}
	if len(local.Bootstrap.EligibilityFares.Discounts) == 0 {
		t.Error("bootstrap.eligibility_fares.discounts is empty in the local sample")
	}
	// The local sample keeps its own traveler mix, but it still has to produce
	// travelers the discounts apply to.
	for eligibility := range local.Bootstrap.EligibilityFares.Discounts {
		if !deployed.Bootstrap.EligibilityFares.Enabled {
			continue
		}
		if _, published := deployed.Bootstrap.EligibilityFares.Discounts[eligibility]; !published {
			t.Errorf("the local sample discounts %s but the deployed config does not: "+
				"the two profiles exercise different fare rules", eligibility)
		}
	}
}

// personaFloatMap reads a weight map out of a persona's overrides. Persona
// overrides are decoded as interface{}, so the shape has to be narrowed here
// rather than through CtxMap.
func personaFloatMap(persona PersonaConfig, key string) (map[string]float64, bool) {
	raw, ok := persona.Overrides[key]
	if !ok {
		return nil, false
	}
	nested, ok := raw.(map[string]interface{})
	if !ok {
		return nil, false
	}
	out := make(map[string]float64, len(nested))
	for k, v := range nested {
		if n, ok := numberFromAny(v); ok {
			out[k] = n
		}
	}
	return out, true
}

// formatWeight renders a weight as a comparison key, so two personas with the
// same mix collide on it.
func formatWeight(v float64) string {
	return strconv.FormatFloat(v, 'f', -1, 64)
}
