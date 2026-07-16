import { fatalHandling, successfulHandling, transientHandling, type EventEnvelope, type EventHandlerResult } from "@trainticket/ts-kit";
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

export function createWaitlistEventHandler(service: WaitlistConsumedEventService) {
  return async (envelope: EventEnvelope): Promise<EventHandlerResult> => {
    try {
      if (envelope.eventType === "WaitlistCapacityFreed") {
        await service.handleCapacityFreed(parseCapacityFreed(envelope), envelope.correlationId, envelope);
        return successfulHandling();
      }
      if (envelope.eventType === "CapacityReleased") {
        // CapacityReleased is a lower-level hold-release fact. Fulfillment is
        // driven by WaitlistCapacityFreed when present, but consume releases that
        // carry segment metadata as a fallback; otherwise ack-skip to avoid
        // poisoning the consumer with unrelated release facts.
        const freed = tryParseCapacityFreed(envelope);
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
      if (error instanceof Error && /failed with status|fetch failed|ECONNREFUSED/u.test(error.message)) return transientHandling(error);
      return fatalHandling(error instanceof Error ? error : new Error("Waitlist event handling failed"));
    }
  };
}

// Lenient variant: returns undefined instead of throwing when the fulfillment
// fields are absent (e.g. a generic CapacityReleased that is not waitlist-scoped).
export function tryParseCapacityFreed(envelope: EventEnvelope): WaitlistCapacityFreed | undefined {
  const payload = envelope.payload as Record<string, unknown>;
  const segmentRef = payload.segmentRef ?? payload.serviceSegmentRef;
  const departureDate = payload.departureDate ?? dateFromCapacityPayload(payload);
  if (typeof segmentRef !== "string" || segmentRef.trim().length === 0) return undefined;
  if (typeof departureDate !== "string" || departureDate.trim().length === 0) return undefined;
  return parseCapacityFreed(envelope);
}

export function parseCapacityFreed(envelope: EventEnvelope): WaitlistCapacityFreed {
  const payload = envelope.payload as Record<string, unknown>;
  const segmentRef = string(payload.segmentRef ?? payload.serviceSegmentRef);
  const departureDate = string(payload.departureDate ?? dateFromCapacityPayload(payload));
  const freedSlots = number(payload.freedSlots ?? payload.availableSlots ?? payload.quantity ?? 1);
  const seatClass = typeof payload.seatClass === "string" ? payload.seatClass : typeof payload.classRef === "string" ? payload.classRef : undefined;
  return { segmentRef, departureDate, freedSlots, seatClass, capacityReleaseRef: envelope.eventId };
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

function dateFromCapacityPayload(payload: Record<string, unknown>): string | undefined {
  const serviceSegmentRef = typeof payload.serviceSegmentRef === "string" ? payload.serviceSegmentRef : typeof payload.segmentRef === "string" ? payload.segmentRef : undefined;
  return serviceSegmentRef ? dateFromSegmentRef(serviceSegmentRef) : undefined;
}

function dateFromSegmentRef(segmentRef: string): string | undefined {
  const match = /(?:^|-)\d{4}-\d{2}-\d{2}(?:-|$)/u.exec(segmentRef);
  return match?.[0].replace(/^-|-$/gu, "");
}
