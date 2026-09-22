package main

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"sync"
	"testing"
)

// The knobs widened here each carry a value set the services accept but the
// generator never sent. A weight map that names a value the service refuses is
// a client-visible error the generator manufactured, which is exactly what
// these guards exist to keep out.

// widenedKnobs pins the value set of each widened knob against the service-side
// enum it has to satisfy. A knob whose configured set drifts outside its enum
// starts producing refusals that look like faults.
var widenedKnobs = []struct {
	knob string
	// allowed is the service-side enum, or the set of values the service has
	// data for. Anything outside it is refused.
	allowed []string
	// required are the values whose absence is the coverage loss this change
	// closed. Their presence is the assertion, not their weight.
	required []string
	why      string
}{
	{
		knob: "traveler_types",
		allowed: []string{"ADULT", "CHILD", "INFANT", "STUDENT", "SENIOR",
			"MILITARY"},
		required: []string{"INFANT", "STUDENT", "MILITARY"},
		why: "traveler-profile TravelerType, enforced by enumValue on create " +
			"and update; an unlisted value is a ValidationException",
	},
	{
		knob: "transfer_contract_mix",
		allowed: []string{"PROTECTED", "SUPPLIER_PROTECTED", "PLATFORM_ASSISTED",
			"SELF_TRANSFER"},
		required: []string{"SUPPLIER_PROTECTED", "PLATFORM_ASSISTED"},
		why: "transfer-management ContractType, enforced by the StrEnum " +
			"construction in register_connection",
	},
	{
		knob:     "post_sales_case_mix",
		allowed:  []string{"REFUND", "CANCELLATION", "COMPENSATION"},
		required: []string{"CANCELLATION", "COMPENSATION"},
		why: "post-sales PostSalesCaseType minus CHANGE, which the change " +
			"journey owns, and minus REBOOK, which post-sales refuses " +
			"unconditionally: decisionFor maps it to a REFUND decision kind and " +
			"recordDecision then requires the kind to equal the case type",
	},
	{
		knob:     "channels",
		allowed:  []string{"WEB", "web", "MOBILE", "COUNTER"},
		required: []string{"MOBILE", "COUNTER"},
		why: "the channels fare-pricing has seeded rule sets for (api.py " +
			"_install_default_rule_sets); a quote for any other channel finds " +
			"no applicable rule set and 400s",
	},
	{
		knob: "support_channels",
		allowed: []string{"APP", "WEB", "PHONE", "IM", "EMAIL",
			"IN_APP_MESSAGE", "BOT", "OPERATOR_CONSOLE"},
		required: []string{"WEB", "PHONE"},
		why: "customer-service's closed channel enum (app.ts); MOBILE and " +
			"COUNTER are refused there, so this cannot share the sales knob",
	},
	{
		knob:     "disruption_option_mix",
		allowed:  []string{"WAIT", "REFUND", "COMPENSATION", "MANUAL"},
		required: []string{"MANUAL"},
		why: "the option set build_option_set offers on every case this drill " +
			"gets a choice on. REACCOMMODATION is excluded on purpose: it is " +
			"offered only for a MISSED_CONNECTION reported by " +
			"TRANSFER_MANAGEMENT as a SYSTEM actor, which this drill is not",
	},
	{
		knob: "disruption_type_mix",
		// disruption-recovery's DISRUPTION_TYPES. report_disruption rejects
		// anything outside it before normalize_disruption_type is reached.
		allowed: []string{"SERVICE_DELAY", "SERVICE_CANCELLED", "SERVICE_SUSPENDED",
			"SAILING_SUSPENDED", "ROAD_CLOSED", "WEATHER", "OPERATION_RESTRICTION",
			"SUPPLIER_FAILURE", "DRIVER_CANCELLED", "DISPATCH_FAILED", "STOP_CHANGED",
			"PORT_CALL_CHANGED", "BATCH_SYSTEM_EVENT", "CONNECTION_MISSED",
			"MISSED_CONNECTION", "DELAY", "CANCELLATION", "PARTIAL", "FORCE_MAJEURE"},
		required: []string{"SERVICE_DELAY"},
		why:      "disruption-recovery DISRUPTION_TYPES, checked in report_disruption",
	},
}

func TestDeployedWidenedKnobsStayInsideTheirServiceEnum(t *testing.T) {
	cfg := requireDeployedConfig(t)
	for _, k := range widenedKnobs {
		mix := cfg.BehaviorMap(k.knob, nil)
		if len(mix) == 0 {
			t.Errorf("behavior.%s did not decode as a weight map", k.knob)
			continue
		}
		allowed := make(map[string]bool, len(k.allowed))
		for _, v := range k.allowed {
			allowed[v] = true
		}
		for value, weight := range mix {
			if !allowed[value] {
				t.Errorf("behavior.%s names %q, which is outside %s. Every draw of "+
					"it is a refusal the generator caused (allowed: %s)",
					k.knob, value, k.why, strings.Join(k.allowed, ", "))
			}
			if weight <= 0 {
				t.Errorf("behavior.%s[%s] = %v: that value is never drawn, so its "+
					"service branch stays dead", k.knob, value, weight)
			}
		}
		for _, value := range k.required {
			if mix[value] <= 0 {
				t.Errorf("behavior.%s dropped %q: the branch it reaches goes back to "+
					"never running", k.knob, value)
			}
		}
	}
}

// TestDeployedDisruptionOptionMixExcludesReaccommodation is separate from the
// allowlist above because it guards the one value that is both a real
// RecoveryOptionType and unreachable from this journey. Adding it would look
// correct against the enum and fail every drill.
func TestDeployedDisruptionOptionMixExcludesReaccommodation(t *testing.T) {
	cfg := requireDeployedConfig(t)
	if cfg.BehaviorMap("disruption_option_mix", nil)["REACCOMMODATION"] > 0 {
		t.Error("disruption_option_mix names REACCOMMODATION, which build_option_set " +
			"offers only for a MISSED_CONNECTION reported by TRANSFER_MANAGEMENT as a " +
			"SYSTEM actor. JourneyDisruption reports as OPERATIONS/ADMIN, so every " +
			"drill would fail on an option the server never offered")
	}
}

// TestDeployedSalesAndSupportChannelsAreDistinctKnobs guards the merge that
// looks like a simplification and is a defect: the two enumerations overlap
// only in WEB.
func TestDeployedSalesAndSupportChannelsAreDistinctKnobs(t *testing.T) {
	cfg := requireDeployedConfig(t)
	sales := cfg.BehaviorMap("channels", nil)
	support := cfg.BehaviorMap("support_channels", nil)
	if len(sales) == 0 || len(support) == 0 {
		t.Fatal("channels / support_channels did not decode")
	}
	// customer-service refuses these two, fare-pricing has rule sets for them.
	for _, salesOnly := range []string{"MOBILE", "COUNTER"} {
		if support[salesOnly] > 0 {
			t.Errorf("support_channels names %q, which customer-service answers with "+
				"400 and field=channel", salesOnly)
		}
	}
	// fare-pricing has no rule set for these, customer-service accepts them.
	for _, supportOnly := range []string{"APP", "PHONE", "IM", "EMAIL"} {
		if sales[supportOnly] > 0 {
			t.Errorf("channels names %q, which has no seeded fare rule set: every "+
				"quote on it fails with 'No applicable fare rule set found'", supportOnly)
		}
	}
}

// ---------------------------------------------------------------------------
// behavior.traveler_types
// ---------------------------------------------------------------------------

func TestTravelerTypeIsDrawnFromTheKnob(t *testing.T) {
	// The knob has to reach the request body. A default baked into the provider
	// would satisfy every assertion about the config file and still send ADULT.
	var sent []string
	m := newFakeMesh(t, nil)
	m.srv.Config.Handler = recordingTravelerHandler(&sent)

	p := newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{
			"p_new_traveler": 1.0,
			"traveler_types": map[string]interface{}{"MILITARY": 1.0},
		},
	})
	entry := p.Reg.AddAccount("acc-1")
	if _, _, err := p.Traveler(context.Background(), entry, nil); err != nil {
		t.Fatalf("Traveler: %v", err)
	}
	if len(sent) != 1 || sent[0] != "MILITARY" {
		t.Errorf("travelerType sent = %v, want [MILITARY]", sent)
	}
}

func TestTravelerTypeDefaultCoversTheWholeEnum(t *testing.T) {
	// With the knob absent the provider falls back to its own map, and that
	// fallback is what a config without the key produces. It must carry all six
	// values rather than the three it used to.
	p := newTestProviders(t, newFakeMesh(t, nil), &Config{})
	mix := p.CtxMap("traveler_types", nil)
	if mix != nil {
		t.Fatalf("traveler_types resolved to %v with no key set", mix)
	}
	var sent []string
	m := newFakeMesh(t, nil)
	m.srv.Config.Handler = recordingTravelerHandler(&sent)
	p = newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{"p_new_traveler": 1.0},
	})
	entry := p.Reg.AddAccount("acc-1")
	seen := map[string]bool{}
	for i := 0; i < 400; i++ {
		if _, _, err := p.Traveler(context.Background(), entry, nil); err != nil {
			t.Fatalf("Traveler: %v", err)
		}
	}
	for _, value := range sent {
		seen[value] = true
	}
	for _, want := range []string{"ADULT", "CHILD", "INFANT", "STUDENT", "SENIOR", "MILITARY"} {
		if !seen[want] {
			t.Errorf("the traveler_types fallback never produced %q in 400 draws", want)
		}
	}
}

// ---------------------------------------------------------------------------
// behavior.post_sales_case_mix
// ---------------------------------------------------------------------------

func TestPostSalesCaseTypeReachesTheRequestAndTheOutcome(t *testing.T) {
	// Each case type must arrive at post-sales as itself and come back as its
	// own outcome word, so the journey rows can be split by what was asked for.
	for _, tc := range []struct {
		caseType string
		outcome  string
	}{
		{"REFUND", "refunded"},
		{"CANCELLATION", "order_cancelled"},
		{"COMPENSATION", "compensated"},
	} {
		var body map[string]interface{}
		m := newFakeMesh(t, nil)
		m.srv.Config.Handler = capturingPostSalesHandler(&body)
		p := newTestProviders(t, m, &Config{
			Behavior: map[string]interface{}{
				"post_sales_case_mix": map[string]interface{}{tc.caseType: 1.0},
			},
		})
		p.Reg.AddPurchase(&Purchase{
			Order: "ord-1", SB: "sb-1", Seg: "seg-1", Traveler: "tvl-1",
			Account: "acc-1", Entitlement: "ent-1", Status: "confirmed",
		})

		outcome, err := JourneyRefund(context.Background(), p)
		if err != nil {
			t.Fatalf("%s: JourneyRefund: %v", tc.caseType, err)
		}
		if got := getString(body, "caseType"); got != tc.caseType {
			t.Errorf("caseType sent = %q, want %q", got, tc.caseType)
		}
		if getString(body, "reasonCode") == "" {
			t.Errorf("%s: reasonCode is blank, which post-sales rejects with @NotBlank",
				tc.caseType)
		}
		if outcome != tc.outcome {
			t.Errorf("%s: outcome = %q, want %q", tc.caseType, outcome, tc.outcome)
		}
		// All three are a completed experience: the customer asked the system to
		// end their booking and it answered in full.
		if got := classifyOutcome(outcome); got != StatusCompleted {
			t.Errorf("%s: outcome %q classifies as %q, want %q",
				tc.caseType, outcome, got, StatusCompleted)
		}
	}
}

func TestPostSalesCaseLeavesNoOrderOpenForASecondExclusiveCase(t *testing.T) {
	// post-sales holds ONE active refund slot per order across REFUND,
	// CANCELLATION, REBOOK and CHANGE, so a purchase left "confirmed" would be
	// picked up again and take a 409 the generator caused.
	for _, caseType := range []string{"REFUND", "CANCELLATION", "COMPENSATION"} {
		var body map[string]interface{}
		m := newFakeMesh(t, nil)
		m.srv.Config.Handler = capturingPostSalesHandler(&body)
		p := newTestProviders(t, m, &Config{
			Behavior: map[string]interface{}{
				"post_sales_case_mix": map[string]interface{}{caseType: 1.0},
			},
		})
		p.Reg.AddPurchase(&Purchase{
			Order: "ord-1", SB: "sb-1", Seg: "seg-1", Traveler: "tvl-1",
			Account: "acc-1", Entitlement: "ent-1", Status: "confirmed",
		})
		if _, err := JourneyRefund(context.Background(), p); err != nil {
			t.Fatalf("%s: JourneyRefund: %v", caseType, err)
		}
		if again := p.Reg.TakePurchase(p.Rng, "confirmed"); again != nil {
			t.Errorf("%s: the order is still 'confirmed' after its case was opened, "+
				"so a later journey asks post-sales for a second exclusive case on it",
				caseType)
		}
	}
}

func TestPostSalesCaseMixExcludesRebook(t *testing.T) {
	// REBOOK is a real PostSalesCaseType and an unconditional 422: decisionFor
	// maps it to DecisionKind.REFUND, and recordDecision requires the kind to
	// equal the case type name, exempting only CANCELLATION.
	cfg := requireDeployedConfig(t)
	if cfg.BehaviorMap("post_sales_case_mix", nil)["REBOOK"] > 0 {
		t.Error("post_sales_case_mix names REBOOK: post-sales refuses every one of " +
			"them with DOMAIN_RULE_VIOLATION on evaluate, and no request shape or " +
			"precondition the generator can establish changes that")
	}
}

// ---------------------------------------------------------------------------
// behavior.disruption_type_mix / disruption_option_mix
// ---------------------------------------------------------------------------

func TestDisruptionTypeIsDrawnFromTheKnob(t *testing.T) {
	var sent disruptionBodies
	m := newFakeMesh(t, nil)
	m.srv.Config.Handler = disruptionHandler(&sent, awaitingChoiceCase(
		"WAIT", "REFUND", "COMPENSATION", "MANUAL"))
	p := newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{
			"disruption_type_mix":   map[string]interface{}{"WEATHER": 1.0},
			"disruption_option_mix": map[string]interface{}{"WAIT": 1.0},
		},
	})
	p.Reg.AddPurchase(confirmedPurchase())

	if _, err := JourneyDisruption(context.Background(), p); err != nil {
		t.Fatalf("JourneyDisruption: %v", err)
	}
	if got := getString(sent.report, "disruptionType"); got != "WEATHER" {
		t.Errorf("disruptionType sent = %q, want WEATHER", got)
	}
	if n := p.Stats.Snapshot().Journeys["disruption:type:weather"]; n != 1 {
		t.Errorf("disruption:type:weather counted %d times, want 1", n)
	}
}

func TestDisruptionStatisticReportsTheOptionActuallySelected(t *testing.T) {
	// The defect this closes: the statistic used to report the CONFIGURED
	// choice. Selecting MANUAL must be counted as MANUAL and nothing else.
	var sent disruptionBodies
	m := newFakeMesh(t, nil)
	m.srv.Config.Handler = disruptionHandler(&sent, awaitingChoiceCase(
		"WAIT", "REFUND", "COMPENSATION", "MANUAL"))
	p := newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{
			"disruption_option_mix": map[string]interface{}{"MANUAL": 1.0},
		},
	})
	p.Reg.AddPurchase(confirmedPurchase())

	if _, err := JourneyDisruption(context.Background(), p); err != nil {
		t.Fatalf("JourneyDisruption: %v", err)
	}
	journeys := p.Stats.Snapshot().Journeys
	if journeys["disruption:option:manual"] != 1 {
		t.Errorf("disruption:option:manual counted %d times, want 1",
			journeys["disruption:option:manual"])
	}
	// The option submitted must be MANUAL's own id, not whichever option came
	// first in the set.
	if got := getString(sent.selected, "optionId"); got != "rop-manual" {
		t.Errorf("submitted optionId = %q, want rop-manual", got)
	}
}

func TestDisruptionStatisticReportsTheServersAutoSelectionNotTheMix(t *testing.T) {
	// When requiresUserChoice is false the server has already auto-selected
	// WAIT. Reporting the configured option for that case would be a fabricated
	// measurement, which is the whole point of the fix.
	var sent disruptionBodies
	m := newFakeMesh(t, nil)
	m.srv.Config.Handler = disruptionHandler(&sent, map[string]interface{}{
		"caseId":           "rcv-1",
		"status":           "RECOVERED",
		"selectedOptionId": "rop-wait",
		"optionSet": map[string]interface{}{"options": []interface{}{
			map[string]interface{}{"optionId": "rop-wait", "optionType": "WAIT"},
		}},
	})
	p := newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{
			"disruption_option_mix": map[string]interface{}{"REFUND": 1.0},
		},
	})
	p.Reg.AddPurchase(confirmedPurchase())

	outcome, err := JourneyDisruption(context.Background(), p)
	if err != nil {
		t.Fatalf("JourneyDisruption: %v", err)
	}
	journeys := p.Stats.Snapshot().Journeys
	if journeys["disruption:option:wait"] != 1 {
		t.Errorf("disruption:option:wait counted %d times, want 1: the server "+
			"auto-selected WAIT", journeys["disruption:option:wait"])
	}
	if journeys["disruption:option:refund"] != 0 {
		t.Error("the configured REFUND was reported for a case the server resolved " +
			"itself as WAIT")
	}
	if outcome != "recovered" {
		t.Errorf("outcome = %q, want recovered", outcome)
	}
	// No choice was submitted, so the select-option endpoint must not be called.
	for _, h := range m.hitsFor("disruption-recovery") {
		if strings.Contains(h, "/select-option") {
			t.Error("submitted a choice for a case that was not AWAITING_USER_CHOICE")
		}
	}
}

func TestDisruptionFailsWhenTheConfiguredOptionWasNotOffered(t *testing.T) {
	// Fast-fail instead of the old fallback, which silently took options[0] and
	// then reported the option it had not taken. A configured option the server
	// does not offer is a wrong configuration, and it has to be visible.
	var sent disruptionBodies
	m := newFakeMesh(t, nil)
	m.srv.Config.Handler = disruptionHandler(&sent,
		awaitingChoiceCase("WAIT", "REACCOMMODATION"))
	p := newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{
			"disruption_option_mix": map[string]interface{}{"COMPENSATION": 1.0},
		},
	})
	p.Reg.AddPurchase(confirmedPurchase())

	_, err := JourneyDisruption(context.Background(), p)
	if err == nil {
		t.Fatal("configuring an option the server did not offer must fail the journey")
	}
	se, ok := err.(*StepError)
	if !ok {
		t.Fatalf("err = %T, want *StepError", err)
	}
	if se.Kind != FailureInternal {
		t.Errorf("failure kind = %q, want %q: no customer was involved, the "+
			"generator asked the wrong question of a healthy server",
			se.Kind, FailureInternal)
	}
	// The message has to name both what was configured and what was offered, or
	// it cannot be acted on.
	for _, want := range []string{"COMPENSATION", "WAIT", "REACCOMMODATION"} {
		if !strings.Contains(se.Detail, want) {
			t.Errorf("detail %q does not mention %q", se.Detail, want)
		}
	}
	// Nothing was submitted: there was no honest option to submit.
	for _, h := range m.hitsFor("disruption-recovery") {
		if strings.Contains(h, "/select-option") {
			t.Error("submitted an option after failing to find the configured one")
		}
	}
	// A visible failure must not also strand the purchase as "consumed".
	if p.Reg.TakePurchase(p.Rng, "confirmed") == nil {
		t.Error("the purchase was left consumed by a failed drill")
	}
}

func TestDisruptionRefundOptionClosesTheOrdersRefundSlot(t *testing.T) {
	// The REFUND option opens a post-sales refund case on the order, and
	// post-sales allows one active refund per order. Leaving the purchase
	// "confirmed" lets a later journey ask for a second and take a 409.
	var sent disruptionBodies
	m := newFakeMesh(t, nil)
	m.srv.Config.Handler = disruptionHandler(&sent, awaitingChoiceCase(
		"WAIT", "REFUND", "COMPENSATION", "MANUAL"))
	p := newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{
			"disruption_option_mix": map[string]interface{}{"REFUND": 1.0},
		},
	})
	p.Reg.AddPurchase(confirmedPurchase())

	if _, err := JourneyDisruption(context.Background(), p); err != nil {
		t.Fatalf("JourneyDisruption: %v", err)
	}
	if again := p.Reg.TakePurchase(p.Rng, "confirmed"); again != nil {
		t.Error("the order is still 'confirmed' after the disruption opened a refund " +
			"case on it, so a later journey asks post-sales for a second one")
	}
}

func TestDisruptionNonRefundOptionLeavesTheOrderReusable(t *testing.T) {
	// WAIT consumes nothing downstream, so the order must go back to the pool.
	var sent disruptionBodies
	m := newFakeMesh(t, nil)
	m.srv.Config.Handler = disruptionHandler(&sent, awaitingChoiceCase(
		"WAIT", "REFUND", "COMPENSATION", "MANUAL"))
	p := newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{
			"disruption_option_mix": map[string]interface{}{"WAIT": 1.0},
		},
	})
	p.Reg.AddPurchase(confirmedPurchase())

	if _, err := JourneyDisruption(context.Background(), p); err != nil {
		t.Fatalf("JourneyDisruption: %v", err)
	}
	if p.Reg.TakePurchase(p.Rng, "confirmed") == nil {
		t.Error("a WAIT drill consumed the order, which nothing downstream did")
	}
}

func confirmedPurchase() *Purchase {
	return &Purchase{
		Order: "ord-1", SB: "sb-1", Seg: "seg-1", Traveler: "tvl-1",
		Account: "acc-1", Entitlement: "ent-1", Status: "confirmed",
		JourneyDate: "2026-10-01",
	}
}

// awaitingChoiceCase builds a recovery case waiting on a user choice, offering
// one option per named optionType with a "rop-<lowercased type>" id.
func awaitingChoiceCase(optionTypes ...string) map[string]interface{} {
	options := make([]interface{}, 0, len(optionTypes))
	for _, optionType := range optionTypes {
		options = append(options, map[string]interface{}{
			"optionId":   "rop-" + lower(optionType),
			"optionType": optionType,
		})
	}
	return map[string]interface{}{
		"caseId":    "rcv-1",
		"status":    "AWAITING_USER_CHOICE",
		"optionSet": map[string]interface{}{"options": options},
	}
}

// disruptionBodies holds the request bodies of the two calls the drill makes.
// They are kept apart because both are asserted on and one would otherwise
// overwrite the other.
type disruptionBodies struct {
	report   map[string]interface{}
	selected map[string]interface{}
}

// disruptionHandler answers the report and the select-option call.
func disruptionHandler(dst *disruptionBodies,
	recoveryCase map[string]interface{}) http.Handler {
	return bodyHandler(func(method, path string,
		body map[string]interface{}) (int, map[string]interface{}) {
		switch {
		case path == "/api/v1/disruptions":
			dst.report = body
			return 202, map[string]interface{}{
				"incident":      map[string]interface{}{"incidentId": "inc-1"},
				"recoveryCases": []interface{}{recoveryCase},
			}
		case strings.HasSuffix(path, "/select-option"):
			dst.selected = body
			return 200, recoveryCase
		default:
			return 200, map[string]interface{}{}
		}
	})
}

// ---------------------------------------------------------------------------
// behavior.support_channels
// ---------------------------------------------------------------------------

func TestSupportChannelIsDrawnFromItsOwnKnob(t *testing.T) {
	var body map[string]interface{}
	m := newFakeMesh(t, nil)
	m.srv.Config.Handler = capturingJSONHandler(&body, 201,
		map[string]interface{}{"supportCaseId": "sc-1"})
	p := newTestProviders(t, m, &Config{
		Behavior: map[string]interface{}{
			// The sales knob is set to a value customer-service refuses. If the
			// support site ever reads it, this test fails.
			"channels":         map[string]interface{}{"COUNTER": 1.0},
			"support_channels": map[string]interface{}{"PHONE": 1.0},
		},
	})
	p.Reg.AddPurchase(&Purchase{Order: "ord-1", Traveler: "tvl-1", Status: "confirmed"})

	if _, err := JourneySupport(context.Background(), p); err != nil {
		t.Fatalf("JourneySupport: %v", err)
	}
	if got := getString(body, "channel"); got != "PHONE" {
		t.Errorf("support case channel = %q, want PHONE (it must not read "+
			"behavior.channels, whose values customer-service rejects)", got)
	}
}

// ---------------------------------------------------------------------------
// behavior.channels
// ---------------------------------------------------------------------------

func TestSalesChannelIsTheSameOnSearchQuoteAndOffer(t *testing.T) {
	// offer-management rejects an order whose channel differs from the offer's
	// (CHANNEL_MISMATCH), so one journey must carry one channel throughout.
	var channels []string
	m := newFakeMesh(t, nil)
	m.srv.Config.Handler = purchaseChannelHandler(&channels)
	p := newTestProviders(t, m, &Config{
		Polling: PollingConfig{Attempts: 1, IntervalSeconds: 0.001},
		Behavior: map[string]interface{}{
			"channels":       map[string]interface{}{"COUNTER": 1.0},
			"p_new_account":  1.0,
			"p_new_traveler": 1.0,
			// The journey blocks on staff nobody is running here, so cut both
			// waits to keep the suite fast. Neither affects which channel is sent.
			"staff_wait_seconds": 0.05,
			"think_time":         map[string]interface{}{"min": 0.0, "max": 0.0},
		},
	})
	// AvailableTrain searches only once it has somewhere to search between, so
	// give it two registered places.
	p.Reg.Places["BJP"] = "plc-bjp"
	p.Reg.Places["SHH"] = "plc-shh"
	// The journey cannot complete without staff, and it does not need to: the
	// search, quote and offer all happen before the first staff wait.
	JourneyPurchase(context.Background(), p)

	if len(channels) < 3 {
		t.Fatalf("only %d channel-bearing requests reached the mesh (%v); the "+
			"search/quote/offer sequence did not run", len(channels), channels)
	}
	for _, got := range channels {
		if got != "COUNTER" {
			t.Errorf("a request carried channel %q, want COUNTER on all of them: %v",
				got, channels)
		}
	}
}

func TestSalesChannelDefaultOnlyNamesChannelsWithRuleSets(t *testing.T) {
	// The provider-level fallback, which is what a config missing the key
	// produces. fare-pricing 400s for a channel with no published rule set.
	seeded := map[string]bool{"WEB": true, "web": true, "MOBILE": true, "COUNTER": true}
	for channel, weight := range salesChannelsDefault {
		if !seeded[channel] {
			t.Errorf("salesChannelsDefault names %q, which fare-pricing has no seeded "+
				"rule set for: every quote on it 400s", channel)
		}
		if weight <= 0 {
			t.Errorf("salesChannelsDefault[%s] = %v: never drawn", channel, weight)
		}
	}
	if len(salesChannelsDefault) < 2 {
		t.Error("salesChannelsDefault has one channel: the fare-pricing " +
			"channel-match branch is exercised with a single value again")
	}
}

// ---------------------------------------------------------------------------
// test mesh handlers
//
// These decode the request body, which the fakeMesh handler signature does not
// pass through, and are therefore installed onto m.srv.Config.Handler directly
// in the same way TestInvoiceRequestBodyIsAcceptableToInvoicing does.
// ---------------------------------------------------------------------------

// bodyHandler builds an http.Handler that decodes each request body and calls
// respond with the request's path, method and decoded body. respond returns the
// status and response body.
func bodyHandler(respond func(method, path string,
	body map[string]interface{}) (int, map[string]interface{})) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]interface{}
		_ = json.NewDecoder(r.Body).Decode(&body)
		// The template points at "<server>/%s", so the service name is the
		// first path segment. Strip it to get the service-relative path.
		trimmed := strings.TrimPrefix(r.URL.Path, "/")
		_, path, _ := strings.Cut(trimmed, "/")
		code, response := respond(r.Method, "/"+path, body)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(code)
		_ = json.NewEncoder(w).Encode(response)
	})
}

type travelerHandler = http.Handler

// recordingTravelerHandler answers a traveler creation, appending each
// requested travelerType to sent.
func recordingTravelerHandler(sent *[]string) travelerHandler {
	var mu sync.Mutex
	return bodyHandler(func(method, path string,
		body map[string]interface{}) (int, map[string]interface{}) {
		if value := getString(body, "travelerType"); value != "" {
			mu.Lock()
			*sent = append(*sent, value)
			mu.Unlock()
		}
		return 201, map[string]interface{}{"travelerId": "tvl-" + UUID7()}
	})
}

// capturingJSONHandler answers everything with one status and body, recording
// the request body of the last call into dst.
func capturingJSONHandler(dst *map[string]interface{}, code int,
	response map[string]interface{}) http.Handler {
	return bodyHandler(func(method, path string,
		body map[string]interface{}) (int, map[string]interface{}) {
		if body != nil {
			*dst = body
		}
		return code, response
	})
}

// capturingPostSalesHandler answers the open/evaluate/approve sequence of a
// post-sales case, recording the open request's body into dst.
func capturingPostSalesHandler(dst *map[string]interface{}) http.Handler {
	return bodyHandler(func(method, path string,
		body map[string]interface{}) (int, map[string]interface{}) {
		if method == "POST" && path == "/api/v1/post-sales-cases" {
			*dst = body
			return 201, map[string]interface{}{"caseId": "psc-1"}
		}
		return 200, map[string]interface{}{"caseId": "psc-1", "status": "APPLIED"}
	})
}

// purchaseChannelHandler answers the pre-payment half of a purchase journey and
// records the channel carried by every request that sends one.
func purchaseChannelHandler(channels *[]string) http.Handler {
	var mu sync.Mutex
	return bodyHandler(func(method, path string,
		body map[string]interface{}) (int, map[string]interface{}) {
		mu.Lock()
		for _, field := range []string{"channel", "channelId"} {
			if value := getString(body, field); value != "" {
				*channels = append(*channels, value)
			}
		}
		mu.Unlock()

		switch {
		case path == "/api/v1/itineraries/search":
			return 200, map[string]interface{}{"itineraries": []interface{}{
				map[string]interface{}{
					"itineraryRef": "itin-1",
					"legs": []interface{}{map[string]interface{}{
						"serviceSegmentRef":  "seg-" + UUID7(),
						"servicePlanRef":     "sp-1",
						"originStopRef":      "nod-1",
						"destinationStopRef": "nod-2",
					}},
				},
			}}
		case path == "/api/v1/fare-quotes":
			return 201, map[string]interface{}{"quoteId": "fq-1"}
		case path == "/api/v1/offers":
			return 201, map[string]interface{}{
				"offerId": "off-1", "offerVersion": 1,
				"total": map[string]interface{}{"currency": "CNY", "minorUnits": 10750},
			}
		case path == "/api/v1/journey-orders":
			return 201, map[string]interface{}{"orderId": "ord-1", "status": "CONFIRMING"}
		default:
			return 200, map[string]interface{}{
				"travelerId": "tvl-" + UUID7(), "accountId": "acc-1",
				"credentialRecordId": "idc-1", "verificationCaseId": "idv-1",
			}
		}
	})
}
