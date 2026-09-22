use std::fmt;
use std::path::{Path, PathBuf};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use std::time::Duration;

use async_trait::async_trait;
use serde::{Serialize, de::DeserializeOwned};
use serde_json::Value;
use sqlx::postgres::{PgConnectOptions, PgPoolOptions};
use sqlx::{Executor, PgPool, Postgres, Transaction};

use crate::idempotency::{IdempotencyRecord, IdempotencyStore};
use crate::messaging::EventEnvelope;
use crate::metrics::{self, POOL_NAME, PoolMetrics};

pub type PgTransaction<'a> = Transaction<'a, Postgres>;

#[derive(Debug, Clone)]
pub enum StorageError {
    Config(String),
    Migration(String),
    Database(String),
    Serialization(String),
    Conflict(String),
    IdempotencyKeyReused,
}

impl fmt::Display for StorageError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            StorageError::Config(message) => write!(f, "storage config error: {message}"),
            StorageError::Migration(message) => write!(f, "migration error: {message}"),
            StorageError::Database(message) => write!(f, "database error: {message}"),
            StorageError::Serialization(message) => write!(f, "serialization error: {message}"),
            StorageError::Conflict(message) => {
                write!(f, "optimistic concurrency conflict: {message}")
            }
            StorageError::IdempotencyKeyReused => write!(f, "IDEMPOTENCY_KEY_REUSED"),
        }
    }
}

impl std::error::Error for StorageError {}

impl From<sqlx::Error> for StorageError {
    fn from(error: sqlx::Error) -> Self {
        StorageError::Database(error.to_string())
    }
}

impl From<serde_json::Error> for StorageError {
    fn from(error: serde_json::Error) -> Self {
        StorageError::Serialization(error.to_string())
    }
}

#[derive(Clone)]
pub struct Storage {
    pool: PgPool,
    migrations_ready: Arc<AtomicBool>,
    /// `None` when metrics export is disabled, which is what keeps a service
    /// without a collector from building observable instruments nothing will
    /// collect.
    pool_metrics: Option<PoolMetrics>,
}

impl Storage {
    pub async fn from_env() -> Result<Self, StorageError> {
        let database_url = std::env::var("DATABASE_URL")
            .map_err(|_| StorageError::Config("DATABASE_URL is required".into()))?;
        Self::connect(&database_url).await
    }

    pub async fn connect(database_url: &str) -> Result<Self, StorageError> {
        let options: PgConnectOptions = database_url
            .parse()
            .map_err(|error| StorageError::Config(format!("invalid DATABASE_URL: {error}")))?;
        let max_conn: u32 = std::env::var("PG_MAX_POOL_SIZE")
            .ok().and_then(|v| v.parse().ok()).unwrap_or(10);
        let pool = PgPoolOptions::new()
            .max_connections(max_conn)
            .acquire_timeout(Duration::from_secs(5))
            .idle_timeout(Duration::from_secs(300))
            .max_lifetime(Duration::from_secs(600))
            .test_before_acquire(true)
            .connect_with(options)
            .await?;
        Ok(Self::new(pool))
    }

    /// Registers the pool's state instruments, so every Rust service is covered
    /// by building its storage through this type rather than by an edit of its
    /// own. The registration is skipped when metrics are disabled.
    pub fn new(pool: PgPool) -> Self {
        let service_name = std::env::var("OTEL_SERVICE_NAME").unwrap_or_default();
        let pool_metrics = if metrics::metrics_enabled() {
            Some(PoolMetrics::register(
                &metrics::meter(&service_name),
                &pool,
                POOL_NAME,
            ))
        } else {
            None
        };
        Self {
            pool,
            migrations_ready: Arc::new(AtomicBool::new(false)),
            pool_metrics,
        }
    }

    pub fn pool(&self) -> &PgPool {
        &self.pool
    }

    /// The handle a call site records one acquisition through. `None` when
    /// metrics are disabled. See [`PoolMetrics::record_acquire`] for why the
    /// wait time cannot come from the pool itself.
    pub fn pool_metrics(&self) -> Option<&PoolMetrics> {
        self.pool_metrics.as_ref()
    }

    pub fn mark_migrations_ready(&self) {
        self.migrations_ready.store(true, Ordering::SeqCst);
    }

    pub async fn is_ready(&self) -> bool {
        if !self.migrations_ready.load(Ordering::SeqCst) {
            return false;
        }
        matches!(
            tokio::time::timeout(
                Duration::from_millis(200),
                sqlx::query("SELECT 1").execute(&self.pool),
            )
            .await,
            Ok(Ok(_))
        )
    }

    pub async fn migrate_dir(&self, migrations_dir: impl AsRef<Path>) -> Result<(), StorageError> {
        run_migrations(&self.pool, migrations_dir).await?;
        self.mark_migrations_ready();
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Migration {
    pub version: String,
    pub path: PathBuf,
    pub sql: String,
}

pub fn load_migrations(migrations_dir: impl AsRef<Path>) -> Result<Vec<Migration>, StorageError> {
    let dir = migrations_dir.as_ref();
    let mut migrations = Vec::new();
    let entries = std::fs::read_dir(dir).map_err(|error| {
        StorageError::Migration(format!(
            "failed to read migrations dir {}: {error}",
            dir.display()
        ))
    })?;
    for entry in entries {
        let entry = entry.map_err(|error| StorageError::Migration(error.to_string()))?;
        let path = entry.path();
        if path.extension().and_then(|ext| ext.to_str()) != Some("sql") {
            continue;
        }
        let file_name = path
            .file_name()
            .and_then(|name| name.to_str())
            .ok_or_else(|| StorageError::Migration("migration filename is not UTF-8".into()))?;
        let Some((version, _)) = file_name.split_once('_') else {
            return Err(StorageError::Migration(format!(
                "migration file {file_name} must be named NNN_description.sql"
            )));
        };
        if version.is_empty() || !version.chars().all(|ch| ch.is_ascii_digit()) {
            return Err(StorageError::Migration(format!(
                "migration file {file_name} must start with numeric version"
            )));
        }
        let sql = std::fs::read_to_string(&path).map_err(|error| {
            StorageError::Migration(format!(
                "failed to read migration {}: {error}",
                path.display()
            ))
        })?;
        migrations.push(Migration {
            version: version.to_string(),
            path,
            sql,
        });
    }
    migrations.sort_by(|left, right| {
        left.version
            .cmp(&right.version)
            .then(left.path.cmp(&right.path))
    });
    Ok(migrations)
}

pub async fn run_migrations(
    pool: &PgPool,
    migrations_dir: impl AsRef<Path>,
) -> Result<(), StorageError> {
    pool.execute(
        "CREATE TABLE IF NOT EXISTS schema_migrations (version text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())",
    )
    .await?;

    for migration in load_migrations(migrations_dir)? {
        let mut tx = pool.begin().await?;
        let already_applied: Option<(String,)> =
            sqlx::query_as("SELECT version FROM schema_migrations WHERE version = $1")
                .bind(&migration.version)
                .fetch_optional(&mut *tx)
                .await?;
        if already_applied.is_some() {
            tx.commit().await?;
            continue;
        }
        tx.execute(migration.sql.as_str()).await.map_err(|error| {
            StorageError::Migration(format!("{} failed: {error}", migration.path.display()))
        })?;
        sqlx::query("INSERT INTO schema_migrations (version) VALUES ($1) ON CONFLICT DO NOTHING")
            .bind(&migration.version)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
    }
    Ok(())
}

#[derive(Debug, Clone, PartialEq)]
pub struct Snapshot<T> {
    pub id: String,
    pub version: i64,
    pub data: T,
}

#[derive(Clone)]
pub struct SnapshotRepository {
    table: String,
}

impl SnapshotRepository {
    pub fn new(table: impl Into<String>) -> Result<Self, StorageError> {
        let table = table.into();
        validate_identifier(&table)?;
        Ok(Self { table })
    }

    pub async fn get<T: DeserializeOwned>(
        &self,
        executor: impl Executor<'_, Database = Postgres>,
        id: &str,
    ) -> Result<Option<Snapshot<T>>, StorageError> {
        let sql = format!("SELECT id, version, data FROM {} WHERE id = $1", self.table);
        let row: Option<(String, i64, Value)> = sqlx::query_as(&sql)
            .bind(id)
            .fetch_optional(executor)
            .await?;
        row.map(|(id, version, data)| {
            Ok(Snapshot {
                id,
                version,
                data: serde_json::from_value(data)?,
            })
        })
        .transpose()
    }

    pub async fn save<T: Serialize>(
        &self,
        tx: &mut PgTransaction<'_>,
        id: &str,
        expected_version: Option<i64>,
        data: &T,
    ) -> Result<i64, StorageError> {
        let data = serde_json::to_value(data)?;
        match expected_version {
            Some(version) => {
                let sql = format!(
                    "UPDATE {} SET version = version + 1, data = $2, updated_at = now() WHERE id = $1 AND version = $3 RETURNING version",
                    self.table
                );
                let updated: Option<(i64,)> = sqlx::query_as(&sql)
                    .bind(id)
                    .bind(data)
                    .bind(version)
                    .fetch_optional(&mut **tx)
                    .await?;
                updated.map(|row| row.0).ok_or_else(|| {
                    StorageError::Conflict(format!(
                        "snapshot {id} version {version} was not current"
                    ))
                })
            }
            None => {
                let sql = format!(
                    "INSERT INTO {} (id, version, data) VALUES ($1, 1, $2) ON CONFLICT DO NOTHING RETURNING version",
                    self.table
                );
                let inserted: Option<(i64,)> = sqlx::query_as(&sql)
                    .bind(id)
                    .bind(data)
                    .fetch_optional(&mut **tx)
                    .await?;
                inserted
                    .map(|row| row.0)
                    .ok_or_else(|| StorageError::Conflict(format!("snapshot {id} already exists")))
            }
        }
    }
}

#[derive(Clone)]
pub struct OutboxAppender;

impl OutboxAppender {
    pub async fn append(
        tx: &mut PgTransaction<'_>,
        stream: &str,
        envelope: &EventEnvelope,
    ) -> Result<(), StorageError> {
        let envelope_json = serde_json::to_value(envelope)?;
        sqlx::query(
            "INSERT INTO outbox (event_id, stream, envelope) VALUES ($1, $2, $3) ON CONFLICT (event_id) DO NOTHING",
        )
        .bind(&envelope.event_id)
        .bind(stream)
        .bind(envelope_json)
        .execute(&mut **tx)
        .await?;
        Ok(())
    }
}

#[derive(Debug, Clone)]
pub struct OutboxRelayConfig {
    pub poll_interval: Duration,
    pub batch_size: i64,
}

impl Default for OutboxRelayConfig {
    fn default() -> Self {
        Self {
            poll_interval: Duration::from_millis(
                std::env::var("OUTBOX_POLL_INTERVAL_MS")
                    .ok()
                    .and_then(|v| v.parse().ok())
                    .unwrap_or(50),
            ),
            // Readable from the environment, as the interval already was. The
            // two together set the relay's ceiling, and only one of them being
            // tunable meant a service whose batch had become the bound could
            // not be given a larger one.
            batch_size: std::env::var("OUTBOX_BATCH_SIZE")
                .ok()
                .and_then(|v| v.parse().ok())
                .filter(|&n| n > 0)
                .unwrap_or(500),
        }
    }
}

#[cfg(feature = "redis-impl")]
pub fn spawn_outbox_relay(pool: PgPool, redis_url: String) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let client = match redis::Client::open(redis_url) {
            Ok(client) => client,
            Err(error) => {
                log::error!("failed to initialize outbox relay redis client: {error}");
                return;
            }
        };
        run_outbox_relay(pool, client, OutboxRelayConfig::default()).await;
    })
}

#[cfg(feature = "redis-impl")]
pub async fn run_outbox_relay(pool: PgPool, client: redis::Client, config: OutboxRelayConfig) {
    let interval = config.poll_interval.min(Duration::from_millis(500));
    let mut tick = tokio::time::interval(interval);
    let mut polls: u64 = 0;
    loop {
        tick.tick().await;
        if let Err(error) = relay_once(&pool, &client, config.batch_size).await {
            log::error!("outbox relay failed: {error}");
        }
        polls = polls.wrapping_add(1);
        if polls % CLEANUP_EVERY_N == 0 {
            cleanup(&pool).await;
        }
    }
}

/// Polls between retention sweeps, matching the other kits.
const CLEANUP_EVERY_N: u64 = 20;

/// Rows per retention DELETE.
const CLEANUP_BATCH_SIZE: i64 = 1_000;

/// Batches per table per sweep. Bounds one pass; the next pass resumes.
const CLEANUP_MAX_BATCHES: usize = 1;

/// Retention sweep for the three platform tables.
///
/// The other three kits each run this from their relay loop and this one did
/// not, so the tables grew for as long as the service ran. Measured on the
/// deployed cluster: capacity_availability held 857929 idempotency_records of
/// which 853203 were past the ten minute retention, the oldest 3 days 18 hours
/// old, and entitlement_ticketing held 716682 rows in 696 MB on the same
/// profile. `n_tup_del` for both tables was 0, so nothing had ever swept them.
///
/// The cost lands on the hot path: every consumed event checks
/// processed_events for deduplication, and every idempotent request checks
/// idempotency_records by key.
/// The tables the sweep covers, paired with the statement that drains each.
pub(crate) const RETENTION_SWEEPS: [(&str, &str); 3] = [
    (
        "outbox",
        "DELETE FROM outbox WHERE ctid IN (SELECT ctid FROM outbox \
         WHERE published_at IS NOT NULL AND published_at < now() - interval '30 seconds' \
         LIMIT $1 FOR UPDATE SKIP LOCKED)",
    ),
    (
        "processed_events",
        "DELETE FROM processed_events WHERE ctid IN (SELECT ctid FROM processed_events \
         WHERE processed_at < now() - interval '5 minutes' \
         LIMIT $1 FOR UPDATE SKIP LOCKED)",
    ),
    (
        "idempotency_records",
        "DELETE FROM idempotency_records WHERE ctid IN (SELECT ctid FROM idempotency_records \
         WHERE created_at < now() - interval '10 minutes' \
         LIMIT $1 FOR UPDATE SKIP LOCKED)",
    ),
];

#[cfg(feature = "redis-impl")]
async fn cleanup(pool: &PgPool) {
    for (table, batched_delete) in RETENTION_SWEEPS {
        sweep(pool, table, batched_delete).await;
    }
}

/// Runs one batched retention sweep.
///
/// Uses `ctid IN (SELECT ... LIMIT n)` because a plain `DELETE ... LIMIT` is
/// not valid in Postgres, and the subquery keeps the row set bounded.
///
/// The subquery takes `FOR UPDATE SKIP LOCKED` so that concurrent sweepers
/// claim disjoint rows rather than locking the same ones in opposite orders.
#[cfg(feature = "redis-impl")]
async fn sweep(pool: &PgPool, table: &str, batched_delete: &str) {
    let mut removed: u64 = 0;
    for _ in 0..CLEANUP_MAX_BATCHES {
        match sqlx::query(batched_delete)
            .bind(CLEANUP_BATCH_SIZE)
            .execute(pool)
            .await
        {
            Ok(result) => {
                let affected = result.rows_affected();
                removed += affected;
                if affected < CLEANUP_BATCH_SIZE as u64 {
                    return;
                }
            }
            Err(error) => {
                log::warn!(
                    "retention sweep for {table} failed after removing {removed} rows: {error}"
                );
                return;
            }
        }
    }
    log::warn!(
        "retention sweep for {table} removed {removed} rows and hit its batch budget; \
         the table is still above retention"
    );
}

#[cfg(feature = "redis-impl")]
pub async fn relay_once(
    pool: &PgPool,
    client: &redis::Client,
    batch_size: i64,
) -> Result<usize, StorageError> {
    let rows: Vec<(i64, String, Value)> = sqlx::query_as(
        "SELECT seq, stream, envelope FROM outbox WHERE published_at IS NULL ORDER BY seq LIMIT $1",
    )
    .bind(batch_size)
    .fetch_all(pool)
    .await?;

    if rows.is_empty() {
        return Ok(0);
    }

    // One connection for the whole batch, opened before the loop.
    //
    // It used to be opened per row. Measured on the deployed trip-planning:
    // the relay published 106 events per second, which is 9.5ms per event
    // against a batch of 100 every 50ms, so it was never interval-bound. The
    // outbox held 29740 unpublished events whose oldest was 250 seconds old,
    // growing in age while stable in depth, which is a relay draining at
    // exactly the arrival rate.
    //
    // The consequence was client-visible rather than internal: offer-management
    // refuses an offer whose ItineraryProposed it has not consumed, the load
    // generator retries that for 17 seconds, and the event arrived four
    // minutes later. Every purchase journey failed on
    // `No consumed Trip Planning itinerary found`.
    let mut connection = client
        .get_multiplexed_async_connection()
        .await
        .map_err(|error| StorageError::Database(error.to_string()))?;

    // Published sequences accumulate and are marked in one statement after the
    // loop. A per-row UPDATE is a second round trip per event, and the rows
    // are already ordered by seq so a crash between the XADD and the UPDATE
    // redelivers from the first unmarked one, which is the same at-least-once
    // guarantee the per-row form gave.
    let mut delivered: Vec<i64> = Vec::with_capacity(rows.len());
    for (seq, stream, envelope) in rows {
        let raw_envelope = serde_json::to_string(&envelope)?;
        crate::messaging::capped_xadd(&stream)
            .arg("envelope")
            .arg(&raw_envelope)
            .query_async::<_, String>(&mut connection)
            .await
            .map_err(|error| StorageError::Database(error.to_string()))?;
        delivered.push(seq);
    }

    if !delivered.is_empty() {
        sqlx::query(
            "UPDATE outbox SET published_at = now() \
             WHERE seq = ANY($1) AND published_at IS NULL",
        )
        .bind(&delivered)
        .execute(pool)
        .await?;
    }
    Ok(delivered.len())
}

/// Check-only sibling of [`mark_event_processing`]: true when the event was
/// already claimed. Lets multi-transaction handlers keep durable side effects
/// (e.g. persisted idempotency keys) in an early transaction while deferring
/// the dedup claim to the final ack transaction.
pub async fn event_already_processed(
    tx: &mut PgTransaction<'_>,
    event_id: &str,
    stream: &str,
) -> Result<bool, StorageError> {
    let row: Option<(i64,)> = sqlx::query_as(
        "SELECT 1 FROM processed_events WHERE event_id = $1 AND stream = $2",
    )
    .bind(event_id)
    .bind(stream)
    .fetch_optional(&mut **tx)
    .await?;
    Ok(row.is_some())
}

pub async fn mark_event_processing(
    tx: &mut PgTransaction<'_>,
    event_id: &str,
    stream: &str,
) -> Result<bool, StorageError> {
    let result = sqlx::query(
        "INSERT INTO processed_events (event_id, stream) VALUES ($1, $2) ON CONFLICT DO NOTHING",
    )
    .bind(event_id)
    .bind(stream)
    .execute(&mut **tx)
    .await?;
    Ok(result.rows_affected() == 1)
}

#[derive(Debug, Clone, PartialEq)]
pub enum IdempotencyTxDecision {
    Claimed,
    Replay(IdempotencyRecord),
}

#[derive(Clone)]
pub struct DbIdempotencyStore {
    pool: PgPool,
}

impl DbIdempotencyStore {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub async fn get_async(&self, key: &str) -> Result<Option<IdempotencyRecord>, StorageError> {
        let row: Option<(String, i32, Option<Value>)> = sqlx::query_as(
            "SELECT request_hash, status_code, response_body FROM idempotency_records WHERE key = $1",
        )
        .bind(key)
        .fetch_optional(&self.pool)
        .await?;
        row.map(|(fingerprint, status_code, body)| {
            let status_code = u16::try_from(status_code).map_err(|_| {
                StorageError::Database(format!("stored status code {status_code} is invalid"))
            })?;
            Ok(IdempotencyRecord {
                fingerprint,
                status_code,
                body: body.unwrap_or(Value::Null),
            })
        })
        .transpose()
    }

    pub async fn set_async(
        &self,
        key: String,
        record: IdempotencyRecord,
    ) -> Result<(), StorageError> {
        let result = sqlx::query(
            "INSERT INTO idempotency_records (key, request_hash, status_code, response_body) VALUES ($1, $2, $3, $4) ON CONFLICT DO NOTHING",
        )
        .bind(&key)
        .bind(&record.fingerprint)
        .bind(i32::from(record.status_code))
        .bind(&record.body)
        .execute(&self.pool)
        .await?;
        if result.rows_affected() == 1 {
            return Ok(());
        }

        let Some(existing) = self.get_async(&key).await? else {
            return Err(StorageError::Database(
                "idempotency insert conflicted but existing record was not found".into(),
            ));
        };
        if existing.fingerprint == record.fingerprint {
            Ok(())
        } else {
            Err(StorageError::IdempotencyKeyReused)
        }
    }

    pub async fn claim_response(
        tx: &mut PgTransaction<'_>,
        key: &str,
        request_hash: &str,
    ) -> Result<IdempotencyTxDecision, StorageError> {
        let result = sqlx::query(
            "INSERT INTO idempotency_records (key, request_hash, status_code, response_body) VALUES ($1, $2, 0, NULL) ON CONFLICT (key) DO NOTHING",
        )
        .bind(key)
        .bind(request_hash)
        .execute(&mut **tx)
        .await?;
        if result.rows_affected() == 1 {
            return Ok(IdempotencyTxDecision::Claimed);
        }
        Self::decision_for_existing(tx, key, request_hash).await
    }

    pub async fn record_response(
        tx: &mut PgTransaction<'_>,
        key: &str,
        request_hash: &str,
        status_code: u16,
        response_body: Value,
    ) -> Result<IdempotencyTxDecision, StorageError> {
        let result = sqlx::query(
            "INSERT INTO idempotency_records (key, request_hash, status_code, response_body) VALUES ($1, $2, $3, $4) ON CONFLICT (key) DO NOTHING",
        )
        .bind(key)
        .bind(request_hash)
        .bind(i32::from(status_code))
        .bind(&response_body)
        .execute(&mut **tx)
        .await?;
        if result.rows_affected() == 1 {
            return Ok(IdempotencyTxDecision::Claimed);
        }

        let existing = Self::decision_for_existing(tx, key, request_hash).await?;
        match existing {
            IdempotencyTxDecision::Claimed => {
                sqlx::query(
                    "UPDATE idempotency_records SET status_code = $2, response_body = $3 WHERE key = $1 AND request_hash = $4 AND status_code = 0 AND response_body IS NULL",
                )
                .bind(key)
                .bind(i32::from(status_code))
                .bind(response_body)
                .bind(request_hash)
                .execute(&mut **tx)
                .await?;
                Ok(IdempotencyTxDecision::Claimed)
            }
            replay => Ok(replay),
        }
    }

    async fn decision_for_existing(
        tx: &mut PgTransaction<'_>,
        key: &str,
        request_hash: &str,
    ) -> Result<IdempotencyTxDecision, StorageError> {
        let Some((fingerprint, status_code, body)) = sqlx::query_as::<_, (String, i32, Option<Value>)>(
            "SELECT request_hash, status_code, response_body FROM idempotency_records WHERE key = $1 FOR UPDATE",
        )
        .bind(key)
        .fetch_optional(&mut **tx)
        .await?
        else {
            return Err(StorageError::Database(
                "idempotency insert conflicted but existing record was not found".into(),
            ));
        };
        if fingerprint != request_hash {
            return Err(StorageError::IdempotencyKeyReused);
        }
        if status_code == 0 {
            return Ok(IdempotencyTxDecision::Claimed);
        }
        let status_code = u16::try_from(status_code).map_err(|_| {
            StorageError::Database(format!("stored status code {status_code} is invalid"))
        })?;
        Ok(IdempotencyTxDecision::Replay(IdempotencyRecord {
            fingerprint,
            status_code,
            body: body.unwrap_or(Value::Null),
        }))
    }
}

impl IdempotencyStore for DbIdempotencyStore {
    fn get(&self, key: &str) -> Option<IdempotencyRecord> {
        tokio::task::block_in_place(|| {
            tokio::runtime::Handle::current()
                .block_on(self.get_async(key))
                .ok()
                .flatten()
        })
    }

    fn set(&self, key: String, record: IdempotencyRecord) {
        let _ = tokio::task::block_in_place(|| {
            tokio::runtime::Handle::current().block_on(self.set_async(key, record))
        });
    }
}

#[async_trait]
pub trait AsyncIdempotencyStore: Send + Sync + 'static {
    async fn get(&self, key: &str) -> Result<Option<IdempotencyRecord>, StorageError>;
    async fn set(&self, key: String, record: IdempotencyRecord) -> Result<(), StorageError>;
}

#[async_trait]
impl AsyncIdempotencyStore for DbIdempotencyStore {
    async fn get(&self, key: &str) -> Result<Option<IdempotencyRecord>, StorageError> {
        self.get_async(key).await
    }

    async fn set(&self, key: String, record: IdempotencyRecord) -> Result<(), StorageError> {
        self.set_async(key, record).await
    }
}

fn validate_identifier(identifier: &str) -> Result<(), StorageError> {
    let mut chars = identifier.chars();
    let Some(first) = chars.next() else {
        return Err(StorageError::Config(
            "SQL identifier cannot be empty".into(),
        ));
    };
    if !(first.is_ascii_alphabetic() || first == '_') {
        return Err(StorageError::Config(format!(
            "invalid SQL identifier: {identifier}"
        )));
    }
    if !chars.all(|ch| ch.is_ascii_alphanumeric() || ch == '_') {
        return Err(StorageError::Config(format!(
            "invalid SQL identifier: {identifier}"
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn migrations_are_loaded_in_version_order() {
        let dir =
            std::env::temp_dir().join(format!("rust-kit-migrations-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("002_second.sql"), "SELECT 2;").unwrap();
        std::fs::write(dir.join("001_first.sql"), "SELECT 1;").unwrap();
        std::fs::write(dir.join("README.md"), "ignored").unwrap();

        let migrations = load_migrations(&dir).unwrap();
        let versions: Vec<_> = migrations
            .iter()
            .map(|migration| migration.version.as_str())
            .collect();
        assert_eq!(versions, vec!["001", "002"]);
        std::fs::remove_dir_all(dir).unwrap();
    }

    // This kit had no retention sweep at all, while the other three ran one
    // from their relay loop. The tables therefore grew for as long as the
    // service ran: on the deployed cluster capacity_availability held 857929
    // idempotency_records of which 853203 were past the ten minute retention,
    // the oldest 3 days 18 hours old, and `n_tup_del` on the table was 0.
    #[test]
    fn the_sweep_covers_every_platform_table() {
        let tables: Vec<&str> = RETENTION_SWEEPS.iter().map(|(table, _)| *table).collect();
        assert_eq!(
            tables,
            vec!["outbox", "processed_events", "idempotency_records"]
        );
    }

    #[test]
    fn every_sweep_is_bounded_and_claims_its_rows_with_skip_locked() {
        for (table, statement) in RETENTION_SWEEPS {
            assert!(
                statement.contains("LIMIT $1"),
                "an unbounded sweep deletes the whole backlog in one transaction: {table}"
            );
            assert!(
                statement.contains("FOR UPDATE SKIP LOCKED"),
                "concurrent sweepers would contend for the same rows: {table}"
            );
        }
    }

    #[test]
    fn snapshot_repository_rejects_unsafe_table_names() {
        assert!(SnapshotRepository::new("inventory_pool_snapshots").is_ok());
        assert!(SnapshotRepository::new("inventory_pool_snapshots; DROP TABLE outbox").is_err());
    }

    #[test]
    fn idempotency_tx_decision_replay_carries_stored_record() {
        let record = IdempotencyRecord {
            fingerprint: "sha256:same".into(),
            status_code: 201,
            body: serde_json::json!({ "status": "HELD" }),
        };
        let decision = IdempotencyTxDecision::Replay(record.clone());
        assert_eq!(decision, IdempotencyTxDecision::Replay(record));
    }

    #[tokio::test]
    #[ignore = "requires TEST_DATABASE_URL pointing at a disposable Postgres database"]
    async fn concurrent_different_idempotency_hash_rolls_back_loser_side_effects() {
        let database_url =
            std::env::var("TEST_DATABASE_URL").expect("TEST_DATABASE_URL is required");
        let pool = sqlx::postgres::PgPoolOptions::new()
            .max_connections(3)
            .connect(&database_url)
            .await
            .unwrap();
        sqlx::query(
            "CREATE TABLE IF NOT EXISTS idempotency_records (key text PRIMARY KEY, request_hash text NOT NULL, status_code int NOT NULL, response_body jsonb, created_at timestamptz NOT NULL DEFAULT now())",
        )
        .execute(&pool)
        .await
        .unwrap();
        let side_effect_table = format!(
            "test_idempotency_side_effects_{}",
            uuid::Uuid::now_v7().simple()
        );
        sqlx::query(&format!(
            "CREATE TABLE {side_effect_table} (id text PRIMARY KEY, writer text NOT NULL)"
        ))
        .execute(&pool)
        .await
        .unwrap();
        let key = format!("idem-{}", uuid::Uuid::now_v7());
        let winner_hash = "sha256:winner";
        let loser_hash = "sha256:loser";

        let mut winner = pool.begin().await.unwrap();
        assert_eq!(
            DbIdempotencyStore::claim_response(&mut winner, &key, winner_hash)
                .await
                .unwrap(),
            IdempotencyTxDecision::Claimed
        );
        sqlx::query(&format!(
            "INSERT INTO {side_effect_table} (id, writer) VALUES ($1, 'winner')"
        ))
        .bind("effect-winner")
        .execute(&mut *winner)
        .await
        .unwrap();

        let loser_pool = pool.clone();
        let loser_key = key.clone();
        let loser_table = side_effect_table.clone();
        let loser = tokio::spawn(async move {
            let mut tx = loser_pool.begin().await.unwrap();
            let claimed = DbIdempotencyStore::claim_response(&mut tx, &loser_key, loser_hash).await;
            match claimed {
                Ok(IdempotencyTxDecision::Claimed) => {
                    sqlx::query(&format!(
                        "INSERT INTO {loser_table} (id, writer) VALUES ($1, 'loser')"
                    ))
                    .bind("effect-loser")
                    .execute(&mut *tx)
                    .await
                    .unwrap();
                    tx.commit().await.unwrap();
                    Ok(())
                }
                Ok(IdempotencyTxDecision::Replay(_)) => {
                    tx.commit().await.unwrap();
                    Ok(())
                }
                Err(error) => {
                    tx.rollback().await.unwrap();
                    Err(error)
                }
            }
        });

        tokio::time::sleep(Duration::from_millis(100)).await;
        assert!(
            !loser.is_finished(),
            "loser must wait on the winner's uncommitted key"
        );
        assert_eq!(
            DbIdempotencyStore::record_response(
                &mut winner,
                &key,
                winner_hash,
                201,
                serde_json::json!({ "status": "HELD" }),
            )
            .await
            .unwrap(),
            IdempotencyTxDecision::Claimed
        );
        winner.commit().await.unwrap();

        let loser_result = loser.await.unwrap();
        assert!(matches!(
            loser_result,
            Err(StorageError::IdempotencyKeyReused)
        ));
        let (request_hash, status_code): (String, i32) = sqlx::query_as(
            "SELECT request_hash, status_code FROM idempotency_records WHERE key = $1",
        )
        .bind(&key)
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(request_hash, winner_hash);
        assert_eq!(status_code, 201);
        let (side_effects,): (i64,) =
            sqlx::query_as(&format!("SELECT count(*) FROM {side_effect_table}"))
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(side_effects, 1);
        sqlx::query(&format!("DROP TABLE {side_effect_table}"))
            .execute(&pool)
            .await
            .unwrap();
        sqlx::query("DELETE FROM idempotency_records WHERE key = $1")
            .bind(&key)
            .execute(&pool)
            .await
            .unwrap();
    }
}
