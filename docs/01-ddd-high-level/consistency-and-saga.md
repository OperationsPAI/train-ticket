# Transactions and workflow consistency

Last updated: 2026-09-24.

## Transaction boundaries

Each owning service commits its business record and outgoing events in a PostgreSQL transaction.
Redis publication and downstream consumption occur after that commit.
A successful command response can therefore precede downstream projection visibility.
Unrelated event types can be acknowledged without business changes; relevant duplicate events must preserve their original effects.

HTTP replay records and business deduplication serve different lifetimes.
Wallet compensation additionally deduplicates by case and the full issuance request.
Retention indexes accelerate existing cleanup predicates without extending the retained event history.

## Recorded workflows

| Workflow | Durable evidence | Completion check |
|---|---|---|
| Purchase | Accepted Offer, order, booking, payment and ticket references. | Required confirmation facts and actual issued tickets. |
| Refund/cancellation | Scoped decision, released reservations and payment refund records. | Case result plus settled refund and original right cancellation. |
| Change | Target Offer and `changeOperations`. | Replacement issuance, original release, actual fare difference and order application. |
| Wallet compensation | `compensationOperations.request`, `issued`, `verified`. | Approved amount in the order account wallet, no case refund and preserved original rights. |
| Recovery | Original report, case and selected option. | The selected operation's actual outcome and required financial settlement. |
| Loadgen continuation | Purchase and operation checkpoints beside `run.state_file`. | Same operation and identifiers survive process restart. |

The change executor submits all replacement reservations before waiting on confirmation.
It records replacement tickets before original ticket void operations.
Eligibility, target validity, sold out capacity and payment outcomes are verified using owning service data.

## Visibility and concurrency

Post Sales performs external policy reads outside write transactions and commits a decision after rechecking current case state.
Its capacity event stream has a separate subscriber pool.
Journey Order, Post Sales and Traveler Profile use virtual HTTP threads while retaining database connection limits.
Invoicing loads command context using its transaction connection and serializes projection writes by order.

Notification's deployed path still sends SMTP while holding its recipient transaction.
The unpublished queue implementation separates claim, sending and result recording, and treats uncertain sending as `Unknown`.
That worktree implementation is not deployed or fully accepted.

## Recovery limits

Short lived outbox and processed event records are not a complete archive.
The current Redis deployment has no persistent transport volume.
Replay beyond retained records requires a verified source and explicit recovery procedure.
No rollback or replay may repeat a completed refund, invalidate an original ticket for wallet compensation, or treat an uncertain send as delivery success.
Acceptance gaps are tracked only in [TODO](../TODO.md).
