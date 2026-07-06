# Fare & Pricing — Events & Commands

Last updated: 2026-07-06

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

### FareRuleSetPublished

| Field | Description |
|---|---|
| **Producer** | fare-pricing |
| **Consumers** | offer-management, admin-audit, reporting |
| **Trigger** | Managed fare rule set is published. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `ruleSetId` | string | yes | Published fare rule set identifier. |
| `supplierId` | string | yes | Supplier owning the fare rules. |
| `contractId` | string | yes | Supplier contract identifier. |
| `productCode` | string | yes | Product/scope code. |
| `mode` | string | yes | Transport mode. |
| `channel` | string | yes | Sales channel. |
| `version` | string | yes | Supplier rule version. |
| `status` | enum | yes | `PUBLISHED`. |
| `effectiveWindow` | object | yes | `{startsAt, endsAt}` RFC3339 UTC validity window. |
| `publishedAt` | RFC3339 UTC | yes | Publish timestamp. |
| `rules` | object[] | yes | Published rules in API shape: `ruleId`, `kind`, `amount`, `explanation`, `refundable`. |

Rule `kind` values are lower-case domain values: `base_fare`, `tax`, `fee`,
`discount`, `refund_fee`, `change_fee`. Money values MUST use `{currency,
minorUnits}`.

### FareRuleSetSuperseded

| Field | Description |
|---|---|
| **Producer** | fare-pricing |
| **Consumers** | offer-management, admin-audit, reporting |
| **Trigger** | Publishing a managed rule set supersedes an older published set with the same `channel` + `productCode`. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `ruleSetId` | string | yes | Superseded fare rule set identifier. |
| `supersededByRuleSetId` | string | yes | Replacement published fare rule set identifier. |
| `supplierId` | string | yes | Supplier of the superseded rule set. |
| `contractId` | string | yes | Contract of the superseded rule set. |
| `productCode` | string | yes | Product/scope code. |
| `mode` | string | yes | Transport mode. |
| `channel` | string | yes | Sales channel. |
| `version` | string | yes | Superseded rule version. |
| `status` | enum | yes | `SUPERSEDED`. |
| `supersededAt` | RFC3339 UTC | yes | Timestamp of replacement publication. |
