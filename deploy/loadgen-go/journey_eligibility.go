package main

import (
	"context"
	"fmt"
	"sync"
	"time"
)

// eligibilityForTravelerType maps a traveler type onto the eligibility a
// traveler of that type actually holds.
//
// The two vocabularies are different and only partly overlap. traveler-profile
// accepts ADULT, CHILD, INFANT, STUDENT, SENIOR and MILITARY
// (services/traveler-profile/src/main/java/com/trainticket/travelerprofile/application/TravelerType.java),
// while both identity-verification and fare-pricing accept exactly STUDENT,
// CHILD and MILITARY_DISABLED
// (EligibilityCertificate.register and ELIGIBILITY_TYPES). A traveler type
// absent from this table warrants no certificate: registering a STUDENT
// certificate for an ADULT would be an entitlement the traveler does not have.
var eligibilityForTravelerType = map[string]string{
	"STUDENT":  "STUDENT",
	"CHILD":    "CHILD",
	"MILITARY": "MILITARY_DISABLED",
}

// EligibilityForTravelerType returns the eligibility type a traveler of this
// type qualifies for, and false when the type warrants none.
func EligibilityForTravelerType(travelerType string) (string, bool) {
	eligibility, ok := eligibilityForTravelerType[travelerType]
	return eligibility, ok
}

// eligibilityRuleSet publishes the discount rule set at most once per process.
//
// Publishing is idempotent in effect rather than in the API: each publish
// supersedes the previously published set for the same channel and product
// code (publish_rule_set in
// services/fare-pricing/src/fare_pricing/application/service.py), so repeating
// it per purchase would churn the rule set that every in-flight quote resolves
// against. once.Do also keeps the publish off the hot path after the first
// eligible purchase.
type eligibilityRuleSet struct {
	once      sync.Once
	published bool
}

var eligibilityRuleSets sync.Map // channel -> *eligibilityRuleSet

// EnsureEligibilityRuleSet publishes the rule set that gives an eligibility
// certificate something to discount, and reports whether it is available.
//
// fare-pricing ships a rail-standard rule set with no discount rule of any
// kind, so against it the eligibility lookup never runs and a certificate
// cannot change a price. This publishes a rule set carrying one discount rule
// per configured eligibility type, each naming its type in
// explanation.parameters.eligibilityType, which is the field fare-pricing
// reads to decide which certificates to look for.
func EnsureEligibilityRuleSet(ctx context.Context, p *Providers, channel string) bool {
	cfg := p.Cfg.Bootstrap.EligibilityFares
	if !cfg.Enabled {
		return false
	}
	value, _ := eligibilityRuleSets.LoadOrStore(channel, &eligibilityRuleSet{})
	state := value.(*eligibilityRuleSet)
	state.once.Do(func() {
		state.published = publishEligibilityRuleSet(ctx, p, channel, cfg)
	})
	return state.published
}

func publishEligibilityRuleSet(ctx context.Context, p *Providers, channel string,
	cfg EligibilityFaresConfig) bool {
	now := time.Now().UTC()
	rules := []jmap{
		{
			"ruleId":      "base-eligible",
			"kind":        "base_fare",
			"amount":      jmap{"currency": p.Currency(), "minorUnits": cfg.BaseFareMinor},
			"explanation": jmap{"code": "fare.base.eligible", "parameters": jmap{"source": "loadgen"}},
			"refundable":  true,
		},
		{
			"ruleId":      "tax-eligible",
			"kind":        "tax",
			"amount":      jmap{"currency": p.Currency(), "minorUnits": cfg.TaxMinor},
			"explanation": jmap{"code": "fare.tax.eligible", "parameters": jmap{"source": "loadgen"}},
			"refundable":  true,
		},
		{
			"ruleId":      "refund-fee-eligible",
			"kind":        "refund_fee",
			"amount":      jmap{"currency": p.Currency(), "minorUnits": cfg.RefundFeeMinor},
			"explanation": jmap{"code": "fare.refund_fee.eligible", "parameters": jmap{"source": "loadgen"}},
			"refundable":  true,
		},
		{
			"ruleId":      "change-fee-eligible",
			"kind":        "change_fee",
			"amount":      jmap{"currency": p.Currency(), "minorUnits": cfg.ChangeFeeMinor},
			"explanation": jmap{"code": "fare.change_fee.eligible", "parameters": jmap{"source": "loadgen"}},
			"refundable":  true,
		},
	}
	// Sorted so the published rule set is byte-identical across processes that
	// read the same config; a Go map iterates in random order.
	for _, eligibility := range sortedKeys(cfg.Discounts) {
		amount := cfg.Discounts[eligibility]
		if amount <= 0 {
			continue
		}
		rules = append(rules, jmap{
			"ruleId": "discount-" + lower(eligibility),
			"kind":   "discount",
			"amount": jmap{"currency": p.Currency(), "minorUnits": amount},
			"explanation": jmap{
				"code": "fare.discount." + lower(eligibility),
				// The field fare-pricing keys the certificate lookup on.
				"parameters": jmap{"eligibilityType": eligibility},
			},
			"refundable": true,
		})
	}

	_, created, err := p.API.Request(ctx, "POST", "fare-pricing", "/api/v1/fare-rule-sets",
		jmap{
			"supplierId":  cfg.SupplierID,
			"contractId":  cfg.ContractID,
			"productCode": cfg.ProductCode,
			"mode":        "rail",
			"channel":     channel,
			"version":     fmt.Sprintf("%s-%s", cfg.VersionPrefix, now.Format("20060102150405")),
			"effectiveWindow": jmap{
				"startsAt": ISO(now.Add(-24 * time.Hour)),
				"endsAt":   ISO(now.Add(time.Duration(cfg.ValidityDays) * 24 * time.Hour)),
			},
			"rules": rules,
		}, nil, []int{200, 201}, "eligibility-rule-set-create")
	if err != nil {
		p.Stats.RecordError("eligibility:rule_set_create")
		return false
	}
	ruleSetID := getString(created, "ruleSetId")
	if ruleSetID == "" {
		p.Stats.RecordError("eligibility:rule_set_no_id")
		return false
	}

	_, _, err = p.API.Request(ctx, "POST", "fare-pricing",
		"/api/v1/fare-rule-sets/"+ruleSetID+"/publish",
		jmap{}, nil, []int{200}, "eligibility-rule-set-publish")
	if err != nil {
		p.Stats.RecordError("eligibility:rule_set_publish")
		return false
	}
	p.Stats.RecordJourney("eligibility:rule_set_published")
	return true
}

// MaybeRegisterEligibility registers an eligibility certificate for a traveler
// whose type warrants one, and returns the product code the fare quote must be
// requested under for the discount to apply.
//
// An empty product code means no certificate is in play and the quote should
// go through the ordinary product. That is the outcome whenever the knob does
// not fire, the traveler's type carries no eligibility, or the certificate
// could not be registered: quoting under the eligibility product code without
// a certificate would price the trip at full fare under a rule set nothing
// else uses, which reads as a discount that silently failed.
//
// A verified credential is required rather than optional. identity-verification
// accepts a certificate against a credential or against an identity cluster,
// refuses one carrying neither, and refuses a credential that is not yet
// VERIFIED (register_certificate in
// services/identity-verification/src/identity_verification/application/service.py).
//
// credentialRecordID is the credential this journey just registered, which is
// empty whenever the traveler was reused and the identity step was therefore
// skipped. In that case one is registered here. The generator does not cache
// credential ids across journeys on purpose: the registry outlives an
// identity-verification reset, and a certificate posted against an id that
// service no longer holds would be a manufactured 404 rather than load.
// Registering a second document for a returning traveler is a real action, and
// the register call is the one that evidences the concession anyway.
func MaybeRegisterEligibility(ctx context.Context, p *Providers, travelerID, travelerType,
	credentialRecordID, channel, journeyDate string) string {
	cfg := p.Cfg.Bootstrap.EligibilityFares
	if !cfg.Enabled {
		return ""
	}
	if !p.Chance("p_eligibility_certificate") {
		return ""
	}
	eligibility, ok := EligibilityForTravelerType(travelerType)
	if !ok {
		return ""
	}
	if cfg.Discounts[eligibility] <= 0 {
		// No discount rule is published for this eligibility, so a certificate
		// for it would change no price.
		return ""
	}
	if !EnsureEligibilityRuleSet(ctx, p, channel) {
		return ""
	}
	if credentialRecordID == "" {
		refs, err := p.Identity(ctx, travelerID)
		if err != nil {
			p.Stats.RecordError("eligibility:credential")
			return ""
		}
		credentialRecordID = refs["identity_credential"]
		if credentialRecordID == "" {
			p.Stats.RecordError("eligibility:credential_no_id")
			return ""
		}
	}

	now := time.Now().UTC()
	// validFrom in the past and validUntil past the journey: the certificate is
	// checked against the JOURNEY date rather than against today (is_active_for
	// in services/fare-pricing/src/fare_pricing/domain.py), and the deployed
	// booking window reaches 45 days out.
	validFrom := now.Add(-24 * time.Hour)
	validUntil := now.Add(time.Duration(cfg.ValidityDays) * 24 * time.Hour)
	// A journey beyond the configured validity would be quoted against a
	// certificate that has expired by the time of travel, which prices at full
	// fare under a product code nothing else uses. Refusing is the honest
	// outcome; extending the window to cover it would misstate how long the
	// concession lasts.
	if journeyDate != "" && journeyDate > validUntil.Format("2006-01-02") {
		p.Stats.RecordError("eligibility:journey_beyond_validity")
		return ""
	}

	_, cert, err := p.API.Request(ctx, "POST", "identity-verification",
		"/api/v1/identity-verification/eligibility-certificates",
		jmap{
			"travelerId":         travelerID,
			"credentialRecordId": credentialRecordID,
			"eligibilityType":    eligibility,
			"validFrom":          ISO(validFrom),
			"validUntil":         ISO(validUntil),
			"policyYear":         now.Format("2006"),
			"policyVersion":      "loadgen-" + lower(eligibility) + "-v1",
			"annualUsageLimit":   cfg.AnnualUsageLimit,
			// Must contain the product code the quote is requested under:
			// fare-pricing rejects a certificate whose applicableProductCodes
			// omit the rule set's product code (is_active_for in
			// services/fare-pricing/src/fare_pricing/domain.py). TRAIN is the
			// product code journey-order's pre-order check uses.
			"applicableProductCodes": []string{cfg.ProductCode, "TRAIN"},
			"certificateHash":        sha256Hex("cert:" + travelerID + ":" + eligibility),
			"evidenceHash":           sha256Hex("evidence:" + travelerID + ":" + eligibility),
		}, nil, []int{200, 201}, "eligibility-certificate")
	if err != nil {
		p.Stats.RecordError("eligibility:certificate")
		return ""
	}
	if getString(cert, "eligibilityCertificateId") == "" {
		p.Stats.RecordError("eligibility:certificate_no_id")
		return ""
	}
	p.Stats.RecordJourney("eligibility:certificate_registered")
	return cfg.ProductCode
}

func sortedKeys(m map[string]int) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	for i := 1; i < len(keys); i++ {
		for j := i; j > 0 && keys[j] < keys[j-1]; j-- {
			keys[j], keys[j-1] = keys[j-1], keys[j]
		}
	}
	return keys
}
