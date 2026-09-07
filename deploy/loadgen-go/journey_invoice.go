package main

import (
	"context"
	"encoding/json"
	"net/url"
	"strconv"
)

// ensureInvoiceTitle returns a usable, ACTIVE invoice title for an account,
// creating one if the cached id is missing or no longer usable.
//
// The re-validation is load-bearing rather than defensive: invoicing's
// create_and_submit rejects a request whose title is not ACTIVE (or whose
// version does not match) with a 412, so a stale cached id would turn every
// subsequent invoice attempt for that account into a permanent failure.
// Returns the title id and its current version.
func ensureInvoiceTitle(ctx context.Context, p *Providers, accountID string) (string, int) {
	if cached := p.Reg.CachedInvoiceTitle(accountID); cached != "" {
		// No `ok` list: a 404/410 here is an expected cache miss, not an error
		// worth recording against invoicing.
		code, data, _ := p.API.Request(ctx, "GET", "invoicing",
			"/api/v1/invoice-titles/"+url.PathEscape(cached),
			nil, nil, nil, "invoice-title-get")
		if code == 200 && getString(data, "status") == "ACTIVE" {
			return cached, getInt(data, "version", 1)
		}
	}

	_, title, err := p.API.Request(ctx, "POST", "invoicing", "/api/v1/invoice-titles",
		map[string]interface{}{
			"accountId":    accountID,
			"titleType":    "PERSONAL",
			"titleName":    "个人",
			"setAsDefault": true,
		}, nil, []int{201}, "invoice-title-create")
	if err != nil {
		return "", 0
	}
	titleID := getString(title, "titleId")
	if titleID == "" {
		return "", 0
	}
	p.Reg.RememberInvoiceTitle(accountID, titleID)
	return titleID, getInt(title, "version", 1)
}

// MaybeRequestInvoice runs the tax e-invoice branch of a purchase: it ensures
// the account has an invoice title, then submits an e-invoice request for the
// order against the SIM tax gateway.
//
// Gated by behavior.p_invoice_after_purchase. Together with the saga-driven
// path this is invoicing's only customer-journey traffic, so if this branch
// does not fire invoicing sees no journey load.
//
// Returns the title / request / invoice ids for the long-tail read probes.
func MaybeRequestInvoice(ctx context.Context, p *Providers, purchase *Purchase) (titleID, requestID, invoiceID string) {
	if !p.OptionalChance("p_invoice_after_purchase", 0.02) {
		return "", "", ""
	}
	// invoicing rejects a non-positive totalAmount outright, so there is no
	// point spending a title creation on it.
	if purchase.TotalMinor <= 0 {
		p.Stats.RecordError("invoicing:non_positive_total")
		return "", "", ""
	}

	titleID, titleVersion := ensureInvoiceTitle(ctx, p, purchase.Account)
	if titleID == "" {
		p.Stats.RecordError("invoicing:no_title")
		return "", "", ""
	}

	currency := p.Currency()
	orderTail := purchase.Order
	if len(orderTail) > 8 {
		orderTail = orderTail[len(orderTail)-8:]
	}

	basis := map[string]interface{}{
		"basisType":             "REVENUE_RECOGNITION",
		"revenueRecognitionIds": []string{"rr-loadgen-" + orderTail},
		"taxLines": []map[string]interface{}{{
			"taxCode":            "VAT_SIM",
			"taxRateBasisPoints": 0,
			"taxableAmount":      map[string]interface{}{"currency": currency, "minorUnits": purchase.TotalMinor},
			"taxAmount":          map[string]interface{}{"currency": currency, "minorUnits": 0},
		}},
		"totalAmount": map[string]interface{}{"currency": currency, "minorUnits": purchase.TotalMinor},
	}
	// invoicing requires amountBasisHash to be non-empty (it stands in for a
	// Finance Settlement projection hash) and compares it when de-duplicating
	// repeat requests. It does not re-derive it, so any stable digest of the
	// basis is acceptable -- it just has to be deterministic for identical
	// input. Go's map marshalling sorts keys, so this is stable.
	if encoded, err := json.Marshal(basis); err == nil {
		basis["amountBasisHash"] = "sha256:" + sha256Hex(string(encoded))
	} else {
		basis["amountBasisHash"] = "sha256:" + sha256Hex(purchase.Order)
	}

	code, req, err := p.API.Request(ctx, "POST", "invoicing", "/api/v1/e-invoice-requests",
		map[string]interface{}{
			"accountId":      purchase.Account,
			"orderId":        purchase.Order,
			"titleId":        titleID,
			"titleVersion":   titleVersion,
			"invoiceScope":   map[string]interface{}{"scopeType": "ORDER"},
			"amountBasis":    basis,
			"recipientEmail": "loadgen@example.com",
			"simSeedRef":     "loadgen-accept",
		}, nil, []int{201, 409, 412, 422}, "invoice-request")
	if err != nil {
		p.Stats.RecordError("invoicing:request")
		return titleID, "", ""
	}

	if code == 201 {
		status := getString(req, "status")
		if status == "" {
			status = "unknown"
		}
		p.Stats.RecordJourney("invoicing:" + lower(status))
		return titleID, getString(req, "invoiceRequestId"), getString(req, "eInvoiceId")
	}

	// 409/412/422 are legitimate outcomes (duplicate request, stale title
	// version, rejected basis) and are counted rather than treated as errors.
	p.Stats.RecordJourney("invoicing:skipped:" + strconv.Itoa(code))
	return titleID, "", ""
}
