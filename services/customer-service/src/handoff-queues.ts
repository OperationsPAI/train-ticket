/**
 * The queues the support desk runs.
 *
 * Where a case is handed on is a deployment's arrangement rather than the service's: a desk opens
 * a lane and states it in the release, without waiting for a build. The handoff guard therefore
 * takes a queue only when the release names it, so that a case cannot be filed in a lane no
 * console reads.
 *
 * A release that names no queue keeps the arrangement every release so far has run, where the
 * queue the caller gives is taken as it stands. Blank counts as nothing named, so a release that
 * has not decided its desk's queues is not one that refuses every handoff.
 *
 * The statement is read per handoff rather than cached at start-up: it carries three names at
 * most, and a lane a release adds answers handoffs without a restart of the running process.
 */

export const CASE_OWNER_QUEUES_SETTING = "CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES";

/** The queue names a release's statement carries, in the order it wrote them. */
export function statedOwnerQueues(
  stated: string | undefined = process.env[CASE_OWNER_QUEUES_SETTING],
): readonly string[] {
  return (stated ?? "")
    .split(",")
    .map((name) => name.trim())
    .filter((name) => name.length > 0);
}

/** Whether the desk runs `ownerQueue`, given the release's statement. */
export function deskRunsQueue(ownerQueue: string, stated?: string): boolean {
  const named = statedOwnerQueues(stated);
  return named.length === 0 || named.includes(ownerQueue);
}
