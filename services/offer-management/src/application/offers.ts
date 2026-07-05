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
} from "../domain.js";
import { type EventEnvelope, type EventPublisher } from "../ports/messaging.js";
import { type QuoteOfferRequest } from "./upstream-state.js";

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

type QuoteCommandFactory = (request: QuoteOfferRequest) => QuoteOfferCommand | Promise<QuoteOfferCommand>;

export class OfferApplicationService {
  constructor(
    private readonly repository: OfferRepository,
    private readonly publisher: EventPublisher | undefined,
    private readonly quoteCommandFactory: QuoteCommandFactory,
  ) {}

  async quoteOffer(request: QuoteOfferRequest, correlationId: string): Promise<QuoteOfferResult> {
    const quoted = Offer.quote(await this.quoteCommandFactory(request));
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
    };
  }

  return {
    offerId: event.offerId,
    offerVersion: event.offerVersion,
    expiredAt: toUtcIso(event.expiredAt),
    previousStatus: offerStatusToApi(event.previousStatus),
    reason: event.reason,
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

function offerStatusToApi(status: string): "QUOTED" | "ACCEPTED" {
  switch (status) {
    case "Quoted":
      return "QUOTED";
    case "Accepted":
      return "ACCEPTED";
    default:
      throw new DomainError("INVALID_EXPIRABLE_STATUS", `OfferExpired previousStatus ${status} is not publishable`);
  }
}

function toUtcIso(value: Date): string {
  return value.toISOString();
}
