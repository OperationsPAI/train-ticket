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

test("PostgresWaitlistRepository.add translates unique violation to conflict", async () => {
  const repository = new PostgresWaitlistRepository(new UniqueViolationDb() as never);
  const entry = WaitlistEntry.create({
    accountId: "acc-1",
    travelerRefs: ["tvl-1"],
    segmentRef: "seg-1",
    departureDate: "2026-07-20",
    seatClass: "SECOND",
    priority: { groupSize: 1, fareClass: "SECOND" },
    paymentGuaranteeRef: "pay-auth-1",
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
