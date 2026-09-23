use invoicing::PostgresInvoicingService;
use rust_kit::messaging::EventEnvelope;
use serde_json::Value;
use std::{sync::Arc, time::Duration};

async fn projection(service: &PostgresInvoicingService, order: &str) -> Value {
    sqlx::query_scalar("SELECT data FROM invoicing_projection_rows WHERE kind='orders' AND id=$1")
        .bind(order)
        .fetch_one(service.pool())
        .await
        .unwrap()
}

async fn clear_recorded_order(
    service: &PostgresInvoicingService,
    order: &str,
    events: &[EventEnvelope],
) {
    sqlx::query("DELETE FROM invoicing_projection_rows WHERE kind='orders' AND id=$1")
        .bind(order)
        .execute(service.pool())
        .await
        .unwrap();
    for event in events {
        sqlx::query("DELETE FROM processed_events WHERE event_id=$1")
            .bind(&event.event_id)
            .execute(service.pool())
            .await
            .unwrap();
    }
}

#[test]
#[ignore = "requires TEST_DATABASE_URL for isolated PostgreSQL schemas"]
fn recorded_orders_preserve_latest_amount_with_four_concurrent_consumers() {
    let source: Value =
        serde_json::from_str(include_str!("fixtures/order-concurrency-recorded.json")).unwrap();
    let other: Value =
        serde_json::from_str(include_str!("fixtures/amount-basis-recorded.json")).unwrap();
    let database = std::env::var("TEST_DATABASE_URL").unwrap();
    let schema = format!("invoice_concurrency_{}", uuid::Uuid::now_v7().simple());
    let mut url = reqwest::Url::parse(&database).unwrap();
    url.query_pairs_mut().append_pair(
        "options",
        &format!("-csearch_path={schema} -cstatement_timeout=5000 -clock_timeout=3000"),
    );
    // This integration binary configures its environment before starting Tokio.
    unsafe {
        std::env::set_var("DATABASE_URL", url.as_str());
        std::env::set_var(
            "MIGRATIONS_DIR",
            concat!(env!("CARGO_MANIFEST_DIR"), "/migrations"),
        );
        std::env::set_var("PG_MAX_POOL_SIZE", "8");
    }
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4)
        .enable_all()
        .build()
        .unwrap();
    runtime.block_on(async move {
        let admin = sqlx::postgres::PgPoolOptions::new().max_connections(1)
            .connect(&database).await.unwrap();
        sqlx::query(&format!("CREATE SCHEMA {schema}")).execute(&admin).await.unwrap();
        let result = tokio::spawn(async move {
            let service = Arc::new(PostgresInvoicingService::from_env().await.unwrap());
            let events: Vec<EventEnvelope> = source["events"].as_array().unwrap().iter()
                .map(|row| serde_json::from_value(row["envelope"].clone()).unwrap()).collect();
            assert_eq!(events.len(), 3);
            assert_eq!(events[0].event_type, "JourneyOrderCreated");
            assert_eq!(events[1].event_type, "JourneyOrderConfirmed");
            assert_eq!(events[2].event_type, "JourneyOrderPostSalesAdjusted");
            let order = events[0].payload["orderId"].as_str().unwrap();
            for event in &events {
                assert_eq!(event.payload["orderId"], order);
                service.handle_subscribed_event(event.clone()).await.unwrap();
            }
            let expected = projection(&service, order).await;
            assert_eq!(expected["expected_total"], events[2].payload["monetarySummary"]["total"]);
            let expected_at: chrono::DateTime<chrono::Utc> = serde_json::from_value(expected["updated_at"].clone()).unwrap();
            assert_eq!(expected_at, chrono::DateTime::parse_from_rfc3339(&events[2].occurred_at).unwrap());
            assert!(!expected["traveler_refs"].as_array().unwrap().is_empty());
            assert!(!expected["segment_refs"].as_array().unwrap().is_empty());
            let sequences = [[0,1,2], [0,2,1], [1,0,2], [1,2,0], [2,0,1], [2,1,0]];
            for sequence in sequences {
                clear_recorded_order(&service, order, &events).await;
                for index in sequence {
                    service.handle_subscribed_event(events[index].clone()).await.unwrap();
                }
                assert_eq!(projection(&service, order).await, expected, "delivery sequence {sequence:?}");
            }
            println!("Recorded order {order}: all six creation, confirmation and adjustment sequences preserve total 9250 and scope references");

            for round in 0..12 {
                clear_recorded_order(&service, order, &events).await;
                let barrier = Arc::new(tokio::sync::Barrier::new(4));
                let mut tasks = tokio::task::JoinSet::new();
                for worker in 0..4 {
                    let service = service.clone();
                    let barrier = barrier.clone();
                    let events = events.clone();
                    let sequence = sequences[(round + worker) % sequences.len()];
                    tasks.spawn(async move {
                        barrier.wait().await;
                        for index in sequence {
                            service.handle_subscribed_event(events[index].clone()).await.unwrap();
                        }
                    });
                }
                while let Some(result) = tasks.join_next().await { result.unwrap(); }
                assert_eq!(projection(&service, order).await, expected);
                for event in &events {
                    assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM processed_events WHERE event_id=$1")
                        .bind(&event.event_id).fetch_one(service.pool()).await.unwrap(), 1);
                }
            }
            assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM outbox").fetch_one(service.pool()).await.unwrap(), 0);
            println!("Four concurrent handlers: 12 rounds, 144 deliveries of recorded envelopes, latest total preserved and each event stored once");

            clear_recorded_order(&service, order, &events).await;
            let mut blocker = service.pool().begin().await.unwrap();
            sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1,0))")
                .bind(format!("orders:{order}")).execute(&mut *blocker).await.unwrap();
            let blocked_service = service.clone();
            let blocked_event = events[2].clone();
            let blocked = tokio::spawn(async move { blocked_service.handle_subscribed_event(blocked_event).await });
            let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
            loop {
                let waits: i64 = sqlx::query_scalar("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted AND (classid,objid,objsubid) IN (SELECT classid,objid,objsubid FROM pg_locks WHERE pid=pg_backend_pid() AND locktype='advisory' AND granted)")
                    .fetch_one(&mut *blocker).await.unwrap();
                if waits > 0 { break; }
                assert!(tokio::time::Instant::now() < deadline, "handler did not wait for its order lock");
                tokio::task::yield_now().await;
            }
            for row in other["orderEvents"].as_array().unwrap().iter().rev() {
                let event = serde_json::from_value(row["envelope"].clone()).unwrap();
                tokio::time::timeout(Duration::from_secs(1), service.handle_subscribed_event(event)).await.unwrap().unwrap();
            }
            let other_order = other["orderId"].as_str().unwrap();
            let other_projection = projection(&service, other_order).await;
            assert_eq!(other_projection["expected_total"], other["orderEvents"][1]["envelope"]["payload"]["monetarySummary"]["total"]);
            assert!(!blocked.is_finished());
            blocker.rollback().await.unwrap();
            blocked.await.unwrap().unwrap();
            for event in events.iter().rev() {
                service.handle_subscribed_event(event.clone()).await.unwrap();
            }
            assert_eq!(projection(&service, order).await, expected);
            let restarted = PostgresInvoicingService::from_env().await.unwrap();
            assert_eq!(projection(&restarted, order).await, expected);
            assert_eq!(projection(&restarted, other_order).await, other_projection);
            restarted.pool().close().await;
            service.pool().close().await;
            println!("One order waiting on its transaction lock does not block another order; projections survive reload");
        }).await;
        sqlx::query(&format!("DROP SCHEMA {schema} CASCADE")).execute(&admin).await.unwrap();
        admin.close().await;
        result.unwrap();
    });
}
