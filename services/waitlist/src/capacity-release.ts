import { tracedFetch, type EventEnvelope } from "@trainticket/ts-kit";
import { normalizeFareClass, type TravelInterval } from "./domain.js";
import type { WaitlistCapacityFreed } from "./promotion.js";

export interface CapacityReleaseResolver {
  resolve(envelope: EventEnvelope, correlationId?: string): Promise<WaitlistCapacityFreed | undefined>;
}

/** Resolves the class and route facts deliberately omitted from CapacityReleased. */
export class HttpCapacityReleaseResolver implements CapacityReleaseResolver {
  constructor(private readonly baseUrl = process.env.WAITLIST_FACTS_BASE_URL_TEMPLATE ?? "http://%s:8080") {}

  async resolve(envelope: EventEnvelope, correlationId?: string): Promise<WaitlistCapacityFreed | undefined> {
    if (envelope.eventType !== "CapacityReleased") return undefined;
    const payload = object(envelope.payload);
    const references = optionalObject(payload.references);
    const segmentBookingRef = optionalString(references?.segmentBookingRef);
    if (!segmentBookingRef) return undefined;
    const booking = await this.get("booking-orchestration", `/api/v1/internal/segment-bookings/${encodeURIComponent(segmentBookingRef)}`, correlationId);
    const segmentRef = requiredString(booking.segmentRef, "segmentRef");
    const scheduledServiceRef = requiredString(booking.scheduledServiceRef, "scheduledServiceRef");
    const departureDate = requiredString(booking.serviceDate, "serviceDate");
    const seatClass = normalizeFareClass(requiredString(booking.classRef, "classRef"));
    if (optionalString(references?.travelerRef) && optionalString(references?.travelerRef) !== optionalString(booking.travelerRef)) {
      throw new Error("CapacityReleased traveler differs from its segment booking");
    }
    const supply = await this.get("service-plan", `/api/v1/internal/service-segments/${encodeURIComponent(segmentRef)}/supply`, correlationId);
    if (requiredString(supply.segmentRef, "segmentRef") !== segmentRef
        || requiredString(supply.scheduledServiceRef, "scheduledServiceRef") !== scheduledServiceRef
        || requiredString(supply.serviceDate, "serviceDate") !== departureDate) {
      throw new Error("CapacityReleased booking differs from service-plan supply");
    }
    const interval = requiredInterval(payload.interval);
    const routeInterval = requiredInterval(supply.interval);
    if (interval.fromSeq < routeInterval.fromSeq || interval.toSeq > routeInterval.toSeq) {
      throw new Error("CapacityReleased interval is outside service-plan supply");
    }
    return {
      segmentRef,
      scheduledServiceRef,
      departureDate,
      seatClass,
      interval,
      stopSequenceVersion: requiredString(supply.stopSequenceVersion, "stopSequenceVersion"),
      freedSlots: 1,
      capacityReleaseRef: envelope.eventId,
    };
  }

  private async get(service: string, path: string, correlationId?: string): Promise<Record<string, unknown>> {
    const response = await tracedFetch(`${this.baseUrl.replace("%s", service).replace(/\/+$/u, "")}${path}`, {
      headers: correlationId ? { "x-correlation-id": correlationId } : undefined,
    });
    if (!response.ok) throw new Error(`Capacity release ${service} request failed with status ${response.status}`);
    return object(await response.json());
  }
}

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Capacity release fact must be an object");
  return value as Record<string, unknown>;
}
function optionalObject(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}
function optionalString(value: unknown): string | undefined { return typeof value === "string" && value.trim() ? value.trim() : undefined; }
function requiredString(value: unknown, field: string): string {
  const result = optionalString(value);
  if (!result) throw new Error(`Capacity release fact is missing ${field}`);
  return result;
}
function requiredInterval(value: unknown): TravelInterval {
  const body = object(value);
  const fromSeq = Number(body.fromSeq);
  const toSeq = Number(body.toSeq);
  if (!Number.isSafeInteger(fromSeq) || !Number.isSafeInteger(toSeq) || fromSeq < 0 || toSeq <= fromSeq) {
    throw new Error("Capacity release interval is invalid");
  }
  return { fromSeq, toSeq };
}
