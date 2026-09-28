import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, it } from "node:test";

import { DomainError } from "./domain.js";
import {
  loadCatalogPolicy,
  noticeFloorHoursFor,
  parseCatalogPolicy,
  requireNoticeFloor,
  resetCatalogPolicy,
} from "./catalog-policy.js";

function policyFile(body: string): string {
  const directory = mkdtempSync(join(tmpdir(), "ancillary-policy-"));
  const path = join(directory, "catalog.properties");
  writeFileSync(path, body, "utf8");
  process.env.CATALOG_POLICY_FILE = path;
  resetCatalogPolicy();
  return path;
}

afterEach(() => {
  delete process.env.CATALOG_POLICY_FILE;
  resetCatalogPolicy();
});

describe("catalogue intake policy", () => {
  it("reads the keys the release writes", () => {
    const policy = parseCatalogPolicy(
      ["# the comment a release keeps", "catalog.notice.floor.hours=3", "catalog.notice.floor.methods=PROVIDER_CONFIRMATION, MANUAL_OPS", ""].join("\n"),
      "test",
    );
    assert.equal(policy.noticeFloorHours, 3);
    assert.deepEqual([...policy.noticeFloorMethods], ["PROVIDER_CONFIRMATION", "MANUAL_OPS"]);
    assert.equal(policy.source, "test");
  });

  it("leaves a method the file does not name without a floor", () => {
    policyFile("catalog.notice.floor.hours=6\ncatalog.notice.floor.methods=MANUAL_OPS\n");
    assert.equal(noticeFloorHoursFor("VOUCHER"), 0);
    assert.equal(noticeFloorHoursFor("MANUAL_OPS"), 6);
  });

  it("holds a named method to the floor the file states", () => {
    policyFile("catalog.notice.floor.hours=2\ncatalog.notice.floor.methods=PROVIDER_CONFIRMATION\n");
    assert.throws(
      () => requireNoticeFloor({ fulfillmentMethod: "PROVIDER_CONFIRMATION", purchaseCutoffHoursBeforeDeparture: 1 }),
      (error: unknown) => error instanceof DomainError && error.code === "NOTICE_FLOOR_NOT_MET",
    );
    assert.doesNotThrow(() => requireNoticeFloor({ fulfillmentMethod: "PROVIDER_CONFIRMATION", purchaseCutoffHoursBeforeDeparture: 2 }));
  });

  it("answers with the built-in values when there is no file to read", () => {
    const policy = loadCatalogPolicy(join(tmpdir(), "ancillary-no-such-directory", "catalog.properties"));
    assert.equal(policy.source, "built-in");
    assert.deepEqual([...policy.noticeFloorMethods], []);
  });
});
