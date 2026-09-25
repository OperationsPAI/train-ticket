import { OptimisticConcurrencyConflict, fatalHandling, successfulHandling, transientHandling, type EventEnvelope, type EventHandlerResult } from "@trainticket/ts-kit";
import type { CapacityReleaseResolver } from "./capacity-release.js";
import { normalizeFareClass, type TravelInterval } from "./domain.js";
import type { WaitlistApplicationService } from "./application.js";
import type { WaitlistCapacityFreed } from "./promotion.js";

export const CAPACITY_AVAILABILITY_STREAM = "events:capacity-availability";
export const JOURNEY_ORDER_STREAM = "events:journey-order";
export const SUBSCRIBED_STREAMS: readonly string[] = [CAPACITY_AVAILABILITY_STREAM, JOURNEY_ORDER_STREAM];
export const CONSUMER_GROUP = "waitlist";

export function consumerName(instanceId?: string): string {
  return `waitlist-${instanceId ?? "default"}`;
}

type WaitlistConsumedEventService = Readonly<{
  handleCapacityFreed(event: WaitlistCapacityFreed, correlationId: string | undefined, envelope: EventEnvelope): Promise<unknown> | unknown;
  handleJourneyOrderConfirmed(orderId: string, correlationId: string | undefined, envelope: EventEnvelope): Promise<unknown> | unknown;
  handleJourneyOrderCancelled(orderId: string, correlationId: string | undefined, envelope: EventEnvelope): Promise<unknown> | unknown;
}>;

const noCapacityReleaseResolver: CapacityReleaseResolver = { resolve: async () => undefined };

export function createWaitlistEventHandler(service: WaitlistConsumedEventService, capacityReleaseResolver: CapacityReleaseResolver = noCapacityReleaseResolver) {
  return async (envelope: EventEnvelope): Promise<EventHandlerResult> => {
    try {
      if (envelope.eventType === "WaitlistCapacityFreed") {
        const freed = tryParseCapacityFreed(envelope);
        if (freed) await service.handleCapacityFreed(freed, envelope.correlationId, envelope);
        return successfulHandling();
      }
      if (envelope.eventType === "CapacityReleased") {
        const freed = tryParseCapacityFreed(envelope) ?? await capacityReleaseResolver.resolve(envelope, envelope.correlationId);
        if (freed) await service.handleCapacityFreed(freed, envelope.correlationId, envelope);
        return successfulHandling();
      }
      if (envelope.eventType === "JourneyOrderConfirmed") {
        await service.handleJourneyOrderConfirmed(parseJourneyOrderId(envelope), envelope.correlationId, envelope);
        return successfulHandling();
      }
      if (envelope.eventType === "JourneyOrderCancelled") {
        await service.handleJourneyOrderCancelled(parseJourneyOrderId(envelope), envelope.correlationId, envelope);
        return successfulHandling();
      }
      return successfulHandling();
    } catch (error) {
      if (error instanceof OptimisticConcurrencyConflict) return transientHandling(error);
      if (error instanceof Error && "code" in error && /^(08|40|53|55P03|57P)/u.test(String(error.code))) return transientHandling(error);
      if (error instanceof Error && /failed with status|fetch failed|ECONNREFUSED/u.test(error.message)) return transientHandling(error);
      return fatalHandling(error instanceof Error ? error : new Error("Waitlist event handling failed"));
    }
  };
}

// Lenient variant: returns undefined instead of throwing when the fulfillment
// fields are absent (e.g. a generic CapacityReleased that is not waitlist-scoped).
export function tryParseCapacityFreed(envelope: EventEnvelope): WaitlistCapacityFreed | undefined {
  const payload = envelope.payload as Record<string, unknown>;
  if (typeof payload.segmentRef !== "string" || payload.segmentRef.trim().length === 0) return undefined;
  if (typeof payload.departureDate !== "string" || payload.departureDate.trim().length === 0) return undefined;
  if (typeof payload.scheduledServiceRef !== "string" || payload.scheduledServiceRef.trim().length === 0) return undefined;
  if (typeof payload.stopSequenceVersion !== "string" || payload.stopSequenceVersion.trim().length === 0) return undefined;
  if (typeof payload.seatClass !== "string" && typeof payload.classRef !== "string") return undefined;
  if (!tryInterval(payload.interval)) return undefined;
  return parseCapacityFreed(envelope);
}

export function parseCapacityFreed(envelope: EventEnvelope): WaitlistCapacityFreed {
  const payload = envelope.payload as Record<string, unknown>;
  const segmentRef = string(payload.segmentRef);
  const scheduledServiceRef = string(payload.scheduledServiceRef);
  const departureDate = string(payload.departureDate);
  const freedSlots = number(payload.freedSlots ?? payload.availableSlots ?? payload.quantity ?? 1);
  const seatClass = normalizeFareClass(string(payload.seatClass ?? payload.classRef));
  const interval = intervalValue(payload.interval);
  const stopSequenceVersion = string(payload.stopSequenceVersion);
  const intentFingerprint = typeof payload.intentFingerprint === "string" && payload.intentFingerprint.trim() ? payload.intentFingerprint.trim() : undefined;
  return { segmentRef, scheduledServiceRef, departureDate, freedSlots, seatClass, interval, stopSequenceVersion, intentFingerprint, capacityReleaseRef: envelope.eventId };
}

export function parseJourneyOrderId(envelope: EventEnvelope): string {
  const payload = envelope.payload as Record<string, unknown>;
  return string(payload.orderId ?? payload.journeyOrderRef);
}

function string(value: unknown): string {
  if (typeof value !== "string" || value.trim().length === 0) throw new Error("WaitlistCapacityFreed event is missing required string field");
  return value;
}

function number(value: unknown): number {
  const parsed = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(parsed) || parsed < 1) throw new Error("WaitlistCapacityFreed event must include freedSlots >= 1");
  return parsed;
}

function tryInterval(value: unknown): TravelInterval | undefined {
  try { return intervalValue(value); } catch { return undefined; }
}

function intervalValue(value: unknown): TravelInterval {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("WaitlistCapacityFreed event is missing interval");
  const body = value as Record<string, unknown>;
  const fromSeq = Number(body.fromSeq);
  const toSeq = Number(body.toSeq);
  if (!Number.isSafeInteger(fromSeq) || !Number.isSafeInteger(toSeq) || fromSeq < 0 || toSeq <= fromSeq) {
    throw new Error("WaitlistCapacityFreed event interval is invalid");
  }
  return { fromSeq, toSeq };
}
