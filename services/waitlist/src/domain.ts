import { uuidV7 } from "@trainticket/ts-kit";

export type WaitlistStatus = "DRAFT" | "QUEUED" | "MATCHING" | "FULFILLED" | "EXPIRED" | "CANCELLED" | "SUSPENDED" | "CLOSED";
export type LoyaltyTier = "PLATINUM" | "GOLD" | "SILVER" | "NONE";
export type FareClass = "BUSINESS" | "FIRST" | "SECOND" | "SECOND_CLASS" | "STANDING";
export type SpecialStatus = "MILITARY" | "DISABLED" | "STUDENT" | "NONE";

export type PriorityInput = Readonly<{
  loyaltyTier?: LoyaltyTier;
  tripCount?: number;
  daysBefore?: number;
  groupSize: number;
  fareClass: FareClass;
  specialStatus?: SpecialStatus | readonly SpecialStatus[];
}>;

export type WaitlistEntrySnapshot = Readonly<{
  entryId: string;
  accountId: string;
  travelerRefs: readonly string[];
  segmentRef: string;
  departureDate: string;
  seatClass: FareClass;
  priorityScore: number;
  status: WaitlistStatus;
  queuePosition: number;
  createdAt: string;
  offeredAt: string | null;
  offerExpiresAt: string | null;
  deadline: string;
  paymentGuaranteeRef: string;
  intentFingerprint: string;
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
  departureDate: string;
  seatClass: FareClass;
  priority: Omit<PriorityInput, "groupSize" | "fareClass"> & Partial<Pick<PriorityInput, "groupSize" | "fareClass">>;
  itineraryRef: string;
  deadline: string;
  paymentGuaranteeRef: string;
  intentFingerprint: string;
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
    public readonly departureDate: string,
    public readonly seatClass: FareClass,
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
    public readonly paymentGuaranteeRef: string = `pay-auth-${uuidV7()}`,
    public readonly intentFingerprint: string = defaultIntentFingerprint(travelerRefs[0] ?? "unknown", segmentRef, departureDate, seatClass),
    private _journeyOrderRef?: string,
    private readonly _fareQuoteIdempotencyKey: string = uuidV7(),
    private readonly _offerIdempotencyKey: string = uuidV7(),
    private readonly _capacityHoldIdempotencyKey: string = uuidV7(),
    private readonly _capacityReleaseIdempotencyKey: string = uuidV7(),
    private readonly _journeyOrderIdempotencyKey: string = uuidV7(),
    private readonly _capacitySegmentBookingId: string = `sb-${uuidV7()}`,
    private _loadedVersion = 0,
  ) {}

  static create(command: CreateWaitlistEntry): WaitlistEntry {
    assertNonEmpty(command.accountId, "accountId");
    if (command.travelerRefs.length === 0) throw new DomainError("VALIDATION_FAILED", "travelerRef is required");
    assertNonEmpty(command.travelerRefs[0] ?? "", "travelerRef");
    assertNonEmpty(command.segmentRef, "segmentRef");
    assertNonEmpty(command.departureDate, "departureDate");
    assertNonEmpty(command.itineraryRef, "itineraryRef");
    assertNonEmpty(command.deadline, "deadline");
    assertNonEmpty(command.paymentGuaranteeRef, "paymentGuaranteeRef");
    assertNonEmpty(command.intentFingerprint, "intentFingerprint");
    const groupSize = command.priority.groupSize ?? command.travelerRefs.length;
    const fareClass = command.priority.fareClass ?? command.seatClass;
    const priorityScore = PriorityCalculator.calculate({ ...command.priority, groupSize, fareClass });
    return new WaitlistEntry(
      command.entryId ?? `wlr-${uuidV7()}`,
      command.accountId,
      [...command.travelerRefs],
      command.segmentRef,
      command.departureDate,
      command.seatClass,
      priorityScore,
      command.createdAt ?? new Date(),
      "QUEUED",
      null,
      null,
      undefined,
      undefined,
      undefined,
      undefined,
      command.itineraryRef,
      command.deadline,
      command.paymentGuaranteeRef,
      command.intentFingerprint,
    );
  }

  static fromSnapshot(snapshot: WaitlistEntrySnapshot): WaitlistEntry {
    return new WaitlistEntry(
      snapshot.entryId,
      snapshot.accountId,
      [...snapshot.travelerRefs],
      snapshot.segmentRef,
      snapshot.departureDate,
      snapshot.seatClass,
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
      snapshot.intentFingerprint,
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

  offer(offerId: string, offerVersion: number, fareQuoteId: string, capacityHoldId: string, now: Date, expiresAt: Date): void {
    this.assertStatus("QUEUED", "Only queued waitlist entries can begin matching");
    if (expiresAt <= now) throw new DomainError("VALIDATION_FAILED", "Offer expiry must be in the future");
    this._status = "MATCHING";
    this._offeredAt = now;
    this._offerExpiresAt = expiresAt;
    if (!Number.isInteger(offerVersion) || offerVersion < 1) throw new DomainError("VALIDATION_FAILED", "offerVersion must be positive");
    this._fareQuoteId = fareQuoteId;
    this._capacityHoldId = capacityHoldId;
    this._offerId = offerId;
    this._offerVersion = offerVersion;
  }

  accept(now: Date, journeyOrderRef: string): void {
    this.ensureOfferAcceptable(now);
    this.recordJourneyOrderRef(journeyOrderRef);
    this._status = "FULFILLED";
  }

  ensureOfferAcceptable(now: Date): void {
    this.assertStatus("MATCHING", "Only matching waitlist entries can be fulfilled");
    if (this._offerExpiresAt && now > this._offerExpiresAt) {
      throw new DomainError("PRECONDITION_FAILED", "Waitlist match has expired");
    }
  }

  expire(): void {
    if (this._status !== "QUEUED" && this._status !== "MATCHING") throw new DomainError("INVALID_TRANSITION", `Cannot expire ${this._status} waitlist entry`);
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
  }

  cancel(): void {
    if (this._status !== "DRAFT" && this._status !== "QUEUED" && this._status !== "SUSPENDED") {
      throw new DomainError("INVALID_TRANSITION", `Cannot cancel ${this._status} waitlist entry`);
    }
    this._status = "CANCELLED";
  }

  toSnapshot(queuePosition = 0): WaitlistEntrySnapshot {
    return {
      entryId: this.entryId,
      accountId: this.accountId,
      travelerRefs: [...this.travelerRefs],
      segmentRef: this.segmentRef,
      departureDate: this.departureDate,
      seatClass: this.seatClass,
      priorityScore: this.priorityScore,
      status: this._status,
      queuePosition,
      createdAt: this.createdAt.toISOString(),
      offeredAt: this._offeredAt?.toISOString() ?? null,
      offerExpiresAt: this._offerExpiresAt?.toISOString() ?? null,
      deadline: this.deadline,
      paymentGuaranteeRef: this.paymentGuaranteeRef,
      intentFingerprint: this.intentFingerprint,
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

  attachJourneyOrder(journeyOrderRef: string): void {
    this.assertStatus("MATCHING", "Only matching waitlist entries can be attached to a journey order");
    this.recordJourneyOrderRef(journeyOrderRef);
  }

  private recordJourneyOrderRef(journeyOrderRef: string): void {
    assertNonEmpty(journeyOrderRef, "journeyOrderRef");
    this._journeyOrderRef = journeyOrderRef;
  }
}

export class WaitlistQueue {
  private readonly entries = new Map<string, WaitlistEntry>();

  constructor(
    public readonly segmentRef: string,
    public readonly departureDate: string,
    public readonly seatClass: FareClass,
    entries: readonly WaitlistEntry[] = [],
  ) {
    for (const entry of entries) this.add(entry);
  }

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

function farePoints(fareClass: FareClass): number {
  const normalized = fareClass === "SECOND_CLASS" ? "SECOND" : fareClass;
  return ({ BUSINESS: 15, FIRST: 12, SECOND: 8, STANDING: 3 } as const)[normalized] ?? 0;
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

function defaultIntentFingerprint(travelerRef: string, segmentRef: string, departureDate: string, seatClass: FareClass): string {
  return `${travelerRef}:${segmentRef}:${departureDate}:${seatClass}`;
}
