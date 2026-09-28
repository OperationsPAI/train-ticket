import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";

const compiled = (module: string) => new URL(module, import.meta.url).href;

const probeDirectory = mkdtempSync(join(tmpdir(), "customer-service-assign-probe-"));
const probePath = join(probeDirectory, "assign-probe.mjs");
const probeSource = `
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { createApp } from ${JSON.stringify(compiled("./app.js"))};
import { InMemoryEventPublisher } from ${JSON.stringify(compiled("./application/messaging.js"))};

function uuidV7() {
  const stamp = Date.now().toString(16).padStart(12, "0");
  const bytes = Buffer.concat([Buffer.from(stamp, "hex"), randomBytes(10)]);
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join("-");
}

const publisher = new InMemoryEventPublisher();
const app = createApp({ publisher, instrumentation: {} });
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
  classification: "POST_SALES_HELP",
  priority: "NORMAL",
  description: "Help me with my order",
});
assert.equal(opened.status, 201);

const refused = await post(\`/api/v1/support-cases/\${opened.body.caseId}/assign\`, { ownerQueue: "tier1" });
const afterRefusal = await app.inject(\`/api/v1/support-cases/\${opened.body.caseId}\`);
const taken = await post(\`/api/v1/support-cases/\${opened.body.caseId}/assign\`, { ownerQueue: "post-sales-desk" });

await app.close();
console.log(JSON.stringify({
  stated: process.env.CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES ?? null,
  refused: { status: refused.status, code: refused.body.code, domainCode: refused.body.details && refused.body.details.domainCode },
  fetched: { status: afterRefusal.statusCode, caseStatus: afterRefusal.json().status, ownerQueue: afterRefusal.json().ownerQueue },
  taken: { status: taken.status, caseStatus: taken.body.status, ownerQueue: taken.body.ownerQueue },
  assignedEvents: publisher.envelopes.filter((envelope) => envelope.eventType === "SupportCaseAssigned").length,
}));
`;
writeFileSync(probePath, probeSource);

function assignUnderStatement(stated: string | undefined): Record<string, never> {
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

describe("customer-service assign HTTP surface", () => {
  it("keeps a refused handoff out of the case and takes the next queue the release states", () => {
    const run = assignUnderStatement("post-sales-desk,ticketing-desk") as never as {
      refused: { status: number; code: string; domainCode: string };
      fetched: { caseStatus: string; ownerQueue?: string };
      taken: { status: number; caseStatus: string; ownerQueue: string };
      assignedEvents: number;
    };

    assert.equal(run.refused.status, 422);
    assert.equal(run.refused.code, "DOMAIN_RULE_VIOLATION");
    assert.equal(run.refused.domainCode, "CASE_NOT_ROUTABLE");
    assert.equal(run.fetched.caseStatus, "OPENED");
    assert.equal(run.fetched.ownerQueue, undefined);

    assert.equal(run.taken.status, 200);
    assert.equal(run.taken.caseStatus, "IN_PROGRESS");
    assert.equal(run.taken.ownerQueue, "post-sales-desk");
    assert.equal(run.assignedEvents, 1);
  });
});

process.on("exit", () => {
  rmSync(probeDirectory, { recursive: true, force: true });
});
