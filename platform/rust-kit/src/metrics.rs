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
