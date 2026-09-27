//! Application metrics leaving a Rust service over the OTLP connection the
//! spans already use.
//!
//! WHAT IS PUBLISHED, AND WHAT IS NOT
//! ----------------------------------
//! Connection-pool state and HTTP server request duration. There is no runtime
//! group: Rust has no managed runtime, so the heap, GC and thread metrics the
//! JVM, CPython and V8 publish have no counterparts to read. Process memory and
//! CPU for these pods come from the `kubeletstats` receiver, which already
//! reports them per container without an SDK.
//!
//! NAMES
//! -----
//! The database client and HTTP server semantic conventions, by name and unit.
//! There is no Rust equivalent of Micrometer to defer to, so the conventions are
//! the only naming authority here, and matching them is what lets one query read
//! the pool of a Rust service and a Java service together.
//!
//! WHAT sqlx CANNOT REPORT
//! -----------------------
//! `db.client.connection.pending_requests` and
//! `db.client.connection.timeouts` are absent. `sqlx::Pool` publishes `size()`,
//! `num_idle()` and its configured maximum, and nothing else: the waiters queue
//! on a private `AsyncSemaphore` inside `PoolInner` with no accessor, and the
//! pool counts no acquisition failures. sqlx offers no acquire hook either --
//! there is no analogue of pgx's `AcquireTracer` -- so the queue depth cannot be
//! derived without wrapping every `acquire()` call site in the four Rust
//! services. Saturation is therefore read here as
//! `db.client.connection.count{state=used}` reaching
//! `db.client.connection.max` rather than from a pending count, and
//! [`PoolMetrics::record_acquire`] is what a call site uses to contribute the
//! wait time and the timeout count it can observe itself.

use std::env;
use std::sync::Arc;
use std::time::Duration;

use opentelemetry::metrics::{Histogram, Meter};
use opentelemetry::{KeyValue, global};
use opentelemetry_otlp::WithExportConfig;
use opentelemetry_sdk::metrics::{PeriodicReader, SdkMeterProvider};
use opentelemetry_sdk::Resource;
use shared_kernel::HttpServerRecorder;
use sqlx::PgPool;

/// The specification's own default for the periodic reader.
const DEFAULT_METRIC_EXPORT_INTERVAL: Duration = Duration::from_secs(60);

/// The pool this kit creates, named so the attribute is comparable across
/// services. Every Rust service runs exactly one.
pub const POOL_NAME: &str = "platform-rust-kit-postgres";

const POOL_NAME_KEY: &str = "db.client.connection.pool.name";
const CONNECTION_STATE_KEY: &str = "db.client.connection.state";

/// Holds the meter provider for the life of the process and shuts it down on
/// drop, mirroring [`crate::otel::OtelGuard`].
#[derive(Clone)]
pub struct MetricsGuard {
    provider: Option<Arc<SdkMeterProvider>>,
}

impl MetricsGuard {
    pub fn noop() -> Self {
        Self { provider: None }
    }

    pub fn shutdown(&self) -> Result<(), opentelemetry_sdk::error::OTelSdkError> {
        if let Some(provider) = &self.provider {
            provider.shutdown()
        } else {
            Ok(())
        }
    }

    pub fn force_flush(&self) -> Result<(), opentelemetry_sdk::error::OTelSdkError> {
        if let Some(provider) = &self.provider {
            provider.force_flush()
        } else {
            Ok(())
        }
    }
}

impl Drop for MetricsGuard {
    fn drop(&mut self) {
        let _ = self.shutdown();
    }
}

/// Install an SDK meter provider when `OTEL_METRICS_EXPORTER` selects one and an
/// OTLP endpoint is configured, and return a no-op guard otherwise, so a service
/// run without a collector attempts no export.
pub fn init_from_env(
    service_name: &str,
) -> Result<MetricsGuard, opentelemetry_otlp::ExporterBuildError> {
    if !metrics_enabled() || !otlp_endpoint_configured() {
        return Ok(MetricsGuard::noop());
    }
    let mut builder = opentelemetry_otlp::MetricExporter::builder().with_tonic();
    // The metrics-specific endpoint takes precedence over the generic one,
    // matching the OTel SDK env-var specification; gating accepts either, so the
    // exporter must honor either too.
    if let Some(endpoint) = configured_endpoint() {
        builder = builder.with_endpoint(endpoint);
    }
    let exporter = builder.build()?;
    let reader = PeriodicReader::builder(exporter)
        .with_interval(metric_export_interval())
        .build();
    let provider = SdkMeterProvider::builder()
        .with_reader(reader)
        .with_resource(service_resource(service_name))
        .build();
    Ok(install_provider(provider))
}

/// Both signals, with one guard covering them, so a service's bootstrap has a
/// single telemetry line rather than one per signal. Each half is independently
/// gated by its own `OTEL_*_EXPORTER` variable.
pub fn init_telemetry_from_env(
    service_name: &str,
) -> Result<TelemetryGuard, opentelemetry_otlp::ExporterBuildError> {
    let traces = crate::otel::init_from_env(service_name)?;
    // A metrics failure returns here with `traces` dropped, and its Drop shuts
    // the tracer provider down rather than leaving it exporting from a process
    // that failed to finish starting.
    let metrics = init_from_env(service_name)?;
    Ok(TelemetryGuard { traces, metrics })
}

/// Holds both providers for the life of the process.
#[derive(Clone)]
pub struct TelemetryGuard {
    traces: crate::otel::OtelGuard,
    metrics: MetricsGuard,
}

impl TelemetryGuard {
    pub fn force_flush(&self) -> Result<(), opentelemetry_sdk::error::OTelSdkError> {
        // Both run even when the first fails: a provider left running holds a
        // gRPC connection and a collection thread.
        let traces = self.traces.force_flush();
        let metrics = self.metrics.force_flush();
        traces.and(metrics)
    }

    pub fn shutdown(&self) -> Result<(), opentelemetry_sdk::error::OTelSdkError> {
        let traces = self.traces.shutdown();
        let metrics = self.metrics.shutdown();
        traces.and(metrics)
    }
}

/// Test-oriented init: the caller supplies an exporter it can read back, so a
/// test sees what was produced without a collector. Production init goes through
/// [`init_from_env`], which reads the endpoint and interval from the
/// environment.
pub fn init_with_exporter<E>(service_name: &str, exporter: E) -> MetricsGuard
where
    E: opentelemetry_sdk::metrics::exporter::PushMetricExporter,
{
    let provider = SdkMeterProvider::builder()
        .with_periodic_exporter(exporter)
        .with_resource(service_resource(service_name))
        .build();
    install_provider(provider)
}

/// The meter the kit's own instrumentation records through. Without an installed
/// provider the global default is a no-op, so callers need no condition of their
/// own.
pub fn meter(service_name: &str) -> Meter {
    global::meter_with_scope(
        opentelemetry::InstrumentationScope::builder("rust-kit")
            .with_attributes([KeyValue::new("service.name", resolved_service_name(service_name))])
            .build(),
    )
}

/// The pool instruments, and the handle a call site records an acquisition
/// through.
///
/// The state instruments are observable: occupancy is a level to be sampled and
/// sqlx already maintains it, so a callback reading the pool is both cheaper than
/// mirroring every checkout and closer to the pool's own view.
#[derive(Clone)]
pub struct PoolMetrics {
    wait_time: Histogram<f64>,
    timeouts: opentelemetry::metrics::Counter<u64>,
    pool_attributes: Vec<KeyValue>,
}

impl PoolMetrics {
    /// Register the pool's state against `meter` and return the acquisition
    /// handle.
    pub fn register(meter: &Meter, pool: &PgPool, pool_name: &str) -> Self {
        let pool_attributes = vec![KeyValue::new(POOL_NAME_KEY, pool_name.to_owned())];
        let used_attributes = [
            KeyValue::new(POOL_NAME_KEY, pool_name.to_owned()),
            KeyValue::new(CONNECTION_STATE_KEY, "used"),
        ];
        let idle_attributes = [
            KeyValue::new(POOL_NAME_KEY, pool_name.to_owned()),
            KeyValue::new(CONNECTION_STATE_KEY, "idle"),
        ];

        let observed = pool.clone();
        meter
            .i64_observable_up_down_counter("db.client.connection.count")
            .with_description(
                "The number of connections that are currently in state described by the state attribute.",
            )
            .with_unit("{connection}")
            .with_callback(move |observer| {
                // sqlx reports the pool's total size and how many of those are
                // idle, not how many are checked out, so "used" is the
                // difference. A negative result is impossible but would corrupt
                // a sum, so it is clamped.
                let size = i64::from(observed.size());
                let idle = i64::try_from(observed.num_idle()).unwrap_or(size);
                observer.observe((size - idle).max(0), &used_attributes);
                observer.observe(idle, &idle_attributes);
            })
            .build();

        let configured = pool.options().get_max_connections();
        let max_attributes = pool_attributes.clone();
        meter
            .i64_observable_up_down_counter("db.client.connection.max")
            .with_description("The maximum number of open connections allowed.")
            .with_unit("{connection}")
            .with_callback(move |observer| {
                observer.observe(i64::from(configured), &max_attributes);
            })
            .build();

        // Synchronous: a wait time is a distribution over events, not a level,
        // and a timeout is an event.
        let wait_time = meter
            .f64_histogram("db.client.connection.wait_time")
            .with_description("The time it took to obtain an open connection from the pool.")
            .with_unit("s")
            .build();
        let timeouts = meter
            .u64_counter("db.client.connection.timeouts")
            .with_description(
                "The number of connection timeouts that have occurred trying to obtain a connection from the pool.",
            )
            .with_unit("{timeout}")
            .build();

        Self {
            wait_time,
            timeouts,
            pool_attributes,
        }
    }

    /// Record one acquisition: how long it waited, and whether it timed out.
    ///
    /// Called by a call site rather than by the pool because sqlx exposes no
    /// acquire hook. An acquisition that failed with
    /// [`sqlx::Error::PoolTimedOut`] waited until its deadline, which is what a
    /// pool timeout is from the caller's side.
    pub fn record_acquire(&self, waited: Duration, outcome: Result<(), &sqlx::Error>) {
        self.wait_time
            .record(waited.as_secs_f64(), &self.pool_attributes);
        if matches!(outcome, Err(sqlx::Error::PoolTimedOut)) {
            self.timeouts.add(1, &self.pool_attributes);
        }
    }
}

/// The HTTP server instruments the semantic conventions define.
///
/// There is no request counter: the duration histogram carries its own count, so
/// a second instrument would be a parallel series with the same information.
#[derive(Clone)]
pub struct HttpServerMetrics {
    request_duration: Histogram<f64>,
    active_requests: opentelemetry::metrics::UpDownCounter<i64>,
}

impl HttpServerMetrics {
    pub fn new(meter: &Meter) -> Self {
        Self {
            request_duration: meter
                .f64_histogram("http.server.request.duration")
                .with_description("Duration of HTTP server requests.")
                .with_unit("s")
                .build(),
            active_requests: meter
                .i64_up_down_counter("http.server.active_requests")
                .with_description("Number of active HTTP server requests.")
                .with_unit("{request}")
                .build(),
        }
    }

    /// Build the instruments only when metrics export is selected, mirroring
    /// [`crate::otel::init_from_env`]. `None` otherwise, so a service with
    /// metrics disabled pays not even the attribute allocation per request.
    pub fn from_env(service_name: &str) -> Option<Self> {
        if !metrics_enabled() {
            return None;
        }
        Some(Self::new(&meter(service_name)))
    }

    /// Install this as the runtime middleware's recorder, or nothing when
    /// metrics are disabled. This is what makes the four Rust services export
    /// HTTP server metrics through `RuntimeConfig` rather than each wiring a
    /// middleware of its own.
    pub fn recorder_from_env(service_name: &str) -> Option<Arc<dyn HttpServerRecorder>> {
        Self::from_env(service_name).map(|metrics| Arc::new(metrics) as Arc<dyn HttpServerRecorder>)
    }
}

impl HttpServerRecorder for HttpServerMetrics {
    fn request_started(&self, method: &str) {
        self.active_requests.add(
            1,
            &[KeyValue::new("http.request.method", request_method(method))],
        );
    }

    fn request_finished(&self, method: &str, route: Option<&str>, status: u16, elapsed: Duration) {
        let method = request_method(method);
        self.active_requests
            .add(-1, &[KeyValue::new("http.request.method", method.clone())]);
        let mut attributes = vec![
            KeyValue::new("http.request.method", method),
            KeyValue::new("http.response.status_code", i64::from(status)),
        ];
        if let Some(route) = route {
            attributes.push(KeyValue::new("http.route", route.to_owned()));
        }
        // error.type is what makes an error rate readable off the histogram.
        if status >= 500 {
            attributes.push(KeyValue::new("error.type", status.to_string()));
        }
        self.request_duration
            .record(elapsed.as_secs_f64(), &attributes);
    }
}

/// Map a method onto the convention's enumeration. An unregistered method
/// becomes `_OTHER`: a client can send any token there, and a series per
/// invented method name is a cardinality leak reachable from outside.
fn request_method(method: &str) -> String {
    match method {
        "GET" | "HEAD" | "POST" | "PUT" | "PATCH" | "DELETE" | "CONNECT" | "OPTIONS" | "TRACE" => {
            method.to_owned()
        }
        _ => "_OTHER".to_owned(),
    }
}

fn install_provider(provider: SdkMeterProvider) -> MetricsGuard {
    global::set_meter_provider(provider.clone());
    MetricsGuard {
        provider: Some(Arc::new(provider)),
    }
}

fn service_resource(service_name: &str) -> Resource {
    // The same resource shape the tracer provider uses. A span and a metric
    // point from one process must carry identical resource attributes, or a
    // query joining them on service.name finds one signal and not the other.
    Resource::builder_empty()
        .with_attribute(KeyValue::new(
            "service.name",
            resolved_service_name(service_name),
        ))
        .build()
}

fn resolved_service_name(service_name: &str) -> String {
    env::var("OTEL_SERVICE_NAME")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| service_name.to_string())
}

pub fn metrics_enabled() -> bool {
    let exporter = env::var("OTEL_METRICS_EXPORTER")
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase();
    !exporter.is_empty() && exporter != "none"
}

fn configured_endpoint() -> Option<String> {
    env::var("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .or_else(|| {
            env::var("OTEL_EXPORTER_OTLP_ENDPOINT")
                .ok()
                .filter(|value| !value.trim().is_empty())
        })
}

fn otlp_endpoint_configured() -> bool {
    configured_endpoint().is_some()
}

/// The order funnel's closed vocabularies, shared with the Java and Python
/// services that publish the same counter. Every label value is a member of one
/// of these tuples or the fallback `other`, so a series can never be named after
/// a request, an itinerary or an exception message.
pub const ORDER_FUNNEL_STAGES: [&str; 6] = [
    "search",
    "quote",
    "order",
    "reservation",
    "payment",
    "ticket",
];
pub const ORDER_FUNNEL_OUTCOMES: [&str; 3] = ["attempted", "accepted", "rejected"];
pub const ORDER_FUNNEL_CHANNELS: [&str; 5] = ["web", "mobile", "counter", "partner", "api"];
pub const ORDER_FUNNEL_OTHER: &str = "other";

fn funnel_vocabulary(value: &str, allowed: &[&str]) -> String {
    let normalized = value.trim().to_ascii_lowercase();
    if allowed.contains(&normalized.as_str()) {
        normalized
    } else {
        ORDER_FUNNEL_OTHER.to_string()
    }
}

/// Publishes `train_ticket.order.transitions` for the stages one service owns.
///
/// A reader takes `rate()` over it and gets a stage's funnel by outcome and
/// purchase channel. One stage records `attempted` once and then exactly one of
/// `accepted` or `rejected`, which is what keeps `attempted = accepted +
/// rejected` for that stage.
///
/// The instrument is built from the global meter, which is a no-op unless the
/// process installed an SDK provider, so a service needs no condition of its own
/// to call it.
#[derive(Clone)]
pub struct OrderFunnel {
    transitions: opentelemetry::metrics::Counter<u64>,
}

impl OrderFunnel {
    pub fn new(meter: &Meter) -> Self {
        Self {
            transitions: meter
                .u64_counter("train_ticket.order.transitions")
                .with_description(
                    "Order funnel transitions by stage, outcome and purchase channel.",
                )
                .with_unit("{transition}")
                .build(),
        }
    }

    pub fn transition(&self, stage: &str, outcome: &str, channel: &str) {
        self.transitions.add(
            1,
            &[
                KeyValue::new("stage", funnel_vocabulary(stage, &ORDER_FUNNEL_STAGES)),
                KeyValue::new("outcome", funnel_vocabulary(outcome, &ORDER_FUNNEL_OUTCOMES)),
                KeyValue::new("channel", funnel_vocabulary(channel, &ORDER_FUNNEL_CHANNELS)),
            ],
        );
    }
}

/// The disposition of one delivered event, as the contract's closed vocabulary
/// defines it. Success, duplication, retry pressure and poison handling are what
/// a reader needs; stream names, consumer names and event types would be a
/// series per integration instead.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConsumerOutcome {
    Success,
    Duplicate,
    Retry,
    DeadLetter,
}

impl ConsumerOutcome {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Success => "success",
            Self::Duplicate => "duplicate",
            Self::Retry => "retry",
            Self::DeadLetter => "dead_letter",
        }
    }
}

/// Why a delivery did not succeed. Every value is a member of the contract's
/// closed set, so a series can never be named after an exception message.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConsumerFailure {
    HandlerException,
    TransientResult,
    FatalResult,
    MissingEnvelope,
    InvalidEnvelope,
    RedeliveryExhausted,
    DlqWrite,
}

impl ConsumerFailure {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::HandlerException => "handler_exception",
            Self::TransientResult => "transient_result",
            Self::FatalResult => "fatal_result",
            Self::MissingEnvelope => "missing_envelope",
            Self::InvalidEnvelope => "invalid_envelope",
            Self::RedeliveryExhausted => "redelivery_exhausted",
            Self::DlqWrite => "dlq_write",
        }
    }
}

/// The process-level transport counters one subscriber publishes.
///
/// A dead-letter counter says messages were moved over time; the current
/// population is `train_ticket.event.dlq.entries`, which the transport collector
/// reads from Redis. Both are needed and neither substitutes for the other.
#[derive(Clone)]
pub struct ConsumerMetrics {
    messages: opentelemetry::metrics::Counter<u64>,
    failures: opentelemetry::metrics::Counter<u64>,
    dead_letters: opentelemetry::metrics::Counter<u64>,
}

impl ConsumerMetrics {
    pub fn new(meter: &Meter) -> Self {
        Self {
            messages: meter
                .u64_counter("train_ticket.event.consumer.messages")
                .with_description("Event deliveries by their processing disposition.")
                .with_unit("{message}")
                .build(),
            failures: meter
                .u64_counter("train_ticket.event.consumer.failures")
                .with_description("Event-consumer failures by a bounded failure classification.")
                .with_unit("{failure}")
                .build(),
            dead_letters: meter
                .u64_counter("train_ticket.event.consumer.dead_letters")
                .with_description("Messages successfully handed to a dead-letter stream.")
                .with_unit("{message}")
                .build(),
        }
    }

    pub fn outcome(&self, outcome: ConsumerOutcome) {
        self.messages
            .add(1, &[KeyValue::new("outcome", outcome.as_str())]);
    }

    pub fn failure(&self, failure: ConsumerFailure) {
        self.failures
            .add(1, &[KeyValue::new("failure.type", failure.as_str())]);
    }

    pub fn dead_letter(&self, reason: ConsumerFailure) {
        self.dead_letters
            .add(1, &[KeyValue::new("reason", reason.as_str())]);
    }
}

/// Which stage of the relay failed. The stages are the three things it does, so
/// a failing select, a failing publish and a failing acknowledgement are
/// distinguishable without naming the statement that failed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OutboxStage {
    Select,
    Publish,
    MarkPublished,
}

impl OutboxStage {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Select => "select",
            Self::Publish => "publish",
            Self::MarkPublished => "mark_published",
        }
    }
}

/// The transactional outbox's health signals.
///
/// The queue depth is a *bounded head sample*, not `count(*)`: a count over an
/// outbox with a real backlog is a sequential scan, and the relay would be
/// adding database pressure in order to report that it cannot keep up. A sample
/// equal to the batch size means at least one full batch is waiting, which is
/// the fact worth alarming on.
#[derive(Clone, Default)]
pub struct OutboxMetrics {
    sample: std::sync::Arc<OutboxSample>,
    published: Option<opentelemetry::metrics::Counter<u64>>,
    failures: Option<opentelemetry::metrics::Counter<u64>>,
}

#[derive(Default)]
struct OutboxSample {
    rows: std::sync::atomic::AtomicI64,
    oldest_age_millis: std::sync::atomic::AtomicI64,
}

impl OutboxMetrics {
    pub fn new(meter: &Meter) -> Self {
        let sample = std::sync::Arc::new(OutboxSample::default());
        let observed = std::sync::Arc::clone(&sample);
        meter
            .i64_observable_gauge("train_ticket.outbox.unpublished.sample")
            .with_description("Rows visible in the relay's bounded unpublished head sample.")
            .with_unit("{event}")
            .with_callback(move |observer| {
                observer.observe(
                    observed.rows.load(std::sync::atomic::Ordering::Relaxed),
                    &[],
                );
            })
            .build();
        let observed = std::sync::Arc::clone(&sample);
        meter
            .f64_observable_gauge("train_ticket.outbox.oldest_unpublished.age")
            .with_description("Age of the head-of-line unpublished outbox event.")
            .with_unit("s")
            .with_callback(move |observer| {
                observer.observe(
                    observed
                        .oldest_age_millis
                        .load(std::sync::atomic::Ordering::Relaxed) as f64
                        / 1000.0,
                    &[],
                );
            })
            .build();
        Self {
            sample,
            published: Some(
                meter
                    .u64_counter("train_ticket.outbox.published")
                    .with_description(
                        "Outbox events whose bounded Redis publish pipeline completed successfully.",
                    )
                    .with_unit("{event}")
                    .build(),
            ),
            failures: Some(
                meter
                    .u64_counter("train_ticket.outbox.failures")
                    .with_description("Outbox relay failures by a bounded processing stage.")
                    .with_unit("{failure}")
                    .build(),
            ),
        }
    }

    /// Records the head of the relay's next batch. `rows` is what the bounded
    /// query returned, never a count of the table.
    pub fn observe_unpublished_sample(&self, rows: usize, oldest_age: Duration) {
        self.sample
            .rows
            .store(rows as i64, std::sync::atomic::Ordering::Relaxed);
        self.sample.oldest_age_millis.store(
            oldest_age.as_millis() as i64,
            std::sync::atomic::Ordering::Relaxed,
        );
    }

    pub fn record_published(&self, rows: usize) {
        if rows > 0
            && let Some(published) = &self.published
        {
            published.add(rows as u64, &[]);
        }
    }

    pub fn record_failure(&self, stage: OutboxStage) {
        if let Some(failures) = &self.failures {
            failures.add(1, &[KeyValue::new("stage", stage.as_str())]);
        }
    }
}

/// Read `OTEL_METRIC_EXPORT_INTERVAL`, the standard SDK variable, which is
/// specified in milliseconds. An unparseable or non-positive value falls back to
/// the specification default rather than failing startup: this governs only how
/// often metrics are sent, and a service must still start.
fn metric_export_interval() -> Duration {
    let configured = env::var("OTEL_METRIC_EXPORT_INTERVAL").unwrap_or_default();
    match configured.trim().parse::<u64>() {
        Ok(millis) if millis > 0 => Duration::from_millis(millis),
        _ => DEFAULT_METRIC_EXPORT_INTERVAL,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use opentelemetry_sdk::metrics::data::{AggregatedMetrics, MetricData};
    use opentelemetry_sdk::metrics::InMemoryMetricExporter;

    #[test]
    fn init_from_env_is_noop_without_otel_metrics_exporter() {
        let _serial = crate::otel::test_serial();
        unsafe {
            env::remove_var("OTEL_METRICS_EXPORTER");
            env::remove_var("OTEL_EXPORTER_OTLP_ENDPOINT");
        }

        let guard = init_from_env("rust-kit-test").expect("noop init succeeds");
        assert!(guard.provider.is_none());
    }

    #[test]
    fn metric_export_interval_falls_back_to_the_specification_default() {
        let _serial = crate::otel::test_serial();
        unsafe {
            env::remove_var("OTEL_METRIC_EXPORT_INTERVAL");
        }
        assert_eq!(metric_export_interval(), Duration::from_secs(60));

        unsafe {
            env::set_var("OTEL_METRIC_EXPORT_INTERVAL", "15000");
        }
        assert_eq!(metric_export_interval(), Duration::from_millis(15_000));

        // A value that is not a positive number of milliseconds governs only the
        // export period, so it falls back rather than failing startup.
        for invalid in ["not-a-number", "0"] {
            unsafe {
                env::set_var("OTEL_METRIC_EXPORT_INTERVAL", invalid);
            }
            assert_eq!(metric_export_interval(), Duration::from_secs(60));
        }
        unsafe {
            env::remove_var("OTEL_METRIC_EXPORT_INTERVAL");
        }
    }

    #[test]
    fn http_server_metrics_publish_convention_names() {
        let _serial = crate::otel::test_serial();
        unsafe {
            env::set_var("OTEL_SERVICE_NAME", "rust-kit-test");
        }
        let exporter = InMemoryMetricExporter::default();
        let guard = init_with_exporter("rust-kit-test", exporter.clone());

        let metrics = HttpServerMetrics::new(&meter("rust-kit-test"));
        metrics.request_started("POST");
        metrics.request_finished("POST", Some("/api/v1/invoices"), 500, Duration::from_millis(250));
        guard.force_flush().expect("flush metrics");

        let collected = collected_names(&exporter);
        for name in ["http.server.request.duration", "http.server.active_requests"] {
            assert!(
                collected.contains(&name.to_string()),
                "missing {name}; collected {collected:?}"
            );
        }
        unsafe {
            env::remove_var("OTEL_SERVICE_NAME");
        }
    }

    #[test]
    fn an_unregistered_method_is_reported_as_other() {
        assert_eq!(request_method("POST"), "POST");
        assert_eq!(request_method("BREW"), "_OTHER");
    }

    /// Every label a reader of the transport rules groups by has to be present
    /// under the name and unit the Java kit already publishes: the rules are
    /// shared, so a kit that spelled one differently would be a series nothing
    /// queries.
    #[test]
    fn transport_metrics_publish_the_contract_names_units_and_labels() {
        let _serial = crate::otel::test_serial();
        unsafe {
            env::set_var("OTEL_SERVICE_NAME", "rust-kit-test");
        }
        let exporter = InMemoryMetricExporter::default();
        let guard = init_with_exporter("rust-kit-test", exporter.clone());
        let meter = meter("rust-kit-test");

        let consumer = ConsumerMetrics::new(&meter);
        consumer.outcome(ConsumerOutcome::Success);
        consumer.outcome(ConsumerOutcome::Retry);
        consumer.failure(ConsumerFailure::TransientResult);
        consumer.dead_letter(ConsumerFailure::FatalResult);

        let outbox = OutboxMetrics::new(&meter);
        outbox.observe_unpublished_sample(7, Duration::from_millis(2_500));
        outbox.record_published(3);
        outbox.record_failure(OutboxStage::Publish);
        guard.force_flush().expect("flush metrics");

        let collected = collected(&exporter);
        for name in [
            "train_ticket.event.consumer.messages",
            "train_ticket.event.consumer.failures",
            "train_ticket.event.consumer.dead_letters",
            "train_ticket.outbox.unpublished.sample",
            "train_ticket.outbox.oldest_unpublished.age",
            "train_ticket.outbox.published",
            "train_ticket.outbox.failures",
        ] {
            assert!(
                collected.contains_key(name),
                "missing {name}; collected {:?}",
                collected.keys().collect::<Vec<_>>()
            );
        }

        let messages = collected["train_ticket.event.consumer.messages"].1.clone();
        assert_eq!(messages.get("outcome=success"), Some(&1.0));
        assert_eq!(messages.get("outcome=retry"), Some(&1.0));
        let failures = collected["train_ticket.event.consumer.failures"].1.clone();
        assert_eq!(failures.get("failure.type=transient_result"), Some(&1.0));
        let dead_letters = collected["train_ticket.event.consumer.dead_letters"].1.clone();
        assert_eq!(dead_letters.get("reason=fatal_result"), Some(&1.0));

        let published = collected["train_ticket.outbox.published"].1.clone();
        assert_eq!(published.get(""), Some(&3.0));
        let outbox_failures = collected["train_ticket.outbox.failures"].1.clone();
        assert_eq!(outbox_failures.get("stage=publish"), Some(&1.0));
        let sample = collected["train_ticket.outbox.unpublished.sample"].1.clone();
        assert_eq!(sample.get(""), Some(&7.0));
        let age = collected["train_ticket.outbox.oldest_unpublished.age"].1.clone();
        assert_eq!(age.get(""), Some(&2.5));
        assert_eq!(
            collected["train_ticket.outbox.oldest_unpublished.age"].0,
            "s"
        );

        unsafe {
            env::remove_var("OTEL_SERVICE_NAME");
        }
    }

    /// A kit without an installed provider must record nothing and panic
    /// nowhere: the transports are unconditional in the subscriber.
    #[test]
    fn transport_metrics_are_inert_without_a_provider() {
        let meter = meter("rust-kit-test-uninstalled");
        let consumer = ConsumerMetrics::new(&meter);
        consumer.outcome(ConsumerOutcome::Duplicate);
        consumer.failure(ConsumerFailure::MissingEnvelope);
        let outbox = OutboxMetrics::default();
        outbox.record_published(1);
        outbox.record_failure(OutboxStage::Select);
        outbox.observe_unpublished_sample(1, Duration::from_secs(1));
    }

    /// The helper the assertions above are written against: metric name to its
    /// unit and the value at each attribute set. The SDK's `Metric` is not
    /// cloneable, so the points are extracted while the exporter is in hand.
    fn collected(exporter: &InMemoryMetricExporter) -> std::collections::HashMap<String, (String, std::collections::HashMap<String, f64>)> {
        let mut found = std::collections::HashMap::new();
        for resource_metric in exporter.get_finished_metrics().expect("finished metrics") {
            for scope_metric in resource_metric.scope_metrics() {
                for metric in scope_metric.metrics() {
                    found.insert(
                        metric.name().to_string(),
                        (metric.unit().to_string(), points(metric)),
                    );
                }
            }
        }
        found
    }

    fn points(metric: &opentelemetry_sdk::metrics::data::Metric) -> std::collections::HashMap<String, f64> {
        let mut found = std::collections::HashMap::new();
        match metric.data() {
            AggregatedMetrics::U64(MetricData::Sum(sum)) => {
                for point in sum.data_points() {
                    found.insert(attributes_key(point.attributes()), point.value() as f64);
                }
            }
            AggregatedMetrics::I64(MetricData::Gauge(gauge)) => {
                for point in gauge.data_points() {
                    found.insert(attributes_key(point.attributes()), point.value() as f64);
                }
            }
            AggregatedMetrics::F64(MetricData::Gauge(gauge)) => {
                for point in gauge.data_points() {
                    found.insert(attributes_key(point.attributes()), point.value());
                }
            }
            other => panic!("unexpected aggregation for a transport metric: {other:?}"),
        }
        found
    }

    fn attributes_key<'a>(attributes: impl Iterator<Item = &'a opentelemetry::KeyValue>) -> String {
        let mut parts: Vec<String> = attributes
            .map(|kv| format!("{}={}", kv.key.as_str(), kv.value))
            .collect();
        parts.sort();
        parts.join(",")
    }

    #[test]
    fn order_funnel_publishes_the_contract_name_and_only_contract_labels() {
        let _serial = crate::otel::test_serial();
        unsafe {
            env::set_var("OTEL_SERVICE_NAME", "rust-kit-test");
        }
        let exporter = InMemoryMetricExporter::default();
        let guard = init_with_exporter("rust-kit-test", exporter.clone());

        let funnel = OrderFunnel::new(&meter("rust-kit-test"));
        // A channel that is not in the vocabulary, in an unexpected case, has to
        // collapse to `other` rather than becoming a series of its own.
        funnel.transition("search", "attempted", "WEB");
        funnel.transition("search", "accepted", "web");
        funnel.transition("search", "rejected", "acme-partner-7");
        guard.force_flush().expect("flush metrics");

        let names = collected_names(&exporter);
        assert!(
            names.contains(&"train_ticket.order.transitions".to_string()),
            "missing the funnel counter; collected {names:?}"
        );

        let mut channels: Vec<String> = Vec::new();
        for resource_metric in exporter.get_finished_metrics().expect("finished metrics") {
            for scope_metric in resource_metric.scope_metrics() {
                for metric in scope_metric.metrics() {
                    if metric.name() != "train_ticket.order.transitions" {
                        continue;
                    }
                    let AggregatedMetrics::U64(MetricData::Sum(sum)) = metric.data() else {
                        panic!("the funnel counter is a u64 sum: {:?}", metric.data());
                    };
                    for point in sum.data_points() {
                        let mut attrs = std::collections::HashMap::new();
                        for kv in point.attributes() {
                            attrs.insert(kv.key.as_str().to_string(), kv.value.to_string());
                        }
                        assert_eq!(attrs.get("stage").map(String::as_str), Some("search"));
                        channels.push(attrs.get("channel").cloned().unwrap_or_default());
                    }
                }
            }
        }
        channels.sort();
        assert_eq!(channels, vec!["other", "web", "web"]);

        unsafe {
            env::remove_var("OTEL_SERVICE_NAME");
        }
    }

    fn collected_names(exporter: &InMemoryMetricExporter) -> Vec<String> {
        let mut names = Vec::new();
        for resource_metric in exporter.get_finished_metrics().expect("finished metrics") {
            for scope_metric in resource_metric.scope_metrics() {
                for metric in scope_metric.metrics() {
                    names.push(metric.name().to_string());
                }
            }
        }
        names
    }
}
