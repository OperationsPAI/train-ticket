import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { referenceArrivesIn } from "@trainticket/ts-kit";

/**
 * The membership programme, as member relations states it.
 *
 * The poles and what each of them asks for, the pole a membership opens at, the
 * names the member-relations export uses, the redemption rule and the namespaces
 * an enrolment's account reference may arrive in are the programme's decisions,
 * not this service's: they move when the programme moves, and every service that
 * reads them has to see one answer. They are stated in
 * `config/membership-programme.json`, which the image carries beside the code,
 * and read once here.
 *
 * `poles.opening` is the pole a membership opens at. `poles.exportNames` maps the
 * names the member-relations export uses onto the poles of the ladder, because
 * the export names the opening pole BASIC where the ladder calls it SILVER; a
 * name the export does not list is a pole name already.
 *
 * `accountReference.acceptedNamespaces` is the statement an enrolment's account
 * reference is held to. It is empty as the programme ships it, so a reference is
 * accepted as the caller sends it; a deployment that states the namespaces its
 * references arrive in lists them there.
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

export type ProgrammeRedemptionRule = Readonly<{
  pointsToCurrencyRate: number;
  minRedemption: number;
  maxFarePercentage: number;
}>;

export type MembershipProgramme = Readonly<{
  programme: string;
  revision: string;
  exportedBy: string;
  openingPole: string;
  exportedNames: Readonly<Record<string, string>>;
  acceptedNamespaces: readonly string[];
  poles: readonly ProgrammePole[];
  redemption: ProgrammeRedemptionRule;
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

/** The pole a name the export uses stands for, or undefined when the name is a pole already. */
export function exportedPoleName(name: string): string | undefined {
  return membershipProgramme().exportedNames[name];
}

/**
 * The namespaces an enrolment's account reference may arrive in. An empty list
 * accepts the reference as the caller sends it.
 */
export function acceptedAccountNamespaces(): readonly string[] {
  return membershipProgramme().acceptedNamespaces;
}

/** The redemption rule the programme states, as the domain's policy reads it. */
export function programmeRedemptionRule(): ProgrammeRedemptionRule {
  return membershipProgramme().redemption;
}

/**
 * Whether an enrolment's account reference is one the programme does not key by.
 *
 * An enrolment keys a membership by the account reference the caller sends, and
 * the ledger keeps that reference as it arrives. A reference from a namespace the
 * programme does not state is therefore refused where it is read, rather than
 * stored under a key the identity service would not resolve an account from. An
 * empty statement refuses nothing.
 */
export function enrolmentReferenceRefused(reference: string): boolean {
  const accepted = acceptedAccountNamespaces();
  return accepted.length > 0 && !referenceArrivesIn(reference, accepted);
}

function readProgramme(): MembershipProgramme {
  const raw = readSettingsFile();
  const poles = objectField(raw.poles, "poles");
  const redemption = objectField(raw.redemption, "redemption");
  const ladder = arrayField(poles.ladder, "poles.ladder").map((entry, index) => readPole(entry, `poles.ladder[${index}]`));
  if (ladder.length === 0) {
    throw new Error(`${PROGRAMME_FILE}: poles.ladder is empty`);
  }
  return Object.freeze({
    programme: stringField(raw.programme, "programme"),
    revision: stringField(raw.revision, "revision"),
    exportedBy: stringField(raw.exportedBy, "exportedBy"),
    openingPole: stringField(poles.opening, "poles.opening"),
    exportedNames: stringMapField(poles.exportNames, "poles.exportNames"),
    acceptedNamespaces: stringListField(objectField(raw.accountReference, "accountReference").acceptedNamespaces, "accountReference.acceptedNamespaces"),
    poles: Object.freeze(ladder),
    redemption: Object.freeze({
      pointsToCurrencyRate: positiveIntegerField(redemption.pointsPerCurrencyUnit, "redemption.pointsPerCurrencyUnit"),
      minRedemption: positiveIntegerField(redemption.minimumPoints, "redemption.minimumPoints"),
      maxFarePercentage: shareField(redemption.maxFareShare, "redemption.maxFareShare"),
    }),
  });
}

function readSettingsFile(): Record<string, unknown> {
  const path = join(PROGRAMME_DIRECTORY, PROGRAMME_FILE);
  let text: string;
  try {
    text = readFileSync(path, "utf8");
  } catch (error) {
    throw new Error(`${PROGRAMME_FILE} could not be read at ${path}: ${describe(error)}`);
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    throw new Error(`${PROGRAMME_FILE} is not valid JSON: ${describe(error)}`);
  }
  return objectField(parsed, "the settings file");
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

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
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

function positiveIntegerField(value: unknown, label: string): number {
  const whole = integerField(value, label);
  if (whole === 0) {
    throw new Error(`${PROGRAMME_FILE}: ${label} must be a positive number`);
  }
  return whole;
}

function shareField(value: unknown, label: string): number {
  if (typeof value !== "number" || !(value > 0) || value > 1) {
    throw new Error(`${PROGRAMME_FILE}: ${label} must be a share of one`);
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
