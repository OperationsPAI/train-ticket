package main

import "context"

// JourneyBrowse performs search (+ maybe quote) then leaves.
func JourneyBrowse(ctx context.Context, p *Providers) (string, error) {
	entry, err := p.Account(ctx)
	if err != nil {
		return "", err
	}
	tvl, _, err := p.Traveler(ctx, entry, nil)
	if err != nil {
		return "", err
	}
	channels := p.CtxMap("channels", map[string]float64{"WEB": 1.0})
	channel := WeightedChoice(p.Rng, channels)

	found, err := p.AvailableTrain(ctx, []string{tvl}, channel)
	if err != nil {
		return "", err
	}
	Think(ctx, p)
	if p.Chance("p_abandon_after_search") {
		return "browsed", nil
	}
	// Browsing quotes under the ordinary product: it registers no eligibility
	// certificate, so there is none for a discount rule set to apply.
	quote, err := p.FareQuote(ctx, []string{tvl}, channel, []string{found.Segment}, found, "")
	if err != nil {
		return "browsed", nil
	}
	MaybeReadProbe(ctx, p, ProbeRefs{
		Quote:     getString(quote, "quoteId"),
		Itinerary: found.Itinerary,
	})
	return "browsed_with_quote", nil
}
