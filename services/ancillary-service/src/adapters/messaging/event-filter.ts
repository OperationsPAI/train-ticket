import type { EventEnvelope } from "@trainticket/ts-kit";

export function handlesAncillaryEvent(envelope: EventEnvelope): boolean {
  if (envelope.producer === "journey-order") return envelope.eventType === "JourneyOrderCancelled";
  if (envelope.producer === "entitlement-ticketing") return envelope.eventType === "EntitlementVoided";
  if (envelope.producer === "payment" && ["PaymentCaptured", "RefundSettled"].includes(envelope.eventType)) {
    return typeof envelope.payload.businessRef === "string" && envelope.payload.businessRef.startsWith("aoi-");
  }
  return false;
}
