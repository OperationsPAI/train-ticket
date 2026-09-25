import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { DEFAULT_EVENT_REPLAY_RETENTION_DAYS, eventReplayRetentionMs } from "./replay.js";

describe("event replay retention", () => {
  it("uses seven days by default and accepts an explicit bounded value", () => {
    assert.equal(eventReplayRetentionMs(undefined), DEFAULT_EVENT_REPLAY_RETENTION_DAYS * 86_400_000);
    assert.equal(eventReplayRetentionMs("30"), 30 * 86_400_000);
  });

  it("rejects unbounded or invalid retention", () => {
    assert.throws(() => eventReplayRetentionMs("0"), /integer from 1 to 90/u);
    assert.throws(() => eventReplayRetentionMs("91"), /integer from 1 to 90/u);
    assert.throws(() => eventReplayRetentionMs("forever"), /integer from 1 to 90/u);
  });
});
