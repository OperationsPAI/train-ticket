#![cfg(feature = "redis-impl")]

use redis::AsyncCommands;
use rust_kit::messaging::EventEnvelope;
use rust_kit::storage::{OutboxAppender, relay_once};
use sqlx::postgres::{PgConnectOptions, PgPoolOptions};
use std::str::FromStr;
use std::time::Duration;

#[tokio::test]
#[ignore = "requires TEST_DATABASE_URL and TEST_REDIS_URL"]
async fn batch_delivery_preserves_order_and_retries_every_unacknowledged_event() {
    let url = std::env::var("TEST_DATABASE_URL").unwrap();
    let admin = PgPoolOptions::new().max_connections(1).connect(&url).await.unwrap();
    let schema = format!("outbox_pipeline_{}", uuid::Uuid::now_v7().simple());
    sqlx::query(&format!("CREATE SCHEMA {schema}")).execute(&admin).await.unwrap();
    let options = PgConnectOptions::from_str(&url).unwrap().options([("search_path", schema.as_str())]);
    let pool = PgPoolOptions::new().max_connections(2).connect_with(options).await.unwrap();
    sqlx::query("CREATE TABLE outbox(seq bigserial PRIMARY KEY,event_id text UNIQUE NOT NULL,stream text NOT NULL,envelope jsonb NOT NULL,published_at timestamptz)")
        .execute(&pool).await.unwrap();
    let redis = redis::Client::open(std::env::var("TEST_REDIS_URL").unwrap()).unwrap();
    let mut connection = redis.get_multiplexed_async_connection().await.unwrap();
    let stream = format!("events:{schema}");
    let failing_stream = format!("events:{schema}:wrongtype");
    let mut events = Vec::new();
    let mut transaction = pool.begin().await.unwrap();
    for index in 0..503 {
        let event = EventEnvelope::try_new("PaymentCaptured", rust_kit::messaging::now_rfc3339_utc(),
            rust_kit::messaging::correlation_id(), None::<String>, "payment",
            serde_json::json!({"paymentIntentId": format!("pi-{}", uuid::Uuid::now_v7()), "capturedAmount": {"currency": "CNY", "minorUnits": index + 1}})).unwrap();
        OutboxAppender::append(&mut transaction, &stream, &event).await.unwrap();
        events.push(serde_json::to_value(event).unwrap());
    }
    transaction.commit().await.unwrap();
    assert_eq!(relay_once(&pool, &redis, 500).await.unwrap(), 500);
    assert_eq!(relay_once(&pool, &redis, 500).await.unwrap(), 3);
    assert_eq!(relay_once(&pool, &redis, 500).await.unwrap(), 0);
    let entries: redis::streams::StreamRangeReply = connection.xrange_all(&stream).await.unwrap();
    assert_eq!(entries.ids.len(), events.len());
    for (entry, expected) in entries.ids.iter().zip(&events) {
        let raw: String = entry.get("envelope").unwrap();
        assert_eq!(&serde_json::from_str::<serde_json::Value>(&raw).unwrap(), expected);
    }
    let published: i64 = sqlx::query_scalar("SELECT count(*) FROM outbox WHERE published_at IS NOT NULL").fetch_one(&pool).await.unwrap();
    assert_eq!(published, 503);

    let _: () = connection.set(&failing_stream, "invalid stream type").await.unwrap();
    let mut transaction = pool.begin().await.unwrap();
    for target in [&stream, &failing_stream, &stream] {
        let event = EventEnvelope::try_new("PaymentCaptured", rust_kit::messaging::now_rfc3339_utc(),
            rust_kit::messaging::correlation_id(), None::<String>, "payment",
            serde_json::json!({"paymentIntentId": format!("pi-{}", uuid::Uuid::now_v7()), "capturedAmount": {"currency": "CNY", "minorUnits": 100}})).unwrap();
        OutboxAppender::append(&mut transaction, target, &event).await.unwrap();
    }
    transaction.commit().await.unwrap();
    let error = relay_once(&pool, &redis, 500).await.unwrap_err();
    assert!(error.to_string().contains("WRONGTYPE"), "{error}");
    let pending: i64 = sqlx::query_scalar("SELECT count(*) FROM outbox WHERE published_at IS NULL").fetch_one(&pool).await.unwrap();
    assert_eq!(pending, 3);
    let _: i64 = connection.del(&failing_stream).await.unwrap();
    assert_eq!(relay_once(&pool, &redis, 500).await.unwrap(), 3);
    assert_eq!(relay_once(&pool, &redis, 500).await.unwrap(), 0);
    let pending: i64 = sqlx::query_scalar("SELECT count(*) FROM outbox WHERE published_at IS NULL").fetch_one(&pool).await.unwrap();
    assert_eq!(pending, 0);
    let delivered_to_recovered_stream: usize = connection.xlen(&failing_stream).await.unwrap();
    assert_eq!(delivered_to_recovered_stream, 1);
    let _: i64 = connection.del(&[&stream, &failing_stream]).await.unwrap();
    pool.close().await;
    sqlx::query(&format!("DROP SCHEMA {schema} CASCADE")).execute(&admin).await.unwrap();
    admin.close().await;
}

#[tokio::test]
#[ignore = "requires TEST_DATABASE_URL and TEST_REDIS_URL"]
async fn delivery_continues_while_retention_waits_for_a_database_lock() {
    let url = std::env::var("TEST_DATABASE_URL").unwrap();
    let admin = PgPoolOptions::new().max_connections(1).connect(&url).await.unwrap();
    let schema = format!("outbox_retention_{}", uuid::Uuid::now_v7().simple());
    sqlx::query(&format!("CREATE SCHEMA {schema}")).execute(&admin).await.unwrap();
    let options = PgConnectOptions::from_str(&url).unwrap().options([("search_path", schema.as_str())]);
    let pool = PgPoolOptions::new().max_connections(4).connect_with(options).await.unwrap();
    for statement in [
        "CREATE TABLE outbox(seq bigserial PRIMARY KEY,event_id text UNIQUE NOT NULL,stream text NOT NULL,envelope jsonb NOT NULL,published_at timestamptz)",
        "CREATE TABLE processed_events(event_id text PRIMARY KEY,processed_at timestamptz NOT NULL)",
        "CREATE TABLE idempotency_records(key text PRIMARY KEY,created_at timestamptz NOT NULL)",
        "INSERT INTO processed_events VALUES ('expired',now()-interval '10 minutes'),('recent',now())",
    ] {
        sqlx::query(statement).execute(&pool).await.unwrap();
    }
    let mut blocker = pool.begin().await.unwrap();
    sqlx::query("LOCK TABLE processed_events IN ACCESS EXCLUSIVE MODE").execute(&mut *blocker).await.unwrap();
    let redis = redis::Client::open(std::env::var("TEST_REDIS_URL").unwrap()).unwrap();
    let stream = format!("events:{schema}");
    let relay = tokio::spawn(rust_kit::storage::run_outbox_relay(pool.clone(), redis.clone(), rust_kit::storage::OutboxRelayConfig {
        poll_interval: Duration::from_millis(5), batch_size: 500,
    }));
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let waiting: i64 = sqlx::query_scalar("SELECT count(*) FROM pg_locks WHERE relation=$1::regclass AND NOT granted")
                .bind(format!("{schema}.processed_events")).fetch_one(&admin).await.unwrap();
            if waiting > 0 { break; }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    }).await.expect("retention reaches the locked table");
    let event = EventEnvelope::try_new("PaymentCaptured", rust_kit::messaging::now_rfc3339_utc(),
        rust_kit::messaging::correlation_id(), None::<String>, "payment",
        serde_json::json!({"paymentIntentId": format!("pi-{}", uuid::Uuid::now_v7()), "capturedAmount": {"currency": "CNY", "minorUnits": 100}})).unwrap();
    let mut transaction = pool.begin().await.unwrap();
    OutboxAppender::append(&mut transaction, &stream, &event).await.unwrap();
    transaction.commit().await.unwrap();
    let mut connection = redis.get_multiplexed_async_connection().await.unwrap();
    tokio::time::timeout(Duration::from_secs(1), async {
        loop {
            let count: usize = connection.xlen(&stream).await.unwrap();
            if count == 1 { break; }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    }).await.expect("delivery must progress while retention waits");
    blocker.rollback().await.unwrap();
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let expired: i64 = sqlx::query_scalar("SELECT count(*) FROM processed_events WHERE event_id='expired'").fetch_one(&pool).await.unwrap();
            if expired == 0 { break; }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    }).await.unwrap();
    assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM processed_events WHERE event_id='recent'").fetch_one(&pool).await.unwrap(), 1);
    relay.abort();
    assert!(relay.await.unwrap_err().is_cancelled());
    pool.close().await;
    let _: i64 = connection.del(&stream).await.unwrap();
    sqlx::query(&format!("DROP SCHEMA {schema} CASCADE")).execute(&admin).await.unwrap();
    admin.close().await;
}
