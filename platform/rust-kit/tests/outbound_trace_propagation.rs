//! Outbound trace propagation over a real HTTP connection, in the style of
//! post-sales' `OutboundTracePropagationTest`: a local axum server records the
//! headers it receives from a real `reqwest` request, and the assertion is that
//! the request carried the active span's W3C traceparent.
//!
//! The unit tests in `rust_kit::outbound` cover the header-construction rules;
//! this test covers the part that can only break end to end -- that a
//! `reqwest::RequestBuilder` routed through the kit actually puts the header on
//! the wire.

//! `reqwest::RequestBuilder` only implements the kit's `OutboundRequest` trait
//! under the `reqwest-client` feature, so the whole test compiles away without
//! it rather than failing to build.
#![cfg(feature = "reqwest-client")]

use std::sync::{Arc, Mutex};

use axum::{Router, extract::State, http::HeaderMap, routing::post};
use opentelemetry::Context as OTelContext;
use opentelemetry::global;
use opentelemetry::trace::{TraceContextExt, Tracer};
use opentelemetry_sdk::trace::InMemorySpanExporterBuilder;
use rust_kit::outbound::inject_trace_context_from;

type Captured = Arc<Mutex<Option<HeaderMap>>>;

async fn record(State(captured): State<Captured>, headers: HeaderMap) -> &'static str {
    *captured.lock().expect("captured lock poisoned") = Some(headers);
    "{}"
}

#[tokio::test]
async fn reqwest_request_carries_active_span_traceparent() {
    unsafe {
        std::env::set_var("OTEL_TRACES_EXPORTER", "otlp");
    }
    let exporter = InMemorySpanExporterBuilder::new().build();
    let guard = rust_kit::otel::init_with_exporter("rust-kit-test", exporter);

    let captured: Captured = Arc::new(Mutex::new(None));
    let app = Router::new()
        .route("/api/v1/internal/seat-allocations", post(record))
        .with_state(Arc::clone(&captured));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind stub server");
    let address = listener.local_addr().expect("stub server address");
    let server = tokio::spawn(async move {
        axum::serve(listener, app).await.expect("stub server");
    });

    // The context is threaded explicitly rather than read from the ambient
    // current context: shared_kernel's runtime middleware starts the server span
    // without attaching it, so a handler must pass the context it holds.
    let span = global::tracer("rust-kit-test").start("outbound");
    let context = OTelContext::current_with_span(span);
    let span_context = context.span().span_context().clone();

    let request = reqwest::Client::new()
        .post(format!("http://{address}/api/v1/internal/seat-allocations"))
        .header("Idempotency-Key", "idem-1")
        .header("X-Correlation-Id", "corr-1")
        .header("Content-Type", "application/json")
        .body(r#"{"a":1}"#);
    let response = inject_trace_context_from(request, &context)
        .send()
        .await
        .expect("stub server responds");
    assert!(response.status().is_success());

    let headers = captured
        .lock()
        .expect("captured lock poisoned")
        .clone()
        .expect("stub server received a request");
    let traceparent = headers
        .get("traceparent")
        .expect("outbound request must carry a traceparent")
        .to_str()
        .expect("traceparent is ascii");
    let parts: Vec<&str> = traceparent.split('-').collect();
    assert_eq!(parts.len(), 4, "{traceparent}");
    assert_eq!(parts[1], span_context.trace_id().to_string());
    // The traceparent must name this span: the callee parents its server span to
    // the span id in the header.
    assert_eq!(parts[2], span_context.span_id().to_string());
    // The headers the call site already set must be untouched.
    assert_eq!(headers.get("idempotency-key").unwrap(), "idem-1");
    assert_eq!(headers.get("x-correlation-id").unwrap(), "corr-1");

    server.abort();
    guard.shutdown().expect("shutdown otel");
}
