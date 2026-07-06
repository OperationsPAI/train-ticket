# Legacy ACL Event Contracts

## LegacyCommandMapped

Produced for every legacy operation mapped through the strangler facade
(DR-014: old side effects become controlled commands + events + audit).

| Field | Description |
|---|---|
| **Producer** | legacy-acl |
| **Consumers** | admin-audit |
| **Trigger** | Any legacy endpoint processed (success or failure). |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `legacyOperation` | enum | yes | `PRESERVE`, `INSIDE_PAYMENT`, `TICKET_ISSUE`, `EXECUTE`, `CANCEL`, `REBOOK`. |
| `outcome` | enum | yes | `SUCCEEDED`, `FAILED`. |
| `operatorRef` | string | yes | From `X-Legacy-Operator`. |
| `reason` | string | no | From `X-Legacy-Reason`. |
| `mappedCommands` | string[] | yes | New-world commands invoked (e.g. `CreateJourneyOrder`). |
| `resultRefs` | object | yes | New-world ids produced (orderId, caseId, paymentIntentId, …); empty object on failure. |
| `failureMessage` | string | no | Downstream error when `outcome=FAILED`. |
| `sourceRef` | string | yes | Idempotency-Key of the legacy request (traceability per DR-014). |
| `metadata` | EventMetadata | yes | Standard event envelope. |
