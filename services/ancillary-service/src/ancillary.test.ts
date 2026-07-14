import assert from "node:assert/strict";
import { describe, it, mock } from "node:test";
import { InMemoryEventPublisher, createEventEnvelope } from "@trainticket/ts-kit";
import { AncillaryApplicationService, HttpFarePricingGateway, InMemoryAncillaryRepository, createApp, resetAncillaryStore, type AncillaryPricingGateway } from "./index.js";

function uuid7(): string { return "018f0000-0000-7000-8000-" + Math.random().toString(16).slice(2).padEnd(12, "0").slice(0, 12); }
function catalogBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  const now = new Date();
  return { serviceType: "MEAL", displayName: "Hot meal", attachmentScope: "SEGMENT", modalities: ["TRAIN"], price: { currency: "CNY", minorUnits: 2500 }, salesWindow: { startAt: now.toISOString(), endAt: new Date(now.getTime() + 86400000).toISOString() }, purchaseCutoffHoursBeforeDeparture: 2, eligibilityRuleVersion: "min-v1", requiresEntitlementRef: true, requiresSegmentRef: true, fulfillmentMethod: "VOUCHER", ...overrides };
}
async function post(app: ReturnType<typeof createApp>, url: string, body: unknown) { return await app.inject({ method: "POST", url, headers: { "idempotency-key": uuid7() }, payload: body as Record<string, unknown> }); }

const dynamicPricingGateway: AncillaryPricingGateway = {
  async quoteAncillaryPrice() {
    return {
      quoteId: "fq-dynamic-1",
      inputHash: "dynamic-hash",
      unitPrice: { currency: "CNY", minorUnits: 3200 },
      ruleSnapshot: { ruleSetId: "frs-ancillary", ruleSetVersion: "v1", capturedAt: new Date().toISOString(), ruleIds: ["base-meal", "service-fee"], explanationCodes: ["DYNAMIC_MEAL", "SERVICE_FEE"], digest: "digest" },
      fees: [{ ruleId: "service-fee", amount: { currency: "CNY", minorUnits: 150 }, explanation: { code: "SERVICE_FEE", parameters: {} }, refundable: false }],
    };
  },
};

describe("ancillary-service contract flows", () => {
  it("creates catalog, prices via dynamic rules, assesses fees, and fulfills", async () => {
    resetAncillaryStore();
    const publisher = new InMemoryEventPublisher();
    const app = createApp({}, { publisher, pricingGateway: dynamicPricingGateway });
    const created = await post(app, "/api/v1/ancillary-catalog-items", catalogBody());
    assert.equal(created.statusCode, 201);
    const catalog = created.json();
    assert.equal(catalog.status, "DRAFT");
    const published = await post(app, `/api/v1/ancillary-catalog-items/${catalog.catalogItemId}/publish`, { approvalRef: "apr-1", expectedVersion: catalog.version });
    assert.equal(published.statusCode, 200);

    const draft = await post(app, "/api/v1/ancillary-offers", { catalogItemId: catalog.catalogItemId, journeyOrderId: "ord-1", travelerRef: "tvl-1", segmentRef: "seg-1", entitlementRef: "ent-1", departureAt: new Date(Date.now() + 6 * 3600000).toISOString(), quantity: 2 });
    assert.equal(draft.statusCode, 201);
    const quote = await post(app, `/api/v1/ancillary-offers/${draft.json().ancillaryOfferId}/quote`, { expectedVersion: draft.json().offerVersion, validitySeconds: 300 });
    assert.equal(quote.statusCode, 200);
    assert.equal(quote.json().unitPrice.minorUnits, 3200);
    assert.equal(quote.json().totalPrice.minorUnits, 6400);
    assert.equal(quote.json().priceQuoteRef.source, "FARE_PRICING");
    assert.equal(quote.json().priceQuoteRef.quoteId, "fq-dynamic-1");
    assert.equal(quote.json().feeAssessment.fee.minorUnits, 150);
    const selected = await post(app, `/api/v1/ancillary-offers/${quote.json().ancillaryOfferId}/select`, { journeyOrderId: "ord-1", expectedVersion: quote.json().offerVersion });
    assert.equal(selected.statusCode, 201);
    assert.equal(selected.json().status, "SELECTED");
    assert.equal(selected.json().payableAmount.minorUnits, 6400);
    assert.equal(selected.json().feeAssessment.fee.minorUnits, 300);
    assert.equal(selected.json().assessedFees[0].amount.minorUnits, 300);

    const pending = await post(app, `/api/v1/ancillary-order-items/${selected.json().ancillaryOrderItemId}/confirm`, { reasonCode: "SUPPLIER_PENDING" });
    assert.equal(pending.statusCode, 200);
    assert.equal(pending.json().status, "PENDING_CONFIRMATION");
    const confirmed = await post(app, `/api/v1/ancillary-order-items/${selected.json().ancillaryOrderItemId}/confirm`, { confirmationRef: "conf-1" });
    assert.equal(confirmed.json().status, "CONFIRMED");
    const ready = await post(app, `/api/v1/ancillary-order-items/${selected.json().ancillaryOrderItemId}/fulfillment-ready`, { providerRef: "voucher-1" });
    assert.equal(ready.json().status, "FULFILLMENT_READY");
    const fulfilled = await post(app, `/api/v1/ancillary-order-items/${selected.json().ancillaryOrderItemId}/fulfillment-facts`, { factType: "MEAL_ISSUED", occurredAt: new Date().toISOString(), performedBy: "PROVIDER", idempotencyRef: "meal-1" });
    assert.equal(fulfilled.json().status, "FULFILLED");
    assert.deepEqual(publisher.findByEventType("AncillaryOfferQuoted")[0].payload.totalPrice, { currency: "CNY", minorUnits: 6400 });
    assert.equal((publisher.findByEventType("AncillaryOfferQuoted")[0].payload.priceQuoteRef as any).source, "FARE_PRICING");
    assert.equal((publisher.findByEventType("AncillaryOrderItemSelected")[0].payload.feeAssessment as any).fee.minorUnits, 300);
    assert.equal(publisher.findByEventType("AncillaryOrderItemFulfilled").length, 1);
  });

  it("uses the client UUID v7 idempotency key for outbound fare-pricing quote retries", async () => {
    const key = "018f0000-0000-7000-8000-000000000123";
    const fetchMock = mock.fn<typeof fetch>(async (_url, init) => {
      assert.equal((init?.headers as Record<string, string>)["idempotency-key"], key);
      return new Response(JSON.stringify({
        quoteId: "fq-http-1",
        status: "QUOTED",
        validFrom: new Date().toISOString(),
        validUntil: new Date(Date.now() + 300000).toISOString(),
        breakdown: { total: { currency: "CNY", minorUnits: 3100 }, fees: [] },
      }), { status: 201, headers: { "content-type": "application/json" } });
    });
    const gateway = new HttpFarePricingGateway("https://fare-pricing.example", fetchMock);

    const result = await gateway.quoteAncillaryPrice({
      ancillaryOfferId: "aof-1",
      offerVersion: 2,
      catalogItemId: "aci-1",
      travelerRef: "tvl-1",
      segmentRef: "seg-1",
      departureAt: new Date().toISOString(),
      quantity: 1,
      catalogUnitPrice: { currency: "CNY", minorUnits: 2500 },
      context: {},
      correlationId: "corr-018f0000-0000-7000-8000-000000000111",
      idempotencyKey: key,
    });

    assert.equal(fetchMock.mock.callCount(), 1);
    assert.equal(result?.quoteId, "fq-http-1");
  });

  it("rejects ineligible time windows and expires quoted offers", async () => {
    resetAncillaryStore();
    const app = createApp();
    const created = await post(app, "/api/v1/ancillary-catalog-items", catalogBody({ attachmentScope: "JOURNEY", requiresSegmentRef: false }));
    await post(app, `/api/v1/ancillary-catalog-items/${created.json().catalogItemId}/publish`, { approvalRef: "apr", expectedVersion: 1 });
    const draft = await post(app, "/api/v1/ancillary-offers", { catalogItemId: created.json().catalogItemId, travelerRef: "tvl", entitlementRef: "ent", departureAt: new Date(Date.now() + 3600000).toISOString(), quantity: 1 });
    assert.equal(draft.json().status, "INELIGIBLE");

    const repository = new InMemoryAncillaryRepository();
    const service = new AncillaryApplicationService(repository, new InMemoryEventPublisher());
    const catalog = await service.createCatalogItem(catalogBody({ attachmentScope: "JOURNEY", requiresSegmentRef: false }) as any);
    await service.publishCatalogItem(catalog.catalogItemId, { approvalRef: "apr", expectedVersion: 1 }, "corr-018f0000-0000-7000-8000-000000000222");
    const ok = await service.draftOffer({ catalogItemId: catalog.catalogItemId, travelerRef: "tvl", entitlementRef: "ent", departureAt: new Date(Date.now() + 6 * 3600000).toISOString(), quantity: 1 });
    const quote = await service.quoteOffer(ok.ancillaryOfferId, { expectedVersion: ok.offerVersion, validitySeconds: 1 }, "corr-018f0000-0000-7000-8000-000000000222");
    await new Promise((resolve) => setTimeout(resolve, 1100));
    assert.equal(await service.expireOffers(new Date(), "corr-018f0000-0000-7000-8000-000000000222"), 1);
    assert.equal((await service.getOffer(quote.ancillaryOfferId)).status, "EXPIRED");
  });
  it("handles JourneyOrderCancelled payload orderId with durable-style application dedup semantics", async () => {
    const publisher = new InMemoryEventPublisher();
    const repository = new InMemoryAncillaryRepository();
    const service = new AncillaryApplicationService(repository, publisher);
    const catalog = await service.createCatalogItem(catalogBody({ attachmentScope: "JOURNEY", requiresSegmentRef: false }) as any);
    await service.publishCatalogItem(catalog.catalogItemId, { approvalRef: "apr", expectedVersion: 1 }, "corr-018f0000-0000-7000-8000-000000000111");
    const draft = await service.draftOffer({ catalogItemId: catalog.catalogItemId, journeyOrderId: "ord-event", travelerRef: "tvl", entitlementRef: "ent", departureAt: new Date(Date.now() + 6 * 3600000).toISOString(), quantity: 1 });
    const quoted = await service.quoteOffer(draft.ancillaryOfferId, { expectedVersion: draft.offerVersion }, "corr-018f0000-0000-7000-8000-000000000111");
    const item = await service.selectOffer(quoted.ancillaryOfferId, { journeyOrderId: "ord-event", expectedVersion: quoted.offerVersion }, "corr-018f0000-0000-7000-8000-000000000111");
    const result = await service.handleJourneyOrderCancelled(createEventEnvelope({ eventType: "JourneyOrderCancelled", producer: "journey-order", correlationId: "corr-018f0000-0000-7000-8000-000000000111", payload: { orderId: "ord-event" } }));
    assert.equal(result, "ack");
    assert.equal((await service.getOrderItem(item.ancillaryOrderItemId)).status, "CANCELLED");
    assert.equal(publisher.findByEventType("AncillaryOrderItemCancelled").at(-1)?.payload.source, "JOURNEY_ORDER_CANCELLED");
    const unknown = await service.handleJourneyOrderCancelled(createEventEnvelope({ eventType: "JourneyOrderCancelled", producer: "journey-order", payload: { orderId: "missing" } }));
    assert.equal(unknown, "ack");
  });

});
