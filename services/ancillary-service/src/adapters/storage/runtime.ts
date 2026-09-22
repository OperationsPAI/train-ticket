import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { OutboxAppender, OutboxRelay, PostgresIdempotencyStore, ProcessedEventsGuard, connectRedisWithRetry, createPostgresPool, createRedisClient, migrationsAwareReadiness, redisClientLiveness, startMigrations, streamForProducer, withTransaction, type EventEnvelope, type SchemaDetail } from "@trainticket/ts-kit";
import { type Pool } from "pg";
import { HandlerError, OptimisticConcurrencyConflict } from "@trainticket/ts-kit";
import { AncillaryApplicationService } from "../../application.js";
import { HttpFarePricingGateway, type AncillaryPricingGateway } from "../../pricing.js";
import { PostgresAncillaryRepository } from "./repository.js";
import { HttpAncillaryReferences } from "../../references.js";
import { HttpAncillaryPayments } from "../../payments.js";
import { uuidV7 } from "@trainticket/ts-kit";

export type AncillaryStorageRuntime = Readonly<{ ready: () => Promise<boolean>; /** Migration retry state, surfaced on `/readyz` so a stuck boot is diagnosable. */ schema: () => SchemaDetail; idempotencyStore: PostgresIdempotencyStore; runCommand: <T>(operation: (application: AncillaryApplicationService) => Promise<T>) => Promise<T>; handleUpstreamEvent: (envelope: EventEnvelope, stream?: string) => Promise<"ack" | "retry" | "dlq">; stop: () => Promise<void> }>;
export async function startAncillaryStorage(redisUrl = process.env.REDIS_URL ?? "redis://localhost:6379"): Promise<AncillaryStorageRuntime> {
  const pool = createPostgresPool();
  // A migration failure used to be caught, logged once and abandoned, which wedged `isReady` false and `/readyz` at 503 for the life of the process (2026-09-07 Postgres OOMKill). `startMigrations` awaits the first attempt -- so a successful boot is unchanged and the schema is in place before we serve -- and, if it failed, keeps retrying in the background on the shared capped-backoff schedule until the database comes back. See `superviseMigrations` for the readiness/liveness reasoning.
  const migrations = await startMigrations(pool, migrationsDirectory(), { service: "ancillary-service" });
  // createRedisClient attaches an "error" listener and an infinite capped backoff retry strategy, and registers a liveness component; `new Redis(url)` got none of that (2026-09-06 outage).
  const redis = createRedisClient(redisUrl, {}, "ancillary-outbox");
  await connectRedisWithRetry(redis, "ancillary-outbox");
  const relay = new OutboxRelay(pool, redis, { pollIntervalMs: 250, name: "ancillary-outbox-relay", onFailure: (error) => console.error(sanitizedErrorForLog(error)) });
  const pricingGateway = farePricingGatewayFromEnv();
  let reconciling=false;
  const reconciliation=setInterval(()=>{
    if(reconciling)return;
    reconciling=true;
    void (async()=>{
      const rows=await pool.query("SELECT id FROM ancillary_order_items WHERE data->>'status'='REFUND_PENDING' AND data->>'paymentIntentId' IS NOT NULL ORDER BY updated_at LIMIT 25");
      for(const row of rows.rows)await withAncillaryTransaction(pool,service=>service.reconcileRefund(row.id,`corr-${uuidV7()}`),pricingGateway);
    })().catch(error=>console.error(sanitizedErrorForLog(error))).finally(()=>{reconciling=false;});
  },1000);
  reconciliation.unref();
  relay.start();
  return {
    ready: migrationsAwareReadiness(migrations, pool),
    schema: () => migrations.detail(),
    idempotencyStore: new PostgresIdempotencyStore(pool),
    runCommand: (operation) => withAncillaryTransaction(pool, operation, pricingGateway),
    handleUpstreamEvent: (envelope, stream) => withAncillaryTransaction(pool, async (application, client) => {
      const guard = new ProcessedEventsGuard(client);
      if (!await guard.tryStart(envelope.eventId, stream)) return "ack";
      return application.handleJourneyOrderCancelled(envelope);
    }, pricingGateway).catch((error: unknown) => {
      if (error instanceof OptimisticConcurrencyConflict) throw new HandlerError("transient", error.message, { cause: error });
      throw error;
    }),
    // Stop the migration retry first: otherwise a shutdown during the retry window leaves a backoff timer running and can issue queries against a pool that is being torn down.
    stop: async () => { clearInterval(reconciliation); await migrations.stop(); await relay.stop(); redisClientLiveness(redis)?.dispose(); await Promise.allSettled([redis.quit(), pool.end()]); },
  };
}
async function withAncillaryTransaction<T>(pool: Pool, operation: (application: AncillaryApplicationService, client: any) => Promise<T>, pricingGateway?: AncillaryPricingGateway): Promise<T> {
  return withTransaction(pool, async (client) => operation(new AncillaryApplicationService(new PostgresAncillaryRepository(client), new TransactionalOutboxPublisher(new OutboxAppender(client)), pricingGateway, new HttpAncillaryReferences(),new HttpAncillaryPayments()), client));
}
class TransactionalOutboxPublisher { constructor(private readonly appender: OutboxAppender) {} async publish(envelope: EventEnvelope): Promise<void> { await this.appender.append(envelope, streamForProducer(envelope.producer)); } }
export function migrationsDirectory(): string { return join(dirname(fileURLToPath(import.meta.url)), "../../../migrations"); }
function farePricingGatewayFromEnv(): AncillaryPricingGateway | undefined { const baseUrl = process.env.FARE_PRICING_URL?.trim(); return baseUrl ? new HttpFarePricingGateway(baseUrl) : undefined; }
function sanitizedErrorForLog(error: unknown): Readonly<{ name: string; message: string }> { return error instanceof Error ? { name: error.name || "Error", message: error.message || "Storage failed" } : { name: typeof error, message: "Storage failed" }; }
