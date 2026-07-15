import { type EventPublisher } from "@trainticket/ts-kit";
import { DomainError, WaitlistEntry, WaitlistQueue, type CreateWaitlistEntry, type FareClass, type PriorityInput, type WaitlistEntrySnapshot } from "./domain.js";
import { HttpCapacityAvailabilityClient, HttpFarePricingClient, HttpOfferManagementClient, PromotionOrchestrator, type CapacityAvailabilityClient, type FarePricingClient, type OfferManagementClient, type WaitlistCapacityFreed, type WaitlistRepository } from "./promotion.js";
import { publishAll, waitlistCancelled, waitlistFulfilled, waitlistPaymentAuthorizationRequested, waitlistQueued, waitlistRequestCreated } from "./publisher.js";

export type JoinWaitlistRequest = Readonly<{
  accountId?: string;
  travelerRefs?: readonly string[];
  travelerRef?: string;
  segmentRef?: string;
  departureDate?: string;
  seatClass?: FareClass;
  travelClass?: FareClass;
  deadline?: string;
  paymentGuaranteeRef?: string;
  loyaltyTier?: PriorityInput["loyaltyTier"];
  tripCount?: number;
  daysBefore?: number;
  specialStatus?: PriorityInput["specialStatus"];
  itineraryRef?: string;
  intentFingerprint?: string;
}>;

export type AcceptPromotionRequest = Readonly<{ paymentMethodRef?: string }>;
export type CancelWaitlistRequest = Readonly<{ reason?: string }>;
export type AcceptPromotionResponse = Readonly<{ orderId: string; seatAssignment: unknown }>;
export type QueueInfo = Readonly<{ totalQueued: number; myPosition: number | null; estimatedPromotionRate: number }>;
export type WaitlistRequestList = Readonly<{
  items: readonly WaitlistRequestResource[];
  total: number;
  limit: number;
  offset: number;
}>;

export type WaitlistRequestResource = Readonly<{
  waitlistRequestId: string;
  accountId: string;
  travelerRef: string;
  segmentRef: string;
  travelClass?: string;
  deadline: string;
  paymentGuaranteeRef: string;
  itineraryRef: string;
  intentFingerprint: string;
  status: WaitlistEntrySnapshot["status"];
  journeyOrderRef?: string;
}>;

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
    if (this.hasActiveDuplicate(entry)) {
      throw new DomainError("CONFLICT", "An active waitlist request already exists for this traveler and intent");
    }
    this.entries.set(entry.entryId, entry);
    entry.markPersisted(1);
    return this.snapshot(entry);
  }

  async get(entryId: string): Promise<WaitlistEntry | undefined> { return this.entries.get(entryId); }

  async save(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    this.entries.set(entry.entryId, entry);
    entry.markPersisted(entry.loadedVersion + 1);
    return this.snapshot(entry);
  }

  async findTopQueued(segmentRef: string, departureDate: string, seatClass?: string): Promise<WaitlistEntry | undefined> {
    const queue = this.buildQueue(segmentRef, departureDate, seatClass);
    return queue.nextQueued();
  }

  async findExpiredOffers(now: Date): Promise<readonly WaitlistEntry[]> {
    return [...this.entries.values()].filter((entry) => entry.status === "MATCHING" && entry.offerExpiresAt !== null && entry.offerExpiresAt <= now);
  }

  async findByJourneyOrderRef(journeyOrderRef: string): Promise<WaitlistEntry | undefined> {
    return [...this.entries.values()].find((entry) => entry.journeyOrderRef === journeyOrderRef);
  }

  async queueFor(segmentRef: string, departureDate: string, seatClass?: string): Promise<readonly WaitlistEntrySnapshot[]> {
    const queue = this.buildQueue(segmentRef, departureDate, seatClass);
    return queue.allEntries().map((entry) => this.snapshot(entry));
  }

  async listByTraveler(travelerRef: string): Promise<readonly WaitlistEntrySnapshot[]> {
    return [...this.entries.values()]
      .filter((entry) => entry.travelerRefs.includes(travelerRef))
      .sort((left, right) => left.createdAt.getTime() - right.createdAt.getTime())
      .map((entry) => this.snapshot(entry));
  }

  clear(): void { this.entries.clear(); }

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

  private hasActiveDuplicate(entry: WaitlistEntry): boolean {
    const travelerRef = entry.travelerRefs[0] ?? "";
    return [...this.entries.values()].some((candidate) => candidate.entryId !== entry.entryId
      && activeStatuses.has(candidate.status)
      && candidate.travelerRefs[0] === travelerRef
      && candidate.intentFingerprint === entry.intentFingerprint);
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

  async join(request: JoinWaitlistRequest | null | undefined, correlationId?: string): Promise<WaitlistRequestResource> {
    const entry = WaitlistEntry.create(this.toCreateCommand(request));
    const snapshot = await this.repository.add(entry);
    const aggregateVersion = entry.loadedVersion;
    if (this.publisher) {
      await publishAll(this.publisher, [
        waitlistRequestCreated(snapshot, aggregateVersion, correlationId),
        waitlistPaymentAuthorizationRequested(snapshot, aggregateVersion, correlationId),
        waitlistQueued(snapshot, aggregateVersion, correlationId, snapshot.createdAt),
      ]);
    }
    return toWaitlistRequestResource(snapshot);
  }

  async get(entryId: string): Promise<WaitlistRequestResource> {
    const entry = await this.requireEntry(entryId);
    return toWaitlistRequestResource(await this.snapshot(entry));
  }

  async listByTraveler(travelerRef: string, status?: WaitlistEntrySnapshot["status"], limit = 20, offset = 0): Promise<WaitlistRequestList> {
    assertRequiredString(travelerRef, "travelerRef");
    const snapshots = (await this.repository.listByTraveler(travelerRef)).filter((entry) => !status || entry.status === status);
    const page = snapshots.slice(offset, offset + limit);
    return { items: page.map(toWaitlistRequestResource), total: snapshots.length, limit, offset };
  }

  async cancel(entryId: string, request: CancelWaitlistRequest = {}, correlationId?: string): Promise<{ waitlistRequestId: string; status: "CANCELLED"; cancelledAt: string }> {
    const reason = assertRequiredString(request.reason, "reason");
    const entry = await this.requireEntry(entryId);
    const cancelledAt = this.now().toISOString();
    try {
      entry.cancel();
    } catch (error) {
      if (error instanceof DomainError && error.code === "INVALID_TRANSITION") throw new DomainError("PRECONDITION_FAILED", error.message);
      throw error;
    }
    const snapshot = await this.repository.save(entry);
    if (this.publisher) await publishAll(this.publisher, [waitlistCancelled(snapshot, entry.loadedVersion, reason, correlationId, cancelledAt)]);
    return { waitlistRequestId: snapshot.entryId, status: "CANCELLED", cancelledAt };
  }

  async accept(entryId: string, request: AcceptPromotionRequest = {}, correlationId?: string): Promise<AcceptPromotionResponse> {
    const entry = await this.requireEntry(entryId);
    const now = this.now();
    entry.ensureOfferAcceptable(now);
    const order = await this.journeyOrder.createOrder(entry, request.paymentMethodRef);
    entry.attachJourneyOrder(order.orderId);
    await this.repository.save(entry);
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

  async handleJourneyOrderConfirmed(orderId: string, correlationId?: string): Promise<WaitlistRequestResource | undefined> {
    const entry = await this.repository.findByJourneyOrderRef?.(orderId);
    if (!entry) return undefined;
    if (entry.status === "FULFILLED") return toWaitlistRequestResource(await this.snapshot(entry));
    const now = this.now();
    entry.accept(now, orderId);
    const snapshot = await this.repository.save(entry);
    if (this.publisher) await publishAll(this.publisher, [waitlistFulfilled(snapshot, entry.loadedVersion, orderId, correlationId, now.toISOString())]);
    return toWaitlistRequestResource(snapshot);
  }

  async handleJourneyOrderCancelled(orderId: string, correlationId?: string): Promise<WaitlistRequestResource | undefined> {
    const entry = await this.repository.findByJourneyOrderRef?.(orderId);
    if (!entry || entry.status !== "MATCHING") return undefined;
    const queuedAt = this.now().toISOString();
    entry.returnToQueue();
    const snapshot = await this.repository.save(entry);
    if (this.publisher) await publishAll(this.publisher, [waitlistQueued(snapshot, entry.loadedVersion, correlationId, queuedAt, { journeyOrderRef: orderId })]);
    return toWaitlistRequestResource(snapshot);
  }

  async expireDueOffers(correlationId?: string) {
    return this.promotion.expireDueOffers(correlationId);
  }

  private toCreateCommand(request: JoinWaitlistRequest | null | undefined): CreateWaitlistEntry {
    const payload = request ?? {};
    const travelerRef = assertRequiredString(payload.travelerRef, "travelerRef");
    const deadline = assertRequiredString(payload.deadline, "deadline");
    const departureDate = payload.departureDate ?? dateFromDeadline(deadline);
    const seatClass = payload.seatClass ?? payload.travelClass ?? "SECOND";
    return {
      accountId: assertRequiredString(payload.accountId, "accountId"),
      travelerRefs: [travelerRef],
      segmentRef: assertRequiredString(payload.segmentRef, "segmentRef"),
      departureDate,
      seatClass,
      itineraryRef: assertRequiredString(payload.itineraryRef, "itineraryRef"),
      deadline,
      paymentGuaranteeRef: assertRequiredString(payload.paymentGuaranteeRef, "paymentGuaranteeRef"),
      intentFingerprint: assertRequiredString(payload.intentFingerprint, "intentFingerprint"),
      priority: {
        loyaltyTier: payload.loyaltyTier ?? "NONE",
        tripCount: payload.tripCount ?? 0,
        daysBefore: payload.daysBefore ?? daysBefore(departureDate),
        groupSize: 1,
        fareClass: seatClass,
        specialStatus: payload.specialStatus ?? "NONE",
      },
    };
  }

  private async requireEntry(entryId: string): Promise<WaitlistEntry> {
    const entry = await this.repository.get(entryId);
    if (!entry) throw new DomainError("NOT_FOUND", "Waitlist entry was not found");
    return entry;
  }

  private async snapshot(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    const queue = await this.repository.queueFor(entry.segmentRef, entry.departureDate, entry.seatClass);
    return queue.find((candidate) => candidate.entryId === entry.entryId) ?? entry.toSnapshot(0);
  }
}

const activeStatuses = new Set<WaitlistEntrySnapshot["status"]>(["DRAFT", "QUEUED", "MATCHING", "SUSPENDED"]);

function toWaitlistRequestResource(snapshot: WaitlistEntrySnapshot): WaitlistRequestResource {
  return {
    waitlistRequestId: snapshot.entryId,
    accountId: snapshot.accountId,
    travelerRef: snapshot.travelerRefs[0] ?? "",
    segmentRef: snapshot.segmentRef,
    travelClass: snapshot.seatClass,
    deadline: snapshot.deadline,
    paymentGuaranteeRef: snapshot.paymentGuaranteeRef,
    itineraryRef: snapshot.itineraryRef ?? snapshot.segmentRef,
    intentFingerprint: snapshot.intentFingerprint,
    status: snapshot.status,
    journeyOrderRef: snapshot.journeyOrderRef,
  };
}

function daysBefore(departureDate: string): number {
  const departure = new Date(`${departureDate}T00:00:00.000Z`).getTime();
  if (!Number.isFinite(departure)) return 0;
  return Math.max(0, Math.ceil((departure - Date.now()) / (24 * 60 * 60 * 1000)));
}

function dateFromDeadline(deadline: string): string {
  return deadline.slice(0, 10);
}

function assertRequiredString(value: string | undefined, field: string): string {
  if (typeof value !== "string" || value.trim().length === 0) throw new DomainError("VALIDATION_FAILED", `${field} is required`);
  return value;
}
