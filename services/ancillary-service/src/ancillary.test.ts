import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { InMemoryEventPublisher, createEventEnvelope } from "@trainticket/ts-kit";
import { AncillaryApplicationService, InMemoryAncillaryRepository, createApp, resetAncillaryStore } from "./index.js";

function uuid7(): string { return "018f0000-0000-7000-8000-" + Math.random().toString(16).slice(2).padEnd(12, "0").slice(0, 12); }
function catalogBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  const now = new Date();
  return { serviceType: "MEAL", displayName: "Hot meal", attachmentScope: "SEGMENT", modalities: ["TRAIN"], price: { currency: "CNY", minorUnits: 2500 }, salesWindow: { startAt: now.toISOString(), endAt: new Date(now.getTime() + 86400000).toISOString() }, purchaseCutoffHoursBeforeDeparture: 2, eligibilityRuleVersion: "min-v1", requiresEntitlementRef: true, requiresSegmentRef: true, fulfillmentMethod: "VOUCHER", ...overrides };
}
async function post(app: ReturnType<typeof createApp>, url: string, body: unknown) { return await app.inject({ method: "POST", url, headers: { "idempotency-key": uuid7() }, payload: body as Record<string, unknown> }); }

describe("ancillary-service contract flows", () => {
  it("creates catalog, quotes/selects offer, confirms and records fulfillment", async () => {
    resetAncillaryStore();
    const publisher = new InMemoryEventPublisher();
    const app = createApp({}, { publisher });
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
    assert.equal(quote.json().totalPrice.minorUnits, 5000);
    const selected = await post(app, `/api/v1/ancillary-offers/${quote.json().ancillaryOfferId}/select`, { journeyOrderId: "ord-1", expectedVersion: quote.json().offerVersion });
    assert.equal(selected.statusCode, 201);
    assert.equal(selected.json().status, "SELECTED");

    const pending = await post(app, `/api/v1/ancillary-order-items/${selected.json().ancillaryOrderItemId}/confirm`, { reasonCode: "SUPPLIER_PENDING" });
    assert.equal(pending.statusCode, 200);
    assert.equal(pending.json().status, "PENDING_CONFIRMATION");
    const confirmed = await post(app, `/api/v1/ancillary-order-items/${selected.json().ancillaryOrderItemId}/confirm`, { confirmationRef: "conf-1" });
    assert.equal(confirmed.json().status, "CONFIRMED");
    const ready = await post(app, `/api/v1/ancillary-order-items/${selected.json().ancillaryOrderItemId}/fulfillment-ready`, { providerRef: "voucher-1" });
    assert.equal(ready.json().status, "FULFILLMENT_READY");
    const fulfilled = await post(app, `/api/v1/ancillary-order-items/${selected.json().ancillaryOrderItemId}/fulfillment-facts`, { factType: "MEAL_ISSUED", occurredAt: new Date().toISOString(), performedBy: "PROVIDER", idempotencyRef: "meal-1" });
    assert.equal(fulfilled.json().status, "FULFILLED");
    assert.deepEqual(publisher.findByEventType("AncillaryOfferQuoted")[0].payload.totalPrice, { currency: "CNY", minorUnits: 5000 });
    assert.equal(publisher.findByEventType("AncillaryOrderItemFulfilled").length, 1);
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
