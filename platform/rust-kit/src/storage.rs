use std::fmt;
use std::path::{Path, PathBuf};
use std::time::Duration;

use async_trait::async_trait;
use serde::{Serialize, de::DeserializeOwned};
use serde_json::Value;
use sqlx::postgres::{PgConnectOptions, PgPoolOptions};
use sqlx::{Executor, PgPool, Postgres, Transaction};

use crate::idempotency::{IdempotencyRecord, IdempotencyStore};
use crate::messaging::EventEnvelope;

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
        let pool = PgPoolOptions::new()
            .max_connections(10)
            .acquire_timeout(Duration::from_secs(5))
            .connect_with(options)
            .await?;
        Ok(Self { pool })
    }

    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub fn pool(&self) -> &PgPool {
        &self.pool
    }

    pub async fn is_ready(&self) -> bool {
        sqlx::query("SELECT 1").execute(&self.pool).await.is_ok()
    }

    pub async fn migrate_dir(&self, migrations_dir: impl AsRef<Path>) -> Result<(), StorageError> {
        run_migrations(&self.pool, migrations_dir).await
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
            poll_interval: Duration::from_millis(250),
            batch_size: 100,
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
    let interval = config.poll_interval.min(Duration::from_millis(250));
    let mut tick = tokio::time::interval(interval);
    loop {
        tick.tick().await;
        if let Err(error) = relay_once(&pool, &client, config.batch_size).await {
            log::error!("outbox relay failed: {error}");
        }
    }
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

    let mut published = 0usize;
    for (seq, stream, envelope) in rows {
        let raw_envelope = serde_json::to_string(&envelope)?;
        let mut connection = client
            .get_multiplexed_async_connection()
            .await
            .map_err(|error| StorageError::Database(error.to_string()))?;
        redis::cmd("XADD")
            .arg(&stream)
            .arg("MAXLEN")
            .arg("~")
            .arg(crate::messaging::RETENTION_MAXLEN)
            .arg("*")
            .arg("envelope")
            .arg(&raw_envelope)
            .query_async::<_, String>(&mut connection)
            .await
            .map_err(|error| StorageError::Database(error.to_string()))?;
        sqlx::query(
            "UPDATE outbox SET published_at = now() WHERE seq = $1 AND published_at IS NULL",
        )
        .bind(seq)
        .execute(pool)
        .await?;
        published += 1;
    }
    Ok(published)
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
