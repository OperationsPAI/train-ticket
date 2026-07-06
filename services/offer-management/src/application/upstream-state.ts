import { DomainError, mapStatusToConfidence, type AvailabilityConfidence, type OfferItem, type PassengerMix, type PriceGuaranteeLevel, type PriceSnapshot, type QuoteOfferCommand, type TravelerRef, type TravelerType } from "../domain.js";
import { type EventEnvelope, type EventHandler } from "../ports/messaging.js";

import { createHash } from "node:crypto";

/** Normative inputHash per events/fare-pricing.md. */
export function contractInputHash(segmentRefs: readonly string[], channel: string, travelerRefs: readonly string[]): string {
  const material = `${[...segmentRefs].sort().join(",")}|${channel}|${[...travelerRefs].sort().join(",")}`;
  return createHash("sha256").update(material, "utf8").digest("hex");
}
export type QuoteOfferRequest = Readonly<{
  accountId: string;
  channelId: string;
  itineraryRef: string;
  travelerRefs: readonly string[];
  quoteRequestId?: string;
}>;

type MoneyLike = Readonly<{ currency: string; minorUnits: number }>;

type StoredItinerary = Readonly<{
  itineraryRef: string;
  itineraryVersion: string;
  segmentRefs: readonly string[];
  modeBySegment: ReadonlyMap<string, OfferItem["mode"]>;
  availabilityBySegment: ReadonlyMap<string, StoredAvailability>;
  inputHash?: string;
}>;

type StoredAvailability = Readonly<{
  snapshotId: string;
  snapshotVersion: string;
  capturedAt: Date;
  expiresAt: Date;
  sellable: boolean;
  status: "AVAILABLE" | "LIMITED" | "UNKNOWN" | "UNAVAILABLE";
  confidence: AvailabilityConfidence;
}>;

type StoredFareQuote = Readonly<{
  quoteId: string;
  inputHash: string;
  channelId: string;
  travelerRefs: readonly string[];
  currency: string;
  validFrom: Date;
  validUntil: Date;
  total: MoneyLike;
  subtotal: MoneyLike;
  ruleSnapshotRef: string;
  pricingVersion: string;
  ruleVersion: string;
  priceSnapshotRef: string;
  guaranteeLevel: PriceGuaranteeLevel;
}>;

type StoredTraveler = Readonly<{
  travelerId: string;
  travelerType: TravelerType;
  maskedDocumentRef?: string;
  eligibilityRef?: TravelerRef["eligibilityRef"];
}>;

export interface UpstreamStateRepository {
  saveItinerary(itinerary: StoredItinerary): Promise<void>;
  findItinerary(itineraryRef: string): Promise<StoredItinerary | undefined>;
  saveFareQuote(fareQuote: StoredFareQuote): Promise<void>;
  findFareQuote(itineraryRef: string, channelId: string, travelerRefs: readonly string[]): Promise<StoredFareQuote | undefined>;
  saveTraveler(traveler: StoredTraveler): Promise<void>;
  findTraveler(travelerId: string): Promise<StoredTraveler | undefined>;
  removeTravelerEligibility(travelerId: string, eligibilityId: string): Promise<void>;
  clear(): void;
}

export class InMemoryUpstreamStateRepository implements UpstreamStateRepository {
  private readonly itineraries = new Map<string, StoredItinerary>();
  private readonly fareQuotesByKey = new Map<string, StoredFareQuote>();
  private readonly travelers = new Map<string, StoredTraveler>();

  async saveItinerary(itinerary: StoredItinerary): Promise<void> {
    this.itineraries.set(itinerary.itineraryRef, itinerary);
  }

  async findItinerary(itineraryRef: string): Promise<StoredItinerary | undefined> {
    return this.itineraries.get(itineraryRef);
  }

  async saveFareQuote(fareQuote: StoredFareQuote): Promise<void> {
    this.fareQuotesByKey.set(fareQuoteKey(fareQuote.inputHash, fareQuote.channelId, fareQuote.travelerRefs), fareQuote);
  }

  async findFareQuote(itineraryRef: string, channelId: string, travelerRefs: readonly string[]): Promise<StoredFareQuote | undefined> {
    return this.fareQuotesByKey.get(fareQuoteKey(itineraryRef, channelId, travelerRefs));
  }

  async saveTraveler(traveler: StoredTraveler): Promise<void> {
    this.travelers.set(traveler.travelerId, traveler);
  }

  async findTraveler(travelerId: string): Promise<StoredTraveler | undefined> {
    return this.travelers.get(travelerId);
  }

  async removeTravelerEligibility(travelerId: string, eligibilityId: string): Promise<void> {
    const traveler = this.travelers.get(travelerId);
    if (traveler?.eligibilityRef?.eligibilityId === eligibilityId) {
      this.travelers.set(travelerId, { ...traveler, eligibilityRef: undefined });
    }
  }

  clear(): void {
    this.itineraries.clear();
    this.fareQuotesByKey.clear();
    this.travelers.clear();
  }
}

export function createUpstreamEventHandler(repository: UpstreamStateRepository): EventHandler {
  return async (envelope) => {
    await applyUpstreamEvent(repository, envelope);
    return "ack";
  };
}

export async function applyUpstreamEvent(repository: UpstreamStateRepository, envelope: EventEnvelope): Promise<void> {
  switch (envelope.eventType) {
    case "ItineraryProposed":
      await storeItineraries(repository, envelope.payload);
      return;
    case "FareQuoteComputed":
      await storeFareQuote(repository, envelope.payload);
      return;
    case "TravelerSnapshotUpdated":
      await storeTravelerSnapshot(repository, envelope.payload);
      return;
    case "EligibilityDetermined":
      await storeEligibility(repository, envelope.payload);
      return;
    case "EligibilityExpired":
      await expireEligibility(repository, envelope.payload);
      return;
    default:
      return;
  }
}

export async function buildQuoteOfferCommand(repository: UpstreamStateRepository, request: QuoteOfferRequest): Promise<QuoteOfferCommand> {
  const itinerary = await repository.findItinerary(request.itineraryRef);
  if (!itinerary) {
    throw new DomainError("MISSING_ITINERARY_SNAPSHOT", `No consumed Trip Planning itinerary found for ${request.itineraryRef}`);
  }

  const fareQuoteLookupKey = contractInputHash(itinerary.segmentRefs, request.channelId, request.travelerRefs);
  const fareQuote = await repository.findFareQuote(fareQuoteLookupKey, request.channelId, request.travelerRefs);
  if (!fareQuote) {
    throw new DomainError("MISSING_FARE_QUOTE", "No consumed Fare Pricing quote matches itinerary/fare input, channelId, and travelerRefs");
  }

  const travelers = await Promise.all(request.travelerRefs.map((travelerId) => repository.findTraveler(travelerId)));
  const missingTraveler = request.travelerRefs.find((_, index) => !travelers[index]);
  if (missingTraveler) {
    throw new DomainError("MISSING_TRAVELER_SNAPSHOT", `No consumed Traveler Profile snapshot found for ${missingTraveler}`);
  }

  const quotedAt = new Date();
  const offerExpiresAt = new Date(Math.min(quotedAt.getTime() + 10 * 60 * 1000, fareQuote.validUntil.getTime()));
  const passengerMix: PassengerMix = {
    travelerSetHash: travelerSetHash(request.travelerRefs),
    travelers: travelers.map((traveler) => ({
      travelerId: traveler!.travelerId,
      travelerType: traveler!.travelerType,
      maskedDocumentRef: traveler!.maskedDocumentRef,
      eligibilityRef: traveler!.eligibilityRef,
    })),
  };

  const itemPrice = { currency: fareQuote.currency, amountMinor: fareQuote.total.minorUnits };
  const items: OfferItem[] = itinerary.segmentRefs.map((segmentRef, index) => {
    // Availability hints are contractually optional and non-authoritative
    // (events/trip-planning.md); the booking saga performs the real capacity
    // hold. Missing hints degrade to UNKNOWN instead of rejecting the offer.
    const availability = itinerary.availabilityBySegment.get(segmentRef) ?? {
      snapshotId: `avs-unknown:${segmentRef}`,
      snapshotVersion: "0",
      capturedAt: quotedAt,
      expiresAt: offerExpiresAt,
      sellable: true,
      status: "UNKNOWN",
      confidence: "UNKNOWN" as AvailabilityConfidence,
    };
    return {
      offerItemId: `ofi-${uuidV7()}`,
      mode: itinerary.modeBySegment.get(segmentRef) ?? "train",
      segmentRef,
      itemPrice: index === 0 ? itemPrice : { currency: fareQuote.currency, amountMinor: 0 },
      availabilitySnapshot: {
        snapshotId: availability.snapshotId,
        snapshotVersion: availability.snapshotVersion,
        sourceContext: "CapacityAvailability",
        capturedAt: availability.capturedAt,
        expiresAt: availability.expiresAt,
        sellable: availability.sellable,
        status: availability.status,
        confidence: availability.confidence,
      },
      fareSnapshot: {
        fareQuoteRef: fareQuote.quoteId,
        ruleSnapshotRef: fareQuote.ruleSnapshotRef,
        pricingVersion: fareQuote.pricingVersion,
        ruleVersion: fareQuote.ruleVersion,
        sourceContext: "FarePricing",
        capturedAt: fareQuote.validFrom,
        expiresAt: fareQuote.validUntil,
      },
    };
  });

  const priceSnapshot: PriceSnapshot = {
    snapshotId: fareQuote.priceSnapshotRef,
    fareQuoteRef: fareQuote.quoteId,
    capturedAt: fareQuote.validFrom,
    expiresAt: fareQuote.validUntil,
    guaranteeLevel: fareQuote.guaranteeLevel,
    currency: fareQuote.currency,
    subtotal: { currency: fareQuote.currency, amountMinor: fareQuote.subtotal.minorUnits },
    taxes: [],
    fees: [],
    discounts: [],
    total: { currency: fareQuote.currency, amountMinor: fareQuote.total.minorUnits },
  };

  return {
    offerId: `off-${uuidV7()}`,
    quoteRequestId: request.quoteRequestId ?? `cmd-${uuidV7()}`,
    accountId: request.accountId,
    channelId: request.channelId,
    itinerary: {
      itineraryId: itinerary.itineraryRef,
      itineraryVersion: itinerary.itineraryVersion,
      sourceContext: "TripPlanning",
      segmentRefs: itinerary.segmentRefs,
    },
    passengerMix,
    validityWindow: { startsAt: quotedAt, expiresAt: offerExpiresAt },
    items,
    priceSnapshot,
    riskDisclosures: [],
    quotedAt,
  };
}

async function storeItineraries(repository: UpstreamStateRepository, payload: Record<string, unknown>): Promise<void> {
  const itineraries = arrayOfObjects(payload.itineraries);
  for (const itinerary of itineraries) {
    const itineraryRef = stringField(itinerary, "itineraryRef");
    if (!itineraryRef) continue;
    const legs = arrayOfObjects(itinerary.legs);
    const segmentRefs = legs.map((leg, index) => stringField(leg, "serviceSegmentRef") ?? `${itineraryRef}:segment:${index + 1}`);
    const modeBySegment = new Map<string, OfferItem["mode"]>();
    for (const [index, leg] of legs.entries()) {
      modeBySegment.set(segmentRefs[index], transportMode(stringField(leg, "mode")));
    }
    const availabilityBySegment = availabilitySnapshots(itinerary, segmentRefs);
    await repository.saveItinerary({
      itineraryRef,
      itineraryVersion: stringField(itinerary, "itineraryVersion") ?? stringField(payload, "snapshotVersion") ?? "v1",
      segmentRefs,
      modeBySegment,
      availabilityBySegment,
      inputHash: stringField(itinerary, "inputHash") ?? stringField(payload, "intentRef"),
    });
  }
}

async function storeFareQuote(repository: UpstreamStateRepository, payload: Record<string, unknown>): Promise<void> {
  if (stringField(payload, "status") !== "QUOTED") return;
  const quoteId = stringField(payload, "quoteId");
  const inputHash = stringField(payload, "inputHash");
  const channelId = stringField(payload, "channel") ?? stringField(payload, "channelId");
  const travelerRefs = stringArray(payload.travelerRefs);
  const currency = stringField(payload, "currency");
  const validFrom = dateField(payload, "validFrom");
  const validUntil = dateField(payload, "validUntil");
  const breakdown = objectField(payload, "breakdown");
  const ruleSnapshot = objectField(payload, "ruleSnapshot");
  if (!quoteId || !inputHash || !channelId || travelerRefs.length === 0 || !currency || !validFrom || !validUntil || !breakdown || !ruleSnapshot) return;

  const total = moneyField(breakdown, "total") ?? moneyField(payload, "total");
  // Contract RuleSnapshot (events/fare-pricing.md) carries ruleSetId/ruleSetVersion/digest;
  // internal references derive from those instead of requiring bespoke fields.
  const ruleSetId = stringField(ruleSnapshot, "ruleSetId") ?? stringField(ruleSnapshot, "ruleSnapshotRef");
  const ruleSetVersion = stringField(ruleSnapshot, "ruleSetVersion") ?? stringField(ruleSnapshot, "ruleVersion");
  const ruleSnapshotRef = ruleSetId && ruleSetVersion ? `${ruleSetId}@${ruleSetVersion}` : stringField(ruleSnapshot, "digest");
  const pricingVersion = ruleSetVersion ?? stringField(payload, "pricingVersion");
  const ruleVersion = ruleSetVersion ?? stringField(payload, "ruleVersion");
  const priceSnapshotRef = stringField(breakdown, "priceSnapshotRef") ?? stringField(payload, "priceSnapshotRef") ?? `price-snapshot:${quoteId}`;
  if (!total || !ruleSnapshotRef || !pricingVersion || !ruleVersion) return;

  await repository.saveFareQuote({
    quoteId,
    inputHash,
    channelId,
    travelerRefs,
    currency,
    validFrom,
    validUntil,
    total,
    subtotal: moneyField(breakdown, "subtotal") ?? total,
    ruleSnapshotRef,
    pricingVersion,
    ruleVersion,
    priceSnapshotRef,
    guaranteeLevel: priceGuarantee(stringField(payload, "priceGuaranteeLevel") ?? stringField(breakdown, "priceGuaranteeLevel")),
  });
}

async function storeTravelerSnapshot(repository: UpstreamStateRepository, payload: Record<string, unknown>): Promise<void> {
  const travelerId = stringField(payload, "travelerId");
  if (!travelerId) return;
  const parsedTravelerType = travelerType(stringField(payload, "travelerType"));
  if (!parsedTravelerType) return;
  const existing = await repository.findTraveler(travelerId);
  await repository.saveTraveler({
    travelerId,
    travelerType: parsedTravelerType,
    maskedDocumentRef: stringField(payload, "maskedDocumentRef"),
    eligibilityRef: existing?.eligibilityRef,
  });
}

async function storeEligibility(repository: UpstreamStateRepository, payload: Record<string, unknown>): Promise<void> {
  const travelerId = stringField(payload, "travelerId");
  const eligibility = objectField(payload, "eligibilityRef");
  const eligibilityId = stringField(eligibility, "eligibilityId") ?? stringField(eligibility, "id");
  const eligibilityType = stringField(eligibility, "eligibilityType");
  const eligibilitySource = stringField(eligibility, "eligibilitySource");
  if (!travelerId || !eligibility || !eligibilityId || !eligibilityType || !eligibilitySource) return;
  const existing = await repository.findTraveler(travelerId);
  if (!existing) return;
  await repository.saveTraveler({
    travelerId,
    travelerType: existing.travelerType,
    maskedDocumentRef: existing.maskedDocumentRef,
    eligibilityRef: {
      eligibilityId,
      eligibilityType,
      eligibilitySource,
      evidenceHash: stringField(eligibility, "evidenceHash"),
      verifiedAt: stringField(payload, "determinedAt"),
    },
  });
}

async function expireEligibility(repository: UpstreamStateRepository, payload: Record<string, unknown>): Promise<void> {
  const travelerId = stringField(payload, "travelerId");
  const eligibilityId = stringField(payload, "eligibilityId");
  if (travelerId && eligibilityId) {
    await repository.removeTravelerEligibility(travelerId, eligibilityId);
  }
}

function availabilitySnapshots(itinerary: Record<string, unknown>, segmentRefs: readonly string[]): ReadonlyMap<string, StoredAvailability> {
  const bySegment = new Map<string, StoredAvailability>();
  for (const candidate of [...arrayOfObjects(itinerary.availabilitySnapshots), ...arrayOfObjects(itinerary.availabilityHint ? [itinerary.availabilityHint] : [])]) {
    const segmentRef = stringField(candidate, "segmentRef") ?? stringField(candidate, "serviceSegmentRef");
    if (!segmentRef) continue;
    const expiresAt = dateField(candidate, "expiresAt") ?? dateField(candidate, "validUntil");
    if (!expiresAt) continue;
    const capturedAt = dateField(candidate, "capturedAt") ?? dateField(candidate, "updatedAt");
    const status = availabilityStatus(stringField(candidate, "status"));
    const snapshotId = stringField(candidate, "snapshotId") ?? stringField(candidate, "availabilitySnapshotRef") ?? stringField(candidate, "availabilitySnapshotId");
    const snapshotVersion = stringField(candidate, "snapshotVersion");
    const sellable = booleanField(candidate, "sellable");
    const confidence = availabilityConfidence(stringField(candidate, "confidence"));
    if (!capturedAt || !status || !snapshotId || !snapshotVersion || sellable === undefined || !confidence) continue;
    bySegment.set(segmentRef, {
      snapshotId,
      snapshotVersion,
      capturedAt,
      expiresAt,
      sellable,
      status,
      confidence: mapStatusToConfidence(status, confidence),
    });
  }
  return bySegment;
}

function fareQuoteKey(inputHash: string, channelId: string, travelerRefs: readonly string[]): string {
  return `${inputHash}:${channelId}:${travelerSetHash(travelerRefs)}`;
}

function travelerSetHash(travelerRefs: readonly string[]): string {
  return [...travelerRefs].sort().join(",");
}

function objectField(source: unknown, key: string): Record<string, unknown> | undefined {
  if (!source || typeof source !== "object") return undefined;
  const value = (source as Record<string, unknown>)[key];
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}

function arrayOfObjects(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value) ? value.filter((entry): entry is Record<string, unknown> => Boolean(entry) && typeof entry === "object" && !Array.isArray(entry)) : [];
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0) : [];
}

function stringField(source: unknown, key: string): string | undefined {
  if (!source || typeof source !== "object") return undefined;
  const value = (source as Record<string, unknown>)[key];
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}

function booleanField(source: Record<string, unknown>, key: string): boolean | undefined {
  const value = source[key];
  return typeof value === "boolean" ? value : undefined;
}

function dateField(source: unknown, key: string): Date | undefined {
  const value = stringField(source, key);
  if (!value) return undefined;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date;
}

function moneyField(source: Record<string, unknown>, key: string): MoneyLike | undefined {
  const value = objectField(source, key);
  const currency = stringField(value, "currency");
  const minorUnits = value?.minorUnits;
  return currency && typeof minorUnits === "number" && Number.isFinite(minorUnits) ? { currency, minorUnits } : undefined;
}

function availabilityStatus(value: string | undefined): StoredAvailability["status"] | undefined {
  switch (value) {
    case "AVAILABLE":
    case "LIMITED":
    case "UNKNOWN":
    case "UNAVAILABLE":
      return value;
    default:
      return undefined;
  }
}

function availabilityConfidence(value: string | undefined): AvailabilityConfidence | undefined {
  switch (value) {
    case "confirmed-snapshot":
    case "low":
    case "estimated":
      return value;
    default:
      return undefined;
  }
}

function travelerType(value: string | undefined): TravelerType | undefined {
  switch (value) {
    case "ADULT":
    case "CHILD":
    case "STUDENT":
    case "SENIOR":
    case "INFANT":
    case "MILITARY":
    case "DISABLED":
      return value;
    default:
      return undefined;
  }
}

function transportMode(value: string | undefined): OfferItem["mode"] {
  switch (value) {
    case "transfer":
    case "ancillary":
      return value;
    default:
      return "train";
  }
}

function priceGuarantee(value: string | undefined): PriceGuaranteeLevel {
  switch (value) {
    case "FIXED_UNTIL_EXPIRY":
    case "FixedUntilExpiry":
      return "FixedUntilExpiry";
    case "ESTIMATED_ONLY":
    case "EstimatedOnly":
      return "EstimatedOnly";
    case "PROVIDER_FINAL_CONFIRM_REQUIRED":
    case "ProviderFinalConfirmRequired":
      return "ProviderFinalConfirmRequired";
    default:
      return "FixedUntilExpiry";
  }
}

function uuidV7(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  const timestamp = BigInt(Date.now());

  bytes[0] = Number((timestamp >> 40n) & 0xffn);
  bytes[1] = Number((timestamp >> 32n) & 0xffn);
  bytes[2] = Number((timestamp >> 24n) & 0xffn);
  bytes[3] = Number((timestamp >> 16n) & 0xffn);
  bytes[4] = Number((timestamp >> 8n) & 0xffn);
  bytes[5] = Number(timestamp & 0xffn);
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;

  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
