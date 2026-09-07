import { randomUUID } from "node:crypto";

export type MemberId = string;
export type AccountId = string;
export type PointsLedgerEntryId = string;
export type PointsLotId = string;
export type RedemptionId = string;

export type MembershipStatus = "ACTIVE" | "SUSPENDED" | "CLOSED";
export type TierName = "SILVER" | "GOLD" | "PLATINUM" | "DIAMOND";
export type SeatClass = "SECOND_CLASS" | "FIRST_CLASS" | "BUSINESS_CLASS" | "二等座" | "一等座" | "商务座";
export type LedgerEntryType = "EARNED" | "REDEEMED" | "EXPIRED" | "ADJUSTED";

export class DomainError extends Error {
  constructor(public readonly code: string, message: string) {
    super(message);
    this.name = "DomainError";
  }
}

export type Money = Readonly<{ currency: string; minorUnits: number }>;
export type SourceFactRef = Readonly<{ stream: string; eventType: string; eventId: string; aggregateId: string; occurredAt: Date }>;
export type BusinessReason = Readonly<{
  reasonType: "ORDER_ACCRUAL" | "REDEMPTION" | "REVERSAL" | "EXPIRY" | "REFUND_ADJUSTMENT" | "TRIP_COUNT";
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

export type MembershipYearSnapshot = Readonly<{ year: number; qualifyingPoints: number; tripCount: number; currentTier: TierName; evaluationDate?: Date }>;

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
  membershipYears?: readonly MembershipYearSnapshot[];
}>;

export type PointsEarned = Readonly<{
  type: "PointsEarned";
  occurredAt: Date;
  memberId: MemberId;
  accountId: AccountId;
  points: number;
  sourceRef: string;
  sourceFactRef: SourceFactRef;
  businessReason: BusinessReason;
  lotId: PointsLotId;
  expiresAt: Date;
  balanceAfter: number;
  qualifyingPoints: number;
  correlationId?: string;
}>;

export type PointsRedeemed = Readonly<{
  type: "PointsRedeemed";
  occurredAt: Date;
  memberId: MemberId;
  accountId: AccountId;
  redemptionId: RedemptionId;
  points: number;
  orderId?: string;
  discountAmountMinor?: number;
  remainingBalance: number;
  balanceAfter: number;
  businessReason: BusinessReason;
  correlationId?: string;
}>;

export type PointsExpired = Readonly<{
  type: "PointsExpired";
  occurredAt: Date;
  memberId: MemberId;
  accountId: AccountId;
  points: number;
  batchId: PointsLotId;
  balanceAfter: number;
  correlationId?: string;
}>;


export type PointsRestored = Readonly<{
  type: "PointsRestored";
  occurredAt: Date;
  memberId: MemberId;
  accountId: AccountId;
  points: number;
  orderId: string;
  sourceFactRef: SourceFactRef;
  businessReason: BusinessReason;
  balanceAfter: number;
  correlationId?: string;
}>;

export type MemberTierUpgraded = Readonly<{
  type: "MemberTierUpgraded";
  occurredAt: Date;
  memberId: MemberId;
  accountId: AccountId;
  oldTier: TierName;
  newTier: TierName;
  qualifyingPoints: number;
  trips: number;
  correlationId?: string;
}>;

export type MemberTierDowngraded = Readonly<{
  type: "MemberTierDowngraded";
  occurredAt: Date;
  memberId: MemberId;
  accountId: AccountId;
  oldTier: TierName;
  newTier: TierName;
  qualifyingPoints: number;
  trips: number;
  correlationId?: string;
}>;

export type TierEvaluationCompleted = Readonly<{
  type: "TierEvaluationCompleted";
  occurredAt: Date;
  memberId: MemberId;
  accountId: AccountId;
  evaluationYear: number;
  resultingTier: TierName;
  qualifyingPoints: number;
  trips: number;
  correlationId?: string;
}>;

export type LoyaltyDomainEvent = PointsEarned | PointsRedeemed | PointsRestored | PointsExpired | MemberTierUpgraded | MemberTierDowngraded | TierEvaluationCompleted;

export type AccruePointsCommand = Readonly<{
  orderId: string;
  accountId: AccountId;
  ticketPrice: Money;
  sourceEventId: string;
  confirmedAt: Date;
  seatClass?: SeatClass;
  travelDate?: Date;
  routeCode?: string;
  routeName?: string;
  isHoliday?: boolean;
  memberBirthDate?: Date;
  tripCount?: number;
  sourceStream?: string;
  sourceEventType?: string;
  correlationId?: string;
}>;

export type RedeemPointsCommand = Readonly<{ memberId: MemberId; points: number; redemptionId?: RedemptionId; reasonCode?: string; correlationId?: string }>;
export type RedemptionRequest = Readonly<{ memberId: MemberId; orderId: string; pointsToRedeem: number; fareAmountMinor: number }>;
export type RedemptionResult = Readonly<{ pointsDeducted: number; discountAmountMinor: number; remainingBalance: number }>;
export type ExpiredBatch = Readonly<{ batchId: PointsLotId; points: number; earnedAt: Date; expiresAt: Date }>;
export type ExpiringBatch = Readonly<{ batchId: PointsLotId; pointsAmount: number; expiryDate: Date }>;
export type PointsEarningRule = Readonly<{ seatClassMultiplier: number; bonusConditions: readonly Readonly<{ name: string; multiplier: number }>[]; tierMultiplier: number }>;
/**
 * `travelDate` is optional, and its absence is meaningful: it means "the travel
 * date is not known", NOT "use some other date".
 *
 * The weekend, holiday and birthday-month bonuses in `bonusMultipliers` are
 * defined by the loyalty contract against the date the member TRAVELS (see
 * docs/10-domain-enrichment/loyalty-membership-enrichment.md R1, "Weekend
 * travel", "Holiday travel"). Substituting any other timestamp -- the purchase
 * time, the capture time, or the current clock -- silently prices a journey off
 * the wrong calendar day and awards bonuses nobody earned. When the date is
 * unknown we therefore award the base rate and skip the date-derived bonuses
 * rather than guess. An explicit upstream `isHoliday` assertion is still
 * honoured, because that is a stated fact rather than something derived here.
 */
export type PointsCalculationInput = Readonly<{ fare: Money; seatClass: SeatClass; travelDate?: Date; memberTier: TierName; routeCode?: string; routeName?: string; isHoliday?: boolean; memberBirthDate?: Date }>;
export type TierPolicy = Readonly<{ tierName: TierName; minQualifyingPoints: number; minTrips: number; benefits: readonly string[] }>;
export type RedemptionPolicySnapshot = Readonly<{ pointsToCurrencyRate: number; minRedemption: number; maxFarePercentage: number }>;

const TIER_ORDER: readonly TierName[] = ["SILVER", "GOLD", "PLATINUM", "DIAMOND"];
const TIER_POLICIES: readonly TierPolicy[] = [
  { tierName: "SILVER", minQualifyingPoints: 0, minTrips: 0, benefits: ["base earning"] },
  { tierName: "GOLD", minQualifyingPoints: 3_000, minTrips: 12, benefits: ["1.2x points"] },
  { tierName: "PLATINUM", minQualifyingPoints: 10_000, minTrips: 30, benefits: ["1.5x points"] },
  { tierName: "DIAMOND", minQualifyingPoints: 25_000, minTrips: 60, benefits: ["2x points", "36 month points expiry"] },
];

export class Tier {
  private constructor(public readonly name: TierName) {}
  static of(name: TierName): Tier {
    if (!TIER_ORDER.includes(name)) throw new DomainError("INVALID_TIER", `Unsupported membership tier ${name}`);
    return new Tier(name);
  }
  static fromTierPoints(tierPoints: number): Tier { return new Tier(TierEvaluator.evaluate(tierPoints, 0)); }
  isHigherThan(other: Tier): boolean { return TIER_ORDER.indexOf(this.name) > TIER_ORDER.indexOf(other.name); }
  isLowerThan(other: Tier): boolean { return TIER_ORDER.indexOf(this.name) < TIER_ORDER.indexOf(other.name); }
}

export class TierEvaluator {
  static evaluate(yearlyQualifyingPoints: number, yearlyTrips: number): TierName {
    assertWholeNonNegative(yearlyQualifyingPoints, "yearlyQualifyingPoints");
    assertWholeNonNegative(yearlyTrips, "yearlyTrips");
    for (const policy of [...TIER_POLICIES].reverse()) {
      if (yearlyQualifyingPoints >= policy.minQualifyingPoints || yearlyTrips >= policy.minTrips) return policy.tierName;
    }
    return "SILVER";
  }
  static policies(): readonly TierPolicy[] { return TIER_POLICIES.map((policy) => freeze({ ...policy, benefits: [...policy.benefits] })); }
}

export class PointsCalculator {
  static calculate(input: PointsCalculationInput): number { return calculatePoints(input).points; }
  static earningRule(input: Omit<PointsCalculationInput, "fare">): PointsEarningRule {
    return freeze({ seatClassMultiplier: seatClassMultiplier(input.seatClass), bonusConditions: bonusMultipliers(input), tierMultiplier: tierMultiplier(input.memberTier) });
  }
}

export class RedemptionPolicy {
  static readonly standard = new RedemptionPolicy({ pointsToCurrencyRate: 100, minRedemption: 500, maxFarePercentage: 0.5 });
  constructor(private readonly snapshot: RedemptionPolicySnapshot) {}
  maxRedeemablePoints(fareAmountMinor: number, availableBalance = Number.MAX_SAFE_INTEGER): number {
    assertWholeNonNegative(fareAmountMinor, "fareAmountMinor");
    assertWholeNonNegative(availableBalance, "availableBalance");
    return Math.min(Math.floor((Math.floor(fareAmountMinor * this.snapshot.maxFarePercentage) / 100) * this.snapshot.pointsToCurrencyRate), availableBalance);
  }
  apply(request: RedemptionRequest, availableBalance: number): RedemptionResult {
    required(request.memberId, "memberId");
    required(request.orderId, "orderId");
    assertPositiveInteger(request.pointsToRedeem, "pointsToRedeem");
    assertWholeNonNegative(request.fareAmountMinor, "fareAmountMinor");
    assertWholeNonNegative(availableBalance, "availableBalance");
    if (request.pointsToRedeem < this.snapshot.minRedemption) throw new DomainError("REDEMPTION_BELOW_MINIMUM", `At least ${this.snapshot.minRedemption} points must be redeemed`);
    if (availableBalance < this.snapshot.minRedemption) throw new DomainError("INSUFFICIENT_POINTS", "Available points cannot cover minimum redemption");
    const pointsDeducted = Math.min(request.pointsToRedeem, this.maxRedeemablePoints(request.fareAmountMinor, availableBalance));
    if (pointsDeducted < this.snapshot.minRedemption) throw new DomainError("REDEMPTION_CAP_BELOW_MINIMUM", "Fare cap is below the minimum redemption amount");
    return freeze({ pointsDeducted, discountAmountMinor: this.pointsToCurrencyMinor(pointsDeducted), remainingBalance: availableBalance - pointsDeducted });
  }
  pointsToCurrencyMinor(points: number): number { assertWholeNonNegative(points, "points"); return Math.floor((points / this.snapshot.pointsToCurrencyRate) * 100); }
  toSnapshot(): RedemptionPolicySnapshot { return freeze({ ...this.snapshot }); }
}

export class PointsLedger {
  private constructor(private readonly entries: readonly PointsLedgerEntrySnapshot[], private readonly lots: readonly PointsLotSnapshot[]) {}
  static empty(): PointsLedger { return new PointsLedger([], []); }
  static fromSnapshots(entries: readonly PointsLedgerEntrySnapshot[], lots: readonly PointsLotSnapshot[]): PointsLedger { return new PointsLedger(entries.map(cloneLedgerEntry), lots.map(cloneLot)); }
  get balance(): number { return this.lots.reduce((total, lot) => total + lot.remainingPoints, 0); }
  get lifetimePoints(): number { return this.entries.filter((entry) => entry.type === "EARNED").reduce((total, entry) => total + entry.points, 0); }
  tierPoints(at: Date = new Date()): number { return this.yearlyQualifyingPoints(at.getUTCFullYear()); }
  yearlyQualifyingPoints(year: number): number { return this.lots.filter((lot) => lot.validFrom.getUTCFullYear() === year).reduce((total, lot) => total + lot.tierEligiblePoints, 0); }
  hasSourceFact(sourceEventId: string): boolean { return this.entries.some((entry) => entry.sourceFactRef.eventId === sourceEventId); }

  accrue(input: { memberId: MemberId; points: number; tierEligiblePoints?: number; sourceFactRef: SourceFactRef; businessReason: BusinessReason; idempotencyKey: string; occurredAt: Date; expiresAt?: Date }): { ledger: PointsLedger; entry: PointsLedgerEntrySnapshot; lot: PointsLotSnapshot } {
    assertPositiveInteger(input.points, "points");
    if (this.hasSourceFact(input.sourceFactRef.eventId) || this.entries.some((entry) => entry.idempotencyKey === input.idempotencyKey)) throw new DomainError("POINTS_ALREADY_ACCRUED", "Source fact has already produced a ledger entry");
    const tierEligiblePoints = input.tierEligiblePoints ?? input.points;
    assertWholeNonNegative(tierEligiblePoints, "tierEligiblePoints");
    const expiresAt = input.expiresAt ?? addUtcMonths(input.occurredAt, 24);
    const entry: PointsLedgerEntrySnapshot = freeze({ entryId: newLedgerEntryId(), memberId: input.memberId, type: "EARNED", points: input.points, sourceFactRef: cloneSourceFact(input.sourceFactRef), businessReason: input.businessReason, idempotencyKey: input.idempotencyKey, occurredAt: new Date(input.occurredAt), expiresAt });
    const lot: PointsLotSnapshot = freeze({ lotId: newPointsLotId(), memberId: input.memberId, sourceEntryId: entry.entryId, originalPoints: input.points, remainingPoints: input.points, tierEligiblePoints, validFrom: new Date(input.occurredAt), expiresAt });
    return { ledger: new PointsLedger([...this.entries, entry], [...this.lots, lot]), entry, lot };
  }

  redeem(input: { memberId: MemberId; points: number; redemptionId: RedemptionId; businessReason: BusinessReason; occurredAt: Date }): { ledger: PointsLedger; entry: PointsLedgerEntrySnapshot } {
    assertPositiveInteger(input.points, "points");
    if (input.points > this.balance) throw new DomainError("INSUFFICIENT_POINTS", "Available points cannot cover redemption");
    const sourceFactRef: SourceFactRef = freeze({ stream: "commands:loyalty-membership", eventType: "POINTS_REDEMPTION_REQUESTED", eventId: input.redemptionId, aggregateId: input.memberId, occurredAt: new Date(input.occurredAt) });
    const entry: PointsLedgerEntrySnapshot = freeze({ entryId: newLedgerEntryId(), memberId: input.memberId, type: "REDEEMED", points: -input.points, sourceFactRef, businessReason: input.businessReason, idempotencyKey: `${input.memberId}:${input.redemptionId}`, occurredAt: new Date(input.occurredAt) });
    return { ledger: new PointsLedger([...this.entries, entry], consumeLots(this.lots, input.points)), entry };
  }

  restore(input: { memberId: MemberId; points: number; sourceFactRef: SourceFactRef; businessReason: BusinessReason; idempotencyKey: string; occurredAt: Date; expiresAt?: Date }): { ledger: PointsLedger; entry: PointsLedgerEntrySnapshot; lot: PointsLotSnapshot } {
    assertPositiveInteger(input.points, "points");
    if (this.hasSourceFact(input.sourceFactRef.eventId) || this.entries.some((entry) => entry.idempotencyKey === input.idempotencyKey)) throw new DomainError("POINTS_ALREADY_RESTORED", "Source fact has already restored redeemed points");
    const expiresAt = input.expiresAt ?? addUtcMonths(input.occurredAt, 24);
    const entry: PointsLedgerEntrySnapshot = freeze({ entryId: newLedgerEntryId(), memberId: input.memberId, type: "ADJUSTED", points: input.points, sourceFactRef: cloneSourceFact(input.sourceFactRef), businessReason: input.businessReason, idempotencyKey: input.idempotencyKey, occurredAt: new Date(input.occurredAt), expiresAt });
    const lot: PointsLotSnapshot = freeze({ lotId: newPointsLotId(), memberId: input.memberId, sourceEntryId: entry.entryId, originalPoints: input.points, remainingPoints: input.points, tierEligiblePoints: 0, validFrom: new Date(input.occurredAt), expiresAt });
    return { ledger: new PointsLedger([...this.entries, entry], [...this.lots, lot]), entry, lot };
  }

  expirePoints(memberId: MemberId, now: Date): { ledger: PointsLedger; expired: readonly ExpiredBatch[] } {
    const expiredLots = this.lots.filter((lot) => lot.remainingPoints > 0 && lot.expiresAt <= now);
    if (expiredLots.length === 0) return { ledger: this, expired: [] };
    const entries = expiredLots.map((lot) => freeze({ entryId: newLedgerEntryId(), memberId, type: "EXPIRED" as const, points: -lot.remainingPoints, sourceFactRef: freeze({ stream: "batch:loyalty-membership", eventType: "POINTS_EXPIRY_BATCH", eventId: `${lot.lotId}:${now.toISOString()}`, aggregateId: memberId, occurredAt: new Date(now) }), businessReason: freeze({ reasonType: "EXPIRY" as const, reasonCode: "POINTS_EXPIRED", referenceType: "POINTS_LOT", referenceId: lot.lotId }), idempotencyKey: `${memberId}:expire:${lot.lotId}:${now.toISOString()}`, occurredAt: new Date(now) }));
    const expired = expiredLots.map((lot) => freeze({ batchId: lot.lotId, points: lot.remainingPoints, earnedAt: lot.validFrom, expiresAt: lot.expiresAt }));
    const expiredIds = new Set(expiredLots.map((lot) => lot.lotId));
    const lots = this.lots.map((lot) => expiredIds.has(lot.lotId) ? freeze({ ...lot, remainingPoints: 0 }) : cloneLot(lot));
    return { ledger: new PointsLedger([...this.entries, ...entries], lots), expired };
  }

  getExpiringWithin(days: number, now = new Date()): readonly ExpiringBatch[] {
    assertWholeNonNegative(days, "days");
    const until = new Date(now); until.setUTCDate(until.getUTCDate() + days);
    return this.lots.filter((lot) => lot.remainingPoints > 0 && lot.expiresAt > now && lot.expiresAt <= until).sort((left, right) => left.expiresAt.getTime() - right.expiresAt.getTime()).map((lot) => freeze({ batchId: lot.lotId, pointsAmount: lot.remainingPoints, expiryDate: new Date(lot.expiresAt) }));
  }

  toSnapshot(): Readonly<{ entries: readonly PointsLedgerEntrySnapshot[]; lots: readonly PointsLotSnapshot[] }> { return freeze({ entries: this.entries.map(cloneLedgerEntry), lots: this.lots.map(cloneLot) }); }
}

export class Member {
  private constructor(private readonly snapshot: MemberSnapshot) {}
  static enroll(input: { accountId: AccountId; memberId?: MemberId; now?: Date }): Member {
    const now = input.now ?? new Date();
    return new Member(freeze({ memberId: input.memberId ?? newMemberId(), accountId: required(input.accountId, "accountId"), status: "ACTIVE", tier: "SILVER", redeemablePoints: 0, lifetimePoints: 0, tierPoints: 0, createdAt: now, updatedAt: now, ledger: [], lots: [], membershipYears: [newMembershipYear(now.getUTCFullYear(), "SILVER")] }));
  }
  static fromSnapshot(snapshot: MemberSnapshot): Member {
    const tier = normalizeTier(snapshot.tier as string);
    return new Member(freeze({ ...snapshot, tier, createdAt: new Date(snapshot.createdAt), updatedAt: new Date(snapshot.updatedAt), ledger: snapshot.ledger.map(cloneLedgerEntry), lots: snapshot.lots.map(cloneLot), membershipYears: normalizeMembershipYears(snapshot.membershipYears, tier, snapshot.createdAt) }));
  }
  get id(): MemberId { return this.snapshot.memberId; }
  get accountId(): AccountId { return this.snapshot.accountId; }
  get tier(): TierName { return this.snapshot.tier; }
  get redeemablePoints(): number { return this.snapshot.redeemablePoints; }

  accrueFromConfirmedOrder(command: AccruePointsCommand, now = new Date()): { member: Member; events: LoyaltyDomainEvent[] } {
    this.assertActive();
    // travelDate is NOT defaulted to confirmedAt. confirmedAt is when the
    // member PAID, which is a different calendar day from when they travel, so
    // falling back to it awarded weekend/holiday bonuses based on the purchase
    // day: the live ledger shows the same e2e fare earning 161 points for a
    // Sunday purchase and 107 for a Monday purchase of the same future trip.
    // When the upstream event carries no travel date we award the base rate.
    const points = PointsCalculator.calculate({ fare: command.ticketPrice, seatClass: command.seatClass ?? "SECOND_CLASS", travelDate: command.travelDate, memberTier: this.snapshot.tier, routeCode: command.routeCode, routeName: command.routeName, isHoliday: command.isHoliday, memberBirthDate: command.memberBirthDate });
    if (points === 0) throw new DomainError("NO_POINTS_TO_ACCRUE", "Ticket price is too small to accrue points");
    const ledger = PointsLedger.fromSnapshots(this.snapshot.ledger, this.snapshot.lots);
    const sourceFactRef = freeze({ stream: command.sourceStream ?? "events:payment", eventType: command.sourceEventType ?? "PAYMENT_CAPTURED", eventId: required(command.sourceEventId, "sourceEventId"), aggregateId: required(command.orderId, "orderId"), occurredAt: command.confirmedAt });
    const businessReason = freeze({ reasonType: "ORDER_ACCRUAL" as const, reasonCode: command.sourceEventType ?? "PAYMENT_CAPTURED", referenceType: "JOURNEY_ORDER", referenceId: command.orderId });
    const { ledger: accruedLedger, lot } = ledger.accrue({ memberId: this.id, points, tierEligiblePoints: points, sourceFactRef, businessReason, idempotencyKey: `${this.id}:${command.sourceEventId}`, occurredAt: command.confirmedAt, expiresAt: addUtcMonths(command.confirmedAt, this.snapshot.tier === "DIAMOND" ? 36 : 24) });
    const year = command.confirmedAt.getUTCFullYear();
    const membershipYears = upsertMembershipYear(this.membershipYears(), year, (membershipYear) => freeze({ ...membershipYear, qualifyingPoints: membershipYear.qualifyingPoints + points, tripCount: membershipYear.tripCount + (command.tripCount ?? 0) }));
    const currentYear = membershipYears.find((membershipYear) => membershipYear.year === year) ?? newMembershipYear(year, this.snapshot.tier);
    const targetTier = Tier.of(TierEvaluator.evaluate(currentYear.qualifyingPoints, currentYear.tripCount));
    const currentTier = Tier.of(this.snapshot.tier);
    const nextTier = targetTier.isHigherThan(currentTier) ? targetTier.name : currentTier.name;
    const nextMembershipYears = membershipYears.map((membershipYear) => membershipYear.year === year ? freeze({ ...membershipYear, currentTier: nextTier }) : membershipYear);
    const ledgerSnapshot = accruedLedger.toSnapshot();
    const nextSnapshot = freeze({ ...this.snapshot, tier: nextTier, redeemablePoints: accruedLedger.balance, lifetimePoints: accruedLedger.lifetimePoints, tierPoints: currentYear.qualifyingPoints, updatedAt: now, ledger: ledgerSnapshot.entries, lots: ledgerSnapshot.lots, membershipYears: nextMembershipYears });
    const events: LoyaltyDomainEvent[] = [freeze({ type: "PointsEarned", occurredAt: now, memberId: this.id, accountId: this.accountId, points, sourceRef: command.orderId, sourceFactRef, businessReason, lotId: lot.lotId, expiresAt: lot.expiresAt, balanceAfter: accruedLedger.balance, qualifyingPoints: points, correlationId: command.correlationId })];
    if (nextTier !== this.snapshot.tier) events.push(freeze({ type: "MemberTierUpgraded", occurredAt: now, memberId: this.id, accountId: this.accountId, oldTier: this.snapshot.tier, newTier: nextTier, qualifyingPoints: currentYear.qualifyingPoints, trips: currentYear.tripCount, correlationId: command.correlationId }));
    return { member: new Member(nextSnapshot), events };
  }


  recordTrip(input: { occurredAt: Date; trips?: number; correlationId?: string }, now = new Date()): { member: Member; events: LoyaltyDomainEvent[] } {
    this.assertActive();
    const year = input.occurredAt.getUTCFullYear();
    const membershipYears = upsertMembershipYear(this.membershipYears(), year, (membershipYear) => freeze({ ...membershipYear, tripCount: membershipYear.tripCount + (input.trips ?? 1) }));
    const currentYear = membershipYears.find((membershipYear) => membershipYear.year === year) ?? newMembershipYear(year, this.snapshot.tier);
    const targetTier = Tier.of(TierEvaluator.evaluate(currentYear.qualifyingPoints, currentYear.tripCount));
    const currentTier = Tier.of(this.snapshot.tier);
    const nextTier = targetTier.isHigherThan(currentTier) ? targetTier.name : currentTier.name;
    const nextMembershipYears = membershipYears.map((membershipYear) => membershipYear.year === year ? freeze({ ...membershipYear, currentTier: nextTier }) : membershipYear);
    const events: LoyaltyDomainEvent[] = [];
    if (nextTier !== this.snapshot.tier) events.push(freeze({ type: "MemberTierUpgraded", occurredAt: now, memberId: this.id, accountId: this.accountId, oldTier: this.snapshot.tier, newTier: nextTier, qualifyingPoints: currentYear.qualifyingPoints, trips: currentYear.tripCount, correlationId: input.correlationId }));
    return { member: new Member(freeze({ ...this.snapshot, tier: nextTier, updatedAt: now, membershipYears: nextMembershipYears })), events };
  }

  qualifyingPointsForOrder(orderId: string): number {
    required(orderId, "orderId");
    return this.snapshot.ledger
      .filter((entry) => entry.type === "EARNED" && entry.businessReason.referenceType === "JOURNEY_ORDER" && entry.businessReason.referenceId === orderId)
      .reduce((total, entry) => total + Math.max(0, entry.points), 0);
  }

  redeemedTicketPointsForOrder(orderId: string): number {
    required(orderId, "orderId");
    const ticketRedemptions = this.snapshot.ledger
      .filter((entry) => entry.type === "REDEEMED" && entry.businessReason.referenceType === "JOURNEY_ORDER" && entry.businessReason.referenceId === orderId)
      .reduce((total, entry) => total + Math.max(0, -entry.points), 0);
    const restoredRedemptions = this.snapshot.ledger
      .filter((entry) => entry.type === "ADJUSTED" && entry.businessReason.reasonCode === "ORDER_CANCELLED_POINTS_RESTORED" && entry.businessReason.referenceType === "JOURNEY_ORDER" && entry.businessReason.referenceId === orderId)
      .reduce((total, entry) => total + Math.max(0, entry.points), 0);
    return Math.max(0, ticketRedemptions - restoredRedemptions);
  }

  adjustQualifyingPointsForRefund(input: { occurredAt: Date; qualifyingPoints: number }, now = new Date()): Member {
    this.assertActive();
    assertWholeNonNegative(input.qualifyingPoints, "qualifyingPoints");
    const year = input.occurredAt.getUTCFullYear();
    const membershipYears = upsertMembershipYear(this.membershipYears(), year, (membershipYear) => freeze({ ...membershipYear, qualifyingPoints: Math.max(0, membershipYear.qualifyingPoints - input.qualifyingPoints) }));
    const currentYear = membershipYears.find((membershipYear) => membershipYear.year === year) ?? newMembershipYear(year, this.snapshot.tier);
    return new Member(freeze({ ...this.snapshot, tierPoints: currentYear.qualifyingPoints, updatedAt: now, membershipYears }));
  }

  restoreRedeemedTicketPoints(input: { orderId: string; sourceEventId: string; cancelledAt: Date; correlationId?: string; sourceStream?: string; sourceEventType?: string }, now = new Date()): { member: Member; events: readonly PointsRestored[] } {
    this.assertActive();
    const orderId = required(input.orderId, "orderId");
    const sourceEventId = required(input.sourceEventId, "sourceEventId");
    const ledger = PointsLedger.fromSnapshots(this.snapshot.ledger, this.snapshot.lots);
    if (ledger.hasSourceFact(sourceEventId)) return { member: this, events: [] };
    const points = this.redeemedTicketPointsForOrder(orderId);
    if (points === 0) return { member: this, events: [] };
    const sourceFactRef = freeze({ stream: input.sourceStream ?? "events:journey-order", eventType: input.sourceEventType ?? "JOURNEY_ORDER_CANCELLED", eventId: sourceEventId, aggregateId: orderId, occurredAt: input.cancelledAt });
    const businessReason = freeze({ reasonType: "REVERSAL" as const, reasonCode: "ORDER_CANCELLED_POINTS_RESTORED", referenceType: "JOURNEY_ORDER", referenceId: orderId });
    const { ledger: restoredLedger } = ledger.restore({ memberId: this.id, points, sourceFactRef, businessReason, idempotencyKey: `${this.id}:restore:${orderId}:${sourceEventId}`, occurredAt: input.cancelledAt, expiresAt: addUtcMonths(input.cancelledAt, this.snapshot.tier === "DIAMOND" ? 36 : 24) });
    const ledgerSnapshot = restoredLedger.toSnapshot();
    const nextSnapshot = freeze({ ...this.snapshot, redeemablePoints: restoredLedger.balance, lifetimePoints: restoredLedger.lifetimePoints, updatedAt: now, ledger: ledgerSnapshot.entries, lots: ledgerSnapshot.lots });
    return { member: new Member(nextSnapshot), events: [freeze({ type: "PointsRestored", occurredAt: now, memberId: this.id, accountId: this.accountId, points, orderId, sourceFactRef, businessReason, balanceAfter: restoredLedger.balance, correlationId: input.correlationId })] };
  }

  redeem(command: RedeemPointsCommand, now = new Date()): { member: Member; event: PointsRedeemed } {
    const redemptionId = command.redemptionId ?? newRedemptionId();
    return this.redeemInternal(command.points, redemptionId, freeze({ reasonType: "REDEMPTION", reasonCode: command.reasonCode ?? "CATALOG_REDEMPTION", referenceType: "POINTS_REDEMPTION", referenceId: redemptionId }), now, command.correlationId);
  }

  redeemForTicket(command: RedemptionRequest & Readonly<{ redemptionId?: RedemptionId; correlationId?: string }>, now = new Date()): { member: Member; event: PointsRedeemed; result: RedemptionResult } {
    const result = RedemptionPolicy.standard.apply(command, this.snapshot.redeemablePoints);
    const redemptionId = command.redemptionId ?? newRedemptionId();
    const { member, event } = this.redeemInternal(result.pointsDeducted, redemptionId, freeze({ reasonType: "REDEMPTION", reasonCode: "TICKET_PARTIAL_PAYMENT", referenceType: "JOURNEY_ORDER", referenceId: command.orderId }), now, command.correlationId, command.orderId, result.discountAmountMinor, result.remainingBalance);
    return { member, event, result };
  }

  expirePoints(now = new Date(), correlationId?: string): { member: Member; events: readonly PointsExpired[] } {
    const { ledger: expiredLedger, expired } = PointsLedger.fromSnapshots(this.snapshot.ledger, this.snapshot.lots).expirePoints(this.id, now);
    if (expired.length === 0) return { member: this, events: [] };
    const ledgerSnapshot = expiredLedger.toSnapshot();
    const events = expired.map((batch) => freeze({ type: "PointsExpired" as const, occurredAt: now, memberId: this.id, accountId: this.accountId, points: batch.points, batchId: batch.batchId, balanceAfter: expiredLedger.balance, correlationId }));
    return { member: new Member(freeze({ ...this.snapshot, redeemablePoints: expiredLedger.balance, lifetimePoints: expiredLedger.lifetimePoints, updatedAt: now, ledger: ledgerSnapshot.entries, lots: ledgerSnapshot.lots })), events };
  }

  evaluateTierAtYearEnd(evaluationYear: number, now = new Date(), correlationId?: string): { member: Member; events: LoyaltyDomainEvent[] } {
    assertWholeNonNegative(evaluationYear, "evaluationYear");
    if (now < new Date(Date.UTC(evaluationYear + 1, 3, 1))) throw new DomainError("TIER_GRACE_PERIOD_ACTIVE", "Downgrade evaluation is available after the three month grace period");
    const membershipYear = this.membershipYears().find((year) => year.year === evaluationYear) ?? newMembershipYear(evaluationYear, this.snapshot.tier);
    const resultingTier = TierEvaluator.evaluate(membershipYear.qualifyingPoints, membershipYear.tripCount);
    const events: LoyaltyDomainEvent[] = [freeze({ type: "TierEvaluationCompleted", occurredAt: now, memberId: this.id, accountId: this.accountId, evaluationYear, resultingTier, qualifyingPoints: membershipYear.qualifyingPoints, trips: membershipYear.tripCount, correlationId })];
    if (Tier.of(resultingTier).isLowerThan(Tier.of(this.snapshot.tier))) events.push(freeze({ type: "MemberTierDowngraded", occurredAt: now, memberId: this.id, accountId: this.accountId, oldTier: this.snapshot.tier, newTier: resultingTier, qualifyingPoints: membershipYear.qualifyingPoints, trips: membershipYear.tripCount, correlationId }));
    const years = upsertMembershipYear(this.membershipYears(), evaluationYear, (year) => freeze({ ...year, currentTier: resultingTier, evaluationDate: now }));
    return { member: new Member(freeze({ ...this.snapshot, tier: resultingTier, updatedAt: now, membershipYears: years })), events };
  }

  getExpiringWithin(days: number, now = new Date()): readonly ExpiringBatch[] { return PointsLedger.fromSnapshots(this.snapshot.ledger, this.snapshot.lots).getExpiringWithin(days, now); }
  toSnapshot(): MemberSnapshot { return freeze({ ...this.snapshot, createdAt: new Date(this.snapshot.createdAt), updatedAt: new Date(this.snapshot.updatedAt), ledger: this.snapshot.ledger.map(cloneLedgerEntry), lots: this.snapshot.lots.map(cloneLot), membershipYears: this.membershipYears() }); }
  private membershipYears(): readonly MembershipYearSnapshot[] { return normalizeMembershipYears(this.snapshot.membershipYears, this.snapshot.tier, this.snapshot.createdAt); }
  private redeemInternal(points: number, redemptionId: RedemptionId, businessReason: BusinessReason, now: Date, correlationId?: string, orderId?: string, discountAmountMinor?: number, remainingBalance?: number): { member: Member; event: PointsRedeemed } {
    this.assertActive();
    const { ledger: redeemedLedger } = PointsLedger.fromSnapshots(this.snapshot.ledger, this.snapshot.lots).redeem({ memberId: this.id, points, redemptionId, businessReason, occurredAt: now });
    const ledgerSnapshot = redeemedLedger.toSnapshot();
    const nextSnapshot = freeze({ ...this.snapshot, redeemablePoints: redeemedLedger.balance, lifetimePoints: redeemedLedger.lifetimePoints, updatedAt: now, ledger: ledgerSnapshot.entries, lots: ledgerSnapshot.lots });
    return { member: new Member(nextSnapshot), event: freeze({ type: "PointsRedeemed", occurredAt: now, memberId: this.id, accountId: this.accountId, redemptionId, points, orderId, discountAmountMinor, remainingBalance: remainingBalance ?? redeemedLedger.balance, balanceAfter: redeemedLedger.balance, businessReason, correlationId }) };
  }
  private assertActive(): void { if (this.snapshot.status !== "ACTIVE") throw new DomainError("MEMBERSHIP_NOT_ACTIVE", "Only active memberships can accrue or redeem points"); }
}

/**
 * Base-rate helper: 1 CNY = 1 point, second class, SILVER, no bonuses.
 *
 * This deliberately supplies no travel date. It previously hardcoded
 * `2026-07-10` -- a Friday, and not a public holiday -- which made the bonus
 * product exactly 1.0 and so happened to yield the intended base rate. That was
 * a fragile way to say "no bonuses": the value was inert only by coincidence of
 * which weekday that date falls on, and any edit to it would have silently
 * rescaled every result. Omitting the date states the intent directly.
 *
 * Prefer `PointsCalculator.calculate` for real accruals; this exists for callers
 * that only want the unmultiplied base rate.
 */
export function pointsForTicketPrice(price: Money): number { return PointsCalculator.calculate({ fare: price, seatClass: "SECOND_CLASS", memberTier: "SILVER" }); }
export function calculatePoints(input: PointsCalculationInput): Readonly<{ points: number; rule: PointsEarningRule }> {
  if (input.fare.currency !== "CNY") throw new DomainError("UNSUPPORTED_CURRENCY", "Loyalty accrual currently supports CNY only");
  assertWholeNonNegative(input.fare.minorUnits, "minorUnits");
  const rule = PointsCalculator.earningRule(input);
  const bonus = rule.bonusConditions.reduce((multiplier, condition) => multiplier * condition.multiplier, 1);
  return freeze({ points: Math.floor((input.fare.minorUnits / 100) * rule.seatClassMultiplier * bonus * rule.tierMultiplier), rule });
}

function seatClassMultiplier(seatClass: SeatClass): number { if (seatClass === "FIRST_CLASS" || seatClass === "一等座") return 1.5; if (seatClass === "BUSINESS_CLASS" || seatClass === "商务座") return 2; return 1; }
function tierMultiplier(tier: TierName): number { return { SILVER: 1, GOLD: 1.2, PLATINUM: 1.5, DIAMOND: 2 }[tier]; }
function bonusMultipliers(input: Omit<PointsCalculationInput, "fare">): readonly Readonly<{ name: string; multiplier: number }>[] {
  const conditions: Readonly<{ name: string; multiplier: number }>[] = [];
  const travelDate = input.travelDate;
  // No travel date => no date-derived bonus. Guessing a date here is what made
  // every accrual price as a Friday in July regardless of when travel happened.
  if (travelDate) {
    const weekday = travelDate.getUTCDay();
    if (weekday === 0 || weekday === 6) conditions.push(freeze({ name: "WEEKEND_TRAVEL", multiplier: 1.5 }));
  }
  if (input.isHoliday === true || (travelDate !== undefined && isPublicHoliday(travelDate))) conditions.push(freeze({ name: "HOLIDAY_TRAVEL", multiplier: 2 }));
  if (isBonusRoute(input.routeCode, input.routeName)) conditions.push(freeze({ name: "BONUS_ROUTE", multiplier: 1.2 }));
  if (input.memberBirthDate && travelDate !== undefined && input.memberBirthDate.getUTCMonth() === travelDate.getUTCMonth()) conditions.push(freeze({ name: "BIRTHDAY_MONTH", multiplier: 2 }));
  return conditions;
}
function isPublicHoliday(date: Date): boolean { return ["01-01", "05-01", "10-01", "10-02", "10-03"].includes(`${String(date.getUTCMonth() + 1).padStart(2, "0")}-${String(date.getUTCDate()).padStart(2, "0")}`); }
function isBonusRoute(routeCode?: string, routeName?: string): boolean { const value = `${routeCode ?? ""} ${routeName ?? ""}`.toUpperCase(); return value.includes("JINGHU") || value.includes("JINGGUANG") || value.includes("京沪") || value.includes("京广"); }
function consumeLots(lots: readonly PointsLotSnapshot[], points: number): PointsLotSnapshot[] { let remainingToConsume = points; return [...lots].sort((left, right) => left.expiresAt.getTime() - right.expiresAt.getTime()).map((lot) => { if (remainingToConsume === 0 || lot.remainingPoints === 0) return cloneLot(lot); const consumed = Math.min(lot.remainingPoints, remainingToConsume); remainingToConsume -= consumed; return freeze({ ...lot, remainingPoints: lot.remainingPoints - consumed }); }); }
function upsertMembershipYear(years: readonly MembershipYearSnapshot[], year: number, update: (year: MembershipYearSnapshot) => MembershipYearSnapshot): readonly MembershipYearSnapshot[] { const existing = years.find((membershipYear) => membershipYear.year === year); const next = update(existing ?? newMembershipYear(year, "SILVER")); return freeze([...years.filter((membershipYear) => membershipYear.year !== year), next].sort((left, right) => left.year - right.year)); }
function normalizeMembershipYears(years: readonly MembershipYearSnapshot[] | undefined, tier: TierName, createdAt: Date): readonly MembershipYearSnapshot[] { if (years && years.length > 0) return freeze(years.map((year) => freeze({ ...year, currentTier: normalizeTier(year.currentTier as string), evaluationDate: year.evaluationDate ? new Date(year.evaluationDate) : undefined }))); return freeze([newMembershipYear(createdAt.getUTCFullYear(), tier)]); }
function newMembershipYear(year: number, tier: TierName): MembershipYearSnapshot { return freeze({ year, qualifyingPoints: 0, tripCount: 0, currentTier: tier }); }
function normalizeTier(tier: string): TierName { if (tier === "BASIC") return "SILVER"; return Tier.of(tier as TierName).name; }
function assertPositiveInteger(value: number, field: string): void { if (!Number.isInteger(value) || value <= 0) throw new DomainError("INVALID_POINTS", `${field} must be a positive integer`); }
function assertWholeNonNegative(value: number, field: string): void { if (!Number.isInteger(value) || value < 0) throw new DomainError("INVALID_AMOUNT", `${field} must be a non-negative integer`); }
function required(value: string, field: string): string { if (value.trim().length === 0) throw new DomainError("MISSING_REQUIRED_FIELD", `${field} is required`); return value; }
function addUtcMonths(date: Date, months: number): Date { const copy = new Date(date); copy.setUTCMonth(copy.getUTCMonth() + months); return copy; }
function cloneLedgerEntry(entry: PointsLedgerEntrySnapshot): PointsLedgerEntrySnapshot { return freeze({ ...entry, type: normalizeLedgerType(entry.type as string), sourceFactRef: cloneSourceFact(entry.sourceFactRef), businessReason: freeze({ ...entry.businessReason }), occurredAt: new Date(entry.occurredAt), expiresAt: entry.expiresAt ? new Date(entry.expiresAt) : undefined }); }
function normalizeLedgerType(type: string): LedgerEntryType { if (type === "ACCRUAL") return "EARNED"; if (type === "REDEMPTION") return "REDEEMED"; if (type === "REVERSAL") return "ADJUSTED"; return type as LedgerEntryType; }
function cloneSourceFact(source: SourceFactRef): SourceFactRef { return freeze({ ...source, occurredAt: new Date(source.occurredAt) }); }
function cloneLot(lot: PointsLotSnapshot): PointsLotSnapshot { return freeze({ ...lot, validFrom: new Date(lot.validFrom), expiresAt: new Date(lot.expiresAt) }); }
function freeze<T extends object>(value: T): Readonly<T> { return Object.freeze(value); }
function newMemberId(): MemberId { return `mem_${randomUUID()}`; }
function newLedgerEntryId(): PointsLedgerEntryId { return `ple_${randomUUID()}`; }
function newPointsLotId(): PointsLotId { return `lot_${randomUUID()}`; }
function newRedemptionId(): RedemptionId { return `red_${randomUUID()}`; }
