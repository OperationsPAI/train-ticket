import { createHash } from "node:crypto";
import { createEventEnvelope, type EventEnvelope, type EventPublisher } from "@trainticket/ts-kit";
import type { WaitlistEntrySnapshot } from "./domain.js";

export const WAITLIST_PRODUCER = "waitlist";

export type WaitlistEventPayload = Record<string, unknown>;

export function waitlistRequestCreated(entry: WaitlistEntrySnapshot, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistRequestCreated", {
    ...requestPayload(entry),
    status: "DRAFT",
    createdAt: entry.createdAt,
  }, correlationId, 1);
}

export function waitlistPaymentAuthorizationRequested(entry: WaitlistEntrySnapshot, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistPaymentAuthorizationRequested", {
    waitlistRequestId: entry.entryId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRefs[0] ?? "",
    segmentRef: entry.segmentRef,
    paymentGuaranteeRef: paymentGuaranteeRef(entry),
    requestedAt: entry.createdAt,
    status: "DRAFT",
  }, correlationId, 2);
}

export function waitlistQueued(entry: WaitlistEntrySnapshot, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistQueued", {
    ...basePayload(entry),
    itineraryRef: itineraryRef(entry),
    intentFingerprint: intentFingerprint(entry),
    queuedAt: entry.createdAt,
    status: "QUEUED",
    journeyOrderRef: entry.journeyOrderRef,
  }, correlationId, Math.max(3, entry.aggregateVersion ?? 1));
}

export function waitlistMatchStarted(entry: WaitlistEntrySnapshot, matchedCapacityReleaseRef: string, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistMatchStarted", {
    ...basePayload(entry),
    matchedCapacityReleaseRef,
    journeyOrderIdempotencyKey: entry.journeyOrderIdempotencyKey,
    startedAt: entry.offeredAt ?? new Date().toISOString(),
    status: "MATCHING",
  }, correlationId, entry.aggregateVersion ?? 1);
}

export function waitlistExpired(entry: WaitlistEntrySnapshot, expiredAt: string, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistExpired", {
    ...basePayload(entry),
    deadline: entry.deadline ?? entry.offerExpiresAt ?? expiredAt,
    expiredAt,
    status: "EXPIRED",
  }, correlationId, entry.aggregateVersion ?? 1);
}

export function waitlistFulfilled(entry: WaitlistEntrySnapshot, fulfilledAt: string, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistFulfilled", {
    ...basePayload(entry),
    journeyOrderRef: entry.journeyOrderRef,
    fulfilledAt,
    status: "FULFILLED",
  }, correlationId, entry.aggregateVersion ?? 1);
}

export function waitlistCancelled(entry: WaitlistEntrySnapshot, cancelledAt: string, reason: string, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistCancelled", {
    ...basePayload(entry),
    cancelledAt,
    reason,
    status: "CANCELLED",
  }, correlationId, entry.aggregateVersion ?? 1);
}

export async function publishAll(publisher: EventPublisher, envelopes: readonly EventEnvelope[]): Promise<void> {
  for (const envelope of envelopes) await publisher.publish(envelope);
}

function requestPayload(entry: WaitlistEntrySnapshot): WaitlistEventPayload {
  return withoutUndefined({
    ...basePayload(entry),
    deadline: deadline(entry),
    paymentGuaranteeRef: paymentGuaranteeRef(entry),
    itineraryRef: itineraryRef(entry),
    intentFingerprint: intentFingerprint(entry),
  });
}

function basePayload(entry: WaitlistEntrySnapshot): WaitlistEventPayload {
  return withoutUndefined({
    waitlistRequestId: entry.entryId,
    accountId: entry.accountId,
    travelerRef: entry.travelerRefs[0] ?? "",
    segmentRef: entry.segmentRef,
    travelClass: entry.seatClass,
  });
}

function waitlistEnvelope(eventType: string, payload: WaitlistEventPayload, correlationId: string | undefined, aggregateVersion: number): EventEnvelope<WaitlistEventPayload> {
  const wirePayload = withoutUndefined(payload);
  return createEventEnvelope({
    eventType,
    producer: WAITLIST_PRODUCER,
    payload: wirePayload,
    correlationId,
    eventId: deterministicEventId(eventType, wirePayload, aggregateVersion),
    causationId: deterministicCausationId(eventType, wirePayload, aggregateVersion),
  });
}

function deterministicEventId(eventType: string, payload: WaitlistEventPayload, aggregateVersion: number): string {
  return deterministicUuidV7(`waitlist:${eventType}:${String(payload.waitlistRequestId)}:${aggregateVersion}`);
}

function deterministicCausationId(eventType: string, payload: WaitlistEventPayload, aggregateVersion: number): string {
  return deterministicUuidV7(`waitlist-causation:${eventType}:${String(payload.waitlistRequestId)}:${aggregateVersion}:${String(payload.status)}`);
}

function deterministicUuidV7(seed: string): string {
  const hex = createHash("sha256").update(seed).digest("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-7${hex.slice(13, 16)}-8${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}

function deadline(entry: WaitlistEntrySnapshot): string {
  return entry.deadline ?? entry.offerExpiresAt ?? `${entry.departureDate}T23:59:59.000Z`;
}

function paymentGuaranteeRef(entry: WaitlistEntrySnapshot): string {
  return entry.paymentGuaranteeRef ?? `pay-auth-${entry.entryId}`;
}

function itineraryRef(entry: WaitlistEntrySnapshot): string {
  return entry.itineraryRef ?? entry.segmentRef;
}

function intentFingerprint(entry: WaitlistEntrySnapshot): string {
  return entry.intentFingerprint ?? `${entry.travelerRefs[0] ?? ""}:${entry.segmentRef}:${entry.departureDate}:${entry.seatClass}`;
}

function withoutUndefined<T extends Record<string, unknown>>(value: T): T {
  return Object.fromEntries(Object.entries(value).filter(([, field]) => field !== undefined)) as T;
}
