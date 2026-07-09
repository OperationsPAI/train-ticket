# Identity Verification — Events & Commands

Last updated: 2026-07-10

## Scope and activation-wave rulings

This contract enumerates the Identity Verification events for ADR-0003 wave A. It is bounded by `docs/02-domains/identity-verification.md` and the HTTP contract in `docs/08-contracts/api/identity-verification.md`.

Activation-wave rulings:

- **RULING (ADR-0003 wave A):** SIM 公安网关 is a deterministic, seedable in-process simulator. The default policy maps the normalized document number tail digit as `0`-`5` -> pass, `6`-`8` -> reject, and `9` -> manual review. No real公安/学信/民政/军残/铁路 endpoint, credential, token, or raw payload is used or emitted.
- Journey Order uses the synchronous pre-order hook documented in the HTTP API before accepting order creation; Identity Verification events do not create Journey Order state transitions.
- Fare & Pricing uses only the read-only certificate query for discount-rule eligibility. This event surface publishes certificate facts but does not compute fare amounts or Money.
- Purchase-limit fact events are published for future Risk & Compliance consumption. Risk & Compliance is not an active consumer in this wave and no `AssessRisk` command is emitted by this context.

All payload fields are camelCase, all enum values are SCREAMING_SNAKE_CASE, and all timestamps are RFC3339 UTC. Envelope fields, including optional trace context propagation, follow `docs/08-contracts/messaging.md` and `docs/08-contracts/shared-primitives.md`. Event payloads MUST NOT carry unmasked document numbers, full names, birth dates, SIM raw request/response, or external credentials.

## Event identity, correlation, and command idempotency

Identity Verification producers MUST assign deterministic event IDs per aggregate transition. The event ID is UUID-v7-shaped with version/variant bits stamped after folding the seed with SHA-256:

```
identity-verification:<eventType>:<aggregateId>:<aggregateVersion>
```

For append-only usage and purchase-limit facts whose business fact ID is itself stable, use:

```
identity-verification:<eventType>:<aggregateId>:<factId>:<factVersion>
```

If a replayed command or consumed event causes no new transition, no new event is emitted. Consumers still deduplicate by envelope `eventId`.

Wire correlation and causation IDs MUST use `corr-<uuid-v7>` and `cmd-<uuid-v7>`/`evt-<uuid-v7>` prefixes. HTTP idempotency keys are UUID-v7-shaped and are used directly at the API boundary. Internal command material is folded separately:

| Command | Aggregate | Event(s) | Internal folded material |
|---|---|---|---|
| `RegisterCredential` | `CredentialRecord` | `CredentialRegistered` | `travelerId`, `documentType`, `documentHash`, `materialFingerprint`, `profileSnapshotVersion` |
| `StartVerificationCase` | `VerificationCase` | `VerificationCaseStarted`, then `VerificationSubmittedToSim` | `travelerId`, `credentialRecordId`, `purpose`, `materialFingerprint`, `simPolicyVersion` |
| `RecordSimVerificationResult` | `VerificationCase` | `VerificationPassed` or `VerificationFailed` | `verificationCaseId`, `simResultRef`, `simPolicyVersion` |
| `RegisterEligibilityCertificate` | `EligibilityCertificate` | `EligibilityCertificateRegistered` | `travelerId`, `eligibilityType`, `certificateHash`, `policyYear`, `policyVersion` |
| `VerifyEligibilityCertificate` | `EligibilityCertificate` | `EligibilityCertificateVerified` | `eligibilityCertificateId`, `verificationAttemptId`, `policyVersion` |
| `ReserveEligibilityUsage` | `EligibilityCertificate` | `EligibilityUsageReserved` | `eligibilityCertificateId`, `policyYear`, `orderIntentId` |
| `ConfirmEligibilityUsage` | `EligibilityCertificate` | `EligibilityUsageConfirmed` | `eligibilityCertificateId`, `usageReservationId`, `journeyOrderId` |
| `ReleaseEligibilityUsage` | `EligibilityCertificate` | `EligibilityUsageReleased` | `eligibilityCertificateId`, `usageReservationId`, `releaseReason` |
| `RecordPurchaseLimitFact` | `PurchaseLimitLedger` | `PurchaseLimitFactRecorded` | `scopeType`, `scopeRef`, `journeyDate`, `productCode`, `orderIntentId`, `limitPolicyVersion` |
| `ConfirmPurchaseLimitFact` | `PurchaseLimitLedger` | `PurchaseLimitFactConfirmed` | `purchaseLimitFactId`, `journeyOrderId` |
| `ReleasePurchaseLimitFact` | `PurchaseLimitLedger` | `PurchaseLimitFactReleased` | `purchaseLimitFactId`, `releaseReason`, `sourceEventId` |
| `MarkPurchaseLimitMissed` | `PurchaseLimitLedger` | `PurchaseLimitFactMissed` | `purchaseLimitFactId`, `ttlBucket`, `monitorRunId` |
| `MarkPurchaseLimitFailed` | `PurchaseLimitLedger` | `PurchaseLimitFactFailed` | `purchaseLimitFactId`, `failureCode`, `detectionRunId` |

Material fingerprint rule: `materialFingerprint = sha256(canonicalNameHash | documentType | documentHash | birthDateHash? | validUntil? | evidenceHash? | policyVersion)`. The material string is never used as a wire idempotency key and is never logged with unhashed sensitive fields.

## Common payload objects

### CredentialSnapshot

| Field | Type | Required | Description |
|---|---|---|---|
| `credentialRecordId` | string | yes | Credential record ID (`crd-<uuid>`). |
| `travelerId` | string | yes | Traveler reference (`tvl-<uuid>`). |
| `documentType` | enum | yes | `ID_CARD` or `PASSPORT`. |
| `maskedDocumentNo` | string | yes | Masked display number. |
| `documentHash` | string | yes | Stable hash index. |
| `identityClusterId` | string | no | Cluster ID (`icl-<uuid>`) when linked. |
| `credentialStatus` | enum | yes | `REGISTERED`, `PENDING_VERIFICATION`, `VERIFIED`, `FAILED`, `EXPIRED`, or `RETIRED`. |
| `validUntil` | RFC3339 UTC | no | Document validity end when known. |

### EligibilityCertificateSnapshot

| Field | Type | Required | Description |
|---|---|---|---|
| `eligibilityCertificateId` | string | yes | Certificate ID (`elc-<uuid>`). |
| `travelerId` | string | yes | Traveler reference. |
| `credentialRecordId` | string | no | Bound credential. |
| `identityClusterId` | string | no | Bound identity cluster. |
| `eligibilityType` | enum | yes | `STUDENT`, `CHILD`, or `MILITARY_DISABLED`. |
| `certificateStatus` | enum | yes | `DRAFT`, `ACTIVE`, `REJECTED`, `EXPIRED`, or `REVOKED`. |
| `validFrom` | RFC3339 UTC | yes | Validity start. |
| `validUntil` | RFC3339 UTC | yes | Validity end. |
| `policyYear` | string | yes | Policy year. |
| `policyVersion` | string | yes | Policy version. |
| `annualUsageLimit` | integer | yes | Annual usage limit. |
| `annualUsageReserved` | integer | yes | Reserved usage count. |
| `annualUsageConfirmed` | integer | yes | Confirmed usage count. |
| `applicableProductCodes` | string[] | yes | Product codes for which the certificate can be considered. |

## Published Events

### CredentialRegistered

| Field | Description |
|---|---|
| **Producer** | identity-verification |
| **Consumers** | none in this wave (deferred: traveler-profile, journey-order, customer-service) |
| **Trigger** | `RegisterCredential` accepts hashed/masked credential material. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `credentialRecordId` | string | yes | Credential record ID. |
| `travelerId` | string | yes | Traveler reference. |
| `profileSnapshotVersion` | string | yes | Traveler Profile snapshot version used. |
| `documentType` | enum | yes | `ID_CARD` or `PASSPORT`. |
| `maskedDocumentNo` | string | yes | Masked document number. |
| `documentHash` | string | yes | Hash index. |
| `materialFingerprint` | string | yes | Folded material fingerprint. |
| `credentialStatus` | enum | yes | `REGISTERED`. |
| `validUntil` | RFC3339 UTC | no | Document validity end. |
| `registeredAt` | RFC3339 UTC | yes | Registration timestamp. |
| `aggregateVersion` | integer | yes | Aggregate version after registration. |

### VerificationCaseStarted

| Field | Description |
|---|---|
| **Producer** | identity-verification |
| **Consumers** | none in this wave (deferred: journey-order, customer-service, reporting) |
| **Trigger** | `StartVerificationCase` creates a new case for a credential and purpose. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `verificationCaseId` | string | yes | Verification case ID (`ivc-<uuid>`). |
| `travelerId` | string | yes | Traveler reference. |
| `credentialRecordId` | string | yes | Credential under verification. |
| `purpose` | enum | yes | `ORDER_CREATION`, `PROFILE_RECHECK`, `ELIGIBILITY_CERTIFICATE`, or `MANUAL_AUDIT`. |
| `materialFingerprint` | string | yes | Folded material fingerprint. |
| `verificationStatus` | enum | yes | `DRAFT`. |
| `simPolicyVersion` | string | yes | SIM deterministic policy version. |
| `startedAt` | RFC3339 UTC | yes | Case start timestamp. |
| `aggregateVersion` | integer | yes | Aggregate version after creation. |

### VerificationSubmittedToSim

| Field | Description |
|---|---|
| **Producer** | identity-verification |
| **Consumers** | none in this wave (deferred: reporting) |
| **Trigger** | Case material is submitted to the deterministic SIM adapter. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `verificationCaseId` | string | yes | Verification case ID. |
| `travelerId` | string | yes | Traveler reference. |
| `credentialRecordId` | string | yes | Credential under verification. |
| `simOperationRef` | string | yes | Safe operation reference (`simop-<uuid>`). |
| `materialFingerprint` | string | yes | Folded material fingerprint. |
| `simPolicyVersion` | string | yes | SIM policy version. |
| `submittedAt` | RFC3339 UTC | yes | Submission timestamp. |
| `verificationStatus` | enum | yes | `SUBMITTED`. |
| `aggregateVersion` | integer | yes | Aggregate version after submission. |

### VerificationPassed

| Field | Description |
|---|---|
| **Producer** | identity-verification |
| **Consumers** | none in this wave (deferred: journey-order, traveler-profile, customer-service, reporting) |
| **Trigger** | Deterministic SIM result is `MATCH` or an audited override produces a passed conclusion. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `verificationCaseId` | string | yes | Verification case ID. |
| `travelerId` | string | yes | Traveler reference. |
| `credentialRecordId` | string | yes | Verified credential. |
| `simOutcome` | enum | yes | `MATCH`. |
| `simResultRef` | string | yes | Safe deterministic SIM result reference. |
| `verificationStatus` | enum | yes | `PASSED`. |
| `validFrom` | RFC3339 UTC | yes | Verification validity start. |
| `validUntil` | RFC3339 UTC | yes | Verification validity end. |
| `policyVersion` | string | yes | Verification policy version. |
| `completedAt` | RFC3339 UTC | yes | Completion timestamp. |
| `aggregateVersion` | integer | yes | Aggregate version after pass. |

### VerificationFailed

| Field | Description |
|---|---|
| **Producer** | identity-verification |
| **Consumers** | none in this wave (deferred: journey-order, traveler-profile, customer-service, reporting) |
| **Trigger** | Deterministic SIM result is rejected, manual review is required, or audited conclusion fails. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `verificationCaseId` | string | yes | Verification case ID. |
| `travelerId` | string | yes | Traveler reference. |
| `credentialRecordId` | string | yes | Credential under verification. |
| `simOutcome` | enum | yes | `REJECTED` or `MANUAL_REVIEW_REQUIRED`. |
| `simResultRef` | string | yes | Safe deterministic SIM result reference. |
| `verificationStatus` | enum | yes | `FAILED` or `MANUAL_REVIEW_REQUIRED`. |
| `reasonCode` | string | yes | Safe reason such as `NAME_DOCUMENT_MISMATCH`, `DOCUMENT_NOT_FOUND`, `DOCUMENT_EXPIRED`, or `MANUAL_REVIEW_REQUIRED`. |
| `policyVersion` | string | yes | Verification policy version. |
| `completedAt` | RFC3339 UTC | yes | Completion timestamp. |
| `aggregateVersion` | integer | yes | Aggregate version after failure. |

### EligibilityCertificateRegistered

| Field | Description |
|---|---|
| **Producer** | identity-verification |
| **Consumers** | none in this wave (deferred: fare-pricing, journey-order, customer-service) |
| **Trigger** | `RegisterEligibilityCertificate` stores certificate material hash and policy scope. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `eligibilityCertificateId` | string | yes | Certificate ID (`elc-<uuid>`). |
| `travelerId` | string | yes | Traveler reference. |
| `credentialRecordId` | string | no | Bound credential. |
| `identityClusterId` | string | no | Bound identity cluster. |
| `eligibilityType` | enum | yes | `STUDENT`, `CHILD`, or `MILITARY_DISABLED`. |
| `certificateStatus` | enum | yes | `DRAFT`. |
| `validFrom` | RFC3339 UTC | yes | Validity start. |
| `validUntil` | RFC3339 UTC | yes | Validity end. |
| `policyYear` | string | yes | Policy year. |
| `policyVersion` | string | yes | Policy version. |
| `annualUsageLimit` | integer | yes | Annual usage limit. |
| `annualUsageReserved` | integer | yes | Reserved usage count; initially `0`. |
| `annualUsageConfirmed` | integer | yes | Confirmed usage count; initially `0`. |
| `applicableProductCodes` | string[] | yes | Product codes for which the certificate can be considered. |
| `certificateHash` | string | yes | Stable certificate material hash. |
| `evidenceHash` | string | yes | Evidence hash. |
| `registeredAt` | RFC3339 UTC | yes | Registration timestamp. |
| `aggregateVersion` | integer | yes | Aggregate version after registration. |

### EligibilityCertificateVerified

| Field | Description |
|---|---|
| **Producer** | identity-verification |
| **Consumers** | none in this wave (deferred: fare-pricing, journey-order, customer-service) |
| **Trigger** | Certificate material passes the deterministic eligibility simulator or audit. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `eligibilityCertificateId` | string | yes | Certificate ID. |
| `travelerId` | string | yes | Traveler reference. |
| `credentialRecordId` | string | no | Bound credential. |
| `identityClusterId` | string | no | Bound identity cluster. |
| `eligibilityType` | enum | yes | `STUDENT`, `CHILD`, or `MILITARY_DISABLED`. |
| `certificateStatus` | enum | yes | `ACTIVE`. |
| `validFrom` | RFC3339 UTC | yes | Validity start. |
| `validUntil` | RFC3339 UTC | yes | Validity end. |
| `policyYear` | string | yes | Policy year. |
| `policyVersion` | string | yes | Policy version. |
| `annualUsageLimit` | integer | yes | Annual usage limit. |
| `annualUsageReserved` | integer | yes | Reserved usage count. |
| `annualUsageConfirmed` | integer | yes | Confirmed usage count. |
| `applicableProductCodes` | string[] | yes | Product codes for which the certificate can be considered. |
| `verificationAttemptId` | string | yes | Attempt reference (`eva-<uuid>`). |
| `verifiedAt` | RFC3339 UTC | yes | Verification timestamp. |
| `aggregateVersion` | integer | yes | Aggregate version after verification. |

### EligibilityUsageReserved

| Field | Description |
|---|---|
| **Producer** | identity-verification |
| **Consumers** | none in this wave (deferred: journey-order, fare-pricing; future: risk-compliance) |
| **Trigger** | Order intent reserves one annual certificate usage. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `usageReservationId` | string | yes | Reservation ID (`eur-<uuid>`). |
| `eligibilityCertificateId` | string | yes | Certificate ID. |
| `travelerId` | string | yes | Traveler reference. |
| `eligibilityType` | enum | yes | Certificate type. |
| `policyYear` | string | yes | Policy year. |
| `orderIntentId` | string | yes | Order intent that owns the reservation. |
| `annualUsageReserved` | integer | yes | Reserved count after transition. |
| `annualUsageConfirmed` | integer | yes | Confirmed count after transition. |
| `reservedAt` | RFC3339 UTC | yes | Reservation timestamp. |
| `aggregateVersion` | integer | yes | Aggregate version after reservation. |

### EligibilityUsageConfirmed

| Field | Description |
|---|---|
| **Producer** | identity-verification |
| **Consumers** | none in this wave (deferred: journey-order, fare-pricing; future: risk-compliance) |
| **Trigger** | A protected Journey Order was accepted and consumes the reserved usage. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `usageReservationId` | string | yes | Reservation ID. |
| `eligibilityCertificateId` | string | yes | Certificate ID. |
| `travelerId` | string | yes | Traveler reference. |
| `journeyOrderId` | string | yes | Accepted journey order (`ord-<uuid>`). |
| `policyYear` | string | yes | Policy year. |
| `annualUsageReserved` | integer | yes | Reserved count after confirmation. |
| `annualUsageConfirmed` | integer | yes | Confirmed count after confirmation. |
| `confirmedAt` | RFC3339 UTC | yes | Confirmation timestamp. |
| `aggregateVersion` | integer | yes | Aggregate version after confirmation. |

### EligibilityUsageReleased

| Field | Description |
|---|---|
| **Producer** | identity-verification |
| **Consumers** | none in this wave (deferred: journey-order, fare-pricing; future: risk-compliance) |
| **Trigger** | A reservation is released because order creation is abandoned, cancelled before protection, or precondition fails. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `usageReservationId` | string | yes | Reservation ID. |
| `eligibilityCertificateId` | string | yes | Certificate ID. |
| `travelerId` | string | yes | Traveler reference. |
| `orderIntentId` | string | yes | Order intent that released the reservation. |
| `releaseReason` | string | yes | Stable reason code; no PII. |
| `annualUsageReserved` | integer | yes | Reserved count after release. |
| `annualUsageConfirmed` | integer | yes | Confirmed count after release. |
| `releasedAt` | RFC3339 UTC | yes | Release timestamp. |
| `aggregateVersion` | integer | yes | Aggregate version after release. |

### PurchaseLimitFactRecorded

| Field | Description |
|---|---|
| **Producer** | identity-verification |
| **Consumers** | none in this wave (future: risk-compliance; deferred: reporting) |
| **Trigger** | Pre-order check records an idempotent fact for a protected identity scope. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `purchaseLimitFactId` | string | yes | Fact ID (`plf-<uuid>`). |
| `scopeType` | enum | yes | `CREDENTIAL` or `IDENTITY_CLUSTER`. |
| `scopeRef` | string | yes | `credentialRecordId` or `identityClusterId`. |
| `travelerId` | string | yes | Traveler reference associated with the fact. |
| `orderIntentId` | string | yes | Order intent that recorded the fact. |
| `journeyDate` | string | yes | Local journey date (`YYYY-MM-DD`). |
| `productCode` | string | yes | Product/ticket code. |
| `segmentRefs` | string[] | yes | Protected segments. |
| `limitPolicyVersion` | string | yes | Limit policy version. |
| `factStatus` | enum | yes | `RECORDED`. |
| `reasonCode` | string | no | Optional safe reason code. |
| `recordedAt` | RFC3339 UTC | yes | Fact record timestamp. |
| `aggregateVersion` | integer | yes | Ledger version after recording. |

### PurchaseLimitFactConfirmed

| Field | Description |
|---|---|
| **Producer** | identity-verification |
| **Consumers** | none in this wave (future: risk-compliance; deferred: reporting) |
| **Trigger** | Journey Order acceptance confirms a recorded purchase-limit fact. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `purchaseLimitFactId` | string | yes | Fact ID. |
| `journeyOrderId` | string | yes | Accepted order (`ord-<uuid>`). |
| `orderIntentId` | string | yes | Original order intent. |
| `factStatus` | enum | yes | `CONFIRMED`. |
| `limitPolicyVersion` | string | yes | Limit policy version. |
| `confirmedAt` | RFC3339 UTC | yes | Confirmation timestamp. |
| `aggregateVersion` | integer | yes | Ledger version after confirmation. |

### PurchaseLimitFactReleased

| Field | Description |
|---|---|
| **Producer** | identity-verification |
| **Consumers** | none in this wave (future: risk-compliance; deferred: reporting) |
| **Trigger** | Order intent is abandoned or cancelled before the protection point. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `purchaseLimitFactId` | string | yes | Fact ID. |
| `orderIntentId` | string | yes | Original order intent. |
| `releaseReason` | string | yes | Stable release reason; no PII. |
| `sourceEventId` | string | no | Upstream event that caused release when applicable. |
| `factStatus` | enum | yes | `RELEASED`. |
| `limitPolicyVersion` | string | yes | Limit policy version. |
| `releasedAt` | RFC3339 UTC | yes | Release timestamp. |
| `aggregateVersion` | integer | yes | Ledger version after release. |

### PurchaseLimitFactMissed

| Field | Description |
|---|---|
| **Producer** | identity-verification |
| **Consumers** | none in this wave (future: risk-compliance; deferred: reporting) |
| **Trigger** | A monitor detects no confirm/release before the fact TTL bucket. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `purchaseLimitFactId` | string | yes | Fact ID. |
| `ttlBucket` | string | yes | Monitor TTL bucket. |
| `monitorRunId` | string | yes | Monitor run reference. |
| `factStatus` | enum | yes | `MISSED`. |
| `limitPolicyVersion` | string | yes | Limit policy version. |
| `missedAt` | RFC3339 UTC | yes | Miss timestamp. |
| `aggregateVersion` | integer | yes | Ledger version after miss. |

### PurchaseLimitFactFailed

| Field | Description |
|---|---|
| **Producer** | identity-verification |
| **Consumers** | none in this wave (future: risk-compliance; deferred: reporting) |
| **Trigger** | Ledger detects a conflict or invariant violation while recording or reconciling a limit fact. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `purchaseLimitFactId` | string | yes | Fact ID. |
| `failureCode` | string | yes | Stable failure code; no PII. |
| `detectionRunId` | string | yes | Detection or reconciliation run reference. |
| `factStatus` | enum | yes | `FAILED`. |
| `limitPolicyVersion` | string | yes | Limit policy version. |
| `failedAt` | RFC3339 UTC | yes | Failure timestamp. |
| `aggregateVersion` | integer | yes | Ledger version after failure. |

## Consumed upstream events

### TravelerSnapshotUpdated

Identity Verification consumes `TravelerSnapshotUpdated` from `events:traveler-profile` to link `travelerId`, `snapshotVersion`, `travelerType`, and `maskedDocumentRef` to credential registration read models. It does not copy full Traveler Profile master data.

## Future/deferred subscriptions

### RiskBlockApplied / RiskBlockLifted

Identity Verification may consume `RiskBlockApplied` and `RiskBlockLifted` from `events:risk-compliance` in a future wave to annotate limit facts with risk-related references. This is not an active subscription in ADR-0003 wave A, does not create a required consumer group, does not rewrite verification results, and does not make Risk & Compliance an input to SIM verification.

## Existing-domain increments (需同波实现)

| Context | Touchpoint | Required same-wave change |
|---|---|---|
| `journey-order` | `CreateJourneyOrder` validation and HTTP adapter | Invoke the pre-order check endpoint before aggregate creation; reject or defer based on `preOrderCheckResult`; persist the UUID-v7 hook idempotency key. |
| `journey-order` | Event/command mapping | Do not add new order lifecycle events for identity results in this wave; existing create rejection uses existing validation/domain error paths. |
| `fare-pricing` | Quote eligibility adapter | Query active `EligibilityCertificate` summaries read-only before discount evaluation. |
| `fare-pricing` | Discount enum mapping | Map `STUDENT`, `CHILD`, `MILITARY_DISABLED` without changing Money fields. |
| `risk-compliance` | Future subscription placeholder | No active consumer in this wave; future consumer will deduplicate purchase-limit facts by envelope `eventId` and fact ID. |
