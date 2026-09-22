/**
 * The desk's roster for this release.
 *
 * Which queues the desk's consoles poll is the deployment's arrangement rather
 * than the service's, so a release staffs a lane by naming it under
 * `CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES` and the process keeps that roster
 * for as long as it runs.
 *
 * A handoff names the queue the work should wait in. A queue no console polls
 * leaves the case in a lane nobody reads, so the roster in force is what a
 * handoff is checked against. A release that states no roster keeps the
 * arrangement every release before it ran, where the queue the caller names is
 * taken as it is -- blank counts as none, so a release that has not written the
 * key is not a release that refuses every handoff.
 */

import { DomainError } from "./domain.js";

const STATED_OWNER_QUEUES = process.env.CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES;

/** The queues one stated roster carries, or `undefined` when it carries none. */
export function statedOwnerQueues(value: string | undefined): readonly string[] | undefined {
  if (value === undefined) return undefined;
  const queues = splitStatedQueues(value);
  return queues.length > 0 ? Object.freeze(queues) : undefined;
}

/** The names one comma-separated roster carries, the blank ones dropped. */
function splitStatedQueues(value: string): readonly string[] {
  return value
    .split(",")
    .map((queue) => queue.trim())
    .filter((queue) => queue.length > 0);
}

const RELEASE_ROSTER = statedOwnerQueues(STATED_OWNER_QUEUES);

/** True when this release staffs the queue, or when it states no roster to go by. */
export function deskStaffsQueue(ownerQueue: string): boolean {
  return RELEASE_ROSTER === undefined || RELEASE_ROSTER.includes(ownerQueue);
}

/** The queues this release staffs, or `undefined` while it states none. */
export function staffedOwnerQueues(): readonly string[] | undefined {
  return RELEASE_ROSTER;
}

/** Refuses a handoff to a queue the release's roster does not staff. */
export function assertHandoffQueueIsStaffed(caseId: string, ownerQueue: string): void {
  if (deskStaffsQueue(ownerQueue)) return;
  throw new DomainError(
    "CASE_NOT_ROUTABLE",
    `SupportCase ${caseId} cannot be handed to ${ownerQueue}: the release's roster does not staff that queue`,
  );
}
