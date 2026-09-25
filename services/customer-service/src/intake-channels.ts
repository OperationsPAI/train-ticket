import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { type CaseChannel } from "./domain.js";

/**
 * The platform's vocabulary for a support case.
 *
 * It is wider than any one desk: a case is worked from the intake queue that owns its channel, and
 * a deployment states the queues it staffs. What a process answers is that vocabulary narrowed to
 * the queues its settings name, which is why the list below is a default rather than the answer.
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
 * `routes` names the intake queue that works each channel's cases and `staffedQueues` names the
 * queues this deployment staffs. A desk works the channels whose queue it staffs, so the two
 * together are what the intake answers.
 */
export type IntakeSheet = Readonly<{
  routes?: Readonly<Record<string, string>>;
  staffedQueues?: readonly string[];
}>;

/**
 * Where the sheet sits relative to this module.
 *
 * One step up and across: the compiled module lands in `dist/` and the sheet ships in `config/`,
 * both beside the service's own directory.
 */
export function intakeSheetPath(): string {
  return join(dirname(fileURLToPath(import.meta.url)), "../config/intake-channels.json");
}

/**
 * The sheet at `path`, or `undefined` when it is absent or is not a JSON object.
 *
 * A settings file that did not travel with a release is not an error: the platform's vocabulary is
 * what such a process answers, which is what it answered before the setting existed.
 */
export function readIntakeSheet(path: string = intakeSheetPath()): IntakeSheet | undefined {
  try {
    const parsed: unknown = JSON.parse(readFileSync(path, "utf8"));
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
      return undefined;
    }
    return parsed as IntakeSheet;
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
export function intakeChannels(sheet: IntakeSheet | undefined): readonly CaseChannel[] {
  const answered = staffedChannels(sheet);
  return answered.length > 0 ? answered : platformChannels;
}

/**
 * The channels whose intake queue the sheet's desk staffs.
 *
 * A channel the sheet does not route, and one whose queue no entry of `staffedQueues` names, is
 * left out. A sheet that states no queue at all answers nothing here, which leaves the whole
 * vocabulary to the caller: the file decides the intake, and a file that says nothing about the
 * queues changes nothing.
 */
function staffedChannels(sheet: IntakeSheet | undefined): CaseChannel[] {
  const routes = sheet?.routes;
  const staffed = sheet?.staffedQueues;
  if (typeof routes !== "object" || routes === null || !Array.isArray(staffed) || staffed.length === 0) {
    return [];
  }
  const queues = new Set(staffed.filter((queue): queue is string => typeof queue === "string"));
  return platformChannels.filter((channel) => {
    const queue = routes[channel];
    return typeof queue === "string" && queues.has(queue);
  });
}
