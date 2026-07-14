import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { Redis } from "ioredis";
import { MigrationRunner, OutboxAppender, OutboxRelay, PostgresIdempotencyStore, ProcessedEventsGuard, checkPostgresReadiness, createPostgresPool, streamForProducer, withTransaction, type EventEnvelope } from "@trainticket/ts-kit";
import { type Pool } from "pg";
import { AncillaryApplicationService } from "../../application.js";
import { HttpFarePricingGateway, type AncillaryPricingGateway } from "../../pricing.js";
import { PostgresAncillaryRepository } from "./repository.js";

export type AncillaryStorageRuntime = Readonly<{ ready: () => Promise<boolean>; idempotencyStore: PostgresIdempotencyStore; runCommand: <T>(operation: (application: AncillaryApplicationService) => Promise<T>) => Promise<T>; handleUpstreamEvent: (envelope: EventEnvelope, stream?: string) => Promise<"ack" | "retry" | "dlq">; stop: () => Promise<void> }>;
export async function startAncillaryStorage(redisUrl = process.env.REDIS_URL ?? "redis://localhost:6379"): Promise<AncillaryStorageRuntime> {
  const pool = createPostgresPool();
  const migrations = new MigrationRunner(pool, migrationsDirectory());
  try { await migrations.apply(); } catch (error) { console.error(sanitizedErrorForLog(error)); }
  const redis = new Redis(redisUrl, { lazyConnect: true });
  await redis.connect();
  const relay = new OutboxRelay(pool, redis, { pollIntervalMs: 250, onFailure: (error) => console.error(sanitizedErrorForLog(error)) });
  const pricingGateway = farePricingGatewayFromEnv();
  relay.start();
  return {
    ready: () => migrations.isReady ? checkPostgresReadiness(pool) : Promise.resolve(false),
    idempotencyStore: new PostgresIdempotencyStore(pool),
    runCommand: (operation) => withAncillaryTransaction(pool, operation, pricingGateway),
    handleUpstreamEvent: (envelope, stream) => withAncillaryTransaction(pool, async (application, client) => {
      const guard = new ProcessedEventsGuard(client);
      if (!await guard.tryStart(envelope.eventId, stream)) return "ack";
      return application.handleJourneyOrderCancelled(envelope);
    }, pricingGateway),
    stop: async () => { await relay.stop(); await Promise.allSettled([redis.quit(), pool.end()]); },
  };
}
async function withAncillaryTransaction<T>(pool: Pool, operation: (application: AncillaryApplicationService, client: any) => Promise<T>, pricingGateway?: AncillaryPricingGateway): Promise<T> {
  return withTransaction(pool, async (client) => operation(new AncillaryApplicationService(new PostgresAncillaryRepository(client), new TransactionalOutboxPublisher(new OutboxAppender(client)), pricingGateway), client));
}
class TransactionalOutboxPublisher { constructor(private readonly appender: OutboxAppender) {} async publish(envelope: EventEnvelope): Promise<void> { await this.appender.append(envelope, streamForProducer(envelope.producer)); } }
export function migrationsDirectory(): string { return join(dirname(fileURLToPath(import.meta.url)), "../../../migrations"); }
function farePricingGatewayFromEnv(): AncillaryPricingGateway | undefined { const baseUrl = process.env.FARE_PRICING_URL?.trim(); return baseUrl ? new HttpFarePricingGateway(baseUrl) : undefined; }
function sanitizedErrorForLog(error: unknown): Readonly<{ name: string; message: string }> { return error instanceof Error ? { name: error.name || "Error", message: error.message || "Storage failed" } : { name: typeof error, message: "Storage failed" }; }
