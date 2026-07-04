export class DomainError extends Error {
  public readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = "DomainError";
    this.code = code;
  }
}

export type OfferId = string;
export type OfferVersion = number;
export type OfferItemId = string;
export type ItineraryId = string;
export type SegmentId = string;
export type TravelerId = string;
export type SnapshotId = string;
export type CurrencyCode = string;

export type OfferStatus = "Quoted" | "Accepted" | "Expired" | "Unavailable" | "Requoted" | "Withdrawn";
export type PriceGuaranteeLevel = "FixedUntilExpiry" | "EstimatedOnly" | "ProviderFinalConfirmRequired";
export type TransportMode = "train" | "transfer" | "ancillary";
export type TravelerType = "ADULT" | "CHILD" | "STUDENT" | "SENIOR" | "INFANT" | "MILITARY" | "DISABLED";
export type RiskSeverity = "info" | "warning" | "blocking";
export type AvailabilityConfidence = "confirmed-snapshot" | "low" | "estimated";

export type Money = Readonly<{
  amountMinor: number;
  currency: CurrencyCode;
}>;

export type ValidityWindow = Readonly<{
  startsAt: Date;
  expiresAt: Date;
}>;

export type ItineraryReference = Readonly<{
  itineraryId: ItineraryId;
  itineraryVersion: string;
  sourceContext: "TripPlanning";
  segmentRefs: readonly SegmentId[];
}>;

export type AvailabilitySnapshotReference = Readonly<{
  snapshotId: SnapshotId;
  snapshotVersion: string;
  sourceContext: "CapacityAvailability";
  capturedAt: Date;
  expiresAt: Date;
  sellable: boolean;
  status: "AVAILABLE" | "LIMITED" | "UNKNOWN" | "UNAVAILABLE";
  confidence: AvailabilityConfidence;
}>;

export type FareRuleSnapshotReference = Readonly<{
  fareQuoteRef: SnapshotId;
  ruleSnapshotRef: SnapshotId;
  pricingVersion: string;
  ruleVersion: string;
  sourceContext: "FarePricing";
  capturedAt: Date;
  expiresAt: Date;
}>;

export type PriceLine = Readonly<{
  code: string;
  description: string;
  amount: Money;
}>;

export type PriceSnapshot = Readonly<{
  snapshotId: SnapshotId;
  fareQuoteRef: SnapshotId;
  capturedAt: Date;
  expiresAt: Date;
  guaranteeLevel: PriceGuaranteeLevel;
  currency: CurrencyCode;
  subtotal: Money;
  taxes: readonly PriceLine[];
  fees: readonly PriceLine[];
  discounts: readonly PriceLine[];
  total: Money;
}>;

export type TravelerRef = Readonly<{
  travelerId: TravelerId;
  travelerType: TravelerType;
  maskedDocumentRef?: string;
  eligibilityRef?: Readonly<{
    eligibilityId: string;
    eligibilityType: string;
    eligibilitySource: string;
    evidenceHash?: string;
    verifiedAt?: string;
  }>;
}>;

export type PassengerMix = Readonly<{
  travelerSetHash: string;
  travelers: readonly TravelerRef[];
}>;

export type RiskDisclosure = Readonly<{
  disclosureId: string;
  severity: RiskSeverity;
  messageCode: string;
  templateVersion: string;
  relatedRef?: string;
  mustAccept: boolean;
  text: string;
}>;

export type OfferItem = Readonly<{
  offerItemId: OfferItemId;
  mode: TransportMode;
  segmentRef?: SegmentId;
  fareSnapshot: FareRuleSnapshotReference;
  availabilitySnapshot: AvailabilitySnapshotReference;
  itemPrice: Money;
}>;

export type QuoteOfferCommand = Readonly<{
  offerId: OfferId;
  offerVersion?: OfferVersion;
  quoteRequestId: string;
  accountId: string;
  channelId: string;
  itinerary: ItineraryReference;
  passengerMix: PassengerMix;
  validityWindow: ValidityWindow;
  items: readonly OfferItem[];
  priceSnapshot: PriceSnapshot;
  riskDisclosures?: readonly RiskDisclosure[];
  quotedAt: Date;
}>;

export type OfferQuoted = Readonly<{
  type: "OfferQuoted";
  eventId: string;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: string;
  causationId?: string;
  producer: string;
  offerId: OfferId;
  offerVersion: OfferVersion;
  quoteRequestId: string;
  accountId: string;
  channelId: string;
  itineraryId: ItineraryId;
  itineraryVersion: string;
  travelerSetHash: string;
  availabilitySnapshotRefs: readonly SnapshotId[];
  priceSnapshotRef: SnapshotId;
  fareQuoteRefs: readonly SnapshotId[];
  ruleSnapshotRefs: readonly SnapshotId[];
  total: Money;
  expiresAt: Date;
  priceGuaranteeLevel: PriceGuaranteeLevel;
  downstreamReference: Readonly<{
    offerId: OfferId;
    offerVersion: OfferVersion;
    priceSnapshotRef: SnapshotId;
    ruleSnapshotRef: SnapshotId;
  }>;
  boundaryProof: BoundaryProof;
}>;

export type OfferExpired = Readonly<{
  type: "OfferExpired";
  eventId: string;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: string;
  causationId?: string;
  producer: string;
  offerId: OfferId;
  offerVersion: OfferVersion;
  expiredAt: Date;
  previousStatus: OfferStatus;
  reason: "VALIDITY_WINDOW_ELAPSED" | "EXPLICIT_EXPIRE_COMMAND";
  boundaryProof: BoundaryProof;
}>;

export type OfferDomainEvent = OfferQuoted | OfferExpired;

export type BoundaryProof = Readonly<{
  inventoryLocked: false;
  capacityHoldMutated: false;
  paymentIntentMutated: false;
  journeyOrderMutated: false;
  entitlementMutated: false;
  crossContextWriteTargets: readonly [];
}>;

export type OfferAcceptanceToken = Readonly<{
  offerId: OfferId;
  offerVersion: OfferVersion;
  priceSnapshotRef: SnapshotId;
  ruleSnapshotRef: SnapshotId;
  travelerSetHash: string;
  acceptedAt: Date;
}>;

export type ValidateOfferForOrderRequest = Readonly<{
  accountId: string;
  channelId: string;
  travelerSetHash: string;
  priceSnapshotRef: SnapshotId;
  at: Date;
  acceptedDisclosureIds?: readonly string[];
}>;

export type OfferSnapshot = Readonly<{
  offerId: OfferId;
  offerVersion: OfferVersion;
  status: OfferStatus;
  accountId: string;
  channelId: string;
  quoteRequestId: string;
  itinerary: ItineraryReference;
  passengerMix: PassengerMix;
  validityWindow: ValidityWindow;
  items: readonly OfferItem[];
  priceSnapshot: PriceSnapshot;
  riskDisclosures: readonly RiskDisclosure[];
  quotedAt: Date;
  expiredAt?: Date;
}>;

const boundaryProof: BoundaryProof = Object.freeze({
  inventoryLocked: false,
  capacityHoldMutated: false,
  paymentIntentMutated: false,
  journeyOrderMutated: false,
  entitlementMutated: false,
  crossContextWriteTargets: Object.freeze([]) as readonly [],
});

export function nonMutationBoundaryProof(): BoundaryProof {
  return boundaryProof;
}

export class Offer {
  private constructor(private readonly snapshot: OfferSnapshot) {
    deepFreeze(this.snapshot);
    Object.freeze(this);
  }

  static quote(command: QuoteOfferCommand): { offer: Offer; event: OfferQuoted } {
    validateQuoteCommand(command);

    const offerVersion = command.offerVersion ?? 1;
    const snapshot: OfferSnapshot = deepFreeze(cloneForSnapshot({
      offerId: command.offerId,
      offerVersion,
      status: "Quoted" as const,
      accountId: command.accountId,
      channelId: command.channelId,
      quoteRequestId: command.quoteRequestId,
      itinerary: command.itinerary,
      passengerMix: command.passengerMix,
      validityWindow: command.validityWindow,
      items: command.items,
      priceSnapshot: command.priceSnapshot,
      riskDisclosures: command.riskDisclosures ?? [],
      quotedAt: command.quotedAt,
    }));

    const offer = new Offer(snapshot);
    const event: OfferQuoted = deepFreeze({
      type: "OfferQuoted" as const,
      eventId: `evt-${crypto.randomUUID()}`,
      eventType: "OfferQuoted",
      schemaVersion: 1,
      occurredAt: new Date(command.quotedAt),
      correlationId: `corr-${crypto.randomUUID()}`,
      producer: "offer-management",
      offerId: snapshot.offerId,
      offerVersion: snapshot.offerVersion,
      quoteRequestId: snapshot.quoteRequestId,
      accountId: snapshot.accountId,
      channelId: snapshot.channelId,
      itineraryId: snapshot.itinerary.itineraryId,
      itineraryVersion: snapshot.itinerary.itineraryVersion,
      travelerSetHash: snapshot.passengerMix.travelerSetHash,
      availabilitySnapshotRefs: snapshot.items.map((item) => item.availabilitySnapshot.snapshotId),
      priceSnapshotRef: snapshot.priceSnapshot.snapshotId,
      fareQuoteRefs: unique(snapshot.items.map((item) => item.fareSnapshot.fareQuoteRef)),
      ruleSnapshotRefs: unique(snapshot.items.map((item) => item.fareSnapshot.ruleSnapshotRef)),
      total: snapshot.priceSnapshot.total,
      expiresAt: new Date(snapshot.validityWindow.expiresAt),
      priceGuaranteeLevel: snapshot.priceSnapshot.guaranteeLevel,
      downstreamReference: {
        offerId: snapshot.offerId,
        offerVersion: snapshot.offerVersion,
        priceSnapshotRef: snapshot.priceSnapshot.snapshotId,
        ruleSnapshotRef: unique(snapshot.items.map((item) => item.fareSnapshot.ruleSnapshotRef))[0] ?? "",
      },
      boundaryProof,
    });

    return { offer, event };
  }

  get id(): OfferId {
    return this.snapshot.offerId;
  }

  get version(): OfferVersion {
    return this.snapshot.offerVersion;
  }

  get status(): OfferStatus {
    return this.snapshot.status;
  }

  get priceSnapshotRef(): SnapshotId {
    return this.snapshot.priceSnapshot.snapshotId;
  }

  isExpiredAt(at: Date): boolean {
    return at.getTime() >= this.snapshot.validityWindow.expiresAt.getTime();
  }

  expire(at: Date, reason: OfferExpired["reason"] = "EXPLICIT_EXPIRE_COMMAND"): { offer: Offer; event: OfferExpired } {
    if (!this.isExpirable()) {
      throw new DomainError("OFFER_NOT_EXPIRABLE", `Offer ${this.id} in ${this.status} cannot expire`);
    }
    if (at.getTime() < this.snapshot.validityWindow.expiresAt.getTime() && reason === "VALIDITY_WINDOW_ELAPSED") {
      throw new DomainError("OFFER_NOT_YET_EXPIRED", "Cannot expire by elapsed validity before expiresAt");
    }

    const expiredSnapshot = deepFreeze(cloneForSnapshot({
      ...this.snapshot,
      status: "Expired" as const,
      expiredAt: at,
    }));
    const event: OfferExpired = deepFreeze({
      type: "OfferExpired" as const,
      eventId: `evt-${crypto.randomUUID()}`,
      eventType: "OfferExpired",
      schemaVersion: 1,
      occurredAt: new Date(at),
      correlationId: `corr-${crypto.randomUUID()}`,
      producer: "offer-management",
      offerId: this.snapshot.offerId,
      offerVersion: this.snapshot.offerVersion,
      expiredAt: new Date(at),
      previousStatus: this.snapshot.status,
      reason,
      boundaryProof,
    });
    return { offer: new Offer(expiredSnapshot), event };
  }

  validateForOrder(request: ValidateOfferForOrderRequest): OfferAcceptanceToken {
    if (this.snapshot.status !== "Quoted" && this.snapshot.status !== "Accepted") {
      throw new DomainError("OFFER_NOT_ACCEPTABLE", `Offer ${this.id} in ${this.status} cannot be used for order creation`);
    }
    if (this.isExpiredAt(request.at)) {
      throw new DomainError("OFFER_EXPIRED", `Offer ${this.id} expired at ${this.snapshot.validityWindow.expiresAt.toISOString()}`);
    }
    if (request.accountId !== this.snapshot.accountId) {
      throw new DomainError("ACCOUNT_MISMATCH", "Offer account does not match order request");
    }
    if (request.channelId !== this.snapshot.channelId) {
      throw new DomainError("CHANNEL_MISMATCH", "Offer channel does not match order request");
    }
    if (request.travelerSetHash !== this.snapshot.passengerMix.travelerSetHash) {
      throw new DomainError("TRAVELER_SET_MISMATCH", "Offer passenger mix does not match order request");
    }
    if (request.priceSnapshotRef !== this.snapshot.priceSnapshot.snapshotId) {
      throw new DomainError("PRICE_SNAPSHOT_MISMATCH", "Order must reference the immutable price snapshot on the Offer");
    }

    const accepted = new Set(request.acceptedDisclosureIds ?? []);
    const missingDisclosure = this.snapshot.riskDisclosures.find((disclosure) => disclosure.mustAccept && !accepted.has(disclosure.disclosureId));
    if (missingDisclosure) {
      throw new DomainError("DISCLOSURE_NOT_ACCEPTED", `Required disclosure ${missingDisclosure.disclosureId} was not accepted`);
    }

    return deepFreeze({
      offerId: this.snapshot.offerId,
      offerVersion: this.snapshot.offerVersion,
      priceSnapshotRef: this.snapshot.priceSnapshot.snapshotId,
      ruleSnapshotRef: unique(this.snapshot.items.map((item) => item.fareSnapshot.ruleSnapshotRef))[0] ?? "",
      travelerSetHash: this.snapshot.passengerMix.travelerSetHash,
      acceptedAt: new Date(request.at),
    });
  }

  toSnapshot(): OfferSnapshot {
    return deepFreeze(cloneForSnapshot(this.snapshot));
  }

  private isExpirable(): boolean {
    return this.snapshot.status === "Quoted" || this.snapshot.status === "Accepted";
  }
}

function validateQuoteCommand(command: QuoteOfferCommand): void {
  requireNonBlank(command.offerId, "offerId");
  requireNonBlank(command.quoteRequestId, "quoteRequestId");
  requireNonBlank(command.accountId, "accountId");
  requireNonBlank(command.channelId, "channelId");
  validateItinerary(command.itinerary);
  validatePassengerMix(command.passengerMix);
  validateValidityWindow(command.validityWindow, command.quotedAt);
  validatePriceSnapshot(command.priceSnapshot, command.quotedAt, command.validityWindow.expiresAt);
  validateOfferItems(command.items, command.quotedAt, command.validityWindow.expiresAt, command.priceSnapshot.currency);
  validatePriceTotals(command.priceSnapshot, command.items);
  for (const disclosure of command.riskDisclosures ?? []) {
    validateRiskDisclosure(disclosure);
  }
}

function validateItinerary(itinerary: ItineraryReference | undefined): void {
  if (!itinerary) {
    throw new DomainError("MISSING_ITINERARY", "Offer must reference a Trip Planning itinerary");
  }
  requireNonBlank(itinerary.itineraryId, "itineraryId");
  requireNonBlank(itinerary.itineraryVersion, "itineraryVersion");
  if (itinerary.sourceContext !== "TripPlanning") {
    throw new DomainError("INVALID_ITINERARY_SOURCE", "Itinerary reference must come from Trip Planning");
  }
  if (itinerary.segmentRefs.length === 0) {
    throw new DomainError("MISSING_SEGMENTS", "Itinerary reference must include at least one segment reference");
  }
}

function validatePassengerMix(passengerMix: PassengerMix | undefined): void {
  if (!passengerMix) {
    throw new DomainError("MISSING_PASSENGER_MIX", "Offer must freeze the passenger mix used for pricing");
  }
  requireNonBlank(passengerMix.travelerSetHash, "travelerSetHash");
  if (passengerMix.travelers.length === 0) {
    throw new DomainError("MISSING_TRAVELERS", "Passenger mix must contain at least one traveler");
  }
  for (const traveler of passengerMix.travelers) {
    requireNonBlank(traveler.travelerId, "travelerId");
  }
}

function validateValidityWindow(window: ValidityWindow | undefined, quotedAt: Date): void {
  if (!window) {
    throw new DomainError("MISSING_VALIDITY_WINDOW", "Offer must have a validity window");
  }
  if (window.startsAt.getTime() > quotedAt.getTime()) {
    throw new DomainError("VALIDITY_START_IN_FUTURE", "Offer validity cannot start after quote time");
  }
  if (window.expiresAt.getTime() <= quotedAt.getTime()) {
    throw new DomainError("STALE_VALIDITY_WINDOW", "Offer validity must expire after quote time");
  }
}

function validatePriceSnapshot(snapshot: PriceSnapshot | undefined, quotedAt: Date, offerExpiresAt: Date): void {
  if (!snapshot) {
    throw new DomainError("MISSING_PRICE_SNAPSHOT", "Offer must freeze a PriceSnapshot");
  }
  requireNonBlank(snapshot.snapshotId, "priceSnapshot.snapshotId");
  requireNonBlank(snapshot.fareQuoteRef, "priceSnapshot.fareQuoteRef");
  requireNonBlank(snapshot.currency, "priceSnapshot.currency");
  validateMoney(snapshot.subtotal, snapshot.currency, "priceSnapshot.subtotal");
  validateMoney(snapshot.total, snapshot.currency, "priceSnapshot.total");
  if (snapshot.capturedAt.getTime() > quotedAt.getTime()) {
    throw new DomainError("PRICE_SNAPSHOT_FROM_FUTURE", "PriceSnapshot cannot be captured after quote time");
  }
  if (snapshot.expiresAt.getTime() <= quotedAt.getTime()) {
    throw new DomainError("STALE_PRICE_SNAPSHOT", "Cannot quote from an expired PriceSnapshot");
  }
  if (snapshot.expiresAt.getTime() < offerExpiresAt.getTime()) {
    throw new DomainError("PRICE_SNAPSHOT_TTL_TOO_SHORT", "Offer validity cannot outlive the PriceSnapshot TTL");
  }
  for (const line of [...snapshot.taxes, ...snapshot.fees, ...snapshot.discounts]) {
    requireNonBlank(line.code, "price line code");
    validateMoney(line.amount, snapshot.currency, `price line ${line.code}`);
  }
}

function validateOfferItems(items: readonly OfferItem[] | undefined, quotedAt: Date, offerExpiresAt: Date, currency: CurrencyCode): void {
  if (!items || items.length === 0) {
    throw new DomainError("MISSING_OFFER_ITEMS", "Offer must contain at least one OfferItem");
  }
  for (const item of items) {
    requireNonBlank(item.offerItemId, "offerItemId");
    validateMoney(item.itemPrice, currency, `itemPrice ${item.offerItemId}`);
    validateFareRuleSnapshot(item.fareSnapshot, quotedAt, offerExpiresAt, item.offerItemId);
    validateAvailabilitySnapshot(item.availabilitySnapshot, quotedAt, offerExpiresAt, item.offerItemId);
  }
}

function validateFareRuleSnapshot(snapshot: FareRuleSnapshotReference | undefined, quotedAt: Date, offerExpiresAt: Date, itemId: string): void {
  if (!snapshot) {
    throw new DomainError("MISSING_FARE_RULE_SNAPSHOT", `OfferItem ${itemId} must reference fare/rule snapshots`);
  }
  requireNonBlank(snapshot.fareQuoteRef, "fareQuoteRef");
  requireNonBlank(snapshot.ruleSnapshotRef, "ruleSnapshotRef");
  requireNonBlank(snapshot.pricingVersion, "pricingVersion");
  requireNonBlank(snapshot.ruleVersion, "ruleVersion");
  if (snapshot.sourceContext !== "FarePricing") {
    throw new DomainError("INVALID_FARE_RULE_SOURCE", "Fare/rule snapshot must come from Fare & Pricing");
  }
  if (snapshot.expiresAt.getTime() <= quotedAt.getTime()) {
    throw new DomainError("STALE_FARE_RULE_SNAPSHOT", `Fare/rule snapshot for ${itemId} is expired`);
  }
  if (snapshot.expiresAt.getTime() < offerExpiresAt.getTime()) {
    throw new DomainError("FARE_RULE_TTL_TOO_SHORT", `Offer validity cannot outlive fare/rule snapshot for ${itemId}`);
  }
}

function validateAvailabilitySnapshot(snapshot: AvailabilitySnapshotReference | undefined, quotedAt: Date, offerExpiresAt: Date, itemId: string): void {
  if (!snapshot) {
    throw new DomainError("MISSING_AVAILABILITY_SNAPSHOT", `OfferItem ${itemId} must reference an AvailabilitySnapshot`);
  }
  requireNonBlank(snapshot.snapshotId, "availabilitySnapshot.snapshotId");
  requireNonBlank(snapshot.snapshotVersion, "availabilitySnapshot.snapshotVersion");
  if (snapshot.sourceContext !== "CapacityAvailability") {
    throw new DomainError("INVALID_AVAILABILITY_SOURCE", "Availability snapshot must come from Capacity & Availability");
  }
  if (!snapshot.sellable) {
    throw new DomainError("UNSELLABLE_AVAILABILITY_SNAPSHOT", `Availability snapshot for ${itemId} is not sellable`);
  }
  if (snapshot.expiresAt.getTime() <= quotedAt.getTime()) {
    throw new DomainError("STALE_AVAILABILITY_SNAPSHOT", `Availability snapshot for ${itemId} is expired`);
  }
  if (snapshot.expiresAt.getTime() < offerExpiresAt.getTime()) {
    throw new DomainError("AVAILABILITY_TTL_TOO_SHORT", `Offer validity cannot outlive availability snapshot for ${itemId}`);
  }
}

function validatePriceTotals(priceSnapshot: PriceSnapshot, items: readonly OfferItem[]): void {
  const itemTotal = sum(items.map((item) => item.itemPrice.amountMinor));
  const taxes = sum(priceSnapshot.taxes.map((line) => line.amount.amountMinor));
  const fees = sum(priceSnapshot.fees.map((line) => line.amount.amountMinor));
  const discounts = sum(priceSnapshot.discounts.map((line) => line.amount.amountMinor));
  const expected = roundMoney(itemTotal + taxes + fees - discounts);
  if (expected !== priceSnapshot.total.amountMinor) {
    throw new DomainError("PRICE_TOTAL_MISMATCH", `PriceSnapshot total ${priceSnapshot.total.amountMinor} does not match item/tax/fee/discount total ${expected}`);
  }
}

function validateRiskDisclosure(disclosure: RiskDisclosure): void {
  requireNonBlank(disclosure.disclosureId, "disclosureId");
  requireNonBlank(disclosure.messageCode, "messageCode");
  requireNonBlank(disclosure.templateVersion, "templateVersion");
  requireNonBlank(disclosure.text, "risk disclosure text");
}

function validateMoney(money: Money | undefined, expectedCurrency: CurrencyCode, label: string): void {
  if (!money) {
    throw new DomainError("MISSING_MONEY", `${label} is required`);
  }
  if (money.currency !== expectedCurrency) {
    throw new DomainError("CURRENCY_MISMATCH", `${label} currency ${money.currency} does not match ${expectedCurrency}`);
  }
  if (!Number.isFinite(money.amountMinor) || money.amountMinor < 0) {
    throw new DomainError("INVALID_MONEY", `${label} must be a finite non-negative amount`);
  }
}

function requireNonBlank(value: string | undefined, label: string): void {
  if (!value || value.trim().length === 0) {
    throw new DomainError("MISSING_REQUIRED_FIELD", `${label} is required`);
  }
}

function sum(values: readonly number[]): number {
  return roundMoney(values.reduce((total, value) => total + value, 0));
}

function roundMoney(value: number): number {
  // amountMinor is integer minor units; no float rounding needed
  return Math.round(value);
}

function unique<T>(values: readonly T[]): readonly T[] {
  return Object.freeze([...new Set(values)]);
}

type DeepFreezable = Record<string, unknown> | unknown[];

function deepFreeze<T>(value: T): T {
  if (value && typeof value === "object") {
    for (const nested of Object.values(value as DeepFreezable)) {
      deepFreeze(nested);
    }
    Object.freeze(value);
  }
  return value;
}

function cloneForSnapshot<T>(value: T): T {
  if (value instanceof Date) {
    return new Date(value) as T;
  }
  if (Array.isArray(value)) {
    return value.map((entry) => cloneForSnapshot(entry)) as T;
  }
  if (value && typeof value === "object") {
    const clone: Record<string, unknown> = {};
    for (const [key, nested] of Object.entries(value as Record<string, unknown>)) {
      clone[key] = cloneForSnapshot(nested);
    }
    return clone as T;
  }
  return value;
}
