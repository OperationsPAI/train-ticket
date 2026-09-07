package main

import (
	"context"
	"net/url"
	"time"
)

// MaybePurchaseAncillary runs the add-on branch of a purchase: it publishes a
// one-off MEAL catalog item, drafts and quotes an offer against the just-placed
// order, selects it into an order item, then confirms it.
//
// Gated by behavior.p_ancillary_purchase. This is the only customer-journey
// traffic ancillary-service receives, so if this branch does not fire the
// service gets no journey load at all.
//
// The confirm step is issued TWICE on purpose. ancillary-service's order-item
// state machine treats confirm as a two-phase transition:
// SELECTED -> PENDING_CONFIRMATION on the first call, and
// PENDING_CONFIRMATION -> CONFIRMED on the second. A single call would leave
// the item parked in PENDING_CONFIRMATION and never exercise the terminal
// transition. See AncillaryOrderItem.confirm in
// services/ancillary-service/src/domain.ts.
//
// Best-effort: an add-on failure must not fail the purchase that already
// succeeded, so errors are recorded and the branch gives up.
func MaybePurchaseAncillary(ctx context.Context, p *Providers, orderID, travelerRef, segmentRef string) (catalogID, offerID, orderItemID string) {
	if !p.OptionalChance("p_ancillary_purchase", 0.03) {
		return "", "", ""
	}

	suffix := UUID7()
	now := time.Now().UTC()

	_, catalog, err := p.API.Request(ctx, "POST", "ancillary-service",
		"/api/v1/ancillary-catalog-items",
		map[string]interface{}{
			"serviceType":     "MEAL",
			"displayName":     "Loadgen meal " + suffix,
			"attachmentScope": "SEGMENT",
			"modalities":      []string{"TRAIN"},
			"price":           map[string]interface{}{"currency": p.Currency(), "minorUnits": 1200},
			"salesWindow": map[string]interface{}{
				"startAt": ISO(now.Add(-1 * time.Hour)),
				"endAt":   ISO(now.Add(24 * time.Hour)),
			},
			"purchaseCutoffHoursBeforeDeparture": 1,
			"eligibilityRuleVersion":             "min-v1",
			"requiresEntitlementRef":             true,
			"requiresSegmentRef":                 true,
			"fulfillmentMethod":                  "VOUCHER",
		}, nil, []int{201}, "anc-catalog")
	if err != nil {
		p.Stats.RecordError("ancillary:catalog")
		return "", "", ""
	}
	catalogID = getString(catalog, "catalogItemId")
	if catalogID == "" {
		p.Stats.RecordError("ancillary:catalog:no_id")
		return "", "", ""
	}

	// A catalog item must be PUBLISHED before an offer can draft against it.
	_, _, err = p.API.Request(ctx, "POST", "ancillary-service",
		"/api/v1/ancillary-catalog-items/"+url.PathEscape(catalogID)+"/publish",
		map[string]interface{}{
			"approvalRef":     "loadgen",
			"expectedVersion": getInt(catalog, "version", 1),
		}, nil, []int{200}, "anc-publish")
	if err != nil {
		p.Stats.RecordError("ancillary:publish")
		return catalogID, "", ""
	}

	_, draft, err := p.API.Request(ctx, "POST", "ancillary-service",
		"/api/v1/ancillary-offers",
		map[string]interface{}{
			"catalogItemId":  catalogID,
			"journeyOrderId": orderID,
			"travelerRef":    travelerRef,
			"segmentRef":     segmentRef,
			"entitlementRef": "ent-" + suffix,
			"departureAt":    ISO(now.Add(4 * time.Hour)),
			"quantity":       1,
		}, nil, []int{201}, "anc-draft")
	if err != nil {
		p.Stats.RecordError("ancillary:draft")
		return catalogID, "", ""
	}
	draftID := getString(draft, "ancillaryOfferId")
	if draftID == "" {
		p.Stats.RecordError("ancillary:draft:no_id")
		return catalogID, "", ""
	}

	// quote/select/confirm each bump offerVersion, so every step must pass the
	// version returned by the previous one -- a stale expectedVersion is a 409.
	_, quoted, err := p.API.Request(ctx, "POST", "ancillary-service",
		"/api/v1/ancillary-offers/"+url.PathEscape(draftID)+"/quote",
		map[string]interface{}{
			"expectedVersion": getInt(draft, "offerVersion", 1),
			"validitySeconds": 600,
		}, nil, []int{200}, "anc-quote")
	if err != nil {
		p.Stats.RecordError("ancillary:quote")
		return catalogID, draftID, ""
	}
	offerID = getString(quoted, "ancillaryOfferId")
	if offerID == "" {
		offerID = draftID
	}

	_, item, err := p.API.Request(ctx, "POST", "ancillary-service",
		"/api/v1/ancillary-offers/"+url.PathEscape(offerID)+"/select",
		map[string]interface{}{
			"journeyOrderId":  orderID,
			"expectedVersion": getInt(quoted, "offerVersion", 2),
		}, nil, []int{201}, "anc-select")
	if err != nil {
		p.Stats.RecordError("ancillary:select")
		return catalogID, offerID, ""
	}
	itemID := getString(item, "ancillaryOrderItemId")
	if itemID == "" {
		p.Stats.RecordError("ancillary:select:no_id")
		return catalogID, offerID, ""
	}

	// Phase 1: SELECTED -> PENDING_CONFIRMATION.
	_, _, err = p.API.Request(ctx, "POST", "ancillary-service",
		"/api/v1/ancillary-order-items/"+url.PathEscape(itemID)+"/confirm",
		map[string]interface{}{"reasonCode": "LOADGEN"},
		nil, []int{200}, "anc-confirm-pending")
	if err != nil {
		p.Stats.RecordError("ancillary:confirm-pending")
		return catalogID, offerID, itemID
	}

	// Phase 2: PENDING_CONFIRMATION -> CONFIRMED.
	_, confirmed, err := p.API.Request(ctx, "POST", "ancillary-service",
		"/api/v1/ancillary-order-items/"+url.PathEscape(itemID)+"/confirm",
		map[string]interface{}{"confirmationRef": "anc-conf-" + suffix},
		nil, []int{200}, "anc-confirm")
	if err != nil {
		p.Stats.RecordError("ancillary:confirm")
		return catalogID, offerID, itemID
	}
	if got := getString(confirmed, "ancillaryOrderItemId"); got != "" {
		itemID = got
	}

	p.Stats.RecordJourney("ancillary:confirmed")
	return catalogID, offerID, itemID
}
