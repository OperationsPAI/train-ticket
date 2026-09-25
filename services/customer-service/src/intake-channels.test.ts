import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, describe, it } from "node:test";

import { PLATFORM_INTAKE_CHANNELS, intakeChannelsFrom } from "./intake-channels.js";

const directory = mkdtempSync(join(tmpdir(), "customer-service-intake-"));
after(() => rmSync(directory, { recursive: true, force: true }));

let sequence = 0;

function settings(contents: string): string {
  sequence += 1;
  const file = join(directory, `settings-${sequence}.json`);
  writeFileSync(file, contents);
  return file;
}

describe("the intake's channel vocabulary", () => {
  it("is the settings file's list when the file names channels the platform knows", () => {
    const file = settings(JSON.stringify({ channels: ["WEB", "PHONE", "BOT"] }));
    assert.deepEqual(intakeChannelsFrom(file), ["WEB", "PHONE", "BOT"]);
  });

  it("drops names the platform does not define rather than answering them", () => {
    const file = settings(JSON.stringify({ channels: ["WEB", "MOBILE", "FAX"] }));
    assert.deepEqual(intakeChannelsFrom(file), ["WEB"]);
  });

  it("falls back to the platform's vocabulary when the file names none of its channels", () => {
    assert.deepEqual(intakeChannelsFrom(settings(JSON.stringify({ channels: ["MOBILE"] }))), PLATFORM_INTAKE_CHANNELS);
    assert.deepEqual(intakeChannelsFrom(settings(JSON.stringify({ channels: [] }))), PLATFORM_INTAKE_CHANNELS);
  });

  it("falls back to the platform's vocabulary when the file is absent or unreadable", () => {
    assert.deepEqual(intakeChannelsFrom(join(directory, "absent.json")), PLATFORM_INTAKE_CHANNELS);
    assert.deepEqual(intakeChannelsFrom(settings("{ not json")), PLATFORM_INTAKE_CHANNELS);
  });
});
