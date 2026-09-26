import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { accountReferenceStatement, membershipProgramme } from "./programme.js";

describe("the membership programme this release carries", () => {
  it("reads the export's revision and the statement an enrolment is held to", () => {
    const programme = membershipProgramme();

    assert.equal(programme.programme, "train-ticket-loyalty");
    assert.equal(programme.exportedBy, "member-relations");
    assert.match(programme.revision, /^\d{4}\.\d{2}$/);
    assert.equal(programme.accountReference, accountReferenceStatement());
    assert.ok(Array.isArray(programme.accountReference.acceptedNamespaces));
  });

  it("refuses a reference the statement does not hold", async () => {
    const { InMemoryEventPublisher, InMemoryMemberRepository, LoyaltyMembershipApplicationService } = await import("./index.js");
    const service = new LoyaltyMembershipApplicationService(new InMemoryMemberRepository(), new InMemoryEventPublisher());
    const accepted = accountReferenceStatement().acceptedNamespaces;
    const held = `${accepted[0] ?? "acc"}_0194f2e0-7b3e-7610-8284-5c26e8b0c456`;

    assert.equal((await service.enrollMember(held)).created, true);
    if (accepted.length > 0) {
      await assert.rejects(() => service.enrollMember("zz_0194f2e0-7b3e-7610-8284-5c26e8b0c456"));
    }
  });
});
