import { fatalHandling, successfulHandling, transientHandling, type EventEnvelope, type EventHandlerResult } from "@trainticket/ts-kit";
import type { JourneyOrderEvent } from "./application.js";
import type { WaitlistCapacityFreed } from "./promotion.js";

export const CAPACITY_AVAILABILITY_STREAM = "events:capacity-availability";
export const JOURNEY_ORDER_STREAM = "events:journey-order";
export const SUBSCRIBED_STREAMS: readonly string[] = [CAPACITY_AVAILABILITY_STREAM, JOURNEY_ORDER_STREAM];
export const CONSUMER_GROUP = "waitlist";

type WaitlistConsumedEventService = Readonly<{
  handleCapacityFreed: (event: WaitlistCapacityFreed, correlationId?: string) => Promise<unknown>;
  handleJourneyOrderConfirmed?: (event: JourneyOrderEvent, correlationId?: string) => Promise<unknown>;
  handleJourneyOrderCancelled?: (event: JourneyOrderEvent, correlationId?: string) => Promise<unknown>;
}>;

export function consumerName(instanceId?: string): string {
  return `waitlist-${instanceId ?? "default"}`;
}

export function createWaitlistEventHandler(service: WaitlistConsumedEventService) {
  return async (envelope: EventEnvelope): Promise<EventHandlerResult> => {
    try {
      if (envelope.eventType === "CapacityReleased" || envelope.eventType === "WaitlistCapacityFreed") {
        await service.handleCapacityFreed(parseCapacityFreed(envelope), envelope.correlationId);
      } else if (envelope.eventType === "JourneyOrderConfirmed" && service.handleJourneyOrderConfirmed) {
        await service.handleJourneyOrderConfirmed(parseJourneyOrderEvent(envelope), envelope.correlationId);
      } else if (envelope.eventType === "JourneyOrderCancelled" && service.handleJourneyOrderCancelled) {
        await service.handleJourneyOrderCancelled(parseJourneyOrderEvent(envelope), envelope.correlationId);
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
  const freedSlots = number(payload.freedSlots ?? payload.availableSlots ?? 1);
  const seatClass = typeof payload.seatClass === "string" ? payload.seatClass : undefined;
  return { eventId: envelope.eventId, segmentRef, departureDate, freedSlots, seatClass };
}

export function parseJourneyOrderEvent(envelope: EventEnvelope): JourneyOrderEvent {
  const payload = envelope.payload as Record<string, unknown>;
  const waitlistRequestId = typeof payload.waitlistRequestId === "string" ? payload.waitlistRequestId : undefined;
  const journeyOrderRef = string(payload.journeyOrderRef ?? payload.orderId ?? payload.journeyOrderId);
  return { eventId: envelope.eventId, waitlistRequestId, journeyOrderRef };
}

function string(value: unknown): string {
  if (typeof value !== "string" || value.trim().length === 0) throw new Error("Consumed event is missing required string field");
  return value;
}

function number(value: unknown): number {
  const parsed = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(parsed) || parsed < 1) throw new Error("CapacityReleased event must include freedSlots >= 1");
  return parsed;
}
