use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use serde_json::Value;
use tower::ServiceExt;

fn test_app() -> (axum::Router, std::sync::Arc<trip_planning::AppState>) {
    trip_planning::router_for_test()
}

#[tokio::test]
async fn healthz_returns_ok() {
    let (app, _state) = test_app();
    let response = app
        .oneshot(
            Request::builder()
                .uri("/healthz")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let json: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["status"], "ok");
}

#[tokio::test]
async fn search_returns_valid_response() {
    let (app, _state) = test_app();
    let payload = serde_json::json!({
        "originRef": "station-A",
        "destinationRef": "station-B",
        "departureDate": "2026-07-10",
        "travelerRefs": ["traveler-1"],
        "channel": "web"
    });
    let response = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/itineraries/search")
                .header("content-type", "application/json")
                .header("x-request-id", "req-test-1")
                .header("x-correlation-id", "corr-test-1")
                .body(Body::from(serde_json::to_vec(&payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let json: Value = serde_json::from_slice(&body).unwrap();

    // Contract fields are present
    assert!(json["intentRef"].is_string());
    assert!(json["itineraries"].is_array());
    assert!(json["planningSnapshotRefs"].is_array());

    let itineraries = json["itineraries"].as_array().unwrap();
    assert!(!itineraries.is_empty());

    let first = &itineraries[0];
    assert!(first["itineraryRef"].is_string());
    assert!(first["legs"].is_array());
    assert!(!first["legs"].as_array().unwrap().is_empty());

    let leg = &first["legs"][0];
    assert!(leg["servicePlanRef"].is_string());
    assert!(leg["serviceSegmentRef"].is_string());
    assert!(leg["originStopRef"].is_string());
    assert!(leg["destinationStopRef"].is_string());
    assert!(leg["departureTime"].is_string());
    assert!(leg["arrivalTime"].is_string());
    assert_eq!(leg["mode"], "train");

    // Price and availability hints
    assert!(first["priceHint"].is_object());
    let price_hint = &first["priceHint"];
    assert!(price_hint["currency"].is_string());
    assert!(price_hint["minorUnits"].is_number());

    assert!(first["availabilityHint"].is_object());
    let avail_hint = &first["availabilityHint"];
    assert!(avail_hint["status"].is_string());
    assert!(avail_hint["confidence"].is_number());
}

#[tokio::test]
async fn search_with_invalid_payload_returns_400() {
    let (app, _state) = test_app();
    let payload = serde_json::json!({
        "originRef": "",
        "destinationRef": "station-B",
        "departureDate": "2026-07-10",
        "travelerRefs": ["traveler-1"],
        "channel": "web"
    });
    let response = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/itineraries/search")
                .header("content-type", "application/json")
                .header("x-request-id", "req-test-2")
                .header("x-correlation-id", "corr-test-2")
                .body(Body::from(serde_json::to_vec(&payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let json: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["code"], "VALIDATION_FAILED");
}

#[tokio::test]
async fn get_itinerary_not_found_returns_404() {
    let (app, _state) = test_app();
    let response = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/itineraries/itin_nonexistent")
                .header("x-request-id", "req-test-3")
                .header("x-correlation-id", "corr-test-3")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let json: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["code"], "NOT_FOUND");
}

#[tokio::test]
async fn search_then_get_returns_saved_itinerary() {
    let (app, state) = test_app();

    // First, search to populate the snapshot
    let payload = serde_json::json!({
        "originRef": "station-X",
        "destinationRef": "station-Y",
        "departureDate": "2026-08-01",
        "travelerRefs": ["traveler-1"],
        "channel": "mobile"
    });
    let response = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/itineraries/search")
                .header("content-type", "application/json")
                .header("x-request-id", "req-test-4")
                .header("x-correlation-id", "corr-test-4")
                .body(Body::from(serde_json::to_vec(&payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let search_result: Value = serde_json::from_slice(&body).unwrap();
    let itinerary_ref = search_result["itineraries"][0]["itineraryRef"]
        .as_str()
        .unwrap()
        .to_string();

    // Now GET that itinerary
    let (get_app, _) = (trip_planning::router_with_state(state.clone()), state);
    let response = get_app
        .oneshot(
            Request::builder()
                .uri(format!("/api/v1/itineraries/{}", itinerary_ref))
                .header("x-request-id", "req-test-5")
                .header("x-correlation-id", "corr-test-5")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let itin: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(itin["itineraryRef"], itinerary_ref);
}

#[tokio::test]
async fn search_with_index_data() {
    let (app, state) = test_app();

    // Populate the index with a segment via event
    let event_payload = serde_json::json!({
        "segmentRef": "seg-G100",
        "originStopRef": "SHA",
        "destinationStopRef": "NKG",
        "departureTime": "2026-07-15T08:00:00Z",
        "arrivalTime": "2026-07-15T09:30:00Z",
        "scheduledServiceRef": "sp-G100"
    });
    state
        .plan_index
        .apply_event("ServiceSegmentCreated", &event_payload);

    let payload = serde_json::json!({
        "originRef": "SHA",
        "destinationRef": "NKG",
        "departureDate": "2026-07-15",
        "travelerRefs": ["traveler-1"],
        "channel": "web"
    });
    let response = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/itineraries/search")
                .header("content-type", "application/json")
                .header("x-request-id", "req-test-6")
                .header("x-correlation-id", "corr-test-6")
                .body(Body::from(serde_json::to_vec(&payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let json: Value = serde_json::from_slice(&body).unwrap();
    let itineraries = json["itineraries"].as_array().unwrap();
    assert_eq!(itineraries.len(), 1);
    assert_eq!(itineraries[0]["legs"][0]["serviceSegmentRef"], "seg-G100");
    assert_eq!(itineraries[0]["legs"][0]["originStopRef"], "SHA");
    assert_eq!(itineraries[0]["legs"][0]["destinationStopRef"], "NKG");
}
