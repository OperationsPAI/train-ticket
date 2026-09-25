import { tracedFetch } from "@trainticket/ts-kit";
import { DomainError, normalizeFareClass, type FareClass, type FareClassInput, type PaymentGuaranteeSnapshot, type TravelInterval } from "./domain.js";

export type WaitlistIntentRequest = Readonly<{
  accountId: string;
  travelerRefs: readonly string[];
  segmentRef: string;
  itineraryRef: string;
  paymentGuaranteeRef: string;
  deadline?: string;
  departureDate?: string;
  seatClass?: FareClassInput;
  scheduledServiceRef?: string;
  interval?: TravelInterval;
  stopSequenceVersion?: string;
}>;

export type ResolvedWaitlistIntent = Readonly<{
  travelerRefs: readonly string[];
  segmentRef: string;
  scheduledServiceRef: string;
  departureDate: string;
  seatClass: FareClass;
  interval: TravelInterval;
  stopSequenceVersion: string;
  paymentGuarantee: PaymentGuaranteeSnapshot;
}>;

export interface WaitlistIntentResolver {
  resolve(request: WaitlistIntentRequest, correlationId?: string): Promise<ResolvedWaitlistIntent>;
}

/** Strict resolver for isolated application tests and callers that already own accepted facts. */
export class SuppliedWaitlistIntentResolver implements WaitlistIntentResolver {
  async resolve(request: WaitlistIntentRequest): Promise<ResolvedWaitlistIntent> {
    const departureDate = request.departureDate ?? request.deadline?.slice(0, 10);
    if (!departureDate) throw new DomainError("VALIDATION_FAILED", "departureDate is required when no intent resolver is configured");
    return {
      travelerRefs: [...request.travelerRefs],
      segmentRef: request.segmentRef,
      scheduledServiceRef: request.scheduledServiceRef ?? request.segmentRef,
      departureDate,
      seatClass: normalizeFareClass(request.seatClass ?? "SECOND_CLASS"),
      interval: { ...(request.interval ?? { fromSeq: 0, toSeq: 1 }) },
      stopSequenceVersion: request.stopSequenceVersion ?? `segment:${request.segmentRef}`,
      paymentGuarantee: {
        paymentIntentId: request.paymentGuaranteeRef || "pi-test",
        sourceOrderRef: "test-source-order",
        payerRef: request.accountId,
        amount: { currency: "CNY", minorUnits: Number.MAX_SAFE_INTEGER },
        sourceStatus: "CREATED",
        sourceExpiresAt: request.deadline ?? `${departureDate}T00:00:00.000Z`,
        authorizedUntil: request.deadline ?? `${departureDate}T00:00:00.000Z`,
        verifiedAt: new Date(0).toISOString(),
      },
    };
  }
}

/**
 * Rebuilds the accepted traveler intent from authoritative read models.
 * A pi-* guarantee points at the failed order whose traveler set and quoted
 * class the customer asked to wait for. The live Payment intent proves account,
 * amount and channel consent at enrollment; its short channel timeout is then
 * snapshotted into a waitlist authorization lasting until the chosen deadline.
 */
export class HttpWaitlistIntentResolver implements WaitlistIntentResolver {
  constructor(
    private readonly baseUrl = process.env.WAITLIST_FACTS_BASE_URL_TEMPLATE ?? "http://%s:8080",
    private readonly now: () => Date = () => new Date(),
  ) {}

  async resolve(request: WaitlistIntentRequest, correlationId?: string): Promise<ResolvedWaitlistIntent> {
    const supply = await this.get("service-plan", `/api/v1/internal/service-segments/${encodeURIComponent(request.segmentRef)}/supply`, correlationId);
    const segmentRef = requiredString(supply, "segmentRef");
    if (segmentRef !== request.segmentRef) throw mismatch("service-plan segment differs from waitlist intent");
    const scheduledServiceRef = requiredString(supply, "scheduledServiceRef");
    const departureDate = requiredString(supply, "serviceDate");
    const stopSequenceVersion = requiredString(supply, "stopSequenceVersion");
    const interval = requiredInterval(supply.interval);
    const stops = requiredObjects(supply, "stops");

    const itinerary = await this.get("trip-planning", `/api/v1/itineraries/${encodeURIComponent(request.itineraryRef)}`, correlationId);
    if (requiredString(itinerary, "itineraryRef") !== request.itineraryRef) throw mismatch("trip-planning itinerary differs from waitlist intent");
    const matchingLegs = requiredObjects(itinerary, "legs").filter((leg) => optionalString(leg, "serviceSegmentRef") === segmentRef);
    if (matchingLegs.length !== 1) throw mismatch("waitlist itinerary must contain the requested segment exactly once");
    const leg = matchingLegs[0]!;
    if (requiredString(leg, "servicePlanRef") !== scheduledServiceRef
        || requiredString(leg, "originStopRef") !== requiredString(stops[interval.fromSeq]!, "nodeRef")
        || requiredString(leg, "destinationStopRef") !== requiredString(stops[interval.toSeq]!, "nodeRef")
        || requiredString(leg, "departureTime").slice(0, 10) !== departureDate) {
      throw mismatch("waitlist itinerary route or date differs from service-plan supply");
    }

    if (!request.paymentGuaranteeRef.startsWith("pi-")) throw mismatch("paymentGuaranteeRef must be a payment intent reference");
    const deadline = requiredFutureInstant(request.deadline, "deadline", this.now());
    let travelerRefs = [...request.travelerRefs];
    let seatClass = request.seatClass ? normalizeFareClass(request.seatClass) : undefined;
    const payment = await this.get("payment", `/api/v1/payment-intents/${encodeURIComponent(request.paymentGuaranteeRef)}`, correlationId);
    if (requiredString(payment, "paymentIntentId") !== request.paymentGuaranteeRef) throw mismatch("payment guarantee identity differs");
    const paymentStatus = requiredString(payment, "status");
    if (paymentStatus !== "CREATED" && paymentStatus !== "AUTHORIZED") throw mismatch(`payment guarantee is not available in status ${paymentStatus}`);
    if (requiredString(payment, "purpose").toLowerCase() !== "purchase") throw mismatch("payment guarantee purpose must be purchase");
    if (requiredString(payment, "payerRef") !== request.accountId) throw mismatch("payment guarantee payer differs from the waitlist account");
    const sourceExpiresAt = requiredFutureInstant(requiredString(payment, "expiresAt"), "payment guarantee expiresAt", this.now());
    const guaranteedAmount = requiredMoney(payment.amount, "payment guarantee amount");
    if (!isZeroMoney(payment.capturedAmount) || !isZeroMoney(payment.refundedAmount)) throw mismatch("payment guarantee must not have captured or refunded funds");
    if (paymentStatus === "AUTHORIZED") {
      const authorizedAmount = requiredMoney(payment.authorizedAmount, "authorizedAmount");
      if (!sameCurrencyAndAtLeast(authorizedAmount, guaranteedAmount)) throw mismatch("authorized payment guarantee is below its declared amount");
    }
    const sourceOrderId = requiredString(payment, "businessRef");
    const order = await this.get("journey-order", `/api/v1/journey-orders/${encodeURIComponent(sourceOrderId)}`, correlationId);
    if (requiredString(order, "orderId") !== sourceOrderId || requiredString(order, "accountId") !== request.accountId) {
      throw mismatch("payment guarantee source order differs from the waitlist account");
    }
    const sourceTotal = requiredMoney(requiredObject(order, "monetarySummary").payableTotal, "source order payableTotal");
    if (!sameCurrencyAndAtLeast(guaranteedAmount, sourceTotal)) throw mismatch("payment guarantee amount is below the source order total");
    const orderTravelers = requiredStrings(order, "travelerRefs");
    if (!sameSet(orderTravelers, request.travelerRefs) && !request.travelerRefs.every((traveler) => orderTravelers.includes(traveler))) {
      throw mismatch("waitlist travelers are outside the guaranteed source order");
    }
    if (!requiredStrings(order, "segmentRefs").includes(segmentRef)) throw mismatch("waitlist segment is outside the guaranteed source order");
    const offerId = requiredString(order, "offerId");
    const offer = await this.get("offer-management", `/api/v1/offers/${encodeURIComponent(offerId)}`, correlationId);
    if (requiredString(offer, "offerId") !== offerId || requiredString(offer, "accountId") !== request.accountId
        || requiredString(offer, "itineraryRef") !== request.itineraryRef) {
      throw mismatch("guaranteed source offer differs from the waitlist intent");
    }
    const offerTravelers = requiredObjects(requiredObject(offer, "passengerMix"), "travelers").map((traveler) => requiredString(traveler, "travelerId"));
    if (!sameSet(offerTravelers, orderTravelers)) throw mismatch("guaranteed offer travelers differ from its order");
    const quoteId = requiredString(offer, "fareQuoteRef");
    const quote = await this.get("fare-pricing", `/api/v1/fare-quotes/${encodeURIComponent(quoteId)}`, correlationId);
    if (requiredString(quote, "quoteId") !== quoteId) throw mismatch("fare quote identity differs from the guaranteed offer");
    const quoteClass = normalizeFareClass(requiredString(requiredObject(requiredObject(quote, "breakdown"), "seatClassMultiplier"), "seatClass"));
    if (seatClass && seatClass !== quoteClass) throw mismatch("requested class differs from the guaranteed fare quote");
    travelerRefs = orderTravelers;
    seatClass = quoteClass;
    if (!seatClass) throw mismatch("waitlist class cannot be proven from the request or payment guarantee");
    if (request.departureDate && request.departureDate !== departureDate) throw mismatch("requested departure date differs from service-plan supply");
    const channelRef = optionalObject(payment.channelRef);
    return {
      travelerRefs, segmentRef, scheduledServiceRef, departureDate, seatClass, interval, stopSequenceVersion,
      paymentGuarantee: {
        paymentIntentId: request.paymentGuaranteeRef,
        sourceOrderRef: sourceOrderId,
        payerRef: request.accountId,
        amount: guaranteedAmount,
        sourceStatus: paymentStatus,
        sourceExpiresAt: sourceExpiresAt.toISOString(),
        authorizedUntil: deadline.toISOString(),
        verifiedAt: this.now().toISOString(),
        preferredChannel: channelRef ? optionalString(channelRef, "channel") : undefined,
      },
    };
  }

  private async get(service: string, path: string, correlationId?: string): Promise<Record<string, unknown>> {
    const response = await tracedFetch(`${this.baseUrl.replace("%s", service).replace(/\/+$/u, "")}${path}`, {
      headers: correlationId ? { "x-correlation-id": correlationId } : undefined,
    });
    if (!response.ok) throw new Error(`Waitlist intent ${service} request failed with status ${response.status}`);
    const body = await response.json();
    if (!body || typeof body !== "object" || Array.isArray(body)) throw new Error(`Waitlist intent ${service} response was not an object`);
    return body as Record<string, unknown>;
  }
}

function mismatch(message: string): DomainError { return new DomainError("PRECONDITION_FAILED", message); }
function requiredObject(body: Record<string, unknown>, field: string): Record<string, unknown> {
  const value = body[field];
  if (!value || typeof value !== "object" || Array.isArray(value)) throw mismatch(`${field} is missing from waitlist intent facts`);
  return value as Record<string, unknown>;
}
function optionalObject(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}
function requiredObjects(body: Record<string, unknown>, field: string): Record<string, unknown>[] {
  const value = body[field];
  if (!Array.isArray(value) || value.length === 0 || value.some((item) => !item || typeof item !== "object" || Array.isArray(item))) {
    throw mismatch(`${field} is missing from waitlist intent facts`);
  }
  return value as Record<string, unknown>[];
}
function requiredString(body: Record<string, unknown>, field: string): string {
  const value = optionalString(body, field);
  if (!value) throw mismatch(`${field} is missing from waitlist intent facts`);
  return value;
}
function optionalString(body: Record<string, unknown>, field: string): string | undefined {
  const value = body[field];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}
function requiredStrings(body: Record<string, unknown>, field: string): string[] {
  const value = body[field];
  if (!Array.isArray(value) || value.length === 0 || value.some((item) => typeof item !== "string" || !item.trim())) {
    throw mismatch(`${field} is missing from waitlist intent facts`);
  }
  const result = value.map((item) => String(item).trim());
  if (new Set(result).size !== result.length) throw mismatch(`${field} contains duplicate waitlist intent references`);
  return result;
}
function requiredInterval(value: unknown): TravelInterval {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw mismatch("interval is missing from service-plan supply");
  const interval = value as Record<string, unknown>;
  const fromSeq = Number(interval.fromSeq);
  const toSeq = Number(interval.toSeq);
  if (!Number.isSafeInteger(fromSeq) || !Number.isSafeInteger(toSeq) || fromSeq < 0 || toSeq <= fromSeq) {
    throw mismatch("service-plan interval is invalid");
  }
  return { fromSeq, toSeq };
}
function requiredMoney(value: unknown, field: string): Readonly<{ currency: string; minorUnits: number }> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw mismatch(`${field} is missing`);
  const body = value as Record<string, unknown>;
  const currency = requiredString(body, "currency");
  const minorUnits = Number(body.minorUnits);
  if (!Number.isSafeInteger(minorUnits) || minorUnits <= 0) throw mismatch(`${field} must be positive integer minor units`);
  return { currency, minorUnits };
}
function isZeroMoney(value: unknown): boolean {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const body = value as Record<string, unknown>;
  return Number(body.minorUnits) === 0;
}
function sameCurrencyAndAtLeast(available: Readonly<{ currency: string; minorUnits: number }>, required: Readonly<{ currency: string; minorUnits: number }>): boolean {
  return available.currency === required.currency && available.minorUnits >= required.minorUnits;
}
function requiredFutureInstant(value: unknown, field: string, now: Date): Date {
  if (typeof value !== "string" || !value.trim()) throw mismatch(`${field} is required`);
  const instant = new Date(value);
  if (!Number.isFinite(instant.getTime()) || instant <= now) throw mismatch(`${field} must be in the future`);
  return instant;
}
function sameSet(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length && left.every((value) => right.includes(value));
}
