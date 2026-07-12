import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { Redis } from "ioredis";
import type { Pool } from "pg";

import { createApp, opentelemetryInstrumentationFromEnv, type InstrumentationHooks } from "./app.js";
import { InMemoryMemberRepository, LoyaltyMembershipApplicationService, type MemberRepository } from "./application.js";
import { Member, type MemberSnapshot, type SeatClass } from "./domain.js";
import { type EventEnvelope, type EventPublisher } from "./ports.js";
import {
  MigrationRunner,
  OutboxAppender,
  OutboxRelay,
  PostgresIdempotencyStore,
  RedisStreamEventPublisher,
  RedisStreamEventSubscriber,
  checkPostgresReadiness,
  createPostgresPool,
  type IdempotencyStore,
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
  const subscriber = await startSubscriber(application);

  const app = createApp({
    instrumentation: options.instrumentation ?? opentelemetryInstrumentationFromEnv(),
    repository,
    publisher,
    idempotencyStore: storage?.idempotencyStore,
    storage,
  });
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

async function startSubscriber(application: LoyaltyMembershipApplicationService): Promise<RedisStreamEventSubscriber | undefined> {
  if (!process.env.REDIS_URL) {
    return undefined;
  }
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
    await subscriber.stop().catch(() => undefined);
    await subscriber.close().catch(() => undefined);
    console.warn({
      service: "loyalty-membership",
      dependency: "redis",
      message: "event subscriber unavailable; HTTP API starting without stream consumption",
      errorName: error instanceof Error ? error.name : "UnknownError",
    });
    return undefined;
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
  runCommand: <T>(operation: (application: LoyaltyMembershipApplicationService) => Promise<T>) => Promise<T>;
  stop: () => Promise<void>;
}>;

export async function startLoyaltyStorage(pool: Pool = createPostgresPool()): Promise<LoyaltyStorage> {
  const migrations = new MigrationRunner(pool, join(dirname(fileURLToPath(import.meta.url)), "..", "migrations"));
  await migrations.apply();
  const redis = new Redis(process.env.REDIS_URL ?? "redis://localhost:6379", { lazyConnect: true });
  const repository = new PostgresMemberRepository(pool);
  const publisher = new TransactionalOutboxPublisher(new OutboxAppender(pool));
  const relay = new OutboxRelay(pool, redis, { onFailure: (error: unknown) => console.warn({ service: "loyalty-membership", dependency: "outbox", errorName: error instanceof Error ? error.name : "UnknownError" }) });
  relay.start();
  const idempotencyStore = new PostgresIdempotencyStore(pool);
  return {
    repository,
    publisher,
    idempotencyStore,
    ready: () => checkPostgresReadiness(pool),
    runCommand: async (operation) => operation(new LoyaltyMembershipApplicationService(repository, publisher)),
    stop: async () => {
      await relay.stop();
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
