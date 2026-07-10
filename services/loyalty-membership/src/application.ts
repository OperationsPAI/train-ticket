import { InMemoryEventPublisher } from "@trainticket/ts-kit";

import {
  DomainError,
  Member,
  type AccountId,
  type MemberId,
  type MemberSnapshot,
  type Money,
  type PointsAccrued,
  type PointsRedeemed,
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
}>;

export type RedemptionDto = Readonly<{
  memberId: string;
  redemptionId: string;
  points: number;
  balanceAfter: number;
}>;

export type MemberRepository = Readonly<{
  findByMemberId(memberId: MemberId): Promise<Member | undefined>;
  findByAccountId(accountId: AccountId): Promise<Member | undefined>;
  save(member: Member): Promise<void>;
}>;

export class InMemoryMemberRepository implements MemberRepository {
  private readonly byMemberId = new Map<MemberId, Member>();
  private readonly accountIndex = new Map<AccountId, MemberId>();

  async findByMemberId(memberId: MemberId): Promise<Member | undefined> {
    return this.byMemberId.get(memberId);
  }

  async findByAccountId(accountId: AccountId): Promise<Member | undefined> {
    const memberId = this.accountIndex.get(accountId);
    return memberId ? this.byMemberId.get(memberId) : undefined;
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

  async accrueFromJourneyOrderConfirmed(command: {
    orderId: string;
    accountId: string;
    ticketPrice: Money;
    sourceEventId: string;
    confirmedAt: Date;
    correlationId: string;
    causationId?: string;
  }): Promise<MemberDetailsDto> {
    const member = (await this.repository.findByAccountId(command.accountId)) ?? Member.enroll({ accountId: command.accountId });
    const { member: updated, events } = member.accrueFromConfirmedOrder(command);
    await this.repository.save(updated);
    for (const event of events) {
      await this.publish(event, command.correlationId, command.causationId);
    }
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
    if (error.code === "MISSING_REQUIRED_FIELD" || error.code === "INVALID_AMOUNT" || error.code === "INVALID_POINTS") {
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
  };
}

function redemptionDetails(event: PointsRedeemed): RedemptionDto {
  return {
    memberId: event.memberId,
    redemptionId: event.redemptionId,
    points: event.points,
    balanceAfter: event.balanceAfter,
  };
}
