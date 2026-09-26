import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * The membership programme this release is built against.
 *
 * Member relations exports the programme on its own cadence, and a service is
 * released against one revision of that export. The revision the settings were
 * taken from and the statement an enrolment's account reference is held to are
 * the programme's decisions rather than this service's, so they travel as data
 * in `config/membership-programme.json` beside the compiled code, which the
 * image carries, and they are read once here.
 *
 * `accountReference.acceptedNamespaces` is the statement an enrolment's account
 * reference is held to. It ships empty, which accepts a reference as the caller
 * sends it; a deployment that states the namespaces its references arrive in
 * lists them there.
 *
 * A settings file that is missing, malformed or incomplete fails the read: an
 * enrolment keyed by a statement nobody made is worse than one not served.
 */
export type AccountReferenceStatement = Readonly<{
  source: string;
  acceptedNamespaces: readonly string[];
}>;

export type MembershipProgramme = Readonly<{
  programme: string;
  revision: string;
  exportedBy: string;
  accountReference: AccountReferenceStatement;
}>;

const SETTINGS_PATH = join(dirname(fileURLToPath(import.meta.url)), "..", "config", "membership-programme.json");

let cached: MembershipProgramme | undefined;

/** The programme this release carries, read from the settings file beside the code. */
export function membershipProgramme(): MembershipProgramme {
  cached ??= readProgramme();
  return cached;
}

/** The statement an enrolment's account reference is held to. */
export function accountReferenceStatement(): AccountReferenceStatement {
  return membershipProgramme().accountReference;
}

/**
 * Whether the statement holds an account reference to the namespaces it lists.
 * An empty statement accepts the reference as the caller sends it.
 */
export function accountReferenceAccepted(accountId: string): boolean {
  const accepted = accountReferenceStatement().acceptedNamespaces;
  return accepted.length === 0 || accepted.some((namespace) => accountId.startsWith(`${namespace}-`) || accountId.startsWith(`${namespace}_`));
}

function readProgramme(): MembershipProgramme {
  const settings = objectField(parseSettings(readSettingsText()), "the programme");
  const accountReference = objectField(settings.accountReference, "accountReference");
  return Object.freeze({
    programme: stringField(settings.programme, "programme"),
    revision: stringField(settings.revision, "revision"),
    exportedBy: stringField(settings.exportedBy, "exportedBy"),
    accountReference: Object.freeze({
      source: stringField(accountReference.source, "accountReference.source"),
      acceptedNamespaces: Object.freeze(stringListField(accountReference.acceptedNamespaces, "accountReference.acceptedNamespaces")),
    }),
  });
}

function readSettingsText(): string {
  try {
    return readFileSync(SETTINGS_PATH, "utf8");
  } catch (error) {
    throw new Error(`membership-programme.json could not be read at ${SETTINGS_PATH}: ${describe(error)}`);
  }
}

function parseSettings(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error(`membership-programme.json is not valid JSON: ${describe(error)}`);
  }
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function objectField(value: unknown, label: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(`membership-programme.json: ${label} must be an object`);
  }
  return value as Record<string, unknown>;
}

function stringField(value: unknown, label: string): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error(`membership-programme.json: ${label} must be a non-empty string`);
  }
  return value;
}

function stringListField(value: unknown, label: string): readonly string[] {
  if (!Array.isArray(value)) {
    throw new Error(`membership-programme.json: ${label} must be an array`);
  }
  return value.map((entry, index) => stringField(entry, `${label}[${index}]`));
}
