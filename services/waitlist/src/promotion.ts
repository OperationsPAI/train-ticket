import { uuidV7, type EventPublisher } from "@trainticket/ts-kit";
import { DomainError, type WaitlistEntry, type WaitlistEntrySnapshot } from "./domain.js";
import { publishAll, waitlistEntryPromoted, waitlistOfferExpired } from "./publisher.js";

export type WaitlistCapacityFreed = Readonly<{
  segmentRef: string;
  departureDate: string;
  seatClass?: string;
  freedSlots: number;
}>;

export type WaitlistOffer = Readonly<{
  offerId: string;
  entryId: string;
  fareQuoteId: string;
  capacityHoldId: string;
  expiresAt: string;
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
  findExpiredOffers(now: Date): Promise<readonly WaitlistEntry[]> | readonly WaitlistEntry[];
  queueFor(segmentRef: string, departureDate: string, seatClass?: string): Promise<readonly WaitlistEntrySnapshot[]> | readonly WaitlistEntrySnapshot[];
}

export interface FarePricingClient {
  quote(entry: WaitlistEntry): Promise<{ fareQuoteId: string }>;
}

export interface CapacityAvailabilityClient {
  hold(entry: WaitlistEntry, fareQuoteId: string): Promise<{ capacityHoldId: string }>;
  releaseHold(capacityHoldId: string): Promise<void>;
}

export class HttpFarePricingClient implements FarePricingClient {
  constructor(private readonly baseUrl = process.env.FARE_PRICING_URL ?? process.env.FARE_PRICING_BASE_URL ?? "http://fare-pricing") {}

  async quote(entry: WaitlistEntry): Promise<{ fareQuoteId: string }> {
    const response = await fetch(`${trimRight(this.baseUrl)}/api/v1/fare-quotes`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        accountId: entry.accountId,
        travelerRefs: entry.travelerRefs,
        segmentRef: entry.segmentRef,
        departureDate: entry.departureDate,
        seatClass: entry.seatClass,
      }),
    });
    if (!response.ok) throw new Error(`Fare quote request failed with status ${response.status}`);
    const body = await response.json() as Record<string, unknown>;
    const fareQuoteId = stringField(body, "fareQuoteId") ?? stringField(body, "quoteId") ?? stringField(body, "id");
    if (!fareQuoteId) throw new Error("Fare quote response did not include fareQuoteId");
    return { fareQuoteId };
  }
}

export class HttpCapacityAvailabilityClient implements CapacityAvailabilityClient {
  constructor(private readonly baseUrl = process.env.CAPACITY_AVAILABILITY_URL ?? process.env.CAPACITY_AVAILABILITY_BASE_URL ?? "http://capacity-availability") {}

  async hold(entry: WaitlistEntry, fareQuoteId: string): Promise<{ capacityHoldId: string }> {
    const response = await fetch(`${trimRight(this.baseUrl)}/api/v1/capacity/holds`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        accountId: entry.accountId,
        travelerRefs: entry.travelerRefs,
        segmentRef: entry.segmentRef,
        departureDate: entry.departureDate,
        seatClass: entry.seatClass,
        fareQuoteId,
        holdReason: "WAITLIST_PROMOTION",
      }),
    });
    if (!response.ok) throw new Error(`Capacity hold request failed with status ${response.status}`);
    const body = await response.json() as Record<string, unknown>;
    const capacityHoldId = stringField(body, "capacityHoldId") ?? stringField(body, "holdId") ?? stringField(body, "id");
    if (!capacityHoldId) throw new Error("Capacity hold response did not include capacityHoldId");
    return { capacityHoldId };
  }

  async releaseHold(capacityHoldId: string): Promise<void> {
    await fetch(`${trimRight(this.baseUrl)}/api/v1/capacity/holds/${encodeURIComponent(capacityHoldId)}`, { method: "DELETE" });
  }
}

export class PromotionOrchestrator {
  constructor(
    private readonly repository: WaitlistRepository,
    private readonly farePricing: FarePricingClient,
    private readonly capacityAvailability: CapacityAvailabilityClient,
    private readonly publisher: EventPublisher,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async onCapacityFreed(event: WaitlistCapacityFreed, correlationId?: string): Promise<PromotionResult> {
    const promoted: WaitlistEntrySnapshot[] = [];
    const offers: WaitlistOffer[] = [];
    const slots = Math.max(0, Math.floor(event.freedSlots));
    for (let index = 0; index < slots; index++) {
      const entry = await this.repository.findTopQueued(event.segmentRef, event.departureDate, event.seatClass);
      if (!entry) break;
      const quote = await this.farePricing.quote(entry);
      const hold = await this.capacityAvailability.hold(entry, quote.fareQuoteId);
      const now = this.now();
      const expiresAt = new Date(now.getTime() + 15 * 60 * 1000);
      const offer = { offerId: `wlo-${uuidV7()}`, entryId: entry.entryId, fareQuoteId: quote.fareQuoteId, capacityHoldId: hold.capacityHoldId, expiresAt: expiresAt.toISOString() };
      entry.offer(offer.offerId, quote.fareQuoteId, hold.capacityHoldId, now, expiresAt);
      const snapshot = await this.repository.save(entry);
      promoted.push(snapshot);
      offers.push(offer);
      await publishAll(this.publisher, [waitlistEntryPromoted(snapshot, offer, correlationId)]);
    }
    return { promoted, offers };
  }

  async expireDueOffers(correlationId?: string): Promise<readonly WaitlistEntrySnapshot[]> {
    const now = this.now();
    const expired: WaitlistEntrySnapshot[] = [];
    for (const entry of await this.repository.findExpiredOffers(now)) {
      const offer = offerFromEntry(entry);
      entry.expire(now);
      if (entry.capacityHoldId) await this.capacityAvailability.releaseHold(entry.capacityHoldId);
      const snapshot = await this.repository.save(entry);
      expired.push(snapshot);
      await publishAll(this.publisher, [waitlistOfferExpired(snapshot, offer, correlationId)]);
      await this.onCapacityFreed({ segmentRef: entry.segmentRef, departureDate: entry.departureDate, seatClass: entry.seatClass, freedSlots: 1 }, correlationId);
    }
    return expired;
  }
}

export function offerFromEntry(entry: WaitlistEntry): WaitlistOffer {
  if (!entry.offerId || !entry.fareQuoteId || !entry.capacityHoldId || !entry.offerExpiresAt) {
    throw new DomainError("PRECONDITION_FAILED", "Waitlist entry does not have an active offer");
  }
  return { offerId: entry.offerId, entryId: entry.entryId, fareQuoteId: entry.fareQuoteId, capacityHoldId: entry.capacityHoldId, expiresAt: entry.offerExpiresAt.toISOString() };
}

function trimRight(value: string): string { return value.replace(/\/+$/u, ""); }
function stringField(body: Record<string, unknown>, field: string): string | undefined { return typeof body[field] === "string" ? body[field] as string : undefined; }
