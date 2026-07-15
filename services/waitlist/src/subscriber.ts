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
      if (envelope.eventType === "CapacityReleased" || envelope.eventType === "WaitlistCapacityFreed") {
        await service.handleCapacityFreed(parseCapacityFreed(envelope), envelope.correlationId, envelope);
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

export function parseCapacityFreed(envelope: EventEnvelope): WaitlistCapacityFreed {
  const payload = envelope.payload as Record<string, unknown>;
  const segmentRef = string(payload.segmentRef);
  const departureDate = string(payload.departureDate);
  const freedSlots = number(payload.freedSlots ?? payload.availableSlots ?? payload.quantity ?? 1);
  const seatClass = typeof payload.seatClass === "string" ? payload.seatClass : typeof payload.classRef === "string" ? payload.classRef : undefined;
  return { segmentRef, departureDate, freedSlots, seatClass, eventId: envelope.eventId };
}

export function parseJourneyOrderId(envelope: EventEnvelope): string {
  return string((envelope.payload as Record<string, unknown>).orderId);
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
