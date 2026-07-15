import { isPrefixedUuidV7, type EventPublisher } from "@trainticket/ts-kit";
import { DomainError, type WaitlistEntry, type WaitlistEntrySnapshot } from "./domain.js";
import { publishAll, waitlistExpired, waitlistHoldAuthorized, waitlistMatchStarted } from "./publisher.js";

export type WaitlistCapacityFreed = Readonly<{
  segmentRef: string;
  departureDate: string;
  seatClass?: string;
  freedSlots: number;
  capacityReleaseRef: string;
}>;

export type WaitlistOffer = Readonly<{
  offerId: string;
  offerVersion: number;
  entryId: string;
  fareQuoteId: string;
  capacityHoldId: string;
  expiresAt: string;
}>;

export type PromotedWaitlistRequest = WaitlistEntrySnapshot & Readonly<{ waitlistRequestId: string }>;

export type PromotionResult = Readonly<{
  promoted: readonly PromotedWaitlistRequest[];
  offers: readonly WaitlistOffer[];
}>;

export interface WaitlistRepository {
  add(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> | WaitlistEntrySnapshot;
  get(entryId: string): Promise<WaitlistEntry | undefined> | WaitlistEntry | undefined;
  save(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> | WaitlistEntrySnapshot;
  findTopQueued(segmentRef: string, departureDate: string, seatClass?: string): Promise<WaitlistEntry | undefined> | WaitlistEntry | undefined;
  findByJourneyOrderRef?(journeyOrderRef: string): Promise<WaitlistEntry | undefined> | WaitlistEntry | undefined;
  findExpiredOffers(now: Date): Promise<readonly WaitlistEntry[]> | readonly WaitlistEntry[];
  findArchivable(): Promise<readonly WaitlistEntry[]> | readonly WaitlistEntry[];
  queueFor(segmentRef: string, departureDate: string, seatClass?: string): Promise<readonly WaitlistEntrySnapshot[]> | readonly WaitlistEntrySnapshot[];
  listByTraveler(travelerRef: string): Promise<readonly WaitlistEntrySnapshot[]> | readonly WaitlistEntrySnapshot[];
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
        itineraryRef: entry.itineraryRef ?? entry.segmentRef,
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
        travelerRef: entry.travelerRefs[0],
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
    private readonly offerManagement: OfferManagementClient = new HttpOfferManagementClient(),
    private readonly now: () => Date = () => new Date(),
  ) {}

  async onCapacityFreed(event: WaitlistCapacityFreed, correlationId?: string): Promise<PromotionResult> {
    const promoted: PromotedWaitlistRequest[] = [];
    const offers: WaitlistOffer[] = [];
    const slots = Math.max(0, Math.floor(event.freedSlots));
    for (let index = 0; index < slots; index++) {
      const entry = await this.repository.findTopQueued(event.segmentRef, event.departureDate, event.seatClass);
      if (!entry) break;
      assertCapacityReleaseRef(event.capacityReleaseRef);
      const quote = await this.farePricing.quote(entry);
      const commercialOffer = await this.offerManagement.createOffer(entry, quote.fareQuoteId);
      const hold = await this.capacityAvailability.hold(entry, quote.fareQuoteId);
      const now = this.now();
      const expiresAt = new Date(now.getTime() + 15 * 60 * 1000);
      const offer = { offerId: commercialOffer.offerId, offerVersion: commercialOffer.offerVersion, entryId: entry.entryId, fareQuoteId: quote.fareQuoteId, capacityHoldId: hold.capacityHoldId, expiresAt: expiresAt.toISOString() };
      entry.offer(offer.offerId, offer.offerVersion, quote.fareQuoteId, hold.capacityHoldId, now, expiresAt);
      const snapshot = await this.repository.save(entry);
      promoted.push({ ...snapshot, waitlistRequestId: snapshot.entryId });
      offers.push(offer);
      await publishAll(this.publisher, [
        waitlistHoldAuthorized(snapshot, entry.loadedVersion, correlationId, now.toISOString()),
        waitlistMatchStarted(snapshot, entry.loadedVersion, event.capacityReleaseRef, correlationId, now.toISOString()),
      ]);
    }
    return { promoted, offers };
  }

  async expireDueOffers(correlationId?: string): Promise<readonly WaitlistEntrySnapshot[]> {
    const now = this.now();
    const expired: WaitlistEntrySnapshot[] = [];
    for (const entry of await this.repository.findExpiredOffers(now)) {
      const capacityHoldId = entry.capacityHoldId;
      entry.expire();
      if (capacityHoldId) await this.capacityAvailability.releaseHold(entry, capacityHoldId);
      const snapshot = await this.repository.save(entry);
      expired.push(snapshot);
      await publishAll(this.publisher, [waitlistExpired(snapshot, entry.loadedVersion, correlationId, now.toISOString())]);
    }
    return expired;
  }
}

export function offerFromEntry(entry: WaitlistEntry): WaitlistOffer {
  if (!entry.offerId || !entry.offerVersion || !entry.fareQuoteId || !entry.capacityHoldId || !entry.offerExpiresAt) {
    throw new DomainError("PRECONDITION_FAILED", "Waitlist entry does not have an active offer");
  }
  return { offerId: entry.offerId, offerVersion: entry.offerVersion, entryId: entry.entryId, fareQuoteId: entry.fareQuoteId, capacityHoldId: entry.capacityHoldId, expiresAt: entry.offerExpiresAt.toISOString() };
}

function assertCapacityReleaseRef(capacityReleaseRef: string): void {
  if (!isPrefixedUuidV7(capacityReleaseRef, ["evt"])) {
    throw new DomainError("VALIDATION_FAILED", "capacityReleaseRef must be the consumed CapacityReleased eventId");
  }
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
