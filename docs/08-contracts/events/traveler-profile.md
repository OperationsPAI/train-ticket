# Traveler Profile — Events & Commands

Last updated: 2026-07-04

## Published Events

### TravelerSnapshotUpdated

| Field | Description |
|---|---|
| **Producer** | traveler-profile |
| **Consumers** | offer-management, journey-order |
| **Trigger** | Traveler profile data changed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `travelerId` | `TravelerId` | yes | Canonical traveler ID (`tvl-<uuid>`). |
| `snapshotVersion` | string | yes | Monotonically increasing version. |
| `updatedAt` | RFC3339 UTC | yes | Update timestamp. |
| `travelerType` | enum | yes | Traveler type (see shared-primitives.md TravelerType enum). |
| `maskedDocumentRef` | string | no | Partially masked travel document number. |

### EligibilityDetermined

| Field | Description |
|---|---|
| **Producer** | traveler-profile |
| **Consumers** | offer-management, fare-pricing, journey-order |
| **Trigger** | Traveler eligibility verified (e.g. student, senior, military discount). |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `travelerId` | `TravelerId` | yes | Traveler ID (`tvl-<uuid>`). |
| `eligibilityRef` | `EligibilityRef` | yes | Canonical eligibility reference (see shared-primitives.md section 2b). |
| `determinedAt` | RFC3339 UTC | yes | When the eligibility was determined. |
| `validFrom` | RFC3339 UTC | yes | Eligibility validity start. |
| `validUntil` | RFC3339 UTC | yes | Eligibility validity end. |

### EligibilityExpired

| Field | Description |
|---|---|
| **Producer** | traveler-profile |
| **Consumers** | offer-management, fare-pricing, journey-order |
| **Trigger** | Eligibility validity window elapsed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `eligibilityId` | `EligibilityId` | yes | Eligibility ID that expired. |
| `travelerId` | `TravelerId` | yes | Traveler ID. |
| `expiredAt` | RFC3339 UTC | yes | Expiry timestamp. |

## Accepted Commands

### VerifyEligibility

| Field | Description |
|---|---|
| **Sender** | offer-management, fare-pricing |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `travelerId` | `TravelerId` | yes | Traveler to verify. |
| `eligibilityType` | enum | yes | Eligibility type to verify (see shared-primitives.md EligibilityType enum). |
| `requestedAt` | RFC3339 UTC | yes | Request timestamp. |
| `evidenceRef` | string | no | Reference to evidence document. |

## Cross-Context Dependencies

| Upstream Context | Consumed Data | Purpose |
|---|---|---|
| account | `AccountId` | Traveler identity linkage. |
| none | none | Traveler-profile is the source of truth for traveler data. |
