/**
 * The queues this deployment's desk polls.
 *
 * Which queues a desk staffs is the deployment's arrangement rather than the
 * service's, so the release states the lanes it staffs under
 * `services.customer-service.env` as `CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES`
 * and the service reads them from the process it was handed. A handoff to a
 * queue no console polls would leave the case with nobody owning it, so it is
 * turned away rather than accepted. A release that states no lane keeps the
 * arrangement every earlier release ran: every queue the caller names.
 */

const STATED_OWNER_QUEUES = process.env.CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES;

/**
 * Split the release's comma-separated statement into the queues it carries.
 * `undefined` means the release stated no lane, which is not the same as a
 * statement that names none of them.
 */
export function statedOwnerQueues(value: string | undefined): readonly string[] | undefined {
  if (value === undefined) return undefined;
  const queues = value
    .split(",")
    .map((queue) => queue.trim())
    .filter((queue) => queue.length > 0);
  return queues.length > 0 ? queues : undefined;
}

/** Every queue is polled while the release states none. */
export function queueIsPolled(stated: readonly string[] | undefined, ownerQueue: string): boolean {
  return stated === undefined || stated.includes(ownerQueue);
}

const polledOwnerQueues = statedOwnerQueues(STATED_OWNER_QUEUES);

/** True when this release polls the queue, or when it has no roster to go by. */
export function deskPollsQueue(ownerQueue: string): boolean {
  return queueIsPolled(polledOwnerQueues, ownerQueue);
}

/** The queues this release states, or `undefined` while it states none. */
export function releasedOwnerQueues(): readonly string[] | undefined {
  return polledOwnerQueues;
}
