package main

import "context"

// supportChannelsDefault is the fallback for behavior.support_channels.
//
// Deliberately a separate knob from behavior.channels, which is the SALES
// channel. The two are different enumerations owned by different services and
// cannot share one knob:
//
//   - behavior.channels feeds fare-pricing, where the constraint is data. A
//     quote succeeds only for a channel that has a published rule set
//     (`rule_set.channel != channel` in domain.py), and the seeded sets are
//     WEB / web / MOBILE / COUNTER.
//   - this knob feeds customer-service, which enforces a closed code-level
//     enum (app.ts `channels`): APP, WEB, PHONE, IM, EMAIL, IN_APP_MESSAGE,
//     BOT, OPERATOR_CONSOLE. Verified against the resident deployment: all
//     eight return 201, while MOBILE and COUNTER return 400 with
//     `field: channel`.
//
// WEB is the only member of both sets. Drawing the support channel from
// behavior.channels would therefore 400 on every MOBILE and COUNTER draw, which
// is the client-visible error this widening exists to remove.
var supportChannelsDefault = map[string]float64{
	"APP": 0.45, "WEB": 0.25, "PHONE": 0.12,
	"IM": 0.10, "EMAIL": 0.05, "IN_APP_MESSAGE": 0.03,
}

// JourneySupport opens a customer service case about an existing order.
func JourneySupport(ctx context.Context, p *Providers) (string, error) {
	purchase := p.Reg.PickPurchaseForRead(p.Rng)
	if purchase == nil {
		return "no_order_for_support", nil
	}

	channel := WeightedChoice(p.Rng, p.CtxMap("support_channels", supportChannelsDefault))
	_, caseData, err := p.API.Request(ctx, "POST", "customer-service", "/api/v1/support-cases",
		map[string]interface{}{
			"requesterRef":   purchase.Traveler,
			"channel":        channel,
			"classification": "POST_SALES_HELP",
			"priority":       "NORMAL",
			"description":    "Help me with my order",
			"businessReferences": map[string]interface{}{
				"journeyOrderId": purchase.Order,
			},
		}, nil, []int{200, 201}, "support-case")
	if err != nil {
		return "", err
	}

	caseID := getString(caseData, "supportCaseId")
	if caseID == "" {
		caseID = getString(caseData, "caseId")
	}
	if caseID != "" {
		work := NewWorkItem("support")
		work.Case = caseID
		work.Requester = purchase.Traveler
		select {
		case p.Reg.QSupport <- work:
		case <-ctx.Done():
		}
		MaybeReadProbe(ctx, p, ProbeRefs{SupportCase: caseID})
	}
	return "support_case", nil
}
