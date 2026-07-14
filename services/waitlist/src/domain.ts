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
  itineraryRef: string;
  journeyOrderRef?: string;
  cancelledAt?: string;
  closedAt?: string;
  fareQuoteIdempotencyKey?: string;
  offerIdempotencyKey?: string;
  capacityHoldIdempotencyKey?: string;
  capacityReleaseIdempotencyKey?: string;
  journeyOrderIdempotencyKey?: string;
  capacitySegmentBookingId?: string;
  version: number;
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
  deadline: Date;
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
    public readonly deadline: Date,
    public readonly paymentGuaranteeRef: string,
    public readonly intentFingerprint: string,
    private _fareQuoteId?: string,
    private _capacityHoldId?: string,
    private _offerId?: string,
    private _offerVersion?: number,
    public readonly itineraryRef: string = "",
    private _journeyOrderRef?: string,
    private _cancelledAt?: Date,
    private _closedAt?: Date,
    private readonly _fareQuoteIdempotencyKey: string = uuidV7(),
    private readonly _offerIdempotencyKey: string = uuidV7(),
    private readonly _capacityHoldIdempotencyKey: string = uuidV7(),
    private readonly _capacityReleaseIdempotencyKey: string = uuidV7(),
    private readonly _journeyOrderIdempotencyKey: string = uuidV7(),
    private readonly _capacitySegmentBookingId: string = `sb-${uuidV7()}`,
    private _version = 1,
  ) {}

  static create(command: CreateWaitlistEntry): WaitlistEntry {
    assertNonEmpty(command.accountId, "accountId");
    if (command.travelerRefs.length === 0) throw new DomainError("VALIDATION_FAILED", "travelerRefs must contain at least one traveler");
    assertNonEmpty(command.segmentRef, "segmentRef");
    assertNonEmpty(command.departureDate, "departureDate");
    assertNonEmpty(command.itineraryRef, "itineraryRef");
    validateDeadline(command.deadline);
    const createdAt = command.createdAt ?? new Date();
    if (command.deadline <= createdAt) throw new DomainError("VALIDATION_FAILED", "deadline must be in the future");
    validatePaymentGuaranteeRef(command.paymentGuaranteeRef);
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
      createdAt,
      "QUEUED",
      null,
      null,
      command.deadline,
      command.paymentGuaranteeRef,
      command.intentFingerprint,
      undefined,
      undefined,
      undefined,
      undefined,
      command.itineraryRef,
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
      new Date(snapshot.deadline),
      snapshot.paymentGuaranteeRef,
      snapshot.intentFingerprint,
      snapshot.fareQuoteId,
      snapshot.capacityHoldId,
      snapshot.offerId,
      snapshot.offerVersion,
      snapshot.itineraryRef,
      snapshot.journeyOrderRef,
      snapshot.cancelledAt ? new Date(snapshot.cancelledAt) : undefined,
      snapshot.closedAt ? new Date(snapshot.closedAt) : undefined,
      snapshot.fareQuoteIdempotencyKey,
      snapshot.offerIdempotencyKey,
      snapshot.capacityHoldIdempotencyKey,
      snapshot.capacityReleaseIdempotencyKey,
      snapshot.journeyOrderIdempotencyKey,
      snapshot.capacitySegmentBookingId,
      snapshot.version,
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
  get version(): number { return this._version; }
  get fareQuoteIdempotencyKey(): string { return this._fareQuoteIdempotencyKey; }
  get offerIdempotencyKey(): string { return this._offerIdempotencyKey; }
  get capacityHoldIdempotencyKey(): string { return this._capacityHoldIdempotencyKey; }
  get capacityReleaseIdempotencyKey(): string { return this._capacityReleaseIdempotencyKey; }
  get journeyOrderIdempotencyKey(): string { return this._journeyOrderIdempotencyKey; }
  get capacitySegmentBookingId(): string { return this._capacitySegmentBookingId; }

  offer(offerId: string, offerVersion: number, fareQuoteId: string, capacityHoldId: string, now: Date, expiresAt: Date): void {
    this.assertStatus("QUEUED", "Only queued waitlist requests can start matching");
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
    assertNonEmpty(journeyOrderRef, "journeyOrderRef");
    this._status = "FULFILLED";
    this._journeyOrderRef = journeyOrderRef;
  }

  ensureOfferAcceptable(now: Date): void {
    this.assertStatus("MATCHING", "Only matching waitlist requests can be fulfilled");
    if (this._offerExpiresAt && now > this._offerExpiresAt) throw new DomainError("PRECONDITION_FAILED", "Waitlist match has expired");
    if (now > this.deadline) throw new DomainError("PRECONDITION_FAILED", "Waitlist deadline has expired");
  }

  expire(now: Date): void {
    if (this._status !== "MATCHING" && this._status !== "QUEUED") throw new DomainError("INVALID_TRANSITION", `Cannot expire ${this._status} waitlist request`);
    if (this._status === "MATCHING" && this._offerExpiresAt && now < this._offerExpiresAt && now < this.deadline) {
      throw new DomainError("PRECONDITION_FAILED", "Waitlist match has not reached its expiry time");
    }
    this._status = "EXPIRED";
  }

  cancel(now = new Date()): void {
    if (["FULFILLED", "EXPIRED", "CANCELLED", "CLOSED"].includes(this._status)) throw new DomainError("INVALID_TRANSITION", `Cannot cancel ${this._status} waitlist request`);
    this._status = "CANCELLED";
    this._cancelledAt = now;
  }

  close(now = new Date()): void {
    if (this._status !== "FULFILLED" && this._status !== "EXPIRED" && this._status !== "CANCELLED") throw new DomainError("INVALID_TRANSITION", `Cannot close ${this._status} waitlist request`);
    this._status = "CLOSED";
    this._closedAt = now;
  }

  markPersisted(version: number): void {
    if (!Number.isInteger(version) || version < 1) throw new DomainError("VALIDATION_FAILED", "version must be positive");
    this._version = version;
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
      deadline: this.deadline.toISOString(),
      paymentGuaranteeRef: this.paymentGuaranteeRef,
      intentFingerprint: this.intentFingerprint,
      fareQuoteId: this._fareQuoteId,
      capacityHoldId: this._capacityHoldId,
      offerId: this._offerId,
      offerVersion: this._offerVersion,
      itineraryRef: this.itineraryRef,
      journeyOrderRef: this._journeyOrderRef,
      cancelledAt: this._cancelledAt?.toISOString(),
      closedAt: this._closedAt?.toISOString(),
      fareQuoteIdempotencyKey: this._fareQuoteIdempotencyKey,
      offerIdempotencyKey: this._offerIdempotencyKey,
      capacityHoldIdempotencyKey: this._capacityHoldIdempotencyKey,
      capacityReleaseIdempotencyKey: this._capacityReleaseIdempotencyKey,
      journeyOrderIdempotencyKey: this._journeyOrderIdempotencyKey,
      capacitySegmentBookingId: this._capacitySegmentBookingId,
      version: this._version,
    };
  }

  private assertStatus(expected: WaitlistStatus, message: string): void {
    if (this._status !== expected) throw new DomainError("INVALID_TRANSITION", message);
  }
}

export class WaitlistQueue {
  private readonly entries = new Map<string, WaitlistEntry>();

  constructor(public readonly segmentRef: string, public readonly departureDate: string, public readonly seatClass: FareClass, entries: readonly WaitlistEntry[] = []) {
    for (const entry of entries) this.add(entry);
  }

  add(entry: WaitlistEntry): void {
    if (entry.segmentRef !== this.segmentRef || entry.departureDate !== this.departureDate || entry.seatClass !== this.seatClass) throw new DomainError("VALIDATION_FAILED", "Entry does not belong to this waitlist queue partition");
    if (entry.status !== "CLOSED") this.entries.set(entry.entryId, entry);
  }

  get(entryId: string): WaitlistEntry | undefined { return this.entries.get(entryId); }
  queuedEntries(): readonly WaitlistEntry[] { return this.sorted().filter((entry) => entry.status === "QUEUED"); }
  allEntries(): readonly WaitlistEntry[] { return this.sorted(); }
  nextQueued(): WaitlistEntry | undefined { return this.queuedEntries()[0]; }
  positionOf(entryId: string): number {
    const index = this.queuedEntries().findIndex((entry) => entry.entryId === entryId);
    return index < 0 ? 0 : index + 1;
  }
  totalQueued(): number { return this.queuedEntries().length; }
  private sorted(): WaitlistEntry[] { return [...this.entries.values()].sort(compareEntries); }
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
function loyaltyPoints(tier: LoyaltyTier = "NONE"): number { return ({ PLATINUM: 30, GOLD: 20, SILVER: 10, NONE: 0 } as const)[tier] ?? 0; }
function historyPoints(tripCount = 0): number { if (tripCount > 20) return 20; if (tripCount >= 10) return 15; if (tripCount >= 5) return 10; return 5; }
function advancePoints(daysBefore = 0): number { if (daysBefore > 14) return 15; if (daysBefore >= 7) return 10; return 5; }
function groupPoints(groupSize: number): number { if (!Number.isInteger(groupSize) || groupSize < 1) throw new DomainError("VALIDATION_FAILED", "groupSize must be at least 1"); if (groupSize === 1) return 10; if (groupSize === 2) return 8; if (groupSize <= 4) return 5; return 2; }
function farePoints(fareClass: FareClass): number { const normalized = fareClass === "SECOND_CLASS" ? "SECOND" : fareClass; return ({ BUSINESS: 15, FIRST: 12, SECOND: 8, STANDING: 3 } as const)[normalized] ?? 0; }
function specialPoints(status: SpecialStatus | readonly SpecialStatus[] = "NONE"): number { const statuses = Array.isArray(status) ? status : [status]; if (statuses.includes("MILITARY") || statuses.includes("DISABLED")) return 10; if (statuses.includes("STUDENT")) return 5; return 0; }
function assertNonEmpty(value: string, field: string): void { if (typeof value !== "string" || value.trim().length === 0) throw new DomainError("VALIDATION_FAILED", `${field} is required`); }
function validateDeadline(deadline: Date): void { if (!Number.isFinite(deadline.getTime())) throw new DomainError("VALIDATION_FAILED", "deadline must be a valid RFC3339 instant"); }
function validatePaymentGuaranteeRef(value: string): void { assertNonEmpty(value, "paymentGuaranteeRef"); if (!value.startsWith("pay-auth-") && !value.startsWith("pi-")) throw new DomainError("VALIDATION_FAILED", "paymentGuaranteeRef must start with pay-auth- or pi-"); }
