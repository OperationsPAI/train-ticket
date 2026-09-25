import { tracedFetch, type EventPublisher } from "@trainticket/ts-kit";
import { DomainError, WaitlistEntry, WaitlistQueue, normalizeFareClass, type CreateWaitlistEntry, type FareClass, type FareClassInput, type PriorityInput, type TravelInterval, type WaitlistEntrySnapshot } from "./domain.js";
import { SuppliedWaitlistIntentResolver, type WaitlistIntentResolver } from "./intent.js";
import { HttpWaitlistFulfillmentClient, WaitlistFulfillmentOrchestrator, type WaitlistFulfillmentClient } from "./fulfillment.js";
import { HttpCapacityAvailabilityClient, HttpFarePricingClient, HttpOfferManagementClient, PromotionOrchestrator, type CapacityAvailabilityClient, type FarePricingClient, type JourneyOrderClient, type OfferManagementClient, type WaitlistCapacityFreed, type WaitlistMatchCriteria, type WaitlistRepository } from "./promotion.js";
import { publishAll, waitlistCancelled, waitlistExpired, waitlistFulfilled, waitlistPaymentAuthorizationRequested, waitlistQueued, waitlistRequestCreated } from "./publisher.js";

export type JoinWaitlistRequest = Readonly<{
  accountId: string;
  travelerRefs?: readonly string[];
  travelerRef?: string;
  segmentRef: string;
  departureDate?: string;
  seatClass?: FareClassInput;
  travelClass?: FareClassInput;
  scheduledServiceRef?: string;
  interval?: TravelInterval;
  stopSequenceVersion?: string;
  deadline?: string;
  paymentGuaranteeRef?: string;
  loyaltyTier?: PriorityInput["loyaltyTier"];
  tripCount?: number;
  daysBefore?: number;
  specialStatus?: PriorityInput["specialStatus"];
  itineraryRef?: string;
  intentFingerprint?: string;
}>;

export type AcceptPromotionRequest = Readonly<Record<string, unknown>>;
export type CancelWaitlistRequest = Readonly<{ reason?: string }>;
export type AcceptPromotionResponse = Readonly<{ orderId: string; amount?: Readonly<{ currency: string; minorUnits: number }>; seatAssignment: unknown }>;
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

export class HttpJourneyOrderClient implements JourneyOrderClient {
  constructor(private readonly baseUrl = process.env.JOURNEY_ORDER_URL ?? process.env.JOURNEY_ORDER_BASE_URL ?? "http://journey-order") {}

  async createOrder(entry: WaitlistEntry): Promise<AcceptPromotionResponse> {
    if (!entry.offerId || !entry.offerVersion) {
      throw new DomainError("PRECONDITION_FAILED", "Waitlist entry does not have an orderable offer");
    }
    const response = await tracedFetch(`${this.baseUrl.replace(/\/+$/u, "")}/api/v1/journey-orders`, {
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
    if (typeof body.orderId !== "string" || body.orderId.trim().length === 0) {
      throw new Error("Journey order response did not include orderId");
    }
    const monetarySummary = body.monetarySummary;
    const amount = monetarySummary && typeof monetarySummary === "object" && !Array.isArray(monetarySummary)
      ? moneyField((monetarySummary as Record<string, unknown>).payableTotal) : undefined;
    return { orderId: body.orderId, amount, seatAssignment: null };
  }
}

export class InMemoryWaitlistRepository implements WaitlistRepository {
  private readonly entries = new Map<string, WaitlistEntry>();
  private readonly capacityCredits = new Map<string, WaitlistCapacityFreed & { remainingSlots: number; sequence: number }>();
  private creditSequence = 0;

  async add(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    if (this.hasActiveDuplicate(entry.travelerRefs[0] ?? "", entry.intentFingerprint)) {
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

  async recordCapacityCredit(event: WaitlistCapacityFreed): Promise<boolean> {
    const existing = this.capacityCredits.get(event.capacityReleaseRef);
    if (existing) {
      if (!sameCapacityCredit(existing, event)) throw new DomainError("CONFLICT", "Capacity release reference was reused with different facts");
      return false;
    }
    this.capacityCredits.set(event.capacityReleaseRef, { ...event, interval: { ...event.interval }, remainingSlots: event.freedSlots, sequence: this.creditSequence++ });
    return true;
  }

  async claimNextPromotion(now: Date): Promise<WaitlistEntry | undefined> {
    const candidates = [...this.entries.values()]
      .filter((entry) => entry.status === "QUEUED" && new Date(entry.deadline) > now)
      .sort(compareForPromotion);
    for (const entry of candidates) {
      const credits = [...this.capacityCredits.values()]
        .filter((credit) => credit.remainingSlots > 0 && matchesCapacity(entry, {
          segmentRef: credit.segmentRef,
          scheduledServiceRef: credit.scheduledServiceRef,
          departureDate: credit.departureDate,
          seatClass: credit.seatClass,
          interval: credit.interval,
          stopSequenceVersion: credit.stopSequenceVersion,
          maxTravelers: Number.MAX_SAFE_INTEGER,
          intentFingerprint: credit.intentFingerprint,
        }))
        .sort((left, right) => left.sequence - right.sequence);
      if (credits.reduce((sum, credit) => sum + credit.remainingSlots, 0) < entry.travelerRefs.length) continue;
      let remaining = entry.travelerRefs.length;
      const allocations: Array<{ capacityReleaseRef: string; slots: number }> = [];
      for (const credit of credits) {
        if (remaining === 0) break;
        const consumed = Math.min(credit.remainingSlots, remaining);
        credit.remainingSlots -= consumed;
        remaining -= consumed;
        allocations.push({ capacityReleaseRef: credit.capacityReleaseRef, slots: consumed });
      }
      entry.beginPromotion(allocations);
      return entry;
    }
    return undefined;
  }

  async findRecoverablePromotion(): Promise<WaitlistEntry | undefined> {
    return [...this.entries.values()]
      .filter((entry) => entry.status === "MATCHING" && !entry.journeyOrderRef)
      .sort((left, right) => left.createdAt.getTime() - right.createdAt.getTime() || left.entryId.localeCompare(right.entryId))[0];
  }

  async findRecoverableFulfillment(now: Date): Promise<WaitlistEntry | undefined> {
    return [...this.entries.values()]
      .filter((entry) => entry.status === "MATCHING" && !!entry.journeyOrderRef
        && (!entry.fulfillment.nextActionAt || new Date(entry.fulfillment.nextActionAt) <= now))
      .sort((left, right) => left.createdAt.getTime() - right.createdAt.getTime() || left.entryId.localeCompare(right.entryId))[0];
  }

  async restoreCapacityCredits(entry: WaitlistEntry, slots = entry.travelerRefs.length): Promise<void> {
    if (!Number.isSafeInteger(slots) || slots < 0 || slots > entry.travelerRefs.length) {
      throw new DomainError("VALIDATION_FAILED", "restored capacity slots are outside the matched traveler group");
    }
    let remaining = slots;
    for (const allocation of entry.matchedCapacityCredits) {
      if (remaining === 0) break;
      const credit = this.capacityCredits.get(allocation.capacityReleaseRef);
      const restored = Math.min(allocation.slots, remaining);
      if (!credit || credit.remainingSlots + restored > credit.freedSlots) {
        throw new DomainError("CONFLICT", "Capacity credit cannot be restored");
      }
      credit.remainingSlots += restored;
      remaining -= restored;
    }
    if (remaining !== 0) throw new DomainError("CONFLICT", "Matched capacity credits do not cover the restored slots");
  }

  async findTopQueued(criteria: WaitlistMatchCriteria): Promise<WaitlistEntry | undefined> {
    return [...this.entries.values()]
      .filter((entry) => entry.status === "QUEUED" && matchesCapacity(entry, criteria))
      .sort(compareForPromotion)[0];
  }

  async findExpiredOffers(now: Date): Promise<readonly WaitlistEntry[]> {
    return [...this.entries.values()].filter((entry) => {
      if (entry.status === "QUEUED" || (entry.status === "SUSPENDED" && !entry.journeyOrderRef)) return hasDeadlinePassed(entry, now);
      return entry.status === "MATCHING" && !entry.journeyOrderRef && ((entry.offerExpiresAt !== null && entry.offerExpiresAt <= now) || hasDeadlinePassed(entry, now));
    });
  }

  async findArchivable(): Promise<readonly WaitlistEntry[]> {
    return [...this.entries.values()].filter((entry) => isArchivableStatus(entry.status));
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

  clear(): void { this.entries.clear(); this.capacityCredits.clear(); this.creditSequence = 0; }

  private hasActiveDuplicate(travelerRef: string, intentFingerprint: string): boolean {
    return [...this.entries.values()].some((entry) => isActiveStatus(entry.status) && entry.travelerRefs[0] === travelerRef && entry.intentFingerprint === intentFingerprint);
  }

  private buildQueue(segmentRef: string, departureDate: string, seatClass?: string): WaitlistQueue {
    const classForQueue = normalizeFareClass(seatClass ?? "SECOND_CLASS");
    const matching = [...this.entries.values()].filter((entry) => entry.status !== "CLOSED"
      && entry.segmentRef === segmentRef && entry.departureDate === departureDate && entry.seatClass === classForQueue);
    return new WaitlistQueue(segmentRef, departureDate, classForQueue, matching);
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
  private readonly fulfillment: WaitlistFulfillmentOrchestrator;

  constructor(
    private readonly repository: WaitlistRepository = new InMemoryWaitlistRepository(),
    private readonly publisher?: EventPublisher,
    farePricing: FarePricingClient = new HttpFarePricingClient(),
    capacityAvailability: CapacityAvailabilityClient = new HttpCapacityAvailabilityClient(),
    private readonly journeyOrder: JourneyOrderClient = new HttpJourneyOrderClient(),
    private readonly now: () => Date = () => new Date(),
    offerManagement: OfferManagementClient = new HttpOfferManagementClient(),
    private readonly intentResolver: WaitlistIntentResolver = new SuppliedWaitlistIntentResolver(),
    fulfillmentClient: WaitlistFulfillmentClient = new HttpWaitlistFulfillmentClient(),
  ) {
    this.promotion = new PromotionOrchestrator(repository, farePricing, capacityAvailability, publisher ?? { publish: async () => undefined }, offerManagement, now);
    this.fulfillment = new WaitlistFulfillmentOrchestrator(repository, publisher ?? { publish: async () => undefined }, fulfillmentClient, now);
  }

  async join(request: JoinWaitlistRequest, correlationId?: string): Promise<WaitlistRequestResource> {
    const entry = WaitlistEntry.create(await this.toCreateCommand(request, correlationId));
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
    const snapshots = (await this.repository.listByTraveler(travelerRef)).filter((entry) => !status || entry.status === status);
    const page = snapshots.slice(offset, offset + limit);
    return { items: page.map(toWaitlistRequestResource), total: snapshots.length, limit, offset };
  }

  async cancel(entryId: string, request: CancelWaitlistRequest = {}, correlationId?: string): Promise<{ waitlistRequestId: string; status: "CANCELLED"; cancelledAt: string }> {
    const body = objectBody(request);
    const reason = requiredString(body, "reason");
    const entry = await this.requireEntry(entryId);
    const cancelledAt = this.now().toISOString();
    entry.cancel();
    const snapshot = await this.repository.save(entry);
    if (this.publisher) await publishAll(this.publisher, [waitlistCancelled(snapshot, entry.loadedVersion, reason, correlationId, cancelledAt)]);
    return { waitlistRequestId: snapshot.entryId, status: "CANCELLED", cancelledAt };
  }

  async accept(entryId: string, _request: AcceptPromotionRequest = {}, _correlationId?: string): Promise<AcceptPromotionResponse> {
    const entry = await this.requireEntry(entryId);
    const requestedAt = this.now();
    entry.ensureOfferAcceptable(requestedAt);
    const order = await this.journeyOrder.createOrder(entry);
    entry.linkJourneyOrder(requestedAt, order.orderId, order.amount);
    await this.repository.save(entry);
    return { orderId: order.orderId, amount: order.amount, seatAssignment: order.seatAssignment ?? null };
  }

  async queueInfo(segmentRef: string, departureDate: string, seatClass?: string, entryId?: string): Promise<QueueInfo> {
    const queue = await this.repository.queueFor(segmentRef, departureDate, seatClass);
    const queued = queue.filter((entry) => entry.status === "QUEUED");
    return { totalQueued: queued.length, myPosition: entryId ? queued.find((entry) => entry.entryId === entryId)?.queuePosition ?? null : null, estimatedPromotionRate: queued.length === 0 ? 1 : 1 / queued.length };
  }

  async handleCapacityFreed(event: WaitlistCapacityFreed, correlationId?: string) {
    return this.promotion.onCapacityFreed(event, this.journeyOrder, correlationId);
  }

  async recordCapacityFreed(event: WaitlistCapacityFreed): Promise<boolean> {
    return this.promotion.recordCapacityFreed(event);
  }

  async advanceNextPromotion(correlationId?: string): Promise<WaitlistEntrySnapshot | undefined> {
    return this.promotion.advanceNextPromotion(this.journeyOrder, correlationId);
  }

  async advanceNextWork(correlationId?: string): Promise<WaitlistEntrySnapshot | undefined> {
    return await this.fulfillment.advanceNext(correlationId) ?? this.advanceNextPromotion(correlationId);
  }

  async handleJourneyOrderConfirmed(orderId: string, correlationId?: string): Promise<WaitlistRequestResource | undefined> {
    const entry = await this.repository.findByJourneyOrderRef?.(orderId);
    if (!entry) return undefined;
    if (entry.status === "FULFILLED" || entry.status === "CLOSED") return toWaitlistRequestResource(await this.snapshot(entry));
    const now = this.now();
    entry.confirmJourneyOrder(orderId);
    const snapshot = await this.repository.save(entry);
    if (this.publisher) await publishAll(this.publisher, [waitlistFulfilled(snapshot, entry.loadedVersion, orderId, correlationId, now.toISOString())]);
    return toWaitlistRequestResource(snapshot);
  }

  async handleJourneyOrderCancelled(orderId: string, correlationId?: string): Promise<WaitlistRequestResource | undefined> {
    const snapshot = await this.fulfillment.handleOrderCancelled(orderId, correlationId);
    return snapshot ? toWaitlistRequestResource(snapshot) : undefined;
  }

  async expireDueOffers(correlationId?: string) {
    return this.promotion.expireDueOffers(correlationId);
  }

  async archiveTerminalRequests(): Promise<readonly WaitlistRequestResource[]> {
    const archived: WaitlistRequestResource[] = [];
    for (const entry of await this.repository.findArchivable()) {
      entry.close();
      const snapshot = await this.repository.save(entry);
      archived.push(toWaitlistRequestResource(snapshot));
    }
    return archived;
  }

  private async toCreateCommand(request: JoinWaitlistRequest, correlationId?: string): Promise<CreateWaitlistEntry> {
    const travelerRefs = request.travelerRef ? [request.travelerRef] : request.travelerRefs ?? [];
    const intent = await this.intentResolver.resolve({
      accountId: request.accountId,
      travelerRefs,
      segmentRef: request.segmentRef,
      itineraryRef: request.itineraryRef ?? request.segmentRef,
      paymentGuaranteeRef: request.paymentGuaranteeRef ?? "",
      departureDate: request.departureDate,
      deadline: request.deadline,
      seatClass: request.seatClass ?? request.travelClass,
      scheduledServiceRef: request.scheduledServiceRef,
      interval: request.interval,
      stopSequenceVersion: request.stopSequenceVersion,
    }, correlationId);
    return {
      accountId: request.accountId,
      travelerRefs: intent.travelerRefs,
      segmentRef: intent.segmentRef,
      scheduledServiceRef: intent.scheduledServiceRef,
      departureDate: intent.departureDate,
      seatClass: intent.seatClass,
      interval: intent.interval,
      stopSequenceVersion: intent.stopSequenceVersion,
      itineraryRef: request.itineraryRef ?? request.segmentRef,
      deadline: request.deadline,
      paymentGuaranteeRef: request.paymentGuaranteeRef,
      paymentGuarantee: intent.paymentGuarantee,
      intentFingerprint: request.intentFingerprint,
      priority: {
        loyaltyTier: request.loyaltyTier ?? "NONE",
        tripCount: request.tripCount ?? 0,
        daysBefore: request.daysBefore ?? daysBefore(intent.departureDate),
        groupSize: intent.travelerRefs.length,
        fareClass: intent.seatClass,
        specialStatus: request.specialStatus ?? "NONE",
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

function isActiveStatus(status: WaitlistEntrySnapshot["status"]): boolean {
  return status === "DRAFT" || status === "QUEUED" || status === "MATCHING" || status === "SUSPENDED";
}

function isArchivableStatus(status: WaitlistEntrySnapshot["status"]): boolean {
  return status === "FULFILLED" || status === "EXPIRED" || status === "CANCELLED";
}

function hasDeadlinePassed(entry: WaitlistEntry, now: Date): boolean {
  const deadline = new Date(entry.deadline).getTime();
  return Number.isFinite(deadline) && deadline <= now.getTime();
}

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

function objectBody(value: unknown): CancelWaitlistRequest {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new DomainError("VALIDATION_FAILED", "Request body must be a JSON object");
  }
  return value as CancelWaitlistRequest;
}

function requiredString(body: CancelWaitlistRequest, field: "reason"): string {
  const value = body[field];
  if (typeof value !== "string" || value.trim().length === 0) throw new DomainError("VALIDATION_FAILED", `${field} is required`);
  return value;
}

function daysBefore(departureDate: string): number {
  const departure = new Date(`${departureDate}T00:00:00.000Z`).getTime();
  if (!Number.isFinite(departure)) return 0;
  return Math.max(0, Math.ceil((departure - Date.now()) / (24 * 60 * 60 * 1000)));
}

function matchesCapacity(entry: WaitlistEntry, criteria: WaitlistMatchCriteria): boolean {
  return entry.segmentRef === criteria.segmentRef
    && entry.scheduledServiceRef === criteria.scheduledServiceRef
    && entry.departureDate === criteria.departureDate
    && entry.seatClass === criteria.seatClass
    && entry.stopSequenceVersion === criteria.stopSequenceVersion
    && criteria.interval.fromSeq <= entry.interval.fromSeq
    && criteria.interval.toSeq >= entry.interval.toSeq
    && entry.travelerRefs.length <= criteria.maxTravelers
    && (!criteria.intentFingerprint || entry.intentFingerprint === criteria.intentFingerprint);
}

function compareForPromotion(left: WaitlistEntry, right: WaitlistEntry): number {
  return right.priorityScore - left.priorityScore
    || left.createdAt.getTime() - right.createdAt.getTime()
    || left.entryId.localeCompare(right.entryId);
}

function moneyField(value: unknown): { currency: string; minorUnits: number } | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
  const money = value as Record<string, unknown>;
  const minorUnits = Number(money.minorUnits);
  return typeof money.currency === "string" && money.currency.trim() && Number.isSafeInteger(minorUnits) && minorUnits > 0
    ? { currency: money.currency, minorUnits } : undefined;
}

function sameCapacityCredit(left: WaitlistCapacityFreed, right: WaitlistCapacityFreed): boolean {
  return left.segmentRef === right.segmentRef
    && left.scheduledServiceRef === right.scheduledServiceRef
    && left.departureDate === right.departureDate
    && left.seatClass === right.seatClass
    && left.interval.fromSeq === right.interval.fromSeq
    && left.interval.toSeq === right.interval.toSeq
    && left.stopSequenceVersion === right.stopSequenceVersion
    && left.intentFingerprint === right.intentFingerprint
    && left.freedSlots === right.freedSlots;
}
