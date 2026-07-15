import { type EventPublisher } from "@trainticket/ts-kit";
import { DomainError, WaitlistEntry, WaitlistQueue, isActiveStatus, type CreateWaitlistEntry, type FareClass, type PriorityInput, type WaitlistEntrySnapshot, type WaitlistRequestResource, type WaitlistStatus } from "./domain.js";
import { HttpCapacityAvailabilityClient, HttpFarePricingClient, HttpOfferManagementClient, PromotionOrchestrator, type CapacityAvailabilityClient, type FarePricingClient, type OfferManagementClient, type WaitlistCapacityFreed, type WaitlistRepository } from "./promotion.js";
import { publishAll, waitlistCancelled, waitlistFulfilled, waitlistPaymentAuthorizationRequested, waitlistQueued, waitlistRequestCreated } from "./publisher.js";

export type JoinWaitlistRequest = Readonly<{
  accountId: string;
  travelerRef?: string;
  travelerRefs?: readonly string[];
  segmentRef: string;
  travelClass?: FareClass;
  seatClass?: FareClass;
  departureDate?: string;
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
export type CancelWaitlistResponse = Readonly<{ waitlistRequestId: string; status: "CANCELLED"; cancelledAt: string }>;
export type AcceptPromotionRequest = Readonly<{ paymentMethodRef?: string }>;
export type AcceptPromotionResponse = Readonly<{ orderId: string; seatAssignment: unknown }>;
export type QueueInfo = Readonly<{ totalQueued: number; myPosition: number | null; estimatedPromotionRate: number }>;
export type ListWaitlistRequestsResponse = Readonly<{ items: readonly WaitlistRequestResource[]; total: number; limit: number; offset: number }>;

export interface JourneyOrderClient {
  createOrder(entry: WaitlistEntry, paymentMethodRef?: string): Promise<AcceptPromotionResponse>;
}

export class HttpJourneyOrderClient implements JourneyOrderClient {
  constructor(private readonly baseUrl = process.env.JOURNEY_ORDER_URL ?? process.env.JOURNEY_ORDER_BASE_URL ?? "http://journey-order") {}

  async createOrder(entry: WaitlistEntry, _paymentMethodRef?: string): Promise<AcceptPromotionResponse> {
    if (!entry.offerId || !entry.offerVersion) throw new DomainError("PRECONDITION_FAILED", "Waitlist request does not have an orderable offer");
    const response = await fetch(`${this.baseUrl.replace(/\/+$/u, "")}/api/v1/journey-orders`, {
      method: "POST",
      headers: { "content-type": "application/json", "Idempotency-Key": entry.journeyOrderIdempotencyKey },
      body: JSON.stringify({ accountId: entry.accountId, offerId: entry.offerId, offerVersion: entry.offerVersion, travelerRefs: entry.travelerRefs, segmentRefs: [entry.segmentRef], journeyDate: entry.departureDate, productCode: "rail-standard" }),
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
    entry.authorizePayment(entry.createdAt);
    entry.enqueue(entry.createdAt);
    entry.markPersisted();
    this.entries.set(entry.entryId, entry);
    return this.snapshot(entry);
  }

  async get(entryId: string): Promise<WaitlistEntry | undefined> { return this.entries.get(entryId); }

  async save(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    const current = this.entries.get(entry.entryId);
    if (!current) throw new DomainError("NOT_FOUND", "Waitlist request was not found");
    if (current.loadedVersion !== entry.loadedVersion && current.version !== entry.loadedVersion) throw new DomainError("CONFLICT", "Waitlist request was modified concurrently");
    this.ensureNoActiveDuplicate(entry);
    entry.markPersisted();
    this.entries.set(entry.entryId, entry);
    return this.snapshot(entry);
  }

  async findTopQueued(segmentRef: string, departureDate: string, seatClass?: string): Promise<WaitlistEntry | undefined> { return this.buildQueue(segmentRef, departureDate, seatClass).nextQueued(); }
  async findExpired(now: Date): Promise<readonly WaitlistEntry[]> { return [...this.entries.values()].filter((entry) => (entry.status === "QUEUED" || entry.status === "MATCHING" || entry.status === "SUSPENDED") && entry.deadline <= now); }
  async findExpiredOffers(now: Date): Promise<readonly WaitlistEntry[]> { return this.findExpired(now); }
  async findArchivable(): Promise<readonly WaitlistEntry[]> { return [...this.entries.values()].filter((entry) => entry.status === "FULFILLED" || entry.status === "EXPIRED" || entry.status === "CANCELLED"); }
  async findMatching(): Promise<readonly WaitlistEntry[]> { return [...this.entries.values()].filter((entry) => entry.status === "MATCHING"); }

  async queueFor(segmentRef: string, departureDate: string, seatClass?: string): Promise<readonly WaitlistEntrySnapshot[]> {
    const queue = this.buildQueue(segmentRef, departureDate, seatClass);
    return queue.allEntries().map((entry) => this.snapshot(entry));
  }

  async listByTraveler(travelerRef: string, status?: WaitlistStatus, limit = 20, offset = 0): Promise<{ items: readonly WaitlistEntrySnapshot[]; total: number }> {
    const filtered = [...this.entries.values()].filter((entry) => entry.travelerRef === travelerRef && (!status || entry.status === status)).sort((left, right) => right.createdAt.getTime() - left.createdAt.getTime() || left.entryId.localeCompare(right.entryId));
    return { items: filtered.slice(offset, offset + limit).map((entry) => this.snapshot(entry)), total: filtered.length };
  }

  clear(): void { this.entries.clear(); }

  private ensureNoActiveDuplicate(entry: WaitlistEntry): void {
    if (!isActiveStatus(entry.status)) return;
    const duplicate = [...this.entries.values()].find((candidate) => candidate.entryId !== entry.entryId && candidate.travelerRef === entry.travelerRef && candidate.intentFingerprint === entry.intentFingerprint && isActiveStatus(candidate.status));
    if (duplicate) throw new DomainError("CONFLICT", "An active waitlist request already exists for this traveler and intent");
  }

  private buildQueue(segmentRef: string, departureDate: string, seatClass?: string): WaitlistQueue {
    const matching = [...this.entries.values()].filter((entry) => entry.status !== "CLOSED" && entry.segmentRef === segmentRef && entry.departureDate === departureDate && (!seatClass || entry.seatClass === seatClass));
    const classForQueue = (seatClass ?? matching[0]?.seatClass ?? "SECOND") as FareClass;
    return new WaitlistQueue(segmentRef, departureDate, classForQueue, matching.filter((entry) => entry.seatClass === classForQueue));
  }

  private snapshot(entry: WaitlistEntry): WaitlistEntrySnapshot { return entry.toSnapshot(this.position(entry)); }
  private position(entry: WaitlistEntry): number { return this.buildQueue(entry.segmentRef, entry.departureDate, entry.seatClass).positionOf(entry.entryId); }
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
    if (this.publisher) await publishAll(this.publisher, [
      waitlistRequestCreated({ ...snapshot, status: "DRAFT", version: 1 }, { correlationId, occurredAt: snapshot.createdAt }),
      waitlistPaymentAuthorizationRequested({ ...snapshot, status: "DRAFT", version: 2 }, snapshot.createdAt, { correlationId, occurredAt: snapshot.createdAt }),
      waitlistQueued(snapshot, snapshot.createdAt, { correlationId, occurredAt: snapshot.createdAt }),
    ]);
    return toResource(snapshot);
  }

  async get(entryId: string): Promise<WaitlistRequestResource> { return toResource(await this.snapshot(await this.requireEntry(entryId))); }

  async listByTraveler(travelerRef: string, status?: WaitlistStatus, limit = 20, offset = 0): Promise<ListWaitlistRequestsResponse> {
    if (!travelerRef) throw new DomainError("VALIDATION_FAILED", "travelerRef is required");
    const page = await this.repository.listByTraveler(travelerRef, status, limit, offset);
    return { items: page.items.map(toResource), total: page.total, limit, offset };
  }

  async cancel(entryId: string, request: CancelWaitlistRequest = {}, correlationId?: string): Promise<CancelWaitlistResponse> {
    const reason = required(request.reason, "reason");
    const entry = await this.requireEntry(entryId);
    const now = this.now();
    entry.cancel(now);
    const snapshot = await this.repository.save(entry);
    if (this.publisher) await publishAll(this.publisher, [waitlistCancelled(snapshot, reason, snapshot.cancelledAt ?? now.toISOString(), { correlationId, occurredAt: now })]);
    return { waitlistRequestId: snapshot.waitlistRequestId, status: "CANCELLED", cancelledAt: snapshot.cancelledAt ?? now.toISOString() };
  }

  async accept(entryId: string, request: AcceptPromotionRequest = {}, correlationId?: string): Promise<AcceptPromotionResponse> {
    const entry = await this.requireEntry(entryId);
    const now = this.now();
    const order = await this.journeyOrder.createOrder(entry, request.paymentMethodRef);
    entry.recordJourneyOrderStarted(order.orderId, now);
    await this.repository.save(entry);
    return order;
  }

  async handleJourneyOrderConfirmed(event: JourneyOrderFact, correlationId?: string): Promise<WaitlistRequestResource | undefined> {
    const entry = await this.findMatchingJourneyOrderRequest(event.orderId);
    if (!entry) return undefined;
    const now = event.occurredAt ? new Date(event.occurredAt) : this.now();
    entry.fulfill(event.orderId, now);
    const snapshot = await this.repository.save(entry);
    if (this.publisher) await publishAll(this.publisher, [waitlistFulfilled(snapshot, event.orderId, snapshot.fulfilledAt ?? now.toISOString(), { correlationId, occurredAt: now })]);
    return toResource(snapshot);
  }

  async handleJourneyOrderCancelled(event: JourneyOrderCancelledFact, correlationId?: string): Promise<WaitlistRequestResource | undefined> {
    const entry = await this.findMatchingJourneyOrderRequest(event.orderId);
    if (!entry) return undefined;
    const now = event.occurredAt ? new Date(event.occurredAt) : this.now();
    entry.requeueAfterJourneyOrderCancelled(event.orderId, now);
    const snapshot = await this.repository.save(entry);
    if (this.publisher) await publishAll(this.publisher, [waitlistQueued(snapshot, now.toISOString(), { correlationId, occurredAt: now, requeueReason: event.reason })]);
    return toResource(snapshot);
  }

  async queueInfo(segmentRef: string, departureDate: string, seatClass?: string, entryId?: string): Promise<QueueInfo> {
    const queue = await this.repository.queueFor(segmentRef, departureDate, seatClass);
    const queued = queue.filter((entry) => entry.status === "QUEUED");
    return { totalQueued: queued.length, myPosition: entryId ? queued.find((entry) => entry.entryId === entryId)?.queuePosition ?? null : null, estimatedPromotionRate: queued.length === 0 ? 1 : 1 / queued.length };
  }

  async handleCapacityFreed(event: WaitlistCapacityFreed, correlationId?: string) { return this.promotion.onCapacityFreed(event, correlationId); }
  async expireDueOffers(correlationId?: string) { return this.promotion.expireDueOffers(correlationId); }
  async sweepArchived(): Promise<readonly WaitlistRequestResource[]> {
    const closed: WaitlistRequestResource[] = [];
    for (const entry of await this.repository.findArchivable()) {
      entry.close(this.now());
      closed.push(toResource(await this.repository.save(entry)));
    }
    return closed;
  }

  private toCreateCommand(request: JoinWaitlistRequest): CreateWaitlistEntry {
    const travelerRefs = request.travelerRefs ?? (request.travelerRef ? [request.travelerRef] : []);
    const travelClass = request.travelClass ?? request.seatClass ?? "SECOND";
    const deadline = required(request.deadline, "deadline");
    const departureDate = request.departureDate ?? deadline.slice(0, 10);
    return { accountId: request.accountId, travelerRef: request.travelerRef ?? travelerRefs[0], travelerRefs, segmentRef: request.segmentRef, departureDate, deadline, seatClass: travelClass, travelClass, paymentGuaranteeRef: required(request.paymentGuaranteeRef, "paymentGuaranteeRef"), itineraryRef: required(request.itineraryRef, "itineraryRef"), intentFingerprint: required(request.intentFingerprint, "intentFingerprint"), priority: { loyaltyTier: request.loyaltyTier ?? "NONE", tripCount: request.tripCount ?? 0, daysBefore: request.daysBefore ?? daysBefore(departureDate), groupSize: travelerRefs.length || 1, fareClass: travelClass, specialStatus: request.specialStatus ?? "NONE" } };
  }

  private async findMatchingJourneyOrderRequest(orderId: string): Promise<WaitlistEntry | undefined> {
    for (const entry of await this.repository.findMatching()) {
      if (entry.pendingJourneyOrderRef === orderId || entry.journeyOrderIdempotencyKey === orderId) return entry;
    }
    return undefined;
  }

  private async requireEntry(entryId: string): Promise<WaitlistEntry> {
    const entry = await this.repository.get(entryId);
    if (!entry) throw new DomainError("NOT_FOUND", "Waitlist request was not found");
    return entry;
  }

  private async snapshot(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    const queue = await this.repository.queueFor(entry.segmentRef, entry.departureDate, entry.seatClass);
    return queue.find((candidate) => candidate.entryId === entry.entryId) ?? entry.toSnapshot(0);
  }
}

function toResource(snapshot: WaitlistEntrySnapshot): WaitlistRequestResource {
  const resource: WaitlistRequestResource = { waitlistRequestId: snapshot.waitlistRequestId, accountId: snapshot.accountId, travelerRef: snapshot.travelerRef, segmentRef: snapshot.segmentRef, travelClass: snapshot.travelClass, deadline: snapshot.deadline, paymentGuaranteeRef: snapshot.paymentGuaranteeRef, itineraryRef: snapshot.itineraryRef, intentFingerprint: snapshot.intentFingerprint, status: snapshot.status, journeyOrderRef: snapshot.journeyOrderRef };
  return Object.fromEntries(Object.entries(resource).filter(([, value]) => value !== undefined)) as WaitlistRequestResource;
}

function daysBefore(departureDate: string): number {
  const departure = new Date(`${departureDate}T00:00:00.000Z`).getTime();
  if (!Number.isFinite(departure)) return 0;
  return Math.max(0, Math.ceil((departure - Date.now()) / (24 * 60 * 60 * 1000)));
}

export type JourneyOrderFact = Readonly<{ orderId: string; occurredAt?: string }>;
export type JourneyOrderCancelledFact = JourneyOrderFact & Readonly<{ reason?: string }>;
function required(value: string | undefined, field: string): string { if (!value || value.trim().length === 0) throw new DomainError("VALIDATION_FAILED", `${field} is required`); return value; }
