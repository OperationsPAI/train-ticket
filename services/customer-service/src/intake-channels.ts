import { readFileSync } from "node:fs";

import { type CaseChannel } from "./domain.js";

/**
 * The channels the intake answers are deployment data.
 *
 * The release ships the queues the desk behind it staffs beside the service, at
 * `/app/config/intake-channels.json`, and the process reads that file once at
 * start-up. `INTAKE_CHANNELS_FILE` overrides the path for a host whose layout
 * differs from the image's.
 *
 * The platform's vocabulary stays the built-in default: a release whose
 * settings did not travel with it answers every channel the contract documents,
 * which is what it answered before the list became data, rather than refusing
 * traffic it has no reason to refuse.
 */
export const PLATFORM_INTAKE_CHANNELS: readonly CaseChannel[] = [
  "APP",
  "WEB",
  "PHONE",
  "IM",
  "EMAIL",
  "IN_APP_MESSAGE",
  "BOT",
  "OPERATOR_CONSOLE",
];

export const DEFAULT_INTAKE_CHANNELS_FILE = "/app/config/intake-channels.json";

function isCaseChannel(value: unknown): value is CaseChannel {
  return typeof value === "string" && (PLATFORM_INTAKE_CHANNELS as readonly string[]).includes(value);
}

/**
 * The shape is `{"channels": ["WEB", ...]}` and nothing else is read from it.
 * A missing file, invalid JSON, an empty list, or a list whose members the
 * platform does not define all leave the platform's vocabulary in place: the
 * settings narrow what this deployment answers, they never widen the codes the
 * contracts and the clients already know.
 */
export function intakeChannelsFrom(file: string): readonly CaseChannel[] {
  let settings: unknown;
  try {
    settings = JSON.parse(readFileSync(file, "utf8"));
  } catch {
    return PLATFORM_INTAKE_CHANNELS;
  }
  const named = (settings as { channels?: unknown } | null)?.channels;
  const channels = Array.isArray(named) ? named.filter(isCaseChannel) : [];
  return channels.length > 0 ? channels : PLATFORM_INTAKE_CHANNELS;
}

export const intakeChannels: readonly CaseChannel[] = intakeChannelsFrom(
  process.env.INTAKE_CHANNELS_FILE ?? DEFAULT_INTAKE_CHANNELS_FILE,
);
