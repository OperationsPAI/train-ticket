use entitlement_ticketing::{health, profile, router};

#[test]
fn profile_exports_req_013_contract_metadata() {
    let profile = profile();
    assert_eq!(profile.service_id, "entitlement-ticketing");
    assert_eq!(profile.domain, "Entitlement & Ticketing");
    assert_eq!(profile.phase, "phase-1-domain-foundation");
    assert_eq!(profile.work_packages, &["REQ-013"]);
    assert!(
        profile
            .owns
            .iter()
            .any(|entry| entry.contains("issuance preconditions"))
    );
    assert_eq!(health(), "ok");
    let _router = router();
}

#[tokio::test]
async fn http_entitlement_endpoints_happy_path_and_idempotency() {
    use axum::body::{Body, to_bytes};
    use axum::http::{Method, Request, StatusCode};
    use serde_json::{Value, json};
    use tower::ServiceExt;

    let app = router();
    let issue_body = json!({
        "segmentBookingId": "sb-0194f2e0-7b3e-7610-0284-5c26e8b0c111",
        "journeyOrderId": "ord-0194f2e0-7b3e-7610-0284-5c26e8b0c222",
        "travelerRef": "tvl-0194f2e0-7b3e-7610-0284-5c26e8b0c333",
        "segmentRef": "seg-0194f2e0-7b3e-7610-0284-5c26e8b0c444",
        "issuePurpose": "INITIAL"
    });
    let issue_response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(Method::POST)
                .uri("/api/v1/entitlements")
                .header("content-type", "application/json")
                .header("idempotency-key", "018f2e07-b3e7-7100-8284-5c26e8b0caaa")
                .header("x-correlation-id", "corrtest")
                .body(Body::from(issue_body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(issue_response.status(), StatusCode::CREATED);
    assert_eq!(issue_response.headers()["x-correlation-id"], "corrtest");
    let issue_json: Value = serde_json::from_slice(
        &to_bytes(issue_response.into_body(), usize::MAX)
            .await
            .unwrap(),
    )
    .unwrap();
    assert_eq!(issue_json["status"], "ISSUED");
    assert_eq!(issue_json["credentialType"], "E_TICKET");
    let entitlement_id = issue_json["entitlementId"].as_str().unwrap().to_string();

    let replay_response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(Method::POST)
                .uri("/api/v1/entitlements")
                .header("content-type", "application/json")
                .header("idempotency-key", "018f2e07-b3e7-7100-8284-5c26e8b0caaa")
                .body(Body::from(issue_body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(replay_response.status(), StatusCode::CREATED);
    let replay_json: Value = serde_json::from_slice(
        &to_bytes(replay_response.into_body(), usize::MAX)
            .await
            .unwrap(),
    )
    .unwrap();
    assert_eq!(replay_json, issue_json);

    assert_eq!(
        app.clone()
            .oneshot(
                Request::builder()
                    .uri(format!("/api/v1/entitlements/{entitlement_id}"))
                    .body(Body::empty())
                    .unwrap()
            )
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    let list_response = app.clone().oneshot(Request::builder().uri("/api/v1/entitlements?journeyOrderId=ord-0194f2e0-7b3e-7610-0284-5c26e8b0c222&limit=20&offset=0").body(Body::empty()).unwrap()).await.unwrap();
    assert_eq!(list_response.status(), StatusCode::OK);
    let list_json: Value = serde_json::from_slice(
        &to_bytes(list_response.into_body(), usize::MAX)
            .await
            .unwrap(),
    )
    .unwrap();
    assert_eq!(list_json["total"], 1);

    let void_response = app
        .oneshot(
            Request::builder()
                .method(Method::POST)
                .uri(format!("/api/v1/entitlements/{entitlement_id}/void"))
                .header("content-type", "application/json")
                .header("idempotency-key", "018f2e07-b3e7-7100-8284-5c26e8b0cbbb")
                .body(Body::from(
                    json!({"reason":"REFUND","policy":"NORMAL","businessCaseRef":"case-1"})
                        .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(void_response.status(), StatusCode::OK);
}

#[tokio::test]
async fn validation_failure_uses_canonical_error_shape() {
    use axum::body::{Body, to_bytes};
    use axum::http::{Method, Request, StatusCode};
    use serde_json::{Value, json};
    use tower::ServiceExt;

    let response = router()
        .oneshot(
            Request::builder()
                .method(Method::POST)
                .uri("/api/v1/entitlements")
                .header("content-type", "application/json")
                .header("x-correlation-id", "corrvalidation")
                .body(Body::from(
                    json!({
                        "segmentBookingId":"sb-1",
                        "journeyOrderId":"ord-1",
                        "travelerRef":"tvl-1",
                        "segmentRef":"seg-1",
                        "issuePurpose":"INITIAL"
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = response.status();
    let bytes = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    assert_eq!(
        status,
        StatusCode::BAD_REQUEST,
        "{}",
        String::from_utf8_lossy(&bytes)
    );
    let body: Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(body["code"], "VALIDATION_FAILED");
    assert_eq!(body["correlationId"], "corrvalidation");
    assert!(body.get("details").is_some());
}

#[tokio::test]
async fn in_memory_publisher_wraps_contract_envelope_and_dedup_handler_skips_duplicates() {
    use entitlement_ticketing::adapters::messaging::{
        DeduplicatingEventHandler, InMemoryEventPublisher,
    };
    use entitlement_ticketing::application::{EventEnvelope, EventPublisher};
    use serde_json::json;

    let publisher = InMemoryEventPublisher::default();
    let envelope = EventEnvelope::new(
        "EntitlementIssued",
        "2026-07-05T10:30:00.000Z",
        "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444",
        Some("cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c555"),
        "entitlement-ticketing",
        json!({"entitlementId":"ent-0194f2e0-7b3e-7610-0284-5c26e8b0c111"}),
    );
    publisher.publish(envelope.clone()).await.unwrap();
    let published = publisher.published();
    assert_eq!(published[0].producer, "entitlement-ticketing");
    assert!(published[0].event_id.starts_with("evt-"));
    assert_eq!(
        published[0].payload["entitlementId"],
        "ent-0194f2e0-7b3e-7610-0284-5c26e8b0c111"
    );

    let handler = DeduplicatingEventHandler::default();
    handler.handle(envelope.clone()).unwrap();
    handler.handle(envelope).unwrap();
    assert_eq!(handler.handled_count(), 1);
}

#[tokio::test]
async fn published_entitlement_events_use_contract_payload_shapes() {
    use entitlement_ticketing::adapters::messaging::InMemoryEventPublisher;
    use entitlement_ticketing::{
        EntitlementApi, InMemoryEntitlementService, IssueEntitlementRequest, IssuePurposeDto,
        VoidEntitlementRequest, VoidPolicyDto, VoidReasonDto,
    };
    use std::sync::Arc;

    let publisher = Arc::new(InMemoryEventPublisher::default());
    let service = InMemoryEntitlementService::new(publisher.clone());
    let issued = service
        .issue(
            IssueEntitlementRequest {
                segment_booking_id: "sb-0194f2e0-7b3e-7610-0284-5c26e8b0c111".to_string(),
                journey_order_id: "ord-0194f2e0-7b3e-7610-0284-5c26e8b0c222".to_string(),
                traveler_ref: "tvl-0194f2e0-7b3e-7610-0284-5c26e8b0c333".to_string(),
                segment_ref: "seg-0194f2e0-7b3e-7610-0284-5c26e8b0c444".to_string(),
                issue_purpose: IssuePurposeDto::Initial,
            },
            "018f2e07-b3e7-7100-8284-5c26e8b0d101".to_string(),
            "corr-event-payload".to_string(),
        )
        .await
        .unwrap();
    let issued_payload = &publisher.published()[0].payload;
    assert_eq!(issued_payload["entitlementId"], issued.entitlement_id);
    assert_eq!(
        issued_payload["segmentBookingId"],
        "sb-0194f2e0-7b3e-7610-0284-5c26e8b0c111"
    );
    assert_eq!(
        issued_payload["journeyOrderId"],
        "ord-0194f2e0-7b3e-7610-0284-5c26e8b0c222"
    );
    assert_eq!(
        issued_payload["travelerRef"],
        "tvl-0194f2e0-7b3e-7610-0284-5c26e8b0c333"
    );
    assert_eq!(
        issued_payload["segmentRef"],
        "seg-0194f2e0-7b3e-7610-0284-5c26e8b0c444"
    );
    assert_eq!(issued_payload["issuePurpose"], "INITIAL");
    assert_eq!(issued_payload["credentialType"], "E_TICKET");
    assert!(issued_payload.get("status").is_none());

    service
        .void(
            issued.entitlement_id.clone(),
            VoidEntitlementRequest {
                reason: VoidReasonDto::Refund,
                policy: VoidPolicyDto::Normal,
                business_case_ref: Some("case-event-payload".to_string()),
            },
            "018f2e07-b3e7-7100-8284-5c26e8b0d102".to_string(),
            "corr-event-payload".to_string(),
        )
        .await
        .unwrap();
    let voided_payload = &publisher.published()[1].payload;
    assert_eq!(voided_payload["entitlementId"], issued.entitlement_id);
    assert_eq!(
        voided_payload["segmentBookingId"],
        "sb-0194f2e0-7b3e-7610-0284-5c26e8b0c111"
    );
    assert!(voided_payload["voidedAt"].as_str().unwrap().ends_with('Z'));
    assert_eq!(voided_payload["reason"], "REFUND");
    assert_eq!(voided_payload["policy"], "NORMAL");
    assert_eq!(voided_payload["businessCaseRef"], "case-event-payload");
    assert!(voided_payload.get("status").is_none());
}

#[tokio::test]
async fn invalid_list_query_uses_canonical_error_shape() {
    use axum::body::{Body, to_bytes};
    use axum::http::{Request, StatusCode};
    use serde_json::Value;
    use tower::ServiceExt;

    let response = router()
        .oneshot(
            Request::builder()
                .uri("/api/v1/entitlements?limit=not-a-number")
                .header("x-correlation-id", "corrlistvalidation")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let body: Value =
        serde_json::from_slice(&to_bytes(response.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(body["code"], "VALIDATION_FAILED");
    assert_eq!(body["correlationId"], "corrlistvalidation");
}

#[tokio::test]
async fn invalid_idempotency_key_is_rejected() {
    use axum::body::Body;
    use axum::http::{Method, Request, StatusCode};
    use serde_json::json;
    use tower::ServiceExt;

    let response = router()
        .oneshot(
            Request::builder()
                .method(Method::POST)
                .uri("/api/v1/entitlements")
                .header("content-type", "application/json")
                .header("idempotency-key", "not-a-uuid-v7")
                .body(Body::from(
                    json!({
                        "segmentBookingId":"sb-1",
                        "journeyOrderId":"ord-1",
                        "travelerRef":"tvl-1",
                        "segmentRef":"seg-1",
                        "issuePurpose":"INITIAL"
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn domain_invariant_violation_surfaces_as_domain_rule_violation() {
    use axum::body::{Body, to_bytes};
    use axum::http::{Method, Request, StatusCode};
    use serde_json::{Value, json};
    use tower::ServiceExt;

    let app = router();
    let issue_response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(Method::POST)
                .uri("/api/v1/entitlements")
                .header("content-type", "application/json")
                .header("idempotency-key", "018f2e07-b3e7-7100-8284-5c26e8b0c101")
                .body(Body::from(
                    json!({
                        "segmentBookingId":"sb-domain-1",
                        "journeyOrderId":"ord-domain-1",
                        "travelerRef":"tvl-domain-1",
                        "segmentRef":"seg-domain-1",
                        "issuePurpose":"INITIAL"
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    let issued: Value = serde_json::from_slice(
        &to_bytes(issue_response.into_body(), usize::MAX)
            .await
            .unwrap(),
    )
    .unwrap();
    let entitlement_id = issued["entitlementId"].as_str().unwrap();

    let first_void = app
        .clone()
        .oneshot(
            Request::builder()
                .method(Method::POST)
                .uri(format!("/api/v1/entitlements/{entitlement_id}/void"))
                .header("content-type", "application/json")
                .header("idempotency-key", "018f2e07-b3e7-7100-8284-5c26e8b0c102")
                .body(Body::from(
                    json!({"reason":"REFUND","policy":"NORMAL","businessCaseRef":"case-domain"})
                        .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(first_void.status(), StatusCode::OK);

    let second_void = app
        .oneshot(
            Request::builder()
                .method(Method::POST)
                .uri(format!("/api/v1/entitlements/{entitlement_id}/void"))
                .header("content-type", "application/json")
                .header("idempotency-key", "018f2e07-b3e7-7100-8284-5c26e8b0c103")
                .body(Body::from(
                    json!({"reason":"REFUND","policy":"NORMAL","businessCaseRef":"case-domain-2"})
                        .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(second_void.status(), StatusCode::PRECONDITION_FAILED);
}
