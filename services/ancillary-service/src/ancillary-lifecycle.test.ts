import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { createEventEnvelope, InMemoryEventPublisher } from "@trainticket/ts-kit";

import { AncillaryApplicationService, InMemoryAncillaryRepository } from "./application.js";
import { AncillaryCatalogItem, AncillaryOffer, AncillaryOrderItem, type CatalogInput } from "./domain.js";

const CORR = "corr-018f0000-0000-7000-8000-000000000118";

function futureDeparture(hoursAhead = 24): string {
  return new Date(Date.now() + hoursAhead * 3_600_000).toISOString();
}

function catalogInput(overrides: Partial<CatalogInput> = {}): CatalogInput {
  const now = new Date();
  return {
    serviceType: "MEAL",
    displayName: "Hot meal",
    attachmentScope: "SEGMENT",
    modalities: ["TRAIN", "AIR"],
    supplierRef: "supplier-meal",
    price: { currency: "CNY", minorUnits: 2500 },
    salesWindow: { startAt: now.toISOString(), endAt: new Date(now.getTime() + 86_400_000).toISOString() },
    serviceWindow: { startAt: now.toISOString(), endAt: new Date(now.getTime() + 172_800_000).toISOString() },
    purchaseCutoffHoursBeforeDeparture: 2,
    eligibilityRuleVersion: "min-v1",
    requiresEntitlementRef: true,
    requiresSegmentRef: true,
    fulfillmentMethod: "VOUCHER",
    ...overrides,
  };
}

async function serviceWithPublishedCatalog(overrides: Partial<CatalogInput> = {}) {
  const publisher = new InMemoryEventPublisher();
  const service = new AncillaryApplicationService(new InMemoryAncillaryRepository(), publisher);
  const catalog = await service.createCatalogItem(catalogInput(overrides));
  const published = await service.publishCatalogItem(catalog.catalogItemId, { approvalRef: "approval", expectedVersion: catalog.version }, CORR);
  return { service, publisher, catalog: published };
}

async function selectedOrderItem(overrides: Partial<CatalogInput> = {}) {
  const { service, publisher, catalog } = await serviceWithPublishedCatalog(overrides);
  const draft = await service.draftOffer({ catalogItemId: catalog.catalogItemId, journeyOrderId: "ord-1", travelerRef: "trav-1", segmentRef: catalog.requiresSegmentRef ? "seg-1" : undefined, entitlementRef: "ent-1", departureAt: futureDeparture(), quantity: 2 });
  const quoted = await service.quoteOffer(draft.ancillaryOfferId, { expectedVersion: draft.offerVersion }, CORR);
  const item = await service.selectOffer(quoted.ancillaryOfferId, { journeyOrderId: "ord-1", expectedVersion: quoted.offerVersion }, CORR);
  return { service, publisher, catalog, quoted, item };
}

function assertLastEventId(publisher: InMemoryEventPublisher, eventType: string, aggregateId: string, version: number): void {
  const event = publisher.findByEventType(eventType).at(-1);
  assert.ok(event, `${eventType} was not published`);
  assert.equal(event.eventId, `ancillary-service:${eventType}:${aggregateId}:${version}`);
}

describe("ancillary domain state machines", () => {
  it("enforces the catalog five-state transition table", () => {
    const item = AncillaryCatalogItem.create(catalogInput(), new Date("2026-07-09T10:00:00.000Z"));
    assert.equal(item.toSnapshot().status, "DRAFT");
    assert.equal(item.publish(1, new Date("2026-07-09T10:01:00.000Z")), "DRAFT");
    assert.equal(item.toSnapshot().status, "PUBLISHED");
    assert.equal(item.suspend(2, new Date("2026-07-09T10:02:00.000Z")), "PUBLISHED");
    assert.equal(item.publish(3, new Date("2026-07-09T10:03:00.000Z")), "SUSPENDED");
    assert.equal(item.supersede("aci-replacement", 4, new Date("2026-07-09T10:04:00.000Z")), "PUBLISHED");
    assert.equal(item.toSnapshot().status, "SUPERSEDED");
    assert.throws(() => item.publish(5), /Cannot move catalog item/);

    const expiring = AncillaryCatalogItem.create(catalogInput({ attachmentScope: "JOURNEY", requiresSegmentRef: false }));
    expiring.publish(1);
    assert.equal(expiring.expire(2).toString(), "PUBLISHED");
    assert.equal(expiring.toSnapshot().status, "EXPIRED");
    assert.throws(() => AncillaryCatalogItem.create(catalogInput()).expire(1), /Cannot move catalog item/);
  });

  it("enforces offer state rules and expiry scan", async () => {
    const { service, catalog } = await serviceWithPublishedCatalog({ attachmentScope: "JOURNEY", requiresEntitlementRef: true, requiresSegmentRef: false });
    const ineligible = await service.draftOffer({ catalogItemId: catalog.catalogItemId, travelerRef: "trav", departureAt: new Date(Date.now() + 30 * 60_000).toISOString(), quantity: 1 });
    assert.equal(ineligible.status, "INELIGIBLE");
    assert.equal((await service.quoteOffer(ineligible.ancillaryOfferId, { expectedVersion: ineligible.offerVersion }, CORR)).status, "INELIGIBLE");

    const draft = await service.draftOffer({ catalogItemId: catalog.catalogItemId, travelerRef: "trav", entitlementRef: "ent", departureAt: futureDeparture(), quantity: 1 });
    const quoted = await service.quoteOffer(draft.ancillaryOfferId, { expectedVersion: draft.offerVersion, validitySeconds: 1 }, CORR);
    assert.equal(quoted.status, "QUOTED");
    assert.equal(await service.expireOffers(new Date(Date.parse(quoted.expiresAt) + 1), CORR), 1);
    assert.equal((await service.getOffer(quoted.ancillaryOfferId)).status, "EXPIRED");
  });

  it("enforces order-item nine-state lifecycle and rejects illegal transitions", async () => {
    const { service, item } = await selectedOrderItem({ attachmentScope: "JOURNEY", requiresSegmentRef: false });
    assert.equal(item.status, "SELECTED");
    const pending = await service.confirmOrderItem(item.ancillaryOrderItemId, { reasonCode: "SUPPLIER_PENDING" }, CORR);
    assert.equal(pending.status, "PENDING_CONFIRMATION");
    const confirmed = await service.confirmOrderItem(item.ancillaryOrderItemId, { confirmationRef: "conf" }, CORR);
    assert.equal(confirmed.status, "CONFIRMED");
    const ready = await service.fulfillmentReady(item.ancillaryOrderItemId, { providerRef: "voucher" }, CORR);
    assert.equal(ready.status, "FULFILLMENT_READY");
    const fulfilled = await service.recordFulfillmentFact(item.ancillaryOrderItemId, { factType: "MEAL_ISSUED", occurredAt: "2026-07-09T10:10:00.000Z", performedBy: "PROVIDER", idempotencyRef: "meal-ok" }, CORR);
    assert.equal(fulfilled.status, "FULFILLED");
    await assert.rejects(() => service.cancelOrderItem(item.ancillaryOrderItemId, { reasonCode: "TOO_LATE", source: "USER", expectedStatus: "CONFIRMED" }, CORR), /precondition/i);

    const failedChain = await selectedOrderItem({ attachmentScope: "JOURNEY", requiresSegmentRef: false });
    await failedChain.service.confirmOrderItem(failedChain.item.ancillaryOrderItemId, { reasonCode: "SUPPLIER_PENDING" }, CORR);
    const failed = await failedChain.service.recordFulfillmentFact(failedChain.item.ancillaryOrderItemId, { factType: "PROVIDER_FULFILLMENT_FAILED", occurredAt: "2026-07-09T10:11:00.000Z", performedBy: "PROVIDER", idempotencyRef: "fail", compensable: true }, CORR);
    assert.equal(failed.status, "FAILED");
    const cancelled = await failedChain.service.cancelOrderItem(failedChain.item.ancillaryOrderItemId, { reasonCode: "SUPPLIER_FAILED", source: "SUPPLIER" }, CORR);
    assert.equal(cancelled.status, "CANCELLED");
    const refundPending = await failedChain.service.suggestRefund(failedChain.item.ancillaryOrderItemId, { reasonCode: "SUPPLIER_FAILED" }, CORR);
    assert.equal(refundPending.ancillaryOrderItem.status, "REFUND_PENDING");
    const refunded = await failedChain.service.refunded(failedChain.item.ancillaryOrderItemId, { refundRef: "refund", refundedAmount: { currency: "CNY", minorUnits: 2500 }, refundedAt: "2026-07-09T10:12:00.000Z" }, CORR);
    assert.equal(refunded.status, "REFUNDED");
  });

  it("validates fulfillment factType and deduplicates repeated idempotency refs", async () => {
    const { service, publisher, item } = await selectedOrderItem({ attachmentScope: "JOURNEY", requiresSegmentRef: false });
    await service.confirmOrderItem(item.ancillaryOrderItemId, { reasonCode: "SUPPLIER_PENDING" }, CORR);
    const ready = await service.recordFulfillmentFact(item.ancillaryOrderItemId, { factType: "SERVICE_VOUCHER_ISSUED", occurredAt: "2026-07-09T10:10:00.000Z", performedBy: "SYSTEM", idempotencyRef: "voucher-1" }, CORR);
    assert.equal(ready.status, "FULFILLMENT_READY");
    const repeated = await service.recordFulfillmentFact(item.ancillaryOrderItemId, { factType: "SERVICE_VOUCHER_ISSUED", occurredAt: "2026-07-09T10:10:00.000Z", performedBy: "SYSTEM", idempotencyRef: "voucher-1" }, CORR);
    assert.equal(repeated.fulfillmentFacts.length, 1);
    assert.equal(publisher.findByEventType("AncillaryFulfillmentFactRecorded").length, 1);
    await assert.rejects(() => service.recordFulfillmentFact(item.ancillaryOrderItemId, { factType: "NOT_A_FACT" as never, occurredAt: "2026-07-09T10:10:00.000Z", performedBy: "SYSTEM", idempotencyRef: "bad" }, CORR), /factType/);
  });
});

describe("ancillary integration-event regressions", () => {
  it("uses deterministic event IDs for every published event type", async () => {
    const { service, publisher, catalog } = await serviceWithPublishedCatalog();
    assertLastEventId(publisher, "AncillaryCatalogItemPublished", catalog.catalogItemId, catalog.version);
    const suspended = await service.suspendCatalogItem(catalog.catalogItemId, { reasonCode: "OPS", expectedVersion: catalog.version }, CORR);
    assertLastEventId(publisher, "AncillaryCatalogItemSuspended", catalog.catalogItemId, suspended.version);
    const rep = await service.createCatalogItem(catalogInput());
    const superseded = await service.supersedeCatalogItem(catalog.catalogItemId, { replacementCatalogItemId: rep.catalogItemId, reasonCode: "NEW", expectedVersion: suspended.version }, CORR);
    assertLastEventId(publisher, "AncillaryCatalogItemSuperseded", catalog.catalogItemId, superseded.version);

    const active = await serviceWithPublishedCatalog({ attachmentScope: "JOURNEY", requiresSegmentRef: false });
    const draft = await active.service.draftOffer({ catalogItemId: active.catalog.catalogItemId, travelerRef: "trav", entitlementRef: "ent", departureAt: futureDeparture(), quantity: 1 });
    const quoted = await active.service.quoteOffer(draft.ancillaryOfferId, { expectedVersion: draft.offerVersion, validitySeconds: 1 }, CORR);
    assertLastEventId(active.publisher, "AncillaryOfferQuoted", quoted.ancillaryOfferId, quoted.offerVersion);
    await active.service.expireOffers(new Date(Date.parse(quoted.expiresAt) + 1), CORR);
    const expired = await active.service.getOffer(quoted.ancillaryOfferId);
    assertLastEventId(active.publisher, "AncillaryOfferExpired", expired.ancillaryOfferId, expired.offerVersion);

    const flow = await selectedOrderItem({ attachmentScope: "JOURNEY", requiresSegmentRef: false });
    assertLastEventId(flow.publisher, "AncillaryOrderItemSelected", flow.item.ancillaryOrderItemId, flow.item.aggregateVersion);
    const pending = await flow.service.confirmOrderItem(flow.item.ancillaryOrderItemId, { reasonCode: "SUPPLIER_PENDING" }, CORR);
    assertLastEventId(flow.publisher, "AncillaryOrderItemPendingConfirmation", flow.item.ancillaryOrderItemId, pending.aggregateVersion);
    const confirmed = await flow.service.confirmOrderItem(flow.item.ancillaryOrderItemId, { confirmationRef: "conf" }, CORR);
    assertLastEventId(flow.publisher, "AncillaryOrderItemConfirmed", flow.item.ancillaryOrderItemId, confirmed.aggregateVersion);
    const ready = await flow.service.fulfillmentReady(flow.item.ancillaryOrderItemId, { providerRef: "voucher" }, CORR);
    assertLastEventId(flow.publisher, "AncillaryOrderItemFulfillmentReady", flow.item.ancillaryOrderItemId, ready.aggregateVersion);
    const fulfilled = await flow.service.recordFulfillmentFact(flow.item.ancillaryOrderItemId, { factType: "MEAL_ISSUED", occurredAt: "2026-07-09T10:20:00.000Z", performedBy: "PROVIDER", idempotencyRef: "meal-done" }, CORR);
    const fact = fulfilled.fulfillmentFacts[0];
    assert.equal(flow.publisher.findByEventType("AncillaryFulfillmentFactRecorded").at(-1)?.eventId, `ancillary-service:AncillaryFulfillmentFactRecorded:${flow.item.ancillaryOrderItemId}:${fact.fulfillmentFactId}`);
    assertLastEventId(flow.publisher, "AncillaryOrderItemFulfilled", flow.item.ancillaryOrderItemId, fulfilled.aggregateVersion);

    const failed = await selectedOrderItem({ attachmentScope: "JOURNEY", requiresSegmentRef: false });
    await failed.service.confirmOrderItem(failed.item.ancillaryOrderItemId, { reasonCode: "SUPPLIER_PENDING" }, CORR);
    const failedSnapshot = await failed.service.recordFulfillmentFact(failed.item.ancillaryOrderItemId, { factType: "PROVIDER_FULFILLMENT_FAILED", occurredAt: "2026-07-09T10:21:00.000Z", performedBy: "PROVIDER", idempotencyRef: "provider-failed" }, CORR);
    assertLastEventId(failed.publisher, "AncillaryOrderItemFailed", failed.item.ancillaryOrderItemId, failedSnapshot.aggregateVersion);
    const cancelled = await failed.service.cancelOrderItem(failed.item.ancillaryOrderItemId, { reasonCode: "FAIL", source: "SUPPLIER" }, CORR);
    assertLastEventId(failed.publisher, "AncillaryOrderItemCancelled", failed.item.ancillaryOrderItemId, cancelled.aggregateVersion);
    const refundPending = await failed.service.suggestRefund(failed.item.ancillaryOrderItemId, { reasonCode: "FAIL" }, CORR);
    assertLastEventId(failed.publisher, "AncillaryOrderItemRefundPending", failed.item.ancillaryOrderItemId, refundPending.ancillaryOrderItem.aggregateVersion);
    const refunded = await failed.service.refunded(failed.item.ancillaryOrderItemId, { refundRef: "refund", refundedAmount: { currency: "CNY", minorUnits: 2500 }, refundedAt: "2026-07-09T10:22:00.000Z" }, CORR);
    assertLastEventId(failed.publisher, "AncillaryOrderItemRefunded", failed.item.ancillaryOrderItemId, refunded.aggregateVersion);
  });

  it("freezes catalog snapshots from the selected offer with unitPrice for quantity greater than one", async () => {
    const { service, publisher, catalog } = await serviceWithPublishedCatalog();
    const draft = await service.draftOffer({ catalogItemId: catalog.catalogItemId, travelerRef: "trav", segmentRef: "seg-1", entitlementRef: "ent", departureAt: futureDeparture(), quantity: 3 });
    const quoted = await service.quoteOffer(draft.ancillaryOfferId, { expectedVersion: draft.offerVersion }, CORR);
    await service.selectOffer(quoted.ancillaryOfferId, { journeyOrderId: "ord-quantity", expectedVersion: quoted.offerVersion }, CORR);
    const selected = publisher.findByEventType("AncillaryOrderItemSelected").at(-1)?.payload as any;
    assert.equal(selected.quantity, 3);
    assert.deepEqual(selected.catalogSnapshot.unitPrice, { currency: "CNY", minorUnits: 2500 });
    assert.equal(selected.payableAmount.minorUnits, 7500);
    assert.deepEqual(selected.catalogSnapshot.modalities, ["TRAIN", "AIR"]);
    assert.equal(selected.catalogSnapshot.supplierRef, "supplier-meal");
    assert.equal(selected.catalogSnapshot.purchaseCutoffHoursBeforeDeparture, 2);
    assert.equal(selected.catalogSnapshot.eligibilityRuleVersion, "min-v1");
    assert.equal(selected.catalogSnapshot.requiresEntitlementRef, true);
    assert.equal(selected.catalogSnapshot.requiresSegmentRef, true);
  });

  it("handles JourneyOrderCancelled orderId, ack-skips unknown orders, and suppresses replay effects", async () => {
    const { service, publisher, item } = await selectedOrderItem({ attachmentScope: "JOURNEY", requiresSegmentRef: false });
    const envelope = createEventEnvelope({ eventId: "evt-018f0000-0000-7000-8000-000000000999", eventType: "JourneyOrderCancelled", producer: "journey-order", correlationId: CORR, payload: { orderId: item.journeyOrderId } });
    assert.equal(await service.handleJourneyOrderCancelled(envelope), "ack");
    assert.equal((await service.getOrderItem(item.ancillaryOrderItemId)).status, "CANCELLED");
    assert.equal(publisher.findByEventType("AncillaryOrderItemCancelled").at(-1)?.payload.sourceEventId, envelope.eventId);
    const cancellationCount = publisher.findByEventType("AncillaryOrderItemCancelled").length;
    assert.equal(await service.handleJourneyOrderCancelled(envelope), "ack");
    assert.equal(publisher.findByEventType("AncillaryOrderItemCancelled").length, cancellationCount);
    assert.equal(await service.handleJourneyOrderCancelled(createEventEnvelope({ eventType: "JourneyOrderCancelled", producer: "journey-order", correlationId: CORR, payload: { orderId: "missing" } })), "ack");
  });
});
