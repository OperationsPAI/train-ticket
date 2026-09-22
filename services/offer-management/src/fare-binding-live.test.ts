import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { test } from "node:test";
import { Pool } from "pg";
import { withTransaction, OutboxAppender, type EventEnvelope } from "@trainticket/ts-kit";
import { applyUpstreamEvents, buildQuoteOfferCommand, type QuoteOfferRequest } from "./application/upstream-state.js";
import { PostgresUpstreamStateRepository } from "./adapters/storage/upstream-state-repository.js";
import { PostgresOfferRepository } from "./adapters/storage/offer-repository.js";
import { OfferApplicationService } from "./application/offers.js";
import { migrationsDirectory } from "./adapters/storage/runtime.js";
import { createApp } from "./app.js";

test("offers use the exact recorded fare across delayed and reversed event delivery", {
  skip: !process.env.TEST_DATABASE_URL || !process.env.TEST_OFFER_BINDING_SAMPLE,
}, async () => {
  const source = JSON.parse(await readFile(process.env.TEST_OFFER_BINDING_SAMPLE!, "utf8"));
  const schema = "offer_fare_" + randomUUID().replaceAll("-", "");
  const admin = new Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 1 });
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, options: `-c search_path=${schema}`, max: 4 });
  try {
    for (const name of (await readdir(migrationsDirectory())).filter(name => name.endsWith(".sql") && !name.startsWith("005_")).sort()) await pool.query(await readFile(`${migrationsDirectory()}/${name}`, "utf8"));
    await pool.query("INSERT INTO offer_upstream_itineraries(id,version,data) VALUES ($1,1,$2)", [source.itinerary.itineraryRef, source.itinerary]);
    for (const traveler of source.travelers) await pool.query("INSERT INTO offer_upstream_travelers(id,version,data) VALUES ($1,1,$2)", [traveler.travelerId, traveler]);
    const apply = (events: EventEnvelope[]) => withTransaction(pool, client => applyUpstreamEvents(new PostgresUpstreamStateRepository(client), events));
    const quote = (request: QuoteOfferRequest) => withTransaction(pool, async client => {
      const repository = new PostgresUpstreamStateRepository(client);
      const publisher = { publish: async (envelope: EventEnvelope) => { await new OutboxAppender(client).append(envelope); } };
      return new OfferApplicationService(new PostgresOfferRepository(client), publisher, body => buildQuoteOfferCommand(repository, body)).quoteOffer(request, source.events[1].correlationId);
    });
    await apply([source.events[0]]);
    await pool.query("UPDATE offer_upstream_fare_quotes SET id=data->>'inputHash'");
    const migration = await readFile(`${migrationsDirectory()}/005_fare_quote_identity.sql`, "utf8");
    await pool.query(migration);
    await pool.query(migration);
    assert.equal((await pool.query("SELECT count(*) FROM offer_upstream_fare_quotes WHERE id=$1", [source.quotes[0].quoteId])).rows[0].count, "1");
    await assert.rejects(quote(source.request), /has not been consumed/);
    assert.equal((await pool.query("SELECT count(*) FROM offer_snapshots")).rows[0].count, "0");
    await apply([source.events[1], source.events[0]]);
    await apply([source.events[0]]);
    assert.equal((await pool.query("SELECT count(*) FROM offer_upstream_fare_quotes WHERE id=data->>'quoteId'")).rows[0].count, "2");
    for (const fare of source.quotes) {
      const result = await quote({ ...source.request, fareQuoteRef: fare.quoteId });
      assert.deepEqual(result.response.total, fare.breakdown.total);
      assert.equal(result.response.fareQuoteRef, fare.quoteId);
      const stored = (await pool.query("SELECT data FROM offer_snapshots WHERE id=$1", [result.response.offerId])).rows[0].data;
      assert.equal(stored.priceSnapshot.fareQuoteRef, fare.quoteId);
      assert.ok(stored.items.every((item: any) => item.fareSnapshot.fareQuoteRef === fare.quoteId));
      assert.ok(new Date(result.response.expiresAt).getTime() <= new Date(fare.validUntil).getTime());
    }
    await assert.rejects(quote({ ...source.request, channelId: "unmatched-channel" }), /does not match/);
    await assert.rejects(quote({ ...source.request, travelerRefs: [source.request.accountId] }), /does not match/);
    await pool.query("UPDATE offer_upstream_itineraries SET data=jsonb_set(data,'{segmentRefs}', '[]'::jsonb)");
    await assert.rejects(quote(source.request), /does not match/);
    await pool.query("UPDATE offer_upstream_itineraries SET data=$1", [source.itinerary]);
    await pool.query("UPDATE offer_upstream_fare_quotes SET data=jsonb_set(data,'{validUntil}',to_jsonb((now()-interval '1 second')::text)) WHERE id=$1", [source.request.fareQuoteRef]);
    await assert.rejects(quote(source.request), /validity window/);
    const app = createApp();
    try {
      const { fareQuoteRef, ...missing } = source.request;
      const response = await app.inject({ method: "POST", url: "/api/v1/offers", headers: { "idempotency-key": source.events[0].correlationId.replace(/^corr-/, "") }, body: missing });
      assert.equal(response.statusCode, 400);
      assert.match(response.json().message, /fareQuoteRef/);
    } finally { await app.close(); }
  } finally { await pool.end(); await admin.query(`DROP SCHEMA ${schema} CASCADE`); await admin.end(); }
});
