# Risk & Compliance — Events & Commands

Last updated: 2026-07-04

## Published Events

### RiskAssessmentResult

| Field | Description |
|---|---|
| **Producer** | risk-compliance |
| **Consumers** | journey-order, payment, post-sales, account |
| **Trigger** | `AssessRisk` command or `JourneyOrderCreated` event processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `assessmentId` | `AssessmentId` | yes | Canonical assessment ID (`asmt-<uuid>`). |
| `subjectRef` | string | yes | Subject of the assessment (order ID, payment intent ID, account ID, etc.). |
| `scenario` | string | yes | Risk scenario (e.g. `order_risk`, `payment_risk`, `post_sales_risk`, `account_risk`). |
| `decision` | enum | yes | `ALLOW`, `DENY`, `CHALLENGE`, `HOLD`. |
| `score` | integer | no | Risk score (0-1000). |
| `level` | enum | no | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`. |
| `policyVersion` | string | yes | Rule/policy version that produced the decision. |
| `evidenceRef` | string | yes | Reference to the evidence bundle. |
| `reasonCode` | string | yes | Machine-readable reason code. |
| `reasonExplanation` | string | no | Human-readable explanation. |
| `assessmentSnapshotHash` | string | yes | Hash of input snapshot for explainability. |
| `assessedAt` | RFC3339 UTC | yes | When the assessment was completed. |

### ChallengeIssued

| Field | Description |
|---|---|
| **Producer** | risk-compliance |
| **Consumers** | account, payment |
| **Trigger** | `IssueChallenge` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `challengeId` | `ChallengeId` | yes | Canonical challenge ID (`chg-<uuid>`). |
| `businessRef` | string | yes | Business reference (order ID, payment intent ID, account ID). |
| `challengeType` | enum | yes | `SMS`, `FACE_VERIFICATION`, `PAYMENT_VERIFICATION`, `MANUAL_REVIEW`. |
| `assessmentId` | `AssessmentId` | yes | The assessment that triggered the challenge. |
| `issuedAt` | RFC3339 UTC | yes | When the challenge was issued. |
| `expiresAt` | RFC3339 UTC | yes | Challenge expiry time. |

### ChallengeResolved

| Field | Description |
|---|---|
| **Producer** | risk-compliance |
| **Consumers** | account, payment, journey-order |
| **Trigger** | `ResolveChallenge` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `challengeId` | `ChallengeId` | yes | Canonical challenge ID (`chg-<uuid>`). |
| `outcome` | enum | yes | `PASSED`, `FAILED`, `EXPIRED`. |
| `resolvedAt` | RFC3339 UTC | yes | When the challenge was resolved. |
| `evidenceRef` | string | no | Reference to resolution evidence. |

### RiskBlockApplied

| Field | Description |
|---|---|
| **Producer** | risk-compliance |
| **Consumers** | journey-order, payment, account, booking-orchestration |
| **Trigger** | `BlockSubject` command processed or a blocking order-risk rule is hit. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `blockId` | `BlockId` | yes | Canonical block ID (`blk-<uuid>`). |
| `subjectRef` | string | yes | Subject reference (order ID, payment ID, account ID). |
| `scope` | enum | yes | `ORDER`, `PAYMENT`, `ACCOUNT`. |
| `reasonCode` | string | yes | Machine-readable reason code. |
| `policyVersion` | string | yes | Policy version that produced the block. |
| `evidenceRef` | string | yes | Reference to evidence bundle. |
| `blockedAt` | RFC3339 UTC | yes | When the block was applied. |

### RiskBlockLifted

| Field | Description |
|---|---|
| **Producer** | risk-compliance |
| **Consumers** | journey-order, payment, account, booking-orchestration |
| **Trigger** | `AllowSubject` command or HTTP risk-block lift command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `allowId` | `AllowId` | yes | Canonical allow ID (`alw-<uuid>`). |
| `subjectRef` | string | yes | Subject reference (order ID, payment ID, account ID). |
| `scope` | enum | yes | `ORDER`, `PAYMENT`, `ACCOUNT`. |
| `reasonCode` | string | yes | Machine-readable reason code. |
| `policyVersion` | string | yes | Policy version that produced the allow. |
| `evidenceRef` | string | yes | Reference to evidence bundle. |
| `allowedAt` | RFC3339 UTC | yes | When the allow was applied. |

### EvidenceRecorded

| Field | Description |
|---|---|
| **Producer** | risk-compliance |
| **Consumers** | reporting, admin-and-audit |
| **Trigger** | `RecordEvidence` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `evidenceId` | `EvidenceId` | yes | Canonical evidence ID (`evid-<uuid>`). |
| `subjectRef` | string | yes | Subject reference. |
| `evidenceType` | string | yes | Type of evidence (e.g. `fraud_signal`, `device_fingerprint`, `rule_hit`). |
| `evidenceSummary` | string | yes | Human-readable evidence summary. |
| `recordedAt` | RFC3339 UTC | yes | When the evidence was recorded. |

## Consumed Commands (Inbox)

### AssessRisk

| Field | Type | Required | Description |
|---|---|---|---|
| `assessmentId` | `AssessmentId` | yes | Canonical assessment ID (`asmt-<uuid>`). |
| `subjectRef` | string | yes | Subject to assess. |
| `scenario` | string | yes | Risk scenario. |
| `inputs` | object | yes | Input snapshot (varies by scenario). |
| `idempotencyKey` | string | yes | Idempotency key. |

### IssueChallenge

| Field | Type | Required | Description |
|---|---|---|---|
| `challengeId` | `ChallengeId` | yes | Canonical challenge ID (`chg-<uuid>`). |
| `businessRef` | string | yes | Business reference. |
| `challengeType` | enum | yes | `SMS`, `FACE_VERIFICATION`, `PAYMENT_VERIFICATION`, `MANUAL_REVIEW`. |
| `assessmentId` | `AssessmentId` | yes | The assessment that triggered the challenge. |

### ResolveChallenge

| Field | Type | Required | Description |
|---|---|---|---|
| `challengeId` | `ChallengeId` | yes | Canonical challenge ID (`chg-<uuid>`). |
| `outcome` | enum | yes | `PASSED`, `FAILED`. |
| `evidence` | string | no | Resolution evidence. |

### BlockSubject

| Field | Type | Required | Description |
|---|---|---|---|
| `blockId` | `BlockId` | yes | Canonical block ID (`blk-<uuid>`). |
| `subjectRef` | string | yes | Subject reference. |
| `scope` | enum | yes | `ORDER`, `PAYMENT`, `ACCOUNT`. |
| `reasonCode` | string | yes | Reason code. |

### AllowSubject

| Field | Type | Required | Description |
|---|---|---|---|
| `allowId` | `AllowId` | yes | Canonical allow ID (`alw-<uuid>`). |
| `subjectRef` | string | yes | Subject reference. |
| `scope` | enum | yes | `ORDER`, `PAYMENT`, `ACCOUNT`. |
| `reasonCode` | string | yes | Reason code. |

### RecordEvidence

| Field | Type | Required | Description |
|---|---|---|---|
| `evidenceId` | `EvidenceId` | yes | Canonical evidence ID (`evid-<uuid>`). |
| `subjectRef` | string | yes | Subject reference. |
| `evidenceType` | string | yes | Type of evidence. |
| `evidenceSummary` | string | yes | Evidence summary. |
