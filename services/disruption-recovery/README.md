# disruption-recovery

Deployed service (Helm revision 521; see [current status](../../docs/00-current-status.md)).
The catalog still carries the activation labels `future-scope` /
`placeholder-skeleton` as metadata, but the service is built, deployed and part
of the running release.

Domain: Disruption Recovery

Language: python

Phase: future-scope

Status: placeholder-skeleton

## Owns

- RecoveryCase
- RecoveryOption
- CompensationDecision
- Incident
- ServiceAlert

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `disruption_recovery` on the `core` PostgreSQL instance
  (`services.disruption-recovery.db` and `postgres.instances.core.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/live`, `/ready`,
`/metadata`.

## HTTP surface

- `POST /api/v1/disruptions` reports a disruption fact and merges it into an
  incident.
- `/api/v1/recovery-cases` list/read plus `{id}/select-option`, `{id}/close`,
  `{id}/manual-review/resolve`.
- `/api/v1/service-alerts` list/read and `/api/v1/incidents/{id}`.

The full contract lives in
[`docs/08-contracts/api/disruption-recovery.md`](../../docs/08-contracts/api/disruption-recovery.md).

## Events

Publishes to `events:disruption-recovery`; the event types and their consumers
are in [`docs/08-contracts/events/disruption-recovery.md`](../../docs/08-contracts/events/disruption-recovery.md).
Consumes `events:journey-order`, `events:post-sales`, `events:fulfillment` and
`events:provider-integration`
(`src/disruption_recovery/adapters/messaging/__init__.py`).

## Metrics

Emits `train_ticket.disruption.segment_signals`, a bounded counter over
`signal_type`, `outcome` and `source`
(`src/disruption_recovery/metrics.py`).

## Build and test

```bash
cd services/disruption-recovery && PYTHONPATH=src:../../platform/python-kit/src python3 -m pytest -q
```

Live tests that need PostgreSQL or Redis skip when the corresponding
`TEST_DATABASE_URL` / `TEST_REDIS_URL` variables are unset.

## DDD Sources

- `docs/02-domains/disruption-recovery.md`

## Language Rationale

Python fits recovery optimization and policy experimentation when this future domain is enabled.
