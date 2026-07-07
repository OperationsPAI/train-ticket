/**
 * Account Domain Model
 *
 * Key aggregates: UserAccount, Session, Preference, AccountClosureSaga
 *
 * Key invariants:
 * - Freeze blocks new transactions but never blocks existing refunds,
 *   notifications, invoices, or customer-service flows.
 * - Account closure is a saga shell: it verifies no in-flight orders/refunds
 *   before final closure; closure is irreversible.
 * - Sessions reference account state at issuance; a frozen or closed account
 *   invalidates active sessions for new transactional actions.
 * - Traveler facts stay in Traveler Profile; Account only links to them.
 */

// ──────────────────────────────────────────────
// Domain Error
// ──────────────────────────────────────────────

export class DomainError extends Error {
  public readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = "DomainError";
    this.code = code;
  }
}

// ──────────────────────────────────────────────
// Type aliases
// ──────────────────────────────────────────────

export type AccountId = string;   // acct_<uuid>
export type SessionId = string;   // sess_<uuid>
export type PreferenceId = string; // pref_<uuid>
export type ClosureRequestId = string; // clr_<uuid>

// ──────────────────────────────────────────────
// Enums
// ──────────────────────────────────────────────

export type AccountStatus = "Active" | "Frozen" | "ClosurePending" | "Closed";
export type SessionStatus = "Active" | "Revoked" | "Expired";
export type ClosureSagaStatus = "NotStarted" | "PendingVerification" | "Completed" | "Failed";

// ──────────────────────────────────────────────
// Commands (input)
// ──────────────────────────────────────────────

export type CreateAccount = Readonly<{
  accountId?: AccountId;
  correlationId?: string;
}>;

export type OpenSession = Readonly<{
  accountId: AccountId;
  sessionId?: SessionId;
  correlationId?: string;
}>;

export type RevokeSession = Readonly<{
  sessionId: SessionId;
  accountId: AccountId;
  reason: "logout" | "account-frozen" | "account-closed" | "forced";
  correlationId?: string;
}>;

export type FreezeAccount = Readonly<{
  accountId: AccountId;
  reason: string;
  operator: string;
  caseRef?: string;
  correlationId?: string;
}>;

export type UnfreezeAccount = Readonly<{
  accountId: AccountId;
  reason: string;
  correlationId?: string;
}>;

export type UpdatePreference = Readonly<{
  accountId: AccountId;
  preferenceKey: string;
  value: string;
  correlationId?: string;
}>;

export type StartAccountClosure = Readonly<{
  accountId: AccountId;
  closureRequestId?: ClosureRequestId;
  correlationId?: string;
}>;

export type CompleteAccountClosure = Readonly<{
  accountId: AccountId;
  closureRequestId: ClosureRequestId;
  correlationId?: string;
}>;

// ──────────────────────────────────────────────
// Events (output)
// ──────────────────────────────────────────────

export type AccountCreated = Readonly<{
  type: "AccountCreated";
  occurredAt: Date;
  accountId: AccountId;
  correlationId?: string;
}>;

export type AccountFrozen = Readonly<{
  type: "AccountFrozen";
  occurredAt: Date;
  accountId: AccountId;
  reason: string;
  operator: string;
  caseRef?: string;
  correlationId?: string;
}>;

export type AccountUnfrozen = Readonly<{
  type: "AccountUnfrozen";
  occurredAt: Date;
  accountId: AccountId;
  reason: string;
  correlationId?: string;
}>;

export type AccountClosureStarted = Readonly<{
  type: "AccountClosureStarted";
  occurredAt: Date;
  accountId: AccountId;
  closureRequestId: ClosureRequestId;
  correlationId?: string;
}>;

export type AccountClosed = Readonly<{
  type: "AccountClosed";
  occurredAt: Date;
  accountId: AccountId;
  closureRequestId: ClosureRequestId;
  final: true;
  correlationId?: string;
}>;

export type SessionOpened = Readonly<{
  type: "SessionOpened";
  occurredAt: Date;
  sessionId: SessionId;
  accountId: AccountId;
  correlationId?: string;
}>;

export type SessionRevoked = Readonly<{
  type: "SessionRevoked";
  occurredAt: Date;
  sessionId: SessionId;
  accountId: AccountId;
  reason: "logout" | "account-frozen" | "account-closed" | "forced";
  correlationId?: string;
}>;

export type PreferenceUpdated = Readonly<{
  type: "PreferenceUpdated";
  occurredAt: Date;
  accountId: AccountId;
  preferenceKey: string;
  oldValue?: string;
  newValue: string;
  correlationId?: string;
}>;

export type AccountDomainEvent =
  | AccountCreated
  | AccountFrozen
  | AccountUnfrozen
  | AccountClosureStarted
  | AccountClosed
  | SessionOpened
  | SessionRevoked
  | PreferenceUpdated;

// ──────────────────────────────────────────────
// Snapshot types
// ──────────────────────────────────────────────

export type UserAccountSnapshot = Readonly<{
  accountId: AccountId;
  status: AccountStatus;
  createdAt: Date;
  frozenReason?: string;
  frozenAt?: Date;
  frozenOperator?: string;
  frozenCaseRef?: string;
  closureRequestId?: ClosureRequestId;
  closureStartedAt?: Date;
  closedAt?: Date;
}>;

export type SessionSnapshot = Readonly<{
  sessionId: SessionId;
  accountId: AccountId;
  status: SessionStatus;
  createdAt: Date;
  revokedAt?: Date;
  revokeReason?: "logout" | "account-frozen" | "account-closed" | "forced";
  expiresAt: Date;
}>;

export type PreferenceSnapshot = Readonly<{
  preferenceId: PreferenceId;
  accountId: AccountId;
  key: string;
  value: string;
  updatedAt: Date;
}>;

export type AccountClosureSagaSnapshot = Readonly<{
  closureRequestId: ClosureRequestId;
  accountId: AccountId;
  status: ClosureSagaStatus;
  startedAt: Date;
  completedAt?: Date;
  verificationResult?: "verified" | "in-flight-orders-exist" | "in-flight-refunds-exist";
  failureReason?: string;
}>;

// ──────────────────────────────────────────────
// Utility helpers
// ──────────────────────────────────────────────

function generateAccountId(): AccountId {
  return `acct_${crypto.randomUUID()}`;
}

function generateSessionId(): SessionId {
  return `sess_${crypto.randomUUID()}`;
}

function generateClosureRequestId(): ClosureRequestId {
  return `clr_${crypto.randomUUID()}`;
}

function generatePreferenceId(): PreferenceId {
  return `pref_${crypto.randomUUID()}`;
}

function requireNonBlank(value: string | undefined, label: string): void {
  if (!value || value.trim().length === 0) {
    throw new DomainError("MISSING_REQUIRED_FIELD", `${label} is required`);
  }
}

function deepFreeze<T>(value: T): T {
  if (value && typeof value === "object") {
    for (const nested of Object.values(value as Record<string, unknown>)) {
      deepFreeze(nested);
    }
    Object.freeze(value);
  }
  return value;
}

// ──────────────────────────────────────────────
// UserAccount aggregate
// ──────────────────────────────────────────────

export class UserAccount {
  private constructor(private readonly snapshot: UserAccountSnapshot) {
    deepFreeze(this.snapshot);
    Object.freeze(this);
  }

  // ── Factory ──

  static fromSnapshot(snapshot: UserAccountSnapshot): UserAccount {
    return new UserAccount(deepFreeze({ ...snapshot }));
  }

  static create(command: CreateAccount): { account: UserAccount; event: AccountCreated } {
    const accountId = command.accountId ?? generateAccountId();
    requireNonBlank(accountId, "accountId");

    const snapshot: UserAccountSnapshot = deepFreeze({
      accountId,
      status: "Active",
      createdAt: new Date(),
    });

    const event: AccountCreated = deepFreeze({
      type: "AccountCreated",
      occurredAt: new Date(),
      accountId,
      correlationId: command.correlationId,
    });

    return { account: new UserAccount(snapshot), event };
  }

  // ── Freeze ──

  freeze(command: FreezeAccount): { account: UserAccount; event: AccountFrozen } {
    if (this.snapshot.status !== "Active") {
      throw new DomainError("ACCOUNT_NOT_ACTIVE", `Account ${this.snapshot.accountId} in status ${this.snapshot.status} cannot be frozen`);
    }
    requireNonBlank(command.reason, "reason");
    requireNonBlank(command.operator, "operator");

    const snapshot: UserAccountSnapshot = deepFreeze({
      ...this.snapshot,
      status: "Frozen",
      frozenReason: command.reason,
      frozenAt: new Date(),
      frozenOperator: command.operator,
      frozenCaseRef: command.caseRef,
    });

    const event: AccountFrozen = deepFreeze({
      type: "AccountFrozen",
      occurredAt: new Date(),
      accountId: this.snapshot.accountId,
      reason: command.reason,
      operator: command.operator,
      caseRef: command.caseRef,
      correlationId: command.correlationId,
    });

    return { account: new UserAccount(snapshot), event };
  }

  // ── Unfreeze ──

  unfreeze(command: UnfreezeAccount): { account: UserAccount; event: AccountUnfrozen } {
    if (this.snapshot.status !== "Frozen") {
      throw new DomainError("ACCOUNT_NOT_FROZEN", `Account ${this.snapshot.accountId} in status ${this.snapshot.status} cannot be unfrozen`);
    }
    requireNonBlank(command.reason, "reason");

    const snapshot: UserAccountSnapshot = deepFreeze({
      ...this.snapshot,
      status: "Active",
      frozenReason: undefined,
      frozenAt: undefined,
      frozenOperator: undefined,
      frozenCaseRef: undefined,
    });

    const event: AccountUnfrozen = deepFreeze({
      type: "AccountUnfrozen",
      occurredAt: new Date(),
      accountId: this.snapshot.accountId,
      reason: command.reason,
      correlationId: command.correlationId,
    });

    return { account: new UserAccount(snapshot), event };
  }

  // ── Start Closure ──

  startClosure(command: StartAccountClosure): { account: UserAccount; event: AccountClosureStarted } {
    if (this.snapshot.status === "Closed") {
      throw new DomainError("ACCOUNT_ALREADY_CLOSED", `Account ${this.snapshot.accountId} is already closed`);
    }
    if (this.snapshot.status === "ClosurePending") {
      throw new DomainError("CLOSURE_ALREADY_PENDING", `Account ${this.snapshot.accountId} already has a pending closure`);
    }

    const closureRequestId = command.closureRequestId ?? generateClosureRequestId();
    requireNonBlank(closureRequestId, "closureRequestId");

    const snapshot: UserAccountSnapshot = deepFreeze({
      ...this.snapshot,
      status: "ClosurePending",
      closureRequestId,
      closureStartedAt: new Date(),
    });

    const event: AccountClosureStarted = deepFreeze({
      type: "AccountClosureStarted",
      occurredAt: new Date(),
      accountId: this.snapshot.accountId,
      closureRequestId,
      correlationId: command.correlationId,
    });

    return { account: new UserAccount(snapshot), event };
  }

  // ── Complete Closure ──

  completeClosure(command: CompleteAccountClosure): { account: UserAccount; event: AccountClosed } {
    if (this.snapshot.status !== "ClosurePending") {
      throw new DomainError("CLOSURE_NOT_PENDING", `Account ${this.snapshot.accountId} in status ${this.snapshot.status} cannot complete closure`);
    }
    if (command.closureRequestId !== this.snapshot.closureRequestId) {
      throw new DomainError("CLOSURE_REQUEST_ID_MISMATCH", `Closure request id ${command.closureRequestId} does not match ${this.snapshot.closureRequestId}`);
    }

    const snapshot: UserAccountSnapshot = deepFreeze({
      ...this.snapshot,
      status: "Closed",
      closedAt: new Date(),
    });

    const event: AccountClosed = deepFreeze({
      type: "AccountClosed",
      occurredAt: new Date(),
      accountId: this.snapshot.accountId,
      closureRequestId: command.closureRequestId,
      final: true,
      correlationId: command.correlationId,
    });

    return { account: new UserAccount(snapshot), event };
  }

  // ── Guards ──

  /** Returns true if the account permits new transactional actions. */
  canAct(): boolean {
    return this.snapshot.status === "Active";
  }

  /** Returns true if the account is frozen (blocks new transactions but not refunds/notifications). */
  isFrozen(): boolean {
    return this.snapshot.status === "Frozen";
  }

  // ── Accessors ──

  get id(): AccountId {
    return this.snapshot.accountId;
  }

  get status(): AccountStatus {
    return this.snapshot.status;
  }

  toSnapshot(): UserAccountSnapshot {
    return deepFreeze({ ...this.snapshot });
  }
}

// ──────────────────────────────────────────────
// Session aggregate
// ──────────────────────────────────────────────

export class Session {
  private constructor(private readonly snapshot: SessionSnapshot) {
    deepFreeze(this.snapshot);
    Object.freeze(this);
  }

  static open(
    command: OpenSession,
    accountState: { status: AccountStatus },
    now: Date = new Date(),
  ): { session: Session; event: SessionOpened } {
    requireNonBlank(command.accountId, "accountId");

    if (accountState.status === "Closed") {
      throw new DomainError("ACCOUNT_CLOSED", `Cannot open session for closed account ${command.accountId}`);
    }

    const sessionId = command.sessionId ?? generateSessionId();
    requireNonBlank(sessionId, "sessionId");

    // Sessions expire after 24 hours by default
    const expiresAt = new Date(now.getTime() + 24 * 60 * 60 * 1000);

    const snapshot: SessionSnapshot = deepFreeze({
      sessionId,
      accountId: command.accountId,
      status: "Active",
      createdAt: now,
      expiresAt,
    });

    const event: SessionOpened = deepFreeze({
      type: "SessionOpened",
      occurredAt: now,
      sessionId,
      accountId: command.accountId,
      correlationId: command.correlationId,
    });

    return { session: new Session(snapshot), event };
  }

  revoke(command: RevokeSession): { session: Session; event: SessionRevoked } {
    if (this.snapshot.status !== "Active") {
      throw new DomainError("SESSION_NOT_ACTIVE", `Session ${this.snapshot.sessionId} in status ${this.snapshot.status} cannot be revoked`);
    }
    if (command.accountId !== this.snapshot.accountId) {
      throw new DomainError("ACCOUNT_MISMATCH", `Session ${this.snapshot.sessionId} does not belong to account ${command.accountId}`);
    }

    const snapshot: SessionSnapshot = deepFreeze({
      ...this.snapshot,
      status: "Revoked",
      revokedAt: new Date(),
      revokeReason: command.reason,
    });

    const event: SessionRevoked = deepFreeze({
      type: "SessionRevoked",
      occurredAt: new Date(),
      sessionId: this.snapshot.sessionId,
      accountId: this.snapshot.accountId,
      reason: command.reason,
      correlationId: command.correlationId,
    });

    return { session: new Session(snapshot), event };
  }

  isExpired(at: Date = new Date()): boolean {
    return at.getTime() >= this.snapshot.expiresAt.getTime();
  }

  /** Returns true if this session can be used for transactional actions. */
  canAct(at: Date = new Date()): boolean {
    return this.snapshot.status === "Active" && !this.isExpired(at);
  }

  get id(): SessionId {
    return this.snapshot.sessionId;
  }

  get status(): SessionStatus {
    return this.snapshot.status;
  }

  get accountId(): AccountId {
    return this.snapshot.accountId;
  }

  toSnapshot(): SessionSnapshot {
    return deepFreeze({ ...this.snapshot });
  }
}

// ──────────────────────────────────────────────
// Preference aggregate
// ──────────────────────────────────────────────

export class Preference {
  private constructor(private readonly snapshot: PreferenceSnapshot) {
    deepFreeze(this.snapshot);
    Object.freeze(this);
  }

  static fromSnapshot(snapshot: PreferenceSnapshot): Preference {
    return new Preference(deepFreeze({ ...snapshot }));
  }

  static update(
    command: UpdatePreference,
    existingPreference?: PreferenceSnapshot,
  ): { preference: Preference; event: PreferenceUpdated } {
    requireNonBlank(command.accountId, "accountId");
    requireNonBlank(command.preferenceKey, "preferenceKey");
    requireNonBlank(command.value, "value");

    const oldValue = existingPreference?.value;
    const preferenceId = existingPreference?.preferenceId ?? generatePreferenceId();
    const now = new Date();

    const snapshot: PreferenceSnapshot = deepFreeze({
      preferenceId,
      accountId: command.accountId,
      key: command.preferenceKey,
      value: command.value,
      updatedAt: now,
    });

    const event: PreferenceUpdated = deepFreeze({
      type: "PreferenceUpdated",
      occurredAt: now,
      accountId: command.accountId,
      preferenceKey: command.preferenceKey,
      oldValue,
      newValue: command.value,
      correlationId: command.correlationId,
    });

    return { preference: new Preference(snapshot), event };
  }

  get id(): PreferenceId {
    return this.snapshot.preferenceId;
  }

  get key(): string {
    return this.snapshot.key;
  }

  get value(): string {
    return this.snapshot.value;
  }

  toSnapshot(): PreferenceSnapshot {
    return deepFreeze({ ...this.snapshot });
  }
}

// ──────────────────────────────────────────────
// AccountClosureSaga aggregate (shell)
// ──────────────────────────────────────────────

export class AccountClosureSaga {
  private constructor(private readonly snapshot: AccountClosureSagaSnapshot) {
    deepFreeze(this.snapshot);
    Object.freeze(this);
  }

  static start(command: StartAccountClosure): { saga: AccountClosureSaga } {
    const closureRequestId = command.closureRequestId ?? generateClosureRequestId();
    requireNonBlank(closureRequestId, "closureRequestId");
    requireNonBlank(command.accountId, "accountId");

    const snapshot: AccountClosureSagaSnapshot = deepFreeze({
      closureRequestId,
      accountId: command.accountId,
      status: "PendingVerification",
      startedAt: new Date(),
    });

    return { saga: new AccountClosureSaga(snapshot) };
  }

  /**
   * Verifies that the account has no in-flight orders or refunds.
   * Returns the verification result. In a full implementation this would
   * query downstream contexts; here it's a shell that accepts a result.
   */
  verify(
    inFlightOrdersExist: boolean,
    inFlightRefundsExist: boolean,
  ): { saga: AccountClosureSaga; canProceed: boolean } {
    if (this.snapshot.status !== "PendingVerification") {
      throw new DomainError("SAGA_NOT_PENDING", `Closure saga ${this.snapshot.closureRequestId} in status ${this.snapshot.status} cannot verify`);
    }

    let verificationResult: "verified" | "in-flight-orders-exist" | "in-flight-refunds-exist";
    let canProceed: boolean;
    let failureReason: string | undefined;

    if (inFlightOrdersExist && inFlightRefundsExist) {
      verificationResult = "in-flight-orders-exist";
      canProceed = false;
      failureReason = "Account has in-flight orders and refunds";
    } else if (inFlightOrdersExist) {
      verificationResult = "in-flight-orders-exist";
      canProceed = false;
      failureReason = "Account has in-flight orders";
    } else if (inFlightRefundsExist) {
      verificationResult = "in-flight-refunds-exist";
      canProceed = false;
      failureReason = "Account has in-flight refunds";
    } else {
      verificationResult = "verified";
      canProceed = true;
    }

    const snapshot: AccountClosureSagaSnapshot = deepFreeze({
      ...this.snapshot,
      verificationResult,
      failureReason: canProceed ? undefined : failureReason,
      status: canProceed ? "Completed" : "Failed",
      completedAt: canProceed ? new Date() : undefined,
    });

    return { saga: new AccountClosureSaga(snapshot), canProceed };
  }

  get status(): ClosureSagaStatus {
    return this.snapshot.status;
  }

  get closureRequestId(): ClosureRequestId {
    return this.snapshot.closureRequestId;
  }

  toSnapshot(): AccountClosureSagaSnapshot {
    return deepFreeze({ ...this.snapshot });
  }
}
