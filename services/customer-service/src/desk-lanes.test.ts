import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";

import { DESK_LANES_SETTING, resolveHandoffLane, staffedLanes } from "./desk-lanes.js";

/**
 * The desk's lane statement.
 *
 * The statement belongs to a release rather than to the service, so the cases here pass it in
 * explicitly and the process is left as it was found: a statement one case writes must not decide
 * what the next case's handoff resolves to.
 */
describe("the counter resolves a handoff against the lanes the release staffs", () => {
  const previous = process.env[DESK_LANES_SETTING];

  afterEach(() => {
    if (previous === undefined) {
      delete process.env[DESK_LANES_SETTING];
    } else {
      process.env[DESK_LANES_SETTING] = previous;
    }
  });

  it("keeps the service's own arrangement while the release states no lane", () => {
    assert.equal(staffedLanes(undefined), undefined);
    assert.equal(resolveHandoffLane("tier1", undefined), "tier1");
    assert.equal(resolveHandoffLane("tier1", ""), "tier1");
    assert.equal(resolveHandoffLane("tier1", "  ,  "), "tier1");
  });

  it("resolves a lane the release staffs, with the spaces a statement carries", () => {
    assert.deepEqual(staffedLanes("post-sales-desk, ticketing-desk"), ["post-sales-desk", "ticketing-desk"]);
    assert.equal(resolveHandoffLane("post-sales-desk", "post-sales-desk, ticketing-desk"), "post-sales-desk");
    assert.equal(resolveHandoffLane("ticketing-desk", "post-sales-desk,ticketing-desk"), "ticketing-desk");
  });

  it("resolves no lane for a queue the release does not staff", () => {
    assert.equal(resolveHandoffLane("tier1", "post-sales-desk, ticketing-desk"), undefined);
  });

  it("reads the statement the process carries when none is passed in", () => {
    process.env[DESK_LANES_SETTING] = "post-sales-desk";
    assert.equal(resolveHandoffLane("post-sales-desk"), "post-sales-desk");
    assert.equal(resolveHandoffLane("tier1"), undefined);
  });
});
