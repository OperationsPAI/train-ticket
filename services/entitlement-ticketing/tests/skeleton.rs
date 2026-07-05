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
                .header("idempotency-key", "018f2e07b3e761002845c26e8b0caaa")
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
                .header("idempotency-key", "018f2e07b3e761002845c26e8b0caaa")
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
                .header("idempotency-key", "018f2e07b3e761002845c26e8b0cbbb")
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
