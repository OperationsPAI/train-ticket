use capacity_availability::adapters::storage::PostgresCapacityService;
use capacity_availability::application::HoldCapacityRequest;
use capacity_availability::supply_facts::{Interval, PendingOccupancy, SeatSupply, SupplyClient};
use sqlx::postgres::{PgConnectOptions, PgPoolOptions};
use std::str::FromStr;

#[tokio::test]
#[ignore = "requires actual standing supply, booking and PostgreSQL"]
async fn standing_quota_is_validated_counted_and_persisted_without_physical_seats() {
    let raw = std::fs::read_to_string(std::env::var("TEST_STANDING_SUPPLY").unwrap()).unwrap();
    let supply: SeatSupply = serde_json::from_str(&raw).unwrap();
    assert!(supply.units.is_empty());
    let policy = supply.standing_policy.as_ref().unwrap();
    assert_eq!(policy.limit, policy.basis_units / 10);
    assert_eq!(policy.version, "standing-second-class-10pct-v1");
    let allocated: Vec<_> = supply
        .allocations
        .iter()
        .map(|a| PendingOccupancy {
            hold_id: a.capacity_hold_id.clone(),
            segment_booking_id: a.segment_booking_id.clone(),
            journey_order_id: a.journey_order_id.clone(),
            traveler_ref: a.traveler_ref.clone(),
            capacity_unit_ref: a.capacity_unit_ref.clone(),
            effective_capacity_unit_ref: a.capacity_unit_ref.clone(),
            state: "Confirmed".into(),
            interval: a.interval.clone(),
        })
        .collect();
    let interval = Interval {
        from_seq: 0,
        to_seq: 1,
    };
    assert_eq!(
        supply.remaining(&interval, &[]).unwrap(),
        supply.remaining(&interval, &allocated).unwrap()
    );
    let client =
        SupplyClient::new(std::env::var("SUPPLY_FACTS_BASE_URL_TEMPLATE").unwrap()).unwrap();
    let facts = client
        .reservation(
            &std::env::var("TEST_STANDING_BOOKING").unwrap(),
            "corr-01a0cdac-da33-7fd4-a442-bdafdf12c14d",
        )
        .await
        .unwrap();
    supply.validate(&facts).unwrap();
    let mut invalid = supply.clone();
    invalid.standing_policy.as_mut().unwrap().limit += 1;
    assert!(invalid.validate(&facts).is_err());
    let database = std::env::var("TEST_DATABASE_URL").unwrap();
    let admin = PgPoolOptions::new()
        .max_connections(1)
        .connect(&database)
        .await
        .unwrap();
    let schema = format!("standing_supply_{}", uuid::Uuid::now_v7().simple());
    sqlx::query(&format!("CREATE SCHEMA {schema}"))
        .execute(&admin)
        .await
        .unwrap();
    let pool = PgPoolOptions::new()
        .max_connections(1)
        .connect_with(
            PgConnectOptions::from_str(&database)
                .unwrap()
                .options([("search_path", schema.as_str())]),
        )
        .await
        .unwrap();
    for migration in [
        include_str!("../migrations/001_capacity_storage.sql"),
        include_str!("../migrations/007_pending_booking_confirmations.sql"),
        include_str!("../migrations/008_pending_booking_releases.sql"),
        include_str!("../migrations/009_reservation_supply.sql"),
    ] {
        sqlx::raw_sql(migration).execute(&pool).await.unwrap();
    }
    let service = PostgresCapacityService::new(pool.clone()).unwrap();
    let request = HoldCapacityRequest {
        segment_ref: facts.segment_ref.clone(),
        traveler_ref: facts.traveler_ref.clone(),
        class_ref: facts.class_ref.clone(),
        quantity: 1,
        segment_booking_id: facts.segment_booking_id.clone(),
    };
    let key = uuid::Uuid::now_v7().to_string();
    let corr = format!("corr-{}", uuid::Uuid::now_v7());
    let held = service
        .hold_capacity(request.clone(), &key, &corr)
        .await
        .unwrap();
    assert_eq!(held.status, "HELD");
    assert_eq!(
        service
            .hold_capacity(request, &key, &corr)
            .await
            .unwrap()
            .hold_id,
        held.hold_id
    );
    let detail = service.get_hold(&held.hold_id).await.unwrap();
    assert!(
        detail
            .capacity_unit_ref
            .starts_with("quota:standing-second-class-10pct-v1:")
    );
    let observation: serde_json::Value =
        sqlx::query_scalar("SELECT data FROM capacity_supply_observations")
            .fetch_one(&pool)
            .await
            .unwrap();
    assert!(observation["units"].as_array().unwrap().is_empty());
    let snapshot:serde_json::Value=sqlx::query_scalar("SELECT envelope->'payload' FROM outbox WHERE envelope->>'eventType'='CapacitySnapshotUpdated'").fetch_one(&pool).await.unwrap();
    assert_eq!(snapshot["physicalCapacity"], 0);
    assert_eq!(
        snapshot["totalCapacity"],
        observation["standingPolicy"]["limit"]
    );
    service
        .confirm_hold(&held.hold_id, &uuid::Uuid::now_v7().to_string(), &corr)
        .await
        .unwrap();
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT count(*) FROM outbox WHERE envelope->>'eventType'='OverbookingThresholdReached'"
        )
        .fetch_one(&pool)
        .await
        .unwrap(),
        0
    );
    service
        .release_hold(&held.hold_id, &uuid::Uuid::now_v7().to_string(), &corr)
        .await
        .unwrap();
    assert_eq!(
        service.get_hold(&held.hold_id).await.unwrap().status,
        "RELEASED"
    );
    pool.close().await;
    sqlx::query(&format!("DROP SCHEMA {schema} CASCADE"))
        .execute(&admin)
        .await
        .unwrap();
    admin.close().await;
}
