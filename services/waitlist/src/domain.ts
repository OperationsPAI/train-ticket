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
  travelerRefs: readonly string[];
  departureDate: string;
  seatClass: FareClass;
  priorityScore: number;
  queuePosition: number;
  version: number;
  loadedVersion: number;
  createdAt: string;
  updatedAt: string;
  matchingStartedAt: string | null;
  cancelledAt: string | null;
  expiredAt: string | null;
  fulfilledAt: string | null;
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
  pendingJourneyOrderRef?: string;
}>;

export type CreateWaitlistEntry = Readonly<{
  entryId?: string;
  waitlistRequestId?: string;
  accountId: string;
  travelerRef?: string;
  travelerRefs?: readonly string[];
  segmentRef: string;
  departureDate?: string;
  deadline: string | Date;
  seatClass?: FareClass;
  travelClass?: FareClass;
  paymentGuaranteeRef: string;
  itineraryRef: string;
  intentFingerprint: string;
  priority: Omit<PriorityInput, "groupSize" | "fareClass"> & Partial<Pick<PriorityInput, "groupSize" | "fareClass">>;
  createdAt?: Date;
}>;

export class DomainError extends Error {
  constructor(public readonly code: "VALIDATION_FAILED" | "INVALID_TRANSITION" | "NOT_FOUND" | "PRECONDITION_FAILED" | "CONFLICT" | "DOMAIN_RULE_VIOLATION", message: string) {
    super(message);
    this.name = "DomainError";
  }
}

export class WaitlistEntry {
  private constructor(
    public readonly entryId: string,
    public readonly accountId: string,
    public readonly travelerRef: string,
    public readonly travelerRefs: readonly string[],
    public readonly segmentRef: string,
    public readonly departureDate: string,
    public readonly seatClass: FareClass,
    public readonly deadline: Date,
    public readonly paymentGuaranteeRef: string,
    public readonly itineraryRef: string,
    public readonly intentFingerprint: string,
    public readonly priorityScore: number,
    public readonly createdAt: Date,
    private _status: WaitlistStatus,
    private _version: number,
    private _loadedVersion: number,
    private _updatedAt: Date = createdAt,
    private _matchingStartedAt: Date | null = null,
    private _cancelledAt: Date | null = null,
    private _expiredAt: Date | null = null,
    private _fulfilledAt: Date | null = null,
    private _journeyOrderRef?: string,
    private _pendingJourneyOrderRef?: string,
    private _fareQuoteId?: string,
    private _capacityHoldId?: string,
    private _offerId?: string,
    private _offerVersion?: number,
    private readonly _fareQuoteIdempotencyKey: string = uuidV7(),
    private readonly _offerIdempotencyKey: string = uuidV7(),
    private readonly _capacityHoldIdempotencyKey: string = uuidV7(),
    private readonly _capacityReleaseIdempotencyKey: string = uuidV7(),
    private readonly _journeyOrderIdempotencyKey: string = uuidV7(),
    private readonly _capacitySegmentBookingId: string = `sb-${uuidV7()}`,
  ) {}

  static create(command: CreateWaitlistEntry): WaitlistEntry {
    assertNonEmpty(command.accountId, "accountId");
    assertNonEmpty(command.segmentRef, "segmentRef");
    const travelerRef = command.travelerRef ?? command.travelerRefs?.[0];
    if (!travelerRef) throw new DomainError("VALIDATION_FAILED", "travelerRef is required");
    const deadline = new Date(command.deadline);
    if (!Number.isFinite(deadline.getTime())) throw new DomainError("VALIDATION_FAILED", "deadline must be a valid RFC3339 timestamp");
    const createdAt = command.createdAt ?? new Date();
    if (deadline <= createdAt) throw new DomainError("DOMAIN_RULE_VIOLATION", "deadline must be in the future");
    const paymentGuaranteeRef = required(command.paymentGuaranteeRef, "paymentGuaranteeRef");
    if (!validPaymentGuarantee(paymentGuaranteeRef)) throw new DomainError("DOMAIN_RULE_VIOLATION", "paymentGuaranteeRef must start with pay-auth- or pi-");
    const seatClass = command.travelClass ?? command.seatClass ?? command.priority.fareClass ?? "SECOND";
    const departureDate = command.departureDate ?? deadline.toISOString().slice(0, 10);
    const itineraryRef = required(command.itineraryRef, "itineraryRef");
    const intentFingerprint = required(command.intentFingerprint, "intentFingerprint");
    const groupSize = command.priority.groupSize ?? command.travelerRefs?.length ?? 1;
    const fareClass = command.priority.fareClass ?? seatClass;
    const priorityScore = PriorityCalculator.calculate({ ...command.priority, groupSize, fareClass });
    return new WaitlistEntry(command.waitlistRequestId ?? command.entryId ?? `wlr-${uuidV7()}`, command.accountId, travelerRef, [...(command.travelerRefs ?? [travelerRef])], command.segmentRef, departureDate, seatClass, deadline, paymentGuaranteeRef, itineraryRef, intentFingerprint, priorityScore, createdAt, "DRAFT", 1, 1, createdAt);
  }

  static fromSnapshot(snapshot: WaitlistEntrySnapshot): WaitlistEntry {
    return new WaitlistEntry(snapshot.waitlistRequestId ?? snapshot.entryId, snapshot.accountId, snapshot.travelerRef ?? snapshot.travelerRefs[0] ?? "", snapshot.travelerRefs.length > 0 ? [...snapshot.travelerRefs] : [snapshot.travelerRef ?? ""], snapshot.segmentRef, snapshot.departureDate, snapshot.seatClass, new Date(snapshot.deadline), snapshot.paymentGuaranteeRef, snapshot.itineraryRef, snapshot.intentFingerprint, snapshot.priorityScore, new Date(snapshot.createdAt), snapshot.status, snapshot.version, snapshot.loadedVersion ?? snapshot.version, new Date(snapshot.updatedAt ?? snapshot.createdAt), snapshot.matchingStartedAt ? new Date(snapshot.matchingStartedAt) : null, snapshot.cancelledAt ? new Date(snapshot.cancelledAt) : null, snapshot.expiredAt ? new Date(snapshot.expiredAt) : null, snapshot.fulfilledAt ? new Date(snapshot.fulfilledAt) : null, snapshot.journeyOrderRef, snapshot.pendingJourneyOrderRef, snapshot.fareQuoteId, snapshot.capacityHoldId, snapshot.offerId, snapshot.offerVersion, snapshot.fareQuoteIdempotencyKey, snapshot.offerIdempotencyKey, snapshot.capacityHoldIdempotencyKey, snapshot.capacityReleaseIdempotencyKey, snapshot.journeyOrderIdempotencyKey, snapshot.capacitySegmentBookingId);
  }

  get waitlistRequestId(): string { return this.entryId; }
  get status(): WaitlistStatus { return this._status; }
  get version(): number { return this._version; }
  get loadedVersion(): number { return this._loadedVersion; }
  get updatedAt(): Date { return this._updatedAt; }
  get matchingStartedAt(): Date | null { return this._matchingStartedAt; }
  get cancelledAt(): Date | null { return this._cancelledAt; }
  get expiredAt(): Date | null { return this._expiredAt; }
  get fulfilledAt(): Date | null { return this._fulfilledAt; }
  get journeyOrderRef(): string | undefined { return this._journeyOrderRef; }
  get pendingJourneyOrderRef(): string | undefined { return this._pendingJourneyOrderRef; }
  get offerExpiresAt(): Date | null { return this.deadline; }
  get fareQuoteId(): string | undefined { return this._fareQuoteId; }
  get capacityHoldId(): string | undefined { return this._capacityHoldId; }
  get offerId(): string | undefined { return this._offerId; }
  get offerVersion(): number | undefined { return this._offerVersion; }
  get fareQuoteIdempotencyKey(): string { return this._fareQuoteIdempotencyKey; }
  get offerIdempotencyKey(): string { return this._offerIdempotencyKey; }
  get capacityHoldIdempotencyKey(): string { return this._capacityHoldIdempotencyKey; }
  get capacityReleaseIdempotencyKey(): string { return this._capacityReleaseIdempotencyKey; }
  get journeyOrderIdempotencyKey(): string { return this._journeyOrderIdempotencyKey; }
  get capacitySegmentBookingId(): string { return this._capacitySegmentBookingId; }

  authorizePayment(now: Date): void {
    this.assertStatus("DRAFT", "Only draft waitlist requests can record payment authorization");
    this.touch(now);
  }

  enqueue(now: Date): void {
    if (this._status !== "DRAFT" && this._status !== "SUSPENDED" && this._status !== "MATCHING") throw new DomainError("INVALID_TRANSITION", `Cannot queue ${this._status} waitlist request`);
    this._status = "QUEUED";
    this.touch(now);
  }

  startMatching(offerId: string, offerVersion: number, fareQuoteId: string, capacityHoldId: string, now: Date): void {
    this.assertStatus("QUEUED", "Only queued waitlist requests can start matching");
    if (!Number.isInteger(offerVersion) || offerVersion < 1) throw new DomainError("VALIDATION_FAILED", "offerVersion must be positive");
    this._status = "MATCHING";
    this._matchingStartedAt = now;
    this._fareQuoteId = fareQuoteId;
    this._capacityHoldId = capacityHoldId;
    this._offerId = offerId;
    this._offerVersion = offerVersion;
    this.touch(now);
  }

  recordJourneyOrderStarted(journeyOrderRef: string, now: Date): void {
    this.assertStatus("MATCHING", "Only matching waitlist requests can record a journey order");
    const ref = required(journeyOrderRef, "journeyOrderRef");
    if (this._pendingJourneyOrderRef === ref) return;
    if (this._pendingJourneyOrderRef && this._pendingJourneyOrderRef !== ref) throw new DomainError("PRECONDITION_FAILED", "Waitlist request is already associated with a different journey order");
    this._pendingJourneyOrderRef = ref;
    this.touch(now);
  }

  fulfill(journeyOrderRef: string, now: Date): void {
    this.assertStatus("MATCHING", "Only matching waitlist requests can be fulfilled");
    const ref = required(journeyOrderRef, "journeyOrderRef");
    if (this._pendingJourneyOrderRef && this._pendingJourneyOrderRef !== ref) throw new DomainError("PRECONDITION_FAILED", "Journey order does not match the waitlist request");
    this._status = "FULFILLED";
    this._journeyOrderRef = ref;
    this._pendingJourneyOrderRef = undefined;
    this._fulfilledAt = now;
    this.touch(now);
  }

  requeueAfterJourneyOrderCancelled(journeyOrderRef: string, now: Date): void {
    this.assertStatus("MATCHING", "Only matching waitlist requests can be requeued after journey-order cancellation");
    const ref = required(journeyOrderRef, "journeyOrderRef");
    if (this._pendingJourneyOrderRef && this._pendingJourneyOrderRef !== ref) throw new DomainError("PRECONDITION_FAILED", "Journey order does not match the waitlist request");
    this._pendingJourneyOrderRef = undefined;
    this._matchingStartedAt = null;
    this._fareQuoteId = undefined;
    this._capacityHoldId = undefined;
    this._offerId = undefined;
    this._offerVersion = undefined;
    this._status = "QUEUED";
    this.touch(now);
  }

  expire(now: Date): void {
    if (this._status !== "QUEUED" && this._status !== "MATCHING" && this._status !== "SUSPENDED") throw new DomainError("INVALID_TRANSITION", `Cannot expire ${this._status} waitlist request`);
    if (now < this.deadline) throw new DomainError("PRECONDITION_FAILED", "Waitlist request has not reached its deadline");
    this._status = "EXPIRED";
    this._expiredAt = now;
    this.touch(now);
  }

  cancel(now: Date): void {
    if (isTerminal(this._status)) throw new DomainError("PRECONDITION_FAILED", `Cannot cancel ${this._status} waitlist request`);
    this._status = "CANCELLED";
    this._cancelledAt = now;
    this.touch(now);
  }

  close(now: Date): void {
    if (this._status !== "FULFILLED" && this._status !== "EXPIRED" && this._status !== "CANCELLED") throw new DomainError("INVALID_TRANSITION", `Cannot close ${this._status} waitlist request`);
    this._status = "CLOSED";
    this.touch(now);
  }

  markPersisted(): void { this._loadedVersion = this._version; }

  toResource(): WaitlistRequestResource {
    return omitUndefined({ waitlistRequestId: this.waitlistRequestId, accountId: this.accountId, travelerRef: this.travelerRef, segmentRef: this.segmentRef, travelClass: this.seatClass, deadline: this.deadline.toISOString(), paymentGuaranteeRef: this.paymentGuaranteeRef, itineraryRef: this.itineraryRef, intentFingerprint: this.intentFingerprint, status: this._status, journeyOrderRef: this._journeyOrderRef });
  }

  toSnapshot(queuePosition = 0): WaitlistEntrySnapshot {
    return { ...this.toResource(), entryId: this.entryId, travelerRefs: [...this.travelerRefs], departureDate: this.departureDate, seatClass: this.seatClass, priorityScore: this.priorityScore, queuePosition, version: this._version, loadedVersion: this._loadedVersion, createdAt: this.createdAt.toISOString(), updatedAt: this._updatedAt.toISOString(), matchingStartedAt: this._matchingStartedAt?.toISOString() ?? null, cancelledAt: this._cancelledAt?.toISOString() ?? null, expiredAt: this._expiredAt?.toISOString() ?? null, fulfilledAt: this._fulfilledAt?.toISOString() ?? null, pendingJourneyOrderRef: this._pendingJourneyOrderRef, fareQuoteId: this._fareQuoteId, capacityHoldId: this._capacityHoldId, offerId: this._offerId, offerVersion: this._offerVersion, fareQuoteIdempotencyKey: this._fareQuoteIdempotencyKey, offerIdempotencyKey: this._offerIdempotencyKey, capacityHoldIdempotencyKey: this._capacityHoldIdempotencyKey, capacityReleaseIdempotencyKey: this._capacityReleaseIdempotencyKey, journeyOrderIdempotencyKey: this._journeyOrderIdempotencyKey, capacitySegmentBookingId: this._capacitySegmentBookingId };
  }

  private touch(now: Date): void {
    this._version += 1;
    this._updatedAt = now;
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
    this.entries.set(entry.entryId, entry);
  }

  get(entryId: string): WaitlistEntry | undefined { return this.entries.get(entryId); }
  queuedEntries(): readonly WaitlistEntry[] { return this.sorted().filter((entry) => entry.status === "QUEUED"); }
  allEntries(): readonly WaitlistEntry[] { return this.sorted().filter((entry) => entry.status !== "CLOSED"); }
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

export function isActiveStatus(status: WaitlistStatus): boolean { return status === "DRAFT" || status === "QUEUED" || status === "MATCHING" || status === "SUSPENDED"; }
export function isTerminal(status: WaitlistStatus): boolean { return status === "FULFILLED" || status === "EXPIRED" || status === "CANCELLED" || status === "CLOSED"; }

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
function assertNonEmpty(value: string | undefined, field: string): void { if (!value || value.trim().length === 0) throw new DomainError("VALIDATION_FAILED", `${field} is required`); }
function required(value: string | undefined, field: string): string { if (!value || value.trim().length === 0) throw new DomainError("VALIDATION_FAILED", `${field} is required`); return value; }
function validPaymentGuarantee(value: string): boolean { return value.startsWith("pay-auth-") || value.startsWith("pi-"); }
function omitUndefined<T extends Record<string, unknown>>(value: T): T { return Object.fromEntries(Object.entries(value).filter(([, entry]) => entry !== undefined)) as T; }
