import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";
import { OptimisticConcurrencyConflict } from "@trainticket/ts-kit";
import { DomainError, WaitlistEntry } from "../src/domain.js";
import { PostgresWaitlistRepository, ProcessedEventRepository } from "../src/storage.js";

class FakeDb {
  public readonly calls: Array<{ sql: string; params: readonly unknown[] }> = [];

  constructor(private readonly rowCounts: readonly number[] = []) {}

  async query(sql: string, params: readonly unknown[] = []) {
    this.calls.push({ sql, params });
    return { rows: [], rowCount: this.rowCounts[this.calls.length - 1] ?? 0 };
  }
}

class UniqueViolationDb {
  async query(): Promise<never> {
    throw Object.assign(new Error("duplicate key value violates unique constraint"), { code: "23505" });
  }
}

function persistedEntry(version: number): WaitlistEntry {
  const entry = WaitlistEntry.create({
    accountId: "acc-1",
    travelerRefs: ["tvl-1"],
    segmentRef: "seg-1",
    departureDate: "2026-07-20",
    seatClass: "SECOND",
    priority: { groupSize: 1, fareClass: "SECOND" },
    createdAt: new Date("2026-01-01T00:00:00.000Z"),
  });
  entry.markPersisted(version);
  entry.cancel();
  return entry;
}

test("PostgresWaitlistRepository.save uses loaded version in OCC predicate", async () => {
  const db = new FakeDb([1, 0]);
  const repository = new PostgresWaitlistRepository(db as never);
  const entry = persistedEntry(3);

  await repository.save(entry);

  const update = db.calls[0];
  assert.match(update.sql, /WHERE entry_id=\$1 AND version=\$8/u);
  assert.equal(update.params[7], 3);
  assert.equal(entry.loadedVersion, 4);
});

test("PostgresWaitlistRepository.save rejects stale loaded version", async () => {
  const db = new FakeDb([0]);
  const repository = new PostgresWaitlistRepository(db as never);

  await assert.rejects(() => repository.save(persistedEntry(2)), OptimisticConcurrencyConflict);
});

test("PostgresWaitlistRepository finds archivable terminal rows", async () => {
  const db = new FakeDb();
  const repository = new PostgresWaitlistRepository(db as never);

  await repository.findArchivable();

  assert.match(db.calls[0].sql, /status IN \('FULFILLED', 'EXPIRED', 'CANCELLED'\)/u);
});

test("PostgresWaitlistRepository queue queries exclude CLOSED archival rows", async () => {
  const db = new FakeDb();
  const repository = new PostgresWaitlistRepository(db as never);

  await repository.queueFor("seg-1", "2026-07-20", "SECOND");

  assert.match(db.calls[0].sql, /status <> 'CLOSED'/u);
  assert.match(db.calls[0].sql, /departure_date = \$2/u);
});

test("PostgresWaitlistRepository locks and matches the complete released-capacity partition", async () => {
  const db = new FakeDb();
  const repository = new PostgresWaitlistRepository(db as never);

  await repository.findTopQueued({
    segmentRef: "seg-1",
    scheduledServiceRef: "ss-1",
    departureDate: "2026-07-20",
    seatClass: "SECOND_CLASS",
    interval: { fromSeq: 1, toSeq: 3 },
    stopSequenceVersion: "sha256:route-v1",
    maxTravelers: 2,
    intentFingerprint: "intent-1",
  });

  assert.match(db.calls[0].sql, /departure_date = \$2/u);
  assert.match(db.calls[0].sql, /seat_class = \$3/u);
  assert.match(db.calls[0].sql, /scheduledServiceRef/u);
  assert.match(db.calls[0].sql, /stopSequenceVersion/u);
  assert.match(db.calls[0].sql, /jsonb_array_length\(traveler_refs\) <= \$8/u);
  assert.match(db.calls[0].sql, /FOR UPDATE SKIP LOCKED/u);
  assert.deepEqual(db.calls[0].params, ["seg-1", "2026-07-20", "SECOND_CLASS", "ss-1", "sha256:route-v1", 1, 3, 2, "intent-1"]);
});

test("PostgresWaitlistRepository.add translates unique violation to conflict", async () => {
  const repository = new PostgresWaitlistRepository(new UniqueViolationDb() as never);
  const entry = WaitlistEntry.create({
    accountId: "acc-1",
    travelerRefs: ["tvl-1"],
    segmentRef: "seg-1",
    departureDate: "2026-07-20",
    seatClass: "SECOND",
    priority: { groupSize: 1, fareClass: "SECOND" },
    paymentGuaranteeRef: "pi-1",
    itineraryRef: "itn-1",
    intentFingerprint: "intent-1",
  });

  await assert.rejects(
    () => repository.add(entry),
    (error) => error instanceof DomainError && error.code === "CONFLICT",
  );
});

test("ProcessedEventRepository records by event_id primary key only", async () => {
  const first = new FakeDb([1]);
  assert.equal(await new ProcessedEventRepository(first as never).record("evt-1", "events:journey-order"), true);
  assert.match(first.calls[0].sql, /ON CONFLICT \(event_id\) DO NOTHING/u);

  const replay = new FakeDb([0]);
  assert.equal(await new ProcessedEventRepository(replay as never).record("evt-1", "events:capacity-availability"), false);
});

test("processed_events primary key change is applied by a follow-up migration", async () => {
  const migrationsDir = join(process.cwd(), "migrations");
  const initialMigration = await readFile(join(migrationsDir, "001_waitlist.sql"), "utf8");
  assert.match(initialMigration, /PRIMARY KEY \(event_id, stream\)/u);

  const migration = await readFile(join(migrationsDir, "002_processed_events_event_id_pk.sql"), "utf8");
  assert.match(migration, /DROP CONSTRAINT IF EXISTS processed_events_pkey/u);
  assert.match(migration, /DROP NOT NULL/u);
  assert.match(migration, /ADD PRIMARY KEY \(event_id\)/u);
});

test("precise waitlist matching has a partial queue index", async () => {
  const migration = await readFile(join(process.cwd(), "migrations/007_precise_match_index.sql"), "utf8");
  assert.match(migration, /waitlist_precise_match_idx/u);
  assert.match(migration, /scheduledServiceRef/u);
  assert.match(migration, /stopSequenceVersion/u);
  assert.match(migration, /WHERE status = 'QUEUED'/u);
});

test("capacity release credits use event identity and atomic row locks", async () => {
  const inserted = new FakeDb([1]);
  assert.equal(await new PostgresWaitlistRepository(inserted as never).recordCapacityCredit({
    segmentRef: "seg-1", scheduledServiceRef: "ss-1", departureDate: "2026-07-20", seatClass: "SECOND_CLASS",
    interval: { fromSeq: 0, toSeq: 2 }, stopSequenceVersion: "sha256:v1", freedSlots: 1,
    capacityReleaseRef: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c123",
  }), true);
  assert.match(inserted.calls[0].sql, /ON CONFLICT \(capacity_release_ref\) DO NOTHING/u);

  const claim = new FakeDb();
  await new PostgresWaitlistRepository(claim as never).claimNextPromotion(new Date("2026-01-01T00:00:00Z"));
  assert.match(claim.calls[0].sql, /SUM\(c\.remaining_slots\)/u);
  assert.match(claim.calls[0].sql, /jsonb_array_length\(e\.traveler_refs\)/u);
  assert.match(claim.calls[0].sql, /FOR UPDATE OF e SKIP LOCKED/u);

  const migration = await readFile(join(process.cwd(), "migrations/008_capacity_credit_ledger.sql"), "utf8");
  assert.match(migration, /capacity_release_ref text PRIMARY KEY/u);
  assert.match(migration, /remaining_slots integer NOT NULL/u);
});
