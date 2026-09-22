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
async fn confirmation_waits_for_the_hold_without_acknowledging_missing_state() {
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
    let service = PostgresCapacityService::new(pool.clone()).unwrap();
    let initial = service.handle_inbound_event(event.clone()).await;
    assert!(matches!(initial, HandlerResult::TransientError(_)), "{initial:?}");
    let processed: i64 = sqlx::query_scalar("SELECT count(*) FROM processed_events").fetch_one(&pool).await.unwrap();
    assert_eq!(processed, 0);
    let request = HoldCapacityRequest {
        segment_ref: booking["segmentRef"].as_str().unwrap().to_string(),
        segment_booking_id: booking["segmentBookingId"].as_str().unwrap().to_string(),
        traveler_ref: booking["travelerRef"].as_str().unwrap().to_string(),
        class_ref: "standard".to_string(), quantity: 1,
    };
    let held = service.hold_capacity(request, &uuid::Uuid::now_v7().to_string(), &event.correlation_id).await.unwrap();
    assert_eq!(service.handle_inbound_event(event.clone()).await, HandlerResult::Success);
    assert_eq!(service.get_hold(&held.hold_id).await.unwrap().status, "CONFIRMED");
    let confirmed_count: i64 = sqlx::query_scalar("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='CapacityHoldConfirmed'").fetch_one(&pool).await.unwrap();
    assert_eq!(confirmed_count, 1);
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
