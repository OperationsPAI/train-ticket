/**
 * The desk lanes this release staffs.
 *
 * A case handed to an owner queue is picked up by the console that polls that queue, and which
 * queues a deployment's desk runs is the deployment's arrangement rather than the service's. The
 * release therefore states them in `CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES`, and a handoff is
 * resolved against that statement, so a queue no lane covers is turned away rather than filed where
 * nobody reads it.
 *
 * A release that states no lane keeps the arrangement the service ships, where the queue the
 * caller names is taken as it is; blank counts as nothing stated. The statement is read per handoff
 * rather than cached at start-up, so a lane one release opens is pollable without waiting for a
 * restart of the process that answered the handoff.
 */
export const DESK_LANES_SETTING = "CUSTOMER_SERVICE_CASE_ASSIGN_OWNER_QUEUES";

/** The owner queues this release's desk staffs, or undefined when the release states none. */
export function staffedLanes(
  stated: string | undefined = process.env[DESK_LANES_SETTING],
): readonly string[] | undefined {
  const lanes = (stated ?? "")
    .split(",")
    .map((lane) => lane.trim())
    .filter((lane) => lane.length > 0);
  return lanes.length === 0 ? undefined : lanes;
}

/**
 * The lane a handoff to `ownerQueue` is filed in, or undefined when no staffed lane covers it.
 *
 * The lanes the release states are the whole of what its desk runs, so the resolution is made from
 * the statement alone; the service's own arrangement applies only while the release states none.
 */
export function resolveHandoffLane(
  ownerQueue: string,
  stated: string | undefined = process.env[DESK_LANES_SETTING],
): string | undefined {
  const lanes = staffedLanes(stated);
  if (lanes === undefined) {
    return ownerQueue;
  }
  return lanes.includes(ownerQueue) ? ownerQueue : undefined;
}
