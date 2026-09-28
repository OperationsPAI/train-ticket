# supplier-catalog

Domain: Supplier Catalog

Language: golang

Phase: phase-1-support

Status: implemented and deployed (Helm revision 521; see [current status](../../docs/00-current-status.md))

## Owns

- Supplier
- Carrier
- Contract
- ProductCapability
- ExternalCode

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `supplier_catalog` on the `core` PostgreSQL instance
  (`services.supplier-catalog.db` and `postgres.instances.core.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/ready`,
`/metadata`.

## HTTP surface

- `POST /api/v1/suppliers`, `GET /api/v1/suppliers`,
  `GET /api/v1/suppliers/{supplierId}`.
- `POST /api/v1/carriers`, `POST /api/v1/contracts`.

The full contract lives in
[`docs/08-contracts/api/supplier-catalog.md`](../../docs/08-contracts/api/supplier-catalog.md).

## Events

Publishes `SupplierRegistered`, `CarrierRegistered`, `ContractActivated`,
`ContractSuspended`, `ProductCapabilityDeclared` and `ExternalCodeMapped` to
`events:supplier-catalog`; see
[`docs/08-contracts/events/supplier-catalog.md`](../../docs/08-contracts/events/supplier-catalog.md).
It consumes no inbound event stream
(`internal/adapters/messaging/runtime.go`).

## Metrics

This service owns no service-specific business metric in the
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Build and test

```bash
cd services/supplier-catalog && go test ./...
```

## DDD Sources

- `docs/02-domains/supplier-catalog.md`

## Language Rationale

Go keeps supplier capability lookup compact and separate from provider runtime health.
