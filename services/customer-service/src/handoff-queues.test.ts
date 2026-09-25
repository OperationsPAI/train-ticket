import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { deskPollsQueue, queueIsPolled, releasedOwnerQueues, statedOwnerQueues } from "./handoff-queues.js";

describe("the queues the desk's release staffs", () => {
  it("polls every queue while the release states none", () => {
    assert.equal(queueIsPolled(undefined, "support"), true);
    assert.equal(queueIsPolled(undefined, "post-sales-desk"), true);
  });

  it("keeps only the queues the release states", () => {
    const stated = statedOwnerQueues("post-sales-desk,ticketing-desk");
    assert.deepEqual(stated, ["post-sales-desk", "ticketing-desk"]);
    assert.equal(queueIsPolled(stated, "post-sales-desk"), true);
    assert.equal(queueIsPolled(stated, "support"), false);
  });

  it("trims the lanes the release states and drops the empty ones", () => {
    assert.deepEqual(statedOwnerQueues(" post-sales-desk , ticketing-desk "), ["post-sales-desk", "ticketing-desk"]);
    assert.equal(statedOwnerQueues(""), undefined);
    assert.equal(statedOwnerQueues(" , "), undefined);
  });

  it("hands the process the statement the deployment gave it", () => {
    const stated = statedOwnerQueues(process.env.CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES);
    assert.deepEqual(releasedOwnerQueues(), stated);
    assert.equal(deskPollsQueue("post-sales-desk"), queueIsPolled(stated, "post-sales-desk"));
  });
});
