import { type EventPublisher } from "@trainticket/ts-kit";
import { DomainError, WaitlistEntry, WaitlistQueue, type CreateWaitlistEntry, type FareClass, type PriorityInput, type WaitlistEntrySnapshot } from "./domain.js";
import { HttpCapacityAvailabilityClient, HttpFarePricingClient, PromotionOrchestrator, type CapacityAvailabilityClient, type FarePricingClient, type WaitlistCapacityFreed, type WaitlistRepository } from "./promotion.js";
import { publishAll, waitlistEntryAccepted, waitlistEntryCreated } from "./publisher.js";

export type JoinWaitlistRequest = Readonly<{
  accountId: string;
  travelerRefs: readonly string[];
  segmentRef: string;
  departureDate: string;
  seatClass: FareClass;
  loyaltyTier?: PriorityInput["loyaltyTier"];
  tripCount?: number;
  daysBefore?: number;
  specialStatus?: PriorityInput["specialStatus"];
}>;

export type AcceptPromotionRequest = Readonly<{ paymentMethodRef?: string }>;
export type AcceptPromotionResponse = Readonly<{ orderId: string; seatAssignment: unknown }>;
export type QueueInfo = Readonly<{ totalQueued: number; myPosition: number | null; estimatedPromotionRate: number }>;

export interface JourneyOrderClient {
  createOrder(entry: WaitlistEntry, paymentMethodRef?: string): Promise<AcceptPromotionResponse>;
}

export class HttpJourneyOrderClient implements JourneyOrderClient {
  constructor(private readonly baseUrl = process.env.JOURNEY_ORDER_URL ?? process.env.JOURNEY_ORDER_BASE_URL ?? "http://journey-order") {}

  async createOrder(entry: WaitlistEntry, paymentMethodRef?: string): Promise<AcceptPromotionResponse> {
    const response = await fetch(`${this.baseUrl.replace(/\/+$/u, "")}/api/v1/journey-orders`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        source: "WAITLIST",
        entryId: entry.entryId,
        accountId: entry.accountId,
        travelerRefs: entry.travelerRefs,
        segmentRef: entry.segmentRef,
        departureDate: entry.departureDate,
        seatClass: entry.seatClass,
        fareQuoteId: entry.fareQuoteId,
        capacityHoldId: entry.capacityHoldId,
        paymentMethodRef,
      }),
    });
    if (!response.ok) throw new Error(`Journey order request failed with status ${response.status}`);
    const body = await response.json() as Record<string, unknown>;
    return { orderId: String(body.orderId ?? body.id ?? `ord-${entry.entryId}`), seatAssignment: body.seatAssignment ?? null };
  }
}

export class InMemoryWaitlistRepository implements WaitlistRepository {
  private readonly entries = new Map<string, WaitlistEntry>();

  async add(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    this.entries.set(entry.entryId, entry);
    return this.snapshot(entry);
  }

  async get(entryId: string): Promise<WaitlistEntry | undefined> { return this.entries.get(entryId); }

  async save(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    this.entries.set(entry.entryId, entry);
    return this.snapshot(entry);
  }

  async findTopQueued(segmentRef: string, departureDate: string, seatClass?: string): Promise<WaitlistEntry | undefined> {
    const queue = this.buildQueue(segmentRef, departureDate, seatClass);
    return queue.nextQueued();
  }

  async findExpiredOffers(now: Date): Promise<readonly WaitlistEntry[]> {
    return [...this.entries.values()].filter((entry) => entry.status === "OFFERED" && entry.offerExpiresAt !== null && entry.offerExpiresAt <= now);
  }

  async queueFor(segmentRef: string, departureDate: string, seatClass?: string): Promise<readonly WaitlistEntrySnapshot[]> {
    const queue = this.buildQueue(segmentRef, departureDate, seatClass);
    return queue.allEntries().map((entry) => this.snapshot(entry));
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
  ) {
    this.promotion = new PromotionOrchestrator(repository, farePricing, capacityAvailability, publisher ?? { publish: async () => undefined }, now);
  }

  async join(request: JoinWaitlistRequest, correlationId?: string): Promise<WaitlistEntrySnapshot & { estimatedWaitMinutes: number }> {
    const entry = WaitlistEntry.create(this.toCreateCommand(request));
    const snapshot = await this.repository.add(entry);
    if (this.publisher) await publishAll(this.publisher, [waitlistEntryCreated(snapshot, correlationId)]);
    return { ...snapshot, estimatedWaitMinutes: Math.max(5, snapshot.queuePosition * 15) };
  }

  async get(entryId: string): Promise<WaitlistEntrySnapshot> {
    const entry = await this.requireEntry(entryId);
    return this.snapshot(entry);
  }

  async cancel(entryId: string): Promise<{ cancelled: true }> {
    const entry = await this.requireEntry(entryId);
    entry.cancel();
    await this.repository.save(entry);
    return { cancelled: true };
  }

  async accept(entryId: string, request: AcceptPromotionRequest = {}, correlationId?: string): Promise<AcceptPromotionResponse> {
    const entry = await this.requireEntry(entryId);
    entry.accept(this.now());
    const order = await this.journeyOrder.createOrder(entry, request.paymentMethodRef);
    const snapshot = await this.repository.save(entry);
    if (this.publisher) await publishAll(this.publisher, [waitlistEntryAccepted(snapshot, order.orderId, order.seatAssignment, correlationId)]);
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

  async expireDueOffers(correlationId?: string) {
    return this.promotion.expireDueOffers(correlationId);
  }

  private toCreateCommand(request: JoinWaitlistRequest): CreateWaitlistEntry {
    return {
      accountId: request.accountId,
      travelerRefs: request.travelerRefs,
      segmentRef: request.segmentRef,
      departureDate: request.departureDate,
      seatClass: request.seatClass,
      priority: {
        loyaltyTier: request.loyaltyTier ?? "NONE",
        tripCount: request.tripCount ?? 0,
        daysBefore: request.daysBefore ?? daysBefore(request.departureDate),
        groupSize: request.travelerRefs.length,
        fareClass: request.seatClass,
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

function daysBefore(departureDate: string): number {
  const departure = new Date(`${departureDate}T00:00:00.000Z`).getTime();
  if (!Number.isFinite(departure)) return 0;
  return Math.max(0, Math.ceil((departure - Date.now()) / (24 * 60 * 60 * 1000)));
}
