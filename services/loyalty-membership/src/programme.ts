import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { readJsonSettings } from "@trainticket/ts-kit";

/**
 * The membership programme, as member relations states it.
 *
 * The poles and what each of them asks for, the pole a membership opens at and
 * the account references an enrolment keys by are the programme's decisions,
 * not this service's: they move when the programme moves, and every service
 * that reads them has to see one answer. They are stated in
 * `config/membership-programme.json`, which the image carries beside the code,
 * and read once here.
 *
 * `openingPole` is the pole a new membership opens at. `exportedNames` maps the
 * names the member-relations export uses onto the poles of the ladder, because
 * the export names the opening pole BASIC where the ladder calls it SILVER; a
 * name the export does not list is a pole name already. `accountReference`
 * states the namespaces an enrolment keys by: an empty list accepts the
 * reference as the caller sends it, which is what the programme ships with.
 *
 * A file that is missing, malformed or incomplete fails startup rather than
 * serving enrolments from a value nobody stated.
 */
export type ProgrammePole = Readonly<{
  name: string;
  qualifyingPoints: number;
  trips: number;
  benefits: readonly string[];
}>;

export type MembershipProgramme = Readonly<{
  programme: string;
  revision: string;
  source: string;
  openingPole: string;
  exportedNames: Readonly<Record<string, string>>;
  acceptedNamespaces: readonly string[];
  poles: readonly ProgrammePole[];
}>;

const PROGRAMME_DIRECTORY = join(dirname(fileURLToPath(import.meta.url)), "..", "config");
const PROGRAMME_FILE = "membership-programme.json";

let loaded: MembershipProgramme | undefined;

export function membershipProgramme(): MembershipProgramme {
  loaded ??= readProgramme();
  return loaded;
}

/** The pole a membership opens at, with the export's name resolved to a pole of the ladder. */
export function openingPole(): string {
  const programme = membershipProgramme();
  return programme.exportedNames[programme.openingPole] ?? programme.openingPole;
}

function readProgramme(): MembershipProgramme {
  const raw = readJsonSettings(PROGRAMME_DIRECTORY, PROGRAMME_FILE);
  const poles = arrayField(raw.poles, "poles").map((entry, index) => readPole(entry, `poles[${index}]`));
  if (poles.length === 0) {
    throw new Error(`${PROGRAMME_FILE}: poles is empty`);
  }
  const accountReference = objectField(raw.accountReference, "accountReference");
  return Object.freeze({
    programme: stringField(raw.programme, "programme"),
    revision: stringField(raw.revision, "revision"),
    source: stringField(raw.source, "source"),
    openingPole: stringField(raw.openingPole, "openingPole"),
    exportedNames: stringMapField(raw.exportedNames, "exportedNames"),
    acceptedNamespaces: stringListField(accountReference.acceptedNamespaces, "accountReference.acceptedNamespaces"),
    poles: Object.freeze(poles),
  });
}

function readPole(entry: unknown, label: string): ProgrammePole {
  const pole = objectField(entry, label);
  return Object.freeze({
    name: stringField(pole.name, `${label}.name`),
    qualifyingPoints: integerField(pole.qualifyingPoints, `${label}.qualifyingPoints`),
    trips: integerField(pole.trips, `${label}.trips`),
    benefits: Object.freeze(stringListField(pole.benefits, `${label}.benefits`)),
  });
}

function objectField(value: unknown, label: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(`${PROGRAMME_FILE}: ${label} must be an object`);
  }
  return value as Record<string, unknown>;
}

function arrayField(value: unknown, label: string): readonly unknown[] {
  if (!Array.isArray(value)) {
    throw new Error(`${PROGRAMME_FILE}: ${label} must be an array`);
  }
  return value;
}

function stringField(value: unknown, label: string): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error(`${PROGRAMME_FILE}: ${label} must be a non-empty string`);
  }
  return value;
}

function integerField(value: unknown, label: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw new Error(`${PROGRAMME_FILE}: ${label} must be a whole non-negative number`);
  }
  return value;
}

function stringListField(value: unknown, label: string): readonly string[] {
  return arrayField(value, label).map((entry, index) => stringField(entry, `${label}[${index}]`));
}

function stringMapField(value: unknown, label: string): Readonly<Record<string, string>> {
  const entries = objectField(value, label);
  const mapped: Record<string, string> = {};
  for (const [key, entry] of Object.entries(entries)) {
    mapped[stringField(key, `${label} key`)] = stringField(entry, `${label}.${key}`);
  }
  return Object.freeze(mapped);
}
