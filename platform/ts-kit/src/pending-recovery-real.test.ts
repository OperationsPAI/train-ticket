import assert from "node:assert/strict";
import { test } from "node:test";
import { randomUUID } from "node:crypto";
import { Redis } from "ioredis";
import { RedisStreamEventSubscriber, createEventEnvelope, type EventHandler } from "./messaging.js";

test("pending recovery advances through active entries to reach idle work", { skip: !process.env.TEST_REDIS_URL }, async () => {
  const redis = new Redis(process.env.TEST_REDIS_URL!);
  const stream = `test:pending-cursor:${randomUUID()}`;
  const group = "recovery-test";
  const subscriber = new RedisStreamEventSubscriber(redis);
  try {
    await redis.xgroup("CREATE", stream, group, "0", "MKSTREAM");
    const pipe = redis.pipeline();
    for (let index = 0; index < 1200; index++) {
      pipe.xadd(stream, "*", "envelope", JSON.stringify(createEventEnvelope({ eventType: "RecoveryChecked", producer: "test", payload: { index } })));
    }
    await pipe.exec();
    const read = await redis.xreadgroup("GROUP", group, "previous", "COUNT", 1200, "STREAMS", stream, ">") as [string, [string, string[]][]][];
    const last = read[0][1].at(-1)![0];
    await redis.xclaim(stream, group, "previous", 0, last, "IDLE", 61_000);
    const observed: number[] = [];
    const handler: EventHandler = async event => { observed.push(event.payload.index as number); return { ok: true }; };
    const recovery = subscriber as unknown as { claimAndProcess: (stream: string, group: string, consumer: string, handler: EventHandler) => Promise<void> };
    await recovery.claimAndProcess(stream, group, "current", handler);
    assert.equal(observed.length, 0);
    await recovery.claimAndProcess(stream, group, "current", handler);
    assert.deepEqual(observed, [1199]);
    const summary = await redis.xpending(stream, group) as [number, unknown, unknown, unknown];
    assert.equal(summary[0], 1199);
  } finally {
    await redis.del(stream);
    await subscriber.close();
  }
});
