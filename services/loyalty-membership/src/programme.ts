import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * The membership programme, as member relations exports it.
 *
 * The programme moves on its own cadence and this service is released against one
 * revision of it. Which namespaces an enrolment's account reference may arrive in
 * is the programme's decision rather than the caller's, so it is stated in
 * `config/membership-programme.json`, which the image carries beside the code, and
 * read once here.
 *
 * `accountReference.acceptedNamespaces` is the statement an enrolment's account
 * reference is held to. It is empty as the programme ships it, which accepts a
 * reference as the caller sends it; a deployment that states the namespaces its
 * references arrive in lists them there.
 *
 * A file that is missing, malformed or incomplete fails the read rather than
 * letting an enrolment be keyed by a statement nobody made.
 */
export type AccountReferenceStatement = Readonly<{
  acceptedNamespaces: readonly string[];
}>;

export type MembershipProgramme = Readonly<{
  programme: string;
  revision: string;
  exportedBy: string;
  accountReference: AccountReferenceStatement;
}>;

const SETTINGS_DIRECTORY = join(dirname(fileURLToPath(import.meta.url)), "..", "config");
const SETTINGS_FILE = "membership-programme.json";

let loaded: MembershipProgramme | undefined;

/** The programme this release carries, read from the settings file beside the code. */
export function membershipProgramme(): MembershipProgramme {
  loaded ??= readProgramme();
  return loaded;
}

/** The statement an enrolment's account reference is held to. */
export function accountReferenceStatement(): AccountReferenceStatement {
  return membershipProgramme().accountReference;
}

function readProgramme(): MembershipProgramme {
  const path = join(SETTINGS_DIRECTORY, SETTINGS_FILE);
  const settings = objectField(readJson(path), "the settings file");
  const accountReference = objectField(settings.accountReference, "accountReference");
  return Object.freeze({
    programme: stringField(settings.programme, "programme"),
    revision: stringField(settings.revision, "revision"),
    exportedBy: stringField(settings.exportedBy, "exportedBy"),
    accountReference: Object.freeze({
      acceptedNamespaces: Object.freeze(stringListField(accountReference.acceptedNamespaces, "accountReference.acceptedNamespaces")),
    }),
  });
}

function readJson(path: string): unknown {
  let text: string;
  try {
    text = readFileSync(path, "utf8");
  } catch (error) {
    throw new Error(`${SETTINGS_FILE} could not be read at ${path}: ${describe(error)}`);
  }
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error(`${SETTINGS_FILE} is not valid JSON: ${describe(error)}`);
  }
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function objectField(value: unknown, label: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(`${SETTINGS_FILE}: ${label} must be an object`);
  }
  return value as Record<string, unknown>;
}

function stringField(value: unknown, label: string): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error(`${SETTINGS_FILE}: ${label} must be a non-empty string`);
  }
  return value;
}

function stringListField(value: unknown, label: string): readonly string[] {
  if (!Array.isArray(value)) {
    throw new Error(`${SETTINGS_FILE}: ${label} must be an array`);
  }
  return value.map((entry, index) => stringField(entry, `${label}[${index}]`));
}
