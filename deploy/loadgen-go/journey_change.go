package main

import "context"

// JourneyChange takes a completed purchase and opens a post-sales change case.
func JourneyChange(ctx context.Context, p *Providers) (string, error) {
	purchase := p.Reg.TakePurchase(p.Rng, "confirmed")
	if purchase == nil {
		return "no_purchase_to_change", nil
	}

	_, err := postSalesCase(ctx, p, purchase, "CHANGE", "SCHEDULE_CHANGE")
	if err != nil {
		p.Reg.ReleasePurchase(purchase, "confirmed")
		return "", err
	}

	p.Reg.ReleasePurchase(purchase, "changed")
	return "changed", nil
}
