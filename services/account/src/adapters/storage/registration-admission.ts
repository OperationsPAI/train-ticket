import { type Database } from "@trainticket/ts-kit";

import { windowStart, type RegistrationAdmission, type RegistrationAdmissionSettings } from "../../admission.js";

/**
 * The new-account budget, counted where the accounts are.
 *
 * One row per client: the window the row was last counted in and how many new
 * accounts that client has asked for inside it. What is counted is requests
 * rather than accounts, so a client that has spent its window and keeps asking
 * stays refused for the rest of it -- that is the behaviour the endpoint wants
 * under a retry storm, which is the thing the budget exists for. A new account
 * that was admitted and replayed under the same Idempotency-Key never reaches
 * this counter, because the idempotent operation answers the replay itself.
 *
 * A row for a window that has turned is overwritten by the client's next
 * request, so the table is bounded by the clients the platform has seen and not
 * by time, and no sweep has to keep it honest.
 */
export class PostgresRegistrationAdmission implements RegistrationAdmission {
  constructor(
    private readonly db: Database,
    readonly settings: RegistrationAdmissionSettings,
  ) {}

  async admit(clientKey: string, at: Date = new Date()): Promise<boolean> {
    const result = await this.db.query<{ attempts: number }>(
      `INSERT INTO registration_admissions (client_key, window_start, attempts)
       VALUES ($1, $2, 1)
       ON CONFLICT (client_key) DO UPDATE
         SET attempts = CASE
                          WHEN registration_admissions.window_start = EXCLUDED.window_start
                          THEN registration_admissions.attempts + 1
                          ELSE 1
                        END,
             window_start = EXCLUDED.window_start,
             updated_at = now()
       RETURNING attempts`,
      [clientKey, windowStart(at, this.settings.windowSeconds)],
    );
    return Number(result.rows[0]?.attempts ?? 0) <= this.settings.maxNewAccountsPerClient;
  }
}
