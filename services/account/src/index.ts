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
  serviceId: "account",
  domain: "Account",
  language: "typescript",
  phase: "phase-1-limited",
  workPackages: ["WP-18"],
  owns: ["UserAccount", "Session", "Preference", "Freeze", "AccountClosureSaga shell"],
};

export function health(): "ok" {
  return "ok";
}

export function createApp(): FastifyInstance {
  const app = Fastify({ logger: false });
  app.get("/health", async () => ({ status: health(), service: serviceProfile }));
  return app;
}
