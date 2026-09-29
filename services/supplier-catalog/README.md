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
- The identity a partner is filed under is `SUPPLIER_IDENTITY_SOURCE` in
  `services.supplier-catalog.env`: the field a registration's profile is taken
  from. It defaults to the code the registration hands over, and a release that
  names the brand files one partner per trading name instead, so two onboardings
  of one partner under two codes resolve to the entry the first one filed.
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/ready`,
`/metadata`.

Release values the service reads, in `services.supplier-catalog.env`:

| Value | Meaning |
|---|---|
| `SUPPLIER_IDENTITY_SOURCE` | The field a partner's profile is taken from: the code the registration handed over, or the brand the partner trades as. |
| `PG_MAX_POOL_SIZE` | Connections the service keeps to `supplier_catalog`. |
| `MIGRATIONS_DIR` | Where the image's migrations are read from. |

## HTTP surface

- `POST /api/v1/suppliers`, `GET /api/v1/suppliers`,
  `GET /api/v1/suppliers/{supplierId}`.
- `POST /api/v1/carriers`, `POST /api/v1/contracts`.

A supplier registration files one partner per identity: the second registration
of an identity that is already filed answers `CONFLICT`, which is what the
release's identity source decides.

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
