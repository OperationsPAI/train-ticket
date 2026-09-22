#![cfg(feature = "redis-impl")]

use redis::AsyncCommands;
use rust_kit::messaging::EventEnvelope;
use rust_kit::storage::{OutboxAppender, relay_once};
use sqlx::postgres::{PgConnectOptions, PgPoolOptions};
use std::str::FromStr;

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
