import type { Redis } from "ioredis";

import { maxStreamLength } from "./stream-config.js";

export async function moveToDlq(redis: Redis, stream: string, serializedEnvelope: string): Promise<void> {
  await redis.xadd(`${stream}:dlq`, "MAXLEN", "~", maxStreamLength, "*", "envelope", serializedEnvelope);
}
