//! End-to-end outbound trace propagation for the seat-assignment hop, in the
//! style of post-sales' `OutboundTracePropagationTest`.
//!
//! A real request goes into the router carrying a caller's `traceparent`, a real
//! stub server stands in for seat-assignment and records what it receives, and
//! the assertion is that the outbound request carried a `traceparent` in the
//! *caller's* trace.
//!
//! This is the regression that the ambient-context problem creates:
//! `shared_kernel`'s runtime middleware starts a server span but does not attach
//! it to the ambient `opentelemetry::Context`, so an injector that trusted
//! `Context::current()` would emit a brand-new trace id here and the purchase
//! journey would still split into disconnected traces at this hop. The trace-id
//! assertion below is what catches that.

use std::sync::{Arc, Mutex};

use axum::body::Body;
use axum::extract::State;
use axum::http::{HeaderMap, Method, Request, StatusCode};
use axum::routing::post;
use axum::{Json, Router};
use entitlement_ticketing::{InMemoryEntitlementService, router_with_state};
use serde_json::{Value, json};
use tower::ServiceExt;

const CALLER_TRACE_ID: &str = "4bf92f3577b34da6a3ce929d0e0e4736";
const CALLER_SPAN_ID: &str = "00f067aa0ba902b7";

type Captured = Arc<Mutex<Option<HeaderMap>>>;

async fn seat_allocation_stub(State(captured): State<Captured>, headers: HeaderMap) -> Json<Value> {
    *captured.lock().expect("captured lock poisoned") = Some(headers);
    Json(json!({
        "seatRef": {
            "seatAllocationId": "sa-0194f2e0-7b3e-7610-0284-5c26e8b0cbbb",
            "allocationType": "SEAT",
            "coachNo": "05",
            "seatNo": "12A",
            "displayLabel": "Coach 05 Seat 12A",
            "degraded": false
        }
    }))
}

#[tokio::test]
async fn outbound_seat_assignment_request_joins_the_caller_trace() {
    unsafe {
        std::env::set_var("OTEL_TRACES_EXPORTER", "otlp");
    }
    let exporter = opentelemetry_sdk::trace::InMemorySpanExporterBuilder::new().build();
    let otel_guard = rust_kit::otel::init_with_exporter("entitlement-ticketing", exporter);

    // Stub seat-assignment on a real socket: the outbound call is a real reqwest
    // request, so only a real listener exercises the header on the wire.
    let captured: Captured = Arc::new(Mutex::new(None));
    let stub = Router::new()
        .route(
            "/api/v1/internal/seat-allocations",
            post(seat_allocation_stub),
        )
        .with_state(Arc::clone(&captured));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind stub seat-assignment");
    let address = listener.local_addr().expect("stub address");
    let server = tokio::spawn(async move {
        axum::serve(listener, stub).await.expect("stub server");
    });
    unsafe {
        std::env::set_var("SEAT_ASSIGNMENT_BASE_URL", format!("http://{address}"));
    }

    // seatPreferences makes needs_seat_assignment() true, which is what triggers
    // the outbound call.
    let issue_body = json!({
        "segmentBookingId": "sb-0194f2e0-7b3e-7610-0284-5c26e8b0c111",
        "journeyOrderId": "ord-0194f2e0-7b3e-7610-0284-5c26e8b0c222",
        "travelerRef": "tvl-0194f2e0-7b3e-7610-0284-5c26e8b0c333",
        "segmentRef": "seg-0194f2e0-7b3e-7610-0284-5c26e8b0c444",
        "issuePurpose": "INITIAL",
        "seatPreferences": { "acceptStanding": false, "preferenceVersion": "v1" },
        "scheduledServiceRef": "svc-0194f2e0-7b3e-7610-0284-5c26e8b0c555",
        "serviceDate": "2026-09-08",
        "capacityHoldId": "hold-0194f2e0-7b3e-7610-0284-5c26e8b0c666",
        "capacityUnitRef": "cu-0194f2e0-7b3e-7610-0284-5c26e8b0c777",
        "interval": { "fromSeq": 1, "toSeq": 4 },
        "classRef": "SECOND",
        "expiresAt": "2026-09-08T10:00:00Z"
    });

    let app = router_with_state(Arc::new(InMemoryEntitlementService::default()));
    let response = app
        .oneshot(
            Request::builder()
                .method(Method::POST)
                .uri("/api/v1/entitlements")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "0194f2e0-7b3e-7610-0284-5c26e8b0c888")
                .header("X-Correlation-Id", "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c999")
                // The caller's trace context: everything the outbound hop emits
                // must land in this trace.
                .header(
                    "traceparent",
                    format!("00-{CALLER_TRACE_ID}-{CALLER_SPAN_ID}-01"),
                )
                .body(Body::from(issue_body.to_string()))
                .expect("build request"),
        )
        .await
        .expect("router responds");
    assert_eq!(response.status(), StatusCode::CREATED);

    let headers = captured
        .lock()
        .expect("captured lock poisoned")
        .clone()
        .expect("seat-assignment stub must have received a request");

    let traceparent = headers
        .get("traceparent")
        .expect("outbound seat-assignment request must carry a traceparent")
        .to_str()
        .expect("traceparent is ascii");
    let parts: Vec<&str> = traceparent.split('-').collect();
    assert_eq!(parts.len(), 4, "malformed traceparent {traceparent}");
    assert_eq!(parts[0], "00");
    // The whole point: the same trace as the caller, not a fresh root.
    assert_eq!(
        parts[1], CALLER_TRACE_ID,
        "outbound request must stay in the caller's trace, got {traceparent}"
    );
    // A distinct span within that trace -- the outbound CLIENT span, not the
    // caller's span echoed back.
    assert_ne!(parts[2], CALLER_SPAN_ID);
    assert_eq!(parts[2].len(), 16);
    assert_ne!(parts[2], "0000000000000000");

    // The headers this call site already set must be preserved exactly.
    // The outbound idempotency key is deliberately the contract-folded key
    // rather than the caller's public key, so assert it is present and non-empty
    // rather than pinning a value.
    assert!(
        !headers
            .get("idempotency-key")
            .expect("outbound request must still carry an Idempotency-Key")
            .is_empty()
    );
    assert_eq!(
        headers.get("x-correlation-id").expect("correlation id"),
        "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c999"
    );

    server.abort();
    otel_guard.shutdown().expect("shutdown otel");
    unsafe {
        std::env::remove_var("SEAT_ASSIGNMENT_BASE_URL");
    }
}
