import { uuidV7 } from "@trainticket/ts-kit";

export type WaitlistStatus = "DRAFT" | "QUEUED" | "MATCHING" | "FULFILLED" | "EXPIRED" | "CANCELLED" | "SUSPENDED" | "CLOSED";
export type LoyaltyTier = "PLATINUM" | "GOLD" | "SILVER" | "NONE";
export type FareClass = "BUSINESS" | "FIRST" | "SECOND" | "SECOND_CLASS" | "STANDING";
export type SpecialStatus = "MILITARY" | "DISABLED" | "STUDENT" | "NONE";

export const ACTIVE_WAITLIST_STATUSES: readonly WaitlistStatus[] = ["DRAFT", "QUEUED", "MATCHING", "SUSPENDED"];
export const ARCHIVABLE_WAITLIST_STATUSES: readonly WaitlistStatus[] = ["FULFILLED", "EXPIRED", "CANCELLED"];

export type PriorityInput = Readonly<{
  loyaltyTier?: LoyaltyTier;
  tripCount?: number;
  daysBefore?: number;
  groupSize: number;
  fareClass: FareClass;
  specialStatus?: SpecialStatus | readonly SpecialStatus[];
}>;

export type WaitlistRequestResource = Readonly<{
  waitlistRequestId: string;
  accountId: string;
  travelerRef: string;
  segmentRef: string;
  travelClass?: string;
  deadline: string;
  paymentGuaranteeRef: string;
  itineraryRef: string;
  intentFingerprint: string;
  status: WaitlistStatus;
  journeyOrderRef?: string;
}>;

export type WaitlistEntrySnapshot = WaitlistRequestResource & Readonly<{
  entryId: string;
  version: number;
  loadedVersion: number;
  travelerRefs: readonly string[];
  departureDate: string;
  seatClass: FareClass;
  priorityScore: number;
  queuePosition: number;
  createdAt: string;
  matchingStartedAt: string | null;
  deadlineExpiredAt: string | null;
  cancelledAt: string | null;
  fulfilledAt: string | null;
  closedAt: string | null;
  fareQuoteId?: string;
  capacityHoldId?: string;
  offerId?: string;
  offerVersion?: number;
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
  travelerRef?: string;
  travelerRefs?: readonly string[];
  segmentRef: string;
  departureDate?: string;
  seatClass?: FareClass;
  travelClass?: FareClass;
  deadline: string;
  paymentGuaranteeRef: string;
  itineraryRef: string;
  intentFingerprint: string;
  priority: Omit<PriorityInput, "groupSize" | "fareClass"> & Partial<Pick<PriorityInput, "groupSize" | "fareClass">>;
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
    public readonly travelerRef: string,
    public readonly segmentRef: string,
    public readonly departureDate: string,
    public readonly seatClass: FareClass,
    public readonly priorityScore: number,
    public readonly createdAt: Date,
    public readonly deadline: Date,
    public readonly paymentGuaranteeRef: string,
    public readonly itineraryRef: string,
    public readonly intentFingerprint: string,
    private _status: WaitlistStatus,
    private _version: number,
    private _loadedVersion: number,
    private _matchingStartedAt: Date | null = null,
    private _deadlineExpiredAt: Date | null = null,
    private _cancelledAt: Date | null = null,
    private _fulfilledAt: Date | null = null,
    private _closedAt: Date | null = null,
    private _fareQuoteId?: string,
    private _capacityHoldId?: string,
    private _offerId?: string,
    private _offerVersion?: number,
    private _journeyOrderRef?: string,
    private readonly _fareQuoteIdempotencyKey: string = uuidV7(),
    private readonly _offerIdempotencyKey: string = uuidV7(),
    private readonly _capacityHoldIdempotencyKey: string = uuidV7(),
    private readonly _capacityReleaseIdempotencyKey: string = uuidV7(),
    private readonly _journeyOrderIdempotencyKey: string = uuidV7(),
    private readonly _capacitySegmentBookingId: string = `sb-${uuidV7()}`,
  ) {}

  static create(command: CreateWaitlistEntry): WaitlistEntry {
    assertNonEmpty(command.accountId, "accountId");
    const travelerRefs = command.travelerRefs ?? (command.travelerRef ? [command.travelerRef] : []);
    if (travelerRefs.length === 0) throw new DomainError("VALIDATION_FAILED", "travelerRef is required");
    const travelerRef = travelerRefs[0];
    assertNonEmpty(travelerRef, "travelerRef");
    assertNonEmpty(command.segmentRef, "segmentRef");
    assertNonEmpty(command.deadline, "deadline");
    assertNonEmpty(command.paymentGuaranteeRef, "paymentGuaranteeRef");
    assertNonEmpty(command.itineraryRef, "itineraryRef");
    assertNonEmpty(command.intentFingerprint, "intentFingerprint");
    if (!isPaymentGuaranteeRef(command.paymentGuaranteeRef)) {
      throw new DomainError("VALIDATION_FAILED", "paymentGuaranteeRef must start with pay-auth- or pi-");
    }
    const deadline = new Date(command.deadline);
    if (!Number.isFinite(deadline.getTime())) throw new DomainError("VALIDATION_FAILED", "deadline must be a valid RFC3339 timestamp");
    const seatClass = command.travelClass ?? command.seatClass ?? "SECOND";
    const departureDate = command.departureDate ?? command.deadline.slice(0, 10);
    const groupSize = command.priority.groupSize ?? travelerRefs.length;
    const fareClass = command.priority.fareClass ?? seatClass;
    const priorityScore = PriorityCalculator.calculate({ ...command.priority, groupSize, fareClass });
    return new WaitlistEntry(
      command.entryId ?? `wlr-${uuidV7()}`,
      command.accountId,
      travelerRef,
      command.segmentRef,
      departureDate,
      seatClass,
      priorityScore,
      command.createdAt ?? new Date(),
      deadline,
      command.paymentGuaranteeRef,
      command.itineraryRef,
      command.intentFingerprint,
      "DRAFT",
      1,
      0,
    );
  }

  static fromSnapshot(snapshot: WaitlistEntrySnapshot): WaitlistEntry {
    return new WaitlistEntry(
      snapshot.entryId,
      snapshot.accountId,
      snapshot.travelerRef,
      snapshot.segmentRef,
      snapshot.departureDate,
      snapshot.seatClass,
      snapshot.priorityScore,
      new Date(snapshot.createdAt),
      new Date(snapshot.deadline),
      snapshot.paymentGuaranteeRef,
      snapshot.itineraryRef,
      snapshot.intentFingerprint,
      snapshot.status,
      snapshot.version,
      snapshot.loadedVersion,
      snapshot.matchingStartedAt ? new Date(snapshot.matchingStartedAt) : null,
      snapshot.deadlineExpiredAt ? new Date(snapshot.deadlineExpiredAt) : null,
      snapshot.cancelledAt ? new Date(snapshot.cancelledAt) : null,
      snapshot.fulfilledAt ? new Date(snapshot.fulfilledAt) : null,
      snapshot.closedAt ? new Date(snapshot.closedAt) : null,
      snapshot.fareQuoteId,
      snapshot.capacityHoldId,
      snapshot.offerId,
      snapshot.offerVersion,
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
  get version(): number { return this._version; }
  get loadedVersion(): number { return this._loadedVersion; }
  get travelerRefs(): readonly string[] { return [this.travelerRef]; }
  get matchingStartedAt(): Date | null { return this._matchingStartedAt; }
  get cancelledAt(): Date | null { return this._cancelledAt; }
  get fulfilledAt(): Date | null { return this._fulfilledAt; }
  get deadlineExpiredAt(): Date | null { return this._deadlineExpiredAt; }
  get closedAt(): Date | null { return this._closedAt; }
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

  authorizePaymentReference(): number {
    this.assertStatus("DRAFT", "Only draft waitlist requests can authorize payment guarantee references");
    return this.advanceVersion();
  }

  enqueue(): number {
    if (this._status !== "DRAFT" && this._status !== "SUSPENDED") {
      throw new DomainError("INVALID_TRANSITION", `Cannot queue ${this._status} waitlist request`);
    }
    this._status = "QUEUED";
    return this.advanceVersion();
  }

  startMatching(now: Date, matchedCapacityReleaseRef: string): number {
    this.assertStatus("QUEUED", "Only queued waitlist requests can start matching");
    assertNonEmpty(matchedCapacityReleaseRef, "matchedCapacityReleaseRef");
    this._status = "MATCHING";
    this._matchingStartedAt = now;
    return this.advanceVersion();
  }

  recordHold(offerId: string, offerVersion: number, fareQuoteId: string, capacityHoldId: string): number {
    this.assertStatus("MATCHING", "Only matching waitlist requests can record a hold");
    if (!Number.isInteger(offerVersion) || offerVersion < 1) throw new DomainError("VALIDATION_FAILED", "offerVersion must be positive");
    this._fareQuoteId = fareQuoteId;
    this._capacityHoldId = capacityHoldId;
    this._offerId = offerId;
    this._offerVersion = offerVersion;
    return this.advanceVersion();
  }

  fulfill(journeyOrderRef: string, now: Date): number {
    if (this._status !== "MATCHING" && this._status !== "QUEUED") {
      throw new DomainError("INVALID_TRANSITION", `Cannot fulfill ${this._status} waitlist request`);
    }
    assertNonEmpty(journeyOrderRef, "journeyOrderRef");
    this._status = "FULFILLED";
    this._journeyOrderRef = journeyOrderRef;
    this._fulfilledAt = now;
    return this.advanceVersion();
  }

  requeueAfterJourneyOrderCancelled(journeyOrderRef: string): number {
    this.assertStatus("MATCHING", "Only matching waitlist requests can be requeued after journey order cancellation");
    assertNonEmpty(journeyOrderRef, "journeyOrderRef");
    this._journeyOrderRef = journeyOrderRef;
    this._status = "QUEUED";
    return this.advanceVersion();
  }

  expire(now: Date): number {
    if (this._status !== "QUEUED" && this._status !== "MATCHING" && this._status !== "SUSPENDED") {
      throw new DomainError("INVALID_TRANSITION", `Cannot expire ${this._status} waitlist request`);
    }
    this._status = "EXPIRED";
    this._deadlineExpiredAt = now;
    return this.advanceVersion();
  }

  cancel(now: Date): number {
    if (this._status === "FULFILLED" || this._status === "EXPIRED" || this._status === "CANCELLED" || this._status === "CLOSED") {
      throw new DomainError("PRECONDITION_FAILED", `Cannot cancel ${this._status} waitlist request`);
    }
    this._status = "CANCELLED";
    this._cancelledAt = now;
    return this.advanceVersion();
  }

  close(now: Date): number {
    if (!ARCHIVABLE_WAITLIST_STATUSES.includes(this._status)) {
      throw new DomainError("INVALID_TRANSITION", `Cannot close ${this._status} waitlist request`);
    }
    this._status = "CLOSED";
    this._closedAt = now;
    return this.advanceVersion();
  }

  markPersisted(): void {
    this._loadedVersion = this._version;
  }

  toResource(): WaitlistRequestResource {
    return omitUndefined({
      waitlistRequestId: this.entryId,
      accountId: this.accountId,
      travelerRef: this.travelerRef,
      segmentRef: this.segmentRef,
      travelClass: this.seatClass,
      deadline: this.deadline.toISOString(),
      paymentGuaranteeRef: this.paymentGuaranteeRef,
      itineraryRef: this.itineraryRef,
      intentFingerprint: this.intentFingerprint,
      status: this._status,
      journeyOrderRef: this._journeyOrderRef,
    });
  }

  toSnapshot(queuePosition = 0): WaitlistEntrySnapshot {
    const resource = this.toResource();
    return {
      ...resource,
      entryId: this.entryId,
      version: this._version,
      loadedVersion: this._loadedVersion,
      travelerRefs: [this.travelerRef],
      departureDate: this.departureDate,
      seatClass: this.seatClass,
      priorityScore: this.priorityScore,
      queuePosition,
      createdAt: this.createdAt.toISOString(),
      matchingStartedAt: this._matchingStartedAt?.toISOString() ?? null,
      deadlineExpiredAt: this._deadlineExpiredAt?.toISOString() ?? null,
      cancelledAt: this._cancelledAt?.toISOString() ?? null,
      fulfilledAt: this._fulfilledAt?.toISOString() ?? null,
      closedAt: this._closedAt?.toISOString() ?? null,
      fareQuoteId: this._fareQuoteId,
      capacityHoldId: this._capacityHoldId,
      offerId: this._offerId,
      offerVersion: this._offerVersion,
      fareQuoteIdempotencyKey: this._fareQuoteIdempotencyKey,
      offerIdempotencyKey: this._offerIdempotencyKey,
      capacityHoldIdempotencyKey: this._capacityHoldIdempotencyKey,
      capacityReleaseIdempotencyKey: this._capacityReleaseIdempotencyKey,
      journeyOrderIdempotencyKey: this._journeyOrderIdempotencyKey,
      capacitySegmentBookingId: this._capacitySegmentBookingId,
    };
  }

  private advanceVersion(): number {
    this._version += 1;
    return this._version;
  }

  private assertStatus(expected: WaitlistStatus, message: string): void {
    if (this._status !== expected) throw new DomainError("INVALID_TRANSITION", message);
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

  activeEntries(): readonly WaitlistEntry[] {
    return this.sorted().filter((entry) => entry.status !== "CLOSED");
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

function isPaymentGuaranteeRef(value: string): boolean {
  return value.startsWith("pay-auth-") || value.startsWith("pi-");
}

function omitUndefined<T extends Record<string, unknown>>(value: T): T {
  return Object.fromEntries(Object.entries(value).filter(([, entry]) => entry !== undefined)) as T;
}
