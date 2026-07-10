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
    case "PointsAccrued":
      return {
        memberId: event.memberId,
        accountId: event.accountId,
        points: event.points,
        tierPoints: event.tierPoints,
        balanceAfter: event.balanceAfter,
        sourceFactRef: sourceFactPayload(event.sourceFactRef),
        businessReason: event.businessReason,
        lotId: event.lotId,
        expiresAt: event.expiresAt.toISOString(),
      };
    case "PointsRedeemed":
      return {
        memberId: event.memberId,
        accountId: event.accountId,
        redemptionId: event.redemptionId,
        points: event.points,
        balanceAfter: event.balanceAfter,
        businessReason: event.businessReason,
      };
    case "MembershipTierChanged":
      return {
        memberId: event.memberId,
        accountId: event.accountId,
        fromTier: event.fromTier,
        toTier: event.toTier,
        tierPoints: event.tierPoints,
        reason: event.reason,
      };
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
