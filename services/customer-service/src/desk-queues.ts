/**
 * The queues the desk polls.
 *
 * A case handed to an owner queue is picked up when a console polls that queue, and which queues
 * the desk runs is a deployment's arrangement rather than the service's: a desk brings a queue up
 * without a build. The release therefore names them in
 * `CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES`, and the counter refuses a handoff to a name the
 * release does not carry, instead of filing the case in a lane nothing reads.
 *
 * Nothing stated keeps the arrangement every release so far has run, where the queue the caller
 * names is taken as it is. Blank counts as nothing stated, so a release that has not decided its
 * desk's queues is not a release that refuses every handoff.
 *
 * The statement is read per handoff rather than cached at start-up. It is a release setting that
 * holds three names at most, and a queue one release adds is pollable without waiting for a
 * restart of the process that answered the handoff.
 */
export const DESK_QUEUES_SETTING = "CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES";

/** Whether the desk polls `ownerQueue`, given the release's statement. */
export function deskPollsQueue(
  ownerQueue: string,
  stated: string | undefined = process.env[DESK_QUEUES_SETTING],
): boolean {
  const named = (stated ?? "")
    .split(",")
    .map((queue) => queue.trim())
    .filter((queue) => queue.length > 0);
  if (named.length === 0) {
    return true;
  }
  return named.includes(ownerQueue);
}
