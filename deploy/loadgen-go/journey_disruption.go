package main

import (
	"context"
	"net/url"
	"strings"
)

// JourneyDisruption runs an ops disruption recovery drill.
func JourneyDisruption(ctx context.Context, p *Providers) (string, error) {
	purchase := p.Reg.TakePurchase(p.Rng, "confirmed")
	if purchase == nil {
		return "no_purchase_for_disruption", nil
	}
	defer func() {
		if purchase.Status == "consumed" {
			p.Reg.ReleasePurchase(purchase, "confirmed")
		}
	}()

	suffix := strings.ReplaceAll(UUID7(), "-", "")[:12]
	date := purchase.JourneyDate
	if date == "" {
		date = p.DepartureDate()
	}

	_, reported, err := p.API.Request(ctx, "POST", "disruption-recovery", "/api/v1/disruptions",
		map[string]interface{}{
			"disruptionType":       "SERVICE_DELAY",
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

	incidentMap, _ := reported["incident"].(map[string]interface{})
	incidentID := getString(incidentMap, "incidentId")

	casesRaw, _ := reported["recoveryCases"].([]interface{})
	if len(casesRaw) == 0 {
		p.Reg.ReleasePurchase(purchase, "confirmed")
		return "reported", nil
	}
	caseMap, _ := casesRaw[0].(map[string]interface{})
	caseID := getString(caseMap, "caseId")

	optionMix := p.CtxMap("disruption_option_mix", map[string]float64{
		"WAIT": 0.50, "REFUND": 0.35, "COMPENSATION": 0.15,
	})
	choice := WeightedChoice(p.Rng, optionMix)

	optionSetMap, _ := caseMap["optionSet"].(map[string]interface{})
	optionsRaw, _ := optionSetMap["options"].([]interface{})

	var selectedOption map[string]interface{}
	for _, optRaw := range optionsRaw {
		opt, _ := optRaw.(map[string]interface{})
		if getString(opt, "optionType") == choice {
			selectedOption = opt
			break
		}
	}
	if selectedOption == nil && len(optionsRaw) > 0 {
		selectedOption, _ = optionsRaw[0].(map[string]interface{})
	}

	if selectedOption != nil && getString(caseMap, "status") == "AWAITING_USER_CHOICE" {
		_, _, _ = p.API.Request(ctx, "POST", "disruption-recovery",
			"/api/v1/recovery-cases/"+url.PathEscape(caseID)+"/select-option",
			map[string]interface{}{
				"optionId": getString(selectedOption, "optionId"),
				"selectedBy": map[string]interface{}{
					"actorType": "USER",
					"actorId":   purchase.Account,
				},
			}, nil, []int{200}, "disruption-select")
	}

	p.Stats.RecordJourney("disruption:option:" + lower(choice))
	_ = incidentID
	p.Reg.ReleasePurchase(purchase, "confirmed")
	return lower(getString(caseMap, "status")), nil
}
