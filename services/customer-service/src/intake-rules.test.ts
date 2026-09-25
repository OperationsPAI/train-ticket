import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";

import {
  INTAKE_SETTINGS_FILE,
  PLATFORM_CHANNELS,
  intakeChannels,
  intakeChannelsFor,
  readIntakeSettings,
} from "./intake-rules.js";

const vocabulary = ["APP", "WEB", "PHONE", "IM", "EMAIL", "IN_APP_MESSAGE", "BOT", "OPERATOR_CONSOLE"];

function fileHolding(stated: unknown): string {
  const directory = mkdtempSync(join(tmpdir(), "cs-intake-"));
  const file = join(directory, INTAKE_SETTINGS_FILE);
  writeFileSync(file, typeof stated === "string" ? stated : (JSON.stringify(stated) ?? ""));
  return file;
}

describe("customer-service intake settings", () => {
  it("defines the platform's whole vocabulary for a support case", () => {
    assert.deepEqual([...PLATFORM_CHANNELS], vocabulary);
  });

  it("answers the channels the settings name", () => {
    assert.deepEqual(intakeChannelsFor({ channels: ["WEB", "PHONE"] }), ["WEB", "PHONE"]);
  });

  it("answers the whole vocabulary when the settings name none", () => {
    assert.deepEqual(intakeChannelsFor({ channels: [] }), vocabulary);
  });

  it("reads the statement beside the service once", () => {
    const channels = intakeChannels();

    assert.ok(channels.length > 0);
    assert.ok(channels.every((channel) => vocabulary.includes(channel)));
    // Read once: a list that changed while the process serves would let two
    // replicas of one release answer different channels.
    assert.equal(intakeChannels(), channels);
  });

  it("states nothing when the file is missing, unreadable or malformed", () => {
    assert.deepEqual(readIntakeSettings(join(tmpdir(), "cs-intake-absent.json")), { channels: [] });
    assert.deepEqual(readIntakeSettings(fileHolding("{ not json")), { channels: [] });
    assert.deepEqual(readIntakeSettings(fileHolding({ channels: "WEB" })), { channels: [] });
  });

  it("fails on a name the platform does not define", () => {
    assert.throws(
      () => readIntakeSettings(fileHolding({ channels: ["WEB", "FAX"] })),
      /not a channel of the platform: FAX/,
    );
  });
});
