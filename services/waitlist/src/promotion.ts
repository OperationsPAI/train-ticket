import { type EventPublisher } from "@trainticket/ts-kit";
import { DomainError, type WaitlistEntry, type WaitlistEntrySnapshot } from "./domain.js";
import { publishAll, waitlistHoldAuthorized, waitlistMatchStarted } from "./publisher.js";

export type WaitlistCapacityFreed = Readonly<{
  eventId: string;
  segmentRef: string;
  departureDate: string;
  seatClass?: string;
  freedSlots: number;
}>;

export type WaitlistOffer = Readonly<{
  offerId: string;
  offerVersion: number;
  entryId: string;
  fareQuoteId: string;
  capacityHoldId: string;
  expiresAt?: string;
}>;

export type PromotionResult = Readonly<{
  promoted: readonly WaitlistEntrySnapshot[];
  offers: readonly WaitlistOffer[];
}>;

export interface WaitlistRepository {
  add(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> | WaitlistEntrySnapshot;
  get(entryId: string): Promise<WaitlistEntry | undefined> | WaitlistEntry | undefined;
  save(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> | WaitlistEntrySnapshot;
  findTopQueued(segmentRef: string, departureDate: string, seatClass?: string): Promise<WaitlistEntry | undefined> | WaitlistEntry | undefined;
  findExpired(now: Date): Promise<readonly WaitlistEntry[]> | readonly WaitlistEntry[];
  findArchivable(): Promise<readonly WaitlistEntry[]> | readonly WaitlistEntry[];
  queueFor(segmentRef: string, departureDate: string, seatClass?: string): Promise<readonly WaitlistEntrySnapshot[]> | readonly WaitlistEntrySnapshot[];
  listByTraveler?(travelerRef: string, options: { status?: string; limit: number; offset: number }): Promise<{ items: readonly WaitlistEntrySnapshot[]; total: number }> | { items: readonly WaitlistEntrySnapshot[]; total: number };
  findByJourneyOrderRef?(journeyOrderRef: string): Promise<WaitlistEntry | undefined> | WaitlistEntry | undefined;
}

export interface JourneyOrderCreationClient {
  createOrder(entry: WaitlistEntry): Promise<{ orderId: string; seatAssignment: unknown }>;
}

export interface FarePricingClient {
  quote(entry: WaitlistEntry): Promise<{ fareQuoteId: string }>;
}

export interface OfferManagementClient {
  createOffer(entry: WaitlistEntry, fareQuoteId: string): Promise<{ offerId: string; offerVersion: number }>;
}

export interface CapacityAvailabilityClient {
  hold(entry: WaitlistEntry, fareQuoteId: string): Promise<{ capacityHoldId: string }>;
  releaseHold(entry: WaitlistEntry, capacityHoldId: string): Promise<void>;
}

export class HttpFarePricingClient implements FarePricingClient {
  constructor(
    private readonly baseUrl = process.env.FARE_PRICING_URL ?? process.env.FARE_PRICING_BASE_URL ?? "http://fare-pricing",
    private readonly channel = process.env.WAITLIST_CHANNEL ?? "WEB",
  ) {}

  async quote(entry: WaitlistEntry): Promise<{ fareQuoteId: string }> {
    const response = await fetch(`${trimRight(this.baseUrl)}/api/v1/fare-quotes`, {
      method: "POST",
      headers: { "content-type": "application/json", "Idempotency-Key": entry.fareQuoteIdempotencyKey },
      body: JSON.stringify({
        travelerRefs: entry.travelerRefs,
        channel: this.channel,
        segmentRefs: [entry.segmentRef],
        productCode: "rail-standard",
      }),
    });
    if (!response.ok) throw new Error(`Fare quote request failed with status ${response.status}`);
    const body = await response.json() as Record<string, unknown>;
    const fareQuoteId = stringField(body, "quoteId") ?? stringField(body, "fareQuoteId") ?? stringField(body, "id");
    if (!fareQuoteId) throw new Error("Fare quote response did not include quoteId");
    return { fareQuoteId };
  }
}

export class HttpOfferManagementClient implements OfferManagementClient {
  constructor(
    private readonly baseUrl = process.env.OFFER_MANAGEMENT_URL ?? process.env.OFFER_MANAGEMENT_BASE_URL ?? "http://offer-management",
    private readonly channelId = process.env.WAITLIST_CHANNEL_ID ?? process.env.WAITLIST_CHANNEL ?? "WEB",
  ) {}

  async createOffer(entry: WaitlistEntry, fareQuoteId: string): Promise<{ offerId: string; offerVersion: number }> {
    const response = await fetch(`${trimRight(this.baseUrl)}/api/v1/offers`, {
      method: "POST",
      headers: { "content-type": "application/json", "Idempotency-Key": entry.offerIdempotencyKey },
      body: JSON.stringify({
        accountId: entry.accountId,
        channelId: this.channelId,
        itineraryRef: entry.itineraryRef,
        travelerRefs: entry.travelerRefs,
        quoteRequestId: fareQuoteId,
      }),
    });
    if (!response.ok) throw new Error(`Offer creation request failed with status ${response.status}`);
    const body = await response.json() as Record<string, unknown>;
    const offerId = stringField(body, "offerId") ?? stringField(body, "id");
    const offerVersion = numberField(body, "offerVersion") ?? numberField(recordField(body, "downstreamReference"), "offerVersion");
    if (!offerId) throw new Error("Offer response did not include offerId");
    if (!offerVersion || offerVersion < 1) throw new Error("Offer response did not include offerVersion");
    return { offerId, offerVersion };
  }
}

export class HttpCapacityAvailabilityClient implements CapacityAvailabilityClient {
  constructor(private readonly baseUrl = process.env.CAPACITY_AVAILABILITY_URL ?? process.env.CAPACITY_AVAILABILITY_BASE_URL ?? "http://capacity-availability") {}

  async hold(entry: WaitlistEntry, _fareQuoteId: string): Promise<{ capacityHoldId: string }> {
    const response = await fetch(`${trimRight(this.baseUrl)}/api/v1/capacity-holds`, {
      method: "POST",
      headers: { "content-type": "application/json", "Idempotency-Key": entry.capacityHoldIdempotencyKey },
      body: JSON.stringify({
        segmentRef: entry.segmentRef,
        travelerRef: entry.travelerRef,
        classRef: entry.seatClass,
        quantity: entry.travelerRefs.length,
        segmentBookingId: entry.capacitySegmentBookingId,
      }),
    });
    if (!response.ok) throw new Error(`Capacity hold request failed with status ${response.status}`);
    const body = await response.json() as Record<string, unknown>;
    const capacityHoldId = stringField(body, "holdId") ?? stringField(body, "capacityHoldId") ?? stringField(body, "id");
    if (!capacityHoldId) throw new Error("Capacity hold response did not include holdId");
    return { capacityHoldId };
  }

  async releaseHold(entry: WaitlistEntry, capacityHoldId: string): Promise<void> {
    await fetch(`${trimRight(this.baseUrl)}/api/v1/capacity-holds/${encodeURIComponent(capacityHoldId)}/release`, {
      method: "POST",
      headers: { "content-type": "application/json", "Idempotency-Key": entry.capacityReleaseIdempotencyKey },
    });
  }
}

export class PromotionOrchestrator {
  constructor(
    private readonly repository: WaitlistRepository,
    private readonly farePricing: FarePricingClient,
    private readonly capacityAvailability: CapacityAvailabilityClient,
    private readonly publisher: EventPublisher,
    private readonly journeyOrder: JourneyOrderCreationClient,
    private readonly offerManagement: OfferManagementClient = new HttpOfferManagementClient(),
    private readonly now: () => Date = () => new Date(),
  ) {}

  async onCapacityFreed(event: WaitlistCapacityFreed, correlationId?: string): Promise<PromotionResult> {
    const promoted: WaitlistEntrySnapshot[] = [];
    const offers: WaitlistOffer[] = [];
    const slots = Math.max(0, Math.floor(event.freedSlots));
    for (let index = 0; index < slots; index++) {
      const entry = await this.repository.findTopQueued(event.segmentRef, event.departureDate, event.seatClass);
      if (!entry) break;
      const startedAt = this.now();
      entry.startMatching(startedAt, event.eventId);
      let snapshot = await this.repository.save(entry);
      await publishAll(this.publisher, [waitlistMatchStarted(snapshot, event.eventId, startedAt, correlationId)]);
      const quote = await this.farePricing.quote(entry);
      const commercialOffer = await this.offerManagement.createOffer(entry, quote.fareQuoteId);
      const hold = await this.capacityAvailability.hold(entry, quote.fareQuoteId);
      entry.recordHold(commercialOffer.offerId, commercialOffer.offerVersion, quote.fareQuoteId, hold.capacityHoldId);
      snapshot = await this.repository.save(entry);
      const order = await this.journeyOrder.createOrder(entry);
      entry.recordJourneyOrderRef(order.orderId);
      snapshot = await this.repository.save(entry);
      const offer = {
        offerId: commercialOffer.offerId,
        offerVersion: commercialOffer.offerVersion,
        entryId: entry.entryId,
        fareQuoteId: quote.fareQuoteId,
        capacityHoldId: hold.capacityHoldId,
      };
      promoted.push(snapshot);
      offers.push(offer);
      await publishAll(this.publisher, [waitlistHoldAuthorized(snapshot, this.now(), correlationId)]);
    }
    return { promoted, offers };
  }
}

export function offerFromEntry(entry: WaitlistEntry): WaitlistOffer {
  if (!entry.offerId || !entry.offerVersion || !entry.fareQuoteId || !entry.capacityHoldId) {
    throw new DomainError("PRECONDITION_FAILED", "Waitlist entry does not have an active offer");
  }
  return { offerId: entry.offerId, offerVersion: entry.offerVersion, entryId: entry.entryId, fareQuoteId: entry.fareQuoteId, capacityHoldId: entry.capacityHoldId };
}

function trimRight(value: string): string { return value.replace(/\/+$/u, ""); }
function stringField(body: Record<string, unknown>, field: string): string | undefined { return typeof body[field] === "string" ? body[field] as string : undefined; }
function numberField(body: Record<string, unknown>, field: string): number | undefined {
  if (typeof body[field] === "number") return body[field] as number;
  if (typeof body[field] === "string") {
    const parsed = Number.parseInt(body[field] as string, 10);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
  return undefined;
}
function recordField(body: Record<string, unknown>, field: string): Record<string, unknown> { return isRecord(body[field]) ? body[field] as Record<string, unknown> : {}; }
function isRecord(value: unknown): value is Record<string, unknown> { return typeof value === "object" && value !== null && !Array.isArray(value); }
