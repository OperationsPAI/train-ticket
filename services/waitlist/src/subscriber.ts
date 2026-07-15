import { fatalHandling, successfulHandling, transientHandling, type EventEnvelope, type EventHandlerResult } from "@trainticket/ts-kit";
import type { JourneyOrderCancelledFact, JourneyOrderFact, WaitlistApplicationService } from "./application.js";
import type { WaitlistCapacityFreed } from "./promotion.js";

export const CAPACITY_AVAILABILITY_STREAM = "events:capacity-availability";
export const JOURNEY_ORDER_STREAM = "events:journey-order";
export const SUBSCRIBED_STREAMS: readonly string[] = [CAPACITY_AVAILABILITY_STREAM, JOURNEY_ORDER_STREAM];
export const CONSUMER_GROUP = "waitlist";

type WaitlistEventConsumer = Pick<WaitlistApplicationService, "handleCapacityFreed" | "handleJourneyOrderConfirmed" | "handleJourneyOrderCancelled">;

export function consumerName(instanceId?: string): string {
  return `waitlist-${instanceId ?? "default"}`;
}

export function createWaitlistEventHandler(service: WaitlistEventConsumer) {
  return async (envelope: EventEnvelope): Promise<EventHandlerResult> => {
    try {
      if (envelope.eventType === "CapacityReleased" || envelope.eventType === "WaitlistCapacityFreed") {
        await service.handleCapacityFreed(parseCapacityFreed(envelope), envelope.correlationId);
      } else if (envelope.eventType === "JourneyOrderConfirmed") {
        await service.handleJourneyOrderConfirmed(parseJourneyOrderFact(envelope), envelope.correlationId);
      } else if (envelope.eventType === "JourneyOrderCancelled") {
        await service.handleJourneyOrderCancelled(parseJourneyOrderCancelledFact(envelope), envelope.correlationId);
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
  const segmentRef = string(payload.segmentRef, "WaitlistCapacityFreed event is missing segmentRef");
  const departureDate = string(payload.departureDate, "WaitlistCapacityFreed event is missing departureDate");
  const freedSlots = number(payload.freedSlots ?? payload.availableSlots ?? 1);
  const seatClass = typeof payload.seatClass === "string" ? payload.seatClass : undefined;
  return { segmentRef, departureDate, freedSlots, seatClass, eventId: envelope.eventId };
}

export function parseJourneyOrderFact(envelope: EventEnvelope): JourneyOrderFact {
  const payload = envelope.payload as Record<string, unknown>;
  return { orderId: string(payload.orderId, `${envelope.eventType} event is missing orderId`), occurredAt: typeof payload.confirmedAt === "string" ? payload.confirmedAt : envelope.occurredAt };
}

export function parseJourneyOrderCancelledFact(envelope: EventEnvelope): JourneyOrderCancelledFact {
  const payload = envelope.payload as Record<string, unknown>;
  return { orderId: string(payload.orderId, "JourneyOrderCancelled event is missing orderId"), reason: typeof payload.reason === "string" ? payload.reason : undefined, occurredAt: envelope.occurredAt };
}

function string(value: unknown, message: string): string {
  if (typeof value !== "string" || value.trim().length === 0) throw new Error(message);
  return value;
}

function number(value: unknown): number {
  const parsed = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(parsed) || parsed < 1) throw new Error("WaitlistCapacityFreed event must include freedSlots >= 1");
  return parsed;
}
