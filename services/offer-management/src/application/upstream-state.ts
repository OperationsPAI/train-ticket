import { DomainError, mapStatusToConfidence, type AvailabilityConfidence, type OfferItem, type PassengerMix, type PriceGuaranteeLevel, type PriceSnapshot, type QuoteOfferCommand, type RiskDisclosure, type TravelerRef, type TravelerType } from "../domain.js";
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

export type StoredItinerary = Readonly<{
  itineraryRef: string;
  itineraryVersion: string;
  segmentRefs: readonly string[];
  modeBySegment: ReadonlyMap<string, OfferItem["mode"]>;
  availabilityBySegment: ReadonlyMap<string, StoredAvailability>;
  inputHash?: string;
}>;

export type StoredAvailability = Readonly<{
  snapshotId: string;
  snapshotVersion: string;
  capturedAt: Date;
  expiresAt: Date;
  sellable: boolean;
  status: "AVAILABLE" | "LIMITED" | "UNKNOWN" | "UNAVAILABLE";
  confidence: AvailabilityConfidence;
}>;

export type StoredFareQuote = Readonly<{
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

export type StoredTraveler = Readonly<{
  travelerId: string;
  travelerType: TravelerType;
  maskedDocumentRef?: string;
  eligibilityRef?: TravelerRef["eligibilityRef"];
}>;
export type StoredTransferPlanEvaluation = Readonly<{
  transferPlanId: string;
  itineraryRef: string;
  planningSnapshotVersion: number;
  journeyOrderId?: string;
  status: "EVALUATED" | "UNSERVICEABLE";
  evaluationVersion: number;
  connectionIds: readonly string[];
  riskPolicyVersion: string;
  evaluatedAt: Date;
  unserviceableReasons: readonly string[];
}>;

export type StoredConnectionContract = Readonly<{
  connectionContractId: string;
  connectionId: string;
  contractType: string;
  status: "PROPOSED" | "ELIGIBLE" | "REJECTED";
  responsibleParty: string;
  coverageSummary: string;
  disclosureVersion: string;
  termsSnapshotRef?: string;
  proposedAt: Date;
}>;

export type StoredMctRule = Readonly<{
  mctRuleId: string;
  version: number;
  previousStatus: string;
  status: "PUBLISHED";
  fromNodeType: string;
  toNodeType: string;
  transferCategory: string;
  minimumMinutes: number;
  conditions: Record<string, unknown>;
  validFrom: Date;
  validUntil?: Date;
  publishedAt: Date;
}>;

export type StoredAncillaryCatalogItem = Readonly<{
  catalogItemId: string;
  version: number;
  serviceType: string;
  displayName?: string;
  attachmentScope?: string;
  modalities: readonly string[];
  supplierRef?: string;
  price?: MoneyLike;
  salesWindow?: Readonly<{ startAt?: string; endAt?: string }>;
  serviceWindow?: Readonly<{ startAt?: string; endAt?: string }>;
  purchaseCutoffHoursBeforeDeparture?: number;
  eligibilityRuleVersion?: string;
  requiresEntitlementRef?: boolean;
  requiresSegmentRef?: boolean;
  fulfillmentMethod?: string;
  status: "PUBLISHED" | "SUSPENDED" | "SUPERSEDED";
  replacementCatalogItemId?: string;
  updatedAt: Date;
}>;

export type StoredAncillaryOffer = Readonly<{
  ancillaryOfferId: string;
  offerVersion: number;
  journeyOrderId?: string;
  offerRef?: string;
  travelerRef: string;
  segmentRef?: string;
  catalogItemId: string;
  serviceType: string;
  displayName: string;
  quantity: number;
  unitPrice: MoneyLike;
  totalPrice: MoneyLike;
  validFrom: Date;
  expiresAt: Date;
  status: "QUOTED" | "EXPIRED";
  quotedAt?: Date;
  expiredAt?: Date;
}>;


export interface UpstreamStateRepository {
  saveItinerary(itinerary: StoredItinerary): Promise<void>;
  findItinerary(itineraryRef: string): Promise<StoredItinerary | undefined>;
  saveFareQuote(fareQuote: StoredFareQuote): Promise<void>;
  saveFareQuotes(fareQuotes: readonly StoredFareQuote[]): Promise<void>;
  findFareQuote(itineraryRef: string, channelId: string, travelerRefs: readonly string[]): Promise<StoredFareQuote | undefined>;
  saveTraveler(traveler: StoredTraveler): Promise<void>;
  findTraveler(travelerId: string): Promise<StoredTraveler | undefined>;
  removeTravelerEligibility(travelerId: string, eligibilityId: string): Promise<void>;
  saveTransferPlanEvaluation(evaluation: StoredTransferPlanEvaluation): Promise<void>;
  findTransferPlanEvaluation(itineraryRef: string): Promise<StoredTransferPlanEvaluation | undefined>;
  saveConnectionContract(contract: StoredConnectionContract): Promise<void>;
  findConnectionContracts(connectionIds: readonly string[]): Promise<readonly StoredConnectionContract[]>;
  saveMctRule(rule: StoredMctRule): Promise<void>;
  findPublishedMctRules(): Promise<readonly StoredMctRule[]>;
  saveAncillaryCatalogItem(item: StoredAncillaryCatalogItem): Promise<void>;
  findPublishedAncillaryCatalogItems(): Promise<readonly StoredAncillaryCatalogItem[]>;
  saveAncillaryOffer(offer: StoredAncillaryOffer): Promise<void>;
  findAncillaryOffers(travelerRefs: readonly string[]): Promise<readonly StoredAncillaryOffer[]>;
  clear(): void;
}

export class InMemoryUpstreamStateRepository implements UpstreamStateRepository {
  private readonly itineraries = new Map<string, StoredItinerary>();
  private readonly fareQuotesByKey = new Map<string, StoredFareQuote>();
  private readonly travelers = new Map<string, StoredTraveler>();
  private readonly transferPlanEvaluationsByItinerary = new Map<string, StoredTransferPlanEvaluation>();
  private readonly connectionContractsById = new Map<string, StoredConnectionContract>();
  private readonly mctRulesById = new Map<string, StoredMctRule>();
  private readonly ancillaryCatalogItemsById = new Map<string, StoredAncillaryCatalogItem>();
  private readonly ancillaryOffersById = new Map<string, StoredAncillaryOffer>();

  async saveItinerary(itinerary: StoredItinerary): Promise<void> {
    this.itineraries.set(itinerary.itineraryRef, itinerary);
  }

  async findItinerary(itineraryRef: string): Promise<StoredItinerary | undefined> {
    return this.itineraries.get(itineraryRef);
  }

  async saveFareQuote(fareQuote: StoredFareQuote): Promise<void> {
    await this.saveFareQuotes([fareQuote]);
  }

  async saveFareQuotes(fareQuotes: readonly StoredFareQuote[]): Promise<void> {
    for (const fareQuote of fareQuotes) {
      this.fareQuotesByKey.set(fareQuoteKey(fareQuote.inputHash, fareQuote.channelId, fareQuote.travelerRefs), fareQuote);
    }
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

  async saveTransferPlanEvaluation(evaluation: StoredTransferPlanEvaluation): Promise<void> {
    const existing = this.transferPlanEvaluationsByItinerary.get(evaluation.itineraryRef);
    if (!existing || evaluation.evaluationVersion >= existing.evaluationVersion) {
      this.transferPlanEvaluationsByItinerary.set(evaluation.itineraryRef, evaluation);
    }
  }

  async findTransferPlanEvaluation(itineraryRef: string): Promise<StoredTransferPlanEvaluation | undefined> {
    return this.transferPlanEvaluationsByItinerary.get(itineraryRef);
  }

  async saveConnectionContract(contract: StoredConnectionContract): Promise<void> {
    this.connectionContractsById.set(contract.connectionContractId, contract);
  }

  async findConnectionContracts(connectionIds: readonly string[]): Promise<readonly StoredConnectionContract[]> {
    const ids = new Set(connectionIds);
    return [...this.connectionContractsById.values()].filter((contract) => ids.has(contract.connectionId));
  }

  async saveMctRule(rule: StoredMctRule): Promise<void> {
    this.mctRulesById.set(`${rule.mctRuleId}:${rule.version}`, rule);
  }

  async findPublishedMctRules(): Promise<readonly StoredMctRule[]> {
    return [...this.mctRulesById.values()].filter((rule) => rule.status === "PUBLISHED");
  }

  async saveAncillaryCatalogItem(item: StoredAncillaryCatalogItem): Promise<void> {
    this.ancillaryCatalogItemsById.set(item.catalogItemId, item);
  }

  async findPublishedAncillaryCatalogItems(): Promise<readonly StoredAncillaryCatalogItem[]> {
    return [...this.ancillaryCatalogItemsById.values()].filter((item) => item.status === "PUBLISHED");
  }

  async saveAncillaryOffer(offer: StoredAncillaryOffer): Promise<void> {
    this.ancillaryOffersById.set(offer.ancillaryOfferId, offer);
  }

  async findAncillaryOffers(travelerRefs: readonly string[]): Promise<readonly StoredAncillaryOffer[]> {
    const refs = new Set(travelerRefs);
    return [...this.ancillaryOffersById.values()].filter((offer) => refs.has(offer.travelerRef));
  }

  clear(): void {
    this.itineraries.clear();
    this.fareQuotesByKey.clear();
    this.travelers.clear();
    this.transferPlanEvaluationsByItinerary.clear();
    this.connectionContractsById.clear();
    this.mctRulesById.clear();
    this.ancillaryCatalogItemsById.clear();
    this.ancillaryOffersById.clear();
  }
}

export function createUpstreamEventHandler(repository: UpstreamStateRepository): EventHandler {
  const handler = (async (envelope: EventEnvelope) => {
    await applyUpstreamEvent(repository, envelope);
    return "ack";
  }) as EventHandler;
  handler.handleBatch = async (envelopes) => {
    await applyUpstreamEvents(repository, envelopes);
    return "ack" as const;
  };
  return handler;
}

export async function applyUpstreamEvent(repository: UpstreamStateRepository, envelope: EventEnvelope): Promise<void> {
  await applyUpstreamEvents(repository, [envelope]);
}

export async function applyUpstreamEvents(repository: UpstreamStateRepository, envelopes: readonly EventEnvelope[]): Promise<void> {
  const fareQuotes = envelopes
    .filter((envelope) => envelope.eventType === "FareQuoteComputed")
    .map((envelope) => parseFareQuote(envelope.payload))
    .filter((fareQuote): fareQuote is StoredFareQuote => fareQuote !== undefined);

  if (fareQuotes.length > 0) {
    await repository.saveFareQuotes(fareQuotes);
  }

  for (const envelope of envelopes) {
    switch (envelope.eventType) {
      case "ItineraryProposed":
        await storeItineraries(repository, envelope.payload);
        break;
      case "FareQuoteComputed":
        break;
      case "TravelerSnapshotUpdated":
        await storeTravelerSnapshot(repository, envelope.payload);
        break;
      case "EligibilityDetermined":
        await storeEligibility(repository, envelope.payload);
        break;
      case "EligibilityExpired":
        await expireEligibility(repository, envelope.payload);
        break;
      case "TransferPlanEvaluated":
        await storeTransferPlanEvaluation(repository, envelope.payload);
        break;
      case "ConnectionContractProposed":
        await storeConnectionContract(repository, envelope.payload);
        break;
      case "MctRulePublished":
        await storeMctRule(repository, envelope.payload);
        break;
      case "AncillaryCatalogItemPublished":
      case "AncillaryCatalogItemSuspended":
      case "AncillaryCatalogItemSuperseded":
        await storeAncillaryCatalogItem(repository, envelope.eventType, envelope.payload);
        break;
      case "AncillaryOfferQuoted":
        await storeAncillaryOffer(repository, envelope.payload);
        break;
      case "AncillaryOfferExpired":
        await expireAncillaryOffer(repository, envelope.payload);
        break;
      default:
        break;
    }
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
    riskDisclosures: await buildRiskDisclosures(repository, request.itineraryRef, request.travelerRefs, quotedAt),
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

function parseFareQuote(payload: Record<string, unknown>): StoredFareQuote | undefined {
  if (stringField(payload, "status") !== "QUOTED") return undefined;
  const quoteId = stringField(payload, "quoteId");
  const inputHash = stringField(payload, "inputHash");
  const channelId = stringField(payload, "channel") ?? stringField(payload, "channelId");
  const travelerRefs = stringArray(payload.travelerRefs);
  const currency = stringField(payload, "currency");
  const validFrom = dateField(payload, "validFrom");
  const validUntil = dateField(payload, "validUntil");
  const breakdown = objectField(payload, "breakdown");
  const ruleSnapshot = objectField(payload, "ruleSnapshot");
  if (!quoteId || !inputHash || !channelId || travelerRefs.length === 0 || !currency || !validFrom || !validUntil || !breakdown || !ruleSnapshot) return undefined;

  const total = moneyField(breakdown, "total") ?? moneyField(payload, "total");
  // Contract RuleSnapshot (events/fare-pricing.md) carries ruleSetId/ruleSetVersion/digest;
  // internal references derive from those instead of requiring bespoke fields.
  const ruleSetId = stringField(ruleSnapshot, "ruleSetId") ?? stringField(ruleSnapshot, "ruleSnapshotRef");
  const ruleSetVersion = stringField(ruleSnapshot, "ruleSetVersion") ?? stringField(ruleSnapshot, "ruleVersion");
  const ruleSnapshotRef = ruleSetId && ruleSetVersion ? `${ruleSetId}@${ruleSetVersion}` : stringField(ruleSnapshot, "digest");
  const pricingVersion = ruleSetVersion ?? stringField(payload, "pricingVersion");
  const ruleVersion = ruleSetVersion ?? stringField(payload, "ruleVersion");
  const priceSnapshotRef = stringField(breakdown, "priceSnapshotRef") ?? stringField(payload, "priceSnapshotRef") ?? `price-snapshot:${quoteId}`;
  if (!total || !ruleSnapshotRef || !pricingVersion || !ruleVersion) return undefined;

  return {
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
  };
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

async function storeTransferPlanEvaluation(repository: UpstreamStateRepository, payload: Record<string, unknown>): Promise<void> {
  const transferPlanId = stringField(payload, "transferPlanId");
  const itineraryRef = stringField(payload, "itineraryRef");
  const status = transferPlanStatus(stringField(payload, "status"));
  const evaluatedAt = dateField(payload, "evaluatedAt");
  const planningSnapshotVersion = numberField(payload, "planningSnapshotVersion");
  const evaluationVersion = numberField(payload, "evaluationVersion");
  const riskPolicyVersion = stringField(payload, "riskPolicyVersion");
  if (!transferPlanId || !itineraryRef || !status || !evaluatedAt || planningSnapshotVersion === undefined || evaluationVersion === undefined || !riskPolicyVersion) return;
  await repository.saveTransferPlanEvaluation({
    transferPlanId,
    itineraryRef,
    planningSnapshotVersion,
    journeyOrderId: stringField(payload, "journeyOrderId"),
    status,
    evaluationVersion,
    connectionIds: stringArray(payload.connectionIds),
    riskPolicyVersion,
    evaluatedAt,
    unserviceableReasons: stringArray(payload.unserviceableReasons),
  });
}

async function storeConnectionContract(repository: UpstreamStateRepository, payload: Record<string, unknown>): Promise<void> {
  const connectionContractId = stringField(payload, "connectionContractId");
  const connectionId = stringField(payload, "connectionId");
  const contractType = stringField(payload, "contractType");
  const status = connectionContractStatus(stringField(payload, "status"));
  const responsibleParty = stringField(payload, "responsibleParty");
  const coverageSummary = stringField(payload, "coverageSummary");
  const disclosureVersion = stringField(payload, "disclosureVersion");
  const proposedAt = dateField(payload, "proposedAt");
  if (!connectionContractId || !connectionId || !contractType || !status || !responsibleParty || !coverageSummary || !disclosureVersion || !proposedAt) return;
  await repository.saveConnectionContract({
    connectionContractId,
    connectionId,
    contractType,
    status,
    responsibleParty,
    coverageSummary,
    disclosureVersion,
    termsSnapshotRef: stringField(payload, "termsSnapshotRef"),
    proposedAt,
  });
}

async function storeMctRule(repository: UpstreamStateRepository, payload: Record<string, unknown>): Promise<void> {
  const mctRuleId = stringField(payload, "mctRuleId");
  const version = numberField(payload, "version");
  const previousStatus = stringField(payload, "previousStatus");
  const status = stringField(payload, "status");
  const fromNodeType = stringField(payload, "fromNodeType");
  const toNodeType = stringField(payload, "toNodeType");
  const transferCategory = stringField(payload, "transferCategory");
  const minimumMinutes = numberField(payload, "minimumMinutes");
  const conditions = objectField(payload, "conditions");
  const validFrom = dateField(payload, "validFrom");
  const publishedAt = dateField(payload, "publishedAt");
  if (!mctRuleId || version === undefined || !previousStatus || status !== "PUBLISHED" || !fromNodeType || !toNodeType || !transferCategory || minimumMinutes === undefined || !conditions || !validFrom || !publishedAt) return;
  await repository.saveMctRule({
    mctRuleId,
    version,
    previousStatus,
    status,
    fromNodeType,
    toNodeType,
    transferCategory,
    minimumMinutes,
    conditions,
    validFrom,
    validUntil: dateField(payload, "validUntil"),
    publishedAt,
  });
}

async function storeAncillaryCatalogItem(repository: UpstreamStateRepository, eventType: string, payload: Record<string, unknown>): Promise<void> {
  const catalogItemId = stringField(payload, "catalogItemId");
  const version = numberField(payload, "version");
  const serviceType = stringField(payload, "serviceType");
  const status = ancillaryCatalogStatus(stringField(payload, "status"));
  if (!catalogItemId || version === undefined || !serviceType || !status) return;
  const existing = (await repository.findPublishedAncillaryCatalogItems()).find((item) => item.catalogItemId === catalogItemId);
  await repository.saveAncillaryCatalogItem({
    catalogItemId,
    version,
    serviceType,
    displayName: stringField(payload, "displayName") ?? existing?.displayName,
    attachmentScope: stringField(payload, "attachmentScope") ?? existing?.attachmentScope,
    modalities: stringArray(payload.modalities),
    supplierRef: stringField(payload, "supplierRef") ?? existing?.supplierRef,
    price: moneyField(payload, "price") ?? existing?.price,
    salesWindow: (objectField(payload, "salesWindow") as StoredAncillaryCatalogItem["salesWindow"]) ?? existing?.salesWindow,
    serviceWindow: (objectField(payload, "serviceWindow") as StoredAncillaryCatalogItem["serviceWindow"]) ?? existing?.serviceWindow,
    purchaseCutoffHoursBeforeDeparture: numberField(payload, "purchaseCutoffHoursBeforeDeparture") ?? existing?.purchaseCutoffHoursBeforeDeparture,
    eligibilityRuleVersion: stringField(payload, "eligibilityRuleVersion") ?? existing?.eligibilityRuleVersion,
    requiresEntitlementRef: booleanField(payload, "requiresEntitlementRef") ?? existing?.requiresEntitlementRef,
    requiresSegmentRef: booleanField(payload, "requiresSegmentRef") ?? existing?.requiresSegmentRef,
    fulfillmentMethod: stringField(payload, "fulfillmentMethod") ?? existing?.fulfillmentMethod,
    status,
    replacementCatalogItemId: stringField(payload, "replacementCatalogItemId"),
    updatedAt: dateField(payload, eventType === "AncillaryCatalogItemPublished" ? "publishedAt" : eventType === "AncillaryCatalogItemSuspended" ? "suspendedAt" : "supersededAt") ?? new Date(),
  });
}

async function storeAncillaryOffer(repository: UpstreamStateRepository, payload: Record<string, unknown>): Promise<void> {
  const ancillaryOfferId = stringField(payload, "ancillaryOfferId");
  const offerVersion = numberField(payload, "offerVersion");
  const travelerRef = stringField(payload, "travelerRef");
  const catalogSnapshot = objectField(payload, "catalogSnapshot");
  const catalogItemId = stringField(catalogSnapshot, "catalogItemId");
  const serviceType = stringField(catalogSnapshot, "serviceType");
  const displayName = stringField(catalogSnapshot, "displayName");
  const quantity = numberField(payload, "quantity");
  const unitPrice = moneyField(payload, "unitPrice");
  const totalPrice = moneyField(payload, "totalPrice");
  const validFrom = dateField(payload, "validFrom");
  const expiresAt = dateField(payload, "expiresAt");
  const quotedAt = dateField(payload, "quotedAt");
  if (!ancillaryOfferId || offerVersion === undefined || !travelerRef || !catalogItemId || !serviceType || !displayName || quantity === undefined || !unitPrice || !totalPrice || !validFrom || !expiresAt) return;
  await repository.saveAncillaryOffer({
    ancillaryOfferId,
    offerVersion,
    journeyOrderId: stringField(payload, "journeyOrderId"),
    offerRef: stringField(payload, "offerRef"),
    travelerRef,
    segmentRef: stringField(payload, "segmentRef"),
    catalogItemId,
    serviceType,
    displayName,
    quantity,
    unitPrice,
    totalPrice,
    validFrom,
    expiresAt,
    status: "QUOTED",
    quotedAt,
  });
}

async function expireAncillaryOffer(repository: UpstreamStateRepository, payload: Record<string, unknown>): Promise<void> {
  const ancillaryOfferId = stringField(payload, "ancillaryOfferId");
  const offerVersion = numberField(payload, "offerVersion");
  const travelerRef = stringField(payload, "travelerRef");
  const catalogItemId = stringField(payload, "catalogItemId");
  const expiredAt = dateField(payload, "expiredAt");
  if (!ancillaryOfferId || offerVersion === undefined || !travelerRef || !catalogItemId || !expiredAt) return;
  const existing = (await repository.findAncillaryOffers([travelerRef])).find((offer) => offer.ancillaryOfferId === ancillaryOfferId);
  await repository.saveAncillaryOffer({
    ancillaryOfferId,
    offerVersion,
    journeyOrderId: stringField(payload, "journeyOrderId") ?? existing?.journeyOrderId,
    travelerRef,
    segmentRef: existing?.segmentRef,
    catalogItemId,
    serviceType: existing?.serviceType ?? "UNKNOWN",
    displayName: existing?.displayName ?? catalogItemId,
    quantity: existing?.quantity ?? 1,
    unitPrice: existing?.unitPrice ?? { currency: "XXX", minorUnits: 0 },
    totalPrice: existing?.totalPrice ?? { currency: "XXX", minorUnits: 0 },
    validFrom: existing?.validFrom ?? expiredAt,
    expiresAt: existing?.expiresAt ?? expiredAt,
    status: "EXPIRED",
    expiredAt,
  });
}

async function buildRiskDisclosures(repository: UpstreamStateRepository, itineraryRef: string, travelerRefs: readonly string[], at: Date): Promise<readonly RiskDisclosure[]> {
  const disclosures: RiskDisclosure[] = [];
  const evaluation = await repository.findTransferPlanEvaluation(itineraryRef);
  if (evaluation) {
    disclosures.push({
      disclosureId: `transfer-plan:${evaluation.transferPlanId}:${evaluation.evaluationVersion}`,
      severity: evaluation.status === "UNSERVICEABLE" ? "blocking" : "info",
      messageCode: evaluation.status === "UNSERVICEABLE" ? "TRANSFER_PLAN_UNSERVICEABLE" : "TRANSFER_PLAN_EVALUATED",
      templateVersion: "transfer-management.v1",
      relatedRef: evaluation.transferPlanId,
      mustAccept: evaluation.status === "UNSERVICEABLE",
      text:
        evaluation.status === "UNSERVICEABLE"
          ? `Transfer plan ${evaluation.transferPlanId} is unserviceable: ${evaluation.unserviceableReasons.join(", ") || "UNSERVICEABLE"}.`
          : `Transfer plan ${evaluation.transferPlanId} was evaluated with policy ${evaluation.riskPolicyVersion}.`,
    });
    const contracts = await repository.findConnectionContracts(evaluation.connectionIds);
    for (const contract of contracts) {
      disclosures.push({
        disclosureId: `connection-contract:${contract.connectionContractId}`,
        severity: contract.status === "REJECTED" ? "warning" : "info",
        messageCode: `CONNECTION_CONTRACT_${contract.status}`,
        templateVersion: contract.disclosureVersion,
        relatedRef: contract.connectionId,
        mustAccept: contract.status !== "REJECTED",
        text: contract.coverageSummary,
      });
    }
  }

  const activeMctRules = (await repository.findPublishedMctRules()).filter((rule) => mctRuleIsActive(rule, at));
  for (const rule of activeMctRules.slice(0, 10)) {
    disclosures.push({
      disclosureId: `mct-rule:${rule.mctRuleId}:${rule.version}`,
      severity: "info",
      messageCode: "MCT_RULE_PUBLISHED",
      templateVersion: "transfer-management.v1",
      relatedRef: rule.mctRuleId,
      mustAccept: false,
      text: `${rule.transferCategory} minimum connection time is ${rule.minimumMinutes} minutes.`,
    });
  }

  const activeCatalogItems = (await repository.findPublishedAncillaryCatalogItems()).filter((item) => catalogItemIsInSalesWindow(item, at));
  for (const item of activeCatalogItems.slice(0, 10)) {
    disclosures.push({
      disclosureId: `ancillary-catalog:${item.catalogItemId}:${item.version}`,
      severity: "info",
      messageCode: "ANCILLARY_CATALOG_ITEM_AVAILABLE",
      templateVersion: item.eligibilityRuleVersion ?? "ancillary-service.v1",
      relatedRef: item.catalogItemId,
      mustAccept: false,
      text: `${item.displayName ?? item.catalogItemId} ancillary service is available for quote display.`,
    });
  }

  const quotedAncillaryOffers = (await repository.findAncillaryOffers(travelerRefs)).filter((offer) => offer.status === "QUOTED" && offer.expiresAt.getTime() > at.getTime());
  for (const offer of quotedAncillaryOffers) {
    disclosures.push({
      disclosureId: `ancillary-offer:${offer.ancillaryOfferId}:${offer.offerVersion}`,
      severity: "info",
      messageCode: "ANCILLARY_OFFER_QUOTED",
      templateVersion: "ancillary-service.v1",
      relatedRef: offer.catalogItemId,
      mustAccept: false,
      text: `${offer.displayName} ancillary offer is available until ${offer.expiresAt.toISOString()}.`,
    });
  }

  return disclosures;
}

function mctRuleIsActive(rule: StoredMctRule, at: Date): boolean {
  return rule.validFrom.getTime() <= at.getTime() && (!rule.validUntil || rule.validUntil.getTime() > at.getTime());
}

function catalogItemIsInSalesWindow(item: StoredAncillaryCatalogItem, at: Date): boolean {
  const startAt = item.salesWindow?.startAt ? new Date(item.salesWindow.startAt) : undefined;
  const endAt = item.salesWindow?.endAt ? new Date(item.salesWindow.endAt) : undefined;
  return (!startAt || Number.isNaN(startAt.getTime()) || startAt.getTime() <= at.getTime()) && (!endAt || Number.isNaN(endAt.getTime()) || endAt.getTime() > at.getTime());
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

export function fareQuoteStorageKey(inputHash: string, channelId: string, travelerRefs: readonly string[]): string {
  return `${inputHash}:${channelId}:${travelerSetHash(travelerRefs)}`;
}

function fareQuoteKey(inputHash: string, channelId: string, travelerRefs: readonly string[]): string {
  return fareQuoteStorageKey(inputHash, channelId, travelerRefs);
}

export function travelerSetHash(travelerRefs: readonly string[]): string {
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

function booleanField(source: unknown, key: string): boolean | undefined {
  if (!source || typeof source !== "object") return undefined;
  const value = (source as Record<string, unknown>)[key];
  return typeof value === "boolean" ? value : undefined;
}

function numberField(source: unknown, key: string): number | undefined {
  if (!source || typeof source !== "object") return undefined;
  const value = (source as Record<string, unknown>)[key];
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
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

function transferPlanStatus(value: string | undefined): StoredTransferPlanEvaluation["status"] | undefined {
  return value === "EVALUATED" || value === "UNSERVICEABLE" ? value : undefined;
}

function connectionContractStatus(value: string | undefined): StoredConnectionContract["status"] | undefined {
  return value === "PROPOSED" || value === "ELIGIBLE" || value === "REJECTED" ? value : undefined;
}

function ancillaryCatalogStatus(value: string | undefined): StoredAncillaryCatalogItem["status"] | undefined {
  return value === "PUBLISHED" || value === "SUSPENDED" || value === "SUPERSEDED" ? value : undefined;
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
