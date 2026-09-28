import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";

import {
  contractChannels,
  desksOnDuty,
  intakeChannels,
  intakeDeskOfChannel,
  readDeskSheet,
} from "./intake-desks.js";

/** The roster a release would ship if it ran a desk for every channel the contract names. */
const everyDesk = contractChannels.map((channel) => intakeDeskOfChannel[channel]);

function sheetAt(contents: unknown): string {
  const path = join(mkdtempSync(join(tmpdir(), "intake-desks-")), "intake-desks.json");
  writeFileSync(path, JSON.stringify(contents));
  return path;
}

describe("the desks an intake answers", () => {
  it("answers every channel when the roster names every desk", () => {
    assert.deepEqual(desksOnDuty({ desks: everyDesk }), [...contractChannels]);
    assert.deepEqual(intakeChannels({ desks: everyDesk }), [...contractChannels]);
  });

  it("reports the channels in the contract's order, not the roster's", () => {
    assert.deepEqual(desksOnDuty({ desks: ["console-desk", "web-desk", "mobile-desk"] }), [
      "APP",
      "WEB",
      "OPERATOR_CONSOLE",
    ]);
  });

  it("leaves out the channel whose desk the roster spells under another name", () => {
    const renamed = everyDesk.map((desk) => (desk === "mobile-desk" ? "handset-desk" : desk));
    assert.deepEqual(
      desksOnDuty({ desks: renamed }),
      contractChannels.filter((channel) => channel !== "APP"),
    );
  });

  it("answers the contract's whole vocabulary when the roster names no desk", () => {
    assert.deepEqual(intakeChannels({ desks: [] }), [...contractChannels]);
    assert.deepEqual(intakeChannels({}), [...contractChannels]);
    assert.deepEqual(intakeChannels(undefined), [...contractChannels]);
  });

  it("treats a file that is not a roster as no roster", () => {
    assert.equal(readDeskSheet("/nonexistent/intake-desks.json"), undefined);
    assert.equal(readDeskSheet(sheetAt(["mobile-desk"])), undefined);
    assert.equal(readDeskSheet(sheetAt("mobile-desk")), undefined);
  });

  it("reads the roster the release ships beside the module", () => {
    const sheet = readDeskSheet();
    assert.ok(sheet, "the release ships a roster beside the module");
    assert.ok(Array.isArray(sheet.desks) && sheet.desks.length > 0, "the roster names the desks it runs");
  });
});
