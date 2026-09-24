use capacity_availability::adapters::storage::PostgresCapacityService;
use capacity_availability::application::{AppError, HoldCapacityRequest};
use capacity_availability::supply_facts::SupplyClient;
use sqlx::postgres::{PgConnectOptions, PgPoolOptions};
use std::str::FromStr;

#[tokio::test]
#[ignore = "requires complete recorded service inventory and live upstream facts"]
async fn recorded_service_inventory_counts_every_historical_pool() {
    let source: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(std::env::var("TEST_EXISTING_SUPPLY_INVENTORY").unwrap()).unwrap(),
    )
    .unwrap();
    let url = std::env::var("TEST_DATABASE_URL").unwrap();
    let admin = PgPoolOptions::new()
        .max_connections(1)
        .connect(&url)
        .await
        .unwrap();
    let schema = format!("existing_supply_{}", uuid::Uuid::now_v7().simple());
    sqlx::query(&format!("CREATE SCHEMA {schema}"))
        .execute(&admin)
        .await
        .unwrap();
    let pool = PgPoolOptions::new()
        .max_connections(1)
        .connect_with(
            PgConnectOptions::from_str(&url)
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
    for row in source["pools"].as_array().unwrap() {
        sqlx::query("INSERT INTO inventory_pool_snapshots(id,version,data) VALUES($1,$2,$3)")
            .bind(row["id"].as_str().unwrap())
            .bind(row["version"].as_i64().unwrap())
            .bind(&row["data"])
            .execute(&pool)
            .await
            .unwrap();
    }
    let service = PostgresCapacityService::new(pool.clone()).unwrap();
    let cold_started = std::time::Instant::now();
    let result = service
        .query_availability(
            source["service"].as_str().unwrap(),
            source["segment"].as_str().unwrap(),
        )
        .await;
    let cold_elapsed = cold_started.elapsed();
    eprintln!("complete inventory cold read: {cold_elapsed:?}");
    if let Ok(ref available) = result {
        let business = available
            .remaining_by_class
            .iter()
            .find(|c| c.class_ref == "BUSINESS_CLASS")
            .unwrap();
        assert_eq!(business.total, 36);
        assert_eq!(business.available, 0);
        let now = chrono::Utc::now().timestamp_millis() as u64;
        let active: std::collections::BTreeSet<_> = source["pools"]
            .as_array()
            .unwrap()
            .iter()
            .flat_map(|row| row["data"]["holds"].as_array().unwrap())
            .filter(|hold| {
                hold["state"] == "Confirmed"
                    || (hold["state"] == "Held" && hold["expiresAt"].as_u64().unwrap() > now)
            })
            .map(|hold| hold["references"]["segmentBookingRef"].as_str().unwrap())
            .collect();
        let stored:Vec<(String,serde_json::Value,chrono::DateTime<chrono::Utc>)>=sqlx::query_as(
            "SELECT segment_booking_id,data,recorded_at FROM reservation_supply_facts ORDER BY segment_booking_id")
            .fetch_all(&pool).await.unwrap();
        assert_eq!(
            stored
                .iter()
                .map(|row| row.0.as_str())
                .collect::<std::collections::BTreeSet<_>>(),
            active
        );
        drop(service);
        let restarted = PostgresCapacityService::new(pool.clone()).unwrap();
        for _ in 0..3 {
            let warm_started = std::time::Instant::now();
            let warm = restarted
                .query_availability(
                    source["service"].as_str().unwrap(),
                    source["segment"].as_str().unwrap(),
                )
                .await
                .unwrap();
            let warm_elapsed = warm_started.elapsed();
            eprintln!("complete inventory persisted read: {warm_elapsed:?}");
            let business = warm
                .remaining_by_class
                .iter()
                .find(|c| c.class_ref == "BUSINESS_CLASS")
                .unwrap();
            assert_eq!(business.total, 36);
            assert_eq!(business.available, 0);
            assert!(
                warm_elapsed < std::time::Duration::from_secs(10),
                "persisted read exceeds loadgen request deadline"
            );
        }
        let retained:Vec<(String,serde_json::Value,chrono::DateTime<chrono::Utc>)>=sqlx::query_as(
            "SELECT segment_booking_id,data,recorded_at FROM reservation_supply_facts ORDER BY segment_booking_id")
            .fetch_all(&pool).await.unwrap();
        assert_eq!(
            stored, retained,
            "repeated reads must preserve immutable reservation facts"
        );
        for row in source["pools"].as_array().unwrap() {
            let saved: serde_json::Value =
                sqlx::query_scalar("SELECT data FROM inventory_pool_snapshots WHERE id=$1")
                    .bind(row["id"].as_str().unwrap())
                    .fetch_one(&pool)
                    .await
                    .unwrap();
            assert_eq!(saved, row["data"]);
        }
    }
    pool.close().await;
    sqlx::query(&format!("DROP SCHEMA {schema} CASCADE"))
        .execute(&admin)
        .await
        .unwrap();
    admin.close().await;
    assert!(
        result.is_ok(),
        "complete inventory query failed: {result:?}"
    );
    assert!(
        cold_elapsed < std::time::Duration::from_secs(10),
        "cold read exceeds loadgen request deadline"
    );
}

#[tokio::test]
#[ignore = "requires PostgreSQL and actual fully occupied business class booking"]
async fn fully_occupied_physical_supply_rejects_new_hold_in_real_database() {
    let url = std::env::var("TEST_DATABASE_URL").unwrap();
    let client =
        SupplyClient::new(std::env::var("SUPPLY_FACTS_BASE_URL_TEMPLATE").unwrap()).unwrap();
    let booking = std::env::var("TEST_SUPPLY_BOOKING").unwrap();
    let correlation = format!("corr-{}", uuid::Uuid::now_v7());
    let facts = client.reservation(&booking, &correlation).await.unwrap();
    let physical = client.seats(&facts, &correlation).await.unwrap();
    assert_eq!(physical.units.len(), 36);
    assert_eq!(physical.allocations.len(), 36);
    let admin = PgPoolOptions::new()
        .max_connections(1)
        .connect(&url)
        .await
        .unwrap();
    let schema = format!("supply_hold_{}", uuid::Uuid::now_v7().simple());
    sqlx::query(&format!("CREATE SCHEMA {schema}"))
        .execute(&admin)
        .await
        .unwrap();
    let options = PgConnectOptions::from_str(&url)
        .unwrap()
        .options([("search_path", schema.as_str())]);
    let pool = PgPoolOptions::new()
        .max_connections(1)
        .connect_with(options)
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
        segment_booking_id: booking,
    };
    let key = uuid::Uuid::now_v7().to_string();
    for _ in 0..2 {
        let result = service
            .hold_capacity(request.clone(), &key, &correlation)
            .await;
        assert!(
            matches!(result, Err(AppError::DomainRuleViolation(ref message)) if message=="NO_AVAILABLE_CAPACITY"),
            "{result:?}"
        );
    }
    for table in ["capacity_hold_snapshots", "outbox", "idempotency_records"] {
        let count: i64 = sqlx::query_scalar(&format!("SELECT count(*) FROM {table}"))
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(count, 0, "no partial effect in {table}");
    }
    let supply: serde_json::Value = sqlx::query_scalar("SELECT data FROM reservation_supply_facts")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(supply["classRef"], "BUSINESS_CLASS");
    assert_eq!(supply["serviceDate"], "2026-09-25");
    let availability = service
        .query_availability(&facts.scheduled_service_ref, &facts.segment_ref)
        .await
        .unwrap();
    let business = availability
        .remaining_by_class
        .iter()
        .find(|class| class.class_ref == "BUSINESS_CLASS")
        .unwrap();
    assert_eq!(business.total, 36);
    assert_eq!(business.available, 0);
    assert!(
        availability
            .remaining_by_class
            .iter()
            .all(|class| class.available <= class.total)
    );
    pool.close().await;
    sqlx::query(&format!("DROP SCHEMA {schema} CASCADE"))
        .execute(&admin)
        .await
        .unwrap();
    admin.close().await;
}

#[tokio::test]
#[ignore = "requires PostgreSQL and actual reservation in a class with available units"]
async fn physical_supply_hold_replay_and_release_preserve_counts_and_identity() {
    let url = std::env::var("TEST_DATABASE_URL").unwrap();
    let client =
        SupplyClient::new(std::env::var("SUPPLY_FACTS_BASE_URL_TEMPLATE").unwrap()).unwrap();
    let booking = std::env::var("TEST_AVAILABLE_SUPPLY_BOOKING").unwrap();
    let correlation = format!("corr-{}", uuid::Uuid::now_v7());
    let facts = client.reservation(&booking, &correlation).await.unwrap();
    let physical = client.seats(&facts, &correlation).await.unwrap();
    let remaining = physical
        .remaining(
            &capacity_availability::supply_facts::Interval {
                from_seq: 0,
                to_seq: 1,
            },
            &[],
        )
        .unwrap();
    assert!(remaining > 0);
    let admin = PgPoolOptions::new()
        .max_connections(1)
        .connect(&url)
        .await
        .unwrap();
    let schema = format!("supply_replay_{}", uuid::Uuid::now_v7().simple());
    sqlx::query(&format!("CREATE SCHEMA {schema}"))
        .execute(&admin)
        .await
        .unwrap();
    let options = PgConnectOptions::from_str(&url)
        .unwrap()
        .options([("search_path", schema.as_str())]);
    let pool = PgPoolOptions::new()
        .max_connections(1)
        .connect_with(options)
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
        segment_booking_id: booking,
    };
    let key = uuid::Uuid::now_v7().to_string();
    let first = service
        .hold_capacity(request.clone(), &key, &correlation)
        .await
        .unwrap();
    assert_eq!(first.status, "HELD");
    let replay = service
        .hold_capacity(request.clone(), &key, &correlation)
        .await
        .unwrap();
    assert_eq!(first.hold_id, replay.hold_id);
    let keys: Vec<_> = (0..8).map(|_| uuid::Uuid::now_v7().to_string()).collect();
    let requests = keys
        .iter()
        .map(|key| service.hold_capacity(request.clone(), key, &correlation));
    for response in futures_util::future::join_all(requests).await {
        assert_eq!(response.unwrap().hold_id, first.hold_id);
    }
    let event=capacity_availability::ports::WireEnvelope::try_new("SegmentReservationRequested",chrono::Utc::now().to_rfc3339(),&correlation,None::<String>,"booking-orchestration",
        serde_json::json!({"segmentBookingId":facts.segment_booking_id,"journeyOrderId":facts.journey_order_id,"segmentRef":facts.segment_ref,
            "travelerRef":facts.traveler_ref,"idempotencyKey":uuid::Uuid::now_v7().to_string(),"classRef":facts.class_ref})).unwrap();
    for _ in 0..2 {
        assert_eq!(
            service.handle_inbound_event(event.clone()).await,
            capacity_availability::ports::HandlerResult::Success
        );
    }
    let snapshot: serde_json::Value =
        sqlx::query_scalar("SELECT data FROM inventory_pool_snapshots")
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(
        snapshot["identity"]["scheduledServiceRef"],
        facts.scheduled_service_ref
    );
    assert_eq!(snapshot["identity"]["serviceDate"], facts.service_date);
    let route = client
        .route(&facts.segment_ref, &correlation)
        .await
        .unwrap();
    assert_eq!(
        snapshot["identity"]["stopSequenceVersion"],
        route.stop_sequence_version
    );
    assert_eq!(snapshot["holds"][0]["fromSeq"], route.interval.from_seq);
    assert_eq!(snapshot["holds"][0]["toSeq"], route.interval.to_seq);
    assert_eq!(
        snapshot["holds"][0]["references"]["orderRef"],
        facts.journey_order_id
    );
    assert_eq!(
        snapshot["physicalUnits"].as_array().unwrap().len(),
        physical.units.len()
    );
    let hold = service.get_hold(&first.hold_id).await.unwrap();
    assert!(
        physical
            .units
            .iter()
            .any(|unit| unit.seat_unit_ref == hold.capacity_unit_ref)
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT count(*) FROM outbox WHERE envelope->>'eventType'='CapacityHeld'"
        )
        .fetch_one(&pool)
        .await
        .unwrap(),
        2
    );
    service
        .confirm_hold(
            &first.hold_id,
            &uuid::Uuid::now_v7().to_string(),
            &correlation,
        )
        .await
        .unwrap();
    service
        .release_hold(
            &first.hold_id,
            &uuid::Uuid::now_v7().to_string(),
            &correlation,
        )
        .await
        .unwrap();
    assert_eq!(
        service.get_hold(&first.hold_id).await.unwrap().status,
        "RELEASED"
    );
    let released = service
        .hold_capacity(
            request.clone(),
            &uuid::Uuid::now_v7().to_string(),
            &correlation,
        )
        .await
        .unwrap();
    assert_eq!(released.hold_id, first.hold_id);
    assert_eq!(released.status, "RELEASED");
    let snapshot: serde_json::Value =
        sqlx::query_scalar("SELECT data FROM inventory_pool_snapshots")
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(
        snapshot["physicalUnits"].as_array().unwrap().len(),
        physical.units.len()
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM capacity_hold_snapshots")
            .fetch_one(&pool)
            .await
            .unwrap(),
        1
    );
    pool.close().await;
    sqlx::query(&format!("DROP SCHEMA {schema} CASCADE"))
        .execute(&admin)
        .await
        .unwrap();
    admin.close().await;
}

#[tokio::test]
#[ignore = "requires recorded historical hold and PostgreSQL"]
async fn historical_standard_hold_replay_keeps_original_identity_and_occupancy() {
    use capacity_availability::ports::{HandlerResult, WireEnvelope};
    let source: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(std::env::var("TEST_HISTORICAL_SUPPLY_HOLD").unwrap()).unwrap(),
    )
    .unwrap();
    let hold = &source["hold"];
    let snapshot = &source["pool"];
    let booking = hold["data"]["references"]["segmentBookingRef"]
        .as_str()
        .unwrap();
    let client =
        SupplyClient::new(std::env::var("SUPPLY_FACTS_BASE_URL_TEMPLATE").unwrap()).unwrap();
    let correlation = format!("corr-{}", uuid::Uuid::now_v7());
    let facts = client.reservation(booking, &correlation).await.unwrap();
    let url = std::env::var("TEST_DATABASE_URL").unwrap();
    let admin = PgPoolOptions::new()
        .max_connections(1)
        .connect(&url)
        .await
        .unwrap();
    let schema = format!("supply_history_{}", uuid::Uuid::now_v7().simple());
    sqlx::query(&format!("CREATE SCHEMA {schema}"))
        .execute(&admin)
        .await
        .unwrap();
    let pool = PgPoolOptions::new()
        .max_connections(1)
        .connect_with(
            PgConnectOptions::from_str(&url)
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
    for (table, row) in [
        ("capacity_hold_snapshots", hold),
        ("inventory_pool_snapshots", snapshot),
    ] {
        sqlx::query(&format!(
            "INSERT INTO {table}(id,version,data) VALUES($1,$2,$3)"
        ))
        .bind(row["id"].as_str().unwrap())
        .bind(row["version"].as_i64().unwrap())
        .bind(&row["data"])
        .execute(&pool)
        .await
        .unwrap();
    }
    let business_key = hold["data"]["idempotencyKey"].as_str().unwrap();
    let event=WireEnvelope::try_new("SegmentReservationRequested",chrono::Utc::now().to_rfc3339(),&correlation,None::<String>,"booking-orchestration",
        serde_json::json!({"segmentBookingId":booking,"journeyOrderId":facts.journey_order_id,"segmentRef":facts.segment_ref,"travelerRef":facts.traveler_ref,"idempotencyKey":business_key})).unwrap();
    let service = PostgresCapacityService::new(pool.clone()).unwrap();
    for _ in 0..2 {
        assert_eq!(
            service.handle_inbound_event(event.clone()).await,
            HandlerResult::Success
        );
    }
    let saved: serde_json::Value = sqlx::query_scalar("SELECT data FROM inventory_pool_snapshots")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(saved, snapshot["data"]);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM capacity_hold_snapshots")
            .fetch_one(&pool)
            .await
            .unwrap(),
        1
    );
    let replay_id:String=sqlx::query_scalar("SELECT envelope->'payload'->>'holdId' FROM outbox WHERE envelope->>'eventType'='CapacityHeld'").fetch_one(&pool).await.unwrap();
    assert_eq!(replay_id, hold["id"].as_str().unwrap());
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM outbox")
            .fetch_one(&pool)
            .await
            .unwrap(),
        1
    );
    let availability = service
        .query_availability(&facts.scheduled_service_ref, &facts.segment_ref)
        .await
        .unwrap();
    let physical = client.seats(&facts, &correlation).await.unwrap();
    let class = availability
        .remaining_by_class
        .iter()
        .find(|class| class.class_ref == facts.class_ref)
        .unwrap();
    assert_eq!(class.total, physical.capacity());
    assert!(
        class.available
            <= physical
                .remaining(
                    &capacity_availability::supply_facts::Interval {
                        from_seq: 0,
                        to_seq: 1
                    },
                    &[]
                )
                .unwrap()
    );
    let recorded: serde_json::Value =
        sqlx::query_scalar("SELECT data FROM inventory_pool_snapshots")
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(
        recorded, snapshot["data"],
        "availability must preserve historical holds"
    );
    pool.close().await;
    sqlx::query(&format!("DROP SCHEMA {schema} CASCADE"))
        .execute(&admin)
        .await
        .unwrap();
    admin.close().await;
}
