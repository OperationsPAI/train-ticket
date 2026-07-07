import { type PoolClient, type QueryResult } from "pg";

import { SnapshotRepository, type SnapshotRecord } from "@trainticket/ts-kit";

import { Preference, UserAccount, type PreferenceSnapshot, type UserAccountSnapshot } from "../../domain.js";
import { type AccountRepository } from "../../application.js";

export class PostgresAccountRepository implements AccountRepository {
  private readonly loadedAccountVersions = new Map<string, bigint | undefined>();

  constructor(private readonly client: PoolClient) {}

  async findAccount(accountId: string): Promise<UserAccount | undefined> {
    const record = await new SnapshotRepository<StoredUserAccountSnapshot>(this.client, "account_snapshots").get(accountId);
    this.loadedAccountVersions.set(accountId, record?.version);
    return record ? UserAccount.fromSnapshot(reviveUserAccount(record.data)) : undefined;
  }

  async saveAccount(account: UserAccount): Promise<void> {
    const snapshot = account.toSnapshot();
    const expectedVersion = this.loadedAccountVersions.get(snapshot.accountId);
    const saved = await saveAccountSnapshot(this.client, snapshot, expectedVersion);
    this.loadedAccountVersions.set(snapshot.accountId, saved.version);
  }

  async findPreference(accountId: string, key: string): Promise<PreferenceSnapshot | undefined> {
    const result = await this.client.query(
      `SELECT data FROM account_preferences WHERE account_id = $1 AND preference_key = $2`,
      [accountId, key],
    ) as QueryResult<{ data: StoredPreferenceSnapshot }>;
    const row = result.rows[0];
    return row ? revivePreference(row.data) : undefined;
  }

  async savePreference(preference: Preference): Promise<void> {
    const snapshot = preference.toSnapshot();
    await this.client.query(
      `INSERT INTO account_preferences (account_id, preference_key, data)
       VALUES ($1, $2, $3)
       ON CONFLICT (account_id, preference_key)
       DO UPDATE SET data = EXCLUDED.data, updated_at = now()`,
      [snapshot.accountId, snapshot.key, serializeSnapshot(snapshot)],
    );
  }

  async preferencesFor(accountId: string): Promise<PreferenceSnapshot[]> {
    const result = await this.client.query(
      `SELECT data FROM account_preferences WHERE account_id = $1 ORDER BY preference_key`,
      [accountId],
    ) as QueryResult<{ data: StoredPreferenceSnapshot }>;
    return result.rows.map((row) => revivePreference(row.data));
  }
}

export async function saveAccountSnapshot(client: PoolClient, snapshot: UserAccountSnapshot, expectedVersion?: bigint): Promise<SnapshotRecord<StoredUserAccountSnapshot>> {
  return new SnapshotRepository<StoredUserAccountSnapshot>(client, "account_snapshots").save(snapshot.accountId, serializeStoredSnapshot(snapshot), expectedVersion);
}

type StoredUserAccountSnapshot = Omit<UserAccountSnapshot, "createdAt" | "frozenAt" | "closureStartedAt" | "closedAt"> & Readonly<{
  createdAt: string;
  frozenAt?: string;
  closureStartedAt?: string;
  closedAt?: string;
}>;

type StoredPreferenceSnapshot = Omit<PreferenceSnapshot, "updatedAt"> & Readonly<{ updatedAt: string }>;

function serializeSnapshot<T>(snapshot: T): T {
  return JSON.parse(JSON.stringify(snapshot)) as T;
}

function serializeStoredSnapshot(snapshot: UserAccountSnapshot): StoredUserAccountSnapshot {
  return serializeSnapshot(snapshot) as unknown as StoredUserAccountSnapshot;
}

function reviveUserAccount(snapshot: StoredUserAccountSnapshot): UserAccountSnapshot {
  return {
    ...snapshot,
    createdAt: new Date(snapshot.createdAt),
    frozenAt: snapshot.frozenAt ? new Date(snapshot.frozenAt) : undefined,
    closureStartedAt: snapshot.closureStartedAt ? new Date(snapshot.closureStartedAt) : undefined,
    closedAt: snapshot.closedAt ? new Date(snapshot.closedAt) : undefined,
  };
}

function revivePreference(snapshot: StoredPreferenceSnapshot): PreferenceSnapshot {
  return { ...snapshot, updatedAt: new Date(snapshot.updatedAt) };
}
