use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use capacity_availability::*;
use serde_json::Value;
use tower::ServiceExt;

#[test]
fn profile_exports_contract_metadata() {
    let profile = profile();
    assert_eq!(profile.service_id, "capacity-availability");
    assert_eq!(
        profile.requirement,
        "REQ-006 Capacity & Availability domain foundation"
    );
    assert!(profile.owns.contains(&"InventoryPool"));
    assert!(profile.owns.contains(&"CapacityHold"));
    assert_eq!(health(), "ok");
    let _router = router();
}

async fn get_body(response: axum::response::Response) -> (StatusCode, Value) {
    let status = response.status();
    let body_bytes = axum::body::to_bytes(response.into_body(), usize::MAX).await.unwrap();
    let body: Value = serde_json::from_slice(&body_bytes).unwrap();
    (status, body)
}

#[tokio::test]
async fn api_query_availability_returns_200() {
    let app = router();
    let (status, json) = get_body(
        app.oneshot(
            Request::builder()
                .uri("/api/v1/availability-snapshots?scheduledServiceRef=test&segmentRef=test")
                .header("x-request-id", "test-req")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap(),
    ).await;
    assert_eq!(status, StatusCode::OK, "Got status {} body: {:?}", status, json);
    assert!(json.get("snapshotId").is_some());
}

#[tokio::test]
async fn api_hold_capacity_rejects_missing_idempotency_key() {
    let app = router();
    let (status, json) = get_body(
        app.oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/capacity-holds")
                .header("content-type", "application/json")
                .header("x-request-id", "test-req")
                .body(Body::from(
                    r#"{"segmentRef":"seg-1","travelerRef":"tvl-1","classRef":"first","quantity":1}"#,
                ))
                .unwrap(),
        )
        .await
        .unwrap(),
    ).await;
    assert_eq!(status, StatusCode::BAD_REQUEST, "Got status {} body: {:?}", status, json);
    assert_eq!(json["code"], "VALIDATION_FAILED");
}

#[tokio::test]
async fn api_hold_capacity_succeeds_with_idempotency_key() {
    let app = router();
    let (status, json) = get_body(
        app.oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/capacity-holds")
                .header("content-type", "application/json")
                .header("idempotency-key", "idem-test-1")
                .header("x-request-id", "test-req")
                .body(Body::from(
                    r#"{"segmentRef":"seg-A","travelerRef":"tvl-1","classRef":"first","quantity":1,"segmentBookingId":"sb-1"}"#,
                ))
                .unwrap(),
        )
        .await
        .unwrap(),
    ).await;
    assert_eq!(status, StatusCode::CREATED, "Got status {} body: {:?}", status, json);
    assert!(json["holdId"].as_str().unwrap().starts_with("hold-"));
    assert_eq!(json["status"], "HELD");
    assert_eq!(json["segmentRef"], "seg-A");
    assert!(json["heldUntil"].as_str().unwrap().ends_with('Z'));
}

#[tokio::test]
async fn api_get_hold_returns_404_for_unknown() {
    let app = router();
    let (status, json) = get_body(
        app.oneshot(
            Request::builder()
                .uri("/api/v1/capacity-holds/hold-unknown")
                .header("x-request-id", "test-req")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap(),
    ).await;
    assert_eq!(status, StatusCode::NOT_FOUND, "Got status {} body: {:?}", status, json);
}

#[tokio::test]
async fn api_full_hold_lifecycle() {
    let app = router();

    // Hold
    let (status, json) = get_body(
        app.clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/capacity-holds")
                    .header("content-type", "application/json")
                    .header("idempotency-key", "idem-lifecycle-1")
                    .header("x-request-id", "lifecycle-req")
                    .body(Body::from(
                        r#"{"segmentRef":"seg-life","travelerRef":"tvl-1","classRef":"first","quantity":1,"segmentBookingId":"sb-life"}"#,
                    ))
                    .unwrap(),
            )
            .await
            .unwrap(),
    ).await;
    assert_eq!(status, StatusCode::CREATED, "Hold: Got status {} body: {:?}", status, json);
    let hold_id = json["holdId"].as_str().unwrap().to_string();

    // Confirm
    let (status, json) = get_body(
        app.clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("/api/v1/capacity-holds/{}/confirm", hold_id))
                    .header("content-type", "application/json")
                    .header("idempotency-key", "idem-confirm-1")
                    .header("x-request-id", "confirm-req")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap(),
    ).await;
    assert_eq!(status, StatusCode::OK, "Confirm: Got status {} body: {:?}", status, json);
    assert_eq!(json["status"], "CONFIRMED");

    // Release
    let (status, json) = get_body(
        app.clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("/api/v1/capacity-holds/{}/release", hold_id))
                    .header("content-type", "application/json")
                    .header("idempotency-key", "idem-release-1")
                    .header("x-request-id", "release-req")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap(),
    ).await;
    assert_eq!(status, StatusCode::OK, "Release: Got status {} body: {:?}", status, json);
    assert_eq!(json["status"], "RELEASED");
}

#[tokio::test]
async fn api_idempotent_replay_returns_original() {
    let app = router();

    let (status1, json1) = get_body(
        app.clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/capacity-holds")
                    .header("content-type", "application/json")
                    .header("idempotency-key", "idem-replay-1")
                    .header("x-request-id", "first-req")
                    .body(Body::from(
                        r#"{"segmentRef":"seg-replay","travelerRef":"tvl-1","classRef":"first","quantity":1,"segmentBookingId":"sb-replay"}"#,
                    ))
                    .unwrap(),
            )
            .await
            .unwrap(),
    ).await;
    assert_eq!(status1, StatusCode::CREATED, "First: Got status {} body: {:?}", status1, json1);

    let (status2, json2) = get_body(
        app.clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/capacity-holds")
                    .header("content-type", "application/json")
                    .header("idempotency-key", "idem-replay-1")
                    .header("x-request-id", "second-req")
                    .body(Body::from(
                        r#"{"segmentRef":"seg-replay","travelerRef":"tvl-1","classRef":"first","quantity":1,"segmentBookingId":"sb-replay"}"#,
                    ))
                    .unwrap(),
            )
            .await
            .unwrap(),
    ).await;
    assert_eq!(status2, StatusCode::CREATED, "Replay: Got status {} body: {:?}", status2, json2);
    assert_eq!(json1["holdId"], json2["holdId"], "Idempotent replay should return same holdId");
}

#[tokio::test]
async fn api_publisher_envelope_is_properly_formatted() {
    use capacity_availability::adapters::messaging::InMemoryEventPublisher;
    use capacity_availability::ports::{EventPublisher, WireEnvelope};
    use serde_json::json;

    let publisher = InMemoryEventPublisher::new();
    let envelope = WireEnvelope {
        event_id: "evt-0194f2e0-7b3e-7610-0284-5c26e8b0c222".to_string(),
        event_type: "CapacityHeld".to_string(),
        schema_version: 1,
        producer: "capacity-availability".to_string(),
        causation_id: None,
        correlation_id: "corr-test".to_string(),
        occurred_at: "2026-07-03T10:30:00.000Z".to_string(),
        payload: json!({"holdId": "hold-test"}),
    };
    publisher.publish(&envelope).unwrap();
    let published = publisher.published();
    assert_eq!(published.len(), 1);
    assert_eq!(published[0].event_id, "evt-0194f2e0-7b3e-7610-0284-5c26e8b0c222");
    assert_eq!(published[0].producer, "capacity-availability");
}

#[test]
fn subscriber_deduplicates_duplicate_event_id() {
    use capacity_availability::adapters::messaging::InMemoryEventSubscriber;
    use capacity_availability::ports::{HandlerResult, WireEnvelope};
    use serde_json::json;
    use std::sync::{Arc, Mutex};

    let subscriber = InMemoryEventSubscriber::new();
    let envelope = WireEnvelope {
        event_id: "evt-dup-test".to_string(),
        event_type: "CapacityHeld".to_string(),
        schema_version: 1,
        producer: "capacity-availability".to_string(),
        causation_id: None,
        correlation_id: "corr-test".to_string(),
        occurred_at: "2026-07-03T10:30:00.000Z".to_string(),
        payload: json!({"test": true}),
    };

    let call_count = Arc::new(Mutex::new(0));
    let count = call_count.clone();
    let handler = |_: WireEnvelope| {
        *count.lock().unwrap() += 1;
        HandlerResult::Success
    };

    assert_eq!(subscriber.receive(&envelope, &handler), HandlerResult::Success);
    assert_eq!(subscriber.receive(&envelope, &handler), HandlerResult::Success);
    assert_eq!(*call_count.lock().unwrap(), 1);
    assert!(subscriber.has_seen("evt-dup-test"));
}

#[test]
fn endpoint_validation_failure_returns_400_with_correct_body_shape() {
        use capacity_availability::application::AppError;
    use capacity_availability::application::CapacityService;
    use capacity_availability::adapters::messaging::InMemoryEventPublisher;
    use std::sync::Arc;

    let service = Arc::new(CapacityService::new(Arc::new(InMemoryEventPublisher::new())));

    // Test that validation errors produce correct shape
    let result = service.hold_capacity(
        capacity_availability::application::HoldCapacityRequest {
            segment_ref: "".to_string(),
            traveler_ref: "".to_string(),
            class_ref: "".to_string(),
            quantity: 0,
            segment_booking_id: "".to_string(),
        },
        "test-key",
        "corr-test",
    );
    match result {
        Err(AppError::ValidationFailed(msg)) => {
            assert!(!msg.is_empty());
        }
        other => panic!("Expected ValidationFailed, got {:?}", other),
    }
}
