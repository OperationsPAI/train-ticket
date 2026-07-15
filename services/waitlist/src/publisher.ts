import { createHash } from "node:crypto";
import { createEventEnvelope, type EventEnvelope, type EventPublisher } from "@trainticket/ts-kit";
import type { WaitlistEntrySnapshot } from "./domain.js";

export const WAITLIST_PRODUCER = "waitlist";

export type WaitlistEventType =
  | "WaitlistRequestCreated"
  | "WaitlistPaymentAuthorizationRequested"
  | "WaitlistQueued"
  | "WaitlistMatchStarted"
  | "WaitlistHoldAuthorized"
  | "WaitlistFulfilled"
  | "WaitlistCancelled"
  | "WaitlistExpired";

export type WaitlistEventPayload = Record<string, unknown>;

export function deterministicEventId(seed: string): string {
  const bytes = createHash("sha256").update(seed, "utf8").digest().subarray(0, 16);
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((value) => value.toString(16).padStart(2, "0")).join("");
  return `evt-${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function waitlistEventIdSeed(eventType: WaitlistEventType, waitlistRequestId: string, aggregateVersion: number): string {
  return `waitlist:${eventType}:${waitlistRequestId}:${aggregateVersion}`;
}

export function waitlistRequestCreated(entry: WaitlistEntrySnapshot, aggregateVersion: number, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistRequestCreated", entry, aggregateVersion, {
    ...basePayload(entry),
    deadline: entry.deadline,
    paymentGuaranteeRef: entry.paymentGuaranteeRef,
    itineraryRef: itineraryRef(entry),
    intentFingerprint: entry.intentFingerprint,
    status: "DRAFT",
    createdAt: entry.createdAt,
  }, correlationId, entry.createdAt);
}

export function waitlistPaymentAuthorizationRequested(entry: WaitlistEntrySnapshot, aggregateVersion: number, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistPaymentAuthorizationRequested", entry, aggregateVersion, {
    waitlistRequestId: entry.entryId,
    accountId: entry.accountId,
    travelerRef: travelerRef(entry),
    paymentGuaranteeRef: entry.paymentGuaranteeRef,
    requestedAt: entry.createdAt,
    status: "DRAFT",
  }, correlationId, entry.createdAt);
}

export function waitlistQueued(
  entry: WaitlistEntrySnapshot,
  aggregateVersion: number,
  correlationId: string | undefined,
  queuedAt: string,
  options: Readonly<{ journeyOrderRef?: string; requeueReason?: string }> = {},
): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistQueued", entry, aggregateVersion, {
    ...basePayload(entry),
    itineraryRef: itineraryRef(entry),
    intentFingerprint: entry.intentFingerprint,
    queuedAt,
    status: "QUEUED",
    journeyOrderRef: options.journeyOrderRef ?? entry.journeyOrderRef,
    requeueReason: options.requeueReason,
  }, correlationId, queuedAt);
}

export function waitlistMatchStarted(
  entry: WaitlistEntrySnapshot,
  aggregateVersion: number,
  matchedCapacityReleaseRef: string,
  correlationId: string | undefined,
  startedAt: string,
): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistMatchStarted", entry, aggregateVersion, {
    ...basePayload(entry),
    matchedCapacityReleaseRef,
    journeyOrderIdempotencyKey: entry.journeyOrderIdempotencyKey,
    startedAt,
    status: "MATCHING",
  }, correlationId, startedAt);
}

export function waitlistHoldAuthorized(entry: WaitlistEntrySnapshot, aggregateVersion: number, correlationId: string | undefined, authorizedAt: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistHoldAuthorized", entry, aggregateVersion, {
    ...basePayload(entry),
    authorizedAt,
    status: "MATCHING",
  }, correlationId, authorizedAt);
}

export function waitlistFulfilled(entry: WaitlistEntrySnapshot, aggregateVersion: number, journeyOrderRef: string, correlationId: string | undefined, fulfilledAt: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistFulfilled", entry, aggregateVersion, {
    ...basePayload(entry),
    journeyOrderRef,
    fulfilledAt,
    status: entry.status,
  }, correlationId, fulfilledAt);
}

export function waitlistCancelled(entry: WaitlistEntrySnapshot, aggregateVersion: number, reason: string, correlationId: string | undefined, cancelledAt: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistCancelled", entry, aggregateVersion, {
    ...basePayload(entry),
    cancelledAt,
    reason,
    status: "CANCELLED",
  }, correlationId, cancelledAt);
}

export function waitlistExpired(entry: WaitlistEntrySnapshot, aggregateVersion: number, correlationId: string | undefined, expiredAt: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistExpired", entry, aggregateVersion, {
    ...basePayload(entry),
    deadline: entry.deadline,
    expiredAt,
    status: "EXPIRED",
  }, correlationId, expiredAt);
}

export async function publishAll(publisher: EventPublisher, envelopes: readonly EventEnvelope[]): Promise<void> {
  for (const envelope of envelopes) await publisher.publish(envelope);
}

function waitlistEnvelope(
  eventType: WaitlistEventType,
  entry: WaitlistEntrySnapshot,
  aggregateVersion: number,
  payload: WaitlistEventPayload,
  correlationId?: string,
  occurredAt?: string,
): EventEnvelope<WaitlistEventPayload> {
  return createEventEnvelope({
    eventType,
    producer: WAITLIST_PRODUCER,
    payload,
    correlationId,
    occurredAt,
    eventId: deterministicEventId(waitlistEventIdSeed(eventType, entry.entryId, aggregateVersion)),
  });
}

function basePayload(entry: WaitlistEntrySnapshot): WaitlistEventPayload {
  return {
    waitlistRequestId: entry.entryId,
    accountId: entry.accountId,
    travelerRef: travelerRef(entry),
    segmentRef: entry.segmentRef,
    travelClass: entry.seatClass,
  };
}

function travelerRef(entry: WaitlistEntrySnapshot): string {
  return entry.travelerRefs[0] ?? "";
}

function itineraryRef(entry: WaitlistEntrySnapshot): string {
  return entry.itineraryRef ?? entry.segmentRef;
}
