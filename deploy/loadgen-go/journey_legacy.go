package main

import (
	"context"
	"fmt"
	"time"
)

// JourneyLegacy performs a lifecycle through the legacy-acl facade.
func JourneyLegacy(ctx context.Context, p *Providers) (string, error) {
	entry, err := p.Account(ctx)
	if err != nil {
		return "", err
	}
	tvl, err := p.Traveler(ctx, entry, nil)
	if err != nil {
		return "", err
	}

	if !isVerified(entry, tvl) {
		_, err := p.Identity(ctx, tvl)
		if err != nil {
			return "", err
		}
		markVerified(entry, tvl, p.Reg)
	}

	routes := p.Reg.GetRoutes()
	var legacyRoutes []*RouteEntry
	for _, r := range routes {
		if r.ServiceNumber != "" {
			legacyRoutes = append(legacyRoutes, r)
		}
	}
	if len(legacyRoutes) == 0 {
		return "no_legacy_route", nil
	}
	route := legacyRoutes[p.Rng.Intn(len(legacyRoutes))]
	headers := map[string]string{
		"X-Legacy-Operator": "loadgen-legacy",
		"X-Legacy-Reason":   "LOAD_TEST",
	}

	seatTypes := p.CtxMap("seat_types", map[string]float64{"SECOND": 0.8, "FIRST": 0.2})
	seatType := WeightedChoice(p.Rng, seatTypes)

	// preserve
	_, preserveData, err := p.API.Request(ctx, "POST", "legacy-acl", "/api/v1/legacy/preserve",
		map[string]interface{}{
			"accountId":  entry.AccountID,
			"contactsId": tvl,
			"tripId":     route.ServiceNumber,
			"seatType":   seatType,
			"date":       route.Date,
			"from":       route.OriginPlace,
			"to":         route.DestPlace,
		}, headers, []int{200}, "legacy-preserve")
	if err != nil {
		return "", err
	}
	legacyData := getNestedMap(preserveData, "data")
	if legacyData == nil {
		legacyData = preserveData
	}
	status := getInt(preserveData, "status", 1)
	if status != 1 {
		return "", &StepError{Step: "legacy-preserve", Detail: fmt.Sprintf("status=%d", status)}
	}
	orderID := getString(legacyData, "orderId")
	total := getNestedInt(legacyData, "total", "minorUnits", 10750)

	Think(ctx, p)

	// pay
	_, payData, err := p.API.Request(ctx, "POST", "legacy-acl", "/api/v1/legacy/inside_payment",
		map[string]interface{}{
			"orderId": orderID,
			"price":   map[string]interface{}{"currency": p.Currency(), "minorUnits": total},
		}, headers, []int{200}, "legacy-pay")
	if err != nil {
		return "", err
	}
	if getInt(payData, "status", 0) != 1 {
		return "", &StepError{Step: "legacy-pay", Detail: fmt.Sprintf("status=%d", getInt(payData, "status", 0))}
	}

	time.Sleep(time.Duration(p.Cfg.Polling.IntervalSeconds*2) * time.Second)

	// ticket
	_, tickData, err := p.API.Request(ctx, "POST", "legacy-acl", "/api/v1/legacy/ticket_issue",
		map[string]interface{}{"orderId": orderID}, headers, []int{200}, "legacy-ticket")
	if err != nil {
		return "", err
	}
	if getInt(tickData, "status", 0) != 1 {
		return "", &StepError{Step: "legacy-ticket", Detail: fmt.Sprintf("status=%d", getInt(tickData, "status", 0))}
	}

	Think(ctx, p)

	if p.Chance("p_legacy_cancel") {
		_, _, err := p.API.Request(ctx, "POST", "legacy-acl", "/api/v1/legacy/cancel",
			map[string]interface{}{"orderId": orderID}, headers, []int{200}, "legacy-cancel")
		if err != nil {
			return "", err
		}
		return "legacy_cancelled", nil
	}

	_, _, err = p.API.Request(ctx, "POST", "legacy-acl", "/api/v1/legacy/execute",
		map[string]interface{}{"orderId": orderID}, headers, []int{200}, "legacy-execute")
	if err != nil {
		return "", err
	}
	return "legacy_completed", nil
}

func getNestedMap(m map[string]interface{}, key string) map[string]interface{} {
	if m == nil {
		return nil
	}
	v, ok := m[key].(map[string]interface{})
	if !ok {
		return nil
	}
	return v
}
