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
  serviceId: "customer-service",
  domain: "Customer Service",
  language: "typescript",
  phase: "phase-1-support",
  workPackages: ["WP-20"],
  owns: ["SupportCase", "EvidenceRef", "ManualActionRequest", "CaseTimeline"],
};

export function health(): "ok" {
  return "ok";
}

export function createApp(): FastifyInstance {
  const app = Fastify({ logger: false });
  app.get("/health", async () => ({ status: health(), service: serviceProfile }));
  return app;
}
