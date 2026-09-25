/**
 * The desk arrangement this release runs under.
 *
 * Which queues the desk's consoles poll is a deployment's arrangement rather
 * than the service's, so the release names them in
 * `CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES` and the service reads the names
 * from there. The release values reach the container through
 * `services.customer-service.env` in the chart, so the process starts holding
 * the arrangement its release states.
 *
 * A handoff names the queue the work should wait in. Naming one no console
 * polls files the case in a lane nobody reads, so the queues the release states
 * are the ones a handoff may name. A release that states none keeps the
 * arrangement the releases before it ran, where the queue the caller names is
 * taken as it is; blank counts as none, so a release that has not written the
 * key is not a release that refuses every handoff.
 */
import { DomainError } from "./domain.js";

export const DESK_SETTINGS = Object.freeze({
  /** The queues the release's desk polls; empty when the release names none. */
  assignedOwnerQueues: assignedOwnerQueues(process.env.CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES),
});

/** Refuses a handoff to a queue the release's desk does not poll. */
export function assertHandoffQueueIsPolled(caseId: string, ownerQueue: string): void {
  const queues = DESK_SETTINGS.assignedOwnerQueues;
  if (queues.length === 0 || queues.includes(ownerQueue)) {
    return;
  }
  throw new DomainError(
    "CASE_NOT_ROUTABLE",
    `SupportCase ${caseId} cannot be handed to ${ownerQueue}: the desk does not poll that queue`,
  );
}

/** The names one comma-separated setting carries. */
function assignedOwnerQueues(stated: string | undefined): readonly string[] {
  const queues = (stated ?? "")
    .split(",")
    .map((queue) => queue.trim())
    .filter((queue) => queue.length > 0);
  return Object.freeze(queues);
}
