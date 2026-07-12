import { InMemoryEventPublisher } from "@trainticket/ts-kit";

import {
  DomainError,
  Member,
  type AccountId,
  type MemberId,
  type MemberSnapshot,
  type Money,
  type PointsRedeemed,
  type SeatClass,
} from "./domain.js";
import { toEventEnvelope, type EventPublisher } from "./ports.js";

export { InMemoryEventPublisher };

export type MemberDetailsDto = Readonly<{
  memberId: string;
  accountId: string;
  status: string;
  tier: string;
  redeemablePoints: number;
  lifetimePoints: number;
  tierPoints: number;
  createdAt: string;
  updatedAt: string;
  ledger: readonly Readonly<{
    entryId: string;
    type: string;
    points: number;
    occurredAt: string;
    reasonCode: string;
    referenceId: string;
  }>[];
  expiringBatches: readonly Readonly<{ batchId: string; pointsAmount: number; expiryDate: string }>[];
  membershipYears: readonly Readonly<{ year: number; qualifyingPoints: number; tripCount: number; currentTier: string; evaluationDate?: string }>[];
}>;

export type RedemptionDto = Readonly<{
  memberId: string;
  redemptionId: string;
  points: number;
  balanceAfter: number;
  discountAmountMinor?: number;
  remainingBalance?: number;
}>;

export type MemberRepository = Readonly<{
  findByMemberId(memberId: MemberId): Promise<Member | undefined>;
  findByAccountId(accountId: AccountId): Promise<Member | undefined>;
  findAccountIdByOrderId(orderId: string): Promise<AccountId | undefined>;
  saveOrderAccountRef(orderId: string, accountId: AccountId): Promise<void>;
  save(member: Member): Promise<void>;
}>;

export class InMemoryMemberRepository implements MemberRepository {
  private readonly byMemberId = new Map<MemberId, Member>();
  private readonly accountIndex = new Map<AccountId, MemberId>();
  private readonly orderAccountIndex = new Map<string, AccountId>();

  async findByMemberId(memberId: MemberId): Promise<Member | undefined> {
    return this.byMemberId.get(memberId);
  }

  async findByAccountId(accountId: AccountId): Promise<Member | undefined> {
    const memberId = this.accountIndex.get(accountId);
    return memberId ? this.byMemberId.get(memberId) : undefined;
  }

  async findAccountIdByOrderId(orderId: string): Promise<AccountId | undefined> {
    return this.orderAccountIndex.get(orderId);
  }

  async saveOrderAccountRef(orderId: string, accountId: AccountId): Promise<void> {
    this.orderAccountIndex.set(orderId, accountId);
  }

  async save(member: Member): Promise<void> {
    this.byMemberId.set(member.id, member);
    this.accountIndex.set(member.accountId, member.id);
  }
}

export class LoyaltyMembershipApplicationService {
  constructor(
    private readonly repository: MemberRepository,
    private readonly publisher: EventPublisher,
  ) {}

  async getMember(memberId: string): Promise<MemberDetailsDto> {
    return memberDetails((await this.requireMember(memberId)).toSnapshot());
  }

  async getMemberByAccountId(accountId: string): Promise<MemberDetailsDto> {
    const member = await this.repository.findByAccountId(accountId);
    if (!member) {
      throw new ApplicationError("NOT_FOUND", `No member found for account ${accountId}`, 404);
    }
    return memberDetails(member.toSnapshot());
  }

  async enrollMember(accountId: string): Promise<{ member: MemberDetailsDto; created: boolean }> {
    const existing = await this.repository.findByAccountId(accountId);
    if (existing) {
      return { member: memberDetails(existing.toSnapshot()), created: false };
    }
    const member = Member.enroll({ accountId });
    await this.repository.save(member);
    return { member: memberDetails(member.toSnapshot()), created: true };
  }

  async redeemTicketPoints(command: {
    memberId: string;
    orderId: string;
    pointsToRedeem: number;
    fareAmountMinor: number;
    redemptionId?: string;
    correlationId: string;
    causationId?: string;
  }): Promise<RedemptionDto> {
    const member = await this.requireMember(command.memberId);
    const { member: updated, event } = member.redeemForTicket(command);
    await this.repository.save(updated);
    await this.publish(event, command.correlationId, command.causationId);
    return redemptionDetails(event);
  }

  async redeemPoints(command: {
    memberId: string;
    points: number;
    redemptionId?: string;
    reasonCode?: string;
    correlationId: string;
    causationId?: string;
  }): Promise<RedemptionDto> {
    const member = await this.requireMember(command.memberId);
    const { member: updated, event } = member.redeem(command);
    await this.repository.save(updated);
    await this.publish(event, command.correlationId, command.causationId);
    return redemptionDetails(event);
  }

  async accrueFromPaymentCaptured(command: {
    orderId: string;
    accountId?: string;
    ticketPrice: Money;
    sourceEventId: string;
    confirmedAt: Date;
    correlationId: string;
    causationId?: string;
    seatClass?: SeatClass;
    travelDate?: Date;
    routeCode?: string;
    routeName?: string;
    isHoliday?: boolean;
    memberBirthDate?: Date;
  }): Promise<MemberDetailsDto> {
    const accountId = command.accountId ?? await this.repository.findAccountIdByOrderId(command.orderId);
    if (!accountId) {
      throw new ApplicationError("OUT_OF_ORDER_EVENT", `No account reference found for order ${command.orderId}`, 409);
    }
    return this.accrueFromJourneyOrderConfirmed({ ...command, accountId, sourceStream: "events:payment", sourceEventType: "PAYMENT_CAPTURED" });
  }

  async accrueFromJourneyOrderConfirmed(command: {
    orderId: string;
    accountId: string;
    ticketPrice: Money;
    sourceEventId: string;
    confirmedAt: Date;
    correlationId: string;
    causationId?: string;
    seatClass?: SeatClass;
    travelDate?: Date;
    routeCode?: string;
    routeName?: string;
    isHoliday?: boolean;
    memberBirthDate?: Date;
    tripCount?: number;
    sourceStream?: string;
    sourceEventType?: string;
  }): Promise<MemberDetailsDto> {
    await this.repository.saveOrderAccountRef(command.orderId, command.accountId);
    const member = (await this.repository.findByAccountId(command.accountId)) ?? Member.enroll({ accountId: command.accountId });
    const { member: updated, events } = member.accrueFromConfirmedOrder(command);
    await this.repository.save(updated);
    for (const event of events) {
      await this.publish(event, command.correlationId, command.causationId);
    }
    return memberDetails(updated.toSnapshot());
  }

  async expirePoints(command: { memberId: string; now?: Date; correlationId: string; causationId?: string }): Promise<MemberDetailsDto> {
    const member = await this.requireMember(command.memberId);
    const { member: updated, events } = member.expirePoints(command.now ?? new Date(), command.correlationId);
    await this.repository.save(updated);
    for (const event of events) {
      await this.publish(event, command.correlationId, command.causationId);
    }
    return memberDetails(updated.toSnapshot());
  }

  async evaluateTier(command: { memberId: string; evaluationYear: number; now?: Date; correlationId: string; causationId?: string }): Promise<MemberDetailsDto> {
    const member = await this.requireMember(command.memberId);
    const { member: updated, events } = member.evaluateTierAtYearEnd(command.evaluationYear, command.now ?? new Date(), command.correlationId);
    await this.repository.save(updated);
    for (const event of events) {
      await this.publish(event, command.correlationId, command.causationId);
    }
    return memberDetails(updated.toSnapshot());
  }

  async recordTripFromJourneyOrderCreated(command: { orderId: string; accountId: string; occurredAt: Date; trips?: number; correlationId: string; causationId?: string }): Promise<MemberDetailsDto> {
    await this.repository.saveOrderAccountRef(command.orderId, command.accountId);
    const member = (await this.repository.findByAccountId(command.accountId)) ?? Member.enroll({ accountId: command.accountId });
    const { member: updated, events } = member.recordTrip({ occurredAt: command.occurredAt, trips: command.trips, correlationId: command.correlationId });
    await this.repository.save(updated);
    for (const event of events) {
      await this.publish(event, command.correlationId, command.causationId);
    }
    return memberDetails(updated.toSnapshot());
  }

  async deductQualifyingPointsForRefund(command: { accountId: string; occurredAt: Date; qualifyingPoints: number }): Promise<MemberDetailsDto> {
    const member = await this.repository.findByAccountId(command.accountId);
    if (!member) {
      throw new ApplicationError("NOT_FOUND", `No member found for account ${command.accountId}`, 404);
    }
    const updated = member.adjustQualifyingPointsForRefund({ occurredAt: command.occurredAt, qualifyingPoints: command.qualifyingPoints });
    await this.repository.save(updated);
    return memberDetails(updated.toSnapshot());
  }

  private async requireMember(memberId: string): Promise<Member> {
    const member = await this.repository.findByMemberId(memberId);
    if (!member) {
      throw new ApplicationError("NOT_FOUND", `Member ${memberId} was not found`, 404);
    }
    return member;
  }

  private async publish(event: Parameters<typeof toEventEnvelope>[0], correlationId: string, causationId?: string): Promise<void> {
    await this.publisher.publish(toEventEnvelope(event, correlationId, causationId));
  }
}

export class ApplicationError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly statusCode: number,
    public readonly details: Record<string, unknown> = {},
  ) {
    super(message);
    this.name = "ApplicationError";
  }
}

export function mapError(error: unknown): ApplicationError {
  if (error instanceof ApplicationError) {
    return error;
  }
  if (error instanceof DomainError) {
    if (["MISSING_REQUIRED_FIELD", "INVALID_AMOUNT", "INVALID_POINTS", "REDEMPTION_BELOW_MINIMUM", "REDEMPTION_CAP_BELOW_MINIMUM"].includes(error.code)) {
      return new ApplicationError("VALIDATION_FAILED", error.message, 400, { domainCode: error.code });
    }
    if (error.code === "INSUFFICIENT_POINTS") {
      return new ApplicationError("PRECONDITION_FAILED", error.message, 412, { domainCode: error.code });
    }
    return new ApplicationError("DOMAIN_RULE_VIOLATION", error.message, 422, { domainCode: error.code });
  }
  return new ApplicationError("UNAVAILABLE", "The loyalty membership service is temporarily unavailable", 503);
}

function memberDetails(snapshot: MemberSnapshot): MemberDetailsDto {
  return {
    memberId: snapshot.memberId,
    accountId: snapshot.accountId,
    status: snapshot.status,
    tier: snapshot.tier,
    redeemablePoints: snapshot.redeemablePoints,
    lifetimePoints: snapshot.lifetimePoints,
    tierPoints: snapshot.tierPoints,
    createdAt: snapshot.createdAt.toISOString(),
    updatedAt: snapshot.updatedAt.toISOString(),
    ledger: snapshot.ledger.map((entry) => ({
      entryId: entry.entryId,
      type: entry.type,
      points: entry.points,
      occurredAt: entry.occurredAt.toISOString(),
      reasonCode: entry.businessReason.reasonCode,
      referenceId: entry.businessReason.referenceId,
    })),
    expiringBatches: Member.fromSnapshot(snapshot).getExpiringWithin(30).map((batch) => ({
      batchId: batch.batchId,
      pointsAmount: batch.pointsAmount,
      expiryDate: batch.expiryDate.toISOString(),
    })),
    membershipYears: (snapshot.membershipYears ?? []).map((year) => ({
      year: year.year,
      qualifyingPoints: year.qualifyingPoints,
      tripCount: year.tripCount,
      currentTier: year.currentTier,
      evaluationDate: year.evaluationDate?.toISOString(),
    })),
  };
}

function redemptionDetails(event: PointsRedeemed): RedemptionDto {
  return {
    memberId: event.memberId,
    redemptionId: event.redemptionId,
    points: event.points,
    balanceAfter: event.balanceAfter,
    discountAmountMinor: event.discountAmountMinor,
    remainingBalance: event.remainingBalance,
  };
}
