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
  recordCapacityCredit(event: WaitlistCapacityFreed): Promise<boolean> | boolean;
  claimNextPromotion(now: Date): Promise<WaitlistEntry | undefined> | WaitlistEntry | undefined;
  findRecoverablePromotion(): Promise<WaitlistEntry | undefined> | WaitlistEntry | undefined;
  restoreCapacityCredits(entry: WaitlistEntry): Promise<void> | void;
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
  createOffer(entry: WaitlistEntry, fareQuoteId: string): Promise<{ offerId: string; offerVersion: number; expiresAt: string }>;
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

  async createOffer(entry: WaitlistEntry, fareQuoteId: string): Promise<{ offerId: string; offerVersion: number; expiresAt: string }> {
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
    const expiresAt = stringField(body, "expiresAt");
    if (!offerId) throw new Error("Offer response did not include offerId");
    if (typeof offerVersion !== "number" || !Number.isSafeInteger(offerVersion) || offerVersion < 1) throw new Error("Offer response did not include a positive integer offerVersion");
    if (!expiresAt || !Number.isFinite(new Date(expiresAt).getTime())) throw new Error("Offer response did not include a valid expiresAt");
    return { offerId, offerVersion, expiresAt };
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
    await this.recordCapacityFreed(event);
    const promoted: PromotedWaitlistRequest[] = [];
    const offers: WaitlistOffer[] = [];
    for (let steps = 0; steps < 1_000; steps++) {
      const result = await this.advanceNextPromotion(journeyOrder, correlationId);
      if (!result) break;
      if (result.journeyOrderRef && !promoted.some((entry) => entry.entryId === result.entryId)) {
        promoted.push({ ...result, waitlistRequestId: result.entryId });
      }
      if (result.offerId && result.offerVersion && result.fareQuoteId && result.offerExpiresAt
          && !offers.some((offer) => offer.entryId === result.entryId)) {
        offers.push({ offerId: result.offerId, offerVersion: result.offerVersion, entryId: result.entryId, fareQuoteId: result.fareQuoteId, expiresAt: result.offerExpiresAt });
      }
    }
    return { promoted, offers };
  }

  async recordCapacityFreed(event: WaitlistCapacityFreed): Promise<boolean> {
    assertCapacityReleaseRef(event.capacityReleaseRef);
    return this.repository.recordCapacityCredit({ ...event, seatClass: normalizeFareClass(event.seatClass) });
  }

  async advanceNextPromotion(journeyOrder: JourneyOrderClient, correlationId?: string): Promise<WaitlistEntrySnapshot | undefined> {
    let entry = await this.repository.findRecoverablePromotion();
    if (!entry) {
      entry = await this.repository.claimNextPromotion(this.now());
      if (!entry) return undefined;
      const claimed = await this.repository.save(entry);
      const now = this.now();
      await publishAll(this.publisher, [waitlistMatchStarted(claimed, entry.loadedVersion, entry.matchedCapacityCredits.map((credit) => credit.capacityReleaseRef), correlationId, now.toISOString())]);
      return claimed;
    }
    return this.advancePromotion(entry, journeyOrder, correlationId);
  }

  private async advancePromotion(entry: WaitlistEntry, journeyOrder: JourneyOrderClient, correlationId?: string): Promise<WaitlistEntrySnapshot | undefined> {
    if (entry.status !== "MATCHING" || entry.journeyOrderRef) return undefined;
    const now = this.now();
    if (new Date(entry.deadline) <= now || (entry.offerExpiresAt && entry.offerExpiresAt <= now)) {
      await this.repository.restoreCapacityCredits(entry);
      entry.expire();
      const expired = await this.repository.save(entry);
      await publishAll(this.publisher, [waitlistExpired(expired, entry.loadedVersion, correlationId, now.toISOString())]);
      return expired;
    }
    if (!entry.fareQuoteId) {
      const quote = await this.farePricing.quote(entry);
      entry.recordFareQuote(quote.fareQuoteId);
      return this.repository.save(entry);
    }
    if (!entry.offerId) {
      const commercialOffer = await this.offerManagement.createOffer(entry, entry.fareQuoteId!);
      const offerNow = this.now();
      const expiresAt = earliestInstant(commercialOffer.expiresAt, entry.paymentGuarantee.authorizedUntil, entry.deadline);
      if (expiresAt <= offerNow) throw new DomainError("PRECONDITION_FAILED", "Commercial offer is already outside the waitlist offer window");
      entry.offer(commercialOffer.offerId, commercialOffer.offerVersion, entry.fareQuoteId!, undefined, offerNow, expiresAt);
      return this.repository.save(entry);
    }
    const orderRequestedAt = this.now();
    entry.ensureOfferAcceptable(orderRequestedAt);
    const order = await journeyOrder.createOrder(entry);
    entry.linkJourneyOrder(orderRequestedAt, order.orderId);
    return this.repository.save(entry);
  }

  async expireDueOffers(correlationId?: string): Promise<readonly WaitlistEntrySnapshot[]> {
    const now = this.now();
    const expired: WaitlistEntrySnapshot[] = [];
    for (const entry of await this.repository.findExpiredOffers(now)) {
      const capacityHoldId = entry.capacityHoldId;
      if (!entry.journeyOrderRef && entry.matchedCapacityCredits.length > 0) await this.repository.restoreCapacityCredits(entry);
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
function earliestInstant(...values: readonly string[]): Date {
  const instants = values.map((value) => new Date(value));
  if (instants.some((value) => !Number.isFinite(value.getTime()))) throw new Error("Waitlist offer boundary included an invalid timestamp");
  return new Date(Math.min(...instants.map((value) => value.getTime())));
}
