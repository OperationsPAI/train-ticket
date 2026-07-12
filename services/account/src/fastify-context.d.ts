import type { RequestContext } from "./app.js";

declare module "fastify" {
  interface FastifyRequest {
    ctx?: RequestContext;
  }
}
