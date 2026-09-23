# Business implementation design

Last updated: 2026-09-24.

These documents describe implemented business rules and clearly identified workspace changes.
[Current status](../00-current-status.md) records deployment and verification; [TODO](../TODO.md) is the single remaining work list.

| Area | Current design |
|---|---|
| Refund, change and complaint compensation | [Post Sales](post-sales-refund-policy.md) |
| Physical allocation and standing | [Seat Assignment](seat-assignment-impl.md) |
| Capacity supply integration | [Capacity](capacity-overbooking.md) |
| Scheduled service sources | [Service Plan](service-plan-seasonal.md) |
| Waitlist | [Waitlist](waitlist-impl.md) |
| Notification | [Notification](notification-enrichment.md) |
| Accounting and compensation cost | [Finance Settlement](finance-settlement-enrichment.md) |
| Gross captured payment reports | [Reporting](reporting-enrichment.md) |

The [domain index](../02-domains/README.md), [API index](../08-contracts/api/README.md) and individual service README files cover other service behavior.
Unpublished Capacity and Notification changes are not enabled by this documentation update.
