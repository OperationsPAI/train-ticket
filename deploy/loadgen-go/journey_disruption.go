package main

import (
	"context"
	"fmt"
	"net/url"
	"strings"
)

// disruptionTypeMixDefault is the fallback for behavior.disruption_type_mix.
//
// The keys are raw disruptionType strings from disruption-recovery's
// DISRUPTION_TYPES set (domain.py), each of which normalize_disruption_type maps
// onto one of the four canonical DisruptionType values through
// LEGACY_DISRUPTION_TYPE_MAP. One raw string per canonical type, so every
// canonical type is reported:
//
//	SERVICE_DELAY       -> DELAY
//	SERVICE_CANCELLED   -> CANCELLATION
//	STOP_CHANGED        -> PARTIAL
//	WEATHER             -> FORCE_MAJEURE
//
// Weighted the way an operator sees them: a delay is the everyday case, a
// cancellation is frequent, a changed stop less so, and weather is the rare
// event. CANCELLATION and FORCE_MAJEURE additionally run the mass-disruption
// batch path unconditionally (_process_mass_disruption returns early only for
// the other types below SEVERE severity), so their share sets how often that
// path is exercised.
var disruptionTypeMixDefault = map[string]float64{
	"SERVICE_DELAY": 0.55, "SERVICE_CANCELLED": 0.25,
	"STOP_CHANGED": 0.15, "WEATHER": 0.05,
}

// disruptionOptionMixDefault is the fallback for behavior.disruption_option_mix.
//
// These four are exactly the option set the server builds for every disruption
// this journey reports, whatever its disruptionType. build_option_set
// (domain.py) chooses between three shapes, and the disruptionType alone does
// not pick between them:
//
//   - (WAIT, REACCOMMODATION), when _reaccommodation_from_report returns a
//     mapping. That needs the raw disruptionType MISSED_CONNECTION *and*
//     evidence.sourceSystem TRANSFER_MANAGEMENT *and* reportedBy.actorType
//     SYSTEM. This journey reports as OPERATIONS with sourceSystem ADMIN, so it
//     can never reach this shape and must never configure REACCOMMODATION.
//   - (WAIT,) alone, when wait_only holds: the affected order id ends in "0",
//     or autoRecovery is WAIT. Then requiresUserChoice is false, the server
//     auto-selects WAIT itself and the case comes back RECOVERED, so no choice
//     from this mix is submitted at all.
//   - (WAIT, REFUND, COMPENSATION, MANUAL) otherwise, which is every case this
//     journey gets a choice on.
//
// So one flat mix is correct here rather than a per-type mix: the per-type
// option sets are identical for all four canonical types. MANUAL is offered on
// every one of those cases and was previously unselectable.
var disruptionOptionMixDefault = map[string]float64{
	"WAIT": 0.40, "REFUND": 0.30, "COMPENSATION": 0.20, "MANUAL": 0.10,
}

// JourneyDisruption runs an ops disruption recovery drill.
func JourneyDisruption(ctx context.Context, p *Providers) (string, error) {
	purchase := p.Reg.TakePurchase(p.Rng, "confirmed")
	if purchase == nil {
		return "no_purchase_for_disruption", nil
	}
	// releaseStatus is the registry state the purchase returns to. A recovery
	// option that consumes the order downstream moves it out of "confirmed" so a
	// later journey does not ask post-sales for a second case on it.
	releaseStatus := "confirmed"
	defer func() {
		if purchase.Status == "consumed" {
			p.Reg.ReleasePurchase(purchase, releaseStatus)
		}
	}()

	suffix := strings.ReplaceAll(UUID7(), "-", "")[:12]
	date := purchase.JourneyDate
	if date == "" {
		date = p.DepartureDate()
	}

	disruptionType := WeightedChoice(p.Rng,
		p.CtxMap("disruption_type_mix", disruptionTypeMixDefault))

	_, reported, err := p.API.Request(ctx, "POST", "disruption-recovery", "/api/v1/disruptions",
		map[string]interface{}{
			"disruptionType":       disruptionType,
			"scheduledServiceRef":  "ssch-lg-" + suffix,
			"segmentRef":           purchase.Seg,
			"serviceDate":          date,
			"evidence": map[string]interface{}{
				"evidenceRef":    "ev-lg-" + suffix,
				"sourceSystem":   "ADMIN",
				"sourceRecordId": "lg-" + suffix,
				"summary":        "Loadgen disruption drill",
			},
			"affectedOrderIds": []string{purchase.Order},
			"reportedBy": map[string]interface{}{
				"actorType": "OPERATIONS",
				"actorId":   "loadgen-ops",
			},
			"accountId": purchase.Account,
			"refundScope": map[string]interface{}{
				"orderItemRefs":   []string{purchase.SB},
				"segmentRefs":     []string{purchase.Seg},
				"travelerRefs":    []string{purchase.Traveler},
				"entitlementRefs": []string{purchase.Entitlement},
			},
		}, nil, []int{202}, "disruption-report")
	if err != nil {
		return "", err
	}
	p.Stats.RecordJourney("disruption:type:" + lower(disruptionType))

	incidentMap, _ := reported["incident"].(map[string]interface{})
	incidentID := getString(incidentMap, "incidentId")

	casesRaw, _ := reported["recoveryCases"].([]interface{})
	if len(casesRaw) == 0 {
		return "reported", nil
	}
	caseMap, _ := casesRaw[0].(map[string]interface{})
	caseID := getString(caseMap, "caseId")

	optionSetMap, _ := caseMap["optionSet"].(map[string]interface{})
	optionsRaw, _ := optionSetMap["options"].([]interface{})

	// selectedType is the option the case actually ended on. The statistic below
	// reports this and never the configured choice: a case the server resolved
	// itself took WAIT regardless of what the mix named, and reporting the mix's
	// name for it would be a fabricated measurement.
	var selectedType string

	if getString(caseMap, "status") == "AWAITING_USER_CHOICE" {
		choice := WeightedChoice(p.Rng,
			p.CtxMap("disruption_option_mix", disruptionOptionMixDefault))
		selectedOption := optionByType(optionsRaw, choice)
		if selectedOption == nil {
			// The server is waiting for a choice out of a set that does not
			// contain the configured one. There is no honest option to submit:
			// taking a different one and reporting either name misstates the
			// run. The configuration is wrong about this service, so say so and
			// stop. FailureInternal, because no customer was involved -- the
			// generator asked the wrong question of a healthy server.
			return "", &StepError{Step: "disruption-select",
				Detail: fmt.Sprintf("disruption_option_mix chose %s but case %s offers only %s",
					choice, caseID, strings.Join(optionTypes(optionsRaw), ",")),
				Kind: FailureInternal}
		}
		if _, _, err := p.API.Request(ctx, "POST", "disruption-recovery",
			"/api/v1/recovery-cases/"+url.PathEscape(caseID)+"/select-option",
			map[string]interface{}{
				"optionId": getString(selectedOption, "optionId"),
				"selectedBy": map[string]interface{}{
					"actorType": "USER",
					"actorId":   purchase.Account,
				},
			}, nil, []int{200}, "disruption-select"); err != nil {
			return "", err
		}
		selectedType = choice
		if choice == "REFUND" {
			// The REFUND option opens a post-sales refund case on this order,
			// and post-sales holds one exclusive refund slot per order. Leaving
			// the purchase "confirmed" would let a later refund or disruption
			// journey ask for a second case and take a 409 the generator caused.
			releaseStatus = "refunded"
		}
	} else {
		// requiresUserChoice was false, so the server auto-selected WAIT during
		// the report. Read back which option that was instead of assuming.
		selectedType = optionTypeByID(optionsRaw, getString(caseMap, "selectedOptionId"))
	}

	if selectedType != "" {
		p.Stats.RecordJourney("disruption:option:" + lower(selectedType))
	}
	MaybeReadProbe(ctx, p, ProbeRefs{
		DisruptionIncident: incidentID,
		DisruptionCase:     caseID,
	})
	return lower(getString(caseMap, "status")), nil
}

// optionByType returns the offered recovery option of the given optionType, or
// nil when the set does not contain one.
func optionByType(optionsRaw []interface{}, optionType string) map[string]interface{} {
	for _, raw := range optionsRaw {
		opt, _ := raw.(map[string]interface{})
		if getString(opt, "optionType") == optionType {
			return opt
		}
	}
	return nil
}

// optionTypeByID returns the optionType of the offered option with this
// optionId, or "" when there is no such option.
func optionTypeByID(optionsRaw []interface{}, optionID string) string {
	if optionID == "" {
		return ""
	}
	for _, raw := range optionsRaw {
		opt, _ := raw.(map[string]interface{})
		if getString(opt, "optionId") == optionID {
			return getString(opt, "optionType")
		}
	}
	return ""
}

// optionTypes lists the offered optionTypes, for the error that reports what
// the server actually offered.
func optionTypes(optionsRaw []interface{}) []string {
	types := make([]string, 0, len(optionsRaw))
	for _, raw := range optionsRaw {
		opt, _ := raw.(map[string]interface{})
		if t := getString(opt, "optionType"); t != "" {
			types = append(types, t)
		}
	}
	return types
}
