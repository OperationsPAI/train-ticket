import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { Redis } from "ioredis";
import { MigrationRunner, OutboxAppender, OutboxRelay, PostgresIdempotencyStore, checkPostgresReadiness, createPostgresPool, streamForProducer, withTransaction, type EventEnvelope } from "@trainticket/ts-kit";
import type { Pool, PoolClient, QueryResult } from "pg";
import { DomainError, WaitlistEntry, type WaitlistEntrySnapshot } from "./domain.js";
import type { WaitlistRepository } from "./promotion.js";

export class PostgresWaitlistRepository implements WaitlistRepository {
  constructor(private readonly db: Pool | PoolClient) {}

  async add(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    const snapshot = entry.toSnapshot(0);
    await this.db.query(
      `INSERT INTO waitlist_entries (entry_id, account_id, traveler_refs, segment_ref, departure_date, seat_class, priority_score, status, offered_at, offer_expires_at, fare_quote_id, capacity_hold_id, data, created_at)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)`,
      [snapshot.entryId, snapshot.accountId, JSON.stringify(snapshot.travelerRefs), snapshot.segmentRef, snapshot.departureDate, snapshot.seatClass, snapshot.priorityScore, snapshot.status, snapshot.offeredAt, snapshot.offerExpiresAt, snapshot.fareQuoteId ?? null, snapshot.capacityHoldId ?? null, snapshot, snapshot.createdAt],
    );
    return this.snapshot(entry);
  }

  async get(entryId: string): Promise<WaitlistEntry | undefined> {
    const result = await this.db.query(
      `SELECT entry_id, account_id, traveler_refs, segment_ref, departure_date, seat_class, priority_score, status, offered_at, offer_expires_at, fare_quote_id, capacity_hold_id, data, created_at FROM waitlist_entries WHERE entry_id = $1
       UNION ALL
       SELECT entry_id, account_id, traveler_refs, segment_ref, departure_date, seat_class, priority_score, status, offered_at, offer_expires_at, fare_quote_id, capacity_hold_id, data, created_at FROM waitlist_entries_archive WHERE entry_id = $1
       LIMIT 1`,
      [entryId],
    ) as QueryResult<WaitlistRow>;
    return result.rows[0] ? entryFromRow(result.rows[0]) : undefined;
  }

  async save(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    const snapshot = entry.toSnapshot(0);
    if (snapshot.status === "CLOSED") {
      const result = await this.db.query(
        `WITH current_row AS (
           SELECT version FROM waitlist_entries WHERE entry_id=$1 AND status IN ('FULFILLED', 'EXPIRED', 'CANCELLED') FOR UPDATE
         ), moved AS (
           DELETE FROM waitlist_entries active
           USING current_row
           WHERE active.entry_id=$1 AND active.version=current_row.version
           RETURNING active.*, current_row.version AS expected_version
         )
         INSERT INTO waitlist_entries_archive (entry_id, account_id, traveler_refs, segment_ref, departure_date, seat_class, priority_score, status, offered_at, offer_expires_at, fare_quote_id, capacity_hold_id, data, version, created_at, updated_at, archived_at)
         SELECT entry_id, account_id, traveler_refs, segment_ref, departure_date, seat_class, priority_score, $2, offered_at, offer_expires_at, fare_quote_id, capacity_hold_id, $3, expected_version + 1, created_at, now(), now()
         FROM moved
         ON CONFLICT (entry_id) DO NOTHING
         RETURNING entry_id`,
        [snapshot.entryId, snapshot.status, snapshot],
      );
      if (result.rowCount !== 1) throw new DomainError("PRECONDITION_FAILED", "Waitlist entry version changed before archival");
      return snapshot;
    }
    const result = await this.db.query(
      `UPDATE waitlist_entries
       SET status=$2, offered_at=$3, offer_expires_at=$4, fare_quote_id=$5, capacity_hold_id=$6, data=$7, version=version+1, updated_at=now()
       WHERE entry_id=$1`,
      [snapshot.entryId, snapshot.status, snapshot.offeredAt, snapshot.offerExpiresAt, snapshot.fareQuoteId ?? null, snapshot.capacityHoldId ?? null, snapshot],
    );
    if (result.rowCount !== 1) throw new DomainError("PRECONDITION_FAILED", "Waitlist entry version changed before save");
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
    const result = await this.db.query(`SELECT * FROM waitlist_entries WHERE status = 'MATCHING' AND offer_expires_at <= $1 ORDER BY offer_expires_at ASC`, [now.toISOString()]) as QueryResult<WaitlistRow>;
    return result.rows.map(entryFromRow);
  }

  async findArchivable(): Promise<readonly WaitlistEntry[]> {
    const result = await this.db.query(`SELECT * FROM waitlist_entries WHERE status IN ('FULFILLED', 'EXPIRED', 'CANCELLED') ORDER BY updated_at ASC, entry_id ASC`) as QueryResult<WaitlistRow>;
    return result.rows.map(entryFromRow);
  }

  async queueFor(segmentRef: string, departureDate: string, seatClass?: string): Promise<readonly WaitlistEntrySnapshot[]> {
    const params: unknown[] = [segmentRef, departureDate];
    const seatClause = seatClass ? "AND seat_class = $3" : "";
    if (seatClass) params.push(seatClass);
    const result = await this.db.query(
      `SELECT * FROM waitlist_entries WHERE segment_ref = $1 AND departure_date = $2 ${seatClause} ORDER BY priority_score DESC, created_at ASC, entry_id ASC`,
      params,
    ) as QueryResult<WaitlistRow>;
    const entries = result.rows.map(entryFromRow);
    return entries.map((entry) => entry.toSnapshot(positionIn(entries, entry.entryId)));
  }

  private async snapshot(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    if (entry.status === "CLOSED") return entry.toSnapshot(0);
    const queue = await this.queueFor(entry.segmentRef, entry.departureDate, entry.seatClass);
    return queue.find((candidate) => candidate.entryId === entry.entryId) ?? entry.toSnapshot(0);
  }
}

export type WaitlistStorageRuntime = Readonly<{
  ready: () => Promise<boolean>;
  idempotencyStore: PostgresIdempotencyStore;
  runCommand: <T>(operation: (repository: WaitlistRepository, publisher: TransactionalOutboxPublisher) => Promise<T>) => Promise<T>;
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
  return WaitlistEntry.fromSnapshot({
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
    fareQuoteIdempotencyKey: typeof data.fareQuoteIdempotencyKey === "string" ? data.fareQuoteIdempotencyKey : undefined,
    offerIdempotencyKey: typeof data.offerIdempotencyKey === "string" ? data.offerIdempotencyKey : undefined,
    capacityHoldIdempotencyKey: typeof data.capacityHoldIdempotencyKey === "string" ? data.capacityHoldIdempotencyKey : undefined,
    capacityReleaseIdempotencyKey: typeof data.capacityReleaseIdempotencyKey === "string" ? data.capacityReleaseIdempotencyKey : undefined,
    journeyOrderIdempotencyKey: typeof data.journeyOrderIdempotencyKey === "string" ? data.journeyOrderIdempotencyKey : undefined,
    capacitySegmentBookingId: typeof data.capacitySegmentBookingId === "string" ? data.capacitySegmentBookingId : undefined,
    journeyOrderRef: typeof data.journeyOrderRef === "string" ? data.journeyOrderRef : undefined,
    deadline: typeof data.deadline === "string" ? data.deadline : undefined,
    paymentGuaranteeRef: typeof data.paymentGuaranteeRef === "string" ? data.paymentGuaranteeRef : undefined,
    intentFingerprint: typeof data.intentFingerprint === "string" ? data.intentFingerprint : undefined,
    aggregateVersion: typeof data.aggregateVersion === "number" ? data.aggregateVersion : undefined,
  });
}

function positionIn(entries: readonly WaitlistEntry[], entryId: string): number {
  const queued = entries.filter((entry) => entry.status === "QUEUED");
  const index = queued.findIndex((entry) => entry.entryId === entryId);
  return index < 0 ? 0 : index + 1;
}

function dateString(value: Date | string): string { return value instanceof Date ? value.toISOString().slice(0, 10) : String(value).slice(0, 10); }
function instantString(value: Date | string): string { return value instanceof Date ? value.toISOString() : new Date(value).toISOString(); }
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
  created_at: Date | string;
}>;
