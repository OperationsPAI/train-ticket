import Fastify, { type FastifyInstance } from "fastify";

export type ServiceProfile = {
  serviceId: string;
  domain: string;
  language: "typescript";
  phase: string;
  workPackages: string[];
  owns: string[];
};

export const serviceProfile: ServiceProfile = {
  serviceId: "offer-management",
  domain: "Offer Management",
  language: "typescript",
  phase: "phase-1-core",
  workPackages: ["WP-07"],
  owns: ["Offer", "OfferItem", "PriceSnapshot", "RiskDisclosure", "ChangeOffer"],
};

export function health(): "ok" {
  return "ok";
}

export function createApp(): FastifyInstance {
  const app = Fastify({ logger: false });
  app.get("/health", async () => ({ status: health(), service: serviceProfile }));
  return app;
}
