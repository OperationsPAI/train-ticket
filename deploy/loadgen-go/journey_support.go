package main

import "context"

// JourneySupport opens a customer service case about an existing order.
func JourneySupport(ctx context.Context, p *Providers) (string, error) {
	purchase := p.Reg.PickPurchaseForRead(p.Rng)
	if purchase == nil {
		return "no_order_for_support", nil
	}

	_, caseData, err := p.API.Request(ctx, "POST", "customer-service", "/api/v1/support-cases",
		map[string]interface{}{
			"requesterRef":   purchase.Traveler,
			"channel":        "APP",
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
