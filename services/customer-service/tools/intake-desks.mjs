#!/usr/bin/env node
/**
 * Renders the intake roster an image ships from the statement the staff console publishes.
 *
 * The intake answers the desks its release runs, and the console is where those desks are decided:
 * the desks work the console's queues, operators are registered against them and manual actions are
 * routed to them. A copy of the names written by hand ships whatever the copy last said, so the
 * roster the process resolves at start-up is rendered from the console's own statement, and the
 * rendered file is committed beside the service as well so what an image carries can be read
 * without building it.
 *
 * Usage: node services/customer-service/tools/intake-desks.mjs <console statement> <sheet>
 */
import { readFileSync, writeFileSync } from "node:fs";

/**
 * The note the rendered roster carries, in the intake's own voice.
 *
 * It is fixed here rather than copied from the console's statement because it is the intake's
 * statement of what the list is for; only the names are the console's.
 */
const RENDERED_NOTE =
  "A support case is filed on a channel and worked by the intake desk that owns it. This sheet is " +
  "the release's own statement of the intake desks it runs, rendered from the roster the staff " +
  "console publishes for them.";

function fail(message) {
  console.error(`intake-desks: ${message}`);
  process.exit(1);
}

const [statementPath, sheetPath] = process.argv.slice(2);
if (!statementPath || !sheetPath) {
  fail("usage: intake-desks.mjs <console statement> <sheet>");
}

let statement;
try {
  statement = JSON.parse(readFileSync(statementPath, "utf8"));
} catch (error) {
  fail(`${statementPath}: ${error instanceof Error ? error.message : String(error)}`);
}

const desks = statement?.desks;
if (!Array.isArray(desks) || desks.length === 0 || !desks.every((desk) => typeof desk === "string")) {
  fail(`${statementPath}: desks must be a non-empty list of queue names`);
}

const seen = new Set();
const unique = desks.filter((desk) => {
  if (seen.has(desk)) {
    return false;
  }
  seen.add(desk);
  return true;
});

writeFileSync(
  sheetPath,
  `${JSON.stringify({ note: RENDERED_NOTE, source: statementPath, desks: unique }, null, 2)}\n`,
);
console.log(`intake-desks: ${sheetPath} carries ${unique.length} desks from ${statementPath}`);
