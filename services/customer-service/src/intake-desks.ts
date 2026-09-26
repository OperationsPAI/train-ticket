import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { type CaseChannel } from "./domain.js";

/**
 * Which intake desk works each channel a support case may be filed under.
 *
 * The contract carries two vocabularies side by side: the channel a caller files a case under, and
 * the desk that picks the case up. Both names belong to the platform, and the pairing between them
 * is a rule of the intake rather than a setting, so it is stated here once and every route that has
 * to name a desk reads it from here.
 */
export const intakeDeskOfChannel: Readonly<Record<CaseChannel, string>> = {
  APP: "mobile-desk",
  WEB: "web-desk",
  PHONE: "phone-desk",
  IM: "chat-desk",
  EMAIL: "mail-desk",
  IN_APP_MESSAGE: "message-desk",
  BOT: "bot-desk",
  OPERATOR_CONSOLE: "console-desk",
};

/**
 * The channels the contract defines, in the order it lists them.
 *
 * This is the platform's whole vocabulary, wider than any one release. What an intake answers is
 * this list narrowed to the desks its release runs, and a release that ships no roster answers all
 * of it, which is what every release answered before the roster existed.
 */
export const contractChannels: readonly CaseChannel[] = [
  "APP",
  "WEB",
  "PHONE",
  "IM",
  "EMAIL",
  "IN_APP_MESSAGE",
  "BOT",
  "OPERATOR_CONSOLE",
];

/**
 * The roster of intake desks, as a release ships it beside the service.
 *
 * `desks` names the intake desks this release runs, under the queue names the desk tool publishes
 * for them. A field the roster leaves out is absent rather than wrong, so a roster that names no
 * desk at all is a release that answers the platform's whole vocabulary.
 */
export type DeskSheet = Readonly<{
  note?: string;
  source?: string;
  desks?: readonly string[];
}>;

/**
 * Where the roster sits beside the compiled module.
 *
 * `dist/` and `config/` are siblings in the installed image, so one step up and across from this
 * module is the file the image was built with.
 */
export function deskSheetPath(): string {
  return join(dirname(fileURLToPath(import.meta.url)), "../config/intake-desks.json");
}

/**
 * The roster at `path`, or `undefined` when there is not one to read.
 *
 * A release whose roster did not travel with it is not a broken release: it answers the channels
 * the contract names, which is what it answered before the roster existed. A missing or unreadable
 * file is therefore `undefined` rather than a start-up failure.
 */
export function readDeskSheet(path: string = deskSheetPath()): DeskSheet | undefined {
  try {
    const parsed: unknown = JSON.parse(readFileSync(path, "utf8"));
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
      return undefined;
    }
    return parsed as DeskSheet;
  } catch {
    return undefined;
  }
}

/**
 * The channels whose intake desk the roster runs.
 *
 * A channel the release runs no desk for, and one whose desk the roster spells under a different
 * name, is left out of what this intake answers.
 */
export function desksOnDuty(sheet: DeskSheet | undefined): CaseChannel[] {
  const listed = sheet?.desks;
  if (!Array.isArray(listed)) {
    return [];
  }
  const onDuty = new Set(listed.filter((desk): desk is string => typeof desk === "string"));
  return contractChannels.filter((channel) => onDuty.has(intakeDeskOfChannel[channel]));
}

/**
 * The channels this deployment answers, in the contract's order.
 *
 * Resolved once, where the module that owns the routes loads. A roster that moved while the process
 * serves would let two replicas of one release answer different channels, so every route that
 * validates a channel reads this one list.
 */
export function intakeChannels(sheet: DeskSheet | undefined): readonly CaseChannel[] {
  const onDuty = desksOnDuty(sheet);
  return onDuty.length > 0 ? onDuty : contractChannels;
}
