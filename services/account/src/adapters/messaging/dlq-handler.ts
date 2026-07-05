import type { Redis } from "ioredis";
import { dlqForStream } from "@trainticket/ts-kit";

import { maxStreamLength } from "./stream-config.js";

export async function moveToDlq(redis: Redis, stream: string, serializedEnvelope: string): Promise<void> {
  await redis.xadd(dlqForStream(stream), "MAXLEN", "~", maxStreamLength, "*", "envelope", serializedEnvelope);
}
