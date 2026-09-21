import { initOpenTelemetry } from "@trainticket/ts-kit";

// Before the dynamic import below, as in every other TypeScript service: the
// HTTP and Fastify instrumentations patch modules as they are loaded, so a
// module already imported is never instrumented. waitlist had no init at all,
// which is why it appeared in neither the traces nor, once metrics were turned
// on, the metrics.
initOpenTelemetry({ serviceName: process.env.OTEL_SERVICE_NAME ?? "waitlist" });

const { bootstrap } = await import("./bootstrap.js");

await bootstrap();
