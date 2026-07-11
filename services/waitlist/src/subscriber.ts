import { fatalHandling, successfulHandling, transientHandling, type EventEnvelope, type EventHandlerResult } from "@trainticket/ts-kit";
import type { WaitlistApplicationService } from "./application.js";
import type { WaitlistCapacityFreed } from "./promotion.js";

export const CAPACITY_AVAILABILITY_STREAM = "events:capacity-availability";
export const SUBSCRIBED_STREAMS: readonly string[] = [CAPACITY_AVAILABILITY_STREAM];
export const CONSUMER_GROUP = "waitlist";

export function consumerName(instanceId?: string): string {
  return `waitlist-${instanceId ?? "default"}`;
}

export function createWaitlistEventHandler(service: Pick<WaitlistApplicationService, "handleCapacityFreed">) {
  return async (envelope: EventEnvelope): Promise<EventHandlerResult> => {
    try {
      if (envelope.eventType !== "WaitlistCapacityFreed") return successfulHandling();
      await service.handleCapacityFreed(parseCapacityFreed(envelope), envelope.correlationId);
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
  return { segmentRef, departureDate, freedSlots, seatClass };
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
