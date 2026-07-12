# Supplier Catalog — Events & Commands

Last updated: 2026-07-04

## Published Events

### SupplierRegistered

| Field | Description |
|---|---|
| **Producer** | supplier-catalog |
| **Consumers** | notification, reporting, admin-audit |
| **Trigger** | `RegisterSupplier` command processed with valid supplier profile. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `supplierId` | string | yes | Canonical supplier ID (`sup-<uuid>`). |
| `legalName` | string | yes | Legal entity name of the supplier. |
| `brandName` | string | yes | Brand or trading name. |
| `status` | enum | yes | `DRAFT`, `UNDER_REVIEW`, `ACTIVE`, `SUSPENDED`, `INACTIVE`, `REJECTED`, `ARCHIVED`. |
| `registeredAt` | RFC3339 UTC | yes | When the supplier was registered. |

### CarrierRegistered

| Field | Description |
|---|---|
| **Producer** | supplier-catalog |
| **Consumers** | service-plan, provider-integration, reporting |
| **Trigger** | `RegisterCarrier` command processed with valid carrier data. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `carrierId` | string | yes | Canonical carrier ID (`car-<uuid>`). |
| `supplierId` | string | yes | Parent supplier ID (`sup-<uuid>`). |
| `name` | string | yes | Carrier display name. |
| `code` | string | yes | Carrier code (e.g. `BJRAIL`, `CA`, `MU`). |
| `transportMode` | string | yes | Transport mode (e.g. `RAIL`, `AIR`, `COACH`, `FERRY`, `RIDE_HAILING`). |
| `registeredAt` | RFC3339 UTC | yes | When the carrier was registered. |

### ContractActivated

| Field | Description |
|---|---|
| **Producer** | supplier-catalog |
| **Consumers** | fare-pricing, finance-settlement, provider-integration, notification |
| **Trigger** | `ActivateContract` command processed; contract transitions to PUBLISHED. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `contractId` | string | yes | Canonical contract ID (`ctr-<uuid>`). |
| `supplierId` | string | yes | Supplier ID (`sup-<uuid>`). |
| `contractNo` | string | yes | External contract reference number. |
| `validWindow` | `TimeWindow` | yes | Contract validity period `[start, end)`. |
| `activatedAt` | RFC3339 UTC | yes | When the contract was activated. |

### ContractSuspended

| Field | Description |
|---|---|
| **Producer** | supplier-catalog |
| **Consumers** | fare-pricing, provider-integration, booking-orchestration, notification |
| **Trigger** | `SuspendContract` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `contractId` | string | yes | Canonical contract ID (`ctr-<uuid>`). |
| `supplierId` | string | yes | Supplier ID (`sup-<uuid>`). |
| `contractNo` | string | yes | External contract reference number. |
| `suspendedAt` | RFC3339 UTC | yes | When the contract was suspended. |

### ProductCapabilityDeclared

| Field | Description |
|---|---|
| **Producer** | supplier-catalog |
| **Consumers** | provider-integration, booking-orchestration, capacity-availability |
| **Trigger** | `DeclareProductCapability` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `capabilityId` | string | yes | Canonical capability ID (`pcap-<uuid>`). |
| `supplierId` | string | yes | Supplier ID (`sup-<uuid>`). |
| `contractId` | string | yes | Contract ID (`ctr-<uuid>`). |
| `productName` | string | yes | Name of the product (e.g. `Standard Ticket`). |
| `capability` | string | yes | Capability name (e.g. `SEARCH`, `RESERVE`, `ISSUE`). |
| `status` | enum | yes | `DRAFT`, `VALIDATED`, `PUBLISHED`, `RETIRED`. |
| `validWindow` | `TimeWindow` | yes | Capability validity period `[start, end)`. |
| `declaredAt` | RFC3339 UTC | yes | When the capability was declared. |

### ExternalCodeMapped

| Field | Description |
|---|---|
| **Producer** | supplier-catalog |
| **Consumers** | provider-integration, service-plan, fare-pricing |
| **Trigger** | `MapExternalCode` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `codeId` | string | yes | Canonical code mapping ID (`ec-<uuid>`). |
| `supplierId` | string | yes | Supplier ID (`sup-<uuid>`). |
| `codeType` | enum | yes | `STATION`, `SERVICE_CLASS`, `FARE_FAMILY`, `PRODUCT`, `ANCILLARY`, `RULE`. |
| `externalCode` | string | yes | External code from the supplier system. |
| `internalRef` | string | yes | Internal platform reference (e.g. PlaceId, ServiceClass name). |
| `status` | enum | yes | `ACTIVE`, `CONFLICT`, `RETIRED`. |
| `mappedAt` | RFC3339 UTC | yes | When the mapping was created. |

## Accepted Commands

### RegisterSupplier

| Field | Description |
|---|---|
| **Sender** | Admin UI / Import batch |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `supplierId` | string | yes | Canonical supplier ID (`sup-<uuid>`). |
| `legalName` | string | yes | Legal entity name. |
| `brandName` | string | yes | Brand/trading name. |
| `profile` | string | no | Supplier profile description. |

### RegisterCarrier

| Field | Description |
|---|---|
| **Sender** | Admin UI / Import batch |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `carrierId` | string | yes | Canonical carrier ID (`car-<uuid>`). |
| `supplierId` | string | yes | Parent supplier ID. |
| `name` | string | yes | Carrier display name. |
| `code` | string | yes | Carrier code. |
| `transportMode` | string | yes | Transport mode. |

### ActivateContract

| Field | Description |
|---|---|
| **Sender** | Admin UI |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `contractId` | string | yes | Contract ID to activate. |

### SuspendContract

| Field | Description |
|---|---|
| **Sender** | Admin UI / Automated policy |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `contractId` | string | yes | Contract ID to suspend. |

### DeclareProductCapability

| Field | Description |
|---|---|
| **Sender** | Admin UI / Capability discovery |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `capabilityId` | string | yes | Canonical capability ID. |
| `supplierId` | string | yes | Supplier ID. |
| `contractId` | string | yes | Contract ID. |
| `productName` | string | yes | Product name. |
| `capability` | string | yes | Capability name. |
| `validWindow` | `TimeWindow` | yes | Capability validity window. |

### MapExternalCode

| Field | Description |
|---|---|
| **Sender** | Admin UI / Import batch / ACL |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `codeId` | string | yes | Canonical code mapping ID. |
| `supplierId` | string | yes | Supplier ID. |
| `codeType` | enum | yes | Code type. |
| `externalCode` | string | yes | External code. |
| `internalRef` | string | yes | Internal platform reference. |

## Cross-Context Dependencies

| Upstream Context | Consumed Data | Purpose |
|---|---|---|
| place-network | PlaceId, TransportNodeId | Supplier location mapping references. |
| admin-audit | Operator identity, approval | Supplier/contract registration and activation approval. |
| provider-integration | Provider capability discovery | External capability data to validate against declared capabilities. |
| risk-compliance | Supplier risk flags | Supplier eligibility for activation. |
