use capacity_availability::adapters::storage::PostgresCapacityService;
use capacity_availability::ports::{HandlerResult, WireEnvelope};
use sqlx::postgres::{PgConnectOptions, PgPoolOptions};
use std::{collections::HashSet, str::FromStr};

#[tokio::test]
#[ignore = "requires TEST_DATABASE_URL and TEST_CAPACITY_PARALLEL_EVENTS"]
async fn concurrent_bookings_share_inventory_without_lost_holds_or_duplicate_units() {
    let events: Vec<WireEnvelope> = serde_json::from_str(
        &std::fs::read_to_string(std::env::var("TEST_CAPACITY_PARALLEL_EVENTS").unwrap()).unwrap(),
    )
    .unwrap();
    let requests: Vec<_> = events
        .iter()
        .filter(|event| event.event_type == "SegmentReservationRequested")
        .collect();
    assert!(requests.len() >= 8);
    let segment = requests[0].payload["segmentRef"].as_str().unwrap();
    assert!(
        requests
            .iter()
            .all(|request| request.payload["segmentRef"] == segment)
    );
    let count = requests.len();
    let url = std::env::var("TEST_DATABASE_URL").unwrap();
    let admin = PgPoolOptions::new()
        .max_connections(1)
        .connect(&url)
        .await
        .unwrap();
    let schema = format!("capacity_parallel_{}", uuid::Uuid::now_v7().simple());
    sqlx::query(&format!("CREATE SCHEMA {schema}"))
        .execute(&admin)
        .await
        .unwrap();
    let options = PgConnectOptions::from_str(&url)
        .unwrap()
        .options([("search_path", schema.as_str())]);
    let pool = PgPoolOptions::new()
        .max_connections(8)
        .connect_with(options)
        .await
        .unwrap();
    for migration in [
        include_str!("../migrations/001_capacity_storage.sql"),
        include_str!("../migrations/007_pending_booking_confirmations.sql"),
        include_str!("../migrations/008_pending_booking_releases.sql"),
    ] {
        sqlx::raw_sql(migration).execute(&pool).await.unwrap();
    }
    let service = PostgresCapacityService::new(pool.clone()).unwrap();
    let mut workers = tokio::task::JoinSet::new();
    for event in &events {
        let service = service.clone();
        let event = event.clone();
        workers.spawn(async move { service.handle_inbound_event(event).await });
    }
    while let Some(result) = workers.join_next().await {
        assert_eq!(result.unwrap(), HandlerResult::Success);
    }
    let holds: Vec<serde_json::Value> =
        sqlx::query_scalar("SELECT data FROM capacity_hold_snapshots")
            .fetch_all(&pool)
            .await
            .unwrap();
    assert_eq!(holds.len(), count);
    let mut units = HashSet::new();
    for hold in &holds {
        assert_eq!(hold["state"], "Confirmed");
        assert!(units.insert(hold["capacityUnitRef"].as_str().unwrap()));
    }
    let inventory: serde_json::Value =
        sqlx::query_scalar("SELECT data FROM inventory_pool_snapshots")
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(inventory["holds"].as_array().unwrap().len(), count);
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT count(*) FROM outbox WHERE envelope->>'eventType'='CapacityHoldConfirmed'"
        )
        .fetch_one(&pool)
        .await
        .unwrap(),
        count as i64
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM pending_booking_confirmations")
            .fetch_one(&pool)
            .await
            .unwrap(),
        0
    );
    for event in events {
        assert_eq!(
            service.handle_inbound_event(event).await,
            HandlerResult::Success
        );
    }
    pool.close().await;
    sqlx::query(&format!("DROP SCHEMA {schema} CASCADE"))
        .execute(&admin)
        .await
        .unwrap();
    admin.close().await;
}
