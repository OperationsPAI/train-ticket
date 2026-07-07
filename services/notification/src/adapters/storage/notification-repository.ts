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

function serializeSnapshot(snapshot: NotificationTaskSnapshot): NotificationTaskSnapshot {
  return JSON.parse(JSON.stringify(snapshot)) as NotificationTaskSnapshot;
}
