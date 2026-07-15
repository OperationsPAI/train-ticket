import { createHash } from "node:crypto";
import { createEventEnvelope, type EventEnvelope, type EventPublisher } from "@trainticket/ts-kit";
import type { WaitlistEntrySnapshot } from "./domain.js";

export const WAITLIST_PRODUCER = "waitlist";

export type WaitlistEventPayload = Record<string, unknown>;
export type WaitlistEventType =
  | "WaitlistRequestCreated"
  | "WaitlistPaymentAuthorizationRequested"
  | "WaitlistQueued"
  | "WaitlistMatchStarted"
  | "WaitlistHoldAuthorized"
  | "WaitlistFulfilled"
  | "WaitlistCancelled"
  | "WaitlistExpired";

export function waitlistRequestCreated(entry: WaitlistEntrySnapshot, aggregateVersion: number, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistRequestCreated", entry, aggregateVersion, {
    waitlistRequestId: entry.entryId,
    accountId: entry.accountId,
    travelerRef: travelerRef(entry),
    segmentRef: entry.segmentRef,
    travelClass: entry.seatClass,
    deadline: entry.deadline,
    paymentGuaranteeRef: entry.paymentGuaranteeRef,
    itineraryRef: itineraryRef(entry),
    intentFingerprint: entry.intentFingerprint,
    status: "DRAFT",
    createdAt: entry.createdAt,
  }, correlationId, entry.createdAt);
}

export function waitlistPaymentAuthorizationRequested(entry: WaitlistEntrySnapshot, requestedAt: string, aggregateVersion: number, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistPaymentAuthorizationRequested", entry, aggregateVersion, {
    waitlistRequestId: entry.entryId,
    accountId: entry.accountId,
    travelerRef: travelerRef(entry),
    paymentGuaranteeRef: entry.paymentGuaranteeRef,
    requestedAt,
    status: "DRAFT",
  }, correlationId, requestedAt);
}

export function waitlistQueued(entry: WaitlistEntrySnapshot, queuedAt: string, aggregateVersion: number, correlationId?: string, options: { journeyOrderRef?: string; requeueReason?: string } = {}): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistQueued", entry, aggregateVersion, {
    waitlistRequestId: entry.entryId,
    accountId: entry.accountId,
    travelerRef: travelerRef(entry),
    segmentRef: entry.segmentRef,
    travelClass: entry.seatClass,
    itineraryRef: itineraryRef(entry),
    intentFingerprint: entry.intentFingerprint,
    queuedAt,
    status: "QUEUED",
    journeyOrderRef: options.journeyOrderRef ?? entry.journeyOrderRef,
    requeueReason: options.requeueReason,
  }, correlationId, queuedAt);
}

export function waitlistMatchStarted(entry: WaitlistEntrySnapshot, matchedCapacityReleaseRef: string, startedAt: string, aggregateVersion: number, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistMatchStarted", entry, aggregateVersion, {
    waitlistRequestId: entry.entryId,
    accountId: entry.accountId,
    travelerRef: travelerRef(entry),
    segmentRef: entry.segmentRef,
    travelClass: entry.seatClass,
    matchedCapacityReleaseRef,
    journeyOrderIdempotencyKey: entry.journeyOrderIdempotencyKey,
    startedAt,
    status: "MATCHING",
  }, correlationId, startedAt);
}

export function waitlistHoldAuthorized(entry: WaitlistEntrySnapshot, authorizedAt: string, aggregateVersion: number, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistHoldAuthorized", entry, aggregateVersion, {
    waitlistRequestId: entry.entryId,
    accountId: entry.accountId,
    travelerRef: travelerRef(entry),
    segmentRef: entry.segmentRef,
    travelClass: entry.seatClass,
    authorizedAt,
    status: "MATCHING",
  }, correlationId, authorizedAt);
}

export function waitlistFulfilled(entry: WaitlistEntrySnapshot, fulfilledAt: string, aggregateVersion: number, correlationId?: string, journeyOrderRef = entry.journeyOrderRef): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistFulfilled", entry, aggregateVersion, {
    waitlistRequestId: entry.entryId,
    accountId: entry.accountId,
    travelerRef: travelerRef(entry),
    segmentRef: entry.segmentRef,
    travelClass: entry.seatClass,
    journeyOrderRef,
    fulfilledAt,
    status: "FULFILLED",
  }, correlationId, fulfilledAt);
}

export function waitlistCancelled(entry: WaitlistEntrySnapshot, cancelledAt: string, reason: string, aggregateVersion: number, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistCancelled", entry, aggregateVersion, {
    waitlistRequestId: entry.entryId,
    accountId: entry.accountId,
    travelerRef: travelerRef(entry),
    segmentRef: entry.segmentRef,
    travelClass: entry.seatClass,
    cancelledAt,
    reason,
    status: "CANCELLED",
  }, correlationId, cancelledAt);
}

export function waitlistExpired(entry: WaitlistEntrySnapshot, expiredAt: string, aggregateVersion: number, correlationId?: string): EventEnvelope<WaitlistEventPayload> {
  return waitlistEnvelope("WaitlistExpired", entry, aggregateVersion, {
    waitlistRequestId: entry.entryId,
    accountId: entry.accountId,
    travelerRef: travelerRef(entry),
    segmentRef: entry.segmentRef,
    travelClass: entry.seatClass,
    deadline: entry.deadline,
    expiredAt,
    status: "EXPIRED",
  }, correlationId, expiredAt);
}

export async function publishAll(publisher: EventPublisher, envelopes: readonly EventEnvelope[]): Promise<void> {
  for (const envelope of envelopes) await publisher.publish(envelope);
}

export function waitlistEventIdSeed(eventType: WaitlistEventType, waitlistRequestId: string, aggregateVersion: number): string {
  return `waitlist:${eventType}:${waitlistRequestId}:${aggregateVersion}`;
}

function waitlistEnvelope(eventType: WaitlistEventType, entry: WaitlistEntrySnapshot, aggregateVersion: number, payload: WaitlistEventPayload, correlationId?: string, occurredAt?: string): EventEnvelope<WaitlistEventPayload> {
  const envelope = createEventEnvelope({
    eventId: deterministicEventId(waitlistEventIdSeed(eventType, entry.entryId, aggregateVersion)),
    eventType,
    producer: WAITLIST_PRODUCER,
    correlationId,
    occurredAt,
    payload,
  });
  return Object.freeze({
    ...envelope,
    eventId: waitlistEventIdSeed(eventType, entry.entryId, aggregateVersion),
  });
}

function deterministicEventId(seed: string): string {
  const hash = createHash("sha256").update(seed).digest();
  hash[6] = (hash[6] & 0x0f) | 0x70;
  hash[8] = (hash[8] & 0x3f) | 0x80;
  const hex = hash.subarray(0, 16).toString("hex");
  return `evt-${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;
}

function travelerRef(entry: WaitlistEntrySnapshot): string {
  return entry.travelerRefs[0] ?? "";
}

function itineraryRef(entry: WaitlistEntrySnapshot): string {
  return entry.itineraryRef ?? entry.segmentRef;
}
