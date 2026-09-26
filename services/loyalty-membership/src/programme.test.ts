import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { accountReferenceStatement, membershipProgramme } from "./programme.js";

describe("membership programme", () => {
  it("reads the revision and the account-reference statement the release carries", () => {
    const programme = membershipProgramme();

    assert.equal(programme.programme, "train-ticket-loyalty");
    assert.equal(programme.exportedBy, "member-relations");
    assert.match(programme.revision, /^\d{4}\.\d{2}$/);
    assert.equal(programme.accountReference, accountReferenceStatement());
    assert.ok(Array.isArray(programme.accountReference.acceptedNamespaces));
  });

  it("keys an enrolment by a reference the statement holds", async () => {
    const { InMemoryEventPublisher, InMemoryMemberRepository, LoyaltyMembershipApplicationService } = await import("./index.js");
    const service = new LoyaltyMembershipApplicationService(new InMemoryMemberRepository(), new InMemoryEventPublisher());
    const accepted = accountReferenceStatement().acceptedNamespaces;
    const reference = accepted.length === 0 ? "acc-0194f2e0-7b3e-7610-8284-5c26e8b0c456" : `${accepted[0]}_0194f2e0-7b3e-7610-8284-5c26e8b0c456`;

    assert.equal((await service.enrollMember(reference)).created, true);
    if (accepted.length > 0) {
      await assert.rejects(() => service.enrollMember("zz_0194f2e0-7b3e-7610-8284-5c26e8b0c456"));
    }
  });
});
