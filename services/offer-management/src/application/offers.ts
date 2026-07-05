import {
  DomainError,
  Offer,
  type Money,
  type OfferDomainEvent,
  type OfferItem,
  type OfferSnapshot,
  type PriceGuaranteeLevel,
  type PriceSnapshot,
  type QuoteOfferCommand,
  type RiskDisclosure,
  type TravelerType,
} from "../domain.js";
import { type EventEnvelope, type EventPublisher } from "../ports/messaging.js";

export type QuoteOfferRequest = Readonly<{
  accountId: string;
  channelId: string;
  itineraryRef: string;
  travelerRefs: readonly string[];
  quoteRequestId?: string;
}>;

export type ApiMoney = Readonly<{
  currency: string;
  minorUnits: number;
}>;

export type OfferQuoteResponse = Readonly<{
  offerId: string;
  offerVersion: number;
  total: ApiMoney;
  expiresAt: string;
  priceGuaranteeLevel: ApiPriceGuaranteeLevel;
  downstreamReference: Readonly<{
    offerId: string;
    offerVersion: number;
    priceSnapshotRef: string;
    ruleSnapshotRef: string;
  }>;
  itineraryRef: string;
  travelerSetHash: string;
}>;

export type OfferDetailsResponse = OfferQuoteResponse & Readonly<{
  status: string;
  accountId: string;
  channelId: string;
  quoteRequestId: string;
  items: readonly Record<string, unknown>[];
  priceSnapshot: Record<string, unknown>;
  passengerMix: Record<string, unknown>;
  validityWindow: Record<string, unknown>;
  riskDisclosures: readonly Record<string, unknown>[];
  createdAt: string;
}>;

type ApiPriceGuaranteeLevel = "FIXED_UNTIL_EXPIRY" | "ESTIMATED_ONLY" | "PROVIDER_FINAL_CONFIRM_REQUIRED";

type QuoteOfferResult = Readonly<{
  response: OfferQuoteResponse;
  event: EventEnvelope;
}>;

export interface OfferRepository {
  save(snapshot: OfferSnapshot): Promise<void>;
  findById(offerId: string): Promise<OfferSnapshot | undefined>;
  clear(): void;
}

export class InMemoryOfferRepository implements OfferRepository {
  private readonly offers = new Map<string, OfferSnapshot>();

  async save(snapshot: OfferSnapshot): Promise<void> {
    this.offers.set(snapshot.offerId, snapshot);
  }

  async findById(offerId: string): Promise<OfferSnapshot | undefined> {
    return this.offers.get(offerId);
  }

  clear(): void {
    this.offers.clear();
  }
}

type QuoteCommandFactory = (request: QuoteOfferRequest) => QuoteOfferCommand;

export class OfferApplicationService {
  constructor(
    private readonly repository: OfferRepository,
    private readonly publisher?: EventPublisher,
    private readonly quoteCommandFactory: QuoteCommandFactory = toQuoteOfferCommand,
  ) {}

  async quoteOffer(request: QuoteOfferRequest, correlationId: string): Promise<QuoteOfferResult> {
    const quoted = Offer.quote(this.quoteCommandFactory(request));
    const snapshot = quoted.offer.toSnapshot();
    await this.repository.save(snapshot);

    const envelope = toEventEnvelope(quoted.event, correlationId);
    await this.publisher?.publish(envelope);

    return {
      response: toQuoteResponse(snapshot, quoted.event.downstreamReference),
      event: envelope,
    };
  }

  async getOffer(offerId: string): Promise<OfferDetailsResponse | undefined> {
    const snapshot = await this.repository.findById(offerId);
    return snapshot ? toDetailsResponse(snapshot) : undefined;
  }
}

export function isDomainError(error: unknown): error is DomainError {
  return error instanceof DomainError;
}

export function moneyToApi(money: Money): ApiMoney {
  return { currency: money.currency, minorUnits: money.amountMinor };
}

function moneyFromApi(money: ApiMoney): Money {
  return { currency: money.currency, amountMinor: money.minorUnits };
}

function toQuoteOfferCommand(request: QuoteOfferRequest): QuoteOfferCommand {
  const quotedAt = new Date();
  const expiresAt = new Date(quotedAt.getTime() + 10 * 60 * 1000);
  const upstreamExpiresAt = new Date(quotedAt.getTime() + 15 * 60 * 1000);
  const travelerSetHash = [...request.travelerRefs].sort().join(",");
  const offerId = `off-${uuidV7()}`;
  const currency = "CNY";
  const itemAmount = 10000;
  const total = moneyFromApi({ currency, minorUnits: itemAmount });
  const fareQuoteRef = `fq-${uuidV7()}`;
  const ruleSnapshotRef = `rule-${uuidV7()}`;
  const priceSnapshotRef = `ps-${uuidV7()}`;
  const availabilitySnapshotRef = `av-${uuidV7()}`;

  const item: OfferItem = {
    offerItemId: `ofi-${uuidV7()}`,
    mode: "train",
    segmentRef: `${request.itineraryRef}:segment:1`,
    itemPrice: total,
    availabilitySnapshot: {
      snapshotId: availabilitySnapshotRef,
      snapshotVersion: "capacity-v1",
      sourceContext: "CapacityAvailability",
      capturedAt: quotedAt,
      expiresAt: upstreamExpiresAt,
      sellable: true,
      status: "AVAILABLE",
      confidence: "confirmed-snapshot",
    },
    fareSnapshot: {
      fareQuoteRef,
      ruleSnapshotRef,
      pricingVersion: "pricing-v1",
      ruleVersion: "rule-v1",
      sourceContext: "FarePricing",
      capturedAt: quotedAt,
      expiresAt: upstreamExpiresAt,
    },
  };

  const priceSnapshot: PriceSnapshot = {
    snapshotId: priceSnapshotRef,
    fareQuoteRef,
    capturedAt: quotedAt,
    expiresAt: upstreamExpiresAt,
    guaranteeLevel: "FixedUntilExpiry",
    currency,
    subtotal: total,
    taxes: [],
    fees: [],
    discounts: [],
    total,
  };

  return {
    offerId,
    quoteRequestId: request.quoteRequestId ?? `cmd-${uuidV7()}`,
    accountId: request.accountId,
    channelId: request.channelId,
    quotedAt,
    validityWindow: {
      startsAt: quotedAt,
      expiresAt,
    },
    itinerary: {
      itineraryId: request.itineraryRef,
      itineraryVersion: "v1",
      sourceContext: "TripPlanning",
      segmentRefs: [item.segmentRef ?? request.itineraryRef],
    },
    passengerMix: {
      travelerSetHash,
      travelers: request.travelerRefs.map((travelerId) => ({
        travelerId,
        travelerType: inferTravelerType(travelerId),
      })),
    },
    items: [item],
    priceSnapshot,
    riskDisclosures: [],
  };
}

function inferTravelerType(_travelerId: string): TravelerType {
  return "ADULT";
}

function toQuoteResponse(
  snapshot: OfferSnapshot,
  downstreamReference: OfferQuoteResponse["downstreamReference"],
): OfferQuoteResponse {
  return {
    offerId: snapshot.offerId,
    offerVersion: snapshot.offerVersion,
    total: moneyToApi(snapshot.priceSnapshot.total),
    expiresAt: toUtcIso(snapshot.validityWindow.expiresAt),
    priceGuaranteeLevel: priceGuaranteeToApi(snapshot.priceSnapshot.guaranteeLevel),
    downstreamReference,
    itineraryRef: snapshot.itinerary.itineraryId,
    travelerSetHash: snapshot.passengerMix.travelerSetHash,
  };
}

function toDetailsResponse(snapshot: OfferSnapshot): OfferDetailsResponse {
  return {
    ...toQuoteResponse(snapshot, {
      offerId: snapshot.offerId,
      offerVersion: snapshot.offerVersion,
      priceSnapshotRef: snapshot.priceSnapshot.snapshotId,
      ruleSnapshotRef: snapshot.items[0]?.fareSnapshot.ruleSnapshotRef ?? "",
    }),
    status: snapshot.status,
    accountId: snapshot.accountId,
    channelId: snapshot.channelId,
    quoteRequestId: snapshot.quoteRequestId,
    items: snapshot.items.map(offerItemToApi),
    priceSnapshot: priceSnapshotToApi(snapshot.priceSnapshot),
    passengerMix: passengerMixToApi(snapshot),
    validityWindow: {
      startsAt: toUtcIso(snapshot.validityWindow.startsAt),
      expiresAt: toUtcIso(snapshot.validityWindow.expiresAt),
    },
    riskDisclosures: snapshot.riskDisclosures.map(riskDisclosureToApi),
    createdAt: toUtcIso(snapshot.quotedAt),
  };
}

function toEventEnvelope(event: OfferDomainEvent, correlationId: string): EventEnvelope {
  return {
    eventId: event.eventId,
    eventType: event.eventType,
    schemaVersion: event.schemaVersion,
    producer: event.producer,
    causationId: event.causationId,
    correlationId,
    occurredAt: toUtcIso(event.occurredAt),
    payload: eventPayloadToApi(event),
  };
}

function eventPayloadToApi(event: OfferDomainEvent): Record<string, unknown> {
  if (event.type === "OfferQuoted") {
    return {
      offerId: event.offerId,
      offerVersion: event.offerVersion,
      quoteRequestId: event.quoteRequestId,
      accountId: event.accountId,
      channelId: event.channelId,
      itineraryId: event.itineraryId,
      itineraryVersion: event.itineraryVersion,
      travelerSetHash: event.travelerSetHash,
      availabilitySnapshotRefs: event.availabilitySnapshotRefs,
      priceSnapshotRef: event.priceSnapshotRef,
      fareQuoteRefs: event.fareQuoteRefs,
      ruleSnapshotRefs: event.ruleSnapshotRefs,
      total: moneyToApi(event.total),
      expiresAt: toUtcIso(event.expiresAt),
      priceGuaranteeLevel: priceGuaranteeToApi(event.priceGuaranteeLevel),
      downstreamReference: event.downstreamReference,
      boundaryProof: event.boundaryProof,
    };
  }

  return {
    offerId: event.offerId,
    offerVersion: event.offerVersion,
    expiredAt: toUtcIso(event.expiredAt),
    previousStatus: event.previousStatus,
    reason: event.reason,
    boundaryProof: event.boundaryProof,
  };
}

function offerItemToApi(item: OfferItem): Record<string, unknown> {
  return {
    offerItemId: item.offerItemId,
    mode: item.mode,
    segmentRef: item.segmentRef,
    fareSnapshot: {
      ...item.fareSnapshot,
      capturedAt: toUtcIso(item.fareSnapshot.capturedAt),
      expiresAt: toUtcIso(item.fareSnapshot.expiresAt),
    },
    availabilitySnapshot: {
      ...item.availabilitySnapshot,
      capturedAt: toUtcIso(item.availabilitySnapshot.capturedAt),
      expiresAt: toUtcIso(item.availabilitySnapshot.expiresAt),
    },
    itemPrice: moneyToApi(item.itemPrice),
  };
}

function priceSnapshotToApi(snapshot: PriceSnapshot): Record<string, unknown> {
  return {
    snapshotId: snapshot.snapshotId,
    fareQuoteRef: snapshot.fareQuoteRef,
    capturedAt: toUtcIso(snapshot.capturedAt),
    expiresAt: toUtcIso(snapshot.expiresAt),
    guaranteeLevel: priceGuaranteeToApi(snapshot.guaranteeLevel),
    currency: snapshot.currency,
    subtotal: moneyToApi(snapshot.subtotal),
    taxes: snapshot.taxes.map(priceLineToApi),
    fees: snapshot.fees.map(priceLineToApi),
    discounts: snapshot.discounts.map(priceLineToApi),
    total: moneyToApi(snapshot.total),
  };
}

function priceLineToApi(line: PriceSnapshot["taxes"][number]): Record<string, unknown> {
  return {
    code: line.code,
    description: line.description,
    amount: moneyToApi(line.amount),
  };
}

function passengerMixToApi(snapshot: OfferSnapshot): Record<string, unknown> {
  return {
    travelerSetHash: snapshot.passengerMix.travelerSetHash,
    travelers: snapshot.passengerMix.travelers,
  };
}

function riskDisclosureToApi(disclosure: RiskDisclosure): Record<string, unknown> {
  return { ...disclosure };
}

function priceGuaranteeToApi(level: PriceGuaranteeLevel): ApiPriceGuaranteeLevel {
  switch (level) {
    case "FixedUntilExpiry":
      return "FIXED_UNTIL_EXPIRY";
    case "EstimatedOnly":
      return "ESTIMATED_ONLY";
    case "ProviderFinalConfirmRequired":
      return "PROVIDER_FINAL_CONFIRM_REQUIRED";
  }
}

function toUtcIso(value: Date): string {
  return value.toISOString();
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
