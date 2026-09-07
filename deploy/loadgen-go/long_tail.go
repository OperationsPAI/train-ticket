package main

import (
	"context"
	"net/url"
	"time"
)

// ProbeRefs carries the ids of entities a journey just created, so the
// long-tail read probes can GET them back.
//
// Every field is a REAL id produced earlier in the same journey (or replayed
// from the registry). That is the point of these probes: reading an id the
// system genuinely minted exercises the cache-cold read path, the projection
// lag, and the id-round-trip contract. A synthetic id would only ever measure
// the 404 branch, which is not what this coverage is for.
//
// The zero value of a field means "this journey did not produce one", and the
// corresponding probe is skipped.
type ProbeRefs struct {
	Order         string
	Account       string
	Offer         string
	PaymentIntent string
	Entitlement   string
	Itinerary     string
	Quote         string
	Service       string
	Place         string
	Node          string

	AncillaryCatalog   string
	AncillaryOffer     string
	AncillaryOrderItem string

	InvoiceTitle   string
	InvoiceRequest string
	Invoice        string

	Benefit       string
	WalletAccount string

	FulfillmentRecord string
	PostSalesCase     string
	SupportCase       string

	IdentityCredential string
	IdentityCase       string

	RideRequest string
	RideRider   string

	Waitlist         string
	WaitlistTraveler string

	TransferPlan       string
	TransferConnection string
	TransferJourney    string

	DisruptionIncident string
	DisruptionCase     string
}

// assertGet performs a read probe and verifies the entity round-trips its own
// id. A mismatch is recorded as an error rather than raised, so a bad read
// shows up in the stats without aborting the journey that produced the entity.
func assertGet(ctx context.Context, p *Providers, service, path, idField, expected, step string) map[string]interface{} {
	code, data, err := p.API.Request(ctx, "GET", service, path, nil, nil, []int{200}, step)
	if err != nil || code != 200 {
		return nil
	}
	p.Stats.RecordJourney("long_tail:" + step)
	if idField != "" && expected != "" && getString(data, idField) != expected {
		p.Stats.RecordError("long_tail:" + step + ":id_mismatch")
	}
	return data
}

// assertListContains verifies a list projection has caught up with an entity
// the journey just created.
func assertListContains(p *Providers, page map[string]interface{}, idField, expected, step string) bool {
	if page == nil {
		return false
	}
	items, _ := page["items"].([]interface{})
	for _, raw := range items {
		item, _ := raw.(map[string]interface{})
		if item != nil && getString(item, idField) == expected {
			return true
		}
	}
	p.Stats.RecordError("long_tail:" + step + ":missing_item")
	return false
}

// MaybeReadProbe runs the long-tail read probes over whatever real entity ids
// the calling journey produced.
//
// Gated by long_tail.p_read_probe_after_journey (and the long_tail.enabled
// master switch). Probes are deliberately best-effort: they must never fail
// the journey that created the data.
func MaybeReadProbe(ctx context.Context, p *Providers, refs ProbeRefs) {
	if !p.LongTailChance("p_read_probe_after_journey", 0.05) {
		return
	}

	if refs.Order != "" && refs.Account != "" {
		page := assertGet(ctx, p, "journey-order",
			"/api/v1/journey-orders?accountId="+url.QueryEscape(refs.Account)+"&limit=20&offset=0",
			"", "", "tail-list-orders")
		assertListContains(p, page, "orderId", refs.Order, "tail-list-orders")
		assertGet(ctx, p, "journey-order",
			"/api/v1/journey-orders/"+url.PathEscape(refs.Order),
			"orderId", refs.Order, "tail-get-order")
	}

	if refs.Offer != "" {
		data := assertGet(ctx, p, "offer-management",
			"/api/v1/offers/"+url.PathEscape(refs.Offer), "", "", "tail-get-offer")
		if data != nil {
			got := getString(data, "offerId")
			if got == "" {
				got = getString(data, "id")
			}
			if got != refs.Offer {
				p.Stats.RecordError("long_tail:tail-get-offer:id_mismatch")
			}
		}
	}

	probeAncillary(ctx, p, refs)

	if refs.PaymentIntent != "" {
		assertGet(ctx, p, "payment",
			"/api/v1/payment-intents/"+url.PathEscape(refs.PaymentIntent),
			"paymentIntentId", refs.PaymentIntent, "tail-get-payment-intent")
	}

	// payment-channel has no per-journey entity to read back, so this probe is
	// a bounded list read against the channel statement projection. It is
	// therefore driven by a standalone probability rather than a ref.
	//
	// NOTE: this key lives under behavior: in the deployed ConfigMap, so it is
	// read through the persona-merged behavior context (OptionalChance) rather
	// than through long_tail.*. The previous implementation looked it up in the
	// long_tail dict, where it does not exist, so the deployed 0.10 never
	// actually applied and the hardcoded 0.10 default did. Reading it from its
	// real location makes the configured value load-bearing and lets personas
	// override it; 0.10 is preserved as the default either way.
	if p.OptionalChance("p_payment_channel_read_probe", 0.10) {
		assertGet(ctx, p, "payment-channel",
			"/api/v1/channel-statements?channel=ALIPAY_SIM&limit=5&offset=0",
			"", "", "tail-list-channel-statements")
	}

	probeInvoicing(ctx, p, refs)

	if refs.Order != "" && refs.Entitlement != "" {
		page := assertGet(ctx, p, "entitlement-ticketing",
			"/api/v1/entitlements?journeyOrderId="+url.QueryEscape(refs.Order)+"&limit=20&offset=0",
			"", "", "tail-list-entitlements")
		assertListContains(p, page, "entitlementId", refs.Entitlement, "tail-list-entitlements")
		ent := assertGet(ctx, p, "entitlement-ticketing",
			"/api/v1/entitlements/"+url.PathEscape(refs.Entitlement),
			"entitlementId", refs.Entitlement, "tail-get-entitlement")
		if seatRef, ok := ent["seatRef"].(map[string]interface{}); ok {
			if alloc := getString(seatRef, "seatAllocationId"); alloc != "" {
				assertGet(ctx, p, "seat-assignment",
					"/api/v1/seat-allocations/"+url.PathEscape(alloc),
					"seatAllocationId", alloc, "tail-get-seat-allocation")
			}
		}
	}

	if refs.FulfillmentRecord != "" {
		assertGet(ctx, p, "fulfillment",
			"/api/v1/fulfillment-records/"+url.PathEscape(refs.FulfillmentRecord),
			"fulfillmentRecordId", refs.FulfillmentRecord, "tail-get-fulfillment")
	}
	if refs.PostSalesCase != "" {
		assertGet(ctx, p, "post-sales",
			"/api/v1/post-sales-cases/"+url.PathEscape(refs.PostSalesCase),
			"caseId", refs.PostSalesCase, "tail-get-post-sales")
	}
	if refs.Place != "" {
		assertGet(ctx, p, "place-network",
			"/api/v1/places/"+url.PathEscape(refs.Place),
			"placeId", refs.Place, "tail-get-place")
	}
	if refs.Node != "" {
		assertGet(ctx, p, "place-network",
			"/api/v1/transport-nodes/"+url.PathEscape(refs.Node),
			"nodeId", refs.Node, "tail-get-transport-node")
	}

	if refs.Service != "" {
		page := assertGet(ctx, p, "service-plan",
			"/api/v1/scheduled-services?limit=100&offset=0", "", "", "tail-list-scheduled-services")
		if !assertListContains(p, page, "scheduledServiceRef", refs.Service, "tail-list-scheduled-services") {
			// One retry: a service observed via a fresh itinerary may not be
			// visible to the list projection for a beat.
			select {
			case <-ctx.Done():
				return
			case <-time.After(2 * time.Second):
			}
			page = assertGet(ctx, p, "service-plan",
				"/api/v1/scheduled-services?limit=100&offset=0", "", "", "tail-list-scheduled-services")
			assertListContains(p, page, "scheduledServiceRef", refs.Service, "tail-list-scheduled-services")
		}
		assertGet(ctx, p, "service-plan",
			"/api/v1/scheduled-services/"+url.PathEscape(refs.Service),
			"scheduledServiceRef", refs.Service, "tail-get-scheduled-service")
	}

	if refs.Itinerary != "" {
		assertGet(ctx, p, "trip-planning",
			"/api/v1/itineraries/"+url.PathEscape(refs.Itinerary),
			"itineraryRef", refs.Itinerary, "tail-get-itinerary")
	}
	if refs.SupportCase != "" {
		assertGet(ctx, p, "customer-service",
			"/api/v1/support-cases/"+url.PathEscape(refs.SupportCase),
			"caseId", refs.SupportCase, "tail-get-support-case")
	}
	if refs.Quote != "" {
		assertGet(ctx, p, "fare-pricing",
			"/api/v1/fare-quotes/"+url.PathEscape(refs.Quote),
			"quoteId", refs.Quote, "tail-get-fare-quote")
	}
	if refs.IdentityCredential != "" {
		assertGet(ctx, p, "identity-verification",
			"/api/v1/identity-verification/credentials/"+url.PathEscape(refs.IdentityCredential)+"/verification-status",
			"credentialRecordId", refs.IdentityCredential, "tail-get-identity-credential")
	}
	if refs.IdentityCase != "" {
		assertGet(ctx, p, "identity-verification",
			"/api/v1/identity-verification/verification-cases/"+url.PathEscape(refs.IdentityCase),
			"verificationCaseId", refs.IdentityCase, "tail-get-identity-case")
	}

	if refs.RideRequest != "" {
		assertGet(ctx, p, "dispatch",
			"/api/v1/ride-requests/"+url.PathEscape(refs.RideRequest),
			"rideRequestId", refs.RideRequest, "tail-get-ride-request")
		if refs.RideRider != "" {
			page := assertGet(ctx, p, "dispatch",
				"/api/v1/ride-requests?riderAccountId="+url.QueryEscape(refs.RideRider)+"&limit=20&offset=0",
				"", "", "tail-list-ride-requests")
			assertListContains(p, page, "rideRequestId", refs.RideRequest, "tail-list-ride-requests")
		}
	}

	if refs.Waitlist != "" && refs.WaitlistTraveler != "" {
		page := assertGet(ctx, p, "waitlist",
			"/api/v1/waitlist-requests?travelerRef="+url.QueryEscape(refs.WaitlistTraveler)+"&limit=20&offset=0",
			"", "", "tail-list-waitlist")
		assertListContains(p, page, "waitlistRequestId", refs.Waitlist, "tail-list-waitlist")
		assertGet(ctx, p, "waitlist",
			"/api/v1/waitlist-requests/"+url.PathEscape(refs.Waitlist),
			"waitlistRequestId", refs.Waitlist, "tail-get-waitlist")
	}

	if refs.TransferPlan != "" {
		assertGet(ctx, p, "transfer-management",
			"/api/v1/transfer-plans/"+url.PathEscape(refs.TransferPlan),
			"transferPlanId", refs.TransferPlan, "tail-get-transfer-plan")
	}
	if refs.TransferConnection != "" {
		assertGet(ctx, p, "transfer-management",
			"/api/v1/connections/"+url.PathEscape(refs.TransferConnection),
			"connectionId", refs.TransferConnection, "tail-get-transfer-connection")
	}
	if refs.TransferJourney != "" {
		assertGet(ctx, p, "transfer-management",
			"/api/v1/connections?journeyOrderId="+url.QueryEscape(refs.TransferJourney),
			"", "", "tail-list-transfer-connections")
	}

	if refs.DisruptionIncident != "" {
		assertGet(ctx, p, "disruption-recovery",
			"/api/v1/incidents/"+url.PathEscape(refs.DisruptionIncident),
			"incidentId", refs.DisruptionIncident, "tail-get-disruption-incident")
	}
	if refs.DisruptionCase != "" {
		assertGet(ctx, p, "disruption-recovery",
			"/api/v1/recovery-cases/"+url.PathEscape(refs.DisruptionCase),
			"caseId", refs.DisruptionCase, "tail-get-disruption-case")
	}

	probeWallet(ctx, p, refs)
}

// probeAncillary reads back the ancillary entities the purchase journey's
// add-on branch created. Each read is independently sampled at
// long_tail.p_ancillary_read_probe.
func probeAncillary(ctx context.Context, p *Providers, refs ProbeRefs) {
	if refs.AncillaryCatalog != "" && p.LongTailChance("p_ancillary_read_probe", 0.20) {
		assertGet(ctx, p, "ancillary-service",
			"/api/v1/ancillary-catalog-items/"+url.PathEscape(refs.AncillaryCatalog),
			"catalogItemId", refs.AncillaryCatalog, "tail-get-anc-catalog")
	}
	if refs.AncillaryOffer != "" && p.LongTailChance("p_ancillary_read_probe", 0.20) {
		assertGet(ctx, p, "ancillary-service",
			"/api/v1/ancillary-offers/"+url.PathEscape(refs.AncillaryOffer),
			"ancillaryOfferId", refs.AncillaryOffer, "tail-get-anc-offer")
	}
	if refs.AncillaryOrderItem != "" && refs.Order != "" && p.LongTailChance("p_ancillary_read_probe", 0.20) {
		// ancillary-service requires journeyOrderId on this list endpoint.
		page := assertGet(ctx, p, "ancillary-service",
			"/api/v1/ancillary-order-items?journeyOrderId="+url.QueryEscape(refs.Order)+"&limit=20&offset=0",
			"", "", "tail-list-anc-items")
		assertListContains(p, page, "ancillaryOrderItemId", refs.AncillaryOrderItem, "tail-list-anc-items")
		assertGet(ctx, p, "ancillary-service",
			"/api/v1/ancillary-order-items/"+url.PathEscape(refs.AncillaryOrderItem),
			"ancillaryOrderItemId", refs.AncillaryOrderItem, "tail-get-anc-item")
	}
}

// probeInvoicing reads back the invoicing entities the purchase journey's
// e-invoice branch created.
func probeInvoicing(ctx context.Context, p *Providers, refs ProbeRefs) {
	if refs.InvoiceTitle != "" {
		assertGet(ctx, p, "invoicing",
			"/api/v1/invoice-titles/"+url.PathEscape(refs.InvoiceTitle),
			"titleId", refs.InvoiceTitle, "tail-get-invoice-title")
	}
	if refs.InvoiceRequest != "" {
		assertGet(ctx, p, "invoicing",
			"/api/v1/e-invoice-requests/"+url.PathEscape(refs.InvoiceRequest),
			"invoiceRequestId", refs.InvoiceRequest, "tail-get-invoice-request")
	}
	if refs.Invoice != "" {
		assertGet(ctx, p, "invoicing",
			"/api/v1/e-invoices/"+url.PathEscape(refs.Invoice),
			"eInvoiceId", refs.Invoice, "tail-get-e-invoice")
		if refs.Order != "" {
			// invoicing's list endpoint hard-requires orderId.
			page := assertGet(ctx, p, "invoicing",
				"/api/v1/e-invoices?orderId="+url.QueryEscape(refs.Order)+"&limit=20&offset=0",
				"", "", "tail-list-e-invoices")
			assertListContains(p, page, "eInvoiceId", refs.Invoice, "tail-list-e-invoices")
		}
	}
}

// probeWallet reads back the wallet-promotion entities the purchase journey's
// benefit branch created.
func probeWallet(ctx context.Context, p *Providers, refs ProbeRefs) {
	if refs.Benefit != "" {
		assertGet(ctx, p, "wallet-promotion",
			"/api/v1/benefits/"+url.PathEscape(refs.Benefit),
			"benefitId", refs.Benefit, "tail-get-benefit")
	}
	if refs.WalletAccount != "" {
		assertGet(ctx, p, "wallet-promotion",
			"/api/v1/wallet-accounts/"+url.PathEscape(refs.WalletAccount),
			"accountId", refs.WalletAccount, "tail-get-wallet-account")
		page := assertGet(ctx, p, "wallet-promotion",
			"/api/v1/benefits?byAccountId="+url.QueryEscape(refs.WalletAccount)+"&limit=20&offset=0",
			"", "", "tail-list-benefits")
		if refs.Benefit != "" {
			assertListContains(p, page, "benefitId", refs.Benefit, "tail-list-benefits")
		}
	}
}

// probeRefsFromPurchase rebuilds the probe refs for a purchase replayed from
// the registry, so journeys that consume an existing purchase probe the same
// real ids the purchase journey minted.
func probeRefsFromPurchase(purchase *Purchase) ProbeRefs {
	return ProbeRefs{
		Order:              purchase.Order,
		Account:            purchase.Account,
		Offer:              purchase.Offer,
		PaymentIntent:      purchase.PaymentIntent,
		Entitlement:        purchase.Entitlement,
		Itinerary:          purchase.Itinerary,
		Quote:              purchase.Quote,
		Service:            purchase.Service,
		Place:              purchase.OriginPlace,
		Node:               purchase.OriginNode,
		AncillaryCatalog:   purchase.AncillaryCatalog,
		AncillaryOffer:     purchase.AncillaryOffer,
		AncillaryOrderItem: purchase.AncillaryOrderItem,
		InvoiceTitle:       purchase.InvoiceTitle,
		InvoiceRequest:     purchase.InvoiceRequest,
		Invoice:            purchase.Invoice,
		Benefit:            purchase.Benefit,
		WalletAccount:      purchase.WalletAccount,
		FulfillmentRecord:  purchase.FulfillmentRecord,
		PostSalesCase:      purchase.PostSalesCase,
	}
}
