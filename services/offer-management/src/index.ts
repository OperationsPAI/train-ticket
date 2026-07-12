export {
  createApp,
  health,
  metadata,
  opentelemetryInstrumentationFromEnv,
  resetIdempotencyStore,
  resetOfferStore,
  type ErrorBody,
  type HealthStatus,
  type InstrumentationHooks,
  type ProbeStatus,
  type RequestContext,
  type RequestTraceContext,
  type ServiceMetadata,
  type TraceResult,
  type TraceSpan,
} from "./app.js";
export { bootstrap, runtimeHost, runtimePort, runtimeRedisUrl, type BootstrapOptions } from "./bootstrap.js";
export {
  DomainError,
  Offer,
  nonMutationBoundaryProof,
  type AvailabilitySnapshotReference,
  type BoundaryProof,
  type FareRuleSnapshotReference,
  type ItineraryReference,
  type Money,
  type OfferAcceptanceToken,
  type OfferDomainEvent,
  type OfferExpired,
  type OfferItem,
  type OfferQuoted,
  type OfferSnapshot,
  type PassengerMix,
  type PriceSnapshot,
  type QuoteOfferCommand,
  type RiskDisclosure,
  type ValidityWindow,
} from "./domain.js";
export { serviceProfile, type ServiceProfile } from "./profile.js";

// Messaging ports
export {
  PublishFailed,
  SubscribeFailed,
  type EventEnvelope,
  type EventHandler,
  type EventPublisher,
  type EventSubscriber,
  type HandlerResult,
} from "./ports/messaging.js";

// Adapters (in-memory fakes for testing)
export {
  InMemoryEventPublisher,
  InMemoryEventSubscriber,
} from "./adapters/messaging/in-memory.js";
