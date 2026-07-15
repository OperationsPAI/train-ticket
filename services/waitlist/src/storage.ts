import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { Redis } from "ioredis";
import { MigrationRunner, OutboxAppender, OutboxRelay, PostgresIdempotencyStore, ProcessedEventsGuard, checkPostgresReadiness, createPostgresPool, streamForProducer, withTransaction, type EventEnvelope } from "@trainticket/ts-kit";
import type { Pool, PoolClient, QueryResult } from "pg";
import { ACTIVE_WAITLIST_STATUSES, DomainError, WaitlistEntry, type WaitlistEntrySnapshot } from "./domain.js";
import type { WaitlistRepository } from "./promotion.js";

export class PostgresWaitlistRepository implements WaitlistRepository {
  constructor(private readonly db: Pool | PoolClient) {}

  async add(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    entry.authorizePaymentReference();
    entry.enqueue();
    const snapshot = entry.toSnapshot(0);
    try {
      await this.db.query(
        `INSERT INTO waitlist_entries (entry_id, account_id, traveler_refs, traveler_ref, segment_ref, departure_date, seat_class, priority_score, status, deadline, payment_guarantee_ref, itinerary_ref, intent_fingerprint, journey_order_ref, data, version, created_at)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17)`,
        [snapshot.entryId, snapshot.accountId, JSON.stringify(snapshot.travelerRefs), snapshot.travelerRef, snapshot.segmentRef, snapshot.departureDate, snapshot.seatClass, snapshot.priorityScore, snapshot.status, snapshot.deadline, snapshot.paymentGuaranteeRef, snapshot.itineraryRef, snapshot.intentFingerprint, snapshot.journeyOrderRef ?? null, snapshot, snapshot.version, snapshot.createdAt],
      );
    } catch (error) {
      if (isUniqueViolation(error)) throw new DomainError("CONFLICT", "An active waitlist request already exists for this traveler and intent");
      throw error;
    }
    entry.markPersisted();
    return this.snapshot(entry);
  }

  async get(entryId: string): Promise<WaitlistEntry | undefined> {
    const result = await this.db.query(`SELECT * FROM waitlist_entries WHERE entry_id = $1`, [entryId]) as QueryResult<WaitlistRow>;
    return result.rows[0] ? entryFromRow(result.rows[0]) : undefined;
  }

  async save(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    const snapshot = entry.toSnapshot(0);
    const result = await this.db.query(
      `UPDATE waitlist_entries
       SET status=$2, offered_at=$3, offer_expires_at=$4, fare_quote_id=$5, capacity_hold_id=$6, journey_order_ref=$7, data=$8, version=version+1, updated_at=now()
       WHERE entry_id=$1 AND version=$9`,
      [snapshot.entryId, snapshot.status, snapshot.matchingStartedAt, snapshot.deadlineExpiredAt, snapshot.fareQuoteId ?? null, snapshot.capacityHoldId ?? null, snapshot.journeyOrderRef ?? null, snapshot, entry.loadedVersion],
    ) as QueryResult;
    if (result.rowCount === 0) {
      throw new DomainError("CONFLICT", "Waitlist request was modified concurrently");
    }
    entry.markPersisted();
    return this.snapshot(entry);
  }

  async findTopQueued(segmentRef: string, departureDate: string, seatClass?: string): Promise<WaitlistEntry | undefined> {
    const params: unknown[] = [segmentRef, departureDate];
    const seatClause = seatClass ? "AND seat_class = $3" : "";
    if (seatClass) params.push(seatClass);
    const result = await this.db.query(
      `SELECT * FROM waitlist_entries WHERE segment_ref = $1 AND departure_date = $2 ${seatClause} AND status = 'QUEUED' ORDER BY priority_score DESC, created_at ASC, entry_id ASC LIMIT 1`,
      params,
    ) as QueryResult<WaitlistRow>;
    return result.rows[0] ? entryFromRow(result.rows[0]) : undefined;
  }

  async findExpired(now: Date): Promise<readonly WaitlistEntry[]> {
    const result = await this.db.query(
      `SELECT * FROM waitlist_entries WHERE status = ANY($1::text[]) AND deadline <= $2 ORDER BY deadline ASC`,
      [ACTIVE_WAITLIST_STATUSES, now.toISOString()],
    ) as QueryResult<WaitlistRow>;
    return result.rows.map(entryFromRow);
  }

  async findArchivable(): Promise<readonly WaitlistEntry[]> {
    const result = await this.db.query(
      `SELECT * FROM waitlist_entries WHERE status IN ('FULFILLED', 'EXPIRED', 'CANCELLED') ORDER BY updated_at ASC`,
    ) as QueryResult<WaitlistRow>;
    return result.rows.map(entryFromRow);
  }

  async queueFor(segmentRef: string, departureDate: string, seatClass?: string): Promise<readonly WaitlistEntrySnapshot[]> {
    const params: unknown[] = [segmentRef, departureDate];
    const seatClause = seatClass ? "AND seat_class = $3" : "";
    if (seatClass) params.push(seatClass);
    const result = await this.db.query(
      `SELECT * FROM waitlist_entries WHERE segment_ref = $1 AND departure_date = $2 ${seatClause} AND status <> 'CLOSED' ORDER BY priority_score DESC, created_at ASC, entry_id ASC`,
      params,
    ) as QueryResult<WaitlistRow>;
    const entries = result.rows.map(entryFromRow);
    return entries.map((entry) => entry.toSnapshot(positionIn(entries, entry.entryId)));
  }

  async findByJourneyOrderRef(journeyOrderRef: string): Promise<WaitlistEntry | undefined> {
    const result = await this.db.query(`SELECT * FROM waitlist_entries WHERE journey_order_ref = $1`, [journeyOrderRef]) as QueryResult<WaitlistRow>;
    return result.rows[0] ? entryFromRow(result.rows[0]) : undefined;
  }

  private async snapshot(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    const queue = await this.queueFor(entry.segmentRef, entry.departureDate, entry.seatClass);
    return queue.find((candidate) => candidate.entryId === entry.entryId) ?? entry.toSnapshot(0);
  }
}

export type WaitlistStorageRuntime = Readonly<{
  ready: () => Promise<boolean>;
  idempotencyStore: PostgresIdempotencyStore;
  runCommand: <T>(operation: (repository: WaitlistRepository, publisher: TransactionalOutboxPublisher) => Promise<T>) => Promise<T>;
  runConsumedEvent: <T>(event: { eventId: string; stream?: string }, operation: (repository: WaitlistRepository, publisher: TransactionalOutboxPublisher) => Promise<T>) => Promise<T | undefined>;
  stop: () => Promise<void>;
}>;

export async function startWaitlistStorage(redisUrl = process.env.REDIS_URL ?? "redis://localhost:6379"): Promise<WaitlistStorageRuntime> {
  const pool = createPostgresPool();
  const migrations = new MigrationRunner(pool, migrationsDirectory());
  try { await migrations.apply(); } catch (error) { console.error(sanitizedErrorForLog(error)); }
  const redis = new Redis(redisUrl, { lazyConnect: true });
  await redis.connect();
  const relay = new OutboxRelay(pool, redis, { pollIntervalMs: parseInt(process.env.OUTBOX_POLL_INTERVAL_MS || "50", 10), onFailure: (error) => console.error(sanitizedErrorForLog(error)) });
  relay.start();
  return {
    ready: () => migrations.isReady ? checkPostgresReadiness(pool) : Promise.resolve(false),
    idempotencyStore: new PostgresIdempotencyStore(pool),
    runCommand: (operation) => withTransaction(pool, async (client) => operation(new PostgresWaitlistRepository(client), new TransactionalOutboxPublisher(new OutboxAppender(client)))),
    runConsumedEvent: (event, operation) => withTransaction(pool, async (client) => {
      const guard = new ProcessedEventsGuard(client);
      return guard.runOnce(event.eventId, event.stream, () => operation(new PostgresWaitlistRepository(client), new TransactionalOutboxPublisher(new OutboxAppender(client))));
    }),
    stop: async () => { await relay.stop(); await Promise.allSettled([redis.quit(), pool.end()]); },
  };
}

export class TransactionalOutboxPublisher {
  constructor(private readonly appender: OutboxAppender) {}
  async publish(envelope: EventEnvelope): Promise<void> { await this.appender.append(envelope, streamForProducer(envelope.producer)); }
}

export function migrationsDirectory(): string { return process.env.MIGRATIONS_DIR ?? join(dirname(fileURLToPath(import.meta.url)), "../migrations"); }

function entryFromRow(row: WaitlistRow): WaitlistEntry {
  const data = row.data ?? {};
  const version = Number(row.version ?? data.version ?? 1);
  return WaitlistEntry.fromSnapshot({
    entryId: row.entry_id,
    waitlistRequestId: row.entry_id,
    accountId: row.account_id,
    travelerRef: row.traveler_ref ?? firstTravelerRef(row.traveler_refs),
    travelerRefs: [row.traveler_ref ?? firstTravelerRef(row.traveler_refs)],
    segmentRef: row.segment_ref,
    departureDate: dateString(row.departure_date),
    seatClass: row.seat_class as WaitlistEntrySnapshot["seatClass"],
    travelClass: row.seat_class,
    priorityScore: row.priority_score,
    status: row.status as WaitlistEntrySnapshot["status"],
    queuePosition: 0,
    createdAt: instantString(row.created_at),
    deadline: instantString(row.deadline ?? stringData(data, "deadline") ?? row.created_at),
    paymentGuaranteeRef: row.payment_guarantee_ref ?? stringData(data, "paymentGuaranteeRef") ?? "pay-auth-legacy",
    itineraryRef: row.itinerary_ref ?? stringData(data, "itineraryRef") ?? row.segment_ref,
    intentFingerprint: row.intent_fingerprint ?? stringData(data, "intentFingerprint") ?? `${row.entry_id}:legacy`,
    version,
    loadedVersion: version,
    matchingStartedAt: stringData(data, "matchingStartedAt") ?? null,
    deadlineExpiredAt: stringData(data, "deadlineExpiredAt") ?? null,
    cancelledAt: stringData(data, "cancelledAt") ?? null,
    fulfilledAt: stringData(data, "fulfilledAt") ?? null,
    closedAt: stringData(data, "closedAt") ?? null,
    fareQuoteId: row.fare_quote_id ?? stringData(data, "fareQuoteId"),
    capacityHoldId: row.capacity_hold_id ?? stringData(data, "capacityHoldId"),
    offerId: stringData(data, "offerId"),
    offerVersion: typeof data.offerVersion === "number" ? data.offerVersion : undefined,
    journeyOrderRef: row.journey_order_ref ?? stringData(data, "journeyOrderRef"),
    fareQuoteIdempotencyKey: stringData(data, "fareQuoteIdempotencyKey"),
    offerIdempotencyKey: stringData(data, "offerIdempotencyKey"),
    capacityHoldIdempotencyKey: stringData(data, "capacityHoldIdempotencyKey"),
    capacityReleaseIdempotencyKey: stringData(data, "capacityReleaseIdempotencyKey"),
    journeyOrderIdempotencyKey: stringData(data, "journeyOrderIdempotencyKey"),
    capacitySegmentBookingId: stringData(data, "capacitySegmentBookingId"),
  });
}

function positionIn(entries: readonly WaitlistEntry[], entryId: string): number {
  const queued = entries.filter((entry) => entry.status === "QUEUED");
  const index = queued.findIndex((entry) => entry.entryId === entryId);
  return index < 0 ? 0 : index + 1;
}

function firstTravelerRef(value: unknown): string {
  return Array.isArray(value) && typeof value[0] === "string" ? value[0] : "tvl-legacy";
}

function stringData(data: Record<string, unknown>, field: string): string | undefined {
  return typeof data[field] === "string" ? data[field] as string : undefined;
}

function isUniqueViolation(error: unknown): boolean {
  return typeof error === "object" && error !== null && "code" in error && (error as { code?: unknown }).code === "23505";
}

function dateString(value: Date | string): string { return value instanceof Date ? value.toISOString().slice(0, 10) : String(value).slice(0, 10); }
function instantString(value: Date | string): string { return value instanceof Date ? value.toISOString() : new Date(value).toISOString(); }
function sanitizedErrorForLog(error: unknown): Readonly<{ name: string; message: string }> { return error instanceof Error ? { name: error.name || "Error", message: error.message || "Storage failed" } : { name: typeof error, message: "Storage failed" }; }

type WaitlistRow = Readonly<{
  entry_id: string;
  account_id: string;
  traveler_refs: unknown;
  traveler_ref: string | null;
  segment_ref: string;
  departure_date: Date | string;
  seat_class: string;
  priority_score: number;
  status: string;
  offered_at: Date | string | null;
  offer_expires_at: Date | string | null;
  fare_quote_id: string | null;
  capacity_hold_id: string | null;
  journey_order_ref: string | null;
  deadline: Date | string | null;
  payment_guarantee_ref: string | null;
  itinerary_ref: string | null;
  intent_fingerprint: string | null;
  data: Record<string, unknown> | null;
  version: number | string;
  created_at: Date | string;
}>;
