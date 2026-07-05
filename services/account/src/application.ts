import {
  AccountClosureSaga,
  DomainError,
  Preference,
  UserAccount,
  type AccountDomainEvent,
  type AccountId,
  type PreferenceSnapshot,
  type UserAccountSnapshot,
} from "./domain.js";
import { toEventEnvelope, type EventEnvelope, type EventPublisher } from "./ports.js";
import { newCommandId } from "@trainticket/ts-kit";

export type AccountStatusDto = "ACTIVE" | "FROZEN" | "CLOSURE_PENDING" | "CLOSED";

export type AccountDetailsDto = Readonly<{
  accountId: string;
  status: AccountStatusDto;
  createdAt: string;
  frozenAt?: string;
  frozenReason?: string;
  frozenOperator?: string;
  frozenCaseRef?: string;
  closureRequestId?: string;
  closureStartedAt?: string;
  closedAt?: string;
  preferences: Record<string, string>;
}>;

export type AccountRepository = Readonly<{
  findAccount(accountId: AccountId): Promise<UserAccount | undefined>;
  saveAccount(account: UserAccount): Promise<void>;
  findPreference(accountId: AccountId, key: string): Promise<PreferenceSnapshot | undefined>;
  savePreference(preference: Preference): Promise<void>;
  preferencesFor(accountId: AccountId): Promise<PreferenceSnapshot[]>;
}>;

export class InMemoryAccountRepository implements AccountRepository {
  private readonly accounts = new Map<AccountId, UserAccount>();
  private readonly preferences = new Map<string, PreferenceSnapshot>();

  async findAccount(accountId: AccountId): Promise<UserAccount | undefined> {
    return this.accounts.get(accountId);
  }

  async saveAccount(account: UserAccount): Promise<void> {
    this.accounts.set(account.id, account);
  }

  async findPreference(accountId: AccountId, key: string): Promise<PreferenceSnapshot | undefined> {
    return this.preferences.get(preferenceStorageKey(accountId, key));
  }

  async savePreference(preference: Preference): Promise<void> {
    const snapshot = preference.toSnapshot();
    this.preferences.set(preferenceStorageKey(snapshot.accountId, snapshot.key), snapshot);
  }

  async preferencesFor(accountId: AccountId): Promise<PreferenceSnapshot[]> {
    return [...this.preferences.values()].filter((preference) => preference.accountId === accountId);
  }
}

export { InMemoryEventPublisher } from "@trainticket/ts-kit";

export class AccountApplicationService {
  constructor(
    private readonly repository: AccountRepository,
    private readonly publisher: EventPublisher,
  ) {}

  async createAccount(command: { accountId?: string; correlationId: string; causationId?: string }): Promise<AccountDetailsDto> {
    const { account, event } = UserAccount.create({ accountId: command.accountId, correlationId: command.correlationId });
    if (await this.repository.findAccount(account.id)) {
      throw new ApplicationError("CONFLICT", `Account ${account.id} already exists`, 409);
    }
    await this.repository.saveAccount(account);
    await this.publish(event, command);
    return this.details(account);
  }

  async getAccount(accountId: string): Promise<AccountDetailsDto> {
    return this.details(await this.requireAccount(accountId));
  }

  async freezeAccount(command: {
    accountId: string;
    reason: string;
    operator: string;
    caseRef?: string;
    correlationId: string;
    causationId?: string;
  }): Promise<Readonly<{ accountId: string; status: "FROZEN"; frozenAt: string }>> {
    const account = await this.requireAccount(command.accountId);
    const { account: frozen, event } = account.freeze(command);
    await this.repository.saveAccount(frozen);
    await this.publish(event, command);
    const snapshot = frozen.toSnapshot();
    return { accountId: frozen.id, status: "FROZEN", frozenAt: requiredDate(snapshot.frozenAt).toISOString() };
  }

  async unfreezeAccount(command: {
    accountId: string;
    reason: string;
    correlationId: string;
    causationId?: string;
  }): Promise<Readonly<{ accountId: string; status: "ACTIVE" }>> {
    const account = await this.requireAccount(command.accountId);
    const { account: active, event } = account.unfreeze(command);
    await this.repository.saveAccount(active);
    await this.publish(event, command);
    return { accountId: active.id, status: "ACTIVE" };
  }

  async updatePreference(command: {
    accountId: string;
    preferenceKey: string;
    value: string;
    correlationId: string;
    causationId?: string;
  }): Promise<AccountDetailsDto> {
    const account = await this.requireAccount(command.accountId);
    const existing = await this.repository.findPreference(command.accountId, command.preferenceKey);
    const { preference, event } = Preference.update(command, existing);
    await this.repository.savePreference(preference);
    await this.publish(event, command);
    return this.details(account);
  }

  async startClosure(command: {
    accountId: string;
    correlationId: string;
    causationId?: string;
  }): Promise<Readonly<{ accountId: string; closureRequestId: string; status: "CLOSURE_INITIATED" }>> {
    const account = await this.requireAccount(command.accountId);
    const { account: pending, event } = account.startClosure(command);
    AccountClosureSaga.start({ accountId: pending.id, closureRequestId: event.closureRequestId, correlationId: command.correlationId });
    await this.repository.saveAccount(pending);
    await this.publish(event, command);
    return { accountId: pending.id, closureRequestId: event.closureRequestId, status: "CLOSURE_INITIATED" };
  }

  private async requireAccount(accountId: string): Promise<UserAccount> {
    const account = await this.repository.findAccount(accountId);
    if (!account) {
      throw new ApplicationError("NOT_FOUND", `Account ${accountId} was not found`, 404);
    }
    return account;
  }

  private async details(account: UserAccount): Promise<AccountDetailsDto> {
    const snapshot = account.toSnapshot();
    const preferences = Object.fromEntries((await this.repository.preferencesFor(account.id)).map((preference) => [preference.key, preference.value]));
    return accountDetails(snapshot, preferences);
  }

  private async publish(event: AccountDomainEvent, command: { correlationId: string; causationId?: string }): Promise<void> {
    await this.publisher.publish(toEventEnvelope(event, command.correlationId, command.causationId));
  }
}

export class ApplicationError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly statusCode: number,
    public readonly details: Record<string, unknown> = {},
  ) {
    super(message);
    this.name = "ApplicationError";
  }
}

export type DomainErrorMapping = Readonly<{
  preconditionDomainCodes?: readonly string[] | "all";
}>;

export function mapError(error: unknown, mapping: DomainErrorMapping = {}): ApplicationError {
  if (error instanceof ApplicationError) {
    return error;
  }
  if (error instanceof DomainError) {
    if (error.code === "MISSING_REQUIRED_FIELD") {
      return new ApplicationError("VALIDATION_FAILED", error.message, 400, { domainCode: error.code });
    }
    if (isPreconditionFailure(error.code, mapping)) {
      return new ApplicationError("PRECONDITION_FAILED", error.message, 412, { domainCode: error.code });
    }
    return new ApplicationError("DOMAIN_RULE_VIOLATION", error.message, 422, { domainCode: error.code });
  }
  return new ApplicationError("UNAVAILABLE", "The account service is temporarily unavailable", 503);
}

function isPreconditionFailure(domainCode: string, mapping: DomainErrorMapping): boolean {
  return mapping.preconditionDomainCodes === "all" || mapping.preconditionDomainCodes?.includes(domainCode) === true;
}

function accountDetails(snapshot: UserAccountSnapshot, preferences: Record<string, string>): AccountDetailsDto {
  return {
    accountId: snapshot.accountId,
    status: accountStatusDto(snapshot.status),
    createdAt: snapshot.createdAt.toISOString(),
    frozenAt: snapshot.frozenAt?.toISOString(),
    frozenReason: snapshot.frozenReason,
    frozenOperator: snapshot.frozenOperator,
    frozenCaseRef: snapshot.frozenCaseRef,
    closureRequestId: snapshot.closureRequestId,
    closureStartedAt: snapshot.closureStartedAt?.toISOString(),
    closedAt: snapshot.closedAt?.toISOString(),
    preferences,
  };
}

function accountStatusDto(status: UserAccountSnapshot["status"]): AccountStatusDto {
  switch (status) {
    case "Active":
      return "ACTIVE";
    case "Frozen":
      return "FROZEN";
    case "ClosurePending":
      return "CLOSURE_PENDING";
    case "Closed":
      return "CLOSED";
  }
}

function preferenceStorageKey(accountId: string, key: string): string {
  return `${accountId}:${key}`;
}

function requiredDate(value: Date | undefined): Date {
  if (!value) {
    throw new ApplicationError("UNAVAILABLE", "Expected timestamp was not recorded", 503);
  }
  return value;
}

export const commandId = newCommandId;
