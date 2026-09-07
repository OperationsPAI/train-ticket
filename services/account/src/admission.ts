import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { headerValue, type HeaderBag } from "@trainticket/ts-kit";

/**
 * The identity API's admission for new accounts.
 *
 * `POST /api/v1/accounts` is the platform's cheapest write and its only
 * unauthenticated one: every journey begins with it, and the callers that reach
 * it are exactly the callers whose identity the platform has not established
 * yet. Nothing else is reachable before a caller has an account, so an unbounded
 * registration path is a path where one client's rate is the platform's rate --
 * and 2026-09-06 showed what one client's own retry storm does to it: 36,567
 * transport errors were callers retrying this endpoint against a pod that
 * answered 503.
 *
 * The budget is a per-client count over a fixed window, held in the service's
 * own store (`registration_admissions`). A count kept in the process would admit
 * one budget per replica; a count kept in the store is one budget for the
 * platform, which is what a limit on new accounts has to mean.
 *
 * The numbers are deliberately not here. They are the deployment's statement
 * about what the platform accepts, so they sit in
 * `config/registration-admission.json` beside the service, where they can be
 * read without reading code:
 *
 *   windowSeconds            length of the window the count is kept over
 *   maxNewAccountsPerClient  new accounts one client may open inside one window
 *   clientHeader             header the edge names the client in
 *
 * A settings file that is missing or malformed is a startup failure rather than
 * a default: a service that cannot state its own admission would admit
 * everything.
 */

export type RegistrationAdmissionSettings = Readonly<{
  /** Length of the window the count is kept over, in seconds. */
  windowSeconds: number;
  /** New accounts one client may open inside one window. */
  maxNewAccountsPerClient: number;
  /** Header the edge names the client in; the peer address is used when it sends none. */
  clientHeader: string;
}>;

/**
 * Whether one client may open another account in the window `at` falls in.
 *
 * The settings travel with the admission rather than with the caller so that the
 * window a request is counted in and the window its refusal names are the same
 * numbers, whichever store the count is kept in.
 */
export type RegistrationAdmission = Readonly<{
  settings: RegistrationAdmissionSettings;
  admit: (clientKey: string, at?: Date) => Promise<boolean>;
}>;

export const REGISTRATION_ADMISSION_FILE = "registration-admission.json";

const SETTINGS_DIRECTORY = join(dirname(fileURLToPath(import.meta.url)), "..", "config");

let cached: RegistrationAdmissionSettings | undefined;

export function registrationAdmissionSettings(): RegistrationAdmissionSettings {
  cached ??= readSettings();
  return cached;
}

/**
 * Which client a request is counted against.
 *
 * The edge that terminates the connection names the client it saw, and that name
 * is what the budget is kept for; a request that arrives without one is counted
 * against the address it came from. Nothing in the service can tell a declared
 * client from a forged one, which is why the budget is an admission budget and
 * not an identity check.
 */
export function clientKeyFor(headers: HeaderBag, address: string | undefined, settings: RegistrationAdmissionSettings): string {
  const declared = headerValue(headers[settings.clientHeader])?.split(",")[0]?.trim();
  return declared || address || "unknown";
}

/** Start of the window `at` falls in, for a window of `windowSeconds` seconds. */
export function windowStart(at: Date, windowSeconds: number): Date {
  const widthMs = windowSeconds * 1000;
  return new Date(Math.floor(at.getTime() / widthMs) * widthMs);
}

/**
 * The budget for a service that has no store to count it in.
 *
 * Used by the tests and by a process started without `DATABASE_URL`, where the
 * accounts themselves live in memory too, so the count and the accounts it
 * counts expire together.
 */
export class InMemoryRegistrationAdmission implements RegistrationAdmission {
  private readonly counts = new Map<string, Readonly<{ windowStartMs: number; asked: number }>>();

  constructor(readonly settings: RegistrationAdmissionSettings) {}

  async admit(clientKey: string, at: Date = new Date()): Promise<boolean> {
    const windowStartMs = windowStart(at, this.settings.windowSeconds).getTime();
    const seen = this.counts.get(clientKey);
    const asked = seen && seen.windowStartMs === windowStartMs ? seen.asked + 1 : 1;
    this.counts.set(clientKey, { windowStartMs, asked });
    return asked <= this.settings.maxNewAccountsPerClient;
  }
}

function readSettings(): RegistrationAdmissionSettings {
  const path = join(SETTINGS_DIRECTORY, REGISTRATION_ADMISSION_FILE);
  let parsed: unknown;
  try {
    parsed = JSON.parse(readFileSync(path, "utf8"));
  } catch (error) {
    throw new Error(`registration admission settings are unreadable at ${path}: ${describe(error)}`);
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error(`registration admission settings at ${path} are not an object`);
  }
  const record = parsed as Record<string, unknown>;
  return {
    windowSeconds: positiveInteger(record.windowSeconds, "windowSeconds", path),
    maxNewAccountsPerClient: positiveInteger(record.maxNewAccountsPerClient, "maxNewAccountsPerClient", path),
    clientHeader: nonEmptyString(record.clientHeader, "clientHeader", path).toLowerCase(),
  };
}

function positiveInteger(value: unknown, field: string, path: string): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 1) {
    throw new Error(`registration admission settings at ${path} need ${field} to be a positive integer`);
  }
  return value;
}

function nonEmptyString(value: unknown, field: string, path: string): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error(`registration admission settings at ${path} need ${field} to be a non-empty string`);
  }
  return value.trim();
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
