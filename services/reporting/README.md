# reporting

Domain: Reporting

Language: python

Phase: phase-1-limited

Reporting consumes operational events and maintains metric definitions, dashboard snapshots, and PostgreSQL revenue projections.

Contract baseline: 2026-09-25.

## Owns

- MetricDefinition
- DashboardReadModel
- FunnelView

## DDD Sources

- `docs/02-domains/reporting.md`

## Language Rationale

Python is a pragmatic fit for metric definitions, event-derived views, and analytics validation.

## Skeleton Check

```bash
PYTHONPATH=src python3 -m unittest discover -s tests
```

## Captured payment metrics

All monetary revenue metrics use `PaymentCaptured` only.
The retained API names `revenue`, `totalRevenue`, and `revenue_per_hour` mean gross captured payment amount.
Refunds and revenue recognition events do not increase or decrease this amount.
These fields do not represent net revenue or accounting recognized revenue.
Other consumed event types can still affect event counts, dashboard rebuilds, and anomaly rules.
Existing stored metric definitions are retained; the default catalog description applies to newly initialized definitions.

`reporting_revenue_breakdowns` groups five dimensions: `route`, `seat_class`, `channel`, `traveler_type`, and `time_period`.
The route and seat class materialized views retain their original columns, types, nullable dimension values, and unique index names.
Currency is part of every grouping key.
Breakdowns map missing dimensions to `unknown`, default missing currency to USD, and bucket event timestamps by UTC hour.
Both PostgreSQL and the in-memory aggregator retain a `PaymentCaptured` event with no amount for operational audit but exclude it from monetary totals and payment counts.
`totalRevenue` and `revenue_per_hour` are arrays of Money values ordered by ISO currency code.
The in-memory aggregator and PostgreSQL reader preserve every currency independently and never add or convert unlike currencies.
An empty monetary result is represented by an empty array rather than by a synthetic zero in a default currency.
The revenue endpoint paginates grouped `items` with `limit` and `offset` while `totalRevenue` always covers the complete filtered result.
`total` is the number of grouped rows before pagination; requesting an offset beyond the last row returns an empty `items` array with the same complete totals.

Revenue dimensions never trust route, seat class, traveler type, or attachment flags copied onto `PaymentCaptured`.
They resolve the payment `businessRef` to authoritative facts owned by Journey Order, Post Sales, Booking Orchestration, Seat Assignment, Ancillary Service, and Travel Insurance.
For a payment covering multiple segments or travelers, the amount is divided in minor units with any remainder assigned by stable dimension order; each dimension therefore sums exactly to the per-currency `totalRevenue`.
`ancillaryAttachRate` and `insuranceAttachRate` use order-level purchased attachment facts, not payment payload booleans.
Unknown is returned only when the authoritative relationship is absent after backfill.

## Coordinated revenue migrations

`005_gross_captured_revenue.sql` established the gross captured projection.
`006_exclude_incomplete_captures.sql` aligns PostgreSQL with the in-memory rule for captures that have no amount.
Both require an explicit `reporting.allow_revenue_rebuild=on` setting when source data exists.

`007_authoritative_revenue_facts.sql` adds staging tables without changing active views.
`scripts/backfill_revenue_dimensions.py` then reads source snapshots in bounded batches and writes only Reporting-owned facts.
`008_authoritative_revenue_views.sql` refuses to switch populated views unless the `authoritative-revenue-v1` completion marker exists and every captured payment has a reference.
Use `scripts/migrate_authoritative_revenue.py` for the coordinated 007 → backfill → 008 sequence while Reporting consumers are stopped.
Set explicit source database URLs when services are on different PostgreSQL instances; otherwise the script derives database names from the Reporting connection.
For a large installation, run `backfill_revenue_dimensions.py` once online without `--mark-complete`, record its UTC start time, then stop Reporting and run the coordinated command with `--since=<recorded-time>`.
Only the coordinated runner writes the completion marker by default; 008 also locks source metric rows and rechecks complete capture-reference coverage before building and swapping views.

```bash
REPORTING_DATABASE_URL=postgresql://.../reporting \
PAYMENT_DATABASE_URL=postgresql://.../payment \
JOURNEY_ORDER_DATABASE_URL=postgresql://.../journey_order \
POST_SALES_DATABASE_URL=postgresql://.../post_sales \
BOOKING_ORCHESTRATION_DATABASE_URL=postgresql://.../booking_orchestration \
SEAT_ASSIGNMENT_DATABASE_URL=postgresql://.../seat_assignment \
ANCILLARY_SERVICE_DATABASE_URL=postgresql://.../ancillary_service \
TRAVEL_INSURANCE_DATABASE_URL=postgresql://.../travel_insurance \
services/reporting/.venv/bin/python services/reporting/scripts/migrate_authoritative_revenue.py \
  --allow-rebuild --batch-size 2000 --batch-delay-ms 25 \
  --since 2026-09-25T04:00:00Z
```
An empty installation can apply the migration through normal startup.
The migration never deletes or updates `reporting_metric_events`.
It creates three replacement materialized views with data and unique indexes before dropping the previous derived views and restoring their names.
It preserves relation ownership and table and column grants.
External database dependencies cause `DROP ... RESTRICT` to fail and roll back the complete migration.
Names and column schemas remain stable; relation OIDs change.

The replacement build holds `ACCESS SHARE` on source events and locks on the new objects, allowing source ingestion and reads of the existing views.
The final drop and rename operations require `ACCESS EXCLUSIVE` locks on the original views until commit.
A reader holding a transaction can delay this final step; the migration uses a two second lock timeout and rolls back on failure.
Queued readers can wait behind the final exclusive lock request for this bounded interval per acquisition.
The session and transaction advisory locks use `hashtextextended('reporting:projection-refresh',0)`, matching the background refresher.
The coordinated runner uses one connection and a repeatable read transaction for all three replacement builds.
Events committed after its snapshot remain available for the next background refresh.
The migration does not advance or clear refresh request generations.

Run the command below only in the coordinated rebuild window after the broad validation and any operational deployment hold have ended.
Run it from the repository root with the reporting owner connection in `DATABASE_URL` and the reporting Python environment available.
The existing service may continue serving the old materialized results while replacements build; all reporting instances must use the updated code for consistent application metrics after the change.
Do not deploy a new instance that automatically attempts this populated migration before the coordinated runner has completed.

```bash
services/reporting/.venv/bin/python services/reporting/scripts/migrate_revenue.py \
  --allow-rebuild --statement-timeout-seconds 600
```

The runner calls Python-kit `run_migrations`, applies any pending revenue migration in order, and verifies that `006_exclude_incomplete_captures.sql` is recorded in `schema_migrations` in the same transaction as its SQL changes.
The session advisory lock covers the runner and its completion checks.
The runner reports total elapsed time and the SQL notice for the final swap duration, including any lock acquisition wait.
The swap notice is emitted before commit; exclusive locks remain held through the runner's version insertion and commit.
An active refresher causes an immediate failure; retry during an idle interval.
A lock timeout, dependency error, or statement timeout rolls back the SQL changes and version record; resolve the reported cause before retrying the same command.
A successful repeat invocation observes the version record and performs no revenue rebuild.
The 600 second statement timeout is an operator selected execution budget, not a predicted completion time.

The production directory sample was approximately 15.01 million rows and 575767 pages, or 4.39 GiB for one base relation pass.
Three passes represent approximately 13.18 GiB of base relation pages before caching and sorting effects.
The observed 3963623 reads and 611164160 temporary bytes were statistics window increments, not a measured migration duration.
Production planning retained sorting at `work_mem=8MB`; fewer scans do not guarantee proportional latency or temporary file reductions.
The coordinated production migration completed on 2026-09-23 in 10.359 seconds and preserved the source event table.
All three replacement views were populated at commit, and revision 278 deploys the matching service implementation.
Subsequent refreshes return the same monetary total and payment count for each of the five dimensions.
The measured migration duration is specific to that production snapshot and resource state.

## Verification

```bash
REPORTING_TEST_DATABASE_URL=postgresql://postgres:postgres@127.0.0.1:15432/postgres \
TEST_DATABASE_URL=postgresql://postgres:postgres@127.0.0.1:15432/postgres \
TEST_REPORTING_EVENTS_FILE=/absolute/path/to/recorded-reporting-events.json \
services/reporting/.venv/bin/python -m pytest services/reporting/tests -q
```

`tests/fixtures/gross_captured_recorded.json` contains unchanged production metric rows and the Finance `sourceEventId` evidence for 20 linked pairs.
Those pairs contribute 1928.59 CNY and 20 payments after migration.
Additional recorded rows cover both sides of a UTC hour boundary, null route and seat class dimensions, refunds, and recognition reversals.
Local migration tests check column types, grants, source preservation, repeated delivery, empty installation, refresh exclusion, dependency rollback, swap timeout, and the runner version record.
The recorded payments are CNY only; the plan test verifies currency in all five grouping keys, while the existing Money tests verify currency validation and reject currency mixing.
No recorded multi currency payment dataset was available, so a full multi currency event replay is not claimed.

A read-only production audit on 2026-09-25 found 2,904,448 `PaymentCaptured` rows, all with amount and currency; the only currency present was CNY.
The missing-amount regression therefore appends one synthetic incomplete capture to unchanged recorded production rows and proves that the source row is retained while both PostgreSQL and in-memory revenue stay at 20 payments and CNY 1928.59.

The current local verification on 2026-09-25 completed with 94 tests passed and one external-fixture test skipped, including PostgreSQL and Redis tests.
It covers online fact projection, out-of-order arrival, untrusted payment dimensions, minor-unit allocation, 007/008 gates, full and repeated batched backfill, empty installation, and the existing 005/006 migration regressions.
The earlier 2026-09-23 verification completed with 83 tests passed and zero skipped in 4.87 seconds.
The built reporting image also passed all 83 tests with zero skipped in 5.09 seconds using its installed runtime dependencies.
For the 40 recorded linked rows, migration SQL took 33.38 milliseconds; the coordinated runner took 53.26 milliseconds and reported 8.12 milliseconds for the final swap.
Reads of all three original views completed in 2.67 milliseconds while the replacement build was deliberately blocked on the local source relation.
A retained reader transaction caused the final swap to fail and roll back after 2.03 seconds.
These local measurements establish lock behavior and correctness; they do not estimate production rebuild duration.

## Operational windows

PostgreSQL computes operational metrics directly over indexed timestamp ranges; it does not load or truncate an arbitrary latest-event sample.
The windows are 60 seconds for `orders_per_second`, one hour for captured revenue, booking latency and scalper block rate, 24 hours for refund rate, and five minutes for context rollups.
`/api/v1/metrics/operational` intentionally excludes route rows; use the paginated `/api/v1/metrics/routes` endpoint for route detail.
The route endpoint covers the preceding 24 hours, orders rows by service date descending then route ID, and reports the complete group count independently of `limit` and `offset`.
It contains operational demand/capacity fields only; route revenue is available from `/api/v1/metrics/revenue?groupBy=route`.
On a populated database, run `scripts/prepare_operational_indexes.py` before registering migration 009 so the supporting indexes are built concurrently.
