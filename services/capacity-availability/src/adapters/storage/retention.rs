//! Retention sweep for `capacity_supply_observations`.
//!
//! That table records the supply evidence each admission folded: one row per
//! inventory pool, keyed by `inventory_pool_id`, upserted by the admission path
//! only when the seat-map identity changes. No production code reads it back --
//! only the supply tests do -- so a row has no reader that would justify keeping
//! it forever.
//!
//! It grew because every load generator run bootstraps new segments and
//! therefore new pools, and a pool that is never observed again keeps its last
//! row. Measured on the live cluster it held 746808 rows across 12 GB. The sweep
//! is the platform kit's shape: a bounded, batched `DELETE` that claims its rows
//! with `FOR UPDATE SKIP LOCKED`, so a pass never holds the table and two
//! sweepers never contend for the same row. It deviates from the kit's exact
//! statement in one place, and the plan that motivated that is documented on
//! `SUPPLY_OBSERVATION_SWEEP` below.
//!
//! `seat_maps` in Seat Assignment is deliberately not swept here:
//! `resolveBoundPhysicalUnit` still reads retired maps to place a hold whose unit
//! left the current composition, so a row older than any window is still live.
//! It is recorded against this same work item in `docs/TODO.md`.

use super::*;
use std::time::Duration;

/// Rows per retention DELETE. Matches `CLEANUP_BATCH_SIZE` in the platform kit.
const SWEEP_BATCH_SIZE: i64 = 1_000;

/// Batches per pass. Bounds how much one pass does; the next pass resumes. The
/// kit uses a single batch per cleanup, but that loop turns every twenty
/// delivery intervals while this timer runs on its own cadence, so a pass has to
/// be able to make headway against a backlog the generator built up.
const SWEEP_MAX_BATCHES: usize = 10;

/// How long an observation is kept when nothing overrides it. A row is supply
/// evidence for one admission, and nothing reads it after the Hold it admitted
/// has expired, so a week is far past any reader.
const DEFAULT_RETENTION: Duration = Duration::from_secs(7 * 24 * 60 * 60);

/// How often the sweep runs. The window is measured in days, so a tighter
/// cadence buys nothing; what keeps the sweep off the request path is the
/// per-pass budget below, not the interval.
const SWEEP_INTERVAL: Duration = Duration::from_secs(600);

/// The one sweep statement.
///
/// `< $2` is the window boundary bound as a number of seconds and scaled to an
/// interval, rather than interpolated as a literal, so the window can change
/// without replanning and the statement stays a single prepared query. The
/// `LIMIT $1` bounds the row set, the `ORDER BY observed_at` lets the index on
/// `observed_at` serve it, and `FOR UPDATE SKIP LOCKED` claims the rows so a
/// concurrent sweeper takes others.
///
/// The kit writes the outer match as `ctid IN (SELECT ctid ...)`, and this is
/// the same statement with the rows collected through `ARRAY(...)` first. The
/// difference is not cosmetic: against a real table `IN (subquery)` deletes
/// through a hash join that plans a Seq Scan of the whole target relation, so
/// every batch would read all 12 GB before deleting its thousand rows, and this
/// table is the one the retention index exists to keep off a full scan.
/// `ctid = ANY(ARRAY(...))` plans as a Tid Scan (`EXPLAIN` on PostgreSQL 16),
/// so both the selection and the delete are index-driven. The bounded batch and
/// the `SKIP LOCKED` claim, which are what the kit's shape is really about, are
/// unchanged.
const SUPPLY_OBSERVATION_SWEEP: &str = "DELETE FROM capacity_supply_observations WHERE ctid = \
     ANY(ARRAY(SELECT ctid FROM capacity_supply_observations \
     WHERE observed_at < now() - ($2::float8 * interval '1 second') \
     ORDER BY observed_at LIMIT $1 FOR UPDATE SKIP LOCKED))";

/// The retention window, overridable from the environment so the chart owns it.
///
/// A non-numeric or zero value is ignored rather than honoured: a zero window
/// would sweep the row an admission had only just written.
fn retention_window() -> Duration {
    retention_from(
        std::env::var("CAPACITY_SUPPLY_RETENTION_HOURS")
            .ok()
            .as_deref(),
    )
}

/// The parsing `retention_window` does, split out so it can be tested without
/// mutating the process environment.
fn retention_from(override_hours: Option<&str>) -> Duration {
    override_hours
        .and_then(|value| value.trim().parse::<u64>().ok())
        .filter(|hours| *hours > 0)
        .map(|hours| Duration::from_secs(hours.saturating_mul(60 * 60)))
        .unwrap_or(DEFAULT_RETENTION)
}

/// Runs one bounded, batched sweep and returns the rows removed.
///
/// The batch budget, not the window, is what bounds a pass: a table far above
/// retention is drained by the following passes instead of in one transaction
/// that holds locks for as long as the delete takes. Hitting the budget is
/// logged, because it is the signal that the table is not converging.
pub(super) async fn sweep_supply_observations(
    pool: &PgPool,
    retention: Duration,
    batch_size: i64,
    max_batches: usize,
) -> u64 {
    let mut removed: u64 = 0;
    for _ in 0..max_batches {
        match sqlx::query(SUPPLY_OBSERVATION_SWEEP)
            .bind(batch_size)
            .bind(retention.as_secs_f64())
            .execute(pool)
            .await
        {
            Ok(result) => {
                let affected = result.rows_affected();
                removed += affected;
                if affected < batch_size as u64 {
                    return removed;
                }
            }
            Err(error) => {
                log::warn!(
                    "retention sweep for capacity_supply_observations failed after removing \
                     {removed} rows: {error}"
                );
                return removed;
            }
        }
    }
    log::warn!(
        "retention sweep for capacity_supply_observations removed {removed} rows and hit its \
         batch budget; the table is still above retention"
    );
    removed
}

impl PostgresCapacityService {
    /// Sweep on a timer, the way the business levels are rebuilt. A failed sweep
    /// leaves the rows in place and the next pass retries: a table that is too
    /// large is not a reason to stop serving admission.
    pub fn spawn_supply_observation_sweep(&self) {
        let pool = self.pool().clone();
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(SWEEP_INTERVAL);
            loop {
                ticker.tick().await;
                sweep_supply_observations(
                    &pool,
                    retention_window(),
                    SWEEP_BATCH_SIZE,
                    SWEEP_MAX_BATCHES,
                )
                .await;
            }
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sqlx::PgPool;
    use std::str::FromStr;
    use std::time::Duration;

    #[test]
    fn the_window_defaults_to_a_week() {
        assert_eq!(retention_from(None), Duration::from_secs(604_800));
    }

    #[test]
    fn the_window_reads_hours_and_refuses_to_erase_everything() {
        assert_eq!(retention_from(Some("24")), Duration::from_secs(86_400));
        assert_eq!(retention_from(Some(" 48 ")), Duration::from_secs(172_800));
        // A zero, negative or unparseable window falls back to the default: a
        // zero window would delete the row an admission had just written.
        assert_eq!(retention_from(Some("0")), Duration::from_secs(604_800));
        assert_eq!(retention_from(Some("-1")), Duration::from_secs(604_800));
        assert_eq!(retention_from(Some("soon")), Duration::from_secs(604_800));
    }

    #[test]
    fn the_sweep_is_bounded_and_claims_its_rows_with_skip_locked() {
        // The invariants the kit's own sweep is held to, because this one has to
        // run on a table of sixteen kilobyte documents rather than three narrow
        // platform rows.
        assert!(
            SUPPLY_OBSERVATION_SWEEP.contains("LIMIT $1"),
            "an unbounded sweep deletes the whole backlog in one transaction"
        );
        assert!(
            SUPPLY_OBSERVATION_SWEEP.contains("FOR UPDATE SKIP LOCKED"),
            "concurrent sweepers would contend for the same rows"
        );
        assert!(
            SUPPLY_OBSERVATION_SWEEP.contains("observed_at <"),
            "the sweep must filter on the indexed retention column"
        );
        assert!(
            SUPPLY_OBSERVATION_SWEEP.contains("ORDER BY observed_at"),
            "the batch must be taken in window order"
        );
        assert!(
            SUPPLY_OBSERVATION_SWEEP.contains("ctid = ANY(ARRAY("),
            "the batch must delete through the ctid array so the delete is a Tid Scan rather \
             than a second full scan of the table"
        );
    }

    /// A disposable schema on `TEST_DATABASE_URL`, with the observation table
    /// created from the migration the sweep runs against in production.
    async fn live_schema() -> (PgPool, PgPool, String) {
        let url = std::env::var("TEST_DATABASE_URL").expect("TEST_DATABASE_URL is required");
        let admin = sqlx::postgres::PgPoolOptions::new()
            .max_connections(1)
            .connect(&url)
            .await
            .expect("connect to TEST_DATABASE_URL");
        let schema = format!(
            "capacity_supply_retention_{}",
            uuid::Uuid::now_v7().simple()
        );
        sqlx::query(&format!("CREATE SCHEMA {schema}"))
            .execute(&admin)
            .await
            .expect("create schema");
        let pool = sqlx::postgres::PgPoolOptions::new()
            .max_connections(2)
            .connect_with(
                sqlx::postgres::PgConnectOptions::from_str(&url)
                    .expect("parse TEST_DATABASE_URL")
                    .options([("search_path", schema.as_str())]),
            )
            .await
            .expect("connect with search_path");
        sqlx::raw_sql(include_str!(
            "../../../migrations/009_reservation_supply.sql"
        ))
        .execute(&pool)
        .await
        .expect("create the observation table");
        // The same index the production migration adds, so a broken index
        // statement fails here rather than only in a cluster.
        sqlx::raw_sql(include_str!(
            "../../../migrations/010_supply_observation_retention_index.sql"
        ))
        .execute(&pool)
        .await
        .expect("create the retention index");
        (admin, pool, schema)
    }

    /// Inserts one observation older than `age`, when `age` is given.
    async fn insert_observation(pool: &PgPool, id: &str, age: Option<Duration>) {
        match age {
            Some(age) => {
                sqlx::query(
                    "INSERT INTO capacity_supply_observations \
                     (inventory_pool_id, seat_map_id, seat_map_version, sellable_capacity, data, \
                      observed_at) VALUES ($1, $2, 1, 1, '{}'::jsonb, now() - ($3::float8 * \
                      interval '1 second'))",
                )
                .bind(id)
                .bind(format!("seat-map-{id}"))
                .bind(age.as_secs_f64())
                .execute(pool)
                .await
                .expect("insert aged observation");
            }
            None => {
                sqlx::query(
                    "INSERT INTO capacity_supply_observations \
                     (inventory_pool_id, seat_map_id, seat_map_version, sellable_capacity, data) \
                     VALUES ($1, $2, 1, 1, '{}'::jsonb)",
                )
                .bind(id)
                .bind(format!("seat-map-{id}"))
                .execute(pool)
                .await
                .expect("insert observation");
            }
        }
    }

    async fn remaining(pool: &PgPool) -> i64 {
        sqlx::query_scalar("SELECT count(*) FROM capacity_supply_observations")
            .fetch_one(pool)
            .await
            .expect("count observations")
    }

    #[tokio::test]
    #[ignore = "requires TEST_DATABASE_URL pointing at a disposable Postgres database"]
    async fn the_sweep_deletes_only_rows_past_the_window() {
        let (admin, pool, schema) = live_schema().await;
        insert_observation(&pool, "fresh", None).await;
        insert_observation(&pool, "stale", Some(Duration::from_secs(30 * 24 * 60 * 60))).await;

        let removed = sweep_supply_observations(
            &pool,
            DEFAULT_RETENTION,
            SWEEP_BATCH_SIZE,
            SWEEP_MAX_BATCHES,
        )
        .await;

        assert_eq!(removed, 1, "exactly the row past the window is removed");
        let ids: Vec<String> = sqlx::query_scalar(
            "SELECT inventory_pool_id FROM capacity_supply_observations ORDER BY inventory_pool_id",
        )
        .fetch_all(&pool)
        .await
        .expect("read survivors");
        assert_eq!(ids, vec!["fresh".to_string()]);

        let _ = sqlx::query(&format!("DROP SCHEMA {schema} CASCADE"))
            .execute(&admin)
            .await;
    }

    #[tokio::test]
    #[ignore = "requires TEST_DATABASE_URL pointing at a disposable Postgres database"]
    async fn the_sweep_stops_at_its_batch_budget() {
        let (admin, pool, schema) = live_schema().await;
        for index in 0..5 {
            insert_observation(
                &pool,
                &format!("pool-{index}"),
                Some(Duration::from_secs(30 * 24 * 60 * 60)),
            )
            .await;
        }

        // One batch of two, and the pass must stop there rather than delete the
        // remaining three rows.
        let batch = 2_i64;
        let first = sweep_supply_observations(&pool, DEFAULT_RETENTION, batch, 1).await;
        assert_eq!(first, batch as u64);
        assert_eq!(
            remaining(&pool).await,
            3,
            "a pass that hit its budget left the rest of the backlog for later passes"
        );

        // The following passes resume: 2, then 2, then the final 1.
        let second = sweep_supply_observations(&pool, DEFAULT_RETENTION, batch, 1).await;
        assert_eq!(second, batch as u64);
        let third = sweep_supply_observations(&pool, DEFAULT_RETENTION, batch, 1).await;
        assert_eq!(third, 1);
        assert_eq!(remaining(&pool).await, 0);

        // An empty table costs one bounded statement and removes nothing.
        let empty =
            sweep_supply_observations(&pool, DEFAULT_RETENTION, batch, SWEEP_MAX_BATCHES).await;
        assert_eq!(empty, 0);

        let _ = sqlx::query(&format!("DROP SCHEMA {schema} CASCADE"))
            .execute(&admin)
            .await;
    }
}
