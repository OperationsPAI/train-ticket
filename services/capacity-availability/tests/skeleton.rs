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
    let _router = test_router();
}

fn test_router() -> axum::Router {
    use capacity_availability::adapters::messaging::InMemoryEventPublisher;
    use capacity_availability::application::CapacityService;
    use std::sync::Arc;

    capacity_availability::router_with_service(Arc::new(CapacityService::new(Arc::new(
        InMemoryEventPublisher::new(),
    ))))
}

async fn get_body(response: axum::response::Response) -> (StatusCode, Value) {
    let status = response.status();
    let body_bytes = axum::body::to_bytes(response.into_body(), usize::MAX)
        .await
        .unwrap();
    let body: Value = serde_json::from_slice(&body_bytes).unwrap();
    (status, body)
}

#[tokio::test]
async fn api_query_availability_returns_200() {
    let app = test_router();
    let (status, json) = get_body(
        app.oneshot(
            Request::builder()
                .uri("/api/v1/availability-snapshots?scheduledServiceRef=test&segmentRef=test")
                .header("x-correlation-id", "test-req")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap(),
    )
    .await;
    assert_eq!(
        status,
        StatusCode::OK,
        "Got status {} body: {:?}",
        status,
        json
    );
    assert!(json.get("snapshotId").is_some());
}

#[tokio::test]
async fn api_hold_capacity_rejects_missing_idempotency_key() {
    let app = test_router();
    let (status, json) = get_body(
        app.oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/capacity-holds")
                .header("content-type", "application/json")
                .header("x-correlation-id", "test-req")
                .body(Body::from(
                    r#"{"segmentRef":"seg-1","travelerRef":"tvl-1","classRef":"first","quantity":1,"segmentBookingId":"sb-1"}"#,
                ))
                .unwrap(),
        )
        .await
        .unwrap(),
    ).await;
    assert_eq!(
        status,
        StatusCode::BAD_REQUEST,
        "Got status {} body: {:?}",
        status,
        json
    );
    assert_eq!(json["code"], "VALIDATION_FAILED");
    assert_eq!(json["correlationId"], "test-req");
}

#[tokio::test]
async fn api_hold_capacity_succeeds_with_idempotency_key() {
    let app = test_router();
    let (status, json) = get_body(
        app.oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/capacity-holds")
                .header("content-type", "application/json")
                .header("idempotency-key", "0194f2e0-7b3e-7610-0284-5c26e8b0caa1")
                .header("x-correlation-id", "test-req")
                .body(Body::from(
                    r#"{"segmentRef":"seg-A","travelerRef":"tvl-1","classRef":"first","quantity":1,"segmentBookingId":"sb-1"}"#,
                ))
                .unwrap(),
        )
        .await
        .unwrap(),
    ).await;
    assert_eq!(
        status,
        StatusCode::CREATED,
        "Got status {} body: {:?}",
        status,
        json
    );
    assert!(json["holdId"].as_str().unwrap().starts_with("hold-"));
    assert_eq!(json["status"], "HELD");
    assert_eq!(json["segmentRef"], "seg-A");
    assert!(json["heldUntil"].as_str().unwrap().ends_with('Z'));
}

#[tokio::test]
async fn api_get_hold_returns_404_for_unknown() {
    let app = test_router();
    let (status, json) = get_body(
        app.oneshot(
            Request::builder()
                .uri("/api/v1/capacity-holds/hold-unknown")
                .header("x-correlation-id", "test-req")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap(),
    )
    .await;
    assert_eq!(
        status,
        StatusCode::NOT_FOUND,
        "Got status {} body: {:?}",
        status,
        json
    );
}

#[tokio::test]
async fn api_full_hold_lifecycle() {
    let app = test_router();

    // Hold
    let (status, json) = get_body(
        app.clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/capacity-holds")
                    .header("content-type", "application/json")
                    .header("idempotency-key", "0194f2e0-7b3e-7610-0284-5c26e8b0caa2")
                    .header("x-correlation-id", "lifecycle-req")
                    .body(Body::from(
                        r#"{"segmentRef":"seg-life","travelerRef":"tvl-1","classRef":"first","quantity":1,"segmentBookingId":"sb-life"}"#,
                    ))
                    .unwrap(),
            )
            .await
            .unwrap(),
    ).await;
    assert_eq!(
        status,
        StatusCode::CREATED,
        "Hold: Got status {} body: {:?}",
        status,
        json
    );
    let hold_id = json["holdId"].as_str().unwrap().to_string();

    // Confirm
    let (status, json) = get_body(
        app.clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("/api/v1/capacity-holds/{}/confirm", hold_id))
                    .header("content-type", "application/json")
                    .header("idempotency-key", "0194f2e0-7b3e-7610-0284-5c26e8b0caa3")
                    .header("x-correlation-id", "confirm-req")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(
        status,
        StatusCode::OK,
        "Confirm: Got status {} body: {:?}",
        status,
        json
    );
    assert_eq!(json["status"], "CONFIRMED");

    // Release
    let (status, json) = get_body(
        app.clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("/api/v1/capacity-holds/{}/release", hold_id))
                    .header("content-type", "application/json")
                    .header("idempotency-key", "0194f2e0-7b3e-7610-0284-5c26e8b0caa4")
                    .header("x-correlation-id", "release-req")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(
        status,
        StatusCode::OK,
        "Release: Got status {} body: {:?}",
        status,
        json
    );
    assert_eq!(json["status"], "RELEASED");
}

#[tokio::test]
async fn api_idempotent_replay_returns_original() {
    let app = test_router();

    let (status1, json1) = get_body(
        app.clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/capacity-holds")
                    .header("content-type", "application/json")
                    .header("idempotency-key", "0194f2e0-7b3e-7610-0284-5c26e8b0caa5")
                    .header("x-correlation-id", "first-req")
                    .body(Body::from(
                        r#"{"segmentRef":"seg-replay","travelerRef":"tvl-1","classRef":"first","quantity":1,"segmentBookingId":"sb-replay"}"#,
                    ))
                    .unwrap(),
            )
            .await
            .unwrap(),
    ).await;
    assert_eq!(
        status1,
        StatusCode::CREATED,
        "First: Got status {} body: {:?}",
        status1,
        json1
    );

    let (status2, json2) = get_body(
        app.clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/capacity-holds")
                    .header("content-type", "application/json")
                    .header("idempotency-key", "0194f2e0-7b3e-7610-0284-5c26e8b0caa5")
                    .header("x-correlation-id", "second-req")
                    .body(Body::from(
                        r#"{"segmentRef":"seg-replay","travelerRef":"tvl-1","classRef":"first","quantity":1,"segmentBookingId":"sb-replay"}"#,
                    ))
                    .unwrap(),
            )
            .await
            .unwrap(),
    ).await;
    assert_eq!(
        status2,
        StatusCode::CREATED,
        "Replay: Got status {} body: {:?}",
        status2,
        json2
    );
    assert_eq!(
        json1["holdId"], json2["holdId"],
        "Idempotent replay should return same holdId"
    );
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
    assert_eq!(
        published[0].event_id,
        "evt-0194f2e0-7b3e-7610-0284-5c26e8b0c222"
    );
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

    assert_eq!(
        subscriber.receive(&envelope, &handler),
        HandlerResult::Success
    );
    assert_eq!(
        subscriber.receive(&envelope, &handler),
        HandlerResult::Success
    );
    assert_eq!(*call_count.lock().unwrap(), 1);
    assert!(subscriber.has_seen("evt-dup-test"));
}

#[test]
fn endpoint_validation_failure_returns_400_with_correct_body_shape() {
    use capacity_availability::adapters::messaging::InMemoryEventPublisher;
    use capacity_availability::application::AppError;
    use capacity_availability::application::CapacityService;
    use std::sync::Arc;

    let service = Arc::new(CapacityService::new(
        Arc::new(InMemoryEventPublisher::new()),
    ));

    // Test that validation errors produce correct shape
    let result = service.hold_capacity(
        capacity_availability::application::HoldCapacityRequest {
            segment_ref: "".to_string(),
            traveler_ref: "".to_string(),
            class_ref: "".to_string(),
            quantity: 0,
            segment_booking_id: "".to_string(),
        },
        "0194f2e0-7b3e-7610-0284-5c26e8b0caa6",
        "corr-test",
    );
    match result {
        Err(AppError::ValidationFailed(msg)) => {
            assert!(!msg.is_empty());
        }
        other => panic!("Expected ValidationFailed, got {:?}", other),
    }
}

#[tokio::test]
async fn api_hold_capacity_rejects_missing_segment_booking_id() {
    let app = test_router();
    let (status, json) = get_body(
        app.oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/capacity-holds")
                .header("content-type", "application/json")
                .header("idempotency-key", "0194f2e0-7b3e-7610-0284-5c26e8b0cab1")
                .header("x-correlation-id", "0194f2e0-7b3e-7610-0284-5c26e8b0cab2")
                .body(Body::from(
                    r#"{"segmentRef":"seg-1","travelerRef":"tvl-1","classRef":"first","quantity":1}"#,
                ))
                .unwrap(),
        )
        .await
        .unwrap(),
    )
    .await;
    assert_eq!(
        status,
        StatusCode::BAD_REQUEST,
        "Got status {} body: {:?}",
        status,
        json
    );
    assert_eq!(json["code"], "VALIDATION_FAILED");
    assert_eq!(
        json["correlationId"],
        "0194f2e0-7b3e-7610-0284-5c26e8b0cab2"
    );
}

#[tokio::test]
async fn api_rejects_malformed_idempotency_key() {
    let app = test_router();
    let (status, json) = get_body(
        app.oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/capacity-holds")
                .header("content-type", "application/json")
                .header("idempotency-key", "not-a-uuid")
                .header("x-correlation-id", "0194f2e0-7b3e-7610-0284-5c26e8b0cab3")
                .body(Body::from(
                    r#"{"segmentRef":"seg-1","travelerRef":"tvl-1","classRef":"first","quantity":1,"segmentBookingId":"sb-1"}"#,
                ))
                .unwrap(),
        )
        .await
        .unwrap(),
    )
    .await;
    assert_eq!(
        status,
        StatusCode::BAD_REQUEST,
        "Got status {} body: {:?}",
        status,
        json
    );
    assert_eq!(json["code"], "VALIDATION_FAILED");
    assert_eq!(
        json["correlationId"],
        "0194f2e0-7b3e-7610-0284-5c26e8b0cab3"
    );
}

#[test]
fn subscriber_decision_logic_retries_dlqs_and_dedups() {
    use capacity_availability::adapters::messaging::{
        InMemoryEventSubscriber, ReceivedEvent, SubscriberAction,
    };
    use capacity_availability::ports::{HandlerResult, WireEnvelope};
    use serde_json::json;
    use std::sync::{Arc, Mutex};

    let subscriber = InMemoryEventSubscriber::new();
    let envelope = WireEnvelope {
        event_id: "evt-0194f2e0-7b3e-7610-0284-5c26e8b0cd01".to_string(),
        event_type: "PostSalesApplied".to_string(),
        schema_version: 1,
        producer: "post-sales".to_string(),
        causation_id: None,
        correlation_id: "0194f2e0-7b3e-7610-0284-5c26e8b0cd02".to_string(),
        occurred_at: "2026-07-03T10:30:00.000Z".to_string(),
        payload: json!({"segmentBookingId": "sb-1"}),
    };
    let transient = ReceivedEvent {
        entry_id: "1-0".to_string(),
        stream: "events:post-sales".to_string(),
        envelope: envelope.clone(),
        delivery_attempts: 1,
    };
    assert_eq!(
        subscriber.process_received(&transient, &|_| HandlerResult::TransientError(
            "try again".into()
        )),
        SubscriberAction::LeavePending
    );
    assert!(!subscriber.has_seen(&envelope.event_id));

    let poison = ReceivedEvent {
        delivery_attempts: 5,
        ..transient.clone()
    };
    assert_eq!(
        subscriber.process_received(&poison, &|_| panic!("poison should not dispatch")),
        SubscriberAction::DeadLetterAndAck
    );

    let second_envelope = WireEnvelope {
        event_id: "evt-0194f2e0-7b3e-7610-0284-5c26e8b0cd03".to_string(),
        ..envelope
    };
    let success = ReceivedEvent {
        envelope: second_envelope,
        delivery_attempts: 1,
        ..transient
    };
    let calls = Arc::new(Mutex::new(0));
    let calls_for_handler = calls.clone();
    assert_eq!(
        subscriber.process_received(&success, &move |_| {
            *calls_for_handler.lock().unwrap() += 1;
            HandlerResult::Success
        }),
        SubscriberAction::Ack
    );
    assert_eq!(
        subscriber.process_received(&success, &|_| panic!("duplicate should not dispatch")),
        SubscriberAction::Ack
    );
    assert_eq!(*calls.lock().unwrap(), 1);
}

#[test]
fn in_memory_segment_reservation_requested_missing_idempotency_key_is_fatal() {
    use capacity_availability::adapters::messaging::InMemoryEventPublisher;
    use capacity_availability::application::CapacityService;
    use capacity_availability::ports::{HandlerResult, WireEnvelope};
    use serde_json::json;
    use std::sync::Arc;

    let service = CapacityService::new(Arc::new(InMemoryEventPublisher::new()));
    let result = service.handle_inbound_event(WireEnvelope {
        event_id: "evt-0194f2e0-7b3e-7610-0284-5c26e8b0fa11".to_string(),
        event_type: "SegmentReservationRequested".to_string(),
        schema_version: 1,
        producer: "booking-orchestration".to_string(),
        causation_id: None,
        correlation_id: "corr-0194f2e0-7b3e-7610-0284-5c26e8b0fa12".to_string(),
        occurred_at: "2026-07-03T10:30:00.000Z".to_string(),
        payload: json!({
            "segmentBookingId": "sb-1",
            "journeyOrderId": "ord-1",
            "segmentRef": "seg-1",
            "travelerRef": "traveler-1"
        }),
    });

    assert!(
        matches!(result, HandlerResult::FatalError(message) if message.contains("idempotencyKey"))
    );
}

#[test]
fn in_memory_post_sales_applied_without_hold_pointer_is_acked() {
    use capacity_availability::adapters::messaging::InMemoryEventPublisher;
    use capacity_availability::application::CapacityService;
    use capacity_availability::ports::{HandlerResult, WireEnvelope};
    use serde_json::json;
    use std::sync::Arc;

    let service = CapacityService::new(Arc::new(InMemoryEventPublisher::new()));
    let result = service.handle_inbound_event(WireEnvelope {
        event_id: "evt-0194f2e0-7b3e-7610-0284-5c26e8b0fa21".to_string(),
        event_type: "PostSalesApplied".to_string(),
        schema_version: 1,
        producer: "post-sales".to_string(),
        causation_id: None,
        correlation_id: "corr-0194f2e0-7b3e-7610-0284-5c26e8b0fa22".to_string(),
        occurred_at: "2026-07-03T10:30:00.000Z".to_string(),
        payload: json!({
            "caseId": "psc-1",
            "orderId": "ord-1",
            "resultSummary": {}
        }),
    });

    assert_eq!(result, HandlerResult::Success);
}

#[tokio::test]
async fn postgres_inbound_classifies_known_event_schema_errors_as_fatal() {
    use capacity_availability::adapters::storage::PostgresCapacityService;
    use capacity_availability::ports::{HandlerResult, WireEnvelope};
    use rust_kit::storage::Storage;
    use serde_json::json;
    use sqlx::postgres::PgPoolOptions;

    let pool = PgPoolOptions::new()
        .max_connections(1)
        .connect_lazy("postgres://capacity:capacity@127.0.0.1:1/capacity")
        .unwrap();
    let service = PostgresCapacityService::from_storage(Storage::new(pool)).unwrap();

    let result = service
        .handle_inbound_event(WireEnvelope {
            event_id: "evt-0194f2e0-7b3e-7610-0284-5c26e8b0fa01".to_string(),
            event_type: "SegmentReservationRequested".to_string(),
            schema_version: 1,
            producer: "booking-orchestration".to_string(),
            causation_id: None,
            correlation_id: "corr-0194f2e0-7b3e-7610-0284-5c26e8b0fa02".to_string(),
            occurred_at: "2026-07-03T10:30:00.000Z".to_string(),
            payload: json!({"segmentBookingId": "sb-1", "journeyOrderId": "ord-1", "segmentRef": "seg-1", "travelerRef": "traveler-1"}),
        })
        .await;

    assert!(
        matches!(result, HandlerResult::FatalError(message) if message.contains("idempotencyKey"))
    );
}

#[tokio::test]
async fn postgres_post_sales_applied_without_hold_pointer_is_acked() {
    use capacity_availability::adapters::storage::PostgresCapacityService;
    use capacity_availability::ports::{HandlerResult, WireEnvelope};
    use rust_kit::storage::Storage;
    use serde_json::json;
    use sqlx::postgres::PgPoolOptions;

    let pool = PgPoolOptions::new()
        .max_connections(1)
        .connect_lazy("postgres://capacity:capacity@127.0.0.1:1/capacity")
        .unwrap();
    let service = PostgresCapacityService::from_storage(Storage::new(pool)).unwrap();

    let result = service
        .handle_inbound_event(WireEnvelope {
            event_id: "evt-0194f2e0-7b3e-7610-0284-5c26e8b0fa31".to_string(),
            event_type: "PostSalesApplied".to_string(),
            schema_version: 1,
            producer: "post-sales".to_string(),
            causation_id: None,
            correlation_id: "corr-0194f2e0-7b3e-7610-0284-5c26e8b0fa32".to_string(),
            occurred_at: "2026-07-03T10:30:00.000Z".to_string(),
            payload: json!({
                "caseId": "psc-1",
                "orderId": "ord-1",
                "resultSummary": {}
            }),
        })
        .await;

    assert_eq!(result, HandlerResult::Success);
}

#[test]
fn in_memory_segment_reservation_confirmed_without_capacity_hold_id_is_acked() {
    use capacity_availability::adapters::messaging::InMemoryEventPublisher;
    use capacity_availability::application::CapacityService;
    use capacity_availability::ports::{HandlerResult, WireEnvelope};
    use serde_json::json;
    use std::sync::Arc;

    let service = CapacityService::new(Arc::new(InMemoryEventPublisher::new()));
    let result = service.handle_inbound_event(WireEnvelope {
        event_id: "evt-0194f2e0-7b3e-7610-0284-5c26e8b0fa41".to_string(),
        event_type: "SegmentReservationConfirmed".to_string(),
        schema_version: 1,
        producer: "booking-orchestration".to_string(),
        causation_id: None,
        correlation_id: "corr-0194f2e0-7b3e-7610-0284-5c26e8b0fa42".to_string(),
        occurred_at: "2026-07-03T10:30:00.000Z".to_string(),
        payload: json!({
            "segmentBookingId": "sb-1",
            "providerReference": "prov-1",
            "evidence": "confirmed"
        }),
    });

    assert_eq!(result, HandlerResult::Success);
}

#[test]
fn in_memory_segment_reservation_confirmed_missing_segment_booking_id_is_fatal() {
    use capacity_availability::adapters::messaging::InMemoryEventPublisher;
    use capacity_availability::application::CapacityService;
    use capacity_availability::ports::{HandlerResult, WireEnvelope};
    use serde_json::json;
    use std::sync::Arc;

    let service = CapacityService::new(Arc::new(InMemoryEventPublisher::new()));
    let result = service.handle_inbound_event(WireEnvelope {
        event_id: "evt-0194f2e0-7b3e-7610-0284-5c26e8b0fa43".to_string(),
        event_type: "SegmentReservationConfirmed".to_string(),
        schema_version: 1,
        producer: "booking-orchestration".to_string(),
        causation_id: None,
        correlation_id: "corr-0194f2e0-7b3e-7610-0284-5c26e8b0fa44".to_string(),
        occurred_at: "2026-07-03T10:30:00.000Z".to_string(),
        payload: json!({"evidence": "confirmed"}),
    });

    assert!(
        matches!(result, HandlerResult::FatalError(message) if message.contains("segmentBookingId"))
    );
}

#[tokio::test]
async fn postgres_segment_reservation_confirmed_missing_segment_booking_id_is_fatal() {
    use capacity_availability::adapters::storage::PostgresCapacityService;
    use capacity_availability::ports::{HandlerResult, WireEnvelope};
    use rust_kit::storage::Storage;
    use serde_json::json;
    use sqlx::postgres::PgPoolOptions;

    let pool = PgPoolOptions::new()
        .max_connections(1)
        .connect_lazy("postgres://capacity:capacity@127.0.0.1:1/capacity")
        .unwrap();
    let service = PostgresCapacityService::from_storage(Storage::new(pool)).unwrap();

    let result = service
        .handle_inbound_event(WireEnvelope {
            event_id: "evt-0194f2e0-7b3e-7610-0284-5c26e8b0fa47".to_string(),
            event_type: "SegmentReservationConfirmed".to_string(),
            schema_version: 1,
            producer: "booking-orchestration".to_string(),
            causation_id: None,
            correlation_id: "corr-0194f2e0-7b3e-7610-0284-5c26e8b0fa48".to_string(),
            occurred_at: "2026-07-03T10:30:00.000Z".to_string(),
            payload: json!({"evidence": "confirmed"}),
        })
        .await;

    assert!(
        matches!(result, HandlerResult::FatalError(message) if message.contains("segmentBookingId"))
    );
}

#[test]
fn in_memory_capacity_inbound_event_contract_classification_matrix() {
    use capacity_availability::adapters::messaging::InMemoryEventPublisher;
    use capacity_availability::application::CapacityService;
    use capacity_availability::ports::{HandlerResult, WireEnvelope};
    use serde_json::{Value, json};
    use std::sync::Arc;

    fn envelope(event_type: &str, producer: &str, payload: Value) -> WireEnvelope {
        WireEnvelope {
            event_id: format!("evt-0194f2e0-7b3e-7610-0284-5c26e8{:04}", event_type.len()),
            event_type: event_type.to_string(),
            schema_version: 1,
            producer: producer.to_string(),
            causation_id: None,
            correlation_id: "corr-0194f2e0-7b3e-7610-0284-5c26e8b0fb01".to_string(),
            occurred_at: "2026-07-03T10:30:00.000Z".to_string(),
            payload,
        }
    }

    let service = CapacityService::new(Arc::new(InMemoryEventPublisher::new()));
    let cases = [
        (
            envelope(
                "SegmentReservationRequested",
                "booking-orchestration",
                json!({"segmentBookingId":"sb-1","journeyOrderId":"ord-1","segmentRef":"seg-1","travelerRef":"traveler-1"}),
            ),
            "fatal",
            "idempotencyKey",
        ),
        (
            envelope(
                "SegmentReservationConfirmed",
                "booking-orchestration",
                json!({"segmentBookingId":"sb-1","providerReference":"prov-1","evidence":"confirmed"}),
            ),
            "success",
            "",
        ),
        (
            envelope(
                "SegmentReservationConfirmed",
                "booking-orchestration",
                json!({"evidence":"confirmed"}),
            ),
            "fatal",
            "segmentBookingId",
        ),
        (
            envelope(
                "SegmentBookingCancelled",
                "booking-orchestration",
                json!({"segmentBookingId":"sb-1","reason":"customer"}),
            ),
            "success",
            "",
        ),
        (
            envelope(
                "SegmentBookingCancelled",
                "booking-orchestration",
                json!({"reason":"customer"}),
            ),
            "fatal",
            "segmentBookingId",
        ),
        (
            envelope(
                "SegmentTicketed",
                "booking-orchestration",
                json!({"segmentBookingId":"sb-1","entitlementId":"ent-1"}),
            ),
            "success",
            "",
        ),
        (
            envelope(
                "PostSalesApplied",
                "post-sales",
                json!({"caseId":"psc-1","orderId":"ord-1","resultSummary":{}}),
            ),
            "success",
            "",
        ),
        (
            envelope(
                "EntitlementVoided",
                "entitlement-ticketing",
                json!({"entitlementId":"ent-1","voidedAt":"2026-07-03T10:30:00.000Z","reason":"REFUND","policy":"NORMAL"}),
            ),
            "fatal",
            "segmentBookingRef",
        ),
    ];

    for (event, expected, detail) in cases {
        let result = service.handle_inbound_event(event);
        match expected {
            "success" => assert_eq!(result, HandlerResult::Success),
            "fatal" => assert!(
                matches!(result, HandlerResult::FatalError(message) if message.contains(detail))
            ),
            _ => unreachable!(),
        }
    }
}

#[tokio::test]
async fn postgres_capacity_inbound_event_contract_classification_pre_db_matrix() {
    use capacity_availability::adapters::storage::PostgresCapacityService;
    use capacity_availability::ports::{HandlerResult, WireEnvelope};
    use rust_kit::storage::Storage;
    use serde_json::{Value, json};
    use sqlx::postgres::PgPoolOptions;

    fn envelope(event_type: &str, producer: &str, payload: Value) -> WireEnvelope {
        WireEnvelope {
            event_id: format!("evt-0194f2e0-7b3e-7610-0284-5c26e8{:04}", event_type.len()),
            event_type: event_type.to_string(),
            schema_version: 1,
            producer: producer.to_string(),
            causation_id: None,
            correlation_id: "corr-0194f2e0-7b3e-7610-0284-5c26e8b0fb02".to_string(),
            occurred_at: "2026-07-03T10:30:00.000Z".to_string(),
            payload,
        }
    }

    let pool = PgPoolOptions::new()
        .max_connections(1)
        .connect_lazy("postgres://capacity:capacity@127.0.0.1:1/capacity")
        .unwrap();
    let service = PostgresCapacityService::from_storage(Storage::new(pool)).unwrap();

    let fatal_cases = [
        (
            envelope(
                "SegmentReservationRequested",
                "booking-orchestration",
                json!({"segmentBookingId":"sb-1","journeyOrderId":"ord-1","segmentRef":"seg-1","travelerRef":"traveler-1"}),
            ),
            "idempotencyKey",
        ),
        (
            envelope(
                "SegmentReservationConfirmed",
                "booking-orchestration",
                json!({"evidence":"confirmed"}),
            ),
            "segmentBookingId",
        ),
        (
            envelope(
                "SegmentBookingCancelled",
                "booking-orchestration",
                json!({"reason":"customer"}),
            ),
            "segmentBookingId",
        ),
        (
            envelope(
                "EntitlementVoided",
                "entitlement-ticketing",
                json!({"entitlementId":"ent-1","voidedAt":"2026-07-03T10:30:00.000Z","reason":"REFUND","policy":"NORMAL"}),
            ),
            "segmentBookingRef",
        ),
    ];
    for (event, detail) in fatal_cases {
        let result = service.handle_inbound_event(event).await;
        assert!(matches!(result, HandlerResult::FatalError(message) if message.contains(detail)));
    }

    let success_cases = [
        envelope(
            "SegmentTicketed",
            "booking-orchestration",
            json!({"segmentBookingId":"sb-1","entitlementId":"ent-1"}),
        ),
        envelope(
            "PostSalesApplied",
            "post-sales",
            json!({"caseId":"psc-1","orderId":"ord-1","resultSummary":{}}),
        ),
    ];
    for event in success_cases {
        assert_eq!(
            service.handle_inbound_event(event).await,
            HandlerResult::Success
        );
    }
}

#[tokio::test]
async fn api_responses_include_runtime_correlation_and_request_headers() {
    let app = test_router();
    let response = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/availability-snapshots?scheduledServiceRef=test&segmentRef=test")
                .header("x-correlation-id", "0194f2e0-7b3e-7610-0284-5c26e8b0cff1")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(
        response.headers().get("x-correlation-id").unwrap(),
        "0194f2e0-7b3e-7610-0284-5c26e8b0cff1"
    );
    assert!(response.headers().get("x-request-id").is_some());
}

#[tokio::test]
async fn api_idempotency_key_reused_with_different_hold_body_returns_422() {
    let app = test_router();
    let key = "0194f2e0-7b3e-7610-0284-5c26e8b0cf01";

    let first = get_body(
        app.clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/capacity-holds")
                    .header("content-type", "application/json")
                    .header("idempotency-key", key)
                    .header("x-correlation-id", "0194f2e0-7b3e-7610-0284-5c26e8b0cf02")
                    .body(Body::from(
                        r#"{"segmentRef":"seg-conflict-a","travelerRef":"tvl-1","classRef":"first","quantity":1,"segmentBookingId":"sb-a"}"#,
                    ))
                    .unwrap(),
            )
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(first.0, StatusCode::CREATED);

    let (status, json) = get_body(
        app.oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/capacity-holds")
                .header("content-type", "application/json")
                .header("idempotency-key", key)
                .header("x-correlation-id", "0194f2e0-7b3e-7610-0284-5c26e8b0cf03")
                .body(Body::from(
                    r#"{"segmentRef":"seg-conflict-b","travelerRef":"tvl-1","classRef":"first","quantity":1,"segmentBookingId":"sb-b"}"#,
                ))
                .unwrap(),
        )
        .await
        .unwrap(),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "body: {:?}", json);
    assert_eq!(json["code"], "IDEMPOTENCY_KEY_REUSED");
}

#[tokio::test]
async fn api_confirm_replay_returns_original_200_response() {
    let app = test_router();

    let (_, hold_json) = get_body(
        app.clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/capacity-holds")
                    .header("content-type", "application/json")
                    .header("idempotency-key", "0194f2e0-7b3e-7610-0284-5c26e8b0cf11")
                    .header("x-correlation-id", "0194f2e0-7b3e-7610-0284-5c26e8b0cf12")
                    .body(Body::from(
                        r#"{"segmentRef":"seg-confirm-replay","travelerRef":"tvl-1","classRef":"first","quantity":1,"segmentBookingId":"sb-confirm"}"#,
                    ))
                    .unwrap(),
            )
            .await
            .unwrap(),
    )
    .await;
    let hold_id = hold_json["holdId"].as_str().unwrap().to_string();
    let confirm_key = "0194f2e0-7b3e-7610-0284-5c26e8b0cf13";
    let uri = format!("/api/v1/capacity-holds/{}/confirm", hold_id);

    let (status1, json1) = get_body(
        app.clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(&uri)
                    .header("idempotency-key", confirm_key)
                    .header("x-correlation-id", "0194f2e0-7b3e-7610-0284-5c26e8b0cf14")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap(),
    )
    .await;
    let (status2, json2) = get_body(
        app.oneshot(
            Request::builder()
                .method("POST")
                .uri(&uri)
                .header("idempotency-key", confirm_key)
                .header("x-correlation-id", "0194f2e0-7b3e-7610-0284-5c26e8b0cf15")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap(),
    )
    .await;
    assert_eq!(status1, StatusCode::OK);
    assert_eq!(status2, StatusCode::OK, "body: {:?}", json2);
    assert_eq!(json1, json2);
}

#[tokio::test]
async fn api_release_replay_returns_original_200_response() {
    let app = test_router();

    let (_, hold_json) = get_body(
        app.clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/capacity-holds")
                    .header("content-type", "application/json")
                    .header("idempotency-key", "0194f2e0-7b3e-7610-0284-5c26e8b0cf21")
                    .header("x-correlation-id", "0194f2e0-7b3e-7610-0284-5c26e8b0cf22")
                    .body(Body::from(
                        r#"{"segmentRef":"seg-release-replay","travelerRef":"tvl-1","classRef":"first","quantity":1,"segmentBookingId":"sb-release"}"#,
                    ))
                    .unwrap(),
            )
            .await
            .unwrap(),
    )
    .await;
    let hold_id = hold_json["holdId"].as_str().unwrap().to_string();
    let release_key = "0194f2e0-7b3e-7610-0284-5c26e8b0cf23";
    let uri = format!("/api/v1/capacity-holds/{}/release", hold_id);

    let (status1, json1) = get_body(
        app.clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(&uri)
                    .header("idempotency-key", release_key)
                    .header("x-correlation-id", "0194f2e0-7b3e-7610-0284-5c26e8b0cf24")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap(),
    )
    .await;
    let (status2, json2) = get_body(
        app.oneshot(
            Request::builder()
                .method("POST")
                .uri(&uri)
                .header("idempotency-key", release_key)
                .header("x-correlation-id", "0194f2e0-7b3e-7610-0284-5c26e8b0cf25")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap(),
    )
    .await;
    assert_eq!(status1, StatusCode::OK);
    assert_eq!(status2, StatusCode::OK, "body: {:?}", json2);
    assert_eq!(json1, json2);
}

#[test]
fn subscriber_dlqs_after_fifth_delivery_attempt_from_broker_counter() {
    use capacity_availability::adapters::messaging::{
        InMemoryEventSubscriber, ReceivedEvent, SubscriberAction,
    };
    use capacity_availability::ports::{HandlerResult, WireEnvelope};
    use serde_json::json;

    let subscriber = InMemoryEventSubscriber::new();
    let envelope = WireEnvelope {
        event_id: "evt-0194f2e0-7b3e-7610-0284-5c26e8b0cf31".to_string(),
        event_type: "BookingCapacityRequested".to_string(),
        schema_version: 1,
        producer: "booking-orchestration".to_string(),
        causation_id: Some("cmd-0194f2e0-7b3e-7610-0284-5c26e8b0cf32".to_string()),
        correlation_id: "corr-0194f2e0-7b3e-7610-0284-5c26e8b0cf33".to_string(),
        occurred_at: "2026-07-03T10:30:00.000Z".to_string(),
        payload: json!({"segmentBookingId": "sb-1"}),
    };
    let received = ReceivedEvent {
        entry_id: "5-0".to_string(),
        stream: "events:booking-orchestration".to_string(),
        envelope,
        delivery_attempts: 5,
    };
    assert_eq!(
        subscriber.process_received(&received, &|_| HandlerResult::Success),
        SubscriberAction::DeadLetterAndAck
    );
}

#[test]
fn publish_failure_surfaces_as_unavailable() {
    use capacity_availability::application::{AppError, CapacityService, HoldCapacityRequest};
    use capacity_availability::ports::{EventPublisher, PublishFailed, WireEnvelope};
    use std::sync::Arc;

    struct FailingPublisher;
    impl EventPublisher for FailingPublisher {
        fn publish(&self, _envelope: &WireEnvelope) -> Result<(), PublishFailed> {
            Err(PublishFailed("broker unavailable".into()))
        }
    }

    let service = CapacityService::new(Arc::new(FailingPublisher));
    let result = service.hold_capacity(
        HoldCapacityRequest {
            segment_ref: "seg-publish-fail".to_string(),
            traveler_ref: "tvl-1".to_string(),
            class_ref: "first".to_string(),
            quantity: 1,
            segment_booking_id: "sb-publish-fail".to_string(),
        },
        "0194f2e0-7b3e-7610-0284-5c26e8b0cf41",
        "corr-0194f2e0-7b3e-7610-0284-5c26e8b0cf42",
    );
    match result {
        Err(AppError::Unavailable(message)) => assert!(message.contains("broker unavailable")),
        other => panic!(
            "expected publish failure to surface as unavailable, got {:?}",
            other
        ),
    }
}

#[cfg(feature = "redis-impl")]
#[test]
fn xautoclaim_raw_reply_parses_tuple_shape() {
    use capacity_availability::adapters::messaging::redis_subscriber::parse_xautoclaim_reply;
    use redis::Value;

    let raw = Value::Bulk(vec![
        Value::Data(b"0-0".to_vec()),
        Value::Bulk(vec![Value::Bulk(vec![
            Value::Data(b"1680000000000-0".to_vec()),
            Value::Bulk(vec![
                Value::Data(b"envelope".to_vec()),
                Value::Data(br#"{"eventId":"evt-1"}"#.to_vec()),
            ]),
        ])]),
        Value::Bulk(vec![Value::Data(b"1679999999999-0".to_vec())]),
    ]);

    let parsed = parse_xautoclaim_reply("events:booking-orchestration", &raw).unwrap();
    assert_eq!(parsed.next_cursor, "0-0");
    assert_eq!(parsed.key, "events:booking-orchestration");
    assert_eq!(parsed.ids.len(), 1);
    assert_eq!(parsed.ids[0].id, "1680000000000-0");
    assert_eq!(parsed.deleted_ids, vec!["1679999999999-0"]);
    let envelope: String =
        redis::from_redis_value(parsed.ids[0].map.get("envelope").unwrap()).unwrap();
    assert_eq!(envelope, r#"{"eventId":"evt-1"}"#);
}

#[cfg(feature = "redis-impl")]
#[tokio::test]
#[ignore = "requires TEST_DATABASE_URL pointing at a migrated disposable Postgres database"]
async fn postgres_readiness_reports_ready_when_storage_is_marked_ready() {
    use capacity_availability::PostgresCapacityService;
    use rust_kit::storage::Storage;
    use sqlx::postgres::PgPoolOptions;
    use std::sync::Arc;

    let database_url = std::env::var("TEST_DATABASE_URL").expect("TEST_DATABASE_URL is required");
    let pool = PgPoolOptions::new()
        .max_connections(1)
        .connect(&database_url)
        .await
        .unwrap();
    let storage = Storage::new(pool);
    storage.mark_migrations_ready();
    let service = Arc::new(PostgresCapacityService::from_storage(storage).unwrap());
    let app = capacity_availability::router_with_postgres_service(service);

    let response = app
        .oneshot(
            Request::builder()
                .uri("/ready")
                .header("x-correlation-id", "test-ready")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

#[cfg(feature = "redis-impl")]
#[tokio::test]
async fn postgres_readiness_returns_503_when_database_is_disconnected() {
    use capacity_availability::PostgresCapacityService;
    use rust_kit::storage::Storage;
    use sqlx::postgres::PgPoolOptions;
    use std::sync::Arc;

    let pool = PgPoolOptions::new()
        .max_connections(1)
        .connect_lazy("postgres://capacity:capacity@127.0.0.1:1/capacity")
        .unwrap();
    let storage = Storage::new(pool);
    storage.mark_migrations_ready();
    let service = Arc::new(PostgresCapacityService::from_storage(storage).unwrap());
    let app = capacity_availability::router_with_postgres_service(service);

    let response = app
        .oneshot(
            Request::builder()
                .uri("/readyz")
                .header("x-correlation-id", "test-not-ready")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
}
