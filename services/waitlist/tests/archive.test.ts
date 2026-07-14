import assert from "node:assert/strict";
import test from "node:test";
import { InMemoryEventPublisher, uuidV7 } from "@trainticket/ts-kit";
import { createApp } from "../src/app.js";
import { InMemoryWaitlistRepository, WaitlistApplicationService, type JourneyOrderClient } from "../src/application.js";
import type { CapacityAvailabilityClient, FarePricingClient, OfferManagementClient } from "../src/promotion.js";
import { WaitlistEntry, type WaitlistEntrySnapshot } from "../src/domain.js";
import { PostgresWaitlistRepository } from "../src/storage.js";

class StubFarePricing implements FarePricingClient {
  async quote(entry: WaitlistEntry) { return { fareQuoteId: `fq-${entry.entryId}` }; }
}

class StubOfferManagement implements OfferManagementClient {
  async createOffer(entry: WaitlistEntry) { return { offerId: `off-${entry.entryId}`, offerVersion: 1 }; }
}

class StubCapacity implements CapacityAvailabilityClient {
  async hold(entry: WaitlistEntry) { return { capacityHoldId: `hold-${entry.entryId}` }; }
  async releaseHold() { return undefined; }
}

class StubJourneyOrder implements JourneyOrderClient {
  async createOrder(entry: WaitlistEntry) { return { orderId: `ord-${entry.entryId}`, seatAssignment: { segmentRef: entry.segmentRef } }; }
}

test("archival sweep removes terminal entries from active set and keeps archived snapshot retrievable", async () => {
  const repository = new InMemoryWaitlistRepository();
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(repository, publisher, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const entry = await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20 });
  await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });
  await service.accept(entry.entryId);

  const result = await service.sweepArchived();

  assert.equal(result.archived.length, 1);
  assert.equal(result.archived[0]?.entryId, entry.entryId);
  assert.equal(result.archived[0]?.status, "CLOSED");
  assert.equal(result.archived[0]?.terminalStatus, "ACCEPTED");
  await assert.rejects(() => service.get(entry.entryId), /not found/i);
  assert.equal((await service.getArchived(entry.entryId)).entryId, entry.entryId);
  assert.equal(publisher.findByEventType("WaitlistEntryArchived").length, 0);
});

test("archived entry is retrievable through contract waitlist request GET after on-demand sweep", async () => {
  const repository = new InMemoryWaitlistRepository();
  const app = createApp({ repository, farePricing: new StubFarePricing(), capacityAvailability: new StubCapacity(), journeyOrder: new StubJourneyOrder(), offerManagement: new StubOfferManagement(), now: () => new Date("2026-01-01T00:00:00.000Z") });
  const join = await app.inject({
    method: "POST",
    url: "/api/v1/waitlist/entries",
    headers: { "Idempotency-Key": uuidV7() },
    payload: { accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20 },
  });
  const entryId = join.json().entryId as string;
  const service = new WaitlistApplicationService(repository, undefined, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });
  await app.inject({ method: "POST", url: `/api/v1/waitlist/entries/${entryId}/accept`, headers: { "Idempotency-Key": uuidV7() }, payload: {} });

  const sweep = await app.inject({ method: "POST", url: "/internal/waitlist/archive-sweep", headers: { "Idempotency-Key": uuidV7() }, payload: { limit: 10 } });
  const active = await app.inject({ method: "GET", url: `/api/v1/waitlist/entries/${entryId}` });
  const archived = await app.inject({ method: "GET", url: `/api/v1/waitlist-requests/${entryId}` });
  const inventedArchivePath = await app.inject({ method: "GET", url: `/api/v1/waitlist/archived-entries/${entryId}` });

  assert.equal(sweep.statusCode, 200);
  assert.equal(sweep.json().archived.length, 1);
  assert.equal(active.statusCode, 404);
  assert.equal(inventedArchivePath.statusCode, 404);
  assert.equal(archived.statusCode, 200);
  assert.equal(archived.json().entryId, entryId);
  assert.equal(archived.json().status, "CLOSED");
  assert.equal(archived.json().terminalStatus, "ACCEPTED");
  await app.close();
});

test("postgres archive retry deletes active row when archive snapshot already exists", async () => {
  const entry = WaitlistEntry.create({
    entryId: "wl-retry",
    accountId: "acc",
    travelerRefs: ["t1"],
    segmentRef: "seg",
    departureDate: "2026-07-20",
    seatClass: "SECOND",
    priority: { groupSize: 1, fareClass: "SECOND" },
    createdAt: new Date("2026-01-01T00:00:00.000Z"),
  });
  entry.cancel();
  const archived = WaitlistEntry.fromSnapshot(entry.toSnapshot(0));
  archived.closeForArchive(new Date("2026-01-02T00:00:00.000Z"));
  const archivedSnapshot = archived.toSnapshot(0);
  const db = new ArchiveRetryDb(entry.toSnapshot(0), archivedSnapshot);
  const repository = new PostgresWaitlistRepository(db as never);

  const result = await repository.archive(entry, new Date("2026-01-02T00:00:00.000Z"));

  assert.equal(result?.entryId, entry.entryId);
  assert.equal(result?.status, "CLOSED");
  assert.equal(db.deletedEntryId, entry.entryId);
});

class ArchiveRetryDb {
  deletedEntryId?: string;

  constructor(
    private readonly active: WaitlistEntrySnapshot,
    private readonly archived: WaitlistEntrySnapshot,
  ) {}

  async query(sql: string, params: readonly unknown[]) {
    if (sql.includes("SELECT * FROM waitlist_entries")) {
      return { rows: [this.activeRow()], rowCount: 1 };
    }
    if (sql.includes("INSERT INTO archived_waitlist_entries")) {
      return { rows: [], rowCount: 0 };
    }
    if (sql.includes("SELECT data FROM archived_waitlist_entries")) {
      return { rows: [{ data: this.archived }], rowCount: 1 };
    }
    if (sql.includes("DELETE FROM waitlist_entries")) {
      this.deletedEntryId = String(params[0]);
      return { rows: [], rowCount: 1 };
    }
    throw new Error(`Unexpected query in archive retry test: ${sql}`);
  }

  private activeRow() {
    return {
      entry_id: this.active.entryId,
      account_id: this.active.accountId,
      traveler_refs: this.active.travelerRefs,
      segment_ref: this.active.segmentRef,
      departure_date: this.active.departureDate,
      seat_class: this.active.seatClass,
      priority_score: this.active.priorityScore,
      status: this.active.status,
      offered_at: this.active.offeredAt,
      offer_expires_at: this.active.offerExpiresAt,
      fare_quote_id: this.active.fareQuoteId ?? null,
      capacity_hold_id: this.active.capacityHoldId ?? null,
      data: this.active,
      version: 7,
      created_at: this.active.createdAt,
    };
  }
}
