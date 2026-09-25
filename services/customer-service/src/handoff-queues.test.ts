import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";

import { CASE_OWNER_QUEUES_SETTING, deskRunsQueue, statedOwnerQueues } from "./handoff-queues.js";

/**
 * The handoff guard's queue check.
 *
 * The statement belongs to a release rather than to the service, so the cases here pass it in
 * explicitly and the process is left as it was found: a test that names a queue must not decide
 * what the next test's handoff does.
 */
describe("a handoff lands only in a queue the release names", () => {
  const previous = process.env[CASE_OWNER_QUEUES_SETTING];

  afterEach(() => {
    if (previous === undefined) {
      delete process.env[CASE_OWNER_QUEUES_SETTING];
    } else {
      process.env[CASE_OWNER_QUEUES_SETTING] = previous;
    }
  });

  it("takes the queue the caller names when the release names none", () => {
    assert.deepEqual(statedOwnerQueues(""), []);
    assert.equal(deskRunsQueue("tier1", ""), true);
    assert.equal(deskRunsQueue("tier1", " , "), true);
  });

  it("takes a queue the release names, with the spaces a statement carries", () => {
    assert.equal(deskRunsQueue("post-sales-desk", "post-sales-desk, ticketing-desk"), true);
    assert.equal(deskRunsQueue("ticketing-desk", "post-sales-desk,ticketing-desk"), true);
  });

  it("refuses a queue the release does not name", () => {
    assert.equal(deskRunsQueue("tier1", "post-sales-desk,ticketing-desk"), false);
  });

  it("reads the statement the process carries when none is passed in", () => {
    process.env[CASE_OWNER_QUEUES_SETTING] = "post-sales-desk";
    assert.equal(deskRunsQueue("post-sales-desk"), true);
    assert.equal(deskRunsQueue("tier1"), false);
  });
});
