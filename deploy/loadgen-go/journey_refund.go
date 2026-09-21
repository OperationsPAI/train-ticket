package main

import (
	"context"
	"net/url"
	"strings"
	"time"
)

// postSalesCaseMixDefault is the fallback for behavior.post_sales_case_mix.
//
// Three of post-sales' five PostSalesCaseType values. CHANGE is absent because
// it is the change journey's own case type, and REBOOK is absent because
// post-sales cannot currently accept one: decisionFor maps caseType REBOOK to
// DecisionKind.REFUND (PostSalesApplicationService line 177) while
// recordDecision requires the decision kind to equal the case type name and
// exempts only CANCELLATION (PostSalesCase line 159), so every REBOOK case
// throws DomainRuleViolation and evaluate answers 422. That refusal is
// unconditional rather than a state precondition the generator could satisfy,
// and closing it means editing post-sales.
//
// What the three that remain actually do differently:
//
//   - REFUND and CANCELLATION share DecisionKind.REFUND, so both price through
//     RefundPolicyEngine and both run the void-entitlement / cancel-segment /
//     release-capacity / request-refund execution plan. CANCELLATION differs in
//     one observable way: PostSalesMapper.requestType reports it as
//     "CANCELLATION" where REFUND reports "REFUND_BY_RULE".
//   - COMPENSATION takes DecisionKind.COMPENSATION, which returns its decision
//     before the refund assessment is built. It therefore never consults the
//     policy context, so it cannot 409 on POLICY_CONTEXT_NOT_READY, and it
//     charges no penalty tier.
//
// Weighted so the ordinary voluntary refund stays dominant.
var postSalesCaseMixDefault = map[string]float64{
	"REFUND": 0.62, "CANCELLATION": 0.28, "COMPENSATION": 0.10,
}

// postSalesReasonCodes is the reason string sent per case type.
//
// reasonCode is @NotBlank and otherwise unvalidated, but it is not inert for the
// two refund-kind types: PostSalesApplicationService.classify reads it for the
// substrings CARRIER, TRAIN_CANCEL, DELAY, FORCE, MAJEURE, PLATFORM and ERROR,
// and any hit makes the refund INVOLUNTARY, which
// RefundPolicyEngine.INVOLUNTARY_OVERRIDE prices at a zero penalty. All three
// strings below are deliberately free of those substrings, so the generated
// traffic exercises the ordinary penalty tiers instead of collapsing onto the
// override. They are also what the downstream complaint generator reads, so
// each states the customer's own reason.
var postSalesReasonCodes = map[string]string{
	"REFUND":       "CUSTOMER_REQUEST",
	"CANCELLATION": "CUSTOMER_CANCELLED_TRIP",
	"COMPENSATION": "SERVICE_QUALITY_COMPLAINT",
}

// postSalesOutcome is both the journey's outcome word and the registry status
// the purchase is released to, keyed by case type.
//
// Each of the three is terminal for this purchase and distinct from
// "confirmed", which is what establishes the precondition rather than provoking
// its refusal. post-sales holds one exclusive active-refund slot per order
// across REFUND, CANCELLATION, REBOOK and CHANGE
// (requiresExclusiveRefundSlot, and the post_sales_active_refunds unique index
// behind reserveActiveRefundSlot), so a second case of any of those types on the
// same order is a 409 REFUND_ALREADY_IN_PROGRESS. TakePurchase already claims
// the purchase exclusively within the process; releasing it to a status other
// than "confirmed" keeps a later refund, change or disruption journey from
// picking the same order up and asking for that second case.
//
// classifyOutcome files all three as completed, which is correct: the customer
// asked the system to end their booking and it answered in full.
// "order_cancelled" rather than a bare "cancelled" keeps it out of the
// journey record's abandoned vocabulary, where every word for a customer
// walking away mid-funnel already ends in "cancelled".
var postSalesOutcome = map[string]string{
	"REFUND":       "refunded",
	"CANCELLATION": "order_cancelled",
	"COMPENSATION": "compensated",
}

// JourneyRefund takes a completed purchase and opens a post-sales case against
// it: a refund, a cancellation or a compensation claim.
func JourneyRefund(ctx context.Context, p *Providers) (string, error) {
	purchase := p.Reg.TakePurchase(p.Rng, "confirmed")
	if purchase == nil {
		return "no_purchase_to_refund", nil
	}

	caseType := WeightedChoice(p.Rng,
		p.CtxMap("post_sales_case_mix", postSalesCaseMixDefault))
	caseID, err := postSalesCase(ctx, p, purchase, caseType, postSalesReasonCodes[caseType])
	if err != nil {
		p.Reg.ReleasePurchase(purchase, "confirmed")
		return "", err
	}

	if p.Chance("p_refund_get_after_completion") {
		refundID := findRefundID(ctx, p, caseID)
		if refundID != "" {
			p.API.Request(ctx, "GET", "payment",
				"/api/v1/refunds/"+url.PathEscape(refundID),
				nil, nil, []int{200}, "tail-get-refund")
		}
	}

	p.Reg.ReleasePurchase(purchase, postSalesOutcome[caseType])
	MaybeReadProbe(ctx, p, ProbeRefs{
		PostSalesCase: caseID,
		Order:         purchase.Order,
		Account:       purchase.Account,
	})
	return postSalesOutcome[caseType], nil
}

func postSalesCase(ctx context.Context, p *Providers, purchase *Purchase, caseType, reason string) (string, error) {
	_, caseData, err := p.API.Request(ctx, "POST", "post-sales", "/api/v1/post-sales-cases",
		map[string]interface{}{
			"journeyOrderId": purchase.Order,
			"caseType":       caseType,
			"scope": map[string]interface{}{
				"orderItemRefs":   []string{purchase.SB},
				"segmentRefs":     []string{purchase.Seg},
				"travelerRefs":    []string{purchase.Traveler},
				"entitlementRefs": []string{purchase.Entitlement},
			},
			"reasonCode": reason,
			"actorRef":   purchase.Account,
		}, nil, []int{200, 201}, lower(caseType)+"-case")
	if err != nil {
		return "", err
	}

	caseID := getString(caseData, "caseId")
	if caseID == "" {
		caseID = getString(caseData, "postSalesCaseId")
	}
	if caseID == "" {
		// The request succeeded and the response was unusable. The person saw
		// a confirmation for a case that has no id.
		return "", &StepError{Step: lower(caseType) + "-case",
			Detail: "no case id in response", Kind: FailureMalformed}
	}

	// Retry on 409 POLICY_CONTEXT_NOT_READY.
	//
	// post-sales prices a refund from a policy context it projects off a
	// journey-order event, so a refund opened immediately after purchase can
	// outrun that projection. It used to fall back to departureTime=now, which
	// forces AFTER_DEPARTURE_NON_REFUNDABLE and quotes a ZERO refund -- a wrong
	// answer no later step could distinguish from a correct one. It now refuses
	// with a retryable 409 instead, so the caller has to be willing to wait.
	//
	// Measured on the live cluster: every order that missed had its context within
	// seconds, so a short bounded retry is the right shape rather than a long one.
	// PollRequest keeps the expected 409s out of the error tally; the final attempt
	// does not, so a context that never arrives still surfaces as a failure.
	for attempt := 0; attempt < 6; attempt++ {
		var expected []int
		if attempt < 5 {
			expected = []int{409}
		}
		_, _, err = p.API.PollRequest(ctx, "POST", "post-sales",
			"/api/v1/post-sales-cases/"+url.PathEscape(caseID)+"/evaluate",
			map[string]interface{}{}, nil, []int{200, 201}, "case-evaluate", expected)
		if err == nil {
			break
		}
		if se, ok := err.(*StepError); !ok || !strings.Contains(se.Detail, "409") || attempt == 5 {
			return "", err
		}
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(time.Duration(500+attempt*500) * time.Millisecond):
		}
	}

	_, _, err = p.API.Request(ctx, "POST", "post-sales",
		"/api/v1/post-sales-cases/"+url.PathEscape(caseID)+"/approve",
		map[string]interface{}{}, nil, []int{200, 201}, "case-approve")
	if err != nil {
		return "", err
	}

	purchase.PostSalesCase = caseID
	return caseID, nil
}

// findRefundID searches Redis for refund events (simplified: returns empty if no Redis).
func findRefundID(_ context.Context, _ *Providers, _ string) string {
	// In the Go version, Redis-based event scanning for refund IDs
	// is handled via the RedisClient if available. For now, return empty.
	return ""
}
