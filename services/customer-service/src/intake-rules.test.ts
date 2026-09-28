import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { intakeChannels } from "./intake-rules.js";

const vocabulary = ["APP", "WEB", "PHONE", "IM", "EMAIL", "IN_APP_MESSAGE", "BOT", "OPERATOR_CONSOLE"];

describe("customer-service intake channels", () => {
  it("answers the whole vocabulary when the process names no channel", () => {
    assert.deepEqual(intakeChannels(undefined), vocabulary);
  });

  it("answers the channels the process names, trimmed and in order", () => {
    assert.deepEqual(intakeChannels("WEB, PHONE ,EMAIL"), ["WEB", "PHONE", "EMAIL"]);
  });

  it("treats a blank statement as none rather than as an empty intake", () => {
    assert.deepEqual(intakeChannels(" , "), vocabulary);
  });

  it("does not start with a channel outside the vocabulary", () => {
    assert.throws(() => intakeChannels("WEB,FAX"), /unknown channel: FAX/);
  });
});
