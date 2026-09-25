import { createHash } from "node:crypto";
import { uuidV7 } from "@trainticket/ts-kit";

export type WaitlistStatus = "DRAFT" | "QUEUED" | "MATCHING" | "FULFILLED" | "EXPIRED" | "CANCELLED" | "SUSPENDED" | "CLOSED";
export type LoyaltyTier = "PLATINUM" | "GOLD" | "SILVER" | "NONE";
export type FareClass = "BUSINESS_CLASS" | "FIRST_CLASS" | "SECOND_CLASS" | "STANDING" | "SLEEPER_HARD" | "SLEEPER_SOFT";
export type FareClassInput = FareClass | "BUSINESS" | "FIRST" | "SECOND";
export type TravelInterval = Readonly<{ fromSeq: number; toSeq: number }>;
export type MoneySnapshot = Readonly<{ currency: string; minorUnits: number }>;
export type PaymentGuaranteeSnapshot = Readonly<{
  paymentIntentId: string;
  sourceOrderRef: string;
  payerRef: string;
  amount: MoneySnapshot;
  sourceStatus: "CREATED" | "AUTHORIZED";
  sourceExpiresAt: string;
  authorizedUntil: string;
  verifiedAt: string;
  preferredChannel?: string;
}>;
export type CapacityCreditAllocation = Readonly<{
  capacityReleaseRef: string;
  slots: number;
}>;
export type FulfillmentTraveler = Readonly<{
  travelerRef: string;
  segmentBookingId: string;
  reservationIdempotencyKey: string;
  capacityReleaseIdempotencyKey: string;
  entitlementIdempotencyKey: string;
  reservationStatus?: string;
  capacityHoldId?: string;
  entitlementId?: string;
}>;
export type FulfillmentProgress = Readonly<{
  bookingSagaRef?: string;
  orderAmount?: MoneySnapshot;
  travelers: readonly FulfillmentTraveler[];
  paymentCreateIdempotencyKey: string;
  paymentCaptureIdempotencyKey: string;
  paymentCancelIdempotencyKey: string;
  orderCancelIdempotencyKey: string;
  paymentIntentId?: string;
  paymentStatus?: string;
  nextActionAt?: string;
}>;
export type SpecialStatus = "MILITARY" | "DISABLED" | "STUDENT" | "NONE";

export type PriorityInput = Readonly<{
  loyaltyTier?: LoyaltyTier;
  tripCount?: number;
  daysBefore?: number;
  groupSize: number;
  fareClass: FareClassInput;
  specialStatus?: SpecialStatus | readonly SpecialStatus[];
}>;

export type WaitlistEntrySnapshot = Readonly<{
  entryId: string;
  accountId: string;
  travelerRefs: readonly string[];
  segmentRef: string;
  scheduledServiceRef: string;
  departureDate: string;
  seatClass: FareClass;
  interval: TravelInterval;
  stopSequenceVersion: string;
  priorityScore: number;
  status: WaitlistStatus;
  queuePosition: number;
  createdAt: string;
  offeredAt: string | null;
  offerExpiresAt: string | null;
  deadline: string;
  paymentGuaranteeRef: string;
  paymentGuarantee?: PaymentGuaranteeSnapshot;
  intentFingerprint: string;
  matchedCapacityCredits?: readonly CapacityCreditAllocation[];
  fulfillment?: FulfillmentProgress;
  fareQuoteId?: string;
  capacityHoldId?: string;
  offerId?: string;
  offerVersion?: number;
  itineraryRef?: string;
  journeyOrderRef?: string;
  fareQuoteIdempotencyKey?: string;
  offerIdempotencyKey?: string;
  capacityHoldIdempotencyKey?: string;
  capacityReleaseIdempotencyKey?: string;
  journeyOrderIdempotencyKey?: string;
  capacitySegmentBookingId?: string;
}>;

export type CreateWaitlistEntry = Readonly<{
  entryId?: string;
  accountId: string;
  travelerRefs: readonly string[];
  segmentRef: string;
  scheduledServiceRef?: string;
  departureDate: string;
  seatClass: FareClassInput;
  interval?: TravelInterval;
  stopSequenceVersion?: string;
  priority: Omit<PriorityInput, "groupSize" | "fareClass"> & Partial<Pick<PriorityInput, "groupSize" | "fareClass">>;
  itineraryRef?: string;
  deadline?: string;
  paymentGuaranteeRef?: string;
  paymentGuarantee?: PaymentGuaranteeSnapshot;
  intentFingerprint?: string;
  matchedCapacityCredits?: readonly CapacityCreditAllocation[];
  fulfillment?: FulfillmentProgress;
  createdAt?: Date;
}>;

export class DomainError extends Error {
  constructor(public readonly code: "VALIDATION_FAILED" | "INVALID_TRANSITION" | "NOT_FOUND" | "PRECONDITION_FAILED" | "CONFLICT", message: string) {
    super(message);
    this.name = "DomainError";
  }
}

export class WaitlistEntry {
  private constructor(
    public readonly entryId: string,
    public readonly accountId: string,
    public readonly travelerRefs: readonly string[],
    public readonly segmentRef: string,
    public readonly scheduledServiceRef: string,
    public readonly departureDate: string,
    public readonly seatClass: FareClass,
    public readonly interval: TravelInterval,
    public readonly stopSequenceVersion: string,
    public readonly priorityScore: number,
    public readonly createdAt: Date,
    private _status: WaitlistStatus,
    private _offeredAt: Date | null = null,
    private _offerExpiresAt: Date | null = null,
    private _fareQuoteId?: string,
    private _capacityHoldId?: string,
    private _offerId?: string,
    private _offerVersion?: number,
    public readonly itineraryRef?: string,
    public readonly deadline: string = deadlineFromDepartureDate(departureDate),
    public readonly paymentGuaranteeRef: string = `pi-${uuidV7()}`,
    public readonly paymentGuarantee: PaymentGuaranteeSnapshot = testPaymentGuarantee(paymentGuaranteeRef, accountId, deadline),
    public readonly intentFingerprint: string = defaultIntentFingerprint(travelerRefs, scheduledServiceRef, departureDate, seatClass, interval),
    private _matchedCapacityCredits: readonly CapacityCreditAllocation[] = [],
    private _fulfillment: FulfillmentProgress = newFulfillmentProgress(entryId, travelerRefs),
    private _journeyOrderRef?: string,
    private _fareQuoteIdempotencyKey: string = uuidV7(),
    private _offerIdempotencyKey: string = uuidV7(),
    private readonly _capacityHoldIdempotencyKey: string = uuidV7(),
    private readonly _capacityReleaseIdempotencyKey: string = uuidV7(),
    private _journeyOrderIdempotencyKey: string = uuidV7(),
    private readonly _capacitySegmentBookingId: string = `sb-${uuidV7()}`,
    private _loadedVersion = 0,
  ) {}

  static create(command: CreateWaitlistEntry): WaitlistEntry {
    assertNonEmpty(command.accountId, "accountId");
    if (command.travelerRefs.length === 0) throw new DomainError("VALIDATION_FAILED", "travelerRefs must contain at least one traveler");
    assertNonEmpty(command.segmentRef, "segmentRef");
    assertNonEmpty(command.departureDate, "departureDate");
    const scheduledServiceRef = command.scheduledServiceRef ?? command.segmentRef;
    const interval = command.interval ?? { fromSeq: 0, toSeq: 1 };
    const stopSequenceVersion = command.stopSequenceVersion ?? `segment:${command.segmentRef}`;
    assertNonEmpty(scheduledServiceRef, "scheduledServiceRef");
    assertTravelInterval(interval);
    assertNonEmpty(stopSequenceVersion, "stopSequenceVersion");
    const seatClass = normalizeFareClass(command.seatClass);
    const groupSize = command.priority.groupSize ?? command.travelerRefs.length;
    const fareClass = command.priority.fareClass ?? seatClass;
    const priorityScore = PriorityCalculator.calculate({ ...command.priority, groupSize, fareClass });
    const entryId = command.entryId ?? `wlr-${uuidV7()}`;
    return new WaitlistEntry(
      entryId,
      command.accountId,
      [...command.travelerRefs],
      command.segmentRef,
      scheduledServiceRef,
      command.departureDate,
      seatClass,
      { ...interval },
      stopSequenceVersion,
      priorityScore,
      command.createdAt ?? new Date(),
      "QUEUED",
      null,
      null,
      undefined,
      undefined,
      undefined,
      undefined,
      command.itineraryRef ?? command.segmentRef,
      command.deadline ?? deadlineFromDepartureDate(command.departureDate),
      command.paymentGuaranteeRef ?? `pi-${uuidV7()}`,
      command.paymentGuarantee ?? testPaymentGuarantee(command.paymentGuaranteeRef ?? "pi-test", command.accountId, command.deadline ?? deadlineFromDepartureDate(command.departureDate)),
      command.intentFingerprint ?? defaultIntentFingerprint(command.travelerRefs, scheduledServiceRef, command.departureDate, seatClass, interval),
      command.matchedCapacityCredits ?? [],
      command.fulfillment ?? newFulfillmentProgress(entryId, command.travelerRefs),
    );
  }

  static fromSnapshot(snapshot: WaitlistEntrySnapshot): WaitlistEntry {
    return new WaitlistEntry(
      snapshot.entryId,
      snapshot.accountId,
      [...snapshot.travelerRefs],
      snapshot.segmentRef,
      snapshot.scheduledServiceRef,
      snapshot.departureDate,
      snapshot.seatClass,
      { ...snapshot.interval },
      snapshot.stopSequenceVersion,
      snapshot.priorityScore,
      new Date(snapshot.createdAt),
      snapshot.status,
      snapshot.offeredAt ? new Date(snapshot.offeredAt) : null,
      snapshot.offerExpiresAt ? new Date(snapshot.offerExpiresAt) : null,
      snapshot.fareQuoteId,
      snapshot.capacityHoldId,
      snapshot.offerId,
      snapshot.offerVersion,
      snapshot.itineraryRef,
      snapshot.deadline,
      snapshot.paymentGuaranteeRef,
      snapshot.paymentGuarantee ?? testPaymentGuarantee(snapshot.paymentGuaranteeRef, snapshot.accountId, snapshot.deadline),
      snapshot.intentFingerprint,
      snapshot.matchedCapacityCredits ?? [],
      snapshot.fulfillment ?? newFulfillmentProgress(snapshot.entryId, snapshot.travelerRefs, snapshot.journeyOrderIdempotencyKey ?? "initial"),
      snapshot.journeyOrderRef,
      snapshot.fareQuoteIdempotencyKey,
      snapshot.offerIdempotencyKey,
      snapshot.capacityHoldIdempotencyKey,
      snapshot.capacityReleaseIdempotencyKey,
      snapshot.journeyOrderIdempotencyKey,
      snapshot.capacitySegmentBookingId,
    );
  }

  get status(): WaitlistStatus { return this._status; }
  get offeredAt(): Date | null { return this._offeredAt; }
  get offerExpiresAt(): Date | null { return this._offerExpiresAt; }
  get fareQuoteId(): string | undefined { return this._fareQuoteId; }
  get capacityHoldId(): string | undefined { return this._capacityHoldId; }
  get offerId(): string | undefined { return this._offerId; }
  get offerVersion(): number | undefined { return this._offerVersion; }
  get journeyOrderRef(): string | undefined { return this._journeyOrderRef; }
  get matchedCapacityCredits(): readonly CapacityCreditAllocation[] { return this._matchedCapacityCredits.map((credit) => ({ ...credit })); }
  get fulfillment(): FulfillmentProgress { return cloneFulfillment(this._fulfillment); }
  get fareQuoteIdempotencyKey(): string { return this._fareQuoteIdempotencyKey; }
  get offerIdempotencyKey(): string { return this._offerIdempotencyKey; }
  get capacityHoldIdempotencyKey(): string { return this._capacityHoldIdempotencyKey; }
  get capacityReleaseIdempotencyKey(): string { return this._capacityReleaseIdempotencyKey; }
  get journeyOrderIdempotencyKey(): string { return this._journeyOrderIdempotencyKey; }
  get capacitySegmentBookingId(): string { return this._capacitySegmentBookingId; }
  get loadedVersion(): number { return this._loadedVersion; }

  markPersisted(version: number): void {
    if (!Number.isInteger(version) || version < 1) throw new DomainError("VALIDATION_FAILED", "loadedVersion must be a positive integer");
    this._loadedVersion = version;
  }

  offer(offerId: string, offerVersion: number, fareQuoteId: string, capacityHoldId: string | undefined, now: Date, expiresAt: Date): void {
    if (this._status === "QUEUED") this._status = "MATCHING";
    else if (this._status !== "MATCHING" || this._offerId) throw new DomainError("INVALID_TRANSITION", "Only an unoffered matching waitlist entry can receive an offer");
    if (expiresAt <= now) throw new DomainError("VALIDATION_FAILED", "Offer expiry must be in the future");
    this._offeredAt = now;
    this._offerExpiresAt = expiresAt;
    if (!Number.isInteger(offerVersion) || offerVersion < 1) throw new DomainError("VALIDATION_FAILED", "offerVersion must be positive");
    this._fareQuoteId = fareQuoteId;
    this._capacityHoldId = capacityHoldId;
    this._offerId = offerId;
    this._offerVersion = offerVersion;
  }

  recordFareQuote(fareQuoteId: string): void {
    this.assertStatus("MATCHING", "Only matching waitlist entries can record a fare quote");
    assertNonEmpty(fareQuoteId, "fareQuoteId");
    if (this._fareQuoteId && this._fareQuoteId !== fareQuoteId) throw new DomainError("CONFLICT", "Waitlist promotion already references a different fare quote");
    this._fareQuoteId = fareQuoteId;
  }

  beginPromotion(capacityCredits: readonly CapacityCreditAllocation[]): void {
    this.assertStatus("QUEUED", "Only queued waitlist entries can claim released capacity");
    if (capacityCredits.length === 0
        || capacityCredits.some((credit) => credit.capacityReleaseRef.trim().length === 0 || !Number.isSafeInteger(credit.slots) || credit.slots < 1)
        || new Set(capacityCredits.map((credit) => credit.capacityReleaseRef)).size !== capacityCredits.length
        || capacityCredits.reduce((total, credit) => total + credit.slots, 0) !== this.travelerRefs.length) {
      throw new DomainError("VALIDATION_FAILED", "capacity credits must exactly cover the traveler group");
    }
    this._status = "MATCHING";
    this._matchedCapacityCredits = capacityCredits.map((credit) => ({ ...credit }));
  }

  linkJourneyOrder(now: Date, journeyOrderRef: string, orderAmount?: MoneySnapshot): void {
    this.ensureOfferAcceptable(now);
    this.recordJourneyOrderRef(journeyOrderRef);
    if (orderAmount) this.recordOrderAmount(orderAmount);
  }

  recordOrderAmount(amount: MoneySnapshot): void {
    assertMoney(amount, "orderAmount");
    if (this._fulfillment.orderAmount
        && (this._fulfillment.orderAmount.currency !== amount.currency || this._fulfillment.orderAmount.minorUnits !== amount.minorUnits)) {
      throw new DomainError("CONFLICT", "Waitlist fulfillment already references a different order amount");
    }
    this._fulfillment = { ...this._fulfillment, orderAmount: { ...amount } };
  }

  recordBookingSaga(sagaId: string): void {
    assertNonEmpty(sagaId, "sagaId");
    if (this._fulfillment.bookingSagaRef && this._fulfillment.bookingSagaRef !== sagaId) throw new DomainError("CONFLICT", "Waitlist fulfillment already references a different booking saga");
    this._fulfillment = { ...this._fulfillment, bookingSagaRef: sagaId };
  }

  recordReservation(travelerRef: string, status: string, capacityHoldId?: string): void {
    assertNonEmpty(status, "reservationStatus");
    this.updateTraveler(travelerRef, (traveler) => {
      if (traveler.capacityHoldId && capacityHoldId && traveler.capacityHoldId !== capacityHoldId) {
        throw new DomainError("CONFLICT", "Waitlist reservation already references a different capacity hold");
      }
      return { ...traveler, reservationStatus: status, capacityHoldId: capacityHoldId ?? traveler.capacityHoldId };
    });
  }

  recordPayment(paymentIntentId: string, status: string): void {
    assertNonEmpty(paymentIntentId, "paymentIntentId");
    assertNonEmpty(status, "paymentStatus");
    if (this._fulfillment.paymentIntentId && this._fulfillment.paymentIntentId !== paymentIntentId) throw new DomainError("CONFLICT", "Waitlist fulfillment already references a different payment intent");
    this._fulfillment = { ...this._fulfillment, paymentIntentId, paymentStatus: status };
  }

  recordEntitlement(travelerRef: string, entitlementId: string): void {
    assertNonEmpty(entitlementId, "entitlementId");
    this.updateTraveler(travelerRef, (traveler) => {
      if (traveler.entitlementId && traveler.entitlementId !== entitlementId) throw new DomainError("CONFLICT", "Waitlist traveler already references a different entitlement");
      return { ...traveler, entitlementId };
    });
  }

  deferFulfillment(nextActionAt: Date): void {
    this.assertStatus("MATCHING", "Only matching waitlist entries can defer fulfillment");
    this._fulfillment = { ...this._fulfillment, nextActionAt: nextActionAt.toISOString() };
  }

  clearFulfillmentDelay(): void {
    if (!this._fulfillment.nextActionAt) return;
    const { nextActionAt: _ignored, ...rest } = this._fulfillment;
    this._fulfillment = rest;
  }

  suspend(): void {
    this.assertStatus("MATCHING", "Only matching waitlist entries can be suspended");
    this._status = "SUSPENDED";
  }

  confirmJourneyOrder(journeyOrderRef: string): void {
    if (this._journeyOrderRef !== journeyOrderRef) throw new DomainError("PRECONDITION_FAILED", "Confirmed order differs from the matching order");
    if (this._status !== "MATCHING" && this._status !== "SUSPENDED") {
      throw new DomainError("INVALID_TRANSITION", "Only matching or suspended waitlist entries can be fulfilled");
    }
    this._status = "FULFILLED";
  }

  ensureOfferAcceptable(now: Date): void {
    this.assertStatus("MATCHING", "Only matching waitlist entries can be fulfilled");
    if (this._offerExpiresAt && now >= this._offerExpiresAt) {
      throw new DomainError("PRECONDITION_FAILED", "Waitlist match has expired");
    }
  }

  expire(): void {
    if (this._status !== "QUEUED" && this._status !== "MATCHING" && this._status !== "SUSPENDED") throw new DomainError("INVALID_TRANSITION", `Cannot expire ${this._status} waitlist entry`);
    this._status = "EXPIRED";
  }

  returnToQueue(): void {
    this.assertStatus("MATCHING", "Only matching waitlist entries can return to the queue");
    this._status = "QUEUED";
    this._offeredAt = null;
    this._offerExpiresAt = null;
    this._fareQuoteId = undefined;
    this._capacityHoldId = undefined;
    this._offerId = undefined;
    this._offerVersion = undefined;
    this._journeyOrderRef = undefined;
    this._matchedCapacityCredits = [];
    this._fareQuoteIdempotencyKey = uuidV7();
    this._offerIdempotencyKey = uuidV7();
    this._journeyOrderIdempotencyKey = uuidV7();
    this._fulfillment = newFulfillmentProgress(this.entryId, this.travelerRefs, this._journeyOrderIdempotencyKey);
  }

  cancel(): void {
    if (this._status !== "DRAFT" && this._status !== "QUEUED" && this._status !== "SUSPENDED") {
      throw new DomainError("INVALID_TRANSITION", `Cannot cancel ${this._status} waitlist entry`);
    }
    this._status = "CANCELLED";
  }

  close(): void {
    if (this._status !== "FULFILLED" && this._status !== "EXPIRED" && this._status !== "CANCELLED") {
      throw new DomainError("INVALID_TRANSITION", `Cannot close ${this._status} waitlist entry`);
    }
    this._status = "CLOSED";
  }

  toSnapshot(queuePosition = 0): WaitlistEntrySnapshot {
    return {
      entryId: this.entryId,
      accountId: this.accountId,
      travelerRefs: [...this.travelerRefs],
      segmentRef: this.segmentRef,
      scheduledServiceRef: this.scheduledServiceRef,
      departureDate: this.departureDate,
      seatClass: this.seatClass,
      interval: { ...this.interval },
      stopSequenceVersion: this.stopSequenceVersion,
      priorityScore: this.priorityScore,
      status: this._status,
      queuePosition,
      createdAt: this.createdAt.toISOString(),
      offeredAt: this._offeredAt?.toISOString() ?? null,
      offerExpiresAt: this._offerExpiresAt?.toISOString() ?? null,
      deadline: this.deadline,
      paymentGuaranteeRef: this.paymentGuaranteeRef,
      paymentGuarantee: { ...this.paymentGuarantee, amount: { ...this.paymentGuarantee.amount } },
      intentFingerprint: this.intentFingerprint,
      matchedCapacityCredits: this._matchedCapacityCredits.map((credit) => ({ ...credit })),
      fulfillment: cloneFulfillment(this._fulfillment),
      fareQuoteId: this._fareQuoteId,
      capacityHoldId: this._capacityHoldId,
      offerId: this._offerId,
      offerVersion: this._offerVersion,
      itineraryRef: this.itineraryRef,
      journeyOrderRef: this._journeyOrderRef,
      fareQuoteIdempotencyKey: this._fareQuoteIdempotencyKey,
      offerIdempotencyKey: this._offerIdempotencyKey,
      capacityHoldIdempotencyKey: this._capacityHoldIdempotencyKey,
      capacityReleaseIdempotencyKey: this._capacityReleaseIdempotencyKey,
      journeyOrderIdempotencyKey: this._journeyOrderIdempotencyKey,
      capacitySegmentBookingId: this._capacitySegmentBookingId,
    };
  }

  private assertStatus(expected: WaitlistStatus, message: string): void {
    if (this._status !== expected) throw new DomainError("INVALID_TRANSITION", message);
  }

  private recordJourneyOrderRef(journeyOrderRef: string): void {
    assertNonEmpty(journeyOrderRef, "journeyOrderRef");
    if (this._journeyOrderRef && this._journeyOrderRef !== journeyOrderRef) throw new DomainError("CONFLICT", "Waitlist request already references a different order");
    this._journeyOrderRef = journeyOrderRef;
  }

  private updateTraveler(travelerRef: string, update: (traveler: FulfillmentTraveler) => FulfillmentTraveler): void {
    let found = false;
    const travelers = this._fulfillment.travelers.map((traveler) => {
      if (traveler.travelerRef !== travelerRef) return traveler;
      found = true;
      return update(traveler);
    });
    if (!found) throw new DomainError("PRECONDITION_FAILED", "Fulfillment traveler is outside the waitlist intent");
    this._fulfillment = { ...this._fulfillment, travelers };
  }
}

export class WaitlistQueue {
  private readonly entries = new Map<string, WaitlistEntry>();

  constructor(
    public readonly segmentRef: string,
    public readonly departureDate: string,
    seatClass: FareClassInput,
    entries: readonly WaitlistEntry[] = [],
  ) {
    this.seatClass = normalizeFareClass(seatClass);
    for (const entry of entries) this.add(entry);
  }

  public readonly seatClass: FareClass;

  add(entry: WaitlistEntry): void {
    if (entry.segmentRef !== this.segmentRef || entry.departureDate !== this.departureDate || entry.seatClass !== this.seatClass) {
      throw new DomainError("VALIDATION_FAILED", "Entry does not belong to this waitlist queue partition");
    }
    this.entries.set(entry.entryId, entry);
  }

  get(entryId: string): WaitlistEntry | undefined { return this.entries.get(entryId); }

  queuedEntries(): readonly WaitlistEntry[] {
    return this.sorted().filter((entry) => entry.status === "QUEUED");
  }

  allEntries(): readonly WaitlistEntry[] { return this.sorted(); }

  nextQueued(): WaitlistEntry | undefined { return this.queuedEntries()[0]; }

  positionOf(entryId: string): number {
    const index = this.queuedEntries().findIndex((entry) => entry.entryId === entryId);
    return index < 0 ? 0 : index + 1;
  }

  totalQueued(): number { return this.queuedEntries().length; }

  private sorted(): WaitlistEntry[] {
    return [...this.entries.values()].sort(compareEntries);
  }
}

export class PriorityCalculator {
  static calculate(input: PriorityInput): number {
    const total = loyaltyPoints(input.loyaltyTier) + historyPoints(input.tripCount) + advancePoints(input.daysBefore) + groupPoints(input.groupSize) + farePoints(input.fareClass) + specialPoints(input.specialStatus);
    return Math.max(0, Math.min(100, total));
  }
}

function compareEntries(left: WaitlistEntry, right: WaitlistEntry): number {
  const priority = right.priorityScore - left.priorityScore;
  if (priority !== 0) return priority;
  const created = left.createdAt.getTime() - right.createdAt.getTime();
  if (created !== 0) return created;
  return left.entryId.localeCompare(right.entryId);
}

function loyaltyPoints(tier: LoyaltyTier = "NONE"): number {
  return ({ PLATINUM: 30, GOLD: 20, SILVER: 10, NONE: 0 } as const)[tier] ?? 0;
}

function historyPoints(tripCount = 0): number {
  if (tripCount > 20) return 20;
  if (tripCount >= 10) return 15;
  if (tripCount >= 5) return 10;
  return 5;
}

function advancePoints(daysBefore = 0): number {
  if (daysBefore > 14) return 15;
  if (daysBefore >= 7) return 10;
  return 5;
}

function groupPoints(groupSize: number): number {
  if (!Number.isInteger(groupSize) || groupSize < 1) throw new DomainError("VALIDATION_FAILED", "groupSize must be at least 1");
  if (groupSize === 1) return 10;
  if (groupSize === 2) return 8;
  if (groupSize <= 4) return 5;
  return 2;
}

function farePoints(fareClass: FareClassInput): number {
  const normalized = normalizeFareClass(fareClass);
  return ({ BUSINESS_CLASS: 15, FIRST_CLASS: 12, SECOND_CLASS: 8, STANDING: 3, SLEEPER_HARD: 10, SLEEPER_SOFT: 13 } as const)[normalized];
}

function specialPoints(status: SpecialStatus | readonly SpecialStatus[] = "NONE"): number {
  const statuses = Array.isArray(status) ? status : [status];
  if (statuses.includes("MILITARY") || statuses.includes("DISABLED")) return 10;
  if (statuses.includes("STUDENT")) return 5;
  return 0;
}

function assertNonEmpty(value: string, field: string): void {
  if (value.trim().length === 0) throw new DomainError("VALIDATION_FAILED", `${field} is required`);
}

function deadlineFromDepartureDate(departureDate: string): string {
  return `${departureDate}T00:00:00.000Z`;
}

export function normalizeFareClass(value: string): FareClass {
  const normalized = value.trim().toUpperCase();
  const aliases: Readonly<Record<string, FareClass>> = {
    BUSINESS: "BUSINESS_CLASS",
    BUSINESS_CLASS: "BUSINESS_CLASS",
    FIRST: "FIRST_CLASS",
    FIRST_CLASS: "FIRST_CLASS",
    SECOND: "SECOND_CLASS",
    SECOND_CLASS: "SECOND_CLASS",
    STANDING: "STANDING",
    SLEEPER_HARD: "SLEEPER_HARD",
    SLEEPER_SOFT: "SLEEPER_SOFT",
  };
  const result = aliases[normalized];
  if (!result) throw new DomainError("VALIDATION_FAILED", `Unsupported travel class ${value}`);
  return result;
}

export function intervalContains(container: TravelInterval, requested: TravelInterval): boolean {
  return container.fromSeq <= requested.fromSeq && container.toSeq >= requested.toSeq;
}

function assertTravelInterval(interval: TravelInterval): void {
  if (!Number.isSafeInteger(interval.fromSeq) || !Number.isSafeInteger(interval.toSeq)
      || interval.fromSeq < 0 || interval.toSeq <= interval.fromSeq) {
    throw new DomainError("VALIDATION_FAILED", "interval must contain nonnegative fromSeq and a greater toSeq");
  }
}

function defaultIntentFingerprint(travelerRefs: readonly string[], scheduledServiceRef: string, departureDate: string, seatClass: FareClass, interval: TravelInterval): string {
  return `${[...travelerRefs].sort().join(",")}:${scheduledServiceRef}:${departureDate}:${seatClass}:${interval.fromSeq}-${interval.toSeq}`;
}

function testPaymentGuarantee(paymentIntentId: string, payerRef: string, authorizedUntil: string): PaymentGuaranteeSnapshot {
  return {
    paymentIntentId,
    sourceOrderRef: "test-source-order",
    payerRef,
    amount: { currency: "CNY", minorUnits: Number.MAX_SAFE_INTEGER },
    sourceStatus: "CREATED",
    sourceExpiresAt: authorizedUntil,
    authorizedUntil,
    verifiedAt: new Date(0).toISOString(),
  };
}

function newFulfillmentProgress(entryId: string, travelerRefs: readonly string[], attemptRef = "initial"): FulfillmentProgress {
  const attempt = `${entryId}:${attemptRef}`;
  return {
    travelers: travelerRefs.map((travelerRef) => ({
      travelerRef,
      segmentBookingId: `sb-${stableUuidV7(`${attempt}:booking:${travelerRef}`)}`,
      reservationIdempotencyKey: stableUuidV7(`${attempt}:reservation:${travelerRef}`),
      capacityReleaseIdempotencyKey: stableUuidV7(`${attempt}:capacity:release:${travelerRef}`),
      entitlementIdempotencyKey: stableUuidV7(`${attempt}:entitlement:${travelerRef}`),
    })),
    paymentCreateIdempotencyKey: stableUuidV7(`${attempt}:payment:create`),
    paymentCaptureIdempotencyKey: stableUuidV7(`${attempt}:payment:capture`),
    paymentCancelIdempotencyKey: stableUuidV7(`${attempt}:payment:cancel`),
    orderCancelIdempotencyKey: stableUuidV7(`${attempt}:order:cancel`),
  };
}

function cloneFulfillment(progress: FulfillmentProgress): FulfillmentProgress {
  return {
    ...progress,
    orderAmount: progress.orderAmount ? { ...progress.orderAmount } : undefined,
    travelers: progress.travelers.map((traveler) => ({ ...traveler })),
  };
}

function assertMoney(amount: MoneySnapshot, field: string): void {
  assertNonEmpty(amount.currency, `${field}.currency`);
  if (!Number.isSafeInteger(amount.minorUnits) || amount.minorUnits <= 0) throw new DomainError("VALIDATION_FAILED", `${field}.minorUnits must be positive`);
}

function stableUuidV7(material: string): string {
  const bytes = createHash("sha256").update(material, "utf8").digest().subarray(0, 16);
  bytes[6] = (bytes[6]! & 0x0f) | 0x70;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = [...bytes].map((value) => value.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
