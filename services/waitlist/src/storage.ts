import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { OptimisticConcurrencyConflict, OutboxAppender, OutboxRelay, PostgresIdempotencyStore, connectRedisWithRetry, createPostgresPool, createRedisClient, migrationsAwareReadiness, redisClientLiveness, startMigrations, streamForProducer, withTransaction, type EventEnvelope, type SchemaDetail } from "@trainticket/ts-kit";
import type { Pool, PoolClient, QueryResult } from "pg";
import { DomainError, WaitlistEntry, type WaitlistEntrySnapshot } from "./domain.js";
import type { WaitlistRepository } from "./promotion.js";

export class PostgresWaitlistRepository implements WaitlistRepository {
  constructor(private readonly db: Pool | PoolClient) {}

  async add(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    const snapshot = entry.toSnapshot(0);
    try {
      await this.db.query(
        `INSERT INTO waitlist_entries (entry_id, account_id, traveler_refs, segment_ref, departure_date, seat_class, priority_score, status, offered_at, offer_expires_at, fare_quote_id, capacity_hold_id, data, created_at)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)`,
        [snapshot.entryId, snapshot.accountId, JSON.stringify(snapshot.travelerRefs), snapshot.segmentRef, snapshot.departureDate, snapshot.seatClass, snapshot.priorityScore, snapshot.status, snapshot.offeredAt, snapshot.offerExpiresAt, snapshot.fareQuoteId ?? null, snapshot.capacityHoldId ?? null, snapshot, snapshot.createdAt],
      );
    } catch (error) {
      if (isUniqueViolation(error)) throw new DomainError("CONFLICT", "An active waitlist request already exists for this traveler and intent");
      throw error;
    }
    entry.markPersisted(1);
    return this.snapshot(entry);
  }

  async get(entryId: string): Promise<WaitlistEntry | undefined> {
    const result = await this.db.query(`SELECT * FROM waitlist_entries WHERE entry_id = $1`, [entryId]) as QueryResult<WaitlistRow>;
    return result.rows[0] ? entryFromRow(result.rows[0]) : undefined;
  }

  async save(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    const snapshot = entry.toSnapshot(0);
    const loadedVersion = entry.loadedVersion;
    if (loadedVersion < 1) throw new OptimisticConcurrencyConflict(`Waitlist entry ${entry.entryId} has no loaded version`);
    const result = await this.db.query(
      `UPDATE waitlist_entries
       SET status=$2, offered_at=$3, offer_expires_at=$4, fare_quote_id=$5, capacity_hold_id=$6, data=$7, version=version+1, updated_at=now()
       WHERE entry_id=$1 AND version=$8
       RETURNING version`,
      [snapshot.entryId, snapshot.status, snapshot.offeredAt, snapshot.offerExpiresAt, snapshot.fareQuoteId ?? null, snapshot.capacityHoldId ?? null, snapshot, loadedVersion],
    );
    if (result.rowCount === 0) throw new OptimisticConcurrencyConflict(`Waitlist entry ${entry.entryId} was modified by another writer`);
    entry.markPersisted(Number((result as QueryResult<{ version: string | number | bigint }>).rows[0]?.version ?? loadedVersion + 1));
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

  async findExpiredOffers(now: Date): Promise<readonly WaitlistEntry[]> {
    const result = await this.db.query(
      `SELECT * FROM waitlist_entries
       WHERE (status = 'MATCHING' AND offer_expires_at <= $1)
          OR (status IN ('QUEUED', 'MATCHING') AND (data->>'deadline')::timestamptz <= $1)
       ORDER BY COALESCE(offer_expires_at, (data->>'deadline')::timestamptz) ASC, entry_id ASC`,
      [now.toISOString()],
    ) as QueryResult<WaitlistRow>;
    return result.rows.map(entryFromRow);
  }

  async findArchivable(): Promise<readonly WaitlistEntry[]> {
    const result = await this.db.query(`SELECT * FROM waitlist_entries WHERE status IN ('FULFILLED', 'EXPIRED', 'CANCELLED') ORDER BY updated_at ASC, entry_id ASC`) as QueryResult<WaitlistRow>;
    return result.rows.map(entryFromRow);
  }

  async findByJourneyOrderRef(journeyOrderRef: string): Promise<WaitlistEntry | undefined> {
    const result = await this.db.query(`SELECT * FROM waitlist_entries WHERE data->>'journeyOrderRef' = $1 ORDER BY created_at ASC LIMIT 1`, [journeyOrderRef]) as QueryResult<WaitlistRow>;
    return result.rows[0] ? entryFromRow(result.rows[0]) : undefined;
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

  async listByTraveler(travelerRef: string): Promise<readonly WaitlistEntrySnapshot[]> {
    const result = await this.db.query(
      `SELECT * FROM waitlist_entries WHERE traveler_refs ? $1 ORDER BY created_at ASC, entry_id ASC`,
      [travelerRef],
    ) as QueryResult<WaitlistRow>;
    const entries = result.rows.map(entryFromRow);
    return entries.map((entry) => entry.toSnapshot(positionIn(entries, entry.entryId)));
  }

  private async snapshot(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    const queue = await this.queueFor(entry.segmentRef, entry.departureDate, entry.seatClass);
    return queue.find((candidate) => candidate.entryId === entry.entryId) ?? entry.toSnapshot(0);
  }
}

export class ProcessedEventRepository {
  constructor(private readonly db: Pool | PoolClient) {}

  async record(eventId: string, stream: string): Promise<boolean> {
    const result = await this.db.query(
      `INSERT INTO processed_events (event_id, stream)
       VALUES ($1, $2)
       ON CONFLICT (event_id) DO NOTHING`,
      [eventId, stream],
    );
    return (result.rowCount ?? 0) > 0;
  }
}

export type WaitlistStorageRuntime = Readonly<{
  ready: () => Promise<boolean>;
  /** Migration retry state, surfaced on `/readyz` so a stuck boot is diagnosable. */
  schema: () => SchemaDetail;
  idempotencyStore: PostgresIdempotencyStore;
  runCommand: <T>(operation: (repository: WaitlistRepository, publisher: TransactionalOutboxPublisher) => Promise<T>) => Promise<T>;
  runConsumedEvent: <T>(eventId: string, stream: string, operation: (repository: WaitlistRepository, publisher: TransactionalOutboxPublisher) => Promise<T>) => Promise<T | undefined>;
  stop: () => Promise<void>;
}>;

export async function startWaitlistStorage(redisUrl = process.env.REDIS_URL ?? "redis://localhost:6379"): Promise<WaitlistStorageRuntime> {
  const pool = createPostgresPool();
  // A migration failure used to be caught, logged once and abandoned, which wedged `isReady` false and `/readyz` at 503 for the life of the process (2026-09-07 Postgres OOMKill). `startMigrations` awaits the first attempt -- so a successful boot is unchanged and the schema is in place before we serve -- and, if it failed, keeps retrying in the background on the shared capped-backoff schedule until the database comes back. See `superviseMigrations` for the readiness/liveness reasoning.
  const migrations = await startMigrations(pool, migrationsDirectory(), { service: "waitlist" });
  // createRedisClient attaches an "error" listener and an infinite capped backoff retry strategy, and registers a liveness component; `new Redis(url)` got none of that (2026-09-06 outage).
  const redis = createRedisClient(redisUrl, {}, "waitlist-outbox");
  await connectRedisWithRetry(redis, "waitlist-outbox");
  const relay = new OutboxRelay(pool, redis, { pollIntervalMs: parseInt(process.env.OUTBOX_POLL_INTERVAL_MS || "50", 10), name: "waitlist-outbox-relay", onFailure: (error) => console.error(sanitizedErrorForLog(error)) });
  relay.start();
  return {
    ready: migrationsAwareReadiness(migrations, pool),
    schema: () => migrations.detail(),
    idempotencyStore: new PostgresIdempotencyStore(pool),
    runCommand: (operation) => withTransaction(pool, async (client) => operation(new PostgresWaitlistRepository(client), new TransactionalOutboxPublisher(new OutboxAppender(client)))),
    runConsumedEvent: (eventId, stream, operation) => withTransaction(pool, async (client) => {
      if (!await new ProcessedEventRepository(client).record(eventId, stream)) return undefined;
      return operation(new PostgresWaitlistRepository(client), new TransactionalOutboxPublisher(new OutboxAppender(client)));
    }),
    // Stop the migration retry first: otherwise a shutdown during the retry window leaves a backoff timer running and can issue queries against a pool that is being torn down.
    stop: async () => { await migrations.stop(); await relay.stop(); redisClientLiveness(redis)?.dispose(); await Promise.allSettled([redis.quit(), pool.end()]); },
  };
}

export class TransactionalOutboxPublisher {
  constructor(private readonly appender: OutboxAppender) {}
  async publish(envelope: EventEnvelope): Promise<void> { await this.appender.append(envelope, streamForProducer(envelope.producer)); }
}

export function migrationsDirectory(): string { return process.env.MIGRATIONS_DIR ?? join(dirname(fileURLToPath(import.meta.url)), "../migrations"); }

function entryFromRow(row: WaitlistRow): WaitlistEntry {
  const data = row.data ?? {};
  const entry = WaitlistEntry.fromSnapshot({
    entryId: row.entry_id,
    accountId: row.account_id,
    travelerRefs: Array.isArray(row.traveler_refs) ? row.traveler_refs.map(String) : [],
    segmentRef: row.segment_ref,
    departureDate: dateString(row.departure_date),
    seatClass: row.seat_class as WaitlistEntrySnapshot["seatClass"],
    priorityScore: row.priority_score,
    status: row.status as WaitlistEntrySnapshot["status"],
    queuePosition: 0,
    createdAt: instantString(row.created_at),
    offeredAt: row.offered_at ? instantString(row.offered_at) : null,
    offerExpiresAt: row.offer_expires_at ? instantString(row.offer_expires_at) : null,
    fareQuoteId: row.fare_quote_id ?? (typeof data.fareQuoteId === "string" ? data.fareQuoteId : undefined),
    capacityHoldId: row.capacity_hold_id ?? (typeof data.capacityHoldId === "string" ? data.capacityHoldId : undefined),
    offerId: typeof data.offerId === "string" ? data.offerId : undefined,
    offerVersion: typeof data.offerVersion === "number" ? data.offerVersion : undefined,
    itineraryRef: typeof data.itineraryRef === "string" ? data.itineraryRef : undefined,
    deadline: typeof data.deadline === "string" ? data.deadline : deadlineFromDepartureDate(dateString(row.departure_date)),
    paymentGuaranteeRef: typeof data.paymentGuaranteeRef === "string" ? data.paymentGuaranteeRef : `pay-auth-${row.entry_id}`,
    intentFingerprint: typeof data.intentFingerprint === "string" ? data.intentFingerprint : defaultIntentFingerprint(row.traveler_refs, row.segment_ref, dateString(row.departure_date), row.seat_class),
    journeyOrderRef: typeof data.journeyOrderRef === "string" ? data.journeyOrderRef : undefined,
    fareQuoteIdempotencyKey: typeof data.fareQuoteIdempotencyKey === "string" ? data.fareQuoteIdempotencyKey : undefined,
    offerIdempotencyKey: typeof data.offerIdempotencyKey === "string" ? data.offerIdempotencyKey : undefined,
    capacityHoldIdempotencyKey: typeof data.capacityHoldIdempotencyKey === "string" ? data.capacityHoldIdempotencyKey : undefined,
    capacityReleaseIdempotencyKey: typeof data.capacityReleaseIdempotencyKey === "string" ? data.capacityReleaseIdempotencyKey : undefined,
    journeyOrderIdempotencyKey: typeof data.journeyOrderIdempotencyKey === "string" ? data.journeyOrderIdempotencyKey : undefined,
    capacitySegmentBookingId: typeof data.capacitySegmentBookingId === "string" ? data.capacitySegmentBookingId : undefined,
  });
  entry.markPersisted(Number(row.version));
  return entry;
}

function positionIn(entries: readonly WaitlistEntry[], entryId: string): number {
  const queued = entries.filter((entry) => entry.status === "QUEUED");
  const index = queued.findIndex((entry) => entry.entryId === entryId);
  return index < 0 ? 0 : index + 1;
}

function dateString(value: Date | string): string { return value instanceof Date ? value.toISOString().slice(0, 10) : String(value).slice(0, 10); }
function instantString(value: Date | string): string { return value instanceof Date ? value.toISOString() : new Date(value).toISOString(); }
function deadlineFromDepartureDate(departureDate: string): string { return `${departureDate}T00:00:00.000Z`; }
function defaultIntentFingerprint(travelerRefs: unknown, segmentRef: string, departureDate: string, seatClass: string): string {
  const travelerRef = Array.isArray(travelerRefs) ? String(travelerRefs[0] ?? "unknown") : "unknown";
  return `${travelerRef}:${segmentRef}:${departureDate}:${seatClass}`;
}
function isUniqueViolation(error: unknown): boolean {
  if (typeof error !== "object" || error === null) return false;
  const pgError = error as { code?: unknown; constraint?: unknown };
  return pgError.code === "23505" && (pgError.constraint === undefined || pgError.constraint === "waitlist_active_traveler_intent_idx");
}
function sanitizedErrorForLog(error: unknown): Readonly<{ name: string; message: string }> { return error instanceof Error ? { name: error.name || "Error", message: error.message || "Storage failed" } : { name: typeof error, message: "Storage failed" }; }

type WaitlistRow = Readonly<{
  entry_id: string;
  account_id: string;
  traveler_refs: unknown;
  segment_ref: string;
  departure_date: Date | string;
  seat_class: string;
  priority_score: number;
  status: string;
  offered_at: Date | string | null;
  offer_expires_at: Date | string | null;
  fare_quote_id: string | null;
  capacity_hold_id: string | null;
  data: Record<string, unknown> | null;
  version: string | number | bigint;
  created_at: Date | string;
}>;
