import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";

import { DESK_QUEUES_SETTING, deskPollsQueue } from "./desk-queues.js";

/**
 * The handoff counter's queue check.
 *
 * The statement belongs to a release rather than to the service, so the cases here pass it in
 * explicitly and the process is left as it was found: a test that states a queue must not decide
 * what the next test's handoff does.
 */
describe("the desk takes a handoff only to a queue the release names", () => {
  const previous = process.env[DESK_QUEUES_SETTING];

  afterEach(() => {
    if (previous === undefined) {
      delete process.env[DESK_QUEUES_SETTING];
    } else {
      process.env[DESK_QUEUES_SETTING] = previous;
    }
  });

  it("takes the queue the caller names when the release states none", () => {
    assert.equal(deskPollsQueue("tier1", undefined), true);
    assert.equal(deskPollsQueue("tier1", ""), true);
    assert.equal(deskPollsQueue("tier1", "  ,  "), true);
  });

  it("takes a queue the release names, with the spaces a statement carries", () => {
    assert.equal(deskPollsQueue("post-sales-desk", "post-sales-desk, ticketing-desk"), true);
    assert.equal(deskPollsQueue("ticketing-desk", "post-sales-desk,ticketing-desk"), true);
  });

  it("refuses a queue the release does not name", () => {
    assert.equal(deskPollsQueue("tier1", "post-sales-desk, ticketing-desk"), false);
  });

  it("reads the statement the process carries when none is passed in", () => {
    process.env[DESK_QUEUES_SETTING] = "post-sales-desk";
    assert.equal(deskPollsQueue("post-sales-desk"), true);
    assert.equal(deskPollsQueue("tier1"), false);
  });
});
