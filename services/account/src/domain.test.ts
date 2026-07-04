import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  DomainError,
  UserAccount,
  Session,
  Preference,
  AccountClosureSaga,
  type UserAccountSnapshot,
  type PreferenceSnapshot,
} from "./domain.js";

// ──────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────

function expectDomainError(fn: () => unknown, code: string): void {
  assert.throws(fn, (error: unknown) => error instanceof DomainError && error.code === code);
}

// ──────────────────────────────────────────────
// UserAccount tests
// ──────────────────────────────────────────────

describe("UserAccount aggregate", () => {
  it("creates an account with Active status", () => {
    const { account, event } = UserAccount.create({});

    assert.equal(account.status, "Active");
    assert.ok(account.id.startsWith("acct_"));
    assert.equal(account.canAct(), true);
    assert.equal(account.isFrozen(), false);
    assert.equal(event.type, "AccountCreated");
    assert.equal(event.accountId, account.id);
  });

  it("accepts a provided account id", () => {
    const { account } = UserAccount.create({ accountId: "acct_custom-id-001" });
    assert.equal(account.id, "acct_custom-id-001");
  });

  it("correlation id flows through to the event", () => {
    const { event } = UserAccount.create({ correlationId: "corr-001" });
    assert.equal(event.correlationId, "corr-001");
  });

  it("freezes an active account and clears freeze on unfreeze", () => {
    const { account } = UserAccount.create({});
    assert.equal(account.canAct(), true);

    const { account: frozenAccount, event: freezeEvent } = account.freeze({
      accountId: account.id,
      reason: "security concern",
      operator: "system",
      caseRef: "case-001",
    });

    assert.equal(frozenAccount.status, "Frozen");
    assert.equal(frozenAccount.canAct(), false);
    assert.equal(frozenAccount.isFrozen(), true);
    assert.equal(freezeEvent.type, "AccountFrozen");
    assert.equal(freezeEvent.reason, "security concern");
    assert.equal(freezeEvent.operator, "system");
    assert.equal(freezeEvent.caseRef, "case-001");

    const { account: unfrozenAccount, event: unfreezeEvent } = frozenAccount.unfreeze({
      accountId: frozenAccount.id,
      reason: "resolved",
    });

    assert.equal(unfrozenAccount.status, "Active");
    assert.equal(unfrozenAccount.canAct(), true);
    assert.equal(unfrozenAccount.isFrozen(), false);
    assert.equal(unfreezeEvent.type, "AccountUnfrozen");
    assert.equal(unfreezeEvent.reason, "resolved");
  });

  it("rejects freeze when account is not Active", () => {
    const { account } = UserAccount.create({});

    const { account: frozen } = account.freeze({
      accountId: account.id,
      reason: "test",
      operator: "system",
    });

    expectDomainError(
      () => frozen.freeze({ accountId: frozen.id, reason: "again", operator: "system" }),
      "ACCOUNT_NOT_ACTIVE",
    );
  });

  it("rejects unfreeze when account is not Frozen", () => {
    const { account } = UserAccount.create({});

    expectDomainError(
      () => account.unfreeze({ accountId: account.id, reason: "why?" }),
      "ACCOUNT_NOT_FROZEN",
    );
  });

  it("starts closure from Active or Frozen state", () => {
    const { account } = UserAccount.create({});

    const { account: pending, event } = account.startClosure({ accountId: account.id });

    assert.equal(pending.status, "ClosurePending");
    assert.equal(pending.canAct(), false);
    assert.equal(event.type, "AccountClosureStarted");
    assert.ok(event.closureRequestId.startsWith("clr_"));
  });

  it("rejects startClosure if already Closed", () => {
    const { account } = UserAccount.create({});
    const { account: pending } = account.startClosure({ accountId: account.id });
    const { account: closed } = pending.completeClosure({
      accountId: pending.id,
      closureRequestId: pending.toSnapshot().closureRequestId!,
    });

    expectDomainError(
      () => closed.startClosure({ accountId: closed.id }),
      "ACCOUNT_ALREADY_CLOSED",
    );
  });

  it("rejects startClosure if already ClosurePending", () => {
    const { account } = UserAccount.create({});
    const { account: pending } = account.startClosure({ accountId: account.id });

    expectDomainError(
      () => pending.startClosure({ accountId: pending.id }),
      "CLOSURE_ALREADY_PENDING",
    );
  });

  it("completes closure from ClosurePending state", () => {
    const { account } = UserAccount.create({});
    const { account: pending } = account.startClosure({ accountId: account.id });

    const closureRequestId = pending.toSnapshot().closureRequestId!;
    const { account: closed, event } = pending.completeClosure({
      accountId: pending.id,
      closureRequestId,
    });

    assert.equal(closed.status, "Closed");
    assert.equal(closed.canAct(), false);
    assert.equal(event.type, "AccountClosed");
    assert.equal(event.final, true);
  });

  it("rejects completeClosure if not ClosurePending", () => {
    const { account } = UserAccount.create({});

    expectDomainError(
      () => account.completeClosure({ accountId: account.id, closureRequestId: "clr_unknown" }),
      "CLOSURE_NOT_PENDING",
    );
  });

  it("rejects completeClosure with mismatched closureRequestId", () => {
    const { account } = UserAccount.create({});
    const { account: pending } = account.startClosure({ accountId: account.id });

    expectDomainError(
      () => pending.completeClosure({ accountId: pending.id, closureRequestId: "clr_wrong" }),
      "CLOSURE_REQUEST_ID_MISMATCH",
    );
  });

  it("snapshot is immutable", () => {
    const { account } = UserAccount.create({ accountId: "acct_immutable-test" });
    const snap = account.toSnapshot();

    assert.throws(() => {
      (snap as { status: string }).status = "Frozen";
    }, TypeError);
  });
});

// ──────────────────────────────────────────────
// Session tests
// ──────────────────────────────────────────────

describe("Session aggregate", () => {
  it("opens a session for an active account", () => {
    const now = new Date("2026-07-03T10:00:00.000Z");
    const { session, event } = Session.open(
      { accountId: "acct_test-001" },
      { status: "Active" },
      now,
    );

    assert.equal(session.status, "Active");
    assert.ok(session.id.startsWith("sess_"));
    assert.equal(session.accountId, "acct_test-001");
    assert.equal(event.type, "SessionOpened");
    // Session is active at creation time
    assert.equal(session.canAct(now), true);
  });

  it("rejects opening a session for a closed account", () => {
    expectDomainError(
      () => Session.open({ accountId: "acct_closed" }, { status: "Closed" }),
      "ACCOUNT_CLOSED",
    );
  });

  it("allows session for frozen account", () => {
    const now = new Date("2026-07-03T10:00:00.000Z");
    const { session } = Session.open(
      { accountId: "acct_frozen" },
      { status: "Frozen" },
      now,
    );

    assert.equal(session.status, "Active");
    assert.equal(session.canAct(now), true);
  });

  it("revokes an active session", () => {
    const now = new Date("2026-07-03T10:00:00.000Z");
    const { session } = Session.open({ accountId: "acct_test" }, { status: "Active" }, now);

    const { session: revoked, event } = session.revoke({
      sessionId: session.id,
      accountId: session.accountId,
      reason: "logout",
    });

    assert.equal(revoked.status, "Revoked");
    assert.equal(revoked.canAct(now), false);
    assert.equal(event.type, "SessionRevoked");
    assert.equal(event.reason, "logout");
  });

  it("rejects revoking a session that is not Active", () => {
    const now = new Date("2026-07-03T10:00:00.000Z");
    const { session } = Session.open({ accountId: "acct_test" }, { status: "Active" }, now);

    const { session: revoked } = session.revoke({
      sessionId: session.id,
      accountId: session.accountId,
      reason: "logout",
    });

    expectDomainError(
      () => revoked.revoke({ sessionId: revoked.id, accountId: revoked.accountId, reason: "logout" }),
      "SESSION_NOT_ACTIVE",
    );
  });

  it("rejects revoking a session with mismatched account id", () => {
    const now = new Date("2026-07-03T10:00:00.000Z");
    const { session } = Session.open({ accountId: "acct_test" }, { status: "Active" }, now);

    expectDomainError(
      () => session.revoke({ sessionId: session.id, accountId: "acct_wrong", reason: "forced" }),
      "ACCOUNT_MISMATCH",
    );
  });

  it("detects expired session", () => {
    const now = new Date("2026-07-03T10:00:00.000Z");
    const { session } = Session.open(
      { accountId: "acct_test" },
      { status: "Active" },
      now,
    );

    // Should not be expired within 24h
    assert.equal(session.isExpired(new Date("2026-07-04T09:59:00.000Z")), false);
    assert.equal(session.isExpired(new Date("2026-07-04T10:00:00.000Z")), true);
    assert.equal(session.canAct(new Date("2026-07-04T10:00:00.000Z")), false);
  });

  it("snapshot is immutable", () => {
    const now = new Date("2026-07-03T10:00:00.000Z");
    const { session } = Session.open({ accountId: "acct_test" }, { status: "Active" }, now);
    const snap = session.toSnapshot();

    assert.throws(() => {
      (snap as { status: string }).status = "Revoked";
    }, TypeError);
  });
});

// ──────────────────────────────────────────────
// Preference tests
// ──────────────────────────────────────────────

describe("Preference aggregate", () => {
  it("creates a new preference", () => {
    const { preference, event } = Preference.update({
      accountId: "acct_test",
      preferenceKey: "notification.email",
      value: "enabled",
    });

    assert.equal(preference.key, "notification.email");
    assert.equal(preference.value, "enabled");
    assert.ok(preference.id.startsWith("pref_"));
    assert.equal(event.type, "PreferenceUpdated");
    assert.equal(event.preferenceKey, "notification.email");
    assert.equal(event.newValue, "enabled");
    assert.equal(event.oldValue, undefined);
  });

  it("updates an existing preference and records old value", () => {
    const existing: PreferenceSnapshot = {
      preferenceId: "pref_001",
      accountId: "acct_test",
      key: "notification.email",
      value: "enabled",
      updatedAt: new Date("2026-07-01T00:00:00.000Z"),
    };

    const { preference, event } = Preference.update(
      {
        accountId: "acct_test",
        preferenceKey: "notification.email",
        value: "disabled",
      },
      existing,
    );

    assert.equal(preference.value, "disabled");
    assert.equal(preference.id, "pref_001");
    assert.equal(event.oldValue, "enabled");
    assert.equal(event.newValue, "disabled");
  });

  it("rejects empty key or value", () => {
    expectDomainError(
      () => Preference.update({ accountId: "acct_test", preferenceKey: "", value: "x" }),
      "MISSING_REQUIRED_FIELD",
    );

    expectDomainError(
      () => Preference.update({ accountId: "acct_test", preferenceKey: "key", value: "" }),
      "MISSING_REQUIRED_FIELD",
    );
  });

  it("snapshot is immutable", () => {
    const { preference } = Preference.update({
      accountId: "acct_test",
      preferenceKey: "lang",
      value: "en",
    });
    const snap = preference.toSnapshot();

    assert.throws(() => {
      (snap as { value: string }).value = "fr";
    }, TypeError);
  });
});

// ──────────────────────────────────────────────
// AccountClosureSaga tests
// ──────────────────────────────────────────────

describe("AccountClosureSaga aggregate", () => {
  it("starts a closure saga in PendingVerification status", () => {
    const { saga } = AccountClosureSaga.start({
      accountId: "acct_test",
    });

    assert.equal(saga.status, "PendingVerification");
    assert.ok(saga.closureRequestId.startsWith("clr_"));
  });

  it("accepts a provided closureRequestId", () => {
    const { saga } = AccountClosureSaga.start({
      accountId: "acct_test",
      closureRequestId: "clr_custom-001",
    });

    assert.equal(saga.closureRequestId, "clr_custom-001");
  });

  it("verifies and allows closure when no in-flight items exist", () => {
    const { saga } = AccountClosureSaga.start({ accountId: "acct_test" });

    const { saga: verified, canProceed } = saga.verify(false, false);

    assert.equal(canProceed, true);
    assert.equal(verified.status, "Completed");
  });

  it("rejects closure when in-flight orders exist", () => {
    const { saga } = AccountClosureSaga.start({ accountId: "acct_test" });

    const { saga: verified, canProceed } = saga.verify(true, false);

    assert.equal(canProceed, false);
    assert.equal(verified.status, "Failed");
  });

  it("rejects closure when in-flight refunds exist", () => {
    const { saga } = AccountClosureSaga.start({ accountId: "acct_test" });

    const { saga: verified, canProceed } = saga.verify(false, true);

    assert.equal(canProceed, false);
    assert.equal(verified.status, "Failed");
  });

  it("rejects verify when saga is not in PendingVerification", () => {
    const { saga } = AccountClosureSaga.start({ accountId: "acct_test" });
    const { saga: completed } = saga.verify(false, false);

    expectDomainError(
      () => completed.verify(false, false),
      "SAGA_NOT_PENDING",
    );
  });

  it("snapshot is immutable", () => {
    const { saga } = AccountClosureSaga.start({ accountId: "acct_test" });
    const snap = saga.toSnapshot();

    assert.throws(() => {
      (snap as { status: string }).status = "Completed";
    }, TypeError);
  });
});
