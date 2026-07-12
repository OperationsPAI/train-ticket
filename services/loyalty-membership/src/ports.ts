import { createEventEnvelope, newCommandId, type EventEnvelope, type EventPublisher, type EventSubscriber, type HandlerResult } from "@trainticket/ts-kit";

import type { LoyaltyDomainEvent, SourceFactRef } from "./domain.js";

export type { EventEnvelope, EventPublisher, EventSubscriber, HandlerResult };

export function toEventEnvelope(event: LoyaltyDomainEvent, correlationId: string, causationId = newCommandId()): EventEnvelope {
  return createEventEnvelope({
    eventType: event.type,
    schemaVersion: 1,
    producer: "loyalty-membership",
    causationId,
    correlationId,
    occurredAt: event.occurredAt,
    payload: eventPayload(event),
  });
}

function eventPayload(event: LoyaltyDomainEvent): Record<string, unknown> {
  switch (event.type) {
    case "PointsEarned":
      return {
        memberId: event.memberId,
        accountId: event.accountId,
        points: event.points,
        sourceRef: event.sourceRef,
        sourceFactRef: sourceFactPayload(event.sourceFactRef),
        businessReason: event.businessReason,
        lotId: event.lotId,
        expiresAt: event.expiresAt.toISOString(),
        balanceAfter: event.balanceAfter,
        qualifyingPoints: event.qualifyingPoints,
      };
    case "PointsRedeemed":
      return {
        memberId: event.memberId,
        accountId: event.accountId,
        redemptionId: event.redemptionId,
        points: event.points,
        orderId: event.orderId,
        discountAmountMinor: event.discountAmountMinor,
        remainingBalance: event.remainingBalance,
        balanceAfter: event.balanceAfter,
        businessReason: event.businessReason,
      };
    case "PointsExpired":
      return { memberId: event.memberId, accountId: event.accountId, points: event.points, batchId: event.batchId, balanceAfter: event.balanceAfter };
    case "MemberTierUpgraded":
      return { memberId: event.memberId, accountId: event.accountId, oldTier: event.oldTier, newTier: event.newTier, qualifyingPoints: event.qualifyingPoints, trips: event.trips };
    case "MemberTierDowngraded":
      return { memberId: event.memberId, accountId: event.accountId, oldTier: event.oldTier, newTier: event.newTier, qualifyingPoints: event.qualifyingPoints, trips: event.trips };
    case "TierEvaluationCompleted":
      return { memberId: event.memberId, accountId: event.accountId, evaluationYear: event.evaluationYear, resultingTier: event.resultingTier, qualifyingPoints: event.qualifyingPoints, trips: event.trips };
  }
}

function sourceFactPayload(source: SourceFactRef): Record<string, unknown> {
  return {
    stream: source.stream,
    eventType: source.eventType,
    eventId: source.eventId,
    aggregateId: source.aggregateId,
    occurredAt: source.occurredAt.toISOString(),
  };
}
