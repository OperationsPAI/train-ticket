# Marketing Campaign

Java/Spring service for campaign lifecycle, campaign budgets, coupon templates,
targeting decisions and issuance batches. Wallet / Promotion owns the issued
instrument and redemption ledger.

Status: implemented and deployed (Helm revision 521; see [current status](../../docs/00-current-status.md)).

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `marketing_campaign` on the `core` PostgreSQL instance
  (`services.marketing-campaign.db` and `postgres.instances.core.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/ready`,
`/metadata`.

## HTTP surface

- `/api/v1/campaigns`: create, read, `submit`, `approve`, `schedule`, `start`,
  `pause`, `complete`, `cancel`, `budget`, `templates`.
- `/api/v1/templates/{id}/validate|publish|retire`.
- `/api/v1/batches/{id}/start|close`.

There is no `docs/08-contracts/api/marketing-campaign.md` yet; the controllers
under `src/main/java/com/trainticket/marketingcampaign/api/` are the contract of
record.

## Events

Publishes campaign, budget, template and issuance-batch facts to
`events:marketing-campaign` through the transactional outbox (producer
`marketing-campaign` in `MarketingCampaignService`). Consumes
`events:journey-order`
(`infrastructure/messaging/MarketingCampaignSubscriptions.java`). There is no
`docs/08-contracts/events/marketing-campaign.md` yet.

## Metrics

Publishes the Java kit's process-level transport series; it owns no business
metric in
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Local validation

```bash
cd services/marketing-campaign && mvn -o test
```

Requires JDK 25 (the repo's devcontainer / `maven:3.9-eclipse-temurin-25` image).
