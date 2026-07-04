# Agentic Development Methodology

This document records the operating model for using AgentM WorkGraph and ARL
agent environments to advance this repository. It is intentionally
methodological: keep run-specific traces, PR numbers, image digests, and
incident details in WorkGraph results or external operations logs.

## Purpose

Use the agentic loop for bounded, reviewable infrastructure and service
foundation work where the task can be expressed as a small repository slice
with clear ownership, validation, and merge criteria.

The goal is not to let an agent drift through the repository. The goal is to
turn each slice into a queued work item, run it in an isolated worker
environment, verify it independently, and merge it back into the base branch as
soon as it is proven.

## WorkGraph Scheduling

Treat `.agentm/workgraph` as the control plane for active repository work.

- `ready` contains tasks that are eligible to run.
- `running` contains tasks currently owned by a worker.
- `verified` contains deliveries accepted by an independent verifier.
- `merging` contains deliveries currently being reconciled with the remote base.
- `done` contains work already merged into the base branch.
- `failed` and `conflicts` are recovery queues, not disposal queues.

Each task should declare its repository, base branch, locks, expected scope, and
validation commands. Locks are the concurrency contract: tasks that touch the
same runtime, service family, or shared platform area should not run together.

Prefer a narrow queue and frequent merges over broad parallelism. Parallelism is
useful only when locks and validation boundaries are clean. When the base branch
is changing quickly, run fewer tasks concurrently and merge verified work
immediately.

## Agent Environment Configuration

Run coding and verification inside ARL `agent_env` sandboxes built from the
repository development container. The worker image should include the same
language toolchains and operational CLIs expected by CI and local development.

The WorkGraph configuration should identify:

- the canonical repository remote;
- the authoritative base branch;
- the agent environment backend;
- the worker image;
- the local or remote gateway endpoint;
- the maximum safe development concurrency.

The sandbox must have enough capability to clone, test, commit, push a delivery
branch, and open or update a pull request. Credentials are environment
capabilities, not documentation content: never record raw tokens in task files,
docs, traces copied into docs, or PR descriptions.

## Development Iteration

Each task follows the same lifecycle.

1. Claim one ready task and move it to `running`.
2. Start a fresh sandbox or attach to the task sandbox.
3. Clone the canonical remote and check out the authoritative base.
4. Create a task branch.
5. Implement only the declared slice.
6. Run the task's focused validation first.
7. Run the repository-level validation gate required by the task.
8. Commit the delivery.
9. Rebase the delivery branch onto the latest remote base.
10. Push the rebased branch and open or update the pull request.
11. Submit a structured result that includes status, branch, commit, remote,
    pull request, validation evidence, and residual risk.

A local commit is not a delivery. A successful coder result requires a pushed
remote branch or pull request that another worker can fetch and verify.

## Verification

Verification is independent from coding. The verifier should fetch the pushed
delivery from the remote, run the declared validation, inspect the relevant
diff, and decide whether the task is accepted.

The verifier should not rely on the coder's working tree or uncommitted files.
It may reuse the same sandbox for efficiency, but the evidence should come from
the remote delivery branch.

If validation is incomplete, ambiguous, or blocked by infrastructure, keep the
task out of `done`. Record the blocker and return the task to an appropriate
recovery queue.

## Merge Discipline

Merge verified work quickly. The base branch is the coordination point for all
subsequent agents, so stale verified branches create avoidable conflicts and
incorrect assumptions.

The merger should:

- fetch the latest remote base before merging;
- treat `origin/<base>` as authoritative;
- rebase the delivery branch on that base;
- push the rebased delivery branch with lease protection;
- merge the pull request with rebase semantics;
- fetch the remote base again after the merge;
- report the commit that is visible on the remote base branch.

After a successful merge, update the local repository to the new remote base
before scheduling the next task. This keeps host inspection, subsequent
WorkGraph runs, and sandbox clones aligned.

## Recovery

Failures should be classified before retrying.

- Environment failures include missing gateway configuration, unavailable
  sandbox images, registry access problems, or resource pressure.
- Delivery failures include missing pushes, missing pull requests, validation
  failures, or incomplete task scope.
- Merge failures include stale branches, base conflicts, missing permissions,
  or remote branch state that no longer matches the verified delivery.

For environment failures, fix the environment first and then return the task to
`ready` with a concise recovery note. For delivery failures, preserve the result
and retry only after the next attempt has a concrete correction. For merge
failures, do not mark the task `done` until the remote base branch visibly
contains the delivery.

## Operating Principles

- Keep task scope smaller than the validation boundary.
- Prefer deterministic repository checks over subjective inspection.
- Preserve domain behavior while standardizing runtime infrastructure.
- Use the devcontainer as the source of truth for worker tooling.
- Avoid long-lived unmerged verified branches.
- Keep WorkGraph state, repository base, and remote pull requests synchronized.
- Document methods in repository docs; keep run evidence in WorkGraph results.
