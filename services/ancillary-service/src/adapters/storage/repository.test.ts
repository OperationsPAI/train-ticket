import { test } from "node:test";
import assert from "node:assert/strict";

import { save } from "./repository.js";
import { type SnapshotRepository } from "@trainticket/ts-kit";

function fakeRepo(stored?: { version: bigint }) {
  const calls: Array<{ id: string; expected: bigint | undefined }> = [];
  const repo = {
    async get() {
      return stored ? { id: "x", version: stored.version, data: { any: true } } : undefined;
    },
    async save(id: string, _snapshot: unknown, expected: bigint | undefined) {
      calls.push({ id, expected });
    },
  } as unknown as SnapshotRepository<unknown>;
  return { repo, calls };
}

test("fresh aggregate inserts without an expected version", async () => {
  const { repo, calls } = fakeRepo(undefined);
  await save(repo, "a", { v: 1 }, 1);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].expected, undefined);
});

test("replayed command is a harmless no-op when stored version is not older", async () => {
  const { repo, calls } = fakeRepo({ version: 3n });
  await save(repo, "a", { v: 3 }, 3);
  await save(repo, "a", { v: 2 }, 2);
  assert.equal(calls.length, 0);
});

test("advance guards on the stored version, not the derived one", async () => {
  const { repo, calls } = fakeRepo({ version: 3n });
  await save(repo, "a", { v: 4 }, 4);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].expected, 3n);
});
