import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { type CaseChannel } from "./domain.js";

/**
 * The channels the intake answers.
 *
 * The platform's vocabulary for a support case is wider than the desks behind
 * it. A desk claims the queues it staffs and no others, so a case opened on a
 * channel no queue claims is stored and then waits for a queue that never claims
 * it. Which channels a deployment answers is therefore a statement about that
 * deployment rather than about the domain, and it lives in
 * `config/intake-channels.json` beside the service, read once at start-up:
 *
 *   channels   the channel names this deployment's intake answers
 *
 * The file ships the platform's whole vocabulary, which is what a release answers
 * until its desk states the channels it routes. A file that is missing, unreadable
 * or malformed states nothing and keeps that vocabulary, because the vocabulary is
 * the platform's contract and the settings are a narrowing of it. A name outside
 * the vocabulary stops the process where the typo can be seen instead of dropping
 * one channel's cases silently.
 */

/** The channels the platform defines for a support case, whatever a desk routes. */
export const PLATFORM_CHANNELS: readonly CaseChannel[] = [
  "APP",
  "WEB",
  "PHONE",
  "IM",
  "EMAIL",
  "IN_APP_MESSAGE",
  "BOT",
  "OPERATOR_CONSOLE",
];

export type IntakeSettings = Readonly<{
  /** The channel names this deployment's intake answers; empty keeps the vocabulary. */
  channels: readonly CaseChannel[];
}>;

export const INTAKE_SETTINGS_FILE = "intake-channels.json";

const SETTINGS_DIRECTORY = join(dirname(fileURLToPath(import.meta.url)), "..", "config");

let cached: readonly CaseChannel[] | undefined;

/** Where the service reads its own intake settings from. */
export function intakeSettingsFile(): string {
  return join(SETTINGS_DIRECTORY, INTAKE_SETTINGS_FILE);
}

/** The channels this deployment's intake answers, read from its own settings once. */
export function intakeChannels(): readonly CaseChannel[] {
  cached ??= intakeChannelsFor(readIntakeSettings());
  return cached;
}

/**
 * The channels a settings statement names, which is the platform's vocabulary
 * when it names none.
 *
 * A statement that names nothing is not an intake that answers nothing: the
 * vocabulary is what the platform defines and a release that has said nothing
 * about a desk answers all of it, as every release before this setting did.
 */
export function intakeChannelsFor(settings: IntakeSettings): readonly CaseChannel[] {
  return settings.channels.length > 0 ? settings.channels : PLATFORM_CHANNELS;
}

/**
 * The statement the service ships, read from the file beside it.
 *
 * A file that cannot be read, or does not hold a `channels` list, states nothing
 * rather than failing: the intake then answers the platform's vocabulary, which
 * is what the release did before it could state anything. A name inside the list
 * that the platform does not define is an error, because a release that answered
 * it by refusing it would lose those cases instead of failing where the name is
 * written.
 */
export function readIntakeSettings(file: string = intakeSettingsFile()): IntakeSettings {
  let raw: string;
  try {
    raw = readFileSync(file, "utf8");
  } catch {
    return { channels: [] };
  }
  let stated: unknown;
  try {
    stated = (JSON.parse(raw) as { channels?: unknown } | null)?.channels;
  } catch {
    return { channels: [] };
  }
  if (!Array.isArray(stated)) {
    return { channels: [] };
  }
  return { channels: stated.map((entry) => channelName(entry)) };
}

function channelName(entry: unknown): CaseChannel {
  if (typeof entry === "string" && (PLATFORM_CHANNELS as readonly string[]).includes(entry)) {
    return entry as CaseChannel;
  }
  throw new Error(`${INTAKE_SETTINGS_FILE}: not a channel of the platform: ${String(entry)}`);
}
