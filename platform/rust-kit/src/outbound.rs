//! Outbound HTTP trace-context propagation for Rust services.
//!
//! The asynchronous path already carries trace context: [`crate::messaging`]
//! injects `traceparent` into every event envelope. The synchronous path needs
//! the same thing on outbound HTTP requests, or the callee opens a fresh trace
//! and one user request is recorded as several disconnected ones.
//!
//! Propagation is installed once, here, rather than hand-written at each call
//! site. A call site passes its request builder through
//! [`inject_trace_context`] and the kit decides everything that can go wrong:
//! which propagator to use, whether the active span context is valid, which
//! header names to set, and what to do when tracing is off.
//!
//! # Why the context must be threaded explicitly
//!
//! `shared_kernel`'s runtime middleware starts a server span but does **not**
//! attach it to the ambient [`opentelemetry::Context`]. A handler therefore sees
//! an invalid span context from `Context::current()` unless it establishes one
//! itself -- typically by wrapping its body with
//! `FutureExt::with_context(shared_kernel::extract_trace_context(&headers))`.
//! [`inject_trace_context`] reads `Context::current()` and so benefits from that
//! wrapping automatically; [`inject_trace_context_from`] takes the context
//! explicitly for callers that hold it directly.

use opentelemetry::Context as OTelContext;
use opentelemetry::propagation::{Injector, TextMapPropagator};
use opentelemetry::trace::TraceContextExt;
use opentelemetry_sdk::propagation::TraceContextPropagator;

use crate::otel::tracing_enabled;

/// The W3C trace-context headers, the only ones this module ever sets.
pub const TRACEPARENT_HEADER: &str = "traceparent";
pub const TRACESTATE_HEADER: &str = "tracestate";

/// An outbound request that can carry trace-context headers.
///
/// Implemented for the HTTP client a service uses -- typically
/// `reqwest::RequestBuilder`, whose `header` method already has this exact
/// shape. Keeping the trait here rather than depending on a specific client
/// lets every Rust service share one propagation implementation without the kit
/// dictating an HTTP library.
pub trait OutboundRequest: Sized {
    /// Set `name` to `value`, replacing any existing value for that name.
    ///
    /// Replacement matters: a `traceparent` copied from an inbound request would
    /// parent the callee to the wrong span, so the fresh value must win.
    fn set_trace_header(self, name: &str, value: &str) -> Self;
}

/// Trace-context headers ready to be attached to an outbound request.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TraceContextHeaders {
    pub traceparent: Option<String>,
    pub tracestate: Option<String>,
}

impl TraceContextHeaders {
    /// True when there is nothing to propagate: tracing is off, or no valid span
    /// is active.
    pub fn is_empty(&self) -> bool {
        self.traceparent.is_none()
    }

    /// The headers as name/value pairs, omitting absent ones.
    pub fn iter(&self) -> impl Iterator<Item = (&'static str, &str)> {
        self.traceparent
            .as_deref()
            .map(|value| (TRACEPARENT_HEADER, value))
            .into_iter()
            .chain(
                self.tracestate
                    .as_deref()
                    .map(|value| (TRACESTATE_HEADER, value)),
            )
    }
}

impl Injector for TraceContextHeaders {
    fn set(&mut self, key: &str, value: String) {
        match key.to_ascii_lowercase().as_str() {
            TRACEPARENT_HEADER => self.traceparent = Some(value),
            // An empty tracestate is not a valid header value and carries no
            // information; omit it rather than sending a blank.
            TRACESTATE_HEADER if !value.trim().is_empty() => self.tracestate = Some(value),
            _ => {}
        }
    }
}

/// The W3C trace-context headers for the ambient [`OTelContext`].
pub fn outbound_trace_headers() -> TraceContextHeaders {
    outbound_trace_headers_from(&OTelContext::current())
}

/// The W3C trace-context headers for an explicitly supplied context.
///
/// Returns empty headers when tracing is disabled or the context holds no valid
/// span, so an outbound request never carries a `traceparent` pointing at a
/// span that does not exist.
pub fn outbound_trace_headers_from(context: &OTelContext) -> TraceContextHeaders {
    if !tracing_enabled() {
        return TraceContextHeaders::default();
    }
    if !context.span().span_context().is_valid() {
        return TraceContextHeaders::default();
    }
    let mut headers = TraceContextHeaders::default();
    // The W3C propagator explicitly, matching every callee extractor in the
    // system (all five kits read `traceparent`/`tracestate`) and the envelope
    // injection in `crate::messaging`.
    TraceContextPropagator::new().inject_context(context, &mut headers);
    headers
}

/// Attach the ambient context's W3C trace context to an outbound request.
///
/// A no-op when tracing is off or no valid span is active, so the request is
/// byte-identical to an uninstrumented one in those cases.
pub fn inject_trace_context<R: OutboundRequest>(request: R) -> R {
    inject_trace_context_from(request, &OTelContext::current())
}

/// Attach an explicitly supplied context's W3C trace context to an outbound
/// request.
///
/// Prefer this inside `shared_kernel` handlers when the context is held
/// directly: the runtime middleware does not make the server span ambient.
pub fn inject_trace_context_from<R: OutboundRequest>(request: R, context: &OTelContext) -> R {
    let headers = outbound_trace_headers_from(context);
    headers
        .iter()
        .fold(request, |request, (name, value)| {
            request.set_trace_header(name, value)
        })
}

/// `reqwest::RequestBuilder` is the outbound client every Rust service uses;
/// `header` already replaces on repeat, which is the semantics a fresh trace
/// context needs.
#[cfg(feature = "reqwest-client")]
impl OutboundRequest for reqwest::RequestBuilder {
    fn set_trace_header(self, name: &str, value: &str) -> Self {
        self.header(name, value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use opentelemetry::global;
    use opentelemetry::trace::Tracer;
    use opentelemetry_sdk::trace::InMemorySpanExporterBuilder;
    use std::collections::HashMap;

    /// Records what a real client would have put on the wire.
    #[derive(Debug, Default)]
    struct RecordingRequest {
        headers: HashMap<String, String>,
    }

    impl OutboundRequest for RecordingRequest {
        fn set_trace_header(mut self, name: &str, value: &str) -> Self {
            self.headers
                .insert(name.to_ascii_lowercase(), value.to_string());
            self
        }
    }

    fn trace_id_of(traceparent: &str) -> &str {
        traceparent.split('-').nth(1).expect("traceparent trace id")
    }

    fn span_id_of(traceparent: &str) -> &str {
        traceparent.split('-').nth(2).expect("traceparent span id")
    }

    #[test]
    fn outbound_request_carries_active_span_traceparent() {
        let _serial = crate::otel::test_serial();
        unsafe {
            std::env::set_var("OTEL_TRACES_EXPORTER", "otlp");
        }
        let exporter = InMemorySpanExporterBuilder::new().build();
        let guard = crate::otel::init_with_exporter("rust-kit-test", exporter);
        let tracer = global::tracer("rust-kit-test");
        let span = tracer.start("outbound");
        let context = OTelContext::current_with_span(span);

        // Alongside the trace headers, the headers a call site already sets must
        // survive: propagation adds, it never replaces unrelated headers.
        let request = RecordingRequest::default()
            .set_trace_header("idempotency-key", "idem-1")
            .set_trace_header("x-correlation-id", "corr-1");
        let request = inject_trace_context_from(request, &context);

        let traceparent = request
            .headers
            .get(TRACEPARENT_HEADER)
            .expect("outbound request must carry a traceparent");
        let span_context = context.span().span_context().clone();
        assert_eq!(trace_id_of(traceparent), span_context.trace_id().to_string());
        assert_eq!(span_id_of(traceparent), span_context.span_id().to_string());
        assert_eq!(request.headers.get("idempotency-key").unwrap(), "idem-1");
        assert_eq!(request.headers.get("x-correlation-id").unwrap(), "corr-1");

        guard.shutdown().expect("shutdown otel");
    }

    #[test]
    fn ambient_context_is_used_when_attached() {
        let _serial = crate::otel::test_serial();
        unsafe {
            std::env::set_var("OTEL_TRACES_EXPORTER", "otlp");
        }
        let exporter = InMemorySpanExporterBuilder::new().build();
        let guard = crate::otel::init_with_exporter("rust-kit-test", exporter);
        let tracer = global::tracer("rust-kit-test");
        let span = tracer.start("ambient");
        let expected = OTelContext::current_with_span(span);
        let expected_trace_id = expected.span().span_context().trace_id().to_string();
        let attached = expected.attach();

        let request = inject_trace_context(RecordingRequest::default());

        let traceparent = request
            .headers
            .get(TRACEPARENT_HEADER)
            .expect("ambient context must be propagated");
        assert_eq!(trace_id_of(traceparent), expected_trace_id);

        drop(attached);
        guard.shutdown().expect("shutdown otel");
    }

    #[test]
    fn no_traceparent_without_an_active_span() {
        let _serial = crate::otel::test_serial();
        unsafe {
            std::env::set_var("OTEL_TRACES_EXPORTER", "otlp");
        }
        // A bare context holds no span, so there is nothing valid to reference.
        let request = inject_trace_context_from(RecordingRequest::default(), &OTelContext::new());
        assert!(request.headers.is_empty());
        assert!(outbound_trace_headers_from(&OTelContext::new()).is_empty());
    }

    #[test]
    fn no_traceparent_when_tracing_is_disabled() {
        let _serial = crate::otel::test_serial();
        unsafe {
            std::env::remove_var("OTEL_TRACES_EXPORTER");
        }
        let exporter = InMemorySpanExporterBuilder::new().build();
        let guard = crate::otel::init_with_exporter("rust-kit-test", exporter);
        let tracer = global::tracer("rust-kit-test");
        let span = tracer.start("disabled");
        let context = OTelContext::current_with_span(span);

        let request = inject_trace_context_from(RecordingRequest::default(), &context);
        assert!(
            request.headers.is_empty(),
            "tracing disabled must send no trace headers"
        );

        guard.shutdown().expect("shutdown otel");
    }

    #[test]
    fn a_stale_traceparent_is_replaced_not_duplicated() {
        let _serial = crate::otel::test_serial();
        unsafe {
            std::env::set_var("OTEL_TRACES_EXPORTER", "otlp");
        }
        let exporter = InMemorySpanExporterBuilder::new().build();
        let guard = crate::otel::init_with_exporter("rust-kit-test", exporter);
        let tracer = global::tracer("rust-kit-test");
        let span = tracer.start("stale");
        let context = OTelContext::current_with_span(span);
        let expected_trace_id = context.span().span_context().trace_id().to_string();

        let request = RecordingRequest::default().set_trace_header(
            TRACEPARENT_HEADER,
            "00-11111111111111111111111111111111-2222222222222222-01",
        );
        let request = inject_trace_context_from(request, &context);

        let traceparent = request.headers.get(TRACEPARENT_HEADER).expect("traceparent");
        assert_eq!(trace_id_of(traceparent), expected_trace_id);

        guard.shutdown().expect("shutdown otel");
    }

    #[test]
    fn traceparent_is_well_formed_w3c() {        let _serial = crate::otel::test_serial();
        unsafe {
            std::env::set_var("OTEL_TRACES_EXPORTER", "otlp");
        }
        let exporter = InMemorySpanExporterBuilder::new().build();
        let guard = crate::otel::init_with_exporter("rust-kit-test", exporter);
        let tracer = global::tracer("rust-kit-test");
        let span = tracer.start("shape");
        let context = OTelContext::current_with_span(span);

        let headers = outbound_trace_headers_from(&context);
        let traceparent = headers.traceparent.expect("traceparent");
        assert!(traceparent.starts_with("00-"), "{traceparent}");
        assert_eq!(traceparent.len(), 55, "{traceparent}");
        // No tracestate was set upstream, so none must be sent.
        assert!(headers.tracestate.is_none());

        guard.shutdown().expect("shutdown otel");
    }
}
