import assert from "node:assert/strict";
import test from "node:test";
import { runtimeWaitlistExpiryScanMs } from "../src/bootstrap.js";

test("waitlist expiry scan default is inside e2e polling window", () => {
  const original = process.env.WAITLIST_EXPIRY_SCAN_MS;
  delete process.env.WAITLIST_EXPIRY_SCAN_MS;
  try {
    assert.equal(runtimeWaitlistExpiryScanMs(), 1000);
  } finally {
    if (original === undefined) delete process.env.WAITLIST_EXPIRY_SCAN_MS;
    else process.env.WAITLIST_EXPIRY_SCAN_MS = original;
  }
});

test("waitlist expiry scan interval can be overridden", () => {
  const original = process.env.WAITLIST_EXPIRY_SCAN_MS;
  process.env.WAITLIST_EXPIRY_SCAN_MS = "2500";
  try {
    assert.equal(runtimeWaitlistExpiryScanMs(), 2500);
  } finally {
    if (original === undefined) delete process.env.WAITLIST_EXPIRY_SCAN_MS;
    else process.env.WAITLIST_EXPIRY_SCAN_MS = original;
  }
});
