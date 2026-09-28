import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { intakeChannels, platformChannels } from "./intake-desks.js";

describe("the intake desk sheet", () => {
  it("answers the channel whose desk the sheet has on duty", () => {
    assert.deepEqual(intakeChannels({ desks: ["web-desk"] }), ["WEB"]);
    assert.deepEqual(intakeChannels({ desks: ["web-desk", "phone-desk"] }), ["WEB", "PHONE"]);
  });

  it("answers the desk's channels in the platform's order", () => {
    assert.deepEqual(intakeChannels({ desks: ["mail-desk", "chat-desk", "phone-desk"] }), [
      "PHONE",
      "IM",
      "EMAIL",
    ]);
  });

  it("leaves out a channel whose desk the sheet does not name", () => {
    assert.deepEqual(intakeChannels({ desks: ["web-desk", "support-desk"] }), ["WEB"]);
  });

  it("answers the platform's vocabulary when the sheet names no desk of this release", () => {
    assert.deepEqual(intakeChannels({ desks: [] }), platformChannels);
    assert.deepEqual(intakeChannels({ desks: ["desk-of-another-release"] }), platformChannels);
    assert.deepEqual(intakeChannels({}), platformChannels);
    assert.deepEqual(intakeChannels(undefined), platformChannels);
  });
});
