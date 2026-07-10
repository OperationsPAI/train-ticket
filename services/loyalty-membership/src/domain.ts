import { randomUUID } from "node:crypto";

export type MemberId = string;
export type AccountId = string;
export type PointsLedgerEntryId = string;
export type PointsLotId = string;
export type RedemptionId = string;

export type MembershipStatus = "ACTIVE" | "SUSPENDED" | "CLOSED";
export type TierName = "BASIC" | "SILVER" | "GOLD" | "PLATINUM";
export type LedgerEntryType = "ACCRUAL" | "REDEMPTION" | "REVERSAL";

export class DomainError extends Error {
  constructor(
    public readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "DomainError";
  }
}

export type Money = Readonly<{
  currency: string;
  minorUnits: number;
}>;

export type SourceFactRef = Readonly<{
  stream: string;
  eventType: string;
  eventId: string;
  aggregateId: string;
  occurredAt: Date;
}>;

export type BusinessReason = Readonly<{
  reasonType: "ORDER_ACCRUAL" | "REDEMPTION" | "REVERSAL";
  reasonCode: string;
  referenceType: string;
  referenceId: string;
  description?: string;
}>;

export type PointsLedgerEntrySnapshot = Readonly<{
  entryId: PointsLedgerEntryId;
  memberId: MemberId;
  type: LedgerEntryType;
  points: number;
  sourceFactRef: SourceFactRef;
  businessReason: BusinessReason;
  idempotencyKey: string;
  occurredAt: Date;
  expiresAt?: Date;
}>;

export type PointsLotSnapshot = Readonly<{
  lotId: PointsLotId;
  memberId: MemberId;
  sourceEntryId: PointsLedgerEntryId;
  originalPoints: number;
  remainingPoints: number;
  tierEligiblePoints: number;
  validFrom: Date;
  expiresAt: Date;
}>;

export type MemberSnapshot = Readonly<{
  memberId: MemberId;
  accountId: AccountId;
  status: MembershipStatus;
  tier: TierName;
  redeemablePoints: number;
  lifetimePoints: number;
  tierPoints: number;
  createdAt: Date;
  updatedAt: Date;
  ledger: readonly PointsLedgerEntrySnapshot[];
  lots: readonly PointsLotSnapshot[];
}>;

export type PointsAccrued = Readonly<{
  type: "PointsAccrued";
  occurredAt: Date;
  memberId: MemberId;
  accountId: AccountId;
  points: number;
  tierPoints: number;
  balanceAfter: number;
  sourceFactRef: SourceFactRef;
  businessReason: BusinessReason;
  lotId: PointsLotId;
  expiresAt: Date;
  correlationId?: string;
}>;

export type PointsRedeemed = Readonly<{
  type: "PointsRedeemed";
  occurredAt: Date;
  memberId: MemberId;
  accountId: AccountId;
  redemptionId: RedemptionId;
  points: number;
  balanceAfter: number;
  businessReason: BusinessReason;
  correlationId?: string;
}>;

export type MembershipTierChanged = Readonly<{
  type: "MembershipTierChanged";
  occurredAt: Date;
  memberId: MemberId;
  accountId: AccountId;
  fromTier: TierName;
  toTier: TierName;
  tierPoints: number;
  reason: "ACCRUAL" | "EVALUATION" | "MANUAL_OVERRIDE";
  correlationId?: string;
}>;

export type LoyaltyDomainEvent = PointsAccrued | PointsRedeemed | MembershipTierChanged;

export type AccruePointsCommand = Readonly<{
  orderId: string;
  accountId: AccountId;
  ticketPrice: Money;
  sourceEventId: string;
  confirmedAt: Date;
  correlationId?: string;
}>;

export type RedeemPointsCommand = Readonly<{
  memberId: MemberId;
  points: number;
  redemptionId?: RedemptionId;
  reasonCode?: string;
  correlationId?: string;
}>;

const TIER_ORDER: readonly TierName[] = ["BASIC", "SILVER", "GOLD", "PLATINUM"];
const TIER_THRESHOLDS: Readonly<Record<TierName, number>> = {
  BASIC: 0,
  SILVER: 5_000,
  GOLD: 20_000,
  PLATINUM: 50_000,
};

export class Tier {
  private constructor(public readonly name: TierName) {}

  static of(name: TierName): Tier {
    if (!TIER_ORDER.includes(name)) {
      throw new DomainError("INVALID_TIER", `Unsupported membership tier ${name}`);
    }
    return new Tier(name);
  }

  static fromTierPoints(tierPoints: number): Tier {
    assertWholeNonNegative(tierPoints, "tierPoints");
    if (tierPoints >= TIER_THRESHOLDS.PLATINUM) return new Tier("PLATINUM");
    if (tierPoints >= TIER_THRESHOLDS.GOLD) return new Tier("GOLD");
    if (tierPoints >= TIER_THRESHOLDS.SILVER) return new Tier("SILVER");
    return new Tier("BASIC");
  }

  isHigherThan(other: Tier): boolean {
    return TIER_ORDER.indexOf(this.name) > TIER_ORDER.indexOf(other.name);
  }
}

export class PointsLedger {
  private constructor(
    private readonly entries: readonly PointsLedgerEntrySnapshot[],
    private readonly lots: readonly PointsLotSnapshot[],
  ) {}

  static empty(): PointsLedger {
    return new PointsLedger([], []);
  }

  static fromSnapshots(entries: readonly PointsLedgerEntrySnapshot[], lots: readonly PointsLotSnapshot[]): PointsLedger {
    return new PointsLedger(entries.map(cloneLedgerEntry), lots.map(cloneLot));
  }

  get balance(): number {
    return this.lots.reduce((total, lot) => total + lot.remainingPoints, 0);
  }

  get lifetimePoints(): number {
    return this.entries.filter((entry) => entry.type === "ACCRUAL").reduce((total, entry) => total + entry.points, 0);
  }

  tierPoints(at: Date = new Date()): number {
    const windowStart = new Date(at);
    windowStart.setUTCFullYear(windowStart.getUTCFullYear() - 1);
    return this.lots
      .filter((lot) => lot.validFrom >= windowStart && lot.validFrom <= at)
      .reduce((total, lot) => total + lot.tierEligiblePoints, 0);
  }

  hasSourceFact(sourceEventId: string): boolean {
    return this.entries.some((entry) => entry.sourceFactRef.eventId === sourceEventId);
  }

  accrue(input: {
    memberId: MemberId;
    points: number;
    sourceFactRef: SourceFactRef;
    businessReason: BusinessReason;
    idempotencyKey: string;
    occurredAt: Date;
    expiresAt?: Date;
  }): { ledger: PointsLedger; entry: PointsLedgerEntrySnapshot; lot: PointsLotSnapshot } {
    assertPositiveInteger(input.points, "points");
    if (this.hasSourceFact(input.sourceFactRef.eventId) || this.entries.some((entry) => entry.idempotencyKey === input.idempotencyKey)) {
      throw new DomainError("POINTS_ALREADY_ACCRUED", "Source fact has already produced a ledger entry");
    }
    const expiresAt = input.expiresAt ?? addUtcYears(input.occurredAt, 2);
    const entry: PointsLedgerEntrySnapshot = freeze({
      entryId: newLedgerEntryId(),
      memberId: input.memberId,
      type: "ACCRUAL",
      points: input.points,
      sourceFactRef: cloneSourceFact(input.sourceFactRef),
      businessReason: input.businessReason,
      idempotencyKey: input.idempotencyKey,
      occurredAt: new Date(input.occurredAt),
      expiresAt,
    });
    const lot: PointsLotSnapshot = freeze({
      lotId: newPointsLotId(),
      memberId: input.memberId,
      sourceEntryId: entry.entryId,
      originalPoints: input.points,
      remainingPoints: input.points,
      tierEligiblePoints: input.points,
      validFrom: new Date(input.occurredAt),
      expiresAt,
    });
    return { ledger: new PointsLedger([...this.entries, entry], [...this.lots, lot]), entry, lot };
  }

  redeem(input: {
    memberId: MemberId;
    points: number;
    redemptionId: RedemptionId;
    businessReason: BusinessReason;
    occurredAt: Date;
  }): { ledger: PointsLedger; entry: PointsLedgerEntrySnapshot } {
    assertPositiveInteger(input.points, "points");
    if (input.points > this.balance) {
      throw new DomainError("INSUFFICIENT_POINTS", "Available points cannot cover redemption");
    }
    const sourceFactRef: SourceFactRef = freeze({
      stream: "commands:loyalty-membership",
      eventType: "POINTS_REDEMPTION_REQUESTED",
      eventId: input.redemptionId,
      aggregateId: input.memberId,
      occurredAt: new Date(input.occurredAt),
    });
    const entry: PointsLedgerEntrySnapshot = freeze({
      entryId: newLedgerEntryId(),
      memberId: input.memberId,
      type: "REDEMPTION",
      points: -input.points,
      sourceFactRef,
      businessReason: input.businessReason,
      idempotencyKey: `${input.memberId}:${input.redemptionId}`,
      occurredAt: new Date(input.occurredAt),
    });
    return { ledger: new PointsLedger([...this.entries, entry], consumeLots(this.lots, input.points)), entry };
  }

  toSnapshot(): Readonly<{ entries: readonly PointsLedgerEntrySnapshot[]; lots: readonly PointsLotSnapshot[] }> {
    return freeze({
      entries: this.entries.map(cloneLedgerEntry),
      lots: this.lots.map(cloneLot),
    });
  }
}

export class Member {
  private constructor(private readonly snapshot: MemberSnapshot) {}

  static enroll(input: { accountId: AccountId; memberId?: MemberId; now?: Date }): Member {
    const now = input.now ?? new Date();
    const accountId = required(input.accountId, "accountId");
    return new Member(freeze({
      memberId: input.memberId ?? newMemberId(),
      accountId,
      status: "ACTIVE",
      tier: "BASIC",
      redeemablePoints: 0,
      lifetimePoints: 0,
      tierPoints: 0,
      createdAt: now,
      updatedAt: now,
      ledger: [],
      lots: [],
    }));
  }

  static fromSnapshot(snapshot: MemberSnapshot): Member {
    return new Member(freeze({
      ...snapshot,
      createdAt: new Date(snapshot.createdAt),
      updatedAt: new Date(snapshot.updatedAt),
      ledger: snapshot.ledger.map(cloneLedgerEntry),
      lots: snapshot.lots.map(cloneLot),
    }));
  }

  get id(): MemberId {
    return this.snapshot.memberId;
  }

  get accountId(): AccountId {
    return this.snapshot.accountId;
  }

  get tier(): TierName {
    return this.snapshot.tier;
  }

  get redeemablePoints(): number {
    return this.snapshot.redeemablePoints;
  }

  accrueFromConfirmedOrder(command: AccruePointsCommand, now = new Date()): { member: Member; events: LoyaltyDomainEvent[] } {
    this.assertActive();
    const points = pointsForTicketPrice(command.ticketPrice);
    if (points === 0) {
      throw new DomainError("NO_POINTS_TO_ACCRUE", "Ticket price is too small to accrue points");
    }
    const ledger = PointsLedger.fromSnapshots(this.snapshot.ledger, this.snapshot.lots);
    const sourceFactRef: SourceFactRef = freeze({
      stream: "events:journey-order",
      eventType: "JOURNEY_ORDER_CONFIRMED",
      eventId: required(command.sourceEventId, "sourceEventId"),
      aggregateId: required(command.orderId, "orderId"),
      occurredAt: command.confirmedAt,
    });
    const businessReason: BusinessReason = freeze({
      reasonType: "ORDER_ACCRUAL",
      reasonCode: "JOURNEY_ORDER_CONFIRMED",
      referenceType: "JOURNEY_ORDER",
      referenceId: command.orderId,
    });
    const { ledger: accruedLedger, lot } = ledger.accrue({
      memberId: this.id,
      points,
      sourceFactRef,
      businessReason,
      idempotencyKey: `${this.id}:${command.sourceEventId}`,
      occurredAt: command.confirmedAt,
    });
    const ledgerSnapshot = accruedLedger.toSnapshot();
    const tierPoints = accruedLedger.tierPoints(now);
    const currentTier = Tier.of(this.snapshot.tier);
    const targetTier = Tier.fromTierPoints(tierPoints);
    const nextTier = targetTier.isHigherThan(currentTier) ? targetTier.name : currentTier.name;
    const nextSnapshot = freeze({
      ...this.snapshot,
      tier: nextTier,
      redeemablePoints: accruedLedger.balance,
      lifetimePoints: accruedLedger.lifetimePoints,
      tierPoints,
      updatedAt: now,
      ledger: ledgerSnapshot.entries,
      lots: ledgerSnapshot.lots,
    });
    const accrued: PointsAccrued = freeze({
      type: "PointsAccrued",
      occurredAt: now,
      memberId: this.id,
      accountId: this.accountId,
      points,
      tierPoints: points,
      balanceAfter: accruedLedger.balance,
      sourceFactRef,
      businessReason,
      lotId: lot.lotId,
      expiresAt: lot.expiresAt,
      correlationId: command.correlationId,
    });
    const events: LoyaltyDomainEvent[] = [accrued];
    if (nextTier !== this.snapshot.tier) {
      events.push(freeze({
        type: "MembershipTierChanged",
        occurredAt: now,
        memberId: this.id,
        accountId: this.accountId,
        fromTier: this.snapshot.tier,
        toTier: nextTier,
        tierPoints,
        reason: "ACCRUAL" as const,
        correlationId: command.correlationId,
      }));
    }
    return { member: new Member(nextSnapshot), events };
  }

  redeem(command: RedeemPointsCommand, now = new Date()): { member: Member; event: PointsRedeemed } {
    this.assertActive();
    const redemptionId = command.redemptionId ?? newRedemptionId();
    const businessReason: BusinessReason = freeze({
      reasonType: "REDEMPTION",
      reasonCode: command.reasonCode ?? "CATALOG_REDEMPTION",
      referenceType: "POINTS_REDEMPTION",
      referenceId: redemptionId,
    });
    const ledger = PointsLedger.fromSnapshots(this.snapshot.ledger, this.snapshot.lots);
    const { ledger: redeemedLedger } = ledger.redeem({ memberId: this.id, points: command.points, redemptionId, businessReason, occurredAt: now });
    const ledgerSnapshot = redeemedLedger.toSnapshot();
    const nextSnapshot = freeze({
      ...this.snapshot,
      redeemablePoints: redeemedLedger.balance,
      lifetimePoints: redeemedLedger.lifetimePoints,
      tierPoints: redeemedLedger.tierPoints(now),
      updatedAt: now,
      ledger: ledgerSnapshot.entries,
      lots: ledgerSnapshot.lots,
    });
    return {
      member: new Member(nextSnapshot),
      event: freeze({
        type: "PointsRedeemed",
        occurredAt: now,
        memberId: this.id,
        accountId: this.accountId,
        redemptionId,
        points: command.points,
        balanceAfter: redeemedLedger.balance,
        businessReason,
        correlationId: command.correlationId,
      }),
    };
  }

  toSnapshot(): MemberSnapshot {
    return freeze({
      ...this.snapshot,
      createdAt: new Date(this.snapshot.createdAt),
      updatedAt: new Date(this.snapshot.updatedAt),
      ledger: this.snapshot.ledger.map(cloneLedgerEntry),
      lots: this.snapshot.lots.map(cloneLot),
    });
  }

  private assertActive(): void {
    if (this.snapshot.status !== "ACTIVE") {
      throw new DomainError("MEMBERSHIP_NOT_ACTIVE", "Only active memberships can accrue or redeem points");
    }
  }
}

export function pointsForTicketPrice(price: Money): number {
  if (price.currency !== "CNY") {
    throw new DomainError("UNSUPPORTED_CURRENCY", "Loyalty accrual currently supports CNY only");
  }
  assertWholeNonNegative(price.minorUnits, "minorUnits");
  return Math.floor(price.minorUnits / 10_000);
}

function consumeLots(lots: readonly PointsLotSnapshot[], points: number): PointsLotSnapshot[] {
  let remainingToConsume = points;
  return [...lots]
    .sort((left, right) => left.expiresAt.getTime() - right.expiresAt.getTime())
    .map((lot) => {
      if (remainingToConsume === 0 || lot.remainingPoints === 0) {
        return cloneLot(lot);
      }
      const consumed = Math.min(lot.remainingPoints, remainingToConsume);
      remainingToConsume -= consumed;
      return freeze({ ...lot, remainingPoints: lot.remainingPoints - consumed });
    });
}

function assertPositiveInteger(value: number, field: string): void {
  if (!Number.isInteger(value) || value <= 0) {
    throw new DomainError("INVALID_POINTS", `${field} must be a positive integer`);
  }
}

function assertWholeNonNegative(value: number, field: string): void {
  if (!Number.isInteger(value) || value < 0) {
    throw new DomainError("INVALID_AMOUNT", `${field} must be a non-negative integer`);
  }
}

function required(value: string, field: string): string {
  if (value.trim().length === 0) {
    throw new DomainError("MISSING_REQUIRED_FIELD", `${field} is required`);
  }
  return value;
}

function addUtcYears(date: Date, years: number): Date {
  const copy = new Date(date);
  copy.setUTCFullYear(copy.getUTCFullYear() + years);
  return copy;
}

function cloneLedgerEntry(entry: PointsLedgerEntrySnapshot): PointsLedgerEntrySnapshot {
  return freeze({
    ...entry,
    sourceFactRef: cloneSourceFact(entry.sourceFactRef),
    businessReason: freeze({ ...entry.businessReason }),
    occurredAt: new Date(entry.occurredAt),
    expiresAt: entry.expiresAt ? new Date(entry.expiresAt) : undefined,
  });
}

function cloneSourceFact(source: SourceFactRef): SourceFactRef {
  return freeze({ ...source, occurredAt: new Date(source.occurredAt) });
}

function cloneLot(lot: PointsLotSnapshot): PointsLotSnapshot {
  return freeze({ ...lot, validFrom: new Date(lot.validFrom), expiresAt: new Date(lot.expiresAt) });
}

function freeze<T extends object>(value: T): Readonly<T> {
  return Object.freeze(value);
}

function newMemberId(): MemberId {
  return `mem_${randomUUID()}`;
}

function newLedgerEntryId(): PointsLedgerEntryId {
  return `ple_${randomUUID()}`;
}

function newPointsLotId(): PointsLotId {
  return `lot_${randomUUID()}`;
}

function newRedemptionId(): RedemptionId {
  return `red_${randomUUID()}`;
}
