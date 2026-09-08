//! Trace ids for log lines, so a log line can be joined to a trace in Jaeger.
//!
//! WHY THIS EXISTS
//! ---------------
//! The platform had full W3C trace propagation (`traceparent` across HTTP via
//! [`crate::outbound`], and on every event envelope via [`crate::messaging`]) and
//! structured logs, and no way to get from one to the other: nothing published a
//! trace id where the log formatter could see it, and no formatter printed one.
//! Diagnosing the zero-refund chain is what made this concrete -- five services,
//! each taking a silent early return, correlated only by matching wall-clock
//! timestamps by hand across five `kubectl logs` invocations.
//!
//! WHY THIS IS NOT `tracing-opentelemetry`
//! ---------------------------------------
//! In a `tracing` + `tracing-subscriber` service the answer is nearly free:
//! `tracing-opentelemetry` puts the span context on the tracing span and the
//! subscriber's formatter emits it. The Rust services here do not use `tracing`.
//! They use `log` + `env_logger` (see each service's `init_logging`), and neither
//! `tracing-subscriber` nor `tracing-opentelemetry` appears in any `Cargo.toml`
//! or `Cargo.lock` in this repository. Adding them would mean a new dependency
//! plus rewriting every `log::` call site, so the ids are read from the
//! OpenTelemetry [`OTelContext`] -- already a dependency -- and rendered by the
//! `env_logger` format closure, which is the `log` analogue of injecting into a
//! logging pattern rather than into every call site.
//!
//! FIELD NAMES
//! `trace_id` / `span_id` are the OpenTelemetry logging convention and match what
//! java-kit binds into the SLF4J MDC, so one query joins log lines from services
//! written in either language.
//!
//! ABSENT SPANS
//! Every entry point returns nothing at all when there is no valid span. An
//! absent or non-recording span yields an all-zero trace id
//! (`00000000000000000000000000000000`); printing that is worse than printing
//! nothing, because it looks like a real id and joins every unrelated line in
//! the log together.

use opentelemetry::Context as OTelContext;
use opentelemetry::trace::TraceContextExt;

/// The OpenTelemetry logging convention field names, shared with java-kit,
/// ts-kit and python-kit. A second spelling in one language would silently
/// split the joined view, so they are constants rather than literals.
pub const TRACE_ID_FIELD: &str = "trace_id";
pub const SPAN_ID_FIELD: &str = "span_id";

/// The identifiers of a valid span. Only ever constructed for a span context
/// that reports `is_valid()`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TraceIds {
    pub trace_id: String,
    pub span_id: String,
}

/// The ambient context's ids, or `None` when no valid span is active.
///
/// Ambient means [`OTelContext::current()`]. Note that the HTTP server span is
/// made ambient by `shared_kernel`'s runtime middleware and the event consumer
/// span by [`crate::messaging`]; a span that is merely started and never
/// attached is invisible here, which is why both of those attach.
pub fn current_ids() -> Option<TraceIds> {
    ids_from(&OTelContext::current())
}

/// The ids of an explicitly supplied context, for callers that hold one
/// directly rather than through the ambient context.
pub fn ids_from(context: &OTelContext) -> Option<TraceIds> {
    let span_context = context.span().span_context().clone();
    if !span_context.is_valid() {
        return None;
    }
    Some(TraceIds {
        trace_id: span_context.trace_id().to_string(),
        span_id: span_context.span_id().to_string(),
    })
}

/// The ambient ids rendered for a log line, or an empty string when no valid
/// span is active.
pub fn log_fields() -> String {
    fields_from(&OTelContext::current())
}

/// The supplied context's ids rendered for a log line.
///
/// The rendering carries its own trailing separator and is empty when there is
/// no span, so a formatter can interpolate it unconditionally and lines logged
/// outside any span (startup, shutdown, background timers) stay exactly as
/// readable as they were rather than showing an empty placeholder.
pub fn fields_from(context: &OTelContext) -> String {
    match ids_from(context) {
        None => String::new(),
        Some(ids) => format!(
            "{TRACE_ID_FIELD}={} {SPAN_ID_FIELD}={} ",
            ids.trace_id, ids.span_id
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use opentelemetry::global;
    use opentelemetry::trace::{SpanContext, SpanId, TraceFlags, TraceId, TraceState, Tracer};
    use opentelemetry_sdk::trace::InMemorySpanExporterBuilder;

    const TRACE: &str = "0af7651916cd43dd8448eb211c80319c";
    const SPAN: &str = "b7ad6b7169203331";

    fn sampled_context(trace_id: &str, span_id: &str) -> OTelContext {
        OTelContext::new().with_remote_span_context(SpanContext::new(
            TraceId::from_hex(trace_id).expect("trace id"),
            SpanId::from_hex(span_id).expect("span id"),
            TraceFlags::SAMPLED,
            true,
            TraceState::default(),
        ))
    }

    #[test]
    fn reports_the_ids_of_a_valid_span() {
        let ids = ids_from(&sampled_context(TRACE, SPAN)).expect("valid span has ids");
        assert_eq!(ids.trace_id, TRACE);
        assert_eq!(ids.span_id, SPAN);
    }

    #[test]
    fn reports_nothing_for_a_context_without_a_span() {
        // An absent span yields an all-zero trace id. Rendering that is worse
        // than rendering nothing: it looks like a real id and joins every
        // unrelated line in the log together.
        assert_eq!(ids_from(&OTelContext::new()), None);
        assert_eq!(fields_from(&OTelContext::new()), "");
    }

    #[test]
    fn reports_nothing_for_an_all_zero_span_context() {
        // The exact shape a non-recording span produces, asserted directly so
        // the guard cannot be weakened to "there is a span context" and still pass.
        let invalid = OTelContext::new().with_remote_span_context(SpanContext::new(
            TraceId::INVALID,
            SpanId::INVALID,
            TraceFlags::default(),
            true,
            TraceState::default(),
        ));
        assert_eq!(ids_from(&invalid), None);
        assert_eq!(fields_from(&invalid), "");
        assert!(!fields_from(&invalid).contains('0'));
    }

    #[test]
    fn renders_the_opentelemetry_field_names() {
        // The names must match java-kit's MDC keys exactly; `traceId` or
        // `trace-id` would not join.
        assert_eq!(TRACE_ID_FIELD, "trace_id");
        assert_eq!(SPAN_ID_FIELD, "span_id");
        assert_eq!(
            fields_from(&sampled_context(TRACE, SPAN)),
            format!("trace_id={TRACE} span_id={SPAN} ")
        );
    }

    #[test]
    fn a_rendered_line_contains_both_ids_and_the_message() {
        // Stands in for the env_logger format closure each service installs:
        // interpolating `log_fields()` must produce a line an operator can paste
        // into Jaeger, not just a struct that holds the ids.
        let fields = fields_from(&sampled_context(TRACE, SPAN));
        let line = format!("2026-09-07T00:00:00.000Z WARN invoicing {fields}- refund priced at 0");
        assert!(line.contains(&format!("trace_id={TRACE}")), "{line}");
        assert!(line.contains(&format!("span_id={SPAN}")), "{line}");
        assert!(line.ends_with("- refund priced at 0"), "{line}");
    }

    #[test]
    fn a_line_logged_outside_a_span_is_unchanged() {
        let line = format!(
            "2026-09-07T00:00:00.000Z INFO invoicing {}- listening on 0.0.0.0:8080",
            fields_from(&OTelContext::new())
        );
        assert_eq!(
            line,
            "2026-09-07T00:00:00.000Z INFO invoicing - listening on 0.0.0.0:8080"
        );
    }

    #[test]
    fn current_ids_follows_the_attached_context() {
        // The ambient path is what the format closure actually calls. An
        // attached context must be visible to it, and detaching must not leave
        // the previous scope's ids behind.
        let _serial = crate::otel::test_serial();
        let exporter = InMemorySpanExporterBuilder::new().build();
        let guard = crate::otel::init_with_exporter("rust-kit-test", exporter);

        assert_eq!(current_ids(), None);

        let tracer = global::tracer("rust-kit-test");
        let span = tracer.start("attached");
        let context = OTelContext::current_with_span(span);
        let expected = ids_from(&context).expect("started span is valid");
        let attached = context.attach();

        assert_eq!(current_ids(), Some(expected.clone()));
        assert!(log_fields().contains(&expected.trace_id));

        drop(attached);
        assert_eq!(current_ids(), None);

        guard.shutdown().expect("shutdown otel");
    }

    #[test]
    fn an_inner_scope_without_a_span_does_not_hide_the_outer_ids() {
        // The java-kit MDC equivalent of this had a real bug: an inner no-op
        // scope cleared the enclosing scope's ids on close, so a handler logged
        // the rest of its work -- the lines most likely to explain a failure --
        // with no trace id. Rust's Context is restored by the guard rather than
        // cleared, and an inner context that carries no span is never attached
        // over a live one by this module, so the outer ids survive.
        let _serial = crate::otel::test_serial();
        let exporter = InMemorySpanExporterBuilder::new().build();
        let guard = crate::otel::init_with_exporter("rust-kit-test", exporter);

        let tracer = global::tracer("rust-kit-test");
        let outer = OTelContext::current_with_span(tracer.start("outer"));
        let outer_ids = ids_from(&outer).expect("outer span is valid");
        let outer_attached = outer.attach();

        {
            let inner = OTelContext::current_with_span(tracer.start("inner"));
            let inner_ids = ids_from(&inner).expect("inner span is valid");
            let inner_attached = inner.attach();
            assert_eq!(current_ids(), Some(inner_ids));
            drop(inner_attached);
        }

        assert_eq!(
            current_ids(),
            Some(outer_ids),
            "the outer scope's ids must survive an inner scope closing"
        );

        drop(outer_attached);
        guard.shutdown().expect("shutdown otel");
    }
}
