import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import { readJsonSettings } from "./settings.js";

function settingsDir(): string {
  return mkdtempSync(join(tmpdir(), "ts-kit-settings-"));
}

test("reads the settings file it names and returns it frozen", () => {
  const dir = settingsDir();
  writeFileSync(
    join(dir, "service.json"),
    JSON.stringify({ pole: { name: "SILVER", benefits: ["base earning"] } }),
    "utf8",
  );

  const settings = readJsonSettings(dir, "service.json");

  assert.deepEqual(settings.pole, { name: "SILVER", benefits: ["base earning"] });
  assert.ok(Object.isFrozen(settings));
  assert.ok(Object.isFrozen((settings.pole as { benefits: string[] }).benefits));
  assert.throws(() => {
    (settings as { pole: unknown }).pole = "GOLD";
  }, TypeError);
});

test("a missing or malformed settings file fails with the file named", () => {
  const dir = settingsDir();
  assert.throws(() => readJsonSettings(dir, "absent.json"), /absent\.json could not be read/);

  writeFileSync(join(dir, "truncated.json"), "{", "utf8");
  assert.throws(() => readJsonSettings(dir, "truncated.json"), /truncated\.json is not valid JSON/);

  writeFileSync(join(dir, "list.json"), "[]", "utf8");
  assert.throws(() => readJsonSettings(dir, "list.json"), /list\.json must hold a JSON object/);
});
