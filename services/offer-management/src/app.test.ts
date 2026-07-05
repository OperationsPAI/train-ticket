import assert from "node:assert/strict";
import crypto from "node:crypto";
import { beforeEach, describe, it } from "node:test";

import { createApp, resetIdempotencyStore, resetOfferStore } from "./app.js";
import { InMemoryEventPublisher } from "./adapters/messaging/in-memory.js";
import { applyUpstreamEvent, InMemoryUpstreamStateRepository } from "./application/upstream-state.js";
import { type EventEnvelope } from "./ports/messaging.js";
import { type QuoteOfferCommand } from "./domain.js";

describe("offer-management service operational foundation", () => {
  beforeEach(() => {
    resetIdempotencyStore();
    resetOfferStore();
  });

  it("serves health, liveness, readiness, and metadata with request correlation headers", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "GET",
      url: "/readyz",
      headers: {
        "x-request-id": "req-offer-management-1",
        "x-correlation-id": "corr-offer-management-1",
      },
    });

    assert.equal(response.statusCode, 200);
    assert.equal(response.headers["x-request-id"], "req-offer-management-1");
    assert.equal(response.headers["x-correlation-id"], "corr-offer-management-1");
    assert.deepEqual(response.json(), { status: "ok", probe: "ready" });

    const health = await app.inject("/health");
    assert.equal(health.statusCode, 200);
    assert.equal(health.json().status, "ok");
    assert.equal(health.json().service.serviceId, "offer-management");

    assert.deepEqual((await app.inject("/live")).json(), { status: "ok", probe: "live" });
    assert.deepEqual((await app.inject("/livez")).json(), { status: "ok", probe: "live" });
    assert.deepEqual((await app.inject("/ready")).json(), { status: "ok", probe: "ready" });

    const metadata = await app.inject("/metadata");
    assert.equal(metadata.statusCode, 200);
    assert.equal(metadata.json().service.serviceId, "offer-management");
    assert.deepEqual(metadata.json().observability, { tracing: "opt-in", default: "noop" });
  });

  it("generates request and correlation ids when headers are absent", async () => {
    const app = createApp();

    const response = await app.inject("/livez");

    assert.equal(response.statusCode, 200);
    assert.equal(typeof response.headers["x-request-id"], "string");
    assert.notEqual(response.headers["x-request-id"], "");
    assert.match(String(response.headers["x-correlation-id"]), /^corr-/);
  });

  it("returns the standard error body for missing routes", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "GET",
      url: "/missing",
      headers: { "x-request-id": "req-offer-management-404" },
    });

    assert.equal(response.statusCode, 404);
    const body = response.json();
    assert.equal(body.code, "NOT_FOUND");
    assert.equal(body.message, "Route GET /missing was not found");
    assert.match(body.correlationId, /^corr-/);
    assert.deepEqual(body.details, {});
  });

  it("exposes a no-op-by-default opt-in tracing seam", async () => {
    const seenRequests: Array<{ requestId: string; correlationId: string }> = [];
    const endedSpans: Array<{ method: string; url: string; statusCode: number; requestId: string; correlationId: string }> = [];
    const app = createApp({
      onRequest: (context) => {
        seenRequests.push(context);
      },
      startSpan: (context) => {
        assert.equal(context.method, "GET");
        assert.equal(context.url, "/health");
        return {
          end: (result) => {
            endedSpans.push(result);
          },
        };
      },
    });

    const response = await app.inject({
      method: "GET",
      url: "/health",
      headers: {
        "x-request-id": "req-offer-management-trace",
        "x-correlation-id": "corr-offer-management-trace",
      },
    });

    assert.equal(response.statusCode, 200);
    assert.deepEqual(seenRequests, [{ requestId: "req-offer-management-trace", correlationId: "corr-offer-management-trace" }]);
    assert.deepEqual(endedSpans, [
      {
        method: "GET",
        url: "/health",
        statusCode: 200,
        requestId: "req-offer-management-trace",
        correlationId: "corr-offer-management-trace",
      },
    ]);

    assert.equal((await createApp().inject("/health")).statusCode, 200);
  });
});

describe("Offer Management HTTP API — POST /api/v1/offers", () => {
  beforeEach(() => {
    resetIdempotencyStore();
    resetOfferStore();
  });

  it("creates an offer and returns 201 with the expected response shape", async () => {
    const upstreamRepository = await seededUpstreamRepository();
    const app = createApp({}, { upstreamRepository });
    const idempotencyKey = uuidV7();

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/offers",
      headers: {
        "idempotency-key": idempotencyKey,
        "x-correlation-id": "corr-quote-1",
      },
      body: {
        accountId: "acc-123",
        channelId: "web",
        itineraryRef: "itin-456",
        travelerRefs: ["tvl-001", "tvl-002"],
        quoteRequestId: "qr-789",
      },
    });

    assert.equal(response.statusCode, 201);
    const body = response.json();
    assert.ok(body.offerId.startsWith("off-"));
    assert.equal(body.offerVersion, 1);
    assert.equal(typeof body.total, "object");
    assert.equal(typeof body.total.currency, "string");
    assert.equal(typeof body.total.minorUnits, "number");
    assert.equal(typeof body.expiresAt, "string");
    assert.equal(body.priceGuaranteeLevel, "FIXED_UNTIL_EXPIRY");
    assert.ok(body.downstreamReference.offerId.startsWith("off-"));
    assert.equal(body.downstreamReference.offerVersion, 1);
    assert.equal(body.itineraryRef, "itin-456");
    assert.equal(body.travelerSetHash, "tvl-001,tvl-002");
    assert.equal(response.headers["x-correlation-id"], "corr-quote-1");
    assert.ok(response.headers["x-request-id"]);
  });

  it("publishes the quoted domain event envelope through the configured port", async () => {
    const publisher = new InMemoryEventPublisher();
    const upstreamRepository = await seededUpstreamRepository();
    const app = createApp({}, { publisher, upstreamRepository });

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/offers",
      headers: {
        "idempotency-key": uuidV7(),
        "x-correlation-id": "corr-published-quote",
      },
      body: {
        accountId: "acc-123",
        channelId: "web",
        itineraryRef: "itin-456",
        travelerRefs: ["tvl-001"],
      },
    });

    assert.equal(response.statusCode, 201);
    assert.equal(publisher.published.length, 1);
    const [envelope] = publisher.published;
    assert.equal(envelope.producer, "offer-management");
    assert.equal(envelope.eventType, "OfferQuoted");
    assert.equal(envelope.schemaVersion, 1);
    assert.equal(envelope.correlationId, "corr-published-quote");
    assert.ok(envelope.eventId.startsWith("evt-"));
    assert.equal((envelope.payload as { offerId: string }).offerId, response.json().offerId);
    assert.deepEqual((envelope.payload as { total: unknown }).total, response.json().total);
    assert.deepEqual(Object.keys(envelope.payload).sort(), [
      "accountId",
      "availabilitySnapshotRefs",
      "channelId",
      "downstreamReference",
      "expiresAt",
      "fareQuoteRefs",
      "itineraryId",
      "itineraryVersion",
      "offerId",
      "offerVersion",
      "priceGuaranteeLevel",
      "priceSnapshotRef",
      "quoteRequestId",
      "ruleSnapshotRefs",
      "total",
      "travelerSetHash",
    ].sort());
    assert.equal(Object.hasOwn(envelope.payload, "boundaryProof"), false);
  });

  it("rejects request without Idempotency-Key with 400 VALIDATION_FAILED", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/offers",
      body: {
        accountId: "acc-123",
        channelId: "web",
        itineraryRef: "itin-456",
        travelerRefs: ["tvl-001"],
      },
    });

    assert.equal(response.statusCode, 400);
    assert.equal(response.json().code, "VALIDATION_FAILED");
    assert.ok(response.json().message.includes("Idempotency-Key"));
  });

  it("rejects malformed Idempotency-Key values with 400 VALIDATION_FAILED", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/offers",
      headers: { "idempotency-key": "not-a-uuid-v7" },
      body: {
        accountId: "acc-123",
        channelId: "web",
        itineraryRef: "itin-456",
        travelerRefs: ["tvl-001"],
      },
    });

    assert.equal(response.statusCode, 400);
    assert.equal(response.json().code, "VALIDATION_FAILED");
    assert.ok(response.json().message.includes("UUID v7"));
  });

  it("maps publish failures to 503 UNAVAILABLE", async () => {
    const publisher = new InMemoryEventPublisher();
    publisher.failNext = true;
    const upstreamRepository = await seededUpstreamRepository();
    const app = createApp({}, { publisher, upstreamRepository });

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/offers",
      headers: { "idempotency-key": uuidV7() },
      body: {
        accountId: "acc-123",
        channelId: "web",
        itineraryRef: "itin-456",
        travelerRefs: ["tvl-001"],
      },
    });

    assert.equal(response.statusCode, 503);
    assert.equal(response.json().code, "UNAVAILABLE");
  });

  it("rejects missing required fields with 400 VALIDATION_FAILED", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/offers",
      headers: { "idempotency-key": uuidV7() },
      body: { accountId: "acc-123" },
    });

    assert.equal(response.statusCode, 400);
    assert.equal(response.json().code, "VALIDATION_FAILED");
  });

  it("rejects empty travelerRefs with 400 VALIDATION_FAILED", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/offers",
      headers: { "idempotency-key": uuidV7() },
      body: {
        accountId: "acc-123",
        channelId: "web",
        itineraryRef: "itin-456",
        travelerRefs: [],
      },
    });

    assert.equal(response.statusCode, 400);
    assert.equal(response.json().code, "VALIDATION_FAILED");
  });

  it("surfaces domain invariant violations as 422 DOMAIN_RULE_VIOLATION", async () => {
    const app = createApp({}, {
      quoteCommandFactory: (request) => makeInvalidQuoteCommand(request),
    });

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/offers",
      headers: { "idempotency-key": uuidV7() },
      body: {
        accountId: "acc-123",
        channelId: "web",
        itineraryRef: "itin-456",
        travelerRefs: ["tvl-001"],
      },
    });

    assert.equal(response.statusCode, 422);
    assert.equal(response.json().code, "DOMAIN_RULE_VIOLATION");
    assert.equal(response.json().details.domainCode, "STALE_VALIDITY_WINDOW");
  });

  it("idempotent replay returns the original 201 response", async () => {
    const upstreamRepository = await seededUpstreamRepository();
    const app = createApp({}, { upstreamRepository });
    const idempotencyKey = uuidV7();

    const request = {
      method: "POST" as const,
      url: "/api/v1/offers",
      headers: { "idempotency-key": idempotencyKey },
      body: {
        accountId: "acc-123",
        channelId: "web",
        itineraryRef: "itin-456",
        travelerRefs: ["tvl-001"],
      },
    };

    const first = await app.inject(request);
    assert.equal(first.statusCode, 201);

    const second = await app.inject(request);
    assert.equal(second.statusCode, 201);
    assert.deepEqual(second.json(), first.json());
  });

  it("rejects idempotency key reused with different body using 422 IDEMPOTENCY_KEY_REUSED", async () => {
    const upstreamRepository = await seededUpstreamRepository();
    const app = createApp({}, { upstreamRepository });
    const idempotencyKey = uuidV7();

    await app.inject({
      method: "POST",
      url: "/api/v1/offers",
      headers: { "idempotency-key": idempotencyKey },
      body: {
        accountId: "acc-123",
        channelId: "web",
        itineraryRef: "itin-456",
        travelerRefs: ["tvl-001"],
      },
    });

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/offers",
      headers: { "idempotency-key": idempotencyKey },
      body: {
        accountId: "acc-456",
        channelId: "mobile",
        itineraryRef: "itin-789",
        travelerRefs: ["tvl-002"],
      },
    });

    assert.equal(response.statusCode, 422);
    assert.equal(response.json().code, "IDEMPOTENCY_KEY_REUSED");
  });
});

function makeInvalidQuoteCommand(request: { accountId: string; channelId: string; itineraryRef: string; travelerRefs: readonly string[] }): QuoteOfferCommand {
  const quotedAt = new Date("2026-07-05T10:00:00.000Z");
  const staleExpiry = new Date("2026-07-05T09:59:00.000Z");
  return {
    offerId: `off-${uuidV7()}`,
    quoteRequestId: "quote-request-invalid",
    accountId: request.accountId,
    channelId: request.channelId,
    quotedAt,
    validityWindow: { startsAt: quotedAt, expiresAt: staleExpiry },
    itinerary: {
      itineraryId: request.itineraryRef,
      itineraryVersion: "v1",
      sourceContext: "TripPlanning",
      segmentRefs: ["seg-1"],
    },
    passengerMix: {
      travelerSetHash: request.travelerRefs.join(","),
      travelers: request.travelerRefs.map((travelerId) => ({ travelerId, travelerType: "ADULT" as const })),
    },
    items: [{
      offerItemId: "item-1",
      mode: "train",
      segmentRef: "seg-1",
      itemPrice: { currency: "CNY", amountMinor: 100 },
      availabilitySnapshot: {
        snapshotId: "availability-1",
        snapshotVersion: "v1",
        sourceContext: "CapacityAvailability",
        capturedAt: quotedAt,
        expiresAt: new Date("2026-07-05T10:15:00.000Z"),
        sellable: true,
        status: "AVAILABLE",
        confidence: "confirmed-snapshot",
      },
      fareSnapshot: {
        fareQuoteRef: "fare-1",
        ruleSnapshotRef: "rule-1",
        pricingVersion: "pricing-v1",
        ruleVersion: "rule-v1",
        sourceContext: "FarePricing",
        capturedAt: quotedAt,
        expiresAt: new Date("2026-07-05T10:15:00.000Z"),
      },
    }],
    priceSnapshot: {
      snapshotId: "price-1",
      fareQuoteRef: "fare-1",
      capturedAt: quotedAt,
      expiresAt: new Date("2026-07-05T10:15:00.000Z"),
      guaranteeLevel: "FixedUntilExpiry",
      currency: "CNY",
      subtotal: { currency: "CNY", amountMinor: 100 },
      taxes: [],
      fees: [],
      discounts: [],
      total: { currency: "CNY", amountMinor: 100 },
    },
  };
}

async function seededUpstreamRepository(): Promise<InMemoryUpstreamStateRepository> {
  const repository = new InMemoryUpstreamStateRepository();
  const now = new Date();
  const expiresAt = new Date(now.getTime() + 60 * 60 * 1000).toISOString();
  const occurredAt = now.toISOString();

  await applyUpstreamEvent(repository, makeEnvelope("ItineraryProposed", "trip-planning", {
    intentRef: "intent-1",
    planningSnapshotRefs: ["plan-1"],
    itineraries: [{
      itineraryRef: "itin-456",
      itineraryVersion: "itinerary-v1",
      legs: [{ servicePlanRef: "sp-1", serviceSegmentRef: "seg-1", originStopRef: "stop-a", destinationStopRef: "stop-b", departureTime: occurredAt, arrivalTime: expiresAt, mode: "train" }],
      availabilitySnapshots: [{ segmentRef: "seg-1", snapshotId: "availability-1", snapshotVersion: "capacity-v1", capturedAt: occurredAt, expiresAt, sellable: true, status: "AVAILABLE", confidence: "confirmed-snapshot" }],
    }],
  }));
  await applyUpstreamEvent(repository, makeEnvelope("FareQuoteComputed", "fare-pricing", {
    quoteId: "fq-1",
    inputHash: "intent-1",
    travelerRefs: ["tvl-001", "tvl-002"],
    channel: "web",
    currency: "CNY",
    status: "QUOTED",
    validFrom: occurredAt,
    validUntil: expiresAt,
    breakdown: { subtotal: { currency: "CNY", minorUnits: 20000 }, total: { currency: "CNY", minorUnits: 20000 }, priceSnapshotRef: "price-1" },
    ruleSnapshot: { ruleSnapshotRef: "rule-1", pricingVersion: "pricing-v1", ruleVersion: "rule-v1" },
  }));
  await applyUpstreamEvent(repository, makeEnvelope("FareQuoteComputed", "fare-pricing", {
    quoteId: "fq-2",
    inputHash: "intent-1",
    travelerRefs: ["tvl-001"],
    channel: "web",
    currency: "CNY",
    status: "QUOTED",
    validFrom: occurredAt,
    validUntil: expiresAt,
    breakdown: { subtotal: { currency: "CNY", minorUnits: 10000 }, total: { currency: "CNY", minorUnits: 10000 }, priceSnapshotRef: "price-2" },
    ruleSnapshot: { ruleSnapshotRef: "rule-2", pricingVersion: "pricing-v1", ruleVersion: "rule-v1" },
  }));
  await applyUpstreamEvent(repository, makeEnvelope("TravelerSnapshotUpdated", "traveler-profile", { travelerId: "tvl-001", snapshotVersion: "1", updatedAt: occurredAt, travelerType: "ADULT" }));
  await applyUpstreamEvent(repository, makeEnvelope("TravelerSnapshotUpdated", "traveler-profile", { travelerId: "tvl-002", snapshotVersion: "1", updatedAt: occurredAt, travelerType: "ADULT" }));
  return repository;
}

function makeEnvelope(eventType: string, producer: string, payload: Record<string, unknown>): EventEnvelope {
  return {
    eventId: `evt-${uuidV7()}`,
    eventType,
    schemaVersion: 1,
    producer,
    correlationId: `corr-${uuidV7()}`,
    occurredAt: new Date().toISOString(),
    payload,
  };
}

function uuidV7(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  const timestamp = BigInt(Date.now());

  bytes[0] = Number((timestamp >> 40n) & 0xffn);
  bytes[1] = Number((timestamp >> 32n) & 0xffn);
  bytes[2] = Number((timestamp >> 24n) & 0xffn);
  bytes[3] = Number((timestamp >> 16n) & 0xffn);
  bytes[4] = Number((timestamp >> 8n) & 0xffn);
  bytes[5] = Number(timestamp & 0xffn);
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;

  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

describe("Offer Management HTTP API — GET /api/v1/offers/:offerId", () => {
  beforeEach(() => {
    resetIdempotencyStore();
    resetOfferStore();
  });

  it("returns 200 with full offer details for an existing offer", async () => {
    const upstreamRepository = await seededUpstreamRepository();
    const app = createApp({}, { upstreamRepository });
    const idempotencyKey = uuidV7();

    // Create an offer first
    const createResponse = await app.inject({
      method: "POST",
      url: "/api/v1/offers",
      headers: { "idempotency-key": idempotencyKey },
      body: {
        accountId: "acc-123",
        channelId: "web",
        itineraryRef: "itin-456",
        travelerRefs: ["tvl-001"],
      },
    });
    const { offerId } = createResponse.json();

    // Get the offer
    const getResponse = await app.inject({
      method: "GET",
      url: `/api/v1/offers/${offerId}`,
    });

    assert.equal(getResponse.statusCode, 200);
    const body = getResponse.json();
    assert.equal(body.offerId, offerId);
    assert.equal(body.offerVersion, 1);
    assert.equal(body.status, "Quoted");
    assert.equal(body.accountId, "acc-123");
    assert.equal(body.channelId, "web");
    assert.equal(body.itineraryRef, "itin-456");
    assert.equal(typeof body.total, "object");
    assert.equal(typeof body.expiresAt, "string");
    assert.equal(typeof body.priceGuaranteeLevel, "string");
    assert.equal(typeof body.downstreamReference, "object");
    assert.equal(typeof body.items, "object");
    assert.equal(typeof body.createdAt, "string");
  });

  it("returns 404 NOT_FOUND for a non-existent offer", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "GET",
      url: "/api/v1/offers/non-existent-id",
    });

    assert.equal(response.statusCode, 404);
    assert.equal(response.json().code, "NOT_FOUND");
    assert.ok(response.json().message.includes("non-existent-id"));
  });
});
