import { initOpenTelemetry } from "@trainticket/ts-kit";

initOpenTelemetry({ serviceName: process.env.OTEL_SERVICE_NAME ?? "notification" });

const { bootstrap } = await import("./bootstrap.js");

try {
  await bootstrap();
} catch (error) {
  console.error(error instanceof Error ? { name: error.name, message: error.message, stack: error.stack } : { message: String(error) });
  process.exit(1);
}
