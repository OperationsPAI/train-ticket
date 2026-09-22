use capacity_availability::adapters::storage::PostgresCapacityService;
use capacity_availability::application::HoldCapacityRequest;
use sqlx::postgres::{PgConnectOptions, PgPoolOptions};
use std::str::FromStr;

#[tokio::test]
#[ignore = "requires TEST_DATABASE_URL and TEST_BOOKING_JSON"]
async fn persisted_hold_keeps_service_segment_and_booking_references_distinct() {
    let booking: serde_json::Value = serde_json::from_str(&std::env::var("TEST_BOOKING_JSON").unwrap()).unwrap();
    let url = std::env::var("TEST_DATABASE_URL").unwrap();
    let admin = PgPoolOptions::new().max_connections(1).connect(&url).await.unwrap();
    let schema = format!("hold_refs_{}", uuid::Uuid::now_v7().simple());
    sqlx::query(&format!("CREATE SCHEMA {schema}")).execute(&admin).await.unwrap();
    let options = PgConnectOptions::from_str(&url).unwrap().options([("search_path", schema.as_str())]);
    let pool = PgPoolOptions::new().max_connections(3).connect_with(options).await.unwrap();
    sqlx::raw_sql(include_str!("../migrations/001_capacity_storage.sql")).execute(&pool).await.unwrap();
    sqlx::raw_sql(include_str!("../migrations/007_pending_booking_confirmations.sql")).execute(&pool).await.unwrap();
    sqlx::raw_sql(include_str!("../migrations/008_pending_booking_releases.sql")).execute(&pool).await.unwrap();
    let service = PostgresCapacityService::new(pool.clone()).unwrap();
    let request = HoldCapacityRequest {
        segment_ref: booking["segmentRef"].as_str().unwrap().to_string(),
        segment_booking_id: booking["segmentBookingId"].as_str().unwrap().to_string(),
        traveler_ref: booking["travelerRef"].as_str().unwrap().to_string(),
        class_ref: "standard".to_string(), quantity: 1,
    };
    assert_ne!(request.segment_ref, request.segment_booking_id);
    let key = uuid::Uuid::now_v7().to_string();
    let correlation = format!("corr-{}", uuid::Uuid::now_v7());
    let created = service.hold_capacity(request.clone(), &key, &correlation).await.unwrap();
    assert_eq!(created.segment_ref, request.segment_ref);
    let reloaded = PostgresCapacityService::new(pool.clone()).unwrap();
    for status in ["HELD", "CONFIRMED", "RELEASED"] {
        if status == "CONFIRMED" {
            reloaded.confirm_hold(&created.hold_id, &uuid::Uuid::now_v7().to_string(), &correlation).await.unwrap();
        } else if status == "RELEASED" {
            reloaded.release_hold(&created.hold_id, &uuid::Uuid::now_v7().to_string(), &correlation).await.unwrap();
        }
        let held = reloaded.get_hold(&created.hold_id).await.unwrap();
        assert_eq!(held.status, status);
        assert_eq!(held.segment_ref, request.segment_ref);
        assert_eq!(held.segment_booking_ref.as_deref(), Some(request.segment_booking_id.as_str()));
        assert_eq!(held.traveler_ref.as_deref(), Some(request.traveler_ref.as_str()));
    }
    pool.close().await;
    sqlx::query(&format!("DROP SCHEMA {schema} CASCADE")).execute(&admin).await.unwrap();
    admin.close().await;
}

#[tokio::test]
#[ignore = "requires TEST_DATABASE_URL, TEST_BOOKING_JSON and TEST_CONFIRM_EVENT_JSON"]
async fn confirmation_is_durable_and_applied_when_the_hold_arrives() {
    use capacity_availability::ports::{HandlerResult, WireEnvelope};
    let booking: serde_json::Value = serde_json::from_str(&std::env::var("TEST_BOOKING_JSON").unwrap()).unwrap();
    let event: WireEnvelope = serde_json::from_str(&std::env::var("TEST_CONFIRM_EVENT_JSON").unwrap()).unwrap();
    assert_eq!(event.payload["segmentBookingId"], booking["segmentBookingId"]);
    let url = std::env::var("TEST_DATABASE_URL").unwrap();
    let admin = PgPoolOptions::new().max_connections(1).connect(&url).await.unwrap();
    let schema = format!("hold_confirm_{}", uuid::Uuid::now_v7().simple());
    sqlx::query(&format!("CREATE SCHEMA {schema}")).execute(&admin).await.unwrap();
    let options = PgConnectOptions::from_str(&url).unwrap().options([("search_path", schema.as_str())]);
    let pool = PgPoolOptions::new().max_connections(3).connect_with(options).await.unwrap();
    sqlx::raw_sql(include_str!("../migrations/001_capacity_storage.sql")).execute(&pool).await.unwrap();
    sqlx::raw_sql(include_str!("../migrations/007_pending_booking_confirmations.sql")).execute(&pool).await.unwrap();
    sqlx::raw_sql(include_str!("../migrations/008_pending_booking_releases.sql")).execute(&pool).await.unwrap();
    let service = PostgresCapacityService::new(pool.clone()).unwrap();
    let initial = service.handle_inbound_event(event.clone()).await;
    assert_eq!(initial, HandlerResult::Success);
    let processed: i64 = sqlx::query_scalar("SELECT count(*) FROM processed_events").fetch_one(&pool).await.unwrap();
    assert_eq!(processed, 1);
    let saved: serde_json::Value = sqlx::query_scalar("SELECT envelope FROM pending_booking_confirmations").fetch_one(&pool).await.unwrap();
    assert_eq!(saved, serde_json::to_value(&event).unwrap());
    let request = HoldCapacityRequest {
        segment_ref: booking["segmentRef"].as_str().unwrap().to_string(),
        segment_booking_id: booking["segmentBookingId"].as_str().unwrap().to_string(),
        traveler_ref: booking["travelerRef"].as_str().unwrap().to_string(),
        class_ref: "standard".to_string(), quantity: 1,
    };
    let restarted = PostgresCapacityService::new(pool.clone()).unwrap();
    let key = uuid::Uuid::now_v7().to_string();
    sqlx::query("ALTER TABLE outbox ADD CONSTRAINT block_confirmation CHECK (envelope->>'eventType' <> 'CapacityHoldConfirmed')")
        .execute(&pool).await.unwrap();
    assert!(restarted.hold_capacity(request.clone(), &key, &event.correlation_id).await.is_err());
    assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM pending_booking_confirmations").fetch_one(&pool).await.unwrap(), 1);
    assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM capacity_hold_snapshots").fetch_one(&pool).await.unwrap(), 0);
    assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM inventory_pool_snapshots").fetch_one(&pool).await.unwrap(), 0);
    assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM outbox").fetch_one(&pool).await.unwrap(), 0);
    sqlx::query("ALTER TABLE outbox DROP CONSTRAINT block_confirmation").execute(&pool).await.unwrap();
    let held = restarted.hold_capacity(request.clone(), &key, &event.correlation_id).await.unwrap();
    assert_eq!(held.status, "CONFIRMED");
    let replay = restarted.hold_capacity(request, &key, &event.correlation_id).await.unwrap();
    assert_eq!(replay.status, "CONFIRMED");
    assert_eq!(replay.hold_id, held.hold_id);
    assert_eq!(service.get_hold(&held.hold_id).await.unwrap().status, "CONFIRMED");
    assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM pending_booking_confirmations").fetch_one(&pool).await.unwrap(), 0);
    assert_eq!(service.handle_inbound_event(event.clone()).await, HandlerResult::Success);
    assert_eq!(service.get_hold(&held.hold_id).await.unwrap().status, "CONFIRMED");
    let confirmed_count: i64 = sqlx::query_scalar("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='CapacityHoldConfirmed'").fetch_one(&pool).await.unwrap();
    assert_eq!(confirmed_count, 1);
    let cause: String = sqlx::query_scalar("SELECT envelope->>'causationId' FROM outbox WHERE envelope->>'eventType'='CapacityHoldConfirmed'").fetch_one(&pool).await.unwrap();
    assert_eq!(cause, event.event_id);
    sqlx::query("DELETE FROM processed_events").execute(&pool).await.unwrap();
    assert_eq!(service.handle_inbound_event(event.clone()).await, HandlerResult::Success);
    service.release_hold(&held.hold_id, &uuid::Uuid::now_v7().to_string(), &event.correlation_id).await.unwrap();
    sqlx::query("DELETE FROM processed_events").execute(&pool).await.unwrap();
    assert_eq!(service.handle_inbound_event(event).await, HandlerResult::Success);
    assert_eq!(service.get_hold(&held.hold_id).await.unwrap().status, "RELEASED");
    assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='CapacityHoldConfirmed'").fetch_one(&pool).await.unwrap(), 1);
    pool.close().await;
    sqlx::query(&format!("DROP SCHEMA {schema} CASCADE")).execute(&admin).await.unwrap();
    admin.close().await;
}

#[tokio::test]
#[ignore = "requires TEST_DATABASE_URL, TEST_REQUEST_EVENT_JSON and TEST_CONFIRM_EVENT_JSON"]
async fn reservation_and_confirmation_events_converge_in_both_orders_and_concurrently() {
    use capacity_availability::ports::{HandlerResult, WireEnvelope};
    let request: WireEnvelope = serde_json::from_str(&std::env::var("TEST_REQUEST_EVENT_JSON").unwrap()).unwrap();
    let confirmation: WireEnvelope = serde_json::from_str(&std::env::var("TEST_CONFIRM_EVENT_JSON").unwrap()).unwrap();
    assert_eq!(request.payload["segmentBookingId"], confirmation.payload["segmentBookingId"]);
    let url = std::env::var("TEST_DATABASE_URL").unwrap();
    let admin = PgPoolOptions::new().max_connections(1).connect(&url).await.unwrap();
    for ordering in 0..3 {
        let schema = format!("hold_ordering_{}", uuid::Uuid::now_v7().simple());
        sqlx::query(&format!("CREATE SCHEMA {schema}")).execute(&admin).await.unwrap();
        let options = PgConnectOptions::from_str(&url).unwrap().options([("search_path", schema.as_str())]);
        let pool = PgPoolOptions::new().max_connections(3).connect_with(options).await.unwrap();
        sqlx::raw_sql(include_str!("../migrations/001_capacity_storage.sql")).execute(&pool).await.unwrap();
        sqlx::raw_sql(include_str!("../migrations/007_pending_booking_confirmations.sql")).execute(&pool).await.unwrap();
        sqlx::raw_sql(include_str!("../migrations/008_pending_booking_releases.sql")).execute(&pool).await.unwrap();
        let service = PostgresCapacityService::new(pool.clone()).unwrap();
        if ordering == 0 {
            assert_eq!(service.handle_inbound_event(confirmation.clone()).await, HandlerResult::Success);
            assert_eq!(service.handle_inbound_event(request.clone()).await, HandlerResult::Success);
        } else if ordering == 1 {
            assert_eq!(service.handle_inbound_event(request.clone()).await, HandlerResult::Success);
            assert_eq!(service.handle_inbound_event(confirmation.clone()).await, HandlerResult::Success);
        } else {
            let (requested, confirmed) = tokio::join!(
                service.handle_inbound_event(request.clone()),
                service.handle_inbound_event(confirmation.clone()),
            );
            assert_eq!(requested, HandlerResult::Success);
            assert_eq!(confirmed, HandlerResult::Success);
        }
        let saved: serde_json::Value = sqlx::query_scalar("SELECT data FROM capacity_hold_snapshots").fetch_one(&pool).await.unwrap();
        assert_eq!(saved["state"], "Confirmed");
        let snapshot = service.get_hold(saved["holdId"].as_str().unwrap()).await.unwrap();
        assert_eq!(snapshot.status, "CONFIRMED");
        assert_eq!(snapshot.segment_booking_ref.as_deref(), request.payload["segmentBookingId"].as_str());
        assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM pending_booking_confirmations").fetch_one(&pool).await.unwrap(), 0);
        assert_eq!(service.handle_inbound_event(confirmation.clone()).await, HandlerResult::Success);
        assert_eq!(service.handle_inbound_event(request.clone()).await, HandlerResult::Success);
        assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM capacity_hold_snapshots").fetch_one(&pool).await.unwrap(), 1);
        assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='CapacityHoldConfirmed'").fetch_one(&pool).await.unwrap(), 1);
        assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM processed_events").fetch_one(&pool).await.unwrap(), 2);
        if ordering == 0 {
            let status: String = sqlx::query_scalar("SELECT response_body->>'status' FROM idempotency_records WHERE key=$1")
                .bind(request.payload["idempotencyKey"].as_str().unwrap()).fetch_one(&pool).await.unwrap();
            assert_eq!(status, "CONFIRMED");
        }
        pool.close().await;
        sqlx::query(&format!("DROP SCHEMA {schema} CASCADE")).execute(&admin).await.unwrap();
    }
    admin.close().await;
}

#[tokio::test]
#[ignore = "requires TEST_DATABASE_URL and TEST_FAILED_HOLD_JSON"]
async fn no_capacity_result_remains_failed_after_replay_and_available_capacity() {
    use capacity_availability::ports::{HandlerResult, WireEnvelope};
    let source: serde_json::Value = serde_json::from_str(&std::env::var("TEST_FAILED_HOLD_JSON").unwrap()).unwrap();
    let mut request: WireEnvelope = serde_json::from_value(source["request"].clone()).unwrap();
    let confirmation: WireEnvelope = serde_json::from_value(source["confirmation"].clone()).unwrap();
    let url = std::env::var("TEST_DATABASE_URL").unwrap();
    let admin = PgPoolOptions::new().max_connections(1).connect(&url).await.unwrap();
    let schema = format!("failed_hold_{}", uuid::Uuid::now_v7().simple());
    sqlx::query(&format!("CREATE SCHEMA {schema}")).execute(&admin).await.unwrap();
    let options = PgConnectOptions::from_str(&url).unwrap().options([("search_path", schema.as_str())]);
    let pool = PgPoolOptions::new().max_connections(3).connect_with(options).await.unwrap();
    sqlx::raw_sql(include_str!("../migrations/001_capacity_storage.sql")).execute(&pool).await.unwrap();
    sqlx::raw_sql(include_str!("../migrations/007_pending_booking_confirmations.sql")).execute(&pool).await.unwrap();
    sqlx::raw_sql(include_str!("../migrations/008_pending_booking_releases.sql")).execute(&pool).await.unwrap();
    sqlx::query("INSERT INTO inventory_pool_snapshots(id,version,data) VALUES ($1,$2,$3)")
        .bind(source["pool"]["id"].as_str().unwrap()).bind(source["pool"]["version"].as_i64().unwrap()).bind(&source["pool"]["data"]).execute(&pool).await.unwrap();
    let service = PostgresCapacityService::new(pool.clone()).unwrap();
    assert_eq!(service.handle_inbound_event(confirmation.clone()).await, HandlerResult::Success);
    assert_eq!(service.handle_inbound_event(request.clone()).await, HandlerResult::Success);
    assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM pending_booking_confirmations").fetch_one(&pool).await.unwrap(), 0);
    let snapshot: serde_json::Value = sqlx::query_scalar("SELECT data FROM capacity_hold_snapshots").fetch_one(&pool).await.unwrap();
    assert_eq!(snapshot["state"], "Failed");
    let hold_id = snapshot["holdId"].as_str().unwrap();
    assert_eq!(service.get_hold(hold_id).await.unwrap().status, "FAILED");
    let status: i32 = sqlx::query_scalar("SELECT status_code FROM idempotency_records WHERE key=$1")
        .bind(request.payload["idempotencyKey"].as_str().unwrap()).fetch_one(&pool).await.unwrap();
    assert_eq!(status, 201);
    assert_eq!(service.handle_inbound_event(confirmation).await, HandlerResult::Success);
    sqlx::query("UPDATE inventory_pool_snapshots SET data=jsonb_set(data,'{holds}','[]'::jsonb)").execute(&pool).await.unwrap();
    sqlx::query("DELETE FROM idempotency_records").execute(&pool).await.unwrap();
    request.event_id = rust_kit::messaging::event_id();
    assert_eq!(service.handle_inbound_event(request).await, HandlerResult::Success);
    assert_eq!(service.get_hold(hold_id).await.unwrap().status, "FAILED");
    assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM capacity_hold_snapshots").fetch_one(&pool).await.unwrap(), 1);
    assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='CapacityHeld'").fetch_one(&pool).await.unwrap(), 0);
    assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='CapacityHoldFailed'").fetch_one(&pool).await.unwrap(), 2);
    pool.close().await;
    sqlx::query(&format!("DROP SCHEMA {schema} CASCADE")).execute(&admin).await.unwrap();
    admin.close().await;
}
