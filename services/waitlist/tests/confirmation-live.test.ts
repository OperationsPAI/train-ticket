import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { Pool } from "pg";
import { OutboxAppender, withTransaction } from "@trainticket/ts-kit";
import { WaitlistApplicationService } from "../src/application.js";
import { WaitlistEntry } from "../src/domain.js";
import { PostgresWaitlistRepository, TransactionalOutboxPublisher } from "../src/storage.js";
import { createWaitlistEventHandler } from "../src/subscriber.js";

test("recorded order facts control waitlist fulfillment and cancellation with durable replay", {
  skip: !process.env.TEST_DATABASE_URL || !process.env.TEST_WAITLIST_CONFIRMATION_SOURCE,
}, async () => {
  const source = JSON.parse(await readFile(process.env.TEST_WAITLIST_CONFIRMATION_SOURCE!, "utf8"));
  assert.equal(source.event.eventType, "JourneyOrderConfirmed");
  assert.equal(source.event.payload.orderId, source.order.orderId);
  assert.equal(source.cancelled.status, "CANCELLED");
  const schema = `waitlist_confirmation_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 1 });
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 4, options: `-c search_path=${schema}` });
  const repository = new PostgresWaitlistRepository(pool);
  const apply = (action: (service: WaitlistApplicationService) => Promise<unknown>) => withTransaction(pool, async client => {
    return action(new WaitlistApplicationService(new PostgresWaitlistRepository(client), new TransactionalOutboxPublisher(new OutboxAppender(client))));
  });
  try {
    await pool.query(await readFile("migrations/001_waitlist.sql", "utf8"));
    await pool.query(await readFile("migrations/006_order_reference_lookup.sql", "utf8"));
    const now = new Date();
    const entry = WaitlistEntry.create({ accountId: source.order.accountId, travelerRefs: source.order.travelerRefs,
      segmentRef: source.order.segmentRefs[0], itineraryRef: source.offer.itineraryRef,
      departureDate: source.order.segments[0].departureTime.slice(0, 10), seatClass: "SECOND_CLASS", priority: { tripCount: 0 } });
    await repository.add(entry);
    entry.offer(source.offer.offerId, source.offer.offerVersion, source.offer.fareQuoteRef, undefined, now, new Date(now.getTime() + 900_000));
    entry.linkJourneyOrder(now, source.order.orderId);
    await repository.save(entry);
    assert.equal((await repository.get(entry.entryId))?.status, "MATCHING");
    assert.equal((await pool.query("SELECT count(*) FROM outbox")).rows[0].count, "0");
    const afterExpiry = new Date(now.getTime() + 3_600_000);
    assert.equal((await repository.findExpiredOffers(afterExpiry)).length, 0);
    await pool.query("UPDATE waitlist_entries SET offer_expires_at=now()-interval '1 minute' WHERE entry_id=$1", [entry.entryId]);
    const locked = await pool.connect();
    try {
      await locked.query("BEGIN");
      await locked.query("SELECT entry_id FROM waitlist_entries WHERE entry_id=$1 FOR UPDATE", [entry.entryId]);
      const application = new WaitlistApplicationService(repository, new TransactionalOutboxPublisher(new OutboxAppender(pool)));
      const handler = createWaitlistEventHandler({
        handleCapacityFreed: application.handleCapacityFreed.bind(application),
        handleJourneyOrderCancelled: application.handleJourneyOrderCancelled.bind(application),
        handleJourneyOrderConfirmed: orderId => withTransaction(pool, async client => {
          await client.query("SET LOCAL lock_timeout='100ms'");
          return new WaitlistApplicationService(new PostgresWaitlistRepository(client), new TransactionalOutboxPublisher(new OutboxAppender(client)))
            .handleJourneyOrderConfirmed(orderId);
        }),
      });
      const result = await handler(source.event);
      assert.equal(result.ok, false);
      if (result.ok) throw new Error("locked confirmation must remain retryable");
      assert.equal(result.kind, "transient");
      assert.equal((await pool.query("SELECT count(*) FROM outbox")).rows[0].count, "0");
    } finally {
      await locked.query("ROLLBACK");
      locked.release();
    }
    await apply(service => service.handleJourneyOrderConfirmed(source.event.payload.orderId, source.event.correlationId));
    assert.equal((await repository.get(entry.entryId))?.status, "FULFILLED");
    const event = (await pool.query("SELECT envelope FROM outbox WHERE envelope->>'eventType'='WaitlistFulfilled'")).rows;
    assert.equal(event.length, 1);
    assert.equal(event[0].envelope.payload.journeyOrderRef, source.order.orderId);
    await Promise.all(Array.from({ length: 4 }, () => apply(service => service.handleJourneyOrderConfirmed(source.order.orderId))));
    assert.equal((await pool.query("SELECT count(*) FROM outbox")).rows[0].count, "1");
    await apply(service => service.archiveTerminalRequests());
    await apply(service => service.handleJourneyOrderConfirmed(source.order.orderId));
    assert.equal((await repository.get(entry.entryId))?.status, "CLOSED");
    assert.equal((await pool.query("SELECT count(*) FROM outbox")).rows[0].count, "1");

    const pending = WaitlistEntry.create({ accountId: source.cancelled.accountId, travelerRefs: source.cancelled.travelerRefs,
      segmentRef: source.cancelled.segmentRefs[0], itineraryRef: source.cancelledOffer.itineraryRef,
      departureDate: source.cancelled.segments[0].departureTime.slice(0, 10), seatClass: "SECOND_CLASS", priority: { tripCount: 0 } });
    await repository.add(pending);
    pending.offer(source.cancelledOffer.offerId, source.cancelledOffer.offerVersion, source.cancelledOffer.fareQuoteRef, undefined, now, afterExpiry);
    pending.linkJourneyOrder(now, source.cancelled.orderId);
    await repository.save(pending);
    const keys = [pending.fareQuoteIdempotencyKey, pending.offerIdempotencyKey, pending.journeyOrderIdempotencyKey];
    await apply(service => service.handleJourneyOrderCancelled(source.cancelled.orderId));
    const queued = (await repository.get(pending.entryId))!;
    assert.equal(queued.status, "QUEUED");
    assert.equal(queued.journeyOrderRef, undefined);
    assert.equal(queued.offerId, undefined);
    [queued.fareQuoteIdempotencyKey, queued.offerIdempotencyKey, queued.journeyOrderIdempotencyKey]
      .forEach((key, index) => assert.notEqual(key, keys[index]));
    await apply(service => service.handleJourneyOrderCancelled(source.cancelled.orderId));
    assert.equal((await pool.query("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='WaitlistQueued'")).rows[0].count, "1");
    queued.offer(source.cancelledOffer.offerId, source.cancelledOffer.offerVersion, source.cancelledOffer.fareQuoteRef, undefined, now, afterExpiry);
    queued.linkJourneyOrder(now, source.cancelled.orderId);
    await repository.save(queued);
    await pool.query("UPDATE waitlist_entries SET data=jsonb_set(data,'{deadline}',to_jsonb((now()-interval '1 minute')::text)) WHERE entry_id=$1", [queued.entryId]);
    await apply(service => service.handleJourneyOrderCancelled(source.cancelled.orderId));
    assert.equal((await repository.get(queued.entryId))?.status, "EXPIRED");
    assert.equal((await pool.query("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='WaitlistExpired'")).rows[0].count, "1");
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA ${schema} CASCADE`);
    await admin.end();
  }
});
