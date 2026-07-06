# Fare & Pricing — Events & Commands

Last updated: 2026-06-28

## Published Events

### FareQuoteComputed

| Field | Description |
|---|---|
| **Producer** | fare-pricing |
| **Consumers** | offer-management |
| **Trigger** | `ComputeFareQuote` command processed with valid `FareRuleSet` and input. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `quoteId` | `FareQuoteId` | yes | Canonical quote ID (`fq-<uuid>`). |
| `inputHash` | string | yes | Hash of input parameters. |
| `travelerRefs` | string[] | yes | Traveler references. |
| `channel` | string | yes | Sales channel. |
| `currency` | string | yes | ISO-4217 currency code. |
| `status` | enum | yes | `QUOTED` or `FAILED`. |
| `validFrom` | RFC3339 UTC | yes | Quote validity start. |
| `validUntil` | RFC3339 UTC | yes | Quote validity end. |
| `breakdown` | `FareBreakdown` | no | Price breakdown (present if status is `QUOTED`). |
| `ruleSnapshot` | `RuleSnapshot` | no | Snapshot of rules used. |

**`inputHash` definition (normative):** lowercase hex SHA-256 of the UTF-8
string `sorted(segmentRefs).join(",") + "|" + channel + "|" +
sorted(travelerRefs).join(",")`. Both producer and consumers MUST compute it
with this exact formula so quotes can be correlated without a shared store.

**`RuleSnapshot` shape (normative):**

| Field | Type | Description |
|---|---|---|
| `ruleSetId` | string | Rule set identifier. |
| `ruleSetVersion` | string | Rule set version. |
| `ruleIds` | string[] | Applied rule ids. |
| `digest` | string | Digest of the applied rule set. |
| `capturedAt` | RFC3339 UTC | Snapshot capture time. |
| `explanationCodes` | string[] | Price explanation codes. |
| `failedReason` | string | no | Failure reason (present if `FAILED`). |

### AdjustmentQuoteComputed

| Field | Description |
|---|---|
| **Producer** | fare-pricing |
| **Consumers** | post-sales |
| **Trigger** | Refund or change fee assessment completed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `adjustmentQuoteId` | string | yes | Adjustment quote ID. |
| `purpose` | enum | yes | `REFUND` or `CHANGE`. |
| `status` | enum | yes | `QUOTED` or `FAILED`. |
| `refundableAmount` | `Money` | yes | Amount refundable to customer. |
| `amountDue` | `Money` | yes | Amount due from customer. |
| `validUntil` | RFC3339 UTC | yes | Adjustment quote expiry. |
