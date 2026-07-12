export { createApp, health, metadata, opentelemetryInstrumentationFromEnv, type AppOptions } from "./app.js";
export { bootstrap, handleJourneyOrderConfirmed, handleJourneyOrderCreated, handlePaymentCaptured, handlePostSalesApplied, runtimeHost, runtimePort, type BootstrapOptions } from "./bootstrap.js";
export { InMemoryEventPublisher, InMemoryMemberRepository, LoyaltyMembershipApplicationService } from "./application.js";
export { InMemoryIdempotencyStore } from "@trainticket/ts-kit";
export type { EventEnvelope, EventPublisher, EventSubscriber, HandlerResult } from "./ports.js";
export { DomainError, Member, PointsCalculator, PointsLedger, RedemptionPolicy, Tier, TierEvaluator, pointsForTicketPrice, type LoyaltyDomainEvent, type MemberSnapshot, type TierName } from "./domain.js";
export { serviceProfile, type ServiceProfile } from "./profile.js";
