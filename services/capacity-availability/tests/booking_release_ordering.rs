use capacity_availability::adapters::storage::PostgresCapacityService;
use capacity_availability::application::HoldCapacityRequest;
use capacity_availability::ports::{HandlerResult, WireEnvelope};
use sqlx::postgres::{PgConnectOptions, PgPoolOptions};
use std::str::FromStr;

#[tokio::test]
#[ignore = "requires TEST_DATABASE_URL and TEST_CANCELLED_BOOKING_EVENTS_JSON"]
async fn cancelled_bookings_release_capacity_in_every_event_order() {
    let source: serde_json::Value =
        serde_json::from_str(&std::env::var("TEST_CANCELLED_BOOKING_EVENTS_JSON").unwrap())
            .unwrap();
    let request: WireEnvelope = serde_json::from_value(source["request"].clone()).unwrap();
    let confirmation: WireEnvelope =
        serde_json::from_value(source["confirmation"].clone()).unwrap();
    let cancellation: WireEnvelope =
        serde_json::from_value(source["cancellation"].clone()).unwrap();
    assert_eq!(
        request.payload["segmentBookingId"],
        confirmation.payload["segmentBookingId"]
    );
    assert_eq!(
        request.payload["segmentBookingId"],
        cancellation.payload["segmentBookingId"]
    );
    let url = std::env::var("TEST_DATABASE_URL").unwrap();
    let admin = PgPoolOptions::new()
        .max_connections(1)
        .connect(&url)
        .await
        .unwrap();
    let events = [request.clone(), confirmation.clone(), cancellation.clone()];
    let orders = [
        [0, 1, 2],
        [0, 2, 1],
        [1, 0, 2],
        [1, 2, 0],
        [2, 0, 1],
        [2, 1, 0],
    ];
    for ordering in 0..8 {
        let schema = format!("hold_release_{}", uuid::Uuid::now_v7().simple());
        sqlx::query(&format!("CREATE SCHEMA {schema}"))
            .execute(&admin)
            .await
            .unwrap();
        let options = PgConnectOptions::from_str(&url)
            .unwrap()
            .options([("search_path", schema.as_str())]);
        let pool = PgPoolOptions::new()
            .max_connections(4)
            .connect_with(options)
            .await
            .unwrap();
        sqlx::raw_sql(include_str!("../migrations/001_capacity_storage.sql"))
            .execute(&pool)
            .await
            .unwrap();
        sqlx::raw_sql(include_str!(
            "../migrations/007_pending_booking_confirmations.sql"
        ))
        .execute(&pool)
        .await
        .unwrap();
        sqlx::raw_sql(include_str!(
            "../migrations/008_pending_booking_releases.sql"
        ))
        .execute(&pool)
        .await
        .unwrap();
        let service = PostgresCapacityService::new(pool.clone()).unwrap();
        if ordering < orders.len() {
            for index in orders[ordering] {
                assert_eq!(
                    service.handle_inbound_event(events[index].clone()).await,
                    HandlerResult::Success
                );
            }
        } else if ordering == 6 {
            let results = tokio::join!(
                service.handle_inbound_event(request.clone()),
                service.handle_inbound_event(confirmation.clone()),
                service.handle_inbound_event(cancellation.clone()),
            );
            assert_eq!(
                results,
                (
                    HandlerResult::Success,
                    HandlerResult::Success,
                    HandlerResult::Success
                )
            );
        } else {
            assert_eq!(
                service.handle_inbound_event(cancellation.clone()).await,
                HandlerResult::Success
            );
            assert_eq!(
                service.handle_inbound_event(confirmation.clone()).await,
                HandlerResult::Success
            );
            let saved: serde_json::Value =
                sqlx::query_scalar("SELECT envelope FROM pending_booking_releases")
                    .fetch_one(&pool)
                    .await
                    .unwrap();
            assert_eq!(saved, serde_json::to_value(&cancellation).unwrap());
            let restarted = PostgresCapacityService::new(pool.clone()).unwrap();
            let hold_request = HoldCapacityRequest {
                segment_ref: request.payload["segmentRef"].as_str().unwrap().to_string(),
                segment_booking_id: request.payload["segmentBookingId"]
                    .as_str()
                    .unwrap()
                    .to_string(),
                traveler_ref: source["booking"]["travelerRef"]
                    .as_str()
                    .unwrap()
                    .to_string(),
                class_ref: request
                    .payload
                    .get("classRef")
                    .or_else(|| request.payload.get("seatClassRef"))
                    .and_then(|value| value.as_str())
                    .unwrap_or("standard")
                    .to_string(),
                quantity: 1,
            };
            let key = uuid::Uuid::now_v7().to_string();
            sqlx::query("ALTER TABLE outbox ADD CONSTRAINT block_release CHECK (envelope->>'eventType' <> 'CapacityReleased')")
                .execute(&pool).await.unwrap();
            assert!(
                restarted
                    .hold_capacity(hold_request.clone(), &key, &request.correlation_id)
                    .await
                    .is_err()
            );
            assert_eq!(
                sqlx::query_scalar::<_, i64>("SELECT count(*) FROM pending_booking_releases")
                    .fetch_one(&pool)
                    .await
                    .unwrap(),
                1
            );
            assert_eq!(
                sqlx::query_scalar::<_, i64>("SELECT count(*) FROM pending_booking_confirmations")
                    .fetch_one(&pool)
                    .await
                    .unwrap(),
                1
            );
            assert_eq!(
                sqlx::query_scalar::<_, i64>("SELECT count(*) FROM capacity_hold_snapshots")
                    .fetch_one(&pool)
                    .await
                    .unwrap(),
                0
            );
            assert_eq!(
                sqlx::query_scalar::<_, i64>("SELECT count(*) FROM inventory_pool_snapshots")
                    .fetch_one(&pool)
                    .await
                    .unwrap(),
                0
            );
            assert_eq!(
                sqlx::query_scalar::<_, i64>("SELECT count(*) FROM outbox")
                    .fetch_one(&pool)
                    .await
                    .unwrap(),
                0
            );
            sqlx::query("ALTER TABLE outbox DROP CONSTRAINT block_release")
                .execute(&pool)
                .await
                .unwrap();
            let response = restarted
                .hold_capacity(hold_request.clone(), &key, &request.correlation_id)
                .await
                .unwrap();
            assert_eq!(response.status, "RELEASED");
            assert_eq!(
                restarted
                    .hold_capacity(hold_request, &key, &request.correlation_id)
                    .await
                    .unwrap()
                    .status,
                "RELEASED"
            );
            let cause: String = sqlx::query_scalar("SELECT envelope->>'causationId' FROM outbox WHERE envelope->>'eventType'='CapacityReleased'").fetch_one(&pool).await.unwrap();
            assert_eq!(cause, cancellation.event_id);
        }
        let saved: serde_json::Value =
            sqlx::query_scalar("SELECT data FROM capacity_hold_snapshots")
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(saved["state"], "Released", "ordering {ordering}");
        assert_eq!(
            service
                .get_hold(saved["holdId"].as_str().unwrap())
                .await
                .unwrap()
                .status,
            "RELEASED"
        );
        assert_eq!(
            sqlx::query_scalar::<_, i64>("SELECT count(*) FROM pending_booking_releases")
                .fetch_one(&pool)
                .await
                .unwrap(),
            0
        );
        assert_eq!(
            sqlx::query_scalar::<_, i64>("SELECT count(*) FROM pending_booking_confirmations")
                .fetch_one(&pool)
                .await
                .unwrap(),
            0
        );
        let scheduled_service: String =
            sqlx::query_scalar("SELECT scheduled_service_ref FROM inventory_pool_snapshots")
                .fetch_one(&pool)
                .await
                .unwrap();
        let available = service
            .query_availability(
                &scheduled_service,
                request.payload["segmentRef"].as_str().unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(available.available_count, available.total_units);
        sqlx::query("DELETE FROM processed_events")
            .execute(&pool)
            .await
            .unwrap();
        assert_eq!(
            service.handle_inbound_event(cancellation.clone()).await,
            HandlerResult::Success
        );
        assert_eq!(
            service.handle_inbound_event(confirmation.clone()).await,
            HandlerResult::Success
        );
        assert_eq!(
            sqlx::query_scalar::<_, i64>(
                "SELECT count(*) FROM outbox WHERE envelope->>'eventType'='CapacityReleased'"
            )
            .fetch_one(&pool)
            .await
            .unwrap(),
            1
        );
        assert_eq!(
            sqlx::query_scalar::<_, i64>("SELECT count(*) FROM pending_booking_releases")
                .fetch_one(&pool)
                .await
                .unwrap(),
            0
        );
        pool.close().await;
        sqlx::query(&format!("DROP SCHEMA {schema} CASCADE"))
            .execute(&admin)
            .await
            .unwrap();
    }
    admin.close().await;
}
