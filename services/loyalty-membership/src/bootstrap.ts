import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import type { Pool } from "pg";

import { createApp, opentelemetryInstrumentationFromEnv, type InstrumentationHooks } from "./app.js";
import { InMemoryMemberRepository, LoyaltyMembershipApplicationService, type MemberRepository } from "./application.js";
import { Member, type MemberSnapshot, type SeatClass } from "./domain.js";
import { type EventEnvelope, type EventPublisher } from "./ports.js";
import { superviseSubscribe, type SubscribeSupervisor, type SupervisedConsumer, type SuperviseSubscribeOptions } from "./subscriber-retry.js";
import {
  OutboxAppender,
  OutboxRelay,
  PostgresIdempotencyStore,
  RedisStreamEventPublisher,
  RedisStreamEventSubscriber,
  connectRedisWithRetry,
  createPostgresPool,
  createRedisClient,
  migrationsAwareReadiness,
  redisClientLiveness,
  startMigrations,
  type IdempotencyStore,
  type SchemaDetail,
} from "@trainticket/ts-kit";

export const JOURNEY_ORDER_STREAM = "events:journey-order";
export const PAYMENT_STREAM = "events:payment";
export const POST_SALES_STREAM = "events:post-sales";

export type BootstrapOptions = Readonly<{
  host?: string;
  port?: number;
  instrumentation?: InstrumentationHooks;
}>;

export function runtimeHost(options: Pick<BootstrapOptions, "host"> = {}): string {
  return options.host ?? process.env.HOST ?? "0.0.0.0";
}

export function runtimePort(options: Pick<BootstrapOptions, "port"> = {}): number {
  if (options.port !== undefined) return options.port;
  const configured = Number.parseInt(process.env.PORT ?? "", 10);
  return Number.isFinite(configured) ? configured : 8080;
}

export async function bootstrap(options: BootstrapOptions = {}) {
  const storage = process.env.DATABASE_URL ? await startLoyaltyStorage() : undefined;
  const publisher = storage ? storage.publisher : new RedisStreamEventPublisher();
  const repository = storage?.repository ?? new InMemoryMemberRepository();
  const application = new LoyaltyMembershipApplicationService(repository, publisher);
  const subscriber = startSubscriber(application);

  const app = createApp({
    instrumentation: options.instrumentation ?? opentelemetryInstrumentationFromEnv(),
    repository,
    publisher,
    idempotencyStore: storage?.idempotencyStore,
    storage,
    eventConsumption: subscriber ? () => subscriber.state() : undefined,
  });
  // Wait for the FIRST subscribe attempt only. Success means consumption is
  // live before we accept traffic; failure means the supervisor is retrying in
  // the background and we start serving HTTP anyway rather than crash-looping
  // on a dependency that a restart cannot fix.
  await subscriber?.settled();
  app.addHook("onClose", async () => {
    await subscriber?.close();
    if (storage) {
      await storage.stop();
    } else {
      await publisher.close?.();
    }
  });
  await app.listen({ host: runtimeHost(options), port: runtimePort(options) });
  return app;
}

/**
 * Start stream consumption, retrying subscribe in the background forever.
 *
 * Previously this awaited `subscribe()` once and, on failure, logged a warning
 * and returned `undefined` -- leaving the process serving HTTP with no event
 * consumption at all, permanently and silently, behind a green `/healthz`. No
 * `redis-consumer.*` component was ever registered on that path, so the
 * never-healthy guard (correctly) prevented liveness from ever firing, and a
 * restart would not have helped because the failure recurs at subscribe time.
 *
 * A subscribe failure is now transient: `superviseSubscribe` keeps retrying
 * with capped exponential backoff until it lands, at which point ts-kit
 * registers `redis-consumer.loyalty-membership` and the existing liveness path
 * takes over for any LATER wedge.
 */
export function startSubscriber<T extends SupervisedConsumer = RedisStreamEventSubscriber>(
  application: LoyaltyMembershipApplicationService,
  overrides: Pick<SuperviseSubscribeOptions<T>, "subscribe" | "backoffMs" | "sleep" | "log"> | undefined = undefined,
): SubscribeSupervisor | undefined {
  if (!overrides && !process.env.REDIS_URL) {
    return undefined;
  }
  return superviseSubscribe<SupervisedConsumer>({
    ...overrides,
    // A fresh subscriber per attempt: a RedisEventSubscriber whose subscribe()
    // rejected has set `stopped = true` for good, so reusing it would register
    // a healthy consumer component whose loops exit immediately -- a green
    // probe over silent non-consumption.
    subscribe: overrides?.subscribe ?? (() => subscribeOnce(application)),
  });
}

async function subscribeOnce(application: LoyaltyMembershipApplicationService): Promise<RedisStreamEventSubscriber> {
  const subscriber = new RedisStreamEventSubscriber(undefined, undefined, { thrownHandlerErrors: "dlq" });
  try {
    await subscriber.subscribe([JOURNEY_ORDER_STREAM, PAYMENT_STREAM, POST_SALES_STREAM], "loyalty-membership", consumerName(), async (envelope) => {
      if (envelope.eventType === "JourneyOrderCreated") {
        await handleJourneyOrderCreated(application, envelope);
      }
      if (envelope.eventType === "JourneyOrderCancelled") {
        await handleJourneyOrderCancelled(application, envelope);
      }
      if (envelope.eventType === "PaymentCaptured") {
        await handlePaymentCaptured(application, envelope);
      }
      if (envelope.eventType === "PostSalesApplied") {
        await handlePostSalesApplied(application, envelope);
      }
      return { ok: true };
    });
    return subscriber;
  } catch (error) {
    // Release this attempt's client and liveness component before the retry
    // creates the next one, so failed attempts cannot accumulate.
    await subscriber.stop().catch(() => undefined);
    await subscriber.close().catch(() => undefined);
    throw error;
  }
}

export async function handleJourneyOrderCreated(application: LoyaltyMembershipApplicationService, envelope: EventEnvelope): Promise<void> {
  const payload = envelope.payload;
  await application.recordTripFromJourneyOrderCreated({
    orderId: stringField(payload.orderId, "orderId"),
    accountId: stringField(payload.accountId, "accountId"),
    occurredAt: new Date(typeof payload.createdAt === "string" ? payload.createdAt : envelope.occurredAt),
    trips: 1,
    correlationId: envelope.correlationId,
    causationId: envelope.eventId,
  });
}

export async function handlePostSalesApplied(application: LoyaltyMembershipApplicationService, envelope: EventEnvelope): Promise<void> {
  const payload = envelope.payload;
  const orderId = stringField(payload.orderId, "orderId");
  const caseId = optionalStringField(payload.caseId) ?? optionalStringField(payload.postSalesCaseId);
  if (!caseId) {
    throw new Error("caseId is required");
  }
  const resultSummary = optionalObjectField(payload.resultSummary);
  const refundDecision = optionalObjectField(payload.refundDecision);
  if (!postSalesAppliedRefunded(resultSummary, refundDecision)) {
    return;
  }
  await application.deductQualifyingPointsForRefund({
    orderId,
    occurredAt: new Date(envelope.occurredAt),
    qualifyingPoints: optionalNonNegativeInteger(resultSummary?.qualifyingPoints ?? refundDecision?.qualifyingPoints, "qualifyingPoints"),
  });
}

export async function handlePaymentCaptured(application: LoyaltyMembershipApplicationService, envelope: EventEnvelope): Promise<void> {
  const payload = envelope.payload;
  const orderId = stringField(payload.businessRef ?? payload.orderId, "businessRef");
  const accountId = optionalStringField(payload.accountId);
  const capturedAmount = objectField(payload.capturedAmount ?? payload.amount, "capturedAmount");
  const minorUnits = integerField(capturedAmount.minorUnits, "capturedAmount.minorUnits");
  const currency = stringField(capturedAmount.currency, "capturedAmount.currency");
  const capturedAt = new Date(typeof payload.capturedAt === "string" ? payload.capturedAt : envelope.occurredAt);
  await application.accrueFromPaymentCaptured({
    orderId,
    accountId,
    ticketPrice: { currency, minorUnits },
    sourceEventId: envelope.eventId,
    confirmedAt: capturedAt,
    correlationId: envelope.correlationId,
    causationId: envelope.eventId,
    seatClass: optionalSeatClass(payload.seatClass),
    travelDate: optionalTravelDate(payload),
    routeCode: optionalStringField(payload.routeCode),
    routeName: optionalStringField(payload.routeName),
    isHoliday: typeof payload.isHoliday === "boolean" ? payload.isHoliday : undefined,
  });
}

export async function handleJourneyOrderConfirmed(application: LoyaltyMembershipApplicationService, envelope: EventEnvelope): Promise<void> {
  const payload = envelope.payload;
  await application.recordTripFromJourneyOrderCreated({
    orderId: stringField(payload.orderId, "orderId"),
    accountId: stringField(payload.accountId, "accountId"),
    occurredAt: new Date(typeof payload.confirmedAt === "string" ? payload.confirmedAt : envelope.occurredAt),
    trips: 0,
    correlationId: envelope.correlationId,
    causationId: envelope.eventId,
  });
}

export async function handleJourneyOrderCancelled(application: LoyaltyMembershipApplicationService, envelope: EventEnvelope): Promise<void> {
  const payload = envelope.payload;
  await application.restoreRedeemedPointsForCancelledOrder({
    orderId: stringField(payload.orderId, "orderId"),
    accountId: stringField(payload.accountId, "accountId"),
    sourceEventId: envelope.eventId,
    cancelledAt: new Date(typeof payload.cancelledAt === "string" ? payload.cancelledAt : envelope.occurredAt),
    correlationId: envelope.correlationId,
    causationId: envelope.eventId,
  });
}

export type LoyaltyStorage = Readonly<{
  repository: MemberRepository;
  publisher: EventPublisher & Readonly<{ close?: () => Promise<void> }>;
  idempotencyStore: IdempotencyStore;
  ready: () => Promise<boolean>;
  /** Migration retry state, surfaced on `/readyz` so a stuck boot is diagnosable. */
  schema: () => SchemaDetail;
  runCommand: <T>(operation: (application: LoyaltyMembershipApplicationService) => Promise<T>) => Promise<T>;
  stop: () => Promise<void>;
}>;

/**
 * loyalty-membership is the one service whose migration handling had a
 * DIFFERENT shape, so it gets a correspondingly different fix.
 *
 * The other six did `try { await migrations.apply(); } catch { console.error }`
 * and then gated readiness on `migrations.isReady`, which is the permanent-503
 * wedge. This service did neither: it awaited `apply()` bare, so a failure
 * propagated up through `bootstrap()` into `main.ts`, which logs and calls
 * `process.exit(1)` -- a CRASH LOOP rather than a wedge -- and its `ready()`
 * only ran `checkPostgresReadiness(pool)`, never consulting migration state at
 * all. That second half is its own latent bug: had the process survived a
 * partially-applied migration, `/readyz` would have answered 200 while the
 * schema was incomplete, which is the failure the whole readiness gate exists
 * to prevent.
 *
 * Both are fixed the same way as everywhere else, and the crash loop is
 * genuinely worse than what it is replaced by: CrashLoopBackOff caps at 5
 * minutes between attempts, gives up its Redis connection and outbox relay on
 * every cycle, and produces restart counts that mask the actual cause. The
 * supervisor retries in-process on a 100ms -> 30s schedule while keeping the
 * pod's HTTP listener and its logs alive to say why it is not ready.
 */
export async function startLoyaltyStorage(pool: Pool = createPostgresPool()): Promise<LoyaltyStorage> {
  const migrations = await startMigrations(pool, join(dirname(fileURLToPath(import.meta.url)), "..", "migrations"), { service: "loyalty-membership" });
  // createRedisClient attaches an "error" listener and an infinite capped
  // backoff retry strategy, and registers a liveness component; `new Redis`
  // got none of that (2026-09-06 outage).
  const redis = createRedisClient(process.env.REDIS_URL ?? "redis://localhost:6379", {}, "loyalty-membership-outbox");
  await connectRedisWithRetry(redis, "loyalty-membership-outbox");
  const repository = new PostgresMemberRepository(pool);
  const publisher = new TransactionalOutboxPublisher(new OutboxAppender(pool));
  const relay = new OutboxRelay(pool, redis, { name: "loyalty-membership-outbox-relay", onFailure: (error: unknown) => console.warn({ service: "loyalty-membership", dependency: "outbox", errorName: error instanceof Error ? error.name : "UnknownError" }) });
  relay.start();
  const idempotencyStore = new PostgresIdempotencyStore(pool);
  return {
    repository,
    publisher,
    idempotencyStore,
    // Gate on migrations as the other six services already did. Without this,
    // a pod whose schema is missing answers /readyz 200 and takes traffic that
    // can only 500.
    ready: migrationsAwareReadiness(migrations, pool),
    schema: () => migrations.detail(),
    runCommand: async (operation) => operation(new LoyaltyMembershipApplicationService(repository, publisher)),
    stop: async () => {
      // Stop the migration retry first: otherwise a shutdown during the retry
      // window leaves a backoff timer running and can issue queries against a
      // pool that is being torn down.
      await migrations.stop();
      await relay.stop();
      redisClientLiveness(redis)?.dispose();
      await redis.quit();
      await pool.end();
    },
  };
}

class TransactionalOutboxPublisher implements EventPublisher {
  constructor(private readonly appender: OutboxAppender) {}

  async publish(envelope: EventEnvelope): Promise<void> {
    await this.appender.append(envelope);
  }
}

export class PostgresMemberRepository implements MemberRepository {
  constructor(private readonly pool: Pool) {}

  async findByMemberId(memberId: string): Promise<Member | undefined> {
    const result = await this.pool.query("SELECT snapshot FROM members WHERE member_id = $1", [memberId]);
    return rowToMember(result.rows[0]);
  }

  async findByAccountId(accountId: string): Promise<Member | undefined> {
    const result = await this.pool.query("SELECT snapshot FROM members WHERE account_id = $1", [accountId]);
    return rowToMember(result.rows[0]);
  }

  async findAccountIdByOrderId(orderId: string): Promise<string | undefined> {
    const result = await this.pool.query("SELECT account_id FROM order_account_refs WHERE order_id = $1", [orderId]);
    return result.rows[0]?.account_id as string | undefined;
  }

  async saveOrderAccountRef(orderId: string, accountId: string): Promise<void> {
    await this.pool.query(
      `INSERT INTO order_account_refs (order_id, account_id) VALUES ($1, $2)
       ON CONFLICT (order_id) DO UPDATE SET account_id = EXCLUDED.account_id`,
      [orderId, accountId],
    );
  }

  async save(member: Member): Promise<void> {
    const snapshot = member.toSnapshot();
    await this.pool.query(
      `INSERT INTO members (member_id, account_id, tier, status, redeemable_points, tier_points, lifetime_points, snapshot)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
       ON CONFLICT (member_id) DO UPDATE SET
         tier = EXCLUDED.tier,
         status = EXCLUDED.status,
         redeemable_points = EXCLUDED.redeemable_points,
         tier_points = EXCLUDED.tier_points,
         lifetime_points = EXCLUDED.lifetime_points,
         snapshot = EXCLUDED.snapshot,
         updated_at = now()`,
      [snapshot.memberId, snapshot.accountId, snapshot.tier, snapshot.status, snapshot.redeemablePoints, snapshot.tierPoints, snapshot.lifetimePoints, snapshot],
    );
    await this.pool.query("DELETE FROM points_ledger WHERE member_id = $1", [snapshot.memberId]);
    for (const entry of snapshot.ledger) {
      await this.pool.query(
        `INSERT INTO points_ledger (entry_id, member_id, entry_type, points, source_event_id, source_event_type, business_reason, occurred_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
         ON CONFLICT (entry_id) DO NOTHING`,
        [entry.entryId, entry.memberId, entry.type, entry.points, entry.sourceFactRef.eventId, entry.sourceFactRef.eventType, entry.businessReason, entry.occurredAt],
      );
    }
  }
}

function rowToMember(row: { snapshot: MemberSnapshot } | undefined): Member | undefined {
  return row ? Member.fromSnapshot(row.snapshot) : undefined;
}

function consumerName(): string {
  return process.env.HOSTNAME ?? `loyalty-membership-${process.pid}`;
}

function stringField(value: unknown, field: string): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error(`${field} is required`);
  }
  return value;
}

function optionalStringField(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}

function optionalObjectField(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}

function optionalNonNegativeInteger(value: unknown, field: string): number | undefined {
  if (value === undefined) {
    return undefined;
  }
  const parsed = integerField(value, field);
  if (parsed < 0) {
    throw new Error(`${field} must be a non-negative integer`);
  }
  return parsed;
}

function postSalesAppliedRefunded(resultSummary?: Record<string, unknown>, refundDecision?: Record<string, unknown>): boolean {
  if (refundDecision) {
    if (typeof refundDecision.refunded === "boolean") return refundDecision.refunded;
    if (typeof refundDecision.eligible === "boolean") return refundDecision.eligible;
    if (typeof refundDecision.kind === "string") return refundDecision.kind.toUpperCase().includes("REFUND");
  }
  if (!resultSummary) {
    return true;
  }
  if (typeof resultSummary.refund === "boolean") return resultSummary.refund;
  if (typeof resultSummary.refunded === "boolean") return resultSummary.refunded;
  return true;
}

/**
 * The date the member actually TRAVELS, when the upstream event carries it.
 *
 * The weekend/holiday accrual bonuses are defined against the travel date, not
 * the purchase or capture date. `PaymentCaptured` is not currently specified to
 * carry one (docs/08-contracts/events/payment.md), so this returns undefined for
 * today's traffic and the domain awards the base rate rather than inventing a
 * date -- see the note on `PointsCalculationInput`. The aliases follow the
 * tolerant-reader precedent in services/reporting (`serviceDate`/`travelDate`/
 * `departureDate`), so once any producer starts emitting one of these fields the
 * bonus applies with no further change here.
 *
 * Accepts a full RFC3339 timestamp or a bare `YYYY-MM-DD` operating date. A
 * value that is present but unparseable is ignored rather than thrown on: a
 * malformed optional enrichment field must not stop the member being credited.
 */
function optionalTravelDate(payload: Record<string, unknown>): Date | undefined {
  for (const field of ["travelDate", "serviceDate", "departureDate", "departureTime"]) {
    const raw = optionalStringField(payload[field]);
    if (!raw) {
      continue;
    }
    // A bare YYYY-MM-DD is parsed as UTC midnight by Date, which is what the
    // UTC-based weekday/holiday checks in the domain expect.
    const parsed = new Date(raw);
    if (!Number.isNaN(parsed.getTime())) {
      return parsed;
    }
  }
  return undefined;
}

function optionalSeatClass(value: unknown): SeatClass | undefined {
  const seatClass = optionalStringField(value);
  if (!seatClass) {
    return undefined;
  }
  if (["SECOND_CLASS", "FIRST_CLASS", "BUSINESS_CLASS", "二等座", "一等座", "商务座"].includes(seatClass)) {
    return seatClass as SeatClass;
  }
  throw new Error("seatClass is invalid");
}

function integerField(value: unknown, field: string): number {
  if (!Number.isInteger(value)) {
    throw new Error(`${field} must be an integer`);
  }
  return value as number;
}

function objectField(value: unknown, field: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`${field} must be an object`);
  }
  return value as Record<string, unknown>;
}
