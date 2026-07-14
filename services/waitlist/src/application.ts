import { type EventPublisher } from "@trainticket/ts-kit";
import { DomainError, WaitlistEntry, WaitlistQueue, type CreateWaitlistEntry, type FareClass, type PriorityInput, type WaitlistEntrySnapshot } from "./domain.js";
import { HttpCapacityAvailabilityClient, HttpFarePricingClient, HttpOfferManagementClient, PromotionOrchestrator, type CapacityAvailabilityClient, type FarePricingClient, type OfferManagementClient, type WaitlistCapacityFreed, type WaitlistRepository } from "./promotion.js";
import { publishAll, waitlistEntryAccepted, waitlistEntryCreated } from "./publisher.js";

export type JoinWaitlistRequest = Readonly<{
  accountId: string;
  travelerRef?: string;
  travelerRefs?: readonly string[];
  segmentRef: string;
  travelClass?: FareClass;
  seatClass?: FareClass;
  deadline: string;
  paymentGuaranteeRef: string;
  intentFingerprint: string;
  loyaltyTier?: PriorityInput["loyaltyTier"];
  tripCount?: number;
  daysBefore?: number;
  specialStatus?: PriorityInput["specialStatus"];
  itineraryRef: string;
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

export type AcceptPromotionRequest = Readonly<{ paymentMethodRef?: string }>;
export type AcceptPromotionResponse = Readonly<{ orderId: string; seatAssignment: unknown }>;
export type QueueInfo = Readonly<{ totalQueued: number; myPosition: number | null; estimatedPromotionRate: number }>;

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
    this.entries.set(entry.entryId, entry);
    return this.snapshot(entry);
  }

  async get(entryId: string): Promise<WaitlistEntry | undefined> { return this.entries.get(entryId); }

  async save(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    entry.markPersisted(entry.version + 1);
    this.entries.set(entry.entryId, entry);
    return this.snapshot(entry);
  }

  async findTopQueued(segmentRef: string, departureDate: string, seatClass?: string): Promise<WaitlistEntry | undefined> {
    const queue = this.buildQueue(segmentRef, departureDate, seatClass);
    return queue.nextQueued();
  }

  async findExpiredOffers(now: Date): Promise<readonly WaitlistEntry[]> {
    return [...this.entries.values()].filter((entry) => entry.status !== "CLOSED" && ((entry.status === "MATCHING" && entry.offerExpiresAt !== null && entry.offerExpiresAt <= now) || ((entry.status === "QUEUED" || entry.status === "MATCHING") && entry.deadline <= now)));
  }

  async findArchivable(): Promise<readonly WaitlistEntry[]> {
    return [...this.entries.values()].filter((entry) => entry.status === "FULFILLED" || entry.status === "EXPIRED" || entry.status === "CANCELLED");
  }

  async queueFor(segmentRef: string, departureDate: string, seatClass?: string): Promise<readonly WaitlistEntrySnapshot[]> {
    const queue = this.buildQueue(segmentRef, departureDate, seatClass);
    return queue.allEntries().map((entry) => this.snapshot(entry));
  }

  clear(): void { this.entries.clear(); }

  private buildQueue(segmentRef: string, departureDate: string, seatClass?: string): WaitlistQueue {
    const matching = [...this.entries.values()].filter((entry) => entry.status !== "CLOSED" && entry.segmentRef === segmentRef && entry.departureDate === departureDate && (!seatClass || entry.seatClass === seatClass));
    const classForQueue = (seatClass ?? matching[0]?.seatClass ?? "SECOND") as FareClass;
    return new WaitlistQueue(segmentRef, departureDate, classForQueue, matching.filter((entry) => entry.seatClass === classForQueue));
  }

  private snapshot(entry: WaitlistEntry): WaitlistEntrySnapshot { return entry.toSnapshot(this.position(entry)); }
  private position(entry: WaitlistEntry): number { return entry.status === "CLOSED" ? 0 : this.buildQueue(entry.segmentRef, entry.departureDate, entry.seatClass).positionOf(entry.entryId); }

  private ensureNoActiveDuplicate(entry: WaitlistEntry): void {
    const duplicate = [...this.entries.values()].find((candidate) => candidate.travelerRefs[0] === entry.travelerRefs[0] && candidate.intentFingerprint === entry.intentFingerprint && ["DRAFT", "QUEUED", "MATCHING", "SUSPENDED"].includes(candidate.status));
    if (duplicate) throw new DomainError("CONFLICT", "Active waitlist request already exists for traveler and intentFingerprint");
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
    if (this.publisher) await publishAll(this.publisher, [waitlistEntryCreated(snapshot, correlationId)]);
    return toWaitlistRequest(snapshot);
  }

  async get(entryId: string): Promise<WaitlistRequestResource> {
    const entry = await this.requireEntry(entryId);
    return toWaitlistRequest(await this.snapshot(entry));
  }

  async cancel(entryId: string): Promise<{ waitlistRequestId: string; status: "CANCELLED"; cancelledAt: string }> {
    const entry = await this.requireEntry(entryId);
    const cancelledAt = this.now();
    entry.cancel(cancelledAt);
    const snapshot = await this.repository.save(entry);
    return { waitlistRequestId: snapshot.entryId, status: "CANCELLED", cancelledAt: snapshot.cancelledAt ?? cancelledAt.toISOString() };
  }

  async accept(entryId: string, request: AcceptPromotionRequest = {}, correlationId?: string): Promise<AcceptPromotionResponse> {
    const entry = await this.requireEntry(entryId);
    const now = this.now();
    entry.ensureOfferAcceptable(now);
    const order = await this.journeyOrder.createOrder(entry, request.paymentMethodRef);
    entry.accept(now, order.orderId);
    const snapshot = await this.repository.save(entry);
    if (this.publisher) await publishAll(this.publisher, [waitlistEntryAccepted(snapshot, order.orderId, order.seatAssignment, correlationId)]);
    return order;
  }

  async queueInfo(segmentRef: string, departureDate: string, seatClass?: string, entryId?: string): Promise<QueueInfo> {
    const queue = await this.repository.queueFor(segmentRef, departureDate, seatClass);
    const queued = queue.filter((entry) => entry.status === "QUEUED");
    return { totalQueued: queued.length, myPosition: entryId ? queued.find((entry) => entry.entryId === entryId)?.queuePosition ?? null : null, estimatedPromotionRate: queued.length === 0 ? 1 : 1 / queued.length };
  }

  async handleCapacityFreed(event: WaitlistCapacityFreed, correlationId?: string) { return this.promotion.onCapacityFreed(event, correlationId); }
  async expireDueOffers(correlationId?: string) { return this.promotion.expireDueOffers(correlationId); }

  async archiveTerminalRequests(correlationId?: string): Promise<readonly WaitlistRequestResource[]> {
    const archived: WaitlistRequestResource[] = [];
    for (const entry of await this.repository.findArchivable()) {
      entry.close(this.now());
      const snapshot = await this.repository.save(entry);
      archived.push(toWaitlistRequest(snapshot));
    }
    return archived;
  }

  private toCreateCommand(request: JoinWaitlistRequest): CreateWaitlistEntry {
    const travelerRefs = request.travelerRefs ?? (request.travelerRef ? [request.travelerRef] : []);
    const seatClass = request.seatClass ?? request.travelClass ?? "SECOND";
    return {
      accountId: request.accountId,
      travelerRefs,
      segmentRef: request.segmentRef,
      departureDate: datePart(request.deadline),
      seatClass,
      itineraryRef: request.itineraryRef,
      deadline: new Date(request.deadline),
      paymentGuaranteeRef: request.paymentGuaranteeRef,
      intentFingerprint: request.intentFingerprint,
      priority: {
        loyaltyTier: request.loyaltyTier ?? "NONE",
        tripCount: request.tripCount ?? 0,
        daysBefore: request.daysBefore ?? daysBefore(datePart(request.deadline)),
        groupSize: travelerRefs.length,
        fareClass: seatClass,
        specialStatus: request.specialStatus ?? "NONE",
      },
    };
  }

  private async requireEntry(entryId: string): Promise<WaitlistEntry> {
    const entry = await this.repository.get(entryId);
    if (!entry) throw new DomainError("NOT_FOUND", "Waitlist request was not found");
    return entry;
  }

  private async snapshot(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    if (entry.status === "CLOSED") return entry.toSnapshot(0);
    const queue = await this.repository.queueFor(entry.segmentRef, entry.departureDate, entry.seatClass);
    return queue.find((candidate) => candidate.entryId === entry.entryId) ?? entry.toSnapshot(0);
  }
}

export function toWaitlistRequest(snapshot: WaitlistEntrySnapshot): WaitlistRequestResource {
  return {
    waitlistRequestId: snapshot.entryId,
    accountId: snapshot.accountId,
    travelerRef: snapshot.travelerRefs[0] ?? "",
    segmentRef: snapshot.segmentRef,
    travelClass: snapshot.seatClass,
    deadline: snapshot.deadline,
    paymentGuaranteeRef: snapshot.paymentGuaranteeRef,
    itineraryRef: snapshot.itineraryRef,
    intentFingerprint: snapshot.intentFingerprint,
    status: snapshot.status,
    ...(snapshot.journeyOrderRef ? { journeyOrderRef: snapshot.journeyOrderRef } : {}),
  };
}

function daysBefore(departureDate: string): number {
  const departure = new Date(`${departureDate}T00:00:00.000Z`).getTime();
  if (!Number.isFinite(departure)) return 0;
  return Math.max(0, Math.ceil((departure - Date.now()) / (24 * 60 * 60 * 1000)));
}
function datePart(value: string): string { return value.slice(0, 10); }
