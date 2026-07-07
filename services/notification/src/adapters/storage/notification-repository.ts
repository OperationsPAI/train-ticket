import { type PoolClient, type QueryResult } from "pg";

import { SnapshotRepository, type SnapshotRecord } from "@trainticket/ts-kit";

import { type NotificationTaskSnapshot } from "../../domain.js";
import { type UserPreferenceRepository } from "../../application/notification-service.js";

export type PersistedNotificationTask = SnapshotRecord<NotificationTaskSnapshot>;

export class PostgresNotificationTaskRepository {
  constructor(private readonly client: PoolClient) {}

  async get(notificationTaskId: string): Promise<PersistedNotificationTask | undefined> {
    return new SnapshotRepository<NotificationTaskSnapshot>(this.client, "notification_task_snapshots").get(notificationTaskId);
  }

  async saveNew(snapshot: NotificationTaskSnapshot): Promise<PersistedNotificationTask> {
    return new SnapshotRepository<NotificationTaskSnapshot>(this.client, "notification_task_snapshots").save(snapshot.notificationTaskId, serializeSnapshot(snapshot));
  }

  async save(snapshot: NotificationTaskSnapshot, expectedVersion: bigint): Promise<PersistedNotificationTask> {
    return new SnapshotRepository<NotificationTaskSnapshot>(this.client, "notification_task_snapshots").save(snapshot.notificationTaskId, serializeSnapshot(snapshot), expectedVersion);
  }
}

export class PostgresUserPreferenceRepository implements UserPreferenceRepository {
  constructor(private readonly client: PoolClient) {}

  async isEnabled(recipientRef: string, intent: string, channel: string): Promise<boolean> {
    const result = await this.client.query(
      `SELECT enabled
       FROM user_preferences
       WHERE recipient_ref = $1 AND intent = $2 AND channel = $3`,
      [recipientRef, intent, channel],
    ) as QueryResult<{ enabled: boolean }>;
    return result.rows[0]?.enabled ?? true;
  }
}

export async function listInAppNotifications(client: PoolClient, recipientRef: string, limit = 50): Promise<NotificationTaskSnapshot[]> {
  const cappedLimit = Math.max(1, Math.min(limit, 100));
  const result = await client.query(
    `SELECT data
     FROM notification_task_snapshots
     WHERE data->>'recipientRef' = $1 AND data->>'channel' = 'IN_APP'
     ORDER BY updated_at DESC
     LIMIT $2`,
    [recipientRef, cappedLimit],
  ) as QueryResult<{ data: NotificationTaskSnapshot }>;
  return result.rows.map((row) => deserializeSnapshot(row.data));
}

function serializeSnapshot(snapshot: NotificationTaskSnapshot): NotificationTaskSnapshot {
  return JSON.parse(JSON.stringify(snapshot)) as NotificationTaskSnapshot;
}

function deserializeSnapshot(snapshot: NotificationTaskSnapshot): NotificationTaskSnapshot {
  return snapshot;
}
