import assert from "node:assert/strict";
import { test } from "node:test";

import { Member, Tier, TierEvaluator } from "./domain.js";
import { InMemoryEventPublisher, InMemoryMemberRepository, LoyaltyMembershipApplicationService } from "./index.js";
import { acceptedAccountNamespaces, exportedPoleName, membershipProgramme, openingPole } from "./programme.js";

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

test("an exported pole name is read as the pole it stands for", () => {
  for (const [exported, pole] of Object.entries(membershipProgramme().exportedNames)) {
    assert.equal(exportedPoleName(exported), pole);
  }
});

test("an enrolment keys by a namespace the programme states", async () => {
  const accepted = acceptedAccountNamespaces();
  const service = new LoyaltyMembershipApplicationService(new InMemoryMemberRepository(), new InMemoryEventPublisher());

  assert.deepEqual(accepted, membershipProgramme().acceptedNamespaces);
  const namespace = accepted[0] ?? "acct";
  await assert.doesNotReject(() => service.enrollMember(`${namespace}-0194f2e0-7b3e-7610-8284-5c26e8b0c456`));
  if (accepted.length > 0) {
    await assert.rejects(
      () => service.enrollMember("zz-0194f2e0-7b3e-7610-8284-5c26e8b0c456"),
      (error: Error & { code?: string; statusCode?: number }) => error.code === "VALIDATION_FAILED" && error.statusCode === 400,
    );
  }
});
