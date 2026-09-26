import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { DomainError, Member } from "./domain.js";
import { accountReferenceStatement, membershipProgramme } from "./programme.js";

const ACCOUNT_REFERENCE = "acc-0194f2e0-7b3e-7610-8284-5c26e8b0c456";

describe("membership programme", () => {
  it("reads the revision and the account reference statement the release carries", () => {
    const programme = membershipProgramme();

    assert.equal(programme.programme, "train-ticket-loyalty");
    assert.equal(programme.exportedBy, "member-relations");
    assert.match(programme.revision, /^\d{4}\.\d{2}$/);
    assert.ok(Array.isArray(programme.accountReference.acceptedNamespaces));
    assert.equal(programme.accountReference, accountReferenceStatement());
  });

  it("accepts the reference as the caller sends it when the statement lists no namespace", () => {
    const member = Member.enroll({ accountId: ACCOUNT_REFERENCE, statement: { acceptedNamespaces: [] } });

    assert.equal(member.accountId, ACCOUNT_REFERENCE);
  });

  it("refuses a reference from a namespace the statement does not list", () => {
    for (const acceptedNamespaces of [["acct"], ["acct", "account"]]) {
      assert.throws(
        () => Member.enroll({ accountId: ACCOUNT_REFERENCE, statement: { acceptedNamespaces } }),
        (error: unknown) => error instanceof DomainError && error.code === "ACCOUNT_REFERENCE_NOT_ACCEPTED",
      );
    }
  });

  it("accepts a reference from a namespace the statement lists, under either separator", () => {
    for (const accountId of ["acct_0194f2e0-7b3e-7610-8284-5c26e8b0c456", "acct-0194f2e0-7b3e-7610-8284-5c26e8b0c456"]) {
      const member = Member.enroll({ accountId, statement: { acceptedNamespaces: ["acct"] } });

      assert.equal(member.accountId, accountId);
    }
  });
});
