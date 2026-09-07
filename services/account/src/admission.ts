import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { headerValue, type HeaderBag } from "@trainticket/ts-kit";

/**
 * The admission the identity API answers new accounts on.
 *
 * `POST /api/v1/accounts` is the platform's only unauthenticated write and the
 * first call of every journey: nothing else is reachable before a caller holds
 * an account, and the callers that reach it are exactly the ones whose identity
 * the platform has not established. That makes it the one path where a single
 * client's retry rate is the platform's rate -- the 2026-09-06 bus outage left
 * 36,567 transport errors that were callers retrying this endpoint against a
 * pod answering 503.
 *
 * So the endpoint carries a per-client budget: a count of new accounts one
 * client may open inside a fixed window. The count is kept where the accounts
 * are (`registration_admissions`, migration 004) rather than in the process,
 * because a count in one replica's memory admits one budget per replica, and an
 * allowance on new accounts has to mean one allowance for the deployment.
 *
 * The numbers are the deployment's statement about what it serves, not the
 * mechanism's, so they are not here: they live in
 * `config/registration-admission.json` beside the service, where they can be
 * read and changed without reading the code that enforces them.
 *
 *   windowSeconds            length of the window one count is kept over
 *   maxNewAccountsPerClient  new accounts one client may open inside it
 *   clientHeader             header the edge names the client in
 *
 * A settings file that is missing or malformed is a startup failure rather than
 * a default: a service that cannot state what it admits would admit everything.
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
 * The admission for new accounts: the settings it was built from, and one
 * question -- may this client open another account now?
 *
 * The settings travel with the admission rather than with the caller so that
 * the window a request is counted in and the window its refusal names are the
 * same numbers, whichever store the count is kept in.
 */
export type RegistrationAdmission = Readonly<{
  settings: RegistrationAdmissionSettings;
  admit: (clientKey: string, at?: Date) => Promise<boolean>;
}>;

export const REGISTRATION_ADMISSION_FILE = "registration-admission.json";

const SETTINGS_DIRECTORY = join(dirname(fileURLToPath(import.meta.url)), "..", "config");

let loaded: RegistrationAdmissionSettings | undefined;

/** The deployment's statement about the endpoint, read once and kept. */
export function registrationAdmissionSettings(): RegistrationAdmissionSettings {
  loaded ??= loadAdmissionSettings();
  return loaded;
}

/**
 * Which client a request is counted against.
 *
 * The edge that terminates the connection is the only party that can say who it
 * saw, so it does: the first entry of the configured header names the client,
 * and a request that arrives without one is counted against the address it came
 * from. Nothing in the service can tell a declared client from an invented one,
 * which is why this is an admission budget and not an identity check.
 */
export function clientKeyFor(headers: HeaderBag, address: string | undefined, settings: RegistrationAdmissionSettings): string {
  const declared = headerValue(headers[settings.clientHeader])?.split(",")[0]?.trim();
  return declared || address || "unknown";
}

/** Start of the window `at` falls in, for a window `windowSeconds` seconds wide. */
export function windowStart(at: Date, windowSeconds: number): Date {
  const widthMs = windowSeconds * 1000;
  return new Date(Math.floor(at.getTime() / widthMs) * widthMs);
}

/**
 * The allowance for a process with no store to count it in: the tests, and a
 * service started without `DATABASE_URL`, where the accounts themselves are in
 * memory too, so the count and the accounts it counts expire together.
 */
export class InMemoryRegistrationAdmission implements RegistrationAdmission {
  private readonly counts = new Map<string, Readonly<{ windowStartMs: number; asked: number }>>();

  constructor(readonly settings: RegistrationAdmissionSettings) {}

  async admit(clientKey: string, at: Date = new Date()): Promise<boolean> {
    const windowStartMs = windowStart(at, this.settings.windowSeconds).getTime();
    const counted = this.counts.get(clientKey);
    const asked = counted && counted.windowStartMs === windowStartMs ? counted.asked + 1 : 1;
    this.counts.set(clientKey, { windowStartMs, asked });
    return asked <= this.settings.maxNewAccountsPerClient;
  }
}

function loadAdmissionSettings(): RegistrationAdmissionSettings {
  const path = join(SETTINGS_DIRECTORY, REGISTRATION_ADMISSION_FILE);
  let parsed: unknown;
  try {
    parsed = JSON.parse(readFileSync(path, "utf8"));
  } catch (error) {
    throw new Error(`registration admission settings are unreadable at ${path}: ${messageOf(error)}`);
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

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
