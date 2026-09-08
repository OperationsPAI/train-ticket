package main

import (
	"context"
	"net/url"
	"strings"
	"time"
)

// JourneyRefund takes a completed purchase and opens a post-sales refund case.
func JourneyRefund(ctx context.Context, p *Providers) (string, error) {
	purchase := p.Reg.TakePurchase(p.Rng, "confirmed")
	if purchase == nil {
		return "no_purchase_to_refund", nil
	}

	caseID, err := postSalesCase(ctx, p, purchase, "REFUND", "CUSTOMER_REQUEST")
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

	p.Reg.ReleasePurchase(purchase, "refunded")
	MaybeReadProbe(ctx, p, ProbeRefs{
		PostSalesCase: caseID,
		Order:         purchase.Order,
		Account:       purchase.Account,
	})
	return "refunded", nil
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
		return "", &StepError{Step: lower(caseType) + "-case", Detail: "no case id in response"}
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
