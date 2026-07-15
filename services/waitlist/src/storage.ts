import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { Redis } from "ioredis";
import { MigrationRunner, OutboxAppender, OutboxRelay, PostgresIdempotencyStore, checkPostgresReadiness, createPostgresPool, streamForProducer, withTransaction, type EventEnvelope } from "@trainticket/ts-kit";
import type { Pool, PoolClient, QueryResult } from "pg";
import { DomainError, WaitlistEntry, type WaitlistEntrySnapshot, type WaitlistStatus } from "./domain.js";
import type { WaitlistRepository } from "./promotion.js";

export class PostgresWaitlistRepository implements WaitlistRepository {
  constructor(private readonly db: Pool | PoolClient) {}

  async add(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    entry.authorizePayment(entry.createdAt);
    entry.enqueue(entry.createdAt);
    const snapshot = entry.toSnapshot(0);
    try {
      await this.db.query(
        `INSERT INTO waitlist_entries (entry_id, account_id, traveler_ref, traveler_refs, segment_ref, departure_date, seat_class, priority_score, status, deadline, payment_guarantee_ref, itinerary_ref, intent_fingerprint, fare_quote_id, capacity_hold_id, data, version, created_at, updated_at)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19)`,
        [snapshot.entryId, snapshot.accountId, snapshot.travelerRef, JSON.stringify(snapshot.travelerRefs), snapshot.segmentRef, snapshot.departureDate, snapshot.seatClass, snapshot.priorityScore, snapshot.status, snapshot.deadline, snapshot.paymentGuaranteeRef, snapshot.itineraryRef, snapshot.intentFingerprint, snapshot.fareQuoteId ?? null, snapshot.capacityHoldId ?? null, snapshot, snapshot.version, snapshot.createdAt, snapshot.updatedAt],
      );
    } catch (error) {
      throw translatePersistenceError(error);
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
    try {
      const result = await this.db.query(
        `UPDATE waitlist_entries
         SET status=$2, fare_quote_id=$3, capacity_hold_id=$4, offer_version=$5, data=$6, version=version+1, updated_at=$7
         WHERE entry_id=$1 AND version=$8`,
        [snapshot.entryId, snapshot.status, snapshot.fareQuoteId ?? null, snapshot.capacityHoldId ?? null, snapshot.offerVersion ?? null, snapshot, snapshot.updatedAt, entry.loadedVersion],
      );
      if (result.rowCount === 0) throw new DomainError("CONFLICT", "Waitlist request was modified concurrently");
    } catch (error) {
      if (error instanceof DomainError) throw error;
      throw translatePersistenceError(error);
    }
    entry.markPersisted();
    return this.snapshot(entry);
  }

  async findTopQueued(segmentRef: string, departureDate: string, seatClass?: string): Promise<WaitlistEntry | undefined> {
    const params: unknown[] = [segmentRef, departureDate];
    const seatClause = seatClass ? "AND seat_class = $3" : "";
    if (seatClass) params.push(seatClass);
    const result = await this.db.query(`SELECT * FROM waitlist_entries WHERE segment_ref = $1 AND departure_date = $2 ${seatClause} AND status = 'QUEUED' ORDER BY priority_score DESC, created_at ASC, entry_id ASC LIMIT 1`, params) as QueryResult<WaitlistRow>;
    return result.rows[0] ? entryFromRow(result.rows[0]) : undefined;
  }

  async findExpired(now: Date): Promise<readonly WaitlistEntry[]> {
    const result = await this.db.query(`SELECT * FROM waitlist_entries WHERE status IN ('QUEUED','MATCHING','SUSPENDED') AND deadline <= $1 ORDER BY deadline ASC`, [now.toISOString()]) as QueryResult<WaitlistRow>;
    return result.rows.map(entryFromRow);
  }
  async findExpiredOffers(now: Date): Promise<readonly WaitlistEntry[]> { return this.findExpired(now); }

  async findArchivable(): Promise<readonly WaitlistEntry[]> {
    const result = await this.db.query(`SELECT * FROM waitlist_entries WHERE status IN ('FULFILLED','EXPIRED','CANCELLED') ORDER BY updated_at ASC LIMIT 100`) as QueryResult<WaitlistRow>;
    return result.rows.map(entryFromRow);
  }

  async findMatching(): Promise<readonly WaitlistEntry[]> {
    const result = await this.db.query(`SELECT * FROM waitlist_entries WHERE status = 'MATCHING' ORDER BY updated_at ASC LIMIT 100`) as QueryResult<WaitlistRow>;
    return result.rows.map(entryFromRow);
  }

  async queueFor(segmentRef: string, departureDate: string, seatClass?: string): Promise<readonly WaitlistEntrySnapshot[]> {
    const params: unknown[] = [segmentRef, departureDate];
    const seatClause = seatClass ? "AND seat_class = $3" : "";
    if (seatClass) params.push(seatClass);
    const result = await this.db.query(`SELECT * FROM waitlist_entries WHERE segment_ref = $1 AND departure_date = $2 ${seatClause} AND status <> 'CLOSED' ORDER BY priority_score DESC, created_at ASC, entry_id ASC`, params) as QueryResult<WaitlistRow>;
    const entries = result.rows.map(entryFromRow);
    return entries.map((entry) => entry.toSnapshot(positionIn(entries, entry.entryId)));
  }

  async listByTraveler(travelerRef: string, status?: WaitlistStatus, limit = 20, offset = 0): Promise<{ items: readonly WaitlistEntrySnapshot[]; total: number }> {
    const params: unknown[] = [travelerRef];
    const statusClause = status ? "AND status = $2" : "";
    if (status) params.push(status);
    const limitIndex = params.length + 1;
    const offsetIndex = params.length + 2;
    const total = await this.db.query(`SELECT count(*)::int AS total FROM waitlist_entries WHERE traveler_ref = $1 ${statusClause}`, params) as QueryResult<{ total: number }>;
    const result = await this.db.query(`SELECT * FROM waitlist_entries WHERE traveler_ref = $1 ${statusClause} ORDER BY created_at DESC, entry_id ASC LIMIT $${limitIndex} OFFSET $${offsetIndex}`, [...params, limit, offset]) as QueryResult<WaitlistRow>;
    return { items: result.rows.map((row) => entryFromRow(row).toSnapshot(0)), total: total.rows[0]?.total ?? 0 };
  }

  private async snapshot(entry: WaitlistEntry): Promise<WaitlistEntrySnapshot> {
    const queue = await this.queueFor(entry.segmentRef, entry.departureDate, entry.seatClass);
    return queue.find((candidate) => candidate.entryId === entry.entryId) ?? entry.toSnapshot(0);
  }
}

export type WaitlistStorageRuntime = Readonly<{ ready: () => Promise<boolean>; idempotencyStore: PostgresIdempotencyStore; runCommand: <T>(operation: (repository: WaitlistRepository, publisher: TransactionalOutboxPublisher) => Promise<T>) => Promise<T>; stop: () => Promise<void> }>;

export async function startWaitlistStorage(redisUrl = process.env.REDIS_URL ?? "redis://localhost:6379"): Promise<WaitlistStorageRuntime> {
  const pool = createPostgresPool();
  const migrations = new MigrationRunner(pool, migrationsDirectory());
  try { await migrations.apply(); } catch (error) { console.error(sanitizedErrorForLog(error)); }
  const redis = new Redis(redisUrl, { lazyConnect: true });
  await redis.connect();
  const relay = new OutboxRelay(pool, redis, { pollIntervalMs: parseInt(process.env.OUTBOX_POLL_INTERVAL_MS || "50", 10), onFailure: (error) => console.error(sanitizedErrorForLog(error)) });
  relay.start();
  return { ready: () => migrations.isReady ? checkPostgresReadiness(pool) : Promise.resolve(false), idempotencyStore: new PostgresIdempotencyStore(pool), runCommand: (operation) => withTransaction(pool, async (client) => operation(new PostgresWaitlistRepository(client), new TransactionalOutboxPublisher(new OutboxAppender(client)))), stop: async () => { await relay.stop(); await Promise.allSettled([redis.quit(), pool.end()]); } };
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
    travelerRef: row.traveler_ref ?? (Array.isArray(row.traveler_refs) ? String(row.traveler_refs[0] ?? "") : String((data.travelerRef as string | undefined) ?? "")),
    travelerRefs: Array.isArray(row.traveler_refs) ? row.traveler_refs.map(String) : [],
    segmentRef: row.segment_ref,
    departureDate: dateString(row.departure_date),
    seatClass: row.seat_class as WaitlistEntrySnapshot["seatClass"],
    travelClass: row.seat_class,
    priorityScore: row.priority_score,
    status: row.status as WaitlistEntrySnapshot["status"],
    queuePosition: 0,
    deadline: instantString(row.deadline ?? data.deadline ?? row.created_at),
    paymentGuaranteeRef: row.payment_guarantee_ref ?? String(data.paymentGuaranteeRef ?? "pay-auth-migrated"),
    itineraryRef: row.itinerary_ref ?? String(data.itineraryRef ?? row.segment_ref),
    intentFingerprint: row.intent_fingerprint ?? String(data.intentFingerprint ?? `${row.traveler_ref}:${row.segment_ref}:${dateString(row.departure_date)}:${row.seat_class}`),
    journeyOrderRef: typeof data.journeyOrderRef === "string" ? data.journeyOrderRef : undefined,
    version,
    loadedVersion: version,
    createdAt: instantString(row.created_at),
    updatedAt: instantString(row.updated_at ?? row.created_at),
    matchingStartedAt: typeof data.matchingStartedAt === "string" ? data.matchingStartedAt : null,
    cancelledAt: typeof data.cancelledAt === "string" ? data.cancelledAt : null,
    expiredAt: typeof data.expiredAt === "string" ? data.expiredAt : null,
    fulfilledAt: typeof data.fulfilledAt === "string" ? data.fulfilledAt : null,
    fareQuoteId: row.fare_quote_id ?? (typeof data.fareQuoteId === "string" ? data.fareQuoteId : undefined),
    capacityHoldId: row.capacity_hold_id ?? (typeof data.capacityHoldId === "string" ? data.capacityHoldId : undefined),
    offerId: typeof data.offerId === "string" ? data.offerId : undefined,
    offerVersion: typeof data.offerVersion === "number" ? data.offerVersion : numberOrUndefined(row.offer_version),
    fareQuoteIdempotencyKey: typeof data.fareQuoteIdempotencyKey === "string" ? data.fareQuoteIdempotencyKey : undefined,
    offerIdempotencyKey: typeof data.offerIdempotencyKey === "string" ? data.offerIdempotencyKey : undefined,
    capacityHoldIdempotencyKey: typeof data.capacityHoldIdempotencyKey === "string" ? data.capacityHoldIdempotencyKey : undefined,
    capacityReleaseIdempotencyKey: typeof data.capacityReleaseIdempotencyKey === "string" ? data.capacityReleaseIdempotencyKey : undefined,
    journeyOrderIdempotencyKey: typeof data.journeyOrderIdempotencyKey === "string" ? data.journeyOrderIdempotencyKey : undefined,
    pendingJourneyOrderRef: typeof data.pendingJourneyOrderRef === "string" ? data.pendingJourneyOrderRef : undefined,
    capacitySegmentBookingId: typeof data.capacitySegmentBookingId === "string" ? data.capacitySegmentBookingId : undefined,
  });
}

function positionIn(entries: readonly WaitlistEntry[], entryId: string): number { const queued = entries.filter((entry) => entry.status === "QUEUED"); const index = queued.findIndex((entry) => entry.entryId === entryId); return index < 0 ? 0 : index + 1; }
function translatePersistenceError(error: unknown): Error { if (isPgError(error) && error.code === "23505") return new DomainError("CONFLICT", "An active waitlist request already exists for this traveler and intent"); return error instanceof Error ? error : new Error("Waitlist persistence failed"); }
function isPgError(error: unknown): error is { code: string } { return typeof error === "object" && error !== null && "code" in error; }
function dateString(value: Date | string): string { return value instanceof Date ? value.toISOString().slice(0, 10) : String(value).slice(0, 10); }
function numberOrUndefined(value: unknown): number | undefined { const parsed = typeof value === "number" ? value : Number(value); return Number.isFinite(parsed) ? parsed : undefined; }
function instantString(value: unknown): string { return value instanceof Date ? value.toISOString() : new Date(String(value)).toISOString(); }
function sanitizedErrorForLog(error: unknown): Readonly<{ name: string; message: string }> { return error instanceof Error ? { name: error.name || "Error", message: error.message || "Storage failed" } : { name: typeof error, message: "Storage failed" }; }

type WaitlistRow = Readonly<{ entry_id: string; account_id: string; traveler_ref: string | null; traveler_refs: unknown; segment_ref: string; departure_date: Date | string; seat_class: string; priority_score: number; status: string; deadline: Date | string | null; payment_guarantee_ref: string | null; itinerary_ref: string | null; intent_fingerprint: string | null; fare_quote_id: string | null; capacity_hold_id: string | null; offer_version: number | string | null; data: Record<string, unknown> | null; version: number | string; created_at: Date | string; updated_at: Date | string | null }>;
