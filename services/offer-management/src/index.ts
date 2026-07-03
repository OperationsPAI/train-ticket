import Fastify, { type FastifyInstance } from "fastify";

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

export type ServiceProfile = {
  serviceId: string;
  domain: string;
  language: "typescript";
  phase: string;
  requirement: string;
  workPackages: string[];
  owns: string[];
  doesNotOwn: string[];
  publishes: string[];
};

export const serviceProfile: ServiceProfile = {
  serviceId: "offer-management",
  domain: "Offer Management",
  language: "typescript",
  phase: "phase-1-offer-domain-foundation",
  requirement: "REQ-009-Offer-Management-domain-foundation",
  workPackages: ["REQ-009", "WP-07-offer-snapshots"],
  owns: [
    "Offer identity and lifecycle",
    "OfferItem snapshot composition",
    "ItineraryReference",
    "AvailabilitySnapshotReference",
    "PriceSnapshot",
    "FareRuleSnapshotReference",
    "PassengerMix",
    "RiskDisclosure",
    "OfferQuoted",
    "OfferExpired",
  ],
  doesNotOwn: ["CapacityHold", "PaymentIntent", "JourneyOrder", "Entitlement"],
  publishes: ["OfferQuoted", "OfferExpired"],
};

export function health(): "ok" {
  return "ok";
}

export function createApp(): FastifyInstance {
  const app = Fastify({ logger: false });
  app.get("/health", async () => ({ status: health(), service: serviceProfile }));
  return app;
}
