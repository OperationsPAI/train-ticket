import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { referenceArrivesIn } from "@trainticket/ts-kit";

import { createApp } from "./index.js";
import {
  acceptedAccountNamespaces,
  enrolmentReferenceRefused,
  exportedPoleName,
  membershipProgramme,
  openingPole,
  programmeRedemptionRule,
} from "./programme.js";

describe("the membership programme", () => {
  it("is read from the service's own settings", () => {
    const programme = membershipProgramme();

    assert.equal(programme.programme, "train-ticket-loyalty");
    assert.equal(programme.exportedBy, "member-relations");
    assert.ok(programme.poles.length > 0);
    assert.ok(programme.poles.some((pole) => pole.name === programme.openingPole));
  });

  it("opens a membership at the pole the export names", () => {
    const programme = membershipProgramme();

    assert.equal(openingPole(), programme.exportedNames[programme.openingPole] ?? programme.openingPole);
    for (const [exported, pole] of Object.entries(programme.exportedNames)) {
      assert.equal(exportedPoleName(exported), pole);
      assert.ok(programme.poles.some((stated) => stated.name === pole));
    }
  });

  it("states the redemption rule the domain's policy is built from", () => {
    const rule = programmeRedemptionRule();

    assert.ok(rule.pointsToCurrencyRate > 0);
    assert.ok(rule.minRedemption > 0);
    assert.ok(rule.maxFarePercentage > 0 && rule.maxFarePercentage <= 1);
  });

  it("accepts a reference as the caller sends it while the statement is empty", () => {
    assert.equal(referenceArrivesIn("acc-0194f2e0-7b3e-7610-8284-5c26e8b0c456", []), true);
  });

  it("holds an enrolment to the namespaces the programme states", async () => {
    const accepted = acceptedAccountNamespaces();
    const reference = "0194f2e0-7b3e-7610-8284-5c26e8b0c456";

    assert.deepEqual(accepted, membershipProgramme().acceptedNamespaces);
    assert.equal(enrolmentReferenceRefused(`zz-${reference}`), accepted.length > 0);
    for (const namespace of accepted) {
      assert.equal(enrolmentReferenceRefused(`${namespace}-${reference}`), false);
      assert.equal(enrolmentReferenceRefused(`${namespace}_${reference}`), false);
    }

    const app = createApp();
    const refused = await app.inject({
      method: "POST",
      url: "/members/enroll",
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-8284-5c26e8b0c0fe" },
      payload: { accountId: `zz-${reference}` },
    });
    assert.equal(refused.statusCode, accepted.length > 0 ? 400 : 201);
    const namespace = accepted[0];
    if (namespace !== undefined) {
      const enrolled = await app.inject({
        method: "POST",
        url: "/members/enroll",
        headers: { "idempotency-key": "0194f2e0-7b3e-7610-8284-5c26e8b0c0ff" },
        payload: { accountId: `${namespace}-${reference}` },
      });
      assert.equal(enrolled.statusCode, 201);
      assert.equal(enrolled.json().accountId, `${namespace}-${reference}`);
    }
  });
});
