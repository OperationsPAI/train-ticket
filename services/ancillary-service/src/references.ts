import { DomainError } from "./domain.js";

export type AncillaryReferences = Readonly<{
  journeyOrderId?: string;
  travelerRef: string;
  segmentRef?: string;
  entitlementRef?: string;
  departureAt?: string;
}>;

export class ReferenceUnavailable extends Error {}

export class HttpAncillaryReferences {
  constructor(
    private readonly orderUrl = process.env.JOURNEY_ORDER_URL ?? "http://journey-order:8080",
    private readonly ticketUrl = process.env.ENTITLEMENT_TICKETING_URL ?? "http://entitlement-ticketing:8080",
  ) {}

  async verify(input: AncillaryReferences, requiresTicket: boolean): Promise<{ departureAt: string; primaryTicketStatus: "ISSUED" | "UNKNOWN" }> {
    if (!input.journeyOrderId || !input.travelerRef || !input.segmentRef)
      throw new DomainError("INVALID_REFERENCES", "Order, traveler and segment references are required");
    const order = await this.read(this.orderUrl, "/api/v1/journey-orders/", input.journeyOrderId);
    if (order.orderId !== input.journeyOrderId || order.status !== "CONFIRMED")
      throw new DomainError("ORDER_NOT_CONFIRMED", "Ancillary purchase requires a confirmed order");
    if (!Array.isArray(order.travelerRefs) || !order.travelerRefs.includes(input.travelerRef))
      throw new DomainError("REFERENCE_MISMATCH", "Traveler does not belong to the order");
    const segment = Array.isArray(order.segments) ? order.segments.find((item: any) => item.segmentRef === input.segmentRef) : undefined;
    if (!segment || !Number.isFinite(Date.parse(segment.departureTime)))
      throw new DomainError("REFERENCE_MISMATCH", "Segment or departure is unavailable on the order");
    if (input.departureAt !== undefined && Date.parse(input.departureAt) !== Date.parse(segment.departureTime))
      throw new DomainError("REFERENCE_MISMATCH", "Departure time does not match the order segment");
    if (requiresTicket && !input.entitlementRef)
      throw new DomainError("TICKET_REQUIRED", "An issued entitlement is required");
    if (input.entitlementRef) {
      const ticket = await this.read(this.ticketUrl, "/api/v1/entitlements/", input.entitlementRef);
      if (ticket.entitlementId !== input.entitlementRef || ticket.journeyOrderId !== input.journeyOrderId
          || ticket.travelerRef !== input.travelerRef || ticket.segmentRef !== input.segmentRef)
        throw new DomainError("REFERENCE_MISMATCH", "Entitlement does not match the order, traveler and segment");
      if (ticket.status !== "ISSUED") throw new DomainError("TICKET_NOT_ISSUED", "Entitlement must be ISSUED for ancillary purchase");
    }
    return { departureAt: new Date(segment.departureTime).toISOString(), primaryTicketStatus: input.entitlementRef ? "ISSUED" : "UNKNOWN" };
  }

  private async read(base: string, path: string, id: string): Promise<any> {
    let response: Response;
    try { response = await fetch(base.replace(/\/$/, "") + path + encodeURIComponent(id), { signal: AbortSignal.timeout(5000) }); }
    catch (error) { throw new ReferenceUnavailable(`Reference service unavailable: ${String(error)}`); }
    if (response.status === 404) throw new DomainError("REFERENCE_NOT_FOUND", "Referenced order or entitlement does not exist");
    if (!response.ok) throw new ReferenceUnavailable(`Reference service returned ${response.status}`);
    return response.json();
  }
}
