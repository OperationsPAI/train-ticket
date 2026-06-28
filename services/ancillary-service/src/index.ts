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
  serviceId: "ancillary-service",
  domain: "Ancillary Service",
  language: "typescript",
  phase: "future-scope",
  workPackages: [],
  owns: ["AncillaryOffer", "AncillaryOrderItem", "ActivationPolicy"],
};

export function health(): "ok" {
  return "ok";
}

export function createApp(): FastifyInstance {
  const app = Fastify({ logger: false });
  app.get("/health", async () => ({ status: health(), service: serviceProfile }));
  return app;
}
