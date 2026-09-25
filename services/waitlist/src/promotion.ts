import { isPrefixedUuidV7, tracedFetch, type EventPublisher } from "@trainticket/ts-kit";
import { DomainError, normalizeFareClass, type FareClass, type TravelInterval, type WaitlistEntry, type WaitlistEntrySnapshot } from "./domain.js";
import { publishAll, waitlistExpired, waitlistMatchStarted } from "./publisher.js";

export type WaitlistCapacityFreed = Readonly<{
  segmentRef: string;
  scheduledServiceRef: string;
  departureDate: string;
  seatClass: FareClass;
  interval: TravelInterval;
  stopSequenceVersion: string;
  intentFingerprint?: string;
  freedSlots: number;
  capacityReleaseRef: string;
}>;

export type WaitlistMatchCriteria = Readonly<{
  segmentRef: string;
  scheduledServiceRef: string;
  departureDate: string;
  seatClass: FareClass;
  interval: TravelInterval;
  stopSequenceVersion: string;
  maxTravelers: number;
  intentFingerprint?: string;
}>;

export type WaitlistOffer = Readonly<{
  offerId: string;
  offerVersion: number;
  entryId: string;
  fareQuoteId: string;
  capacityHoldId?: string;
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
  findTopQueued(criteria: WaitlistMatchCriteria): Promise<WaitlistEntry | undefined> | WaitlistEntry | undefined;
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

export type JourneyOrderCreation = Readonly<{ orderId: string; seatAssignment?: unknown }>;

export interface JourneyOrderClient {
  createOrder(entry: WaitlistEntry): Promise<JourneyOrderCreation>;
}

export class HttpFarePricingClient implements FarePricingClient {
  constructor(
    private readonly baseUrl = process.env.FARE_PRICING_URL ?? process.env.FARE_PRICING_BASE_URL ?? "http://fare-pricing",
    private readonly channel = process.env.WAITLIST_CHANNEL ?? "WEB",
  ) {}

  async quote(entry: WaitlistEntry): Promise<{ fareQuoteId: string }> {
    const response = await tracedFetch(`${trimRight(this.baseUrl)}/api/v1/fare-quotes`, {
      method: "POST",
      headers: { "content-type": "application/json", "Idempotency-Key": entry.fareQuoteIdempotencyKey },
      body: JSON.stringify({
        travelerRefs: entry.travelerRefs,
        channel: this.channel,
        segmentRefs: [entry.segmentRef],
        seatClass: entry.seatClass,
        productCode: "rail-standard",
      }),
    });
    if (!response.ok) throw new Error(`Fare quote request failed with status ${response.status}`);
    const body = await response.json() as Record<string, unknown>;
    const fareQuoteId = stringField(body, "quoteId");
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
    const response = await tracedFetch(`${trimRight(this.baseUrl)}/api/v1/offers`, {
      method: "POST",
      headers: { "content-type": "application/json", "Idempotency-Key": entry.offerIdempotencyKey },
      body: JSON.stringify({
        accountId: entry.accountId,
        channelId: this.channelId,
        itineraryRef: entry.itineraryRef ?? entry.segmentRef,
        travelerRefs: entry.travelerRefs,
        fareQuoteRef: fareQuoteId,
      }),
    });
    if (!response.ok) throw new Error(`Offer creation request failed with status ${response.status}`);
    const body = await response.json() as Record<string, unknown>;
    const offerId = stringField(body, "offerId");
    const offerVersion = body.offerVersion;
    if (!offerId) throw new Error("Offer response did not include offerId");
    if (typeof offerVersion !== "number" || !Number.isSafeInteger(offerVersion) || offerVersion < 1) throw new Error("Offer response did not include a positive integer offerVersion");
    return { offerId, offerVersion };
  }
}

export class HttpCapacityAvailabilityClient implements CapacityAvailabilityClient {
  constructor(private readonly baseUrl = process.env.CAPACITY_AVAILABILITY_URL ?? process.env.CAPACITY_AVAILABILITY_BASE_URL ?? "http://capacity-availability") {}

  async hold(entry: WaitlistEntry, _fareQuoteId: string): Promise<{ capacityHoldId: string }> {
    const response = await tracedFetch(`${trimRight(this.baseUrl)}/api/v1/capacity-holds`, {
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
    const capacityHoldId = stringField(body, "holdId");
    if (!capacityHoldId) throw new Error("Capacity hold response did not include holdId");
    return { capacityHoldId };
  }

  async releaseHold(entry: WaitlistEntry, capacityHoldId: string): Promise<void> {
    await tracedFetch(`${trimRight(this.baseUrl)}/api/v1/capacity-holds/${encodeURIComponent(capacityHoldId)}/release`, {
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

  async onCapacityFreed(event: WaitlistCapacityFreed, journeyOrder: JourneyOrderClient, correlationId?: string): Promise<PromotionResult> {
    assertCapacityReleaseRef(event.capacityReleaseRef);
    const promoted: PromotedWaitlistRequest[] = [];
    const offers: WaitlistOffer[] = [];
    let remainingSlots = Math.max(0, Math.floor(event.freedSlots));
    while (remainingSlots > 0) {
      const entry = await this.repository.findTopQueued({
        segmentRef: event.segmentRef,
        scheduledServiceRef: event.scheduledServiceRef,
        departureDate: event.departureDate,
        seatClass: normalizeFareClass(event.seatClass),
        interval: event.interval,
        stopSequenceVersion: event.stopSequenceVersion,
        maxTravelers: remainingSlots,
        intentFingerprint: event.intentFingerprint,
      });
      if (!entry) break;
      if (new Date(entry.deadline) <= this.now()) {
        entry.expire();
        const expired = await this.repository.save(entry);
        await publishAll(this.publisher, [waitlistExpired(expired, entry.loadedVersion, correlationId, this.now().toISOString())]);
        continue;
      }
      const quote = await this.farePricing.quote(entry);
      const commercialOffer = await this.offerManagement.createOffer(entry, quote.fareQuoteId);
      const now = this.now();
      const expiresAt = new Date(now.getTime() + 15 * 60 * 1000);
      const offer = { offerId: commercialOffer.offerId, offerVersion: commercialOffer.offerVersion, entryId: entry.entryId, fareQuoteId: quote.fareQuoteId, expiresAt: expiresAt.toISOString() };
      entry.offer(offer.offerId, offer.offerVersion, quote.fareQuoteId, undefined, now, expiresAt);
      let snapshot = await this.repository.save(entry);
      await publishAll(this.publisher, [waitlistMatchStarted(snapshot, entry.loadedVersion, event.capacityReleaseRef, correlationId, now.toISOString())]);

      const order = await journeyOrder.createOrder(entry);
      entry.linkJourneyOrder(this.now(), order.orderId);
      snapshot = await this.repository.save(entry);
      promoted.push({ ...snapshot, waitlistRequestId: snapshot.entryId });
      offers.push(offer);
      remainingSlots -= entry.travelerRefs.length;
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
  if (!entry.offerId || !entry.offerVersion || !entry.fareQuoteId || !entry.offerExpiresAt) {
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
function stringField(body: Record<string, unknown>, field: string): string | undefined {
  const value = body[field];
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}
