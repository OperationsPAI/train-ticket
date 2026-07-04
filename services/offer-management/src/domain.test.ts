import assert from "node:assert/strict";
import crypto from "node:crypto";
import { describe, it } from "node:test";

import {
  DomainError,
  Offer,
  nonMutationBoundaryProof,
  type QuoteOfferCommand,
} from "./domain.js";

const quotedAt = new Date("2026-07-03T10:00:00.000Z");
const expiresAt = new Date("2026-07-03T10:10:00.000Z");
const upstreamExpiresAt = new Date("2026-07-03T10:15:00.000Z");

function money(amountMinor: number) {
  return { amountMinor, currency: "CNY" } as const;
}

function quoteCommand(overrides: Partial<QuoteOfferCommand> = {}): QuoteOfferCommand {
  const base: QuoteOfferCommand = {
    offerId: "offer-123",
    quoteRequestId: "quote-request-abc",
    accountId: "account-1",
    channelId: "web",
    quotedAt,
    validityWindow: {
      startsAt: new Date("2026-07-03T09:59:00.000Z"),
      expiresAt,
    },
    itinerary: {
      itineraryId: "itinerary-1",
      itineraryVersion: "it-v1",
      sourceContext: "TripPlanning",
      segmentRefs: ["segment-1"],
    },
    passengerMix: {
      travelerSetHash: "traveler-hash-1",
      travelers: [{ travelerId: "traveler-1", travelerType: "ADULT" }],
    },
    items: [
      {
        offerItemId: "item-1",
        mode: "train",
        segmentRef: "segment-1",
        itemPrice: money(100),
        availabilitySnapshot: {
          snapshotId: "availability-snapshot-1",
          snapshotVersion: "capacity-v4",
          sourceContext: "CapacityAvailability",
          capturedAt: new Date("2026-07-03T09:58:00.000Z"),
          expiresAt: upstreamExpiresAt,
          sellable: true,
          status: "AVAILABLE",
          confidence: "confirmed-snapshot",
        },
        fareSnapshot: {
          fareQuoteRef: "fare-quote-1",
          ruleSnapshotRef: "rule-snapshot-1",
          pricingVersion: "pricing-v7",
          ruleVersion: "rule-v3",
          sourceContext: "FarePricing",
          capturedAt: new Date("2026-07-03T09:58:30.000Z"),
          expiresAt: upstreamExpiresAt,
        },
      },
    ],
    priceSnapshot: {
      snapshotId: "price-snapshot-1",
      fareQuoteRef: "fare-quote-1",
      capturedAt: new Date("2026-07-03T09:58:30.000Z"),
      expiresAt: upstreamExpiresAt,
      guaranteeLevel: "FixedUntilExpiry",
      currency: "CNY",
      subtotal: money(100),
      taxes: [{ code: "tax", description: "rail tax", amount: money(5) }],
      fees: [{ code: "service", description: "service fee", amount: money(2) }],
      discounts: [{ code: "promo", description: "promotion", amount: money(3) }],
      total: money(104),
    },
    riskDisclosures: [
      {
        disclosureId: "risk-1",
        severity: "warning",
        messageCode: "SELF_TRANSFER_RISK",
        templateVersion: "risk-template-v1",
        relatedRef: "segment-1",
        mustAccept: true,
        text: "Self transfer is not protected by the platform.",
      },
    ],
  };
  return { ...base, ...overrides };
}

function expectDomainError(fn: () => unknown, code: string): void {
  assert.throws(fn, (error: unknown) => error instanceof DomainError && error.code === code);
}

describe("Offer Management domain foundation", () => {
  it("quotes an immutable offer from itinerary, availability, fare/rule, price, passenger, and risk snapshots", () => {
    const { offer, event } = Offer.quote(quoteCommand());

    assert.equal(offer.id, "offer-123");
    assert.equal(offer.version, 1);
    assert.equal(offer.status, "Quoted");
    assert.equal(event.type, "OfferQuoted");
    assert.equal(event.eventType, "OfferQuoted");
    assert.equal(event.schemaVersion, 1);
    assert.equal(event.producer, "offer-management");
    assert.ok(event.eventId.startsWith("evt-"));
    assert.deepEqual(event.availabilitySnapshotRefs, ["availability-snapshot-1"]);
    assert.deepEqual(event.fareQuoteRefs, ["fare-quote-1"]);
    assert.deepEqual(event.ruleSnapshotRefs, ["rule-snapshot-1"]);
    assert.equal(event.downstreamReference.priceSnapshotRef, "price-snapshot-1");
    assert.equal(event.downstreamReference.ruleSnapshotRef, "rule-snapshot-1");
    assert.deepEqual(event.boundaryProof, nonMutationBoundaryProof());

    const snapshot = offer.toSnapshot();
    assert.throws(() => {
      (snapshot.priceSnapshot.total as { amountMinor: number }).amountMinor = 999;
    }, TypeError);
    assert.equal(offer.toSnapshot().priceSnapshot.total.amountMinor, 104);
  });

  it("rejects missing or stale snapshots and offer validity beyond upstream TTLs", () => {
    expectDomainError(
      () => Offer.quote(quoteCommand({ items: [] })),
      "MISSING_OFFER_ITEMS",
    );

    expectDomainError(
      () => Offer.quote(quoteCommand({ priceSnapshot: { ...quoteCommand().priceSnapshot, expiresAt: quotedAt } })),
      "STALE_PRICE_SNAPSHOT",
    );

    expectDomainError(
      () => Offer.quote(quoteCommand({
        items: [{ ...quoteCommand().items[0], availabilitySnapshot: { ...quoteCommand().items[0].availabilitySnapshot, expiresAt: quotedAt } }],
      })),
      "STALE_AVAILABILITY_SNAPSHOT",
    );

    expectDomainError(
      () => Offer.quote(quoteCommand({
        items: [{ ...quoteCommand().items[0], fareSnapshot: { ...quoteCommand().items[0].fareSnapshot, expiresAt: quotedAt } }],
      })),
      "STALE_FARE_RULE_SNAPSHOT",
    );

    expectDomainError(
      () => Offer.quote(quoteCommand({ validityWindow: { startsAt: quotedAt, expiresAt: new Date("2026-07-03T10:20:00.000Z") } })),
      "PRICE_SNAPSHOT_TTL_TOO_SHORT",
    );
  });

  it("rejects unsellable availability and inconsistent price totals", () => {
    expectDomainError(
      () => Offer.quote(quoteCommand({
        items: [{ ...quoteCommand().items[0], availabilitySnapshot: { ...quoteCommand().items[0].availabilitySnapshot, sellable: false } }],
      })),
      "UNSELLABLE_AVAILABILITY_SNAPSHOT",
    );

    expectDomainError(
      () => Offer.quote(quoteCommand({ priceSnapshot: { ...quoteCommand().priceSnapshot, total: money(999) } })),
      "PRICE_TOTAL_MISMATCH",
    );
  });

  it("enforces expiration and order validation without accepting stale or mismatched snapshots", () => {
    const { offer } = Offer.quote(quoteCommand());

    const token = offer.validateForOrder({
      accountId: "account-1",
      channelId: "web",
      travelerSetHash: "traveler-hash-1",
      priceSnapshotRef: "price-snapshot-1",
      acceptedDisclosureIds: ["risk-1"],
      at: new Date("2026-07-03T10:05:00.000Z"),
    });
    assert.equal(token.offerId, "offer-123");
    assert.equal(token.offerVersion, 1);

    expectDomainError(
      () => offer.validateForOrder({
        accountId: "account-1",
        channelId: "web",
        travelerSetHash: "traveler-hash-1",
        priceSnapshotRef: "price-snapshot-1",
        acceptedDisclosureIds: [],
        at: new Date("2026-07-03T10:05:00.000Z"),
      }),
      "DISCLOSURE_NOT_ACCEPTED",
    );

    expectDomainError(
      () => offer.validateForOrder({
        accountId: "account-1",
        channelId: "web",
        travelerSetHash: "traveler-hash-1",
        priceSnapshotRef: "other-price-snapshot",
        acceptedDisclosureIds: ["risk-1"],
        at: new Date("2026-07-03T10:05:00.000Z"),
      }),
      "PRICE_SNAPSHOT_MISMATCH",
    );

    expectDomainError(
      () => offer.validateForOrder({
        accountId: "account-1",
        channelId: "web",
        travelerSetHash: "traveler-hash-1",
        priceSnapshotRef: "price-snapshot-1",
        acceptedDisclosureIds: ["risk-1"],
        at: expiresAt,
      }),
      "OFFER_EXPIRED",
    );

    const { offer: expiredOffer, event } = offer.expire(expiresAt, "VALIDITY_WINDOW_ELAPSED");
    assert.equal(expiredOffer.status, "Expired");
    assert.equal(event.type, "OfferExpired");
    assert.equal(event.previousStatus, "Quoted");
    assert.deepEqual(event.boundaryProof, nonMutationBoundaryProof());
  });

  it("proves quoting does not mutate CapacityHold, PaymentIntent, JourneyOrder, or Entitlement state", () => {
    const externalState = {
      capacityHold: { id: "hold-1", status: "Held", version: 2 },
      paymentIntent: { id: "payment-1", status: "NotCreated" },
      journeyOrder: { id: "order-1", status: "Draft" },
      entitlement: { id: "ticket-1", status: "NotIssued" },
    };
    const before = structuredClone(externalState);

    const { event } = Offer.quote(quoteCommand());

    assert.deepEqual(externalState, before);
    assert.equal(event.boundaryProof.inventoryLocked, false);
    assert.equal(event.boundaryProof.capacityHoldMutated, false);
    assert.equal(event.boundaryProof.paymentIntentMutated, false);
    assert.equal(event.boundaryProof.journeyOrderMutated, false);
    assert.equal(event.boundaryProof.entitlementMutated, false);
    assert.deepEqual(event.boundaryProof.crossContextWriteTargets, []);
  });
});
