import { tracedFetch, type EventPublisher } from "@trainticket/ts-kit";
import { DomainError, type FulfillmentTraveler, type MoneySnapshot, type WaitlistEntry, type WaitlistEntrySnapshot } from "./domain.js";
import { publishAll, waitlistExpired, waitlistFulfilled, waitlistQueued } from "./publisher.js";
import type { WaitlistRepository } from "./promotion.js";

export type OrderFacts = Readonly<{
  orderId: string;
  accountId: string;
  offerId: string;
  status: string;
  riskClear: boolean;
  travelerRefs: readonly string[];
  segmentRefs: readonly string[];
  amount: MoneySnapshot;
}>;
export type SagaFacts = Readonly<{ sagaId: string; orderId: string; status: string }>;
export type ReservationFacts = Readonly<{ segmentBookingId: string; orderId: string; travelerRef: string; segmentRef: string; status: string; capacityHoldId?: string }>;
export type PaymentFacts = Readonly<{ paymentIntentId: string; businessRef: string; payerRef: string; status: string; amount: MoneySnapshot }>;
export type EntitlementFacts = Readonly<{ entitlementId: string; segmentBookingId: string; orderId: string; status: string }>;

export interface WaitlistFulfillmentClient {
  getOrder(orderId: string, correlationId?: string): Promise<OrderFacts>;
  findSaga(orderId: string, correlationId?: string): Promise<SagaFacts | undefined>;
  requestReservation(entry: WaitlistEntry, traveler: FulfillmentTraveler, sagaId: string, correlationId?: string): Promise<ReservationFacts>;
  getReservation(traveler: FulfillmentTraveler, correlationId?: string): Promise<ReservationFacts | undefined>;
  createPayment(entry: WaitlistEntry, correlationId?: string): Promise<PaymentFacts>;
  findPayment(orderId: string, correlationId?: string): Promise<PaymentFacts | undefined>;
  getPayment(paymentIntentId: string, correlationId?: string): Promise<PaymentFacts>;
  capturePayment(entry: WaitlistEntry, correlationId?: string): Promise<PaymentFacts>;
  cancelPayment(entry: WaitlistEntry, reason: string, correlationId?: string): Promise<void>;
  releaseCapacity(traveler: FulfillmentTraveler, capacityHoldId: string, correlationId?: string): Promise<void>;
  cancelOrder(entry: WaitlistEntry, reason: string, correlationId?: string): Promise<void>;
  issueEntitlement(entry: WaitlistEntry, traveler: FulfillmentTraveler, correlationId?: string): Promise<EntitlementFacts>;
}

export class HttpWaitlistFulfillmentClient implements WaitlistFulfillmentClient {
  constructor(private readonly bases: Readonly<Record<string, string>> = {}) {}

  async getOrder(orderId: string, correlationId?: string): Promise<OrderFacts> {
    const body = await this.getRequired("journey-order", `/api/v1/journey-orders/${encodeURIComponent(orderId)}`, correlationId);
    return {
      orderId: requiredString(body, "orderId"), accountId: requiredString(body, "accountId"), offerId: requiredString(body, "offerId"),
      status: requiredString(body, "status"), riskClear: body.riskClear === true,
      travelerRefs: requiredStrings(body, "travelerRefs"), segmentRefs: requiredStrings(body, "segmentRefs"),
      amount: requiredMoney(requiredObject(body, "monetarySummary").payableTotal, "monetarySummary.payableTotal"),
    };
  }

  async findSaga(orderId: string, correlationId?: string): Promise<SagaFacts | undefined> {
    const body = await this.get("booking-orchestration", `/api/v1/internal/booking-sagas/by-order/${encodeURIComponent(orderId)}`, correlationId, true);
    return body ? { sagaId: requiredString(body, "sagaId"), orderId: requiredString(body, "journeyOrderId"), status: requiredString(body, "status") } : undefined;
  }

  async requestReservation(entry: WaitlistEntry, traveler: FulfillmentTraveler, sagaId: string, correlationId?: string): Promise<ReservationFacts> {
    const body = await this.post("booking-orchestration", `/api/v1/internal/booking-sagas/${encodeURIComponent(sagaId)}/request-reservation`, traveler.reservationIdempotencyKey, {
      segmentRef: entry.segmentRef, travelerRef: traveler.travelerRef, segmentBookingId: traveler.segmentBookingId,
      classRef: entry.seatClass, scheduledServiceRef: entry.scheduledServiceRef, serviceDate: entry.departureDate,
    }, correlationId);
    return { segmentBookingId: requiredString(body, "segmentBookingId"), orderId: entry.journeyOrderRef!, travelerRef: traveler.travelerRef, segmentRef: entry.segmentRef, status: requiredString(body, "status") };
  }

  async getReservation(traveler: FulfillmentTraveler, correlationId?: string): Promise<ReservationFacts | undefined> {
    const body = await this.get("booking-orchestration", `/api/v1/internal/segment-bookings/${encodeURIComponent(traveler.segmentBookingId)}`, correlationId, true);
    if (!body) return undefined;
    return {
      segmentBookingId: requiredString(body, "segmentBookingId"), orderId: requiredString(body, "journeyOrderId"),
      travelerRef: requiredString(body, "travelerRef"), segmentRef: requiredString(body, "segmentRef"),
      status: requiredString(body, "status"), capacityHoldId: optionalString(body, "capacityHoldId"),
    };
  }

  async createPayment(entry: WaitlistEntry, correlationId?: string): Promise<PaymentFacts> {
    const amount = entry.fulfillment.orderAmount;
    if (!entry.journeyOrderRef || !amount) throw new DomainError("PRECONDITION_FAILED", "Waitlist fulfillment has no payable order");
    const body = await this.post("payment", "/api/v1/payment-intents", entry.fulfillment.paymentCreateIdempotencyKey, {
      businessRef: entry.journeyOrderRef, purpose: "purchase", amount, payerRef: entry.accountId,
      preferredChannel: entry.paymentGuarantee.preferredChannel,
    }, correlationId);
    return {
      paymentIntentId: requiredString(body, "paymentIntentId"),
      businessRef: requiredString(body, "businessRef"),
      payerRef: entry.accountId,
      status: normalizePaymentStatus(requiredString(body, "status")),
      amount: requiredMoney(body.amount, "amount"),
    };
  }

  async getPayment(paymentIntentId: string, correlationId?: string): Promise<PaymentFacts> {
    return paymentFacts(await this.getRequired("payment", `/api/v1/payment-intents/${encodeURIComponent(paymentIntentId)}`, correlationId));
  }

  async findPayment(orderId: string, correlationId?: string): Promise<PaymentFacts | undefined> {
    const body = await this.get("payment", `/api/v1/payment-intents/by-business/${encodeURIComponent(orderId)}`, correlationId, true);
    return body ? paymentFacts(body) : undefined;
  }

  async capturePayment(entry: WaitlistEntry, correlationId?: string): Promise<PaymentFacts> {
    const paymentIntentId = entry.fulfillment.paymentIntentId;
    if (!paymentIntentId) throw new DomainError("PRECONDITION_FAILED", "Waitlist fulfillment has no payment intent");
    const channel = entry.paymentGuarantee.preferredChannel;
    const body = await this.post("payment", `/api/v1/payment-intents/${encodeURIComponent(paymentIntentId)}/capture`, entry.fulfillment.paymentCaptureIdempotencyKey,
      channel ? { channelRef: { channel } } : {}, correlationId);
    return { paymentIntentId: requiredString(body, "paymentIntentId"), businessRef: entry.journeyOrderRef!, payerRef: entry.accountId,
      status: normalizePaymentStatus(requiredString(body, "status")), amount: entry.fulfillment.orderAmount! };
  }

  async cancelPayment(entry: WaitlistEntry, reason: string, correlationId?: string): Promise<void> {
    const paymentIntentId = entry.fulfillment.paymentIntentId;
    if (!paymentIntentId) return;
    const payment = await this.getPayment(paymentIntentId, correlationId);
    if (["CAPTURED", "FAILED", "CANCELLED", "EXPIRED"].includes(payment.status)) return;
    await this.post("payment", `/api/v1/payment-intents/${encodeURIComponent(paymentIntentId)}/cancel`, entry.fulfillment.paymentCancelIdempotencyKey, { reason }, correlationId);
  }

  async releaseCapacity(traveler: FulfillmentTraveler, capacityHoldId: string, correlationId?: string): Promise<void> {
    const current = await this.get("capacity-availability", `/api/v1/capacity-holds/${encodeURIComponent(capacityHoldId)}`, correlationId, true);
    if (!current || ["RELEASED", "EXPIRED", "CANCELLED", "FAILED"].includes(optionalString(current, "status") ?? "")) return;
    await this.post("capacity-availability", `/api/v1/capacity-holds/${encodeURIComponent(capacityHoldId)}/release`, traveler.capacityReleaseIdempotencyKey, {}, correlationId);
  }

  async cancelOrder(entry: WaitlistEntry, reason: string, correlationId?: string): Promise<void> {
    if (!entry.journeyOrderRef) return;
    await this.post("journey-order", `/api/v1/journey-orders/${encodeURIComponent(entry.journeyOrderRef)}/cancel`, entry.fulfillment.orderCancelIdempotencyKey, { reason }, correlationId);
  }

  async issueEntitlement(entry: WaitlistEntry, traveler: FulfillmentTraveler, correlationId?: string): Promise<EntitlementFacts> {
    if (!entry.journeyOrderRef) throw new DomainError("PRECONDITION_FAILED", "Waitlist fulfillment has no journey order");
    const body = await this.post("entitlement-ticketing", "/api/v1/entitlements", traveler.entitlementIdempotencyKey, {
      segmentBookingId: traveler.segmentBookingId, journeyOrderId: entry.journeyOrderRef, travelerRef: traveler.travelerRef,
      segmentRef: entry.segmentRef, issuePurpose: "INITIAL",
    }, correlationId);
    return { entitlementId: requiredString(body, "entitlementId"), segmentBookingId: requiredString(body, "segmentBookingId"), orderId: requiredString(body, "journeyOrderId"), status: requiredString(body, "status") };
  }

  private async get(service: string, path: string, correlationId?: string, allowNotFound = false): Promise<Record<string, unknown> | undefined> {
    const response = await tracedFetch(`${this.base(service)}${path}`, { headers: correlationId ? { "x-correlation-id": correlationId } : undefined, signal: AbortSignal.timeout(5_000) });
    if (allowNotFound && response.status === 404) return undefined;
    if (!response.ok) throw new Error(`Waitlist fulfillment ${service} request failed with status ${response.status}`);
    return responseObject(await response.json(), service);
  }

  private async getRequired(service: string, path: string, correlationId?: string): Promise<Record<string, unknown>> {
    const result = await this.get(service, path, correlationId);
    if (!result) throw new Error(`Waitlist fulfillment ${service} response was not found`);
    return result;
  }

  private async post(service: string, path: string, key: string, body: unknown, correlationId?: string): Promise<Record<string, unknown>> {
    const headers: Record<string, string> = { "content-type": "application/json", "Idempotency-Key": key };
    if (correlationId) headers["x-correlation-id"] = correlationId;
    const response = await tracedFetch(`${this.base(service)}${path}`, { method: "POST", headers, body: JSON.stringify(body), signal: AbortSignal.timeout(5_000) });
    if (!response.ok) throw new Error(`Waitlist fulfillment ${service} request failed with status ${response.status}`);
    return responseObject(await response.json(), service);
  }

  private base(service: string): string {
    const envName = `${service.replaceAll("-", "_").toUpperCase()}_BASE_URL`;
    return (this.bases[service] ?? process.env[envName] ?? `http://${service}:8080`).replace(/\/+$/u, "");
  }
}

export class WaitlistFulfillmentOrchestrator {
  constructor(
    private readonly repository: WaitlistRepository,
    private readonly publisher: EventPublisher,
    private readonly client: WaitlistFulfillmentClient,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async advanceNext(correlationId?: string): Promise<WaitlistEntrySnapshot | undefined> {
    const entry = await this.repository.findRecoverableFulfillment(this.now());
    if (!entry?.journeyOrderRef) return undefined;
    entry.clearFulfillmentDelay();
    const order = await this.client.getOrder(entry.journeyOrderRef, correlationId);
    assertOrder(entry, order);
    if (order.status === "CONFIRMED") {
      entry.confirmJourneyOrder(order.orderId);
      const fulfilled = await this.repository.save(entry);
      await publishAll(this.publisher, [waitlistFulfilled(fulfilled, entry.loadedVersion, order.orderId, correlationId, this.now().toISOString())]);
      return fulfilled;
    }
    if (isTerminalOrder(order.status)) return this.cleanupAndRequeue(entry, order.orderId, correlationId);
    if (!entry.fulfillment.orderAmount) {
      if (!guaranteeCovers(entry, order.amount)) {
        entry.suspend();
        return this.repository.save(entry);
      }
      entry.recordOrderAmount(order.amount);
      return this.repository.save(entry);
    }
    const saga = await this.client.findSaga(order.orderId, correlationId);
    if (!saga) return this.defer(entry);
    if (saga.orderId !== order.orderId) throw new DomainError("CONFLICT", "Booking saga belongs to another order");
    if (!entry.fulfillment.bookingSagaRef) {
      entry.recordBookingSaga(saga.sagaId);
      return this.repository.save(entry);
    }
    if (entry.fulfillment.bookingSagaRef !== saga.sagaId) throw new DomainError("CONFLICT", "Booking saga identity changed during fulfillment");
    if (saga.status === "FAILED") return this.abort(entry, "WAITLIST_BOOKING_FAILED", correlationId);
    if (["RISK_CHECKING", "STARTED"].includes(saga.status) || !order.riskClear) return this.defer(entry);

    for (const traveler of entry.fulfillment.travelers) {
      if (!traveler.reservationStatus) {
        const existing = await this.client.getReservation(traveler, correlationId);
        if (existing) {
          assertReservation(entry, traveler, existing);
          entry.recordReservation(traveler.travelerRef, existing.status, existing.capacityHoldId);
          return this.repository.save(entry);
        }
        if (!canRequestReservation(saga.status)) return this.defer(entry);
        const reservation = await this.client.requestReservation(entry, traveler, saga.sagaId, correlationId);
        assertReservation(entry, traveler, reservation);
        entry.recordReservation(traveler.travelerRef, reservation.status, reservation.capacityHoldId);
        return this.repository.save(entry);
      }
      if (!reservationReady(traveler)) {
        const reservation = await this.client.getReservation(traveler, correlationId);
        if (!reservation) return this.defer(entry);
        assertReservation(entry, traveler, reservation);
        if (reservation.status === "FAILED" || reservation.status === "CANCELLED") return this.abort(entry, "WAITLIST_RESERVATION_FAILED", correlationId);
        entry.recordReservation(traveler.travelerRef, reservation.status, reservation.capacityHoldId);
        return reservationReady(entry.fulfillment.travelers.find((candidate) => candidate.travelerRef === traveler.travelerRef)!)
          ? this.repository.save(entry) : this.defer(entry);
      }
    }

    if (!entry.fulfillment.paymentIntentId && saga.status !== "WAITING_PAYMENT") return this.defer(entry);
    if (!entry.fulfillment.paymentIntentId) {
      const payment = await this.client.createPayment(entry, correlationId);
      assertPayment(entry, payment);
      entry.recordPayment(payment.paymentIntentId, payment.status);
      return this.repository.save(entry);
    }
    const currentPayment = await this.client.getPayment(entry.fulfillment.paymentIntentId, correlationId);
    assertPayment(entry, currentPayment);
    if (currentPayment.status !== entry.fulfillment.paymentStatus) {
      entry.recordPayment(currentPayment.paymentIntentId, currentPayment.status);
      return this.repository.save(entry);
    }
    if (currentPayment.status !== "CAPTURED") {
      if (["FAILED", "CANCELLED", "EXPIRED"].includes(currentPayment.status)) return this.abort(entry, "WAITLIST_PAYMENT_FAILED", correlationId);
      const captured = await this.client.capturePayment(entry, correlationId);
      assertPayment(entry, captured);
      entry.recordPayment(captured.paymentIntentId, captured.status);
      if (captured.status !== "CAPTURED") entry.deferFulfillment(new Date(this.now().getTime() + 1_000));
      return this.repository.save(entry);
    }
    if (!["TICKETING", "INVOICING", "COMPLETED"].includes(saga.status)) return this.defer(entry);

    for (const traveler of entry.fulfillment.travelers) {
      if (traveler.entitlementId) continue;
      const entitlement = await this.client.issueEntitlement(entry, traveler, correlationId);
      if (entitlement.segmentBookingId !== traveler.segmentBookingId || entitlement.orderId !== entry.journeyOrderRef || entitlement.status !== "ISSUED") {
        throw new DomainError("CONFLICT", "Issued entitlement differs from the waitlist fulfillment");
      }
      entry.recordEntitlement(traveler.travelerRef, entitlement.entitlementId);
      return this.repository.save(entry);
    }
    return this.defer(entry);
  }

  async handleOrderCancelled(orderId: string, correlationId?: string): Promise<WaitlistEntrySnapshot | undefined> {
    const entry = await this.repository.findByJourneyOrderRef?.(orderId);
    if (!entry || entry.status !== "MATCHING") return undefined;
    return this.cleanupAndRequeue(entry, orderId, correlationId);
  }

  private async defer(entry: WaitlistEntry, delayMs = 1_000): Promise<WaitlistEntrySnapshot> {
    entry.deferFulfillment(new Date(this.now().getTime() + delayMs));
    return this.repository.save(entry);
  }

  private async abort(entry: WaitlistEntry, reason: string, correlationId?: string): Promise<WaitlistEntrySnapshot> {
    const payment = entry.fulfillment.paymentIntentId
      ? await this.client.getPayment(entry.fulfillment.paymentIntentId, correlationId)
      : await this.client.findPayment(entry.journeyOrderRef!, correlationId);
    if (payment) {
      assertPayment(entry, payment);
      entry.recordPayment(payment.paymentIntentId, payment.status);
    }
    if (payment?.status === "CAPTURED" || entry.fulfillment.travelers.some((traveler) => traveler.entitlementId)) {
      entry.suspend();
      return this.repository.save(entry);
    }
    for (const traveler of entry.fulfillment.travelers) {
      const latest = traveler.capacityHoldId ? undefined : await this.client.getReservation(traveler, correlationId);
      if (latest) {
        assertReservation(entry, traveler, latest);
        entry.recordReservation(traveler.travelerRef, latest.status, latest.capacityHoldId);
      }
      const capacityHoldId = latest?.capacityHoldId ?? traveler.capacityHoldId;
      if (capacityHoldId) await this.client.releaseCapacity(traveler, capacityHoldId, correlationId);
    }
    if (entry.fulfillment.paymentIntentId && entry.fulfillment.paymentStatus !== "CAPTURED") {
      await this.client.cancelPayment(entry, reason, correlationId);
    }
    await this.client.cancelOrder(entry, reason, correlationId);
    return this.defer(entry);
  }

  private async cleanupAndRequeue(entry: WaitlistEntry, orderId: string, correlationId?: string): Promise<WaitlistEntrySnapshot> {
    const queuedAt = this.now().toISOString();
    const payment = entry.fulfillment.paymentIntentId
      ? await this.client.getPayment(entry.fulfillment.paymentIntentId, correlationId)
      : await this.client.findPayment(orderId, correlationId);
    if (payment) {
      assertPayment(entry, payment);
      entry.recordPayment(payment.paymentIntentId, payment.status);
      if (payment.status === "CAPTURED" || entry.fulfillment.travelers.some((traveler) => traveler.entitlementId)) {
        entry.suspend();
        return this.repository.save(entry);
      }
      await this.client.cancelPayment(entry, "WAITLIST_ORDER_TERMINAL", correlationId);
    }
    let unheldSlots = 0;
    for (const traveler of entry.fulfillment.travelers) {
      const latest = traveler.capacityHoldId ? undefined : await this.client.getReservation(traveler, correlationId);
      if (latest) {
        assertReservation(entry, traveler, latest);
        entry.recordReservation(traveler.travelerRef, latest.status, latest.capacityHoldId);
      }
      const capacityHoldId = latest?.capacityHoldId ?? traveler.capacityHoldId;
      if (capacityHoldId) {
        await this.client.releaseCapacity(traveler, capacityHoldId, correlationId);
        continue;
      }
      if (latest && !["FAILED", "CANCELLED"].includes(latest.status)) return this.defer(entry);
      unheldSlots++;
    }
    if (unheldSlots > 0) await this.repository.restoreCapacityCredits(entry, unheldSlots);
    entry.returnToQueue();
    if (new Date(entry.deadline) <= this.now()) entry.expire();
    const saved = await this.repository.save(entry);
    await publishAll(this.publisher, [saved.status === "EXPIRED"
      ? waitlistExpired(saved, entry.loadedVersion, correlationId, queuedAt)
      : waitlistQueued(saved, entry.loadedVersion, correlationId, queuedAt, { journeyOrderRef: orderId, requeueReason: "ORDER_TERMINAL" })]);
    return saved;
  }
}

function assertOrder(entry: WaitlistEntry, order: OrderFacts): void {
  if (order.orderId !== entry.journeyOrderRef || order.accountId !== entry.accountId || order.offerId !== entry.offerId
      || !sameSet(order.travelerRefs, entry.travelerRefs) || order.segmentRefs.length !== 1 || order.segmentRefs[0] !== entry.segmentRef) {
    throw new DomainError("CONFLICT", "Journey order differs from the waitlist promotion");
  }
  if (entry.fulfillment.orderAmount && !sameMoney(entry.fulfillment.orderAmount, order.amount)) throw new DomainError("CONFLICT", "Journey order amount changed during fulfillment");
}
function guaranteeCovers(entry: WaitlistEntry, amount: MoneySnapshot): boolean {
  return entry.paymentGuarantee.amount.currency === amount.currency && entry.paymentGuarantee.amount.minorUnits >= amount.minorUnits;
}
function assertReservation(entry: WaitlistEntry, traveler: FulfillmentTraveler, reservation: ReservationFacts): void {
  if (reservation.segmentBookingId !== traveler.segmentBookingId || reservation.orderId !== entry.journeyOrderRef
      || reservation.travelerRef !== traveler.travelerRef || reservation.segmentRef !== entry.segmentRef) {
    throw new DomainError("CONFLICT", "Segment reservation differs from the waitlist fulfillment");
  }
}
function assertPayment(entry: WaitlistEntry, payment: PaymentFacts): void {
  if (payment.businessRef !== entry.journeyOrderRef || payment.payerRef !== entry.accountId
      || !entry.fulfillment.orderAmount || !sameMoney(payment.amount, entry.fulfillment.orderAmount)) {
    throw new DomainError("CONFLICT", "Payment intent differs from the waitlist fulfillment");
  }
}
function reservationReady(traveler: FulfillmentTraveler): boolean {
  return ["CONFIRMED", "TICKETED", "COMPLETED"].includes(traveler.reservationStatus ?? "") && !!traveler.capacityHoldId;
}
function canRequestReservation(sagaStatus: string): boolean {
  return ["RESERVING", "SEAT_ASSIGNING", "WAITING_PAYMENT", "HELD", "TICKETING"].includes(sagaStatus);
}
function isTerminalOrder(status: string): boolean { return ["CANCELLED", "FAILED", "EXPIRED", "CLOSED", "REFUNDED"].includes(status); }
function sameSet(left: readonly string[], right: readonly string[]): boolean { return left.length === right.length && left.every((value) => right.includes(value)); }
function sameMoney(left: MoneySnapshot, right: MoneySnapshot): boolean { return left.currency === right.currency && left.minorUnits === right.minorUnits; }
function paymentFacts(body: Record<string, unknown>): PaymentFacts {
  return { paymentIntentId: requiredString(body, "paymentIntentId"), businessRef: requiredString(body, "businessRef"), payerRef: requiredString(body, "payerRef"), status: normalizePaymentStatus(requiredString(body, "status")), amount: requiredMoney(body.amount, "amount") };
}
function responseObject(value: unknown, service: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`Waitlist fulfillment ${service} response was not an object`);
  return value as Record<string, unknown>;
}
function requiredObject(body: Record<string, unknown>, field: string): Record<string, unknown> {
  const value = body[field];
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`Waitlist fulfillment response is missing ${field}`);
  return value as Record<string, unknown>;
}
function requiredString(body: Record<string, unknown>, field: string): string {
  const value = optionalString(body, field);
  if (!value) throw new Error(`Waitlist fulfillment response is missing ${field}`);
  return value;
}
function optionalString(body: Record<string, unknown>, field: string): string | undefined {
  const value = body[field];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}
function requiredStrings(body: Record<string, unknown>, field: string): string[] {
  const value = body[field];
  if (!Array.isArray(value) || value.length === 0 || value.some((item) => typeof item !== "string" || !item.trim())) throw new Error(`Waitlist fulfillment response is missing ${field}`);
  return value.map(String);
}
function requiredMoney(value: unknown, field: string): MoneySnapshot {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`Waitlist fulfillment response is missing ${field}`);
  const body = value as Record<string, unknown>;
  const currency = requiredString(body, "currency");
  const minorUnits = Number(body.minorUnits);
  if (!Number.isSafeInteger(minorUnits) || minorUnits <= 0) throw new Error(`Waitlist fulfillment ${field} is invalid`);
  return { currency, minorUnits };
}
function normalizePaymentStatus(status: string): string { return status === "PENDING_CHANNEL" ? "CREATED" : status; }
