import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";

const compiled = (module: string) => new URL(module, import.meta.url).href;

// The arrangement is read when the settings module is first loaded and the
// process holds it for the lifetime of the release, so the three arrangements
// are run in child processes rather than by rewriting the environment of a
// process whose module has already been imported.
const probeDirectory = mkdtempSync(join(tmpdir(), "customer-service-desk-settings-"));
const probePath = join(probeDirectory, "desk-settings-probe.mjs");
const probeSource = `
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { createApp } from ${JSON.stringify(compiled("./app.js"))};

// The probe runs outside the package and imports only the compiled service, so
// it mints its own v7 ids rather than resolving the kit the service imports.
function uuidV7() {
  const stamp = Date.now().toString(16).padStart(12, "0");
  const bytes = Buffer.concat([Buffer.from(stamp, "hex"), randomBytes(10)]);
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join("-");
}

const app = createApp({ instrumentation: {} });
await app.listen({ host: "127.0.0.1", port: 0 });
const address = app.server.address();
assert.ok(address && typeof address === "object");
const base = \`http://127.0.0.1:\${address.port}\`;

async function post(path, body) {
  const response = await fetch(base + path, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "idempotency-key": uuidV7(),
      "x-correlation-id": \`corr-\${uuidV7()}\`,
      "x-operator-ref": "op-test-001",
    },
    body: JSON.stringify(body),
  });
  return { status: response.status, body: await response.json() };
}

const opened = await post("/api/v1/support-cases", {
  requesterRef: "tvl-test-001",
  channel: "APP",
  priority: "NORMAL",
  description: "Help me with my order",
});
assert.equal(opened.status, 201);

const assigned = await post(\`/api/v1/support-cases/\${opened.body.caseId}/assign\`, {
  ownerQueue: "tier1",
});

await app.close();
console.log(JSON.stringify({
  stated: process.env.CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES ?? null,
  status: assigned.status,
  code: assigned.body.code,
  message: assigned.body.message,
  domainCode: assigned.body.details && assigned.body.details.domainCode,
}));
`;
writeFileSync(probePath, probeSource);

function assignUnderStatement(stated: string | undefined): Record<string, unknown> {
  const env = { ...process.env };
  if (stated === undefined) {
    delete env.CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES;
  } else {
    env.CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES = stated;
  }
  const output = execFileSync(process.execPath, [probePath], { env, encoding: "utf8" });
  const lines = output.trim().split("\n");
  return JSON.parse(lines[lines.length - 1] ?? "{}");
}

describe("customer-service desk settings", () => {
  it("refuses a handoff to a queue the release does not state", () => {
    const refused = assignUnderStatement("post-sales-desk,ticketing-desk");
    assert.equal(refused.stated, "post-sales-desk,ticketing-desk");
    assert.equal(refused.status, 422);
    assert.equal(refused.code, "DOMAIN_RULE_VIOLATION");
    assert.equal(refused.domainCode, "CASE_NOT_ROUTABLE");
    assert.match(String(refused.message), /cannot be handed to tier1/);
  });

  it("takes a handoff to a queue the release states", () => {
    const taken = assignUnderStatement("tier1,post-sales-desk");
    assert.equal(taken.status, 200);
    assert.equal(taken.code, undefined);
  });

  it("takes every handoff when the release states no queues", () => {
    for (const stated of [undefined, "", "   "]) {
      assert.equal(assignUnderStatement(stated).status, 200);
    }
  });
});

process.on("exit", () => {
  rmSync(probeDirectory, { recursive: true, force: true });
});
