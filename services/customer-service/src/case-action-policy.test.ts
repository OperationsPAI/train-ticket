import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { admitsCaseAction, admittedStatuses, caseActionVariable } from "./case-action-policy.js";

describe("customer-service case action policy", () => {
  it("takes the statuses the desk has always taken when the process states nothing", () => {
    assert.deepEqual(admittedStatuses("classify", undefined), ["Opened"]);
    assert.deepEqual(admittedStatuses("assign", undefined), ["Classifying", "Opened"]);
    assert.deepEqual(admittedStatuses("reopen", undefined), ["Closed", "Resolved"]);
  });

  it("takes the statuses the process states, trimmed and in order", () => {
    assert.deepEqual(admittedStatuses("assign", " Classifying , InProgress "), [
      "Classifying",
      "InProgress",
    ]);
  });

  it("treats a blank statement as none rather than as an action that takes nothing", () => {
    assert.deepEqual(admittedStatuses("resolve", " , "), [
      "Assigned",
      "InProgress",
      "WaitingCustomer",
      "WaitingExternal",
      "Escalated",
      "Reopened",
    ]);
  });

  it("does not start with a status outside the vocabulary", () => {
    assert.throws(() => admittedStatuses("assign", "Classifying,ARCHIVED"), /unknown status: ARCHIVED/);
  });

  it("remembers the statement the process read", () => {
    assert.equal(admitsCaseAction("assign", "Opened"), true);
    assert.equal(admitsCaseAction("assign", "Resolved"), false);
  });

  it("names the variable one action is stated in", () => {
    assert.equal(caseActionVariable("assign"), "CUSTOMER_SERVICE_CASE_ACTION_ASSIGN");
  });
});
