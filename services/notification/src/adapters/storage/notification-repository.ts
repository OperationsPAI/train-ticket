import { type PoolClient, type QueryResult } from "pg";

import { SnapshotRepository, type SnapshotRecord } from "@trainticket/ts-kit";

import { type NotificationChannel, type NotificationTaskSnapshot } from "../../domain.js";
import { type ContactProfile, type RateLimitStore, type RecipientContactRepository, type UserPreferenceRepository } from "../../application/notification-service.js";

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


export class PostgresRecipientContactRepository implements RecipientContactRepository {
  constructor(private readonly client: PoolClient) {}

  async getContactProfile(recipientRef: string): Promise<ContactProfile> {
    const result = await this.client.query(
      `SELECT device_token, phone_number, email_address, preferred_channel
       FROM recipient_contacts
       WHERE recipient_ref = $1`,
      [recipientRef],
    ) as QueryResult<{ device_token: string | null; phone_number: string | null; email_address: string | null; preferred_channel: NotificationChannel | null }>;
    const row = result.rows[0];
    return {
      ...(row?.device_token ? { deviceToken: row.device_token } : {}),
      ...(row?.phone_number ? { phoneNumber: row.phone_number } : {}),
      emailAddress: row?.email_address ?? `${recipientRef}@passengers.train-ticket.local`,
      ...(row?.preferred_channel ? { preferredChannel: row.preferred_channel } : {}),
    };
  }
}

export class PostgresRateLimitRepository implements RateLimitStore {
  constructor(private readonly client: PoolClient) {}

  async checkAndRecord(recipientRef: string, channel: NotificationChannel, at: Date): Promise<{ allowed: true } | { allowed: false; retryAfter: Date }> {
    const limit = channelLimit(channel);
    const userResult = await this.client.query(
      `SELECT occurred_at
       FROM notification_rate_limits
       WHERE recipient_ref = $1 AND channel = $2 AND occurred_at > $3
       ORDER BY occurred_at ASC`,
      [recipientRef, channel, new Date(at.getTime() - limit.windowMs).toISOString()],
    ) as QueryResult<{ occurred_at: Date }>;
    if (userResult.rows.length >= limit.max) {
      return { allowed: false, retryAfter: new Date(userResult.rows[0].occurred_at.getTime() + limit.windowMs) };
    }

    if (channel === "SMS") {
      const globalResult = await this.client.query(
        `SELECT occurred_at
         FROM notification_rate_limits
         WHERE channel = 'SMS' AND occurred_at > $1
         ORDER BY occurred_at ASC`,
        [new Date(at.getTime() - 60_000).toISOString()],
      ) as QueryResult<{ occurred_at: Date }>;
      if (globalResult.rows.length >= 1000) {
        return { allowed: false, retryAfter: new Date(globalResult.rows[0].occurred_at.getTime() + 60_000) };
      }
    }

    await this.client.query(
      `INSERT INTO notification_rate_limits (recipient_ref, channel, occurred_at) VALUES ($1, $2, $3)`,
      [recipientRef, channel, at.toISOString()],
    );
    return { allowed: true };
  }
}

function channelLimit(channel: NotificationChannel): Readonly<{ max: number; windowMs: number }> {
  switch (channel) {
    case "PUSH":
      return { max: 10, windowMs: 60 * 60 * 1000 };
    case "SMS":
      return { max: 5, windowMs: 24 * 60 * 60 * 1000 };
    case "EMAIL":
      return { max: 20, windowMs: 24 * 60 * 60 * 1000 };
  }
}
