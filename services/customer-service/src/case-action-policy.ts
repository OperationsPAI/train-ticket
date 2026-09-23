import type { SupportCaseStatus } from "./domain.js";

/**
 * The statuses each of the desk's case actions admits.
 *
 * The actions are the service's; which status an action takes is the desk's
 * working arrangement, so a deployment states it with
 * `CUSTOMER_SERVICE_CASE_ACTION_<ACTION>` instead of waiting for a build: a
 * queue that takes another status is a routing decision the release owns.
 *
 * Unset or blank keeps the table below, which is the arrangement every
 * deployment released so far runs. A name outside the status vocabulary fails
 * here, at start-up, rather than refusing one action's cases in production.
 * The statement is read once per process, so a value that changed under a
 * running process cannot leave one guard answering a different list from the
 * next.
 */
export const caseActions = ["classify", "assign", "escalate", "resolve", "close", "reopen"] as const;

export type CaseAction = (typeof caseActions)[number];

const statusVocabulary = [
  "Opened",
  "Classifying",
  "Assigned",
  "InProgress",
  "WaitingCustomer",
  "WaitingExternal",
  "Escalated",
  "Resolved",
  "Closed",
  "Reopened",
] as const satisfies readonly SupportCaseStatus[];

const statedArrangement: Readonly<Record<CaseAction, readonly SupportCaseStatus[]>> = {
  classify: ["Opened"],
  assign: ["Classifying", "Opened"],
  escalate: [
    "Opened",
    "Classifying",
    "Assigned",
    "InProgress",
    "WaitingExternal",
    "Reopened",
    "Escalated",
  ],
  resolve: [
    "Assigned",
    "InProgress",
    "WaitingCustomer",
    "WaitingExternal",
    "Escalated",
    "Reopened",
  ],
  close: ["Resolved", "Escalated", "Opened", "Classifying", "Reopened"],
  reopen: ["Closed", "Resolved"],
};

/** The variable a deployment states one action's statuses in. */
export function caseActionVariable(action: CaseAction): string {
  return `CUSTOMER_SERVICE_CASE_ACTION_${action.toUpperCase()}`;
}

/**
 * The statuses one action admits, from the statement the deployment carries.
 */
export function admittedStatuses(
  action: CaseAction,
  configured: string | undefined = process.env[caseActionVariable(action)],
): readonly SupportCaseStatus[] {
  const named = (configured ?? "")
    .split(",")
    .map((status) => status.trim())
    .filter((status) => status.length > 0);
  if (named.length === 0) {
    return statedArrangement[action];
  }
  return named.map((status) => {
    if (!isSupportCaseStatus(status)) {
      throw new Error(`${caseActionVariable(action)} names an unknown status: ${status}`);
    }
    return status;
  });
}

const kept = new Map<CaseAction, ReadonlySet<SupportCaseStatus>>();

/**
 * Whether one action takes a case in this status, against the list this
 * process kept at start-up.
 */
export function admitsCaseAction(action: CaseAction, status: SupportCaseStatus): boolean {
  let admitted = kept.get(action);
  if (admitted === undefined) {
    admitted = new Set(admittedStatuses(action));
    kept.set(action, admitted);
  }
  return admitted.has(status);
}

function isSupportCaseStatus(status: string): status is SupportCaseStatus {
  return (statusVocabulary as readonly string[]).includes(status);
}
