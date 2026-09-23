# Implemented state model references

Last updated: 2026-09-24.

Service states are separate and do not imply each other's completion.
Internal enums and HTTP representations may differ; serializers in the owning service define the public response.

| Object | Current state source | Public reference |
|---|---|---|
| JourneyOrder | `OrderLifecycleState` in `services/journey-order`. | [Journey Order API](../08-contracts/api/journey-order.md) |
| SegmentBooking | `SegmentBookingStatus` in `services/booking-orchestration`. | [Booking API](../08-contracts/api/booking-orchestration.md) |
| CapacityHold | `CapacityHoldState` in `services/capacity-availability/src/domain.rs`. | [Capacity API](../08-contracts/api/capacity-availability.md) |
| Seat allocation | Allocation constants in `services/seat-assignment/internal/domain/seatmap_contract.go`. | [Seat API](../08-contracts/api/seat-assignment.md) |
| PaymentIntent | `CREATED, AUTHORIZED, CAPTURED, FAILED, CANCELLED, EXPIRED`. | [Payment API](../08-contracts/api/payment.md) |
| Refund | `REQUESTED, SUBMITTED, SETTLED, FAILED, MANUAL_REVIEW_REQUIRED`. | [Payment API](../08-contracts/api/payment.md) |
| Entitlement | `EntitlementStatus` in `services/entitlement-ticketing`. | [Ticketing API](../08-contracts/api/entitlement-ticketing.md) |
| PostSalesCase | `PostSalesCaseStatus` and `PostSalesMapper.contractStatus`. | [Post Sales API](../08-contracts/api/post-sales.md) |
| PromotionInstrument | `PromotionStatus` in `services/wallet-promotion`. | [Wallet API](../08-contracts/api/wallet-promotion.md) |
| NotificationTask | Domain snapshot and receipt state in `services/notification/src/domain.ts`. | [Notification API](../08-contracts/api/notification.md) |

## Post Sales

The internal case includes `OPENED`, eligibility checking, quoted/user confirmation/approval states, `EXECUTING`, `APPLIED`, rejected/failed/cancelled and manual review states.
Public details report an executing approved case as `APPROVED`.
Wallet compensation becomes `APPLIED` only after its persisted issuance evidence is verified and both execution steps succeed.
Refund settlement and ticket changes still require their independent business checks.

## Loadgen checkpoints

| Status | Meaning |
|---|---|
| `post_sales_pending` | Resume the original unfinished request. |
| `compensation_credited` | Wallet credit verified; original purchase may proceed to fulfillment. |
| `compensation_failed` | Terminal mismatch evidence retained; no automatic post sales, recovery or fulfillment selection. |
| `change_failed` | The checked change failure preserves usable original rights. |
| `changed` | Fulfillment uses recorded replacement tickets. |

## Notification workspace boundary

The unpublished durable queue introduces `Ready`, `Claimed`, `Sending`, `Unknown`, `Delivered` and `Failed`.
A send with unknown external outcome is not automatically replayed.
The deployed release remains on the existing sender until the worktree implementation passes the checks in [TODO](../TODO.md).
