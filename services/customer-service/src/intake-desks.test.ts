import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";

import { contractChannels, desksOnDuty, intakeChannels, readDeskSheet } from "./intake-desks.js";

const everyDesk = [
  "mobile-desk",
  "web-desk",
  "phone-desk",
  "chat-desk",
  "mail-desk",
  "message-desk",
  "bot-desk",
  "console-desk",
];

describe("the intake desk sheet", () => {
  it("keeps the contract's order and leaves out a channel whose desk the sheet does not name", () => {
    assert.deepEqual(desksOnDuty({ desks: ["console-desk", "web-desk", "handset-desk"] }), ["WEB", "OPERATOR_CONSOLE"]);
  });

  it("answers the whole vocabulary while the sheet names every desk", () => {
    assert.deepEqual(intakeChannels({ desks: everyDesk }), [...contractChannels]);
  });

  it("answers the whole vocabulary when the sheet names none of those desks", () => {
    assert.deepEqual(desksOnDuty({ desks: ["front-desk"] }), []);
    assert.deepEqual(intakeChannels({ desks: ["front-desk"] }), [...contractChannels]);
  });

  it("reads the sheet it is pointed at, and gives nothing back for one that is not there", () => {
    const directory = mkdtempSync(join(tmpdir(), "intake-desks-"));
    const path = join(directory, "intake-desks.json");
    writeFileSync(path, JSON.stringify({ desks: ["web-desk"] }));
    assert.deepEqual(readDeskSheet(path), { desks: ["web-desk"] });
    assert.equal(readDeskSheet(join(directory, "absent.json")), undefined);
  });
});
