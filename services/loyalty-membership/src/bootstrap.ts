import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { Redis } from "ioredis";
import type { Pool } from "pg";

import { createApp, opentelemetryInstrumentationFromEnv, type InstrumentationHooks } from "./app.js";
import { InMemoryMemberRepository, LoyaltyMembershipApplicationService, type MemberRepository } from "./application.js";
import { Member, type MemberSnapshot } from "./domain.js";
import { type EventEnvelope, type EventPublisher } from "./ports.js";
import {
  MigrationRunner,
  PostgresIdempotencyStore,
  RedisStreamEventPublisher,
  RedisStreamEventSubscriber,
  checkPostgresReadiness,
  createPostgresPool,
  type IdempotencyStore,
} from "@trainticket/ts-kit";

export const JOURNEY_ORDER_STREAM = "events:journey-order";
export const PAYMENT_STREAM = "events:payment";

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
    await subscriber.subscribe([JOURNEY_ORDER_STREAM, PAYMENT_STREAM], "loyalty-membership", consumerName(), async (envelope) => {
      if (envelope.eventType === "JourneyOrderConfirmed") {
        await handleJourneyOrderConfirmed(application, envelope);
      }
      if (envelope.eventType === "PaymentCaptured") {
        // Payment spend tracking will share the same inbox boundary. The current
        // foundation intentionally keeps accrual tied to JourneyOrderConfirmed per REQ-210.
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

export async function handleJourneyOrderConfirmed(application: LoyaltyMembershipApplicationService, envelope: EventEnvelope): Promise<void> {
  const payload = envelope.payload;
  const orderId = stringField(payload.orderId, "orderId");
  const accountId = stringField(payload.accountId, "accountId");
  const monetarySummary = objectField(payload.monetarySummary, "monetarySummary");
  const total = objectField(monetarySummary.total, "monetarySummary.total");
  const minorUnits = integerField(total.minorUnits, "monetarySummary.total.minorUnits");
  const currency = stringField(total.currency ?? monetarySummary.currency, "currency");
  const confirmedAt = new Date(stringField(payload.confirmedAt, "confirmedAt"));
  await application.accrueFromJourneyOrderConfirmed({
    orderId,
    accountId,
    ticketPrice: { currency, minorUnits },
    sourceEventId: envelope.eventId,
    confirmedAt,
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
  const publisher = new RedisStreamEventPublisher(redis);
  const idempotencyStore = new PostgresIdempotencyStore(pool);
  return {
    repository,
    publisher,
    idempotencyStore,
    ready: () => checkPostgresReadiness(pool),
    runCommand: async (operation) => operation(new LoyaltyMembershipApplicationService(repository, publisher)),
    stop: async () => {
      await publisher.close();
      await pool.end();
    },
  };
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
