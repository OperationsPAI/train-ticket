import { type EventPublisher } from "@trainticket/ts-kit";
import { ACTIVE_WAITLIST_STATUSES, DomainError, WaitlistEntry, WaitlistQueue, type CreateWaitlistEntry, type FareClass, type PriorityInput, type WaitlistEntrySnapshot, type WaitlistRequestResource } from "./domain.js";
import { HttpCapacityAvailabilityClient, HttpFarePricingClient, HttpOfferManagementClient, PromotionOrchestrator, type CapacityAvailabilityClient, type FarePricingClient, type OfferManagementClient, type WaitlistCapacityFreed, type WaitlistRepository } from "./promotion.js";
import { publishAll, waitlistCancelled, waitlistExpired, waitlistFulfilled, waitlistPaymentAuthorizationRequested, waitlistQueued, waitlistRequestCreated } from "./publisher.js";

export type JoinWaitlistRequest = Readonly<{
  accountId: string;
  travelerRef?: string;
  travelerRefs?: readonly string[];
  segmentRef: string;
  departureDate?: string;
  seatClass?: FareClass;
  travelClass?: FareClass;
  deadline?: string;
  paymentGuaranteeRef?: string;
  itineraryRef?: string;
  intentFingerprint?: string;
  loyaltyTier?: PriorityInput["loyaltyTier"];
  tripCount?: number;
  daysBefore?: number;
  specialStatus?: PriorityInput["specialStatus"];
}>;

export type CancelWaitlistRequest = Readonly<{ reason?: string }>;

export type JourneyOrderEvent = Readonly<{
  eventId?: string;
  waitlistRequestId?: string;
  journeyOrderRef: string;
}>;
export type AcceptPromotionRequest = Readonly<{ paymentMethodRef?: string }>;
export type AcceptPromotionResponse = Readonly<{ orderId: string; seatAssignment: unknown }>;
export type QueueInfo = Readonly<{ totalQueued: number; myPosition: number | null; estimatedPromotionRate: number }>;

export interface JourneyOrderClient {
  createOrder(entry: WaitlistEntry, paymentMethodRef?: string): Promise<AcceptPromotionResponse>;
}

export class HttpJourneyOrderClient implements JourneyOrderClient {
  constructor(private readonly baseUrl = process.env.JOURNEY_ORDER_URL ?? process.env.JOURNEY_ORDER_BASE_URL ?? "http://journey-order") {}

  async createOrder(entry: WaitlistEntry, _paymentMethodRef?: string): Promise<AcceptPromotionResponse> {
    if (!entry.offerId || !entry.offerVersion) {
      throw new DomainError("PRECONDITION_FAILED", "Waitlist entry does not have an orderable offer");
    }
    const response = await fetch(`${this.baseUrl.replace(/\/+$/u, "")}/api/v1/journey-orders`, {
      method: "POST",
      headers: { "content-type": "application/json", "Idempotency-Key": entry.journeyOrderIdempotencyKey },
      body: JSON.stringify({
        accountId: entry.accountId,
        offerId: entry.offerId,
        offerVersion: entry.offerVersion,
        travelerRefs: entry.travelerRefs,
        segmentRefs: [entry.segmentRef],
        journeyDate: entry.departureDate,
        productCode: "rail-standard",
      }),
    });
    if (!response.ok) throw new Error(`Journey order request failed with status ${response.status}`);
    const body = await response.json() as Record<string, unknown>;
    return { orderId: String(body.orderId ?? body.id ?? `ord-${entry.entryId}`), seatAssignment: null };
  }
}

export class InMemoryWaitlistRepository implements WaitlistRepository {
  private readonly entries = new Map<string, WaitlistEntry>();

  async add(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    this.ensureNoActiveDuplicate(entry);
    entry.authorizePaymentReference();
    entry.enqueue();
    entry.markPersisted();
    this.entries.set(entry.entryId, entry);
    return this.snapshot(entry);
  }

  async get(entryId: string): Promise<WaitlistEntry | undefined> { return this.entries.get(entryId); }

  async save(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    entry.markPersisted();
    this.entries.set(entry.entryId, entry);
    return this.snapshot(entry);
  }

  async findTopQueued(segmentRef: string, departureDate: string, seatClass?: string): Promise<WaitlistEntry | undefined> {
    const queue = this.buildQueue(segmentRef, departureDate, seatClass);
    return queue.nextQueued();
  }

  async findExpired(now: Date): Promise<readonly WaitlistEntry[]> {
    return [...this.entries.values()].filter((entry) => ACTIVE_WAITLIST_STATUSES.includes(entry.status) && entry.deadline <= now);
  }

  async findArchivable(): Promise<readonly WaitlistEntry[]> {
    return [...this.entries.values()].filter((entry) => entry.status === "FULFILLED" || entry.status === "EXPIRED" || entry.status === "CANCELLED");
  }

  async queueFor(segmentRef: string, departureDate: string, seatClass?: string): Promise<readonly WaitlistEntrySnapshot[]> {
    const queue = this.buildQueue(segmentRef, departureDate, seatClass);
    return queue.activeEntries().map((entry) => this.snapshot(entry));
  }

  async findByJourneyOrderRef(journeyOrderRef: string): Promise<WaitlistEntry | undefined> {
    return [...this.entries.values()].find((entry) => entry.journeyOrderRef === journeyOrderRef);
  }

  clear(): void { this.entries.clear(); }

  private ensureNoActiveDuplicate(entry: WaitlistEntry): void {
    const duplicate = [...this.entries.values()].find((candidate) => candidate.travelerRef === entry.travelerRef && candidate.intentFingerprint === entry.intentFingerprint && ACTIVE_WAITLIST_STATUSES.includes(candidate.status));
    if (duplicate) throw new DomainError("CONFLICT", "An active waitlist request already exists for this traveler and intent");
  }

  private buildQueue(segmentRef: string, departureDate: string, seatClass?: string): WaitlistQueue {
    const matching = [...this.entries.values()].filter((entry) => entry.segmentRef === segmentRef && entry.departureDate === departureDate && (!seatClass || entry.seatClass === seatClass));
    const classForQueue = (seatClass ?? matching[0]?.seatClass ?? "SECOND") as FareClass;
    return new WaitlistQueue(segmentRef, departureDate, classForQueue, matching.filter((entry) => entry.seatClass === classForQueue));
  }

  private snapshot(entry: WaitlistEntry): WaitlistEntrySnapshot {
    return entry.toSnapshot(this.position(entry));
  }

  private position(entry: WaitlistEntry): number {
    return this.buildQueue(entry.segmentRef, entry.departureDate, entry.seatClass).positionOf(entry.entryId);
  }
}

export class WaitlistApplicationService {
  private readonly promotion: PromotionOrchestrator;

  constructor(
    private readonly repository: WaitlistRepository = new InMemoryWaitlistRepository(),
    private readonly publisher?: EventPublisher,
    farePricing: FarePricingClient = new HttpFarePricingClient(),
    capacityAvailability: CapacityAvailabilityClient = new HttpCapacityAvailabilityClient(),
    private readonly journeyOrder: JourneyOrderClient = new HttpJourneyOrderClient(),
    private readonly now: () => Date = () => new Date(),
    offerManagement: OfferManagementClient = new HttpOfferManagementClient(),
  ) {
    this.promotion = new PromotionOrchestrator(repository, farePricing, capacityAvailability, publisher ?? { publish: async () => undefined }, offerManagement, now);
  }

  async join(request: JoinWaitlistRequest, correlationId?: string): Promise<WaitlistRequestResource> {
    const entry = WaitlistEntry.create(this.toCreateCommand(request));
    const snapshot = await this.repository.add(entry);
    if (this.publisher) {
      await publishAll(this.publisher, [
        waitlistRequestCreated({ ...snapshot, version: 1, status: "DRAFT" }, correlationId),
        waitlistPaymentAuthorizationRequested({ ...snapshot, version: 2, status: "DRAFT" }, this.now(), correlationId),
        waitlistQueued(snapshot, this.now(), correlationId),
      ]);
    }
    return this.resource(snapshot);
  }

  async get(entryId: string): Promise<WaitlistRequestResource> {
    const entry = await this.requireEntry(entryId);
    return entry.toResource();
  }

  async cancel(entryId: string, request: CancelWaitlistRequest = {}, correlationId?: string): Promise<{ waitlistRequestId: string; status: "CANCELLED"; cancelledAt: string }> {
    const entry = await this.requireEntry(entryId);
    const cancelledAt = this.now();
    entry.cancel(cancelledAt);
    const snapshot = await this.repository.save(entry);
    if (this.publisher) await publishAll(this.publisher, [waitlistCancelled(snapshot, cancelledAt, request.reason ?? "user_cancelled", correlationId)]);
    return { waitlistRequestId: entryId, status: "CANCELLED", cancelledAt: cancelledAt.toISOString() };
  }

  async accept(entryId: string, request: AcceptPromotionRequest = {}, correlationId?: string): Promise<AcceptPromotionResponse> {
    const entry = await this.requireEntry(entryId);
    const order = await this.journeyOrder.createOrder(entry, request.paymentMethodRef);
    const fulfilledAt = this.now();
    entry.fulfill(order.orderId, fulfilledAt);
    const snapshot = await this.repository.save(entry);
    if (this.publisher) await publishAll(this.publisher, [waitlistFulfilled(snapshot, fulfilledAt, correlationId)]);
    return order;
  }

  async queueInfo(segmentRef: string, departureDate: string, seatClass?: string, entryId?: string): Promise<QueueInfo> {
    const queue = await this.repository.queueFor(segmentRef, departureDate, seatClass);
    const queued = queue.filter((entry) => entry.status === "QUEUED");
    return { totalQueued: queued.length, myPosition: entryId ? queued.find((entry) => entry.entryId === entryId)?.queuePosition ?? null : null, estimatedPromotionRate: queued.length === 0 ? 1 : 1 / queued.length };
  }

  async handleCapacityFreed(event: WaitlistCapacityFreed, correlationId?: string) {
    return this.promotion.onCapacityFreed(event, correlationId);
  }


  async handleJourneyOrderConfirmed(event: JourneyOrderEvent, correlationId?: string) {
    const entry = await this.findJourneyEntry(event);
    if (entry.status === "FULFILLED") return entry.toResource();
    const fulfilledAt = this.now();
    entry.fulfill(event.journeyOrderRef, fulfilledAt);
    const snapshot = await this.repository.save(entry);
    if (this.publisher) await publishAll(this.publisher, [waitlistFulfilled(snapshot, fulfilledAt, correlationId)]);
    return this.resource(snapshot);
  }

  async handleJourneyOrderCancelled(event: JourneyOrderEvent, correlationId?: string) {
    const entry = await this.findJourneyEntry(event);
    if (entry.status === "QUEUED") return entry.toResource();
    entry.requeueAfterJourneyOrderCancelled(event.journeyOrderRef);
    const snapshot = await this.repository.save(entry);
    if (this.publisher) await publishAll(this.publisher, [waitlistQueued(snapshot, this.now(), correlationId)]);
    return this.resource(snapshot);
  }

  async expireDueRequests(correlationId?: string) {
    const now = this.now();
    const expired: WaitlistEntrySnapshot[] = [];
    for (const entry of await this.repository.findExpired(now)) {
      entry.expire(now);
      const snapshot = await this.repository.save(entry);
      expired.push(snapshot);
      if (this.publisher) await publishAll(this.publisher, [waitlistExpired(snapshot, now, correlationId)]);
    }
    return expired;
  }

  async expireDueOffers(correlationId?: string) {
    return this.expireDueRequests(correlationId);
  }

  async archivalSweep(): Promise<readonly WaitlistRequestResource[]> {
    const now = this.now();
    const closed: WaitlistRequestResource[] = [];
    for (const entry of await this.repository.findArchivable()) {
      entry.close(now);
      const snapshot = await this.repository.save(entry);
      closed.push(this.resource(snapshot));
    }
    return closed;
  }

  private toCreateCommand(request: JoinWaitlistRequest): CreateWaitlistEntry {
    const travelerRefs = request.travelerRefs ?? (request.travelerRef ? [request.travelerRef] : []);
    const deadline = request.deadline ?? `${request.departureDate ?? "2099-01-01"}T00:00:00.000Z`;
    return {
      accountId: request.accountId,
      travelerRefs,
      segmentRef: request.segmentRef,
      departureDate: request.departureDate ?? deadline.slice(0, 10),
      seatClass: request.seatClass ?? request.travelClass ?? "SECOND",
      deadline,
      paymentGuaranteeRef: request.paymentGuaranteeRef ?? "pay-auth-test",
      itineraryRef: request.itineraryRef ?? request.segmentRef,
      intentFingerprint: request.intentFingerprint ?? `${travelerRefs[0] ?? "unknown"}:${request.segmentRef}:${deadline}`,
      priority: {
        loyaltyTier: request.loyaltyTier ?? "NONE",
        tripCount: request.tripCount ?? 0,
        daysBefore: request.daysBefore ?? daysBefore(deadline.slice(0, 10)),
        groupSize: travelerRefs.length,
        fareClass: request.seatClass ?? request.travelClass ?? "SECOND",
        specialStatus: request.specialStatus ?? "NONE",
      },
    };
  }


  private async findJourneyEntry(event: JourneyOrderEvent): Promise<WaitlistEntry> {
    const entry = event.waitlistRequestId
      ? await this.repository.get(event.waitlistRequestId)
      : await this.repository.findByJourneyOrderRef?.(event.journeyOrderRef);
    if (!entry) throw new DomainError("NOT_FOUND", "Waitlist request was not found for journey order event");
    return entry;
  }

  private async requireEntry(entryId: string): Promise<WaitlistEntry> {
    const entry = await this.repository.get(entryId);
    if (!entry) throw new DomainError("NOT_FOUND", "Waitlist request was not found");
    return entry;
  }

  private resource(snapshot: WaitlistEntrySnapshot): WaitlistRequestResource {
    const { waitlistRequestId, accountId, travelerRef, segmentRef, travelClass, deadline, paymentGuaranteeRef, itineraryRef, intentFingerprint, status, journeyOrderRef } = snapshot;
    return removeUndefined({ waitlistRequestId, accountId, travelerRef, segmentRef, travelClass, deadline, paymentGuaranteeRef, itineraryRef, intentFingerprint, status, journeyOrderRef });
  }
}

function daysBefore(departureDate: string): number {
  const departure = new Date(`${departureDate}T00:00:00.000Z`).getTime();
  if (!Number.isFinite(departure)) return 0;
  return Math.max(0, Math.ceil((departure - Date.now()) / (24 * 60 * 60 * 1000)));
}

function removeUndefined<T extends Record<string, unknown>>(value: T): T {
  return Object.fromEntries(Object.entries(value).filter(([, entry]) => entry !== undefined)) as T;
}
