import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { test } from "node:test";

import { Redis } from "ioredis";
import { Pool } from "pg";

import { createEventEnvelope } from "./messaging.js";
import { DurableEventReplayWorker } from "./replay.js";
import { ProcessedEventsGuard, withTransaction } from "./storage.js";

test("deleted Redis transport is rebuilt from the durable source without duplicate effects after restart", {
  skip: !process.env.TEST_DATABASE_URL || !process.env.TEST_REDIS_URL,
}, async () => {
  const schema = `replay_transport_${randomUUID().replaceAll("-", "")}`;
  const stream = `events:payment:${schema}`;
  const admin = new Pool({ connectionString: process.env.TEST_DATABASE_URL });
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, options: `-c search_path=${schema}` });
  const redis = new Redis(process.env.TEST_REDIS_URL!);
  const envelope = createEventEnvelope({
    eventType: "PaymentCaptured",
    producer: "payment",
    payload: { paymentIntentId: "pi-replay-1", businessRef: "ord-replay-1" },
  });
  const archived = {
    producer: "payment", afterSequence: 0, oldestAvailableSequence: 1, latestAvailableSequence: 1,
    retainedFrom: "2026-09-25T00:00:00.000Z", hasMore: false,
    events: [{
      sequence: 1, eventId: envelope.eventId, stream: "events:payment",
      envelope: JSON.stringify(envelope), archivedAt: "2026-09-25T00:00:00.000Z",
      retainedUntil: "2026-10-02T00:00:00.000Z",
    }],
  };
  const server = createServer((request, response) => {
    const after = Number(new URL(request.url!, "http://localhost").searchParams.get("after") ?? "0");
    const body = after >= 1 ? { ...archived, afterSequence: after, events: [] } : { ...archived, afterSequence: after };
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify(body));
  });
  await new Promise<void>((resolve, reject) => server.listen(0, "127.0.0.1", resolve).once("error", reject));
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("replay source did not bind a TCP port");
  const source = { name: "payment", producer: "payment", endpoint: `http://127.0.0.1:${address.port}/api/v1/internal/event-replay` };
  try {
    await admin.query(`CREATE SCHEMA ${schema}`);
    await pool.query(`
      CREATE TABLE processed_events(event_id text PRIMARY KEY,stream text,processed_at timestamptz NOT NULL DEFAULT now());
      CREATE TABLE business_effects(event_id text PRIMARY KEY,created_at timestamptz NOT NULL DEFAULT now());
      CREATE TABLE event_dead_letters(
        consumer_group text NOT NULL,source_stream text NOT NULL,evidence_key text NOT NULL,
        source_message_id text NOT NULL,consumer_name text NOT NULL,event_id text,event_type text,producer text,
        original_envelope text,first_failure_kind text NOT NULL,first_failure_reason text NOT NULL,
        failure_kind text NOT NULL,failure_reason text NOT NULL,attempts integer NOT NULL,
        first_dead_lettered_at timestamptz NOT NULL,last_dead_lettered_at timestamptz NOT NULL,
        recovery_status text NOT NULL,recovery_source text,recovery_sequence bigint,
        recovery_started_at timestamptz,recovery_finished_at timestamptz,recovery_error text,
        PRIMARY KEY(consumer_group,source_stream,evidence_key));
      CREATE TABLE event_replay_checkpoints(
        consumer_group text NOT NULL,source_name text NOT NULL,source_endpoint text NOT NULL,
        after_sequence bigint NOT NULL DEFAULT 0,status text NOT NULL,last_event_id text,last_attempt_at timestamptz,
        last_success_at timestamptz,last_error text,created_at timestamptz NOT NULL DEFAULT now(),
        updated_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(consumer_group,source_name));
      CREATE SEQUENCE checkpoint_failure_seq;
      CREATE FUNCTION fail_first_checkpoint() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN
        IF nextval('checkpoint_failure_seq')=1 THEN RAISE EXCEPTION 'simulated checkpoint crash'; END IF;
        RETURN NEW;
      END $$;
      CREATE TRIGGER fail_first_checkpoint BEFORE UPDATE OF after_sequence ON event_replay_checkpoints
        FOR EACH ROW WHEN (NEW.after_sequence>OLD.after_sequence) EXECUTE FUNCTION fail_first_checkpoint();
    `);

    // The transport had the event and then lost the whole stream. Recovery must not read Redis.
    await redis.xadd(stream, "*", "envelope", JSON.stringify(envelope));
    assert.equal(await redis.xlen(stream), 1);
    await redis.del(stream);
    assert.equal(await redis.exists(stream), 0);

    const handler = async () => withTransaction(pool, async client => {
      if (!await new ProcessedEventsGuard(client).tryStart(envelope.eventId, "events:payment")) return { ok: true as const };
      await client.query("INSERT INTO business_effects(event_id) VALUES($1)", [envelope.eventId]);
      return { ok: true as const };
    });
    const first = new DurableEventReplayWorker(pool, [source], handler, { consumerGroup: "loyalty-membership" });
    await assert.rejects(first.runSourceOnce(source), /simulated checkpoint crash/u);
    assert.equal((await pool.query("SELECT count(*) FROM business_effects")).rows[0].count, "1");
    assert.equal((await pool.query("SELECT after_sequence FROM event_replay_checkpoints")).rows[0].after_sequence, "0");

    // New process, same durable cursor: event is fetched again but the business mutation is deduplicated.
    const restarted = new DurableEventReplayWorker(pool, [source], handler, { consumerGroup: "loyalty-membership" });
    assert.equal(await restarted.runSourceOnce(source), 1);
    assert.equal((await pool.query("SELECT count(*) FROM business_effects")).rows[0].count, "1");
    assert.equal((await pool.query("SELECT count(*) FROM processed_events")).rows[0].count, "1");
    assert.equal((await pool.query("SELECT after_sequence FROM event_replay_checkpoints")).rows[0].after_sequence, "1");
  } finally {
    await redis.del(stream);
    redis.disconnect();
    await new Promise<void>(resolve => server.close(() => resolve()));
    await pool.end();
    await admin.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
    await admin.end();
  }
});
