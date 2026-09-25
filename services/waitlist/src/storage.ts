import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { OptimisticConcurrencyConflict, OutboxAppender, OutboxRelay, PostgresIdempotencyStore, connectRedisWithRetry, createPostgresPool, createRedisClient, migrationsAwareReadiness, redisClientLiveness, startMigrations, streamForProducer, withTransaction, type EventEnvelope, type SchemaDetail } from "@trainticket/ts-kit";
import type { Pool, PoolClient, QueryResult } from "pg";
import { DomainError, WaitlistEntry, normalizeFareClass, type TravelInterval, type WaitlistEntrySnapshot } from "./domain.js";
import type { WaitlistMatchCriteria, WaitlistRepository } from "./promotion.js";

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

  async recordCapacityCredit(event: import("./promotion.js").WaitlistCapacityFreed): Promise<boolean> {
    const result = await this.db.query(
      `INSERT INTO waitlist_capacity_credits (
         capacity_release_ref, segment_ref, scheduled_service_ref, departure_date, seat_class,
         from_seq, to_seq, stop_sequence_version, intent_fingerprint, released_slots, remaining_slots
       ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$10)
       ON CONFLICT (capacity_release_ref) DO NOTHING`,
      [event.capacityReleaseRef, event.segmentRef, event.scheduledServiceRef, event.departureDate,
        event.seatClass, event.interval.fromSeq, event.interval.toSeq, event.stopSequenceVersion,
        event.intentFingerprint ?? null, event.freedSlots],
    );
    if ((result.rowCount ?? 0) > 0) return true;
    const existing = await this.db.query(
      `SELECT segment_ref, scheduled_service_ref, departure_date, seat_class, from_seq, to_seq,
              stop_sequence_version, intent_fingerprint, released_slots
       FROM waitlist_capacity_credits WHERE capacity_release_ref=$1`, [event.capacityReleaseRef],
    ) as QueryResult<CapacityCreditRow>;
    if (!existing.rows[0] || !capacityCreditMatches(existing.rows[0], event)) {
      throw new DomainError("CONFLICT", "Capacity release reference was reused with different facts");
    }
    return false;
  }

  async claimNextPromotion(now: Date): Promise<WaitlistEntry | undefined> {
    const candidate = await this.db.query(
      `SELECT e.* FROM waitlist_entries e
       WHERE e.status='QUEUED'
         AND (e.data->>'deadline')::timestamptz > $1
         AND COALESCE((
           SELECT SUM(c.remaining_slots) FROM waitlist_capacity_credits c
           WHERE c.remaining_slots > 0
             AND c.segment_ref=e.segment_ref
             AND c.scheduled_service_ref=e.data->>'scheduledServiceRef'
             AND c.departure_date=e.departure_date
             AND c.seat_class=e.seat_class
             AND c.stop_sequence_version=e.data->>'stopSequenceVersion'
             AND c.from_seq <= (e.data->'interval'->>'fromSeq')::integer
             AND c.to_seq >= (e.data->'interval'->>'toSeq')::integer
             AND (c.intent_fingerprint IS NULL OR c.intent_fingerprint=e.data->>'intentFingerprint')
         ),0) >= jsonb_array_length(e.traveler_refs)
       ORDER BY e.priority_score DESC,e.created_at ASC,e.entry_id ASC
       LIMIT 1 FOR UPDATE OF e SKIP LOCKED`, [now.toISOString()],
    ) as QueryResult<WaitlistRow>;
    if (!candidate.rows[0]) return undefined;
    const entry = entryFromRow(candidate.rows[0]);
    const credits = await this.db.query(
      `SELECT * FROM waitlist_capacity_credits
       WHERE remaining_slots > 0
         AND segment_ref=$1 AND scheduled_service_ref=$2 AND departure_date=$3 AND seat_class=$4
         AND stop_sequence_version=$5 AND from_seq <= $6 AND to_seq >= $7
         AND (intent_fingerprint IS NULL OR intent_fingerprint=$8)
       ORDER BY created_at ASC,capacity_release_ref ASC
       FOR UPDATE`,
      [entry.segmentRef, entry.scheduledServiceRef, entry.departureDate, entry.seatClass,
        entry.stopSequenceVersion, entry.interval.fromSeq, entry.interval.toSeq, entry.intentFingerprint],
    ) as QueryResult<CapacityCreditRow>;
    let remaining = entry.travelerRefs.length;
    const allocations: Array<{ capacityReleaseRef: string; slots: number }> = [];
    for (const credit of credits.rows) {
      if (remaining === 0) break;
      const consumed = Math.min(Number(credit.remaining_slots), remaining);
      if (consumed <= 0) continue;
      await this.db.query(
        `UPDATE waitlist_capacity_credits SET remaining_slots=remaining_slots-$2
         WHERE capacity_release_ref=$1 AND remaining_slots >= $2`,
        [credit.capacity_release_ref, consumed],
      ).then((result) => {
        if ((result.rowCount ?? 0) !== 1) throw new OptimisticConcurrencyConflict(`Capacity credit ${credit.capacity_release_ref} changed while being claimed`);
      });
      remaining -= consumed;
      allocations.push({ capacityReleaseRef: credit.capacity_release_ref, slots: consumed });
    }
    if (remaining !== 0) throw new OptimisticConcurrencyConflict(`Capacity credits changed while claiming ${entry.entryId}`);
    entry.beginPromotion(allocations);
    return entry;
  }

  async restoreCapacityCredits(entry: WaitlistEntry): Promise<void> {
    for (const credit of entry.matchedCapacityCredits) {
      const result = await this.db.query(
        `UPDATE waitlist_capacity_credits SET remaining_slots=remaining_slots+$2
         WHERE capacity_release_ref=$1 AND remaining_slots+$2 <= released_slots`,
        [credit.capacityReleaseRef, credit.slots],
      );
      if ((result.rowCount ?? 0) !== 1) throw new OptimisticConcurrencyConflict(`Capacity credit ${credit.capacityReleaseRef} cannot be restored`);
    }
  }

  async findRecoverablePromotion(): Promise<WaitlistEntry | undefined> {
    const result = await this.db.query(
      `SELECT * FROM waitlist_entries
       WHERE status='MATCHING' AND data->>'journeyOrderRef' IS NULL
       ORDER BY updated_at ASC,entry_id ASC LIMIT 1
       FOR UPDATE SKIP LOCKED`,
    ) as QueryResult<WaitlistRow>;
    return result.rows[0] ? entryFromRow(result.rows[0]) : undefined;
  }

  async findTopQueued(criteria: WaitlistMatchCriteria): Promise<WaitlistEntry | undefined> {
    const result = await this.db.query(
      `SELECT * FROM waitlist_entries
       WHERE segment_ref = $1
         AND departure_date = $2
         AND seat_class = $3
         AND data->>'scheduledServiceRef' = $4
         AND data->>'stopSequenceVersion' = $5
         AND (data->'interval'->>'fromSeq')::integer >= $6
         AND (data->'interval'->>'toSeq')::integer <= $7
         AND jsonb_array_length(traveler_refs) <= $8
         AND ($9::text IS NULL OR data->>'intentFingerprint' = $9)
         AND status = 'QUEUED'
       ORDER BY priority_score DESC, created_at ASC, entry_id ASC
       LIMIT 1
       FOR UPDATE SKIP LOCKED`,
      [criteria.segmentRef, criteria.departureDate, criteria.seatClass, criteria.scheduledServiceRef,
        criteria.stopSequenceVersion, criteria.interval.fromSeq, criteria.interval.toSeq,
        criteria.maxTravelers, criteria.intentFingerprint ?? null],
    ) as QueryResult<WaitlistRow>;
    return result.rows[0] ? entryFromRow(result.rows[0]) : undefined;
  }

  async findExpiredOffers(now: Date): Promise<readonly WaitlistEntry[]> {
    const result = await this.db.query(
      `SELECT * FROM waitlist_entries
       WHERE (status = 'MATCHING' AND data->>'journeyOrderRef' IS NULL AND offer_expires_at <= $1)
          OR (status = 'QUEUED' AND (data->>'deadline')::timestamptz <= $1)
          OR (status = 'MATCHING' AND data->>'journeyOrderRef' IS NULL AND (data->>'deadline')::timestamptz <= $1)
       ORDER BY COALESCE(offer_expires_at, (data->>'deadline')::timestamptz) ASC, entry_id ASC
       FOR UPDATE SKIP LOCKED`,
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
    if (seatClass) params.push(normalizeFareClass(seatClass));
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
  const interval = intervalFrom(data.interval) ?? { fromSeq: 0, toSeq: 1 };
  const scheduledServiceRef = typeof data.scheduledServiceRef === "string" && data.scheduledServiceRef.trim()
    ? data.scheduledServiceRef : row.segment_ref;
  const stopSequenceVersion = typeof data.stopSequenceVersion === "string" && data.stopSequenceVersion.trim()
    ? data.stopSequenceVersion : `legacy:${row.segment_ref}`;
  const entry = WaitlistEntry.fromSnapshot({
    entryId: row.entry_id,
    accountId: row.account_id,
    travelerRefs: Array.isArray(row.traveler_refs) ? row.traveler_refs.map(String) : [],
    segmentRef: row.segment_ref,
    scheduledServiceRef,
    departureDate: dateString(row.departure_date),
    seatClass: normalizeFareClass(row.seat_class),
    interval,
    stopSequenceVersion,
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
    paymentGuaranteeRef: typeof data.paymentGuaranteeRef === "string" ? data.paymentGuaranteeRef : `pi-legacy-${row.entry_id}`,
    paymentGuarantee: paymentGuaranteeFrom(data.paymentGuarantee, row),
    intentFingerprint: typeof data.intentFingerprint === "string" ? data.intentFingerprint : defaultIntentFingerprint(row.traveler_refs, row.segment_ref, dateString(row.departure_date), row.seat_class),
    matchedCapacityCredits: capacityCreditAllocations(data.matchedCapacityCredits),
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
function intervalFrom(value: unknown): TravelInterval | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
  const body = value as Record<string, unknown>;
  const fromSeq = Number(body.fromSeq);
  const toSeq = Number(body.toSeq);
  return Number.isSafeInteger(fromSeq) && Number.isSafeInteger(toSeq) && fromSeq >= 0 && toSeq > fromSeq
    ? { fromSeq, toSeq } : undefined;
}
function paymentGuaranteeFrom(value: unknown, row: WaitlistRow): WaitlistEntrySnapshot["paymentGuarantee"] {
  if (value && typeof value === "object" && !Array.isArray(value)) {
    const guarantee = value as Record<string, unknown>;
    const amount = guarantee.amount;
    if (amount && typeof amount === "object" && !Array.isArray(amount)) {
      const money = amount as Record<string, unknown>;
      const sourceStatus = guarantee.sourceStatus;
      if (typeof guarantee.paymentIntentId === "string" && typeof guarantee.sourceOrderRef === "string"
          && typeof guarantee.payerRef === "string" && typeof money.currency === "string"
          && Number.isSafeInteger(Number(money.minorUnits)) && (sourceStatus === "CREATED" || sourceStatus === "AUTHORIZED")
          && typeof guarantee.sourceExpiresAt === "string" && typeof guarantee.authorizedUntil === "string"
          && typeof guarantee.verifiedAt === "string") {
        return {
          paymentIntentId: guarantee.paymentIntentId,
          sourceOrderRef: guarantee.sourceOrderRef,
          payerRef: guarantee.payerRef,
          amount: { currency: money.currency, minorUnits: Number(money.minorUnits) },
          sourceStatus,
          sourceExpiresAt: guarantee.sourceExpiresAt,
          authorizedUntil: guarantee.authorizedUntil,
          verifiedAt: guarantee.verifiedAt,
          preferredChannel: typeof guarantee.preferredChannel === "string" ? guarantee.preferredChannel : undefined,
        };
      }
    }
  }
  return {
    paymentIntentId: typeof row.data?.paymentGuaranteeRef === "string" ? row.data.paymentGuaranteeRef : `legacy:${row.entry_id}`,
    sourceOrderRef: `legacy:${row.entry_id}`,
    payerRef: row.account_id,
    amount: { currency: "CNY", minorUnits: 0 },
    sourceStatus: "CREATED",
    sourceExpiresAt: instantString(row.created_at),
    authorizedUntil: instantString(row.created_at),
    verifiedAt: instantString(row.created_at),
  };
}
function capacityCreditAllocations(value: unknown): readonly { capacityReleaseRef: string; slots: number }[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!item || typeof item !== "object" || Array.isArray(item)) return [];
    const credit = item as Record<string, unknown>;
    return typeof credit.capacityReleaseRef === "string" && Number.isSafeInteger(Number(credit.slots)) && Number(credit.slots) > 0
      ? [{ capacityReleaseRef: credit.capacityReleaseRef, slots: Number(credit.slots) }] : [];
  });
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

type CapacityCreditRow = Readonly<{
  capacity_release_ref: string;
  segment_ref: string;
  scheduled_service_ref: string;
  departure_date: Date | string;
  seat_class: string;
  from_seq: string | number;
  to_seq: string | number;
  stop_sequence_version: string;
  intent_fingerprint: string | null;
  released_slots: string | number;
  remaining_slots: string | number;
}>;

function capacityCreditMatches(row: CapacityCreditRow, event: import("./promotion.js").WaitlistCapacityFreed): boolean {
  return row.segment_ref === event.segmentRef
    && row.scheduled_service_ref === event.scheduledServiceRef
    && dateString(row.departure_date) === event.departureDate
    && row.seat_class === event.seatClass
    && Number(row.from_seq) === event.interval.fromSeq
    && Number(row.to_seq) === event.interval.toSeq
    && row.stop_sequence_version === event.stopSequenceVersion
    && (row.intent_fingerprint ?? undefined) === event.intentFingerprint
    && Number(row.released_slots) === event.freedSlots;
}
