# Identity Verification — HTTP API

Last updated: 2026-07-10

## Overview

Identity Verification owns实名核验案例、证件合规登记、优惠资质证书和按证件限购事实。本文档是 ADR-0003 wave A 契约面，受 `docs/02-domains/identity-verification.md` 约束。

Activation-wave rulings:

- **RULING (ADR-0003 wave A):** SIM 公安网关是进程内、确定性、可种子化模拟器，不访问真实公安、学信、民政、军残或铁路网络。默认按规范化证件号最后一位数字裁定：`0`-`5` -> `MATCH`/通过，`6`-`8` -> `REJECTED`/拒绝，`9` -> `MANUAL_REVIEW_REQUIRED`/需人工。`simSeed` 只参与 `simResultRef`、延迟桶和测试 fixture 折叠；同一 seed + 同一材料指纹必须得到同一结果。
- Journey Order 在创建订单前必须调用本域下单前核验钩子；本域返回核验、证件有效期、优惠资质和限购事实检查结果，但不创建或推进 Journey Order 状态。
- Fare & Pricing 只通过本域只读端点查询可用 `EligibilityCertificate` 摘要；价格、折扣金额和 Money 计算仍由 Fare & Pricing 拥有。
- Purchase-limit 事实事件会发布到 `events:identity-verification`；Risk & Compliance 的消费是未来接入点，本波只注册事件面并明确排除 active consumer。

Field shapes reference `docs/08-contracts/shared-primitives.md` for IDs, timestamps, event envelope fields, and pagination. JSON fields are camelCase, enum values are SCREAMING_SNAKE_CASE, and timestamps are RFC3339 UTC. No API response, event payload, or normal log may contain unmasked document numbers, full legal names, birth dates, SIM raw request/response, or real external credentials.

## Headers, idempotency, and wire IDs

State-changing POST endpoints require the standard headers from `docs/08-contracts/api/README.md`:

| Header | Required | Description |
|---|---|---|
| `Idempotency-Key` | yes | Client-supplied UUID-v7-shaped key. The HTTP boundary stores and replays by this header exactly; it is not replaced by material strings on the wire. |
| `X-Correlation-Id` | recommended | UUID-v7-shaped request correlation ID. When propagated to events it is serialized as `corr-<uuid-v7>` in the event envelope. |

Internal command IDs and event causation IDs MUST use `cmd-<uuid-v7>`; event IDs MUST use `evt-<uuid-v7>`. Repository-level duplicate detection folds business material in addition to the direct HTTP idempotency header:

| Command / endpoint | API idempotency key used directly? | Internal material folded for duplicate intent detection |
|---|---|---|
| `POST /api/v1/identity-verification/credentials` | yes, `Idempotency-Key` | `travelerId`, `documentType`, `documentHash`, `materialFingerprint`, `profileSnapshotVersion` |
| `POST /api/v1/identity-verification/verification-cases` | yes, `Idempotency-Key` | `travelerId`, `credentialRecordId`, `purpose`, `materialFingerprint`, `simPolicyVersion` |
| `POST /api/v1/identity-verification/eligibility-certificates` | yes, `Idempotency-Key` | `travelerId`, `eligibilityType`, `certificateHash`, `policyYear`, `policyVersion` |
| `POST /api/v1/identity-verification/pre-order-checks` | yes, `Idempotency-Key` | `orderIntentId`, `accountId`, sorted `travelerRefs`, sorted `segmentRefs`, `journeyDate`, `productCode`, `limitPolicyVersion` |

`materialFingerprint = sha256(canonicalNameHash | documentType | documentHash | birthDateHash? | validUntil? | evidenceHash? | policyVersion)`. The fingerprint is safe to store and compare, but the unhashed material is never serialized outside the encrypted storage / SIM mapper boundary.

## Common enums

| Enum | Values |
|---|---|
| `documentType` | `ID_CARD`, `PASSPORT` |
| `verificationPurpose` | `ORDER_CREATION`, `PROFILE_RECHECK`, `ELIGIBILITY_CERTIFICATE`, `MANUAL_AUDIT` |
| `verificationStatus` | `DRAFT`, `SUBMITTED`, `PASSED`, `FAILED`, `MANUAL_REVIEW_REQUIRED`, `EXPIRED`, `CANCELLED`, `SUPERSEDED`, `OVERRIDDEN` |
| `simOutcome` | `MATCH`, `REJECTED`, `MANUAL_REVIEW_REQUIRED` |
| `credentialStatus` | `REGISTERED`, `PENDING_VERIFICATION`, `VERIFIED`, `FAILED`, `EXPIRED`, `RETIRED` |
| `eligibilityType` | `STUDENT`, `CHILD`, `MILITARY_DISABLED` |
| `certificateStatus` | `DRAFT`, `ACTIVE`, `REJECTED`, `EXPIRED`, `REVOKED` |
| `preOrderCheckResult` | `PASS`, `REJECT`, `MANUAL_REVIEW_REQUIRED`, `DEGRADED` |
| `checkCode` | `VERIFICATION_PASSED`, `VERIFICATION_NOT_PASSED`, `DOCUMENT_EXPIRED`, `ELIGIBILITY_ACTIVE`, `ELIGIBILITY_UNAVAILABLE`, `PURCHASE_LIMIT_RECORDED`, `PURCHASE_LIMIT_CONFLICT`, `SIM_UNAVAILABLE`, `MANUAL_REVIEW_REQUIRED` |
| `usageReservationStatus` | `RESERVED`, `CONFIRMED`, `RELEASED` |
| `purchaseLimitFactStatus` | `RECORDED`, `CONFIRMED`, `RELEASED`, `MISSED`, `FAILED` |

`PASSED`, `FAILED`, `MANUAL_REVIEW_REQUIRED`, `EXPIRED`, `CANCELLED`, `SUPERSEDED`, `OVERRIDDEN`, `REJECTED`, and `REVOKED` are rest-observable terminal or resting states: GET endpoints continue to return their historical facts and automatic flows must not rewrite them in place.

## Resource representations

### CredentialRecord

| Field | Type | Required | Description |
|---|---|---|---|
| `credentialRecordId` | string | yes | Credential record ID (`crd-<uuid>`). |
| `travelerId` | string | yes | Traveler reference (`tvl-<uuid>`). |
| `profileSnapshotVersion` | string | yes | Traveler Profile snapshot version used when registering/linking the credential. |
| `documentType` | enum | yes | `ID_CARD` or `PASSPORT`. |
| `maskedDocumentNo` | string | yes | Masked document number for display only. |
| `documentHash` | string | yes | Stable hash index of the normalized document number. |
| `identityClusterId` | string | no | Cluster ID (`icl-<uuid>`) when linked. |
| `status` | enum | yes | Credential status. |
| `validUntil` | RFC3339 UTC | no | Document validity end when known. |
| `verifiedByCaseId` | string | no | Latest passed verification case ID. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last update timestamp. |

### VerificationCase

| Field | Type | Required | Description |
|---|---|---|---|
| `verificationCaseId` | string | yes | Verification case ID (`ivc-<uuid>`). |
| `travelerId` | string | yes | Traveler reference. |
| `credentialRecordId` | string | yes | Credential under verification. |
| `purpose` | enum | yes | Verification purpose. |
| `status` | enum | yes | Verification status. |
| `simOutcome` | enum | no | Deterministic SIM outcome after submission. |
| `simResultRef` | string | no | Safe deterministic reference (`sim-<uuid>`); not a raw SIM response. |
| `reasonCode` | string | no | Safe machine-readable result reason, e.g. `NAME_DOCUMENT_MISMATCH`, `DOCUMENT_NOT_FOUND`, `DOCUMENT_EXPIRED`, `MANUAL_REVIEW_REQUIRED`. |
| `materialFingerprint` | string | yes | SHA-256 folded material fingerprint. |
| `simPolicyVersion` | string | yes | SIM mapping / tail-digit policy version. |
| `validFrom` | RFC3339 UTC | no | Start of reusable passed verification validity. |
| `validUntil` | RFC3339 UTC | no | End of reusable passed verification validity. |
| `submittedAt` | RFC3339 UTC | no | SIM submission timestamp. |
| `completedAt` | RFC3339 UTC | no | Result timestamp for passed/failed/manual outcome. |
| `createdAt` | RFC3339 UTC | yes | Case creation timestamp. |

### EligibilityCertificate

| Field | Type | Required | Description |
|---|---|---|---|
| `eligibilityCertificateId` | string | yes | Certificate ID (`elc-<uuid>`). |
| `travelerId` | string | yes | Traveler reference. |
| `credentialRecordId` | string | no | Bound credential, required unless `identityClusterId` is present. |
| `identityClusterId` | string | no | Bound natural-person cluster, required unless `credentialRecordId` is present. |
| `eligibilityType` | enum | yes | `STUDENT`, `CHILD`, or `MILITARY_DISABLED`. |
| `status` | enum | yes | Certificate status. |
| `validFrom` | RFC3339 UTC | yes | Validity start. |
| `validUntil` | RFC3339 UTC | yes | Validity end. |
| `policyYear` | string | yes | Policy year used for annual usage counters, e.g. `2026`. |
| `policyVersion` | string | yes | Eligibility policy version. |
| `annualUsageLimit` | integer | yes | Allowed usages in the policy year. |
| `annualUsageReserved` | integer | yes | Currently reserved usages. |
| `annualUsageConfirmed` | integer | yes | Confirmed usages. |
| `applicableProductCodes` | string[] | yes | Product codes for which the certificate may be considered. |
| `evidenceHash` | string | yes | Hash of supporting evidence. |
| `reasonCode` | string | no | Rejection/revocation/expiry reason code. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last update timestamp. |

### PreOrderCheck

| Field | Type | Required | Description |
|---|---|---|---|
| `preOrderCheckId` | string | yes | Check ID (`poc-<uuid>`). |
| `orderIntentId` | string | yes | Caller-generated order intent reference; UUID-v7-shaped with `oint-` prefix recommended. |
| `accountId` | string | yes | Account attempting order creation. |
| `travelerRefs` | string[] | yes | Traveler refs checked. |
| `segmentRefs` | string[] | yes | Segment refs checked. |
| `journeyDate` | string | yes | Local journey date (`YYYY-MM-DD`) used for purchase-limit policy. |
| `productCode` | string | yes | Product/ticket code being ordered. |
| `result` | enum | yes | `PASS`, `REJECT`, `MANUAL_REVIEW_REQUIRED`, or `DEGRADED`. |
| `checks` | array[object] | yes | Per-traveler and per-policy check details; see `CheckDetail`. |
| `purchaseLimitFacts` | array[object] | yes | Limit facts recorded by this check; each entry includes `purchaseLimitFactId`, `scopeType`, `scopeRef`, `status`, and `limitPolicyVersion`. |
| `evaluatedAt` | RFC3339 UTC | yes | Check evaluation timestamp. |
| `expiresAt` | RFC3339 UTC | yes | Latest time Journey Order may rely on this check for the same order intent. |

`CheckDetail` fields:

| Field | Type | Required | Description |
|---|---|---|---|
| `travelerId` | string | yes | Traveler reference. |
| `credentialRecordId` | string | no | Credential used by this check. |
| `eligibilityCertificateId` | string | no | Certificate considered by this check. |
| `code` | enum | yes | Check code from the common enum. |
| `status` | enum | yes | `PASS`, `REJECT`, `MANUAL_REVIEW_REQUIRED`, or `DEGRADED`. |
| `reasonCode` | string | no | Stable machine-readable reason; no PII. |
| `policyVersion` | string | yes | Policy version used. |

## Endpoints

### Register Credential

**POST** `/api/v1/identity-verification/credentials`

**Idempotency:** REQUIRED (`Idempotency-Key` header, UUID-v7). The header is used directly for HTTP replay; the repository also folds credential material as described above.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `travelerId` | string | yes | Traveler reference (`tvl-<uuid>`). |
| `profileSnapshotVersion` | string | yes | Traveler Profile snapshot version presented by the caller. |
| `documentType` | enum | yes | `ID_CARD` or `PASSPORT`. |
| `maskedDocumentNo` | string | yes | Masked display number. |
| `documentHash` | string | yes | Hash of normalized document number. |
| `canonicalNameHash` | string | yes | Hash of normalized legal name; full name is not accepted. |
| `birthDateHash` | string | no | Hash of birth date when needed for policy, never clear text. |
| `validUntil` | RFC3339 UTC | no | Document validity end when known. |
| `evidenceHash` | string | no | Supporting evidence hash. |

**Response (201):** `CredentialRecord` resource.

**Error codes:** `VALIDATION_FAILED` (400), `NOT_FOUND` (404 traveler/snapshot), `CONFLICT` (409 active duplicate document hash), `IDEMPOTENCY_KEY_REUSED` (422), `DOMAIN_RULE_VIOLATION` (422 invalid/expired material), `UNAVAILABLE` (503 storage/dependency unavailable).

### Start Verification Case

**POST** `/api/v1/identity-verification/verification-cases`

Creates a VerificationCase and submits to the deterministic SIM adapter in the same command boundary when the adapter is available. Tail-digit RULING is applied to the encrypted material referenced by `credentialRecordId`; request bodies do not carry clear document numbers.

**Idempotency:** REQUIRED (`Idempotency-Key` header, UUID-v7).

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `travelerId` | string | yes | Traveler reference. |
| `credentialRecordId` | string | yes | Registered credential to verify. |
| `purpose` | enum | yes | Verification purpose. |
| `materialFingerprint` | string | yes | Fingerprint computed from the same material used at registration. |
| `simPolicyVersion` | string | yes | SIM deterministic policy version, e.g. `sim-tail-v1`. |
| `requestedAt` | RFC3339 UTC | yes | Caller request timestamp. |

**Response (201):** `VerificationCase` resource.

**Error codes:** `VALIDATION_FAILED` (400), `NOT_FOUND` (404 traveler/credential), `CONFLICT` (409 active same-purpose case already exists), `PRECONDITION_FAILED` (412 credential retired/expired), `IDEMPOTENCY_KEY_REUSED` (422), `DOMAIN_RULE_VIOLATION` (422 material mismatch), `UNAVAILABLE` (503 SIM adapter unavailable).

### Get Verification Case

**GET** `/api/v1/identity-verification/verification-cases/{verificationCaseId}`

**Response (200):** `VerificationCase` resource. Terminal/resting states (`PASSED`, `FAILED`, `MANUAL_REVIEW_REQUIRED`, `EXPIRED`, `CANCELLED`, `SUPERSEDED`, `OVERRIDDEN`) remain queryable for audit and customer-service use.

**Error codes:** `NOT_FOUND` (404).

### Get Credential Verification Status

**GET** `/api/v1/identity-verification/credentials/{credentialRecordId}/verification-status`

**Response (200):**

| Field | Type | Required | Description |
|---|---|---|---|
| `credentialRecordId` | string | yes | Credential record ID. |
| `travelerId` | string | yes | Traveler reference. |
| `credentialStatus` | enum | yes | Credential status. |
| `latestVerificationCaseId` | string | no | Latest verification case considered. |
| `verificationStatus` | enum | yes | Latest effective verification status. |
| `validUntil` | RFC3339 UTC | no | Verification or document validity end. |
| `reasonCode` | string | no | Safe reason code. |
| `readAt` | RFC3339 UTC | yes | Read timestamp. |

**Error codes:** `NOT_FOUND` (404).

### Register Eligibility Certificate

**POST** `/api/v1/identity-verification/eligibility-certificates`

**Idempotency:** REQUIRED (`Idempotency-Key` header, UUID-v7).

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `travelerId` | string | yes | Traveler reference. |
| `credentialRecordId` | string | no | Bound credential; required unless `identityClusterId` is supplied. |
| `identityClusterId` | string | no | Bound identity cluster; required unless `credentialRecordId` is supplied. |
| `eligibilityType` | enum | yes | `STUDENT`, `CHILD`, or `MILITARY_DISABLED`. |
| `validFrom` | RFC3339 UTC | yes | Validity start. |
| `validUntil` | RFC3339 UTC | yes | Validity end. |
| `policyYear` | string | yes | Policy year, e.g. `2026`. |
| `policyVersion` | string | yes | Eligibility policy version. |
| `annualUsageLimit` | integer | yes | Annual limit. |
| `applicableProductCodes` | string[] | yes | Products for which this certificate may be considered. |
| `certificateHash` | string | yes | Stable hash of certificate material. |
| `evidenceHash` | string | yes | Supporting evidence hash. |

**Response (201):** `EligibilityCertificate` resource.

**Error codes:** `VALIDATION_FAILED` (400), `NOT_FOUND` (404 traveler/credential/cluster), `CONFLICT` (409 duplicate active certificate), `PRECONDITION_FAILED` (412 credential not verified), `IDEMPOTENCY_KEY_REUSED` (422), `DOMAIN_RULE_VIOLATION` (422 invalid validity window/policy), `UNAVAILABLE` (503).

### Query Eligibility Certificates (read-only for Fare & Pricing)

**GET** `/api/v1/identity-verification/eligibility-certificates?travelerId={travelerId}&eligibilityType={eligibilityType}&journeyDate={YYYY-MM-DD}&productCode={productCode}`

This endpoint is read-only and MUST NOT reserve annual usage. Fare & Pricing uses it to decide whether a discount rule may be evaluated; this domain does not compute fare amounts.

**Query parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `travelerId` | string | yes | Traveler reference. |
| `eligibilityType` | enum | no | Optional filter: `STUDENT`, `CHILD`, `MILITARY_DISABLED`. |
| `journeyDate` | string | yes | Local journey date (`YYYY-MM-DD`) used for validity and policy-year filtering. |
| `productCode` | string | no | Optional product code filter. |
| `limit` | integer | no | Pagination limit, default 20, max 100. |
| `offset` | integer | no | Pagination offset, default 0. |

**Response (200):** Paginated response with `items`, `total`, `limit`, and `offset`, where each item is an `EligibilityCertificate` summary. Summaries omit `evidenceHash` unless the caller is an audited internal service.

**Error codes:** `VALIDATION_FAILED` (400), `NOT_FOUND` (404 traveler), `UNAVAILABLE` (503).

### Pre-Order Verification Check

**POST** `/api/v1/identity-verification/pre-order-checks`

Journey Order calls this hook before accepting `POST /api/v1/journey-orders`. The call may record idempotent purchase-limit facts for the supplied order intent. It never creates a Journey Order and it never returns a Risk & Compliance final decision.

**Idempotency:** REQUIRED (`Idempotency-Key` header, UUID-v7). Journey Order MUST persist and reuse the same key for retries of the same `orderIntentId`.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `orderIntentId` | string | yes | Caller-generated stable order intent reference; `oint-<uuid>` recommended. |
| `accountId` | string | yes | Account attempting order creation. |
| `offerId` | string | yes | Offer being converted to order. |
| `offerVersion` | integer | yes | Offer version at quote time. |
| `travelerRefs` | string[] | yes | Traveler refs from the Journey Order create request. |
| `segmentRefs` | string[] | yes | Segment refs from the Journey Order create request. |
| `journeyDate` | string | yes | Local journey date (`YYYY-MM-DD`) for limit policy. |
| `productCode` | string | yes | Product/ticket code. |
| `requestedEligibilityTypes` | array[enum] | no | Eligibility types the order intends to use. |
| `limitPolicyVersion` | string | yes | Purchase-limit policy version to record. |
| `requestedAt` | RFC3339 UTC | yes | Request timestamp. |

**Response (200 or 201):** `PreOrderCheck` resource. `201` is used when new facts are recorded; `200` is used for idempotent replay or when no new fact is needed.

**Error codes:** `VALIDATION_FAILED` (400), `NOT_FOUND` (404 traveler/credential/offer reference not known to read model), `CONFLICT` (409 duplicate/conflicting purchase-limit fact for same protected scope), `PRECONDITION_FAILED` (412 required verification or certificate is not in an acceptable state), `IDEMPOTENCY_KEY_REUSED` (422), `DOMAIN_RULE_VIOLATION` (422 policy violation such as expired document), `UNAVAILABLE` (503 read model or outbox unavailable).

## Existing-domain increment: Journey Order hook (需同波实现)

Journey Order contract increment is normative for this wave and is not docs-only. It must be implemented in the same wave at these touchpoints:

| Service | Touchpoint | Required change |
|---|---|---|
| `journey-order` | HTTP command validator for `POST /api/v1/journey-orders` / `CreateJourneyOrder` | Before aggregate creation, call `POST /api/v1/identity-verification/pre-order-checks` with `accountId`, `offerId`, `offerVersion`, `travelerRefs`, `segmentRefs`, `orderIntentId`, `journeyDate`, `productCode`, and `limitPolicyVersion`. Reject on `REJECT` or `MANUAL_REVIEW_REQUIRED`; retry/503 on `DEGRADED` unless product policy explicitly allows degraded creation. |
| `journey-order` | Request enum / validation | Add validation for `identityPreOrderCheckRef` only if returned to clients in a later implementation; no new order status enum is introduced in this contract. |
| `journey-order` | Idempotency handling | Persist the UUID-v7 idempotency key used for the pre-order check with the Journey Order create attempt and reuse it on retry. |

## Existing-domain increment: Fare & Pricing certificate lookup (需同波实现)

Fare & Pricing must consume the read-only eligibility query above in the same wave before evaluating discount rules. Required code touchpoints:

| Service | Touchpoint | Required change |
|---|---|---|
| `fare-pricing` | Fare quote application service / eligibility adapter | Add an outbound read adapter to `GET /api/v1/identity-verification/eligibility-certificates` using `travelerRefs`, requested `eligibilityType`, `journeyDate`, and `productCode`. |
| `fare-pricing` | Discount-rule validation | Treat absent, expired, revoked, or exhausted certificates as ineligible facts; do not create or reserve usage from Fare & Pricing. |
| `fare-pricing` | Enum mapping | Map Identity Verification `eligibilityType` values `STUDENT`, `CHILD`, `MILITARY_DISABLED` to Fare & Pricing discount categories without introducing new Money shapes. |
