import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";

import {
  desksOnDuty,
  intakeChannels,
  intakeDesks,
  platformChannels,
  readDeskSheet,
} from "./intake-desks.js";

const everyDesk = { desks: Object.values(intakeDesks) };

describe("the intake's desk sheet", () => {
  it("answers every channel whose desk is on duty, in the contract's order", () => {
    assert.deepEqual(intakeChannels(everyDesk), platformChannels);
  });

  it("orders the answered channels by the contract, not by the sheet", () => {
    const sheet = { desks: ["console-desk", "web-desk", "mail-desk"] };
    assert.deepEqual(desksOnDuty(sheet), ["WEB", "EMAIL", "OPERATOR_CONSOLE"]);
    assert.deepEqual(intakeChannels(sheet), ["WEB", "EMAIL", "OPERATOR_CONSOLE"]);
  });

  it("leaves out a channel whose desk the sheet does not name", () => {
    const onDuty = platformChannels.filter((channel) => channel !== "APP");
    const sheet = { desks: onDuty.map((channel) => intakeDesks[channel]) };
    assert.deepEqual(desksOnDuty(sheet), [...onDuty]);
    assert.deepEqual(intakeChannels(sheet), [...onDuty]);
    assert.equal(intakeChannels(sheet).includes("APP"), false);
  });

  it("leaves the whole vocabulary to a sheet that names no desk of this release", () => {
    assert.deepEqual(intakeChannels(undefined), platformChannels);
    assert.deepEqual(intakeChannels({}), platformChannels);
    assert.deepEqual(intakeChannels({ desks: [] }), platformChannels);
    assert.deepEqual(intakeChannels({ desks: ["front-desk", "back-office"] }), platformChannels);
    assert.deepEqual(desksOnDuty({ desks: ["front-desk"] }), []);
  });

  it("reads the desks out of the sheet the release ships", () => {
    const directory = mkdtempSync(join(tmpdir(), "intake-desks-"));
    const path = join(directory, "intake-desks.json");
    writeFileSync(path, JSON.stringify({ note: "written by the test", desks: ["web-desk"] }));
    assert.deepEqual(readDeskSheet(path), { note: "written by the test", desks: ["web-desk"] });
  });

  it("reads no sheet where there is nothing usable to read", () => {
    const directory = mkdtempSync(join(tmpdir(), "intake-desks-"));
    const path = join(directory, "intake-desks.json");
    assert.equal(readDeskSheet(join(directory, "absent.json")), undefined);
    writeFileSync(path, "[\"web-desk\"]");
    assert.equal(readDeskSheet(path), undefined);
    writeFileSync(path, "{ not json");
    assert.equal(readDeskSheet(path), undefined);
  });
});
