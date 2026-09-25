import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { test } from "node:test";

import { Pool } from "pg";

import { createEventEnvelope, type DeadLetterEvidence } from "./messaging.js";
import { PostgresDeadLetterEvidenceStore } from "./storage.js";

test("dead-letter evidence survives restart and preserves the original failure", {
  skip: !process.env.TEST_DATABASE_URL,
}, async () => {
  const schema = `dead_letter_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: process.env.TEST_DATABASE_URL });
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL, options: `-c search_path=${schema}` });
  try {
    await pool.query(`CREATE TABLE event_dead_letters (
      consumer_group text NOT NULL, source_stream text NOT NULL, evidence_key text NOT NULL,
      source_message_id text NOT NULL, consumer_name text NOT NULL, event_id text, event_type text,
      producer text, original_envelope text,
      first_failure_kind text NOT NULL, first_failure_reason text NOT NULL,
      failure_kind text NOT NULL, failure_reason text NOT NULL, attempts integer NOT NULL,
      first_dead_lettered_at timestamptz NOT NULL, last_dead_lettered_at timestamptz NOT NULL,
      recovery_status text NOT NULL, recovery_source text, recovery_sequence bigint,
      recovery_started_at timestamptz, recovery_finished_at timestamptz, recovery_error text,
      PRIMARY KEY(consumer_group,source_stream,evidence_key))`);
    const envelope = createEventEnvelope({ eventType: "RefundSettled", producer: "payment", payload: { refundId: "ref-1" } });
    const originalEnvelope = JSON.stringify(envelope);
    const first: DeadLetterEvidence = {
      sourceStream: "events:payment", sourceMessageId: "1-0", consumerGroup: "loyalty-membership",
      consumerName: "loyalty-a", eventId: envelope.eventId, eventType: envelope.eventType,
      producer: envelope.producer, originalEnvelope, failureKind: "FATAL_HANDLER",
      failureReason: "currency mismatch", attempts: 1, deadLetteredAt: "2026-09-25T04:00:00.000Z",
    };
    await new PostgresDeadLetterEvidenceStore(pool).recordDeadLetter(first);

    // Simulate a process restart by constructing a fresh store and observing a later redelivery.
    await new PostgresDeadLetterEvidenceStore(pool).recordDeadLetter({
      ...first, sourceMessageId: "9-0", consumerName: "loyalty-b",
      failureKind: "RETRY_EXHAUSTED", failureReason: "dependency remained unavailable",
      attempts: 5, deadLetteredAt: "2026-09-25T04:05:00.000Z",
    });
    const row = (await pool.query("SELECT * FROM event_dead_letters")).rows[0];
    assert.equal(row.original_envelope, originalEnvelope);
    assert.equal(row.first_failure_kind, "FATAL_HANDLER");
    assert.equal(row.first_failure_reason, "currency mismatch");
    assert.equal(row.failure_kind, "RETRY_EXHAUSTED");
    assert.equal(row.failure_reason, "dependency remained unavailable");
    assert.equal(row.attempts, 5);
    assert.equal(row.recovery_status, "OPEN");

    await assert.rejects(new PostgresDeadLetterEvidenceStore(pool).recordDeadLetter({
      ...first, originalEnvelope: JSON.stringify({ ...envelope, payload: { refundId: "ref-tampered" } }),
    }), /identity changed/u);
    assert.equal((await pool.query("SELECT count(*) FROM event_dead_letters")).rows[0].count, "1");
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA ${schema} CASCADE`);
    await admin.end();
  }
});
