import type { CaseChannel } from "./domain.js";

/**
 * The channels a support case may be opened on.
 *
 * These eight names are the platform's vocabulary for a case channel. Which of
 * them one deployment answers is a routing decision the desk makes rather than
 * a code decision the service makes, so `CUSTOMER_SERVICE_INTAKE_CHANNELS`
 * narrows the intake to the channels the release's queues own: a channel no
 * queue answers would otherwise accept a case that then sits unassigned.
 *
 * The variable is read once per process. Unset or blank keeps the whole
 * vocabulary, which is what every deployment released so far answers. A name
 * outside the vocabulary fails here, at start-up, rather than refusing one
 * channel's cases in production.
 */
const knownChannels = [
  "APP",
  "WEB",
  "PHONE",
  "IM",
  "EMAIL",
  "IN_APP_MESSAGE",
  "BOT",
  "OPERATOR_CONSOLE",
] as const;

export type IntakeChannel = (typeof knownChannels)[number];

export function intakeChannels(
  configured: string | undefined = process.env.CUSTOMER_SERVICE_INTAKE_CHANNELS,
): readonly CaseChannel[] {
  const named = (configured ?? "")
    .split(",")
    .map((channel) => channel.trim())
    .filter((channel) => channel.length > 0);
  if (named.length === 0) {
    return knownChannels;
  }
  return named.map((channel) => {
    if (!isIntakeChannel(channel)) {
      throw new Error(`CUSTOMER_SERVICE_INTAKE_CHANNELS names an unknown channel: ${channel}`);
    }
    return channel;
  });
}

function isIntakeChannel(channel: string): channel is IntakeChannel {
  return (knownChannels as readonly string[]).includes(channel);
}
