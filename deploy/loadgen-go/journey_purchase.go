package main

import (
	"context"
	"fmt"
	"net/url"
	"time"
)

// JourneyPurchase performs the full funnel: account -> traveler -> identity -> search
// -> quote -> offer -> order -> pay -> staff reservation -> staff ticketing.
func JourneyPurchase(ctx context.Context, p *Providers) (string, error) {
	entry, err := p.Account(ctx)
	if err != nil {
		return "", err
	}
	travelers := make([]string, 0, 2)
	tvl, err := p.Traveler(ctx, entry, nil)
	if err != nil {
		return "", err
	}
	travelers = append(travelers, tvl)

	if p.Chance("p_second_traveler") {
		tvl2, err := p.Traveler(ctx, entry, travelers)
		if err == nil {
			travelers = append(travelers, tvl2)
		}
	}

	// Ensure identity verified for all travelers
	for _, t := range travelers {
		if !isVerified(entry, t) {
			_, err := p.Identity(ctx, t)
			if err != nil {
				return "", err
			}
			markVerified(entry, t, p.Reg)
		}
	}

	channels := p.CtxMap("channels", map[string]float64{"WEB": 1.0})
	channel := WeightedChoice(p.Rng, channels)

	found, err := p.AvailableTrain(ctx, travelers, channel)
	if err != nil {
		return "", err
	}
	Think(ctx, p)
	if p.Chance("p_abandon_after_search") {
		return "abandoned", nil
	}

	quote, err := p.FareQuote(ctx, travelers, channel, []string{found.Segment})
	if err != nil {
		return "", err
	}
	Think(ctx, p)
	if p.Chance("p_abandon_after_quote") {
		return "abandoned", nil
	}

	offer, err := p.Offer(ctx, entry.AccountID, channel, found.Itinerary, travelers)
	if err != nil {
		return "", err
	}
	order, err := p.Order(ctx, entry.AccountID, offer, travelers, []string{found.Segment}, found.Date)
	if err != nil {
		return "", err
	}
	orderID := getString(order, "orderId")

	// Risk gate — quick check, don't wait for full saga completion
	status := pollOrderQuick(ctx, p, orderID, 3)
	if status != "" && containsBlock(status) {
		review := NewWorkItem("risk")
		review.Order = orderID
		select {
		case p.Reg.QRisk <- review:
		case <-ctx.Done():
			return "", ctx.Err()
		}
		if err := waitForResult(ctx, review, "risk", p.Cfg.BehaviorFloat("staff_wait_seconds", 90)); err != nil {
			return "", err
		}
		verdict, _ := review.GetResult("risk")
		if verdict != "lifted" {
			return "risk_rejected", nil
		}
	}

	// Reservation is driven by staff
	resv := NewWorkItem("reservation")
	resv.Order = orderID
	resv.Seg = found.Segment
	resv.Traveler = travelers[0]
	select {
	case p.Reg.QReservation <- resv:
	case <-ctx.Done():
		return "", ctx.Err()
	}
	if err := waitForResult(ctx, resv, "sb", p.Cfg.BehaviorFloat("staff_wait_seconds", 90)); err != nil {
		return "", err
	}
	sbVal, _ := resv.GetResult("sb")
	sb, _ := sbVal.(string)

	Think(ctx, p)
	totalMinor := getNestedInt(offer, "total", "minorUnits", 10750)
	intent, err := p.PaymentIntent(ctx, orderID, totalMinor, entry.AccountID)
	if err != nil {
		return "", err
	}
	intentID := getString(intent, "paymentIntentId")

	// Check no-capacity
	if noCapVal, ok := resv.GetResult("no_capacity"); ok && noCapVal == true {
		return handleNoCapacityWaitlist(ctx, p, entry.AccountID, travelers[0],
			found.Segment, intentID, found.Itinerary)
	}

	if p.Chance("p_abandon_before_payment") {
		if p.Chance("p_cancel_payment_intent_on_abandon") {
			p.API.Request(ctx, "POST", "payment",
				"/api/v1/payment-intents/"+url.PathEscape(intentID)+"/cancel",
				map[string]interface{}{"reason": "CUSTOMER_ABANDONED_CHECKOUT"},
				nil, []int{200}, "payment-cancel")
			return "cancelled_payment_intent", nil
		}
		if p.Chance("p_cancel_order_before_payment") {
			p.API.Request(ctx, "POST", "journey-order",
				"/api/v1/journey-orders/"+url.PathEscape(orderID)+"/cancel",
				map[string]interface{}{"reason": "CUSTOMER_CANCELLED_BEFORE_PAYMENT"},
				nil, []int{200}, "order-cancel-before-payment")
			return "cancelled_before_payment", nil
		}
		return "abandoned_before_payment", nil
	}

	var faultSeed string
	if p.Chance("p_payment_channel_missed_seed") {
		faultSeed = "MISSED_ORDER:loadgen"
	}
	if err := p.PaymentCapture(ctx, intentID, faultSeed); err != nil {
		return "", err
	}

	// Ticket issuing via staff
	tick := NewWorkItem("ticketing")
	tick.Order = orderID
	tick.SB = sb
	tick.Traveler = travelers[0]
	tick.Seg = found.Segment
	select {
	case p.Reg.QTicketing <- tick:
	case <-ctx.Done():
		return "", ctx.Err()
	}
	if err := waitForResult(ctx, tick, "entitlement", p.Cfg.BehaviorFloat("staff_wait_seconds", 90)); err != nil {
		return "", err
	}
	entVal, _ := tick.GetResult("entitlement")
	ent, _ := entVal.(string)

	final := pollOrder(ctx, p, orderID, map[string]bool{"CONFIRMED": true, "CONFIRMING": true}, false)
	if final != "CONFIRMED" && final != "CONFIRMING" {
		return "", &StepError{Step: "confirm", Detail: fmt.Sprintf("order %s ended %s", orderID, final)}
	}

	purchase := &Purchase{
		Order:         orderID,
		Saga:          getResultString(resv, "saga"),
		SB:            sb,
		Seg:           found.Segment,
		Traveler:      travelers[0],
		Account:       entry.AccountID,
		Entitlement:   ent,
		TotalMinor:    totalMinor,
		Status:        "confirmed",
		Offer:         getString(offer, "offerId"),
		PaymentIntent: intentID,
		Itinerary:     found.Itinerary,
		Quote:         getString(quote, "quoteId"),
		JourneyDate:   found.Date,
	}
	p.Reg.AddPurchase(purchase)
	return "purchased", nil
}

func handleNoCapacityWaitlist(ctx context.Context, p *Providers, account, traveler, segment, paymentIntent, itinerary string) (string, error) {
	if !p.OptionalChance("p_waitlist_on_no_capacity", 0.30) {
		return "no_available_capacity", nil
	}
	if itinerary == "" {
		p.Stats.RecordError("waitlist:missing-itinerary-ref")
		return "no_available_capacity", nil
	}
	minutes := p.CtxFloat("waitlist_deadline_minutes", 30)
	deadline := time.Now().UTC().Add(time.Duration(minutes) * time.Minute)
	intentFp := traveler + ":" + segment

	code, waitlist, err := p.API.Request(ctx, "POST", "waitlist", "/api/v1/waitlist-requests",
		map[string]interface{}{
			"accountId":            account,
			"travelerRef":          traveler,
			"segmentRef":           segment,
			"itineraryRef":         itinerary,
			"paymentGuaranteeRef":  paymentIntent,
			"intentFingerprint":    intentFp,
			"deadline":             deadline.Format("2006-01-02T15:04:05Z"),
		}, nil, []int{200, 201, 409}, "waitlist-create")
	if err != nil {
		return "", err
	}
	if code == 409 {
		p.Stats.RecordJourney("waitlist:conflict")
		return "waitlist_conflict", nil
	}

	waitlistID := getString(waitlist, "waitlistRequestId")
	status := getString(waitlist, "status")
	if status == "" {
		status = "QUEUED"
	}
	p.Reg.AddWaitlist(&WaitlistRef{
		WaitlistRequestID: waitlistID,
		Account:           account,
		Traveler:          traveler,
		Seg:               segment,
		PaymentIntent:     paymentIntent,
		IntentFingerprint: intentFp,
		Status:            status,
	})

	if status == "QUEUED" {
		p.Stats.RecordJourney("waitlist:queued")
	}

	// Optionally cancel
	if (status == "QUEUED" || status == "MATCHING" || status == "SUSPENDED") &&
		p.OptionalChance("p_waitlist_cancel", 0.05) {
		cancelCode, cancelData, _ := p.API.Request(ctx, "POST", "waitlist",
			"/api/v1/waitlist-requests/"+url.PathEscape(waitlistID)+"/cancel",
			map[string]interface{}{"reason": "CUSTOMER_CHANGED_PLANS"},
			nil, []int{200, 409, 412}, "waitlist-cancel")
		if cancelCode == 200 && getString(cancelData, "status") == "CANCELLED" {
			p.Stats.RecordJourney("waitlist:cancelled")
			return "waitlist_cancelled", nil
		}
	}

	// Poll waitlist
	terminal := pollWaitlist(ctx, p, waitlistID)
	if terminal == "FULFILLED" || terminal == "EXPIRED" || terminal == "CANCELLED" {
		p.Stats.RecordJourney("waitlist:" + lower(terminal))
	}
	if terminal != "" {
		return "waitlist_" + lower(terminal), nil
	}
	return "waitlist_observed", nil
}

func pollWaitlist(ctx context.Context, p *Providers, waitlistID string) string {
	attempts := p.Cfg.Polling.Attempts
	interval := time.Duration(p.Cfg.Polling.IntervalSeconds * float64(time.Second))
	for i := 0; i < attempts; i++ {
		code, data, _ := p.API.Request(ctx, "GET", "waitlist",
			"/api/v1/waitlist-requests/"+url.PathEscape(waitlistID),
			nil, nil, nil, "waitlist-poll")
		if code == 200 {
			status := getString(data, "status")
			if status == "FULFILLED" || status == "EXPIRED" || status == "CANCELLED" || status == "CLOSED" {
				return status
			}
		}
		select {
		case <-ctx.Done():
			return ""
		case <-time.After(interval):
		}
	}
	return ""
}

func pollOrderQuick(ctx context.Context, p *Providers, orderID string, maxAttempts int) string {
	for i := 0; i < maxAttempts; i++ {
		code, data, _ := p.API.Request(ctx, "GET", "journey-order",
			"/api/v1/journey-orders/"+url.PathEscape(orderID),
			nil, nil, nil, "poll-order")
		if code == 200 {
			status := getString(data, "status")
			if containsBlock(status) {
				return status
			}
		}
		select {
		case <-ctx.Done():
			return ""
		case <-time.After(time.Second):
		}
	}
	return ""
}

func pollOrder(ctx context.Context, p *Providers, orderID string, want map[string]bool, giveUpOnBlock bool) string {
	attempts := p.Cfg.Polling.Attempts
	interval := time.Duration(p.Cfg.Polling.IntervalSeconds * float64(time.Second))
	for i := 0; i < attempts; i++ {
		code, data, _ := p.API.Request(ctx, "GET", "journey-order",
			"/api/v1/journey-orders/"+url.PathEscape(orderID),
			nil, nil, nil, "poll-order")
		if code == 200 {
			status := getString(data, "status")
			if want[status] {
				return status
			}
			if giveUpOnBlock && containsBlock(status) {
				return status
			}
		}
		select {
		case <-ctx.Done():
			return ""
		case <-time.After(interval):
		}
	}
	return ""
}

func waitForResult(ctx context.Context, item *WorkItem, key string, timeoutSec float64) error {
	timeout := time.Duration(timeoutSec * float64(time.Second))
	timer := time.NewTimer(timeout)
	defer timer.Stop()

	select {
	case <-item.Done():
		if item.Failed {
			return &StepError{Step: item.Kind, Detail: item.ErrorMsg}
		}
		return nil
	case <-timer.C:
		return &StepError{Step: item.Kind, Detail: fmt.Sprintf("timed out waiting for %s", key)}
	case <-ctx.Done():
		return ctx.Err()
	}
}

func containsBlock(s string) bool {
	return len(s) > 0 && (s == "RISK_BLOCKED" || s == "BLOCKED" ||
		(len(s) >= 5 && (s[len(s)-5:] == "BLOCK" || s[:5] == "BLOCK")))
}

func isVerified(entry *AccountEntry, travelerID string) bool {
	for _, v := range entry.IdentityVerified {
		if v == travelerID {
			return true
		}
	}
	return false
}

func markVerified(entry *AccountEntry, travelerID string, reg *Registry) {
	reg.mu.Lock()
	defer reg.mu.Unlock()
	entry.IdentityVerified = append(entry.IdentityVerified, travelerID)
}

func getResultString(item *WorkItem, key string) string {
	v, ok := item.GetResult(key)
	if !ok {
		return ""
	}
	s, _ := v.(string)
	return s
}

func lower(s string) string {
	b := make([]byte, len(s))
	for i := range s {
		c := s[i]
		if c >= 'A' && c <= 'Z' {
			b[i] = c + 32
		} else {
			b[i] = c
		}
	}
	return string(b)
}
