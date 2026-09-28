# ancillary-service

Deployed service (Helm revision 521; see [current status](../../docs/00-current-status.md)).
The catalog still carries the activation labels `future-scope` /
`placeholder-skeleton` as metadata, but the service is built, deployed and part
of the running release.

Domain: Ancillary Service

Language: typescript

Phase: future-scope

Status: placeholder-skeleton

## Owns

- AncillaryOffer
- AncillaryOrderItem
- ActivationPolicy
- AncillaryCatalogItem

## Runtime

- HTTP port `8080` (`deploy/helm/train-ticket/templates/services.yaml` sets
  `containerPort`/`PORT`).
- Database `ancillary_service` on the `core` PostgreSQL instance
  (`services.ancillary-service.db` and `postgres.instances.core.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/live`,
`/livez`, `/ready`, `/metadata`.
- Catalogue intake policy: `config/catalog.properties` travels inside the image
  at `/app/config/catalog.properties` (`CATALOG_POLICY_FILE` overrides the
  path) and `src/catalog-policy.ts` reads it once at start-up.
  - `catalog.notice.floor.hours` is the notice, in hours before departure, the
    partner desk needs for an item it has to confirm with a supplier.
  - `catalog.notice.floor.methods` is the comma-separated list of fulfillment
    methods held to that floor. A method that is not listed keeps the
    `purchaseCutoffHoursBeforeDeparture` its own item declares, so the floor is
    carried by a release before the desk behind it is ready for it.

## HTTP surface

Three owning aggregates, all under `/api/v1`:

- `ancillary-catalog-items`: create, read, update, delete, `publish`,
  `suspend`, `expire`, `supersede`.
- `ancillary-offers`: create, read, `quote`, `select`.
- `ancillary-order-items`: list, read, `confirm`, `cancel`, `payment`,
  `payment/resolve-late`, `refund-suggestions`, `refunded`,
  `fulfillment-ready`, `fulfillment-facts`.

The full contract lives in
[`docs/08-contracts/api/ancillary-service.md`](../../docs/08-contracts/api/ancillary-service.md).

## Events

Publishes domain facts to `events:ancillary-service` through the transactional
outbox (`src/adapters/messaging/stream-config.ts`); the types and consumers are
in [`docs/08-contracts/events/ancillary-service.md`](../../docs/08-contracts/events/ancillary-service.md).
Consumes `events:journey-order`, `events:entitlement-ticketing` and
`events:payment`.

## Metrics

This service owns no service-specific business metric in the
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Build and test

```bash
cd services/ancillary-service && npm test
```

`npm test` compiles TypeScript into the ignored `test-output/` directory and
runs the Node built-in test suite. Live tests that need PostgreSQL or Redis
skip when `TEST_DATABASE_URL` / `TEST_REDIS_URL` are unset.

## DDD Sources

- `docs/02-domains/ancillary-service.md`

## Language Rationale

TypeScript fits productized add-ons and bundle-facing API composition.
