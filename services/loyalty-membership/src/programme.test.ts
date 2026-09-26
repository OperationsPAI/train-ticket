import assert from "node:assert/strict";
import { test } from "node:test";

import { Member, Tier, TierEvaluator } from "./domain.js";
import { membershipProgramme, openingPole } from "./programme.js";

test("the ladder the service evaluates against is the one the programme states", () => {
  const programme = membershipProgramme();

  assert.deepEqual(
    TierEvaluator.policies().map((policy) => policy.tierName),
    programme.poles.map((pole) => pole.name),
  );
  assert.equal(TierEvaluator.policies()[0]?.minQualifyingPoints, programme.poles[0]?.qualifyingPoints);
  assert.deepEqual(TierEvaluator.policies()[0]?.benefits, programme.poles[0]?.benefits);
});

test("an evaluation that qualifies for nothing falls back to the lowest pole the programme states", () => {
  const lowest = membershipProgramme().poles[0]?.name ?? "";

  assert.equal(TierEvaluator.evaluate(0, 0), lowest);
  assert.equal(Tier.fromTierPoints(0).name, lowest);
});

test("a membership opens at the pole the programme states, under the name the export uses", () => {
  const programme = membershipProgramme();

  assert.equal(openingPole(), programme.exportedNames[programme.openingPole] ?? programme.openingPole);
  assert.equal(Member.enroll({ accountId: "acct-programme-1" }).tier, openingPole());
  assert.equal(Member.enroll({ accountId: "acct-programme-1" }).toSnapshot().membershipYears?.[0]?.currentTier, openingPole());
});
