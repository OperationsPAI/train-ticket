import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { type CaseChannel } from "./domain.js";

/**
 * The intake desk that works each channel's cases.
 *
 * The platform publishes both vocabularies together: the channel name a caller files a support
 * case under, and the desk that works the cases filed that way. A deployment runs some of those
 * desks, so what its intake answers is the channels whose desk it has on duty.
 */
export const intakeDesks: Readonly<Record<CaseChannel, string>> = {
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
 * The platform's vocabulary for a support case.
 *
 * Wider than any one deployment: it is the set of names the contract documents, and what this
 * process answers is that set narrowed to the desks its sheet has on duty.
 */
export const platformChannels: readonly CaseChannel[] = [
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
 * The settings sheet the image ships beside the service.
 *
 * `desks` names the intake desks this release has on duty, as the desk tool publishes them. The
 * intake answers a channel when the desk that owns it is one of them.
 */
export type DeskSheet = Readonly<{
  desks?: readonly string[];
}>;

/**
 * Where the sheet sits relative to this module.
 *
 * One step up and across: the compiled module lands in `dist/` and the sheet ships in `config/`,
 * both beside the service's own directory.
 */
export function deskSheetPath(): string {
  return join(dirname(fileURLToPath(import.meta.url)), "../config/intake-desks.json");
}

/**
 * The sheet at `path`, or `undefined` when it is absent or is not a JSON object.
 *
 * A settings file that did not travel with a release is not an error: the platform's vocabulary is
 * what such a process answers, which is what it answered before the sheet existed.
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
 * The channels this deployment answers, in the platform's order.
 *
 * Called once per process, where the module that owns the route loads: a sheet that moved while
 * the process serves would let two replicas of one release answer different channels, so the value
 * is resolved once and every route that validates a channel reads the same list.
 */
export function intakeChannels(sheet: DeskSheet | undefined): readonly CaseChannel[] {
  const answered = desksOnDuty(sheet);
  return answered.length > 0 ? answered : platformChannels;
}

/**
 * The channels whose intake desk the sheet has on duty.
 *
 * A channel the sheet does not route, and one whose desk the sheet names differently, is left out.
 * A sheet that names no desk of this release answers nothing here, which leaves the whole
 * vocabulary to the caller: the file decides the intake, and a file that says nothing about the
 * desks changes nothing.
 */
function desksOnDuty(sheet: DeskSheet | undefined): CaseChannel[] {
  const listed = sheet?.desks;
  if (!Array.isArray(listed)) {
    return [];
  }
  const onDuty = new Set(listed.filter((desk): desk is string => typeof desk === "string"));
  return platformChannels.filter((channel) => onDuty.has(intakeDesks[channel]));
}
