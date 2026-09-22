import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { describe, it } from "node:test";

// The roster is read when the module is first loaded and the process keeps it
// for the lifetime of the release, so the three arrangements are probed in
// child processes rather than by rewriting the environment of a process whose
// module has already been loaded.
function rosterUnder(stated: string | undefined): Record<string, unknown> {
  const env: NodeJS.ProcessEnv = { ...process.env };
  if (stated === undefined) {
    delete env.CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES;
  } else {
    env.CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES = stated;
  }
  const module = new URL("./desk-roster.js", import.meta.url).href;
  const probe = [
    `const roster = await import(${JSON.stringify(module)});`,
    "console.log(JSON.stringify({",
    "  queues: roster.staffedOwnerQueues() ?? null,",
    '  staffs: ["post-sales-desk", "ticketing-desk", "tier1"].filter((queue) => roster.deskStaffsQueue(queue)),',
    "  refusal: (() => {",
    '    try { roster.assertHandoffQueueIsStaffed("sc-probe", "tier1"); return null; }',
    "    catch (error) { return error.code; }",
    "  })(),",
    "}));",
  ].join("\n");
  const output = execFileSync(process.execPath, ["--input-type=module", "-e", probe], { env, encoding: "utf8" });
  const lines = output.trim().split("\n");
  return JSON.parse(lines[lines.length - 1] ?? "{}") as Record<string, unknown>;
}

describe("the desk's roster", () => {
  it("keeps the lanes the release states", () => {
    const roster = rosterUnder("post-sales-desk,ticketing-desk");
    assert.deepEqual(roster.queues, ["post-sales-desk", "ticketing-desk"]);
    assert.deepEqual(roster.staffs, ["post-sales-desk", "ticketing-desk"]);
    assert.equal(roster.refusal, "CASE_NOT_ROUTABLE");
  });

  it("trims the stated lanes and drops the empty ones", () => {
    assert.deepEqual(rosterUnder(" post-sales-desk , ticketing-desk ").queues, ["post-sales-desk", "ticketing-desk"]);
    assert.equal(rosterUnder("").queues, null);
    assert.equal(rosterUnder(" , ").queues, null);
  });

  it("staffs every lane while the release states none", () => {
    const roster = rosterUnder(undefined);
    assert.equal(roster.queues, null);
    assert.deepEqual(roster.staffs, ["post-sales-desk", "ticketing-desk", "tier1"]);
    assert.equal(roster.refusal, null);
  });
});
