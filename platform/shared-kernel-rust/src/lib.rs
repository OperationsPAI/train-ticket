use std::fmt;
use std::str::FromStr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

use axum::{
    Json, Router,
    extract::{Request, State},
    http::{HeaderValue, StatusCode},
    middleware::{self, Next},
    response::Response,
    routing::get,
};
use opentelemetry::{
    KeyValue, global,
    trace::{Span as OTelSpanTrait, Tracer},
};
use serde::Serialize;
use serde_json::{Value, json};

/// Standard inbound/outbound HTTP request identifier header.
pub const REQUEST_ID_HEADER: &str = "x-request-id";
/// Standard inbound/outbound HTTP correlation identifier header.
pub const CORRELATION_ID_HEADER: &str = "x-correlation-id";

static REQUEST_ID_SEQUENCE: AtomicU64 = AtomicU64::new(1);

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ServiceProfile {
    pub service_id: &'static str,
    pub domain: &'static str,
    pub language: &'static str,
    pub phase: &'static str,
    pub work_packages: &'static [&'static str],
    pub owns: &'static [&'static str],
}

pub fn profile() -> ServiceProfile {
    ServiceProfile {
        service_id: "shared-kernel",
        domain: "Shared Kernel / Platform",
        language: "rust",
        phase: "phase-1-foundation",
        work_packages: &["REQ-003"],
        owns: &[
            "reference implementation for IDs, value objects, and event envelope",
            "language-neutral contract source before code generation exists",
        ],
    }
}

pub fn health() -> &'static str {
    "ok"
}

/// Metadata exposed by HTTP routers and service discovery tests.
pub fn metadata() -> ServiceProfile {
    profile()
}

/// HTTP request identity made available to handlers through Axum extensions.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RequestContext {
    request_id: String,
    correlation_id: String,
}

impl RequestContext {
    pub fn new(request_id: impl Into<String>, correlation_id: impl Into<String>) -> Self {
        Self {
            request_id: request_id.into(),
            correlation_id: correlation_id.into(),
        }
    }

    pub fn request_id(&self) -> &str {
        &self.request_id
    }

    pub fn correlation_id(&self) -> &str {
        &self.correlation_id
    }
}

/// A no-op-by-default observability seam for opt-in tracing adapters.
pub trait Observer: Send + Sync + 'static {
    fn start(&self, context: &RequestContext, operation: &str, method: &str) -> Box<dyn Span>;
}

pub trait Span: Send + Sync + 'static {
    fn end(&mut self, status: StatusCode);
}

#[derive(Debug, Clone, Default)]
pub struct NoopObserver;

#[derive(Debug)]
struct NoopSpan;

impl Observer for NoopObserver {
    fn start(&self, _context: &RequestContext, _operation: &str, _method: &str) -> Box<dyn Span> {
        Box::new(NoopSpan)
    }
}

impl Span for NoopSpan {
    fn end(&mut self, _status: StatusCode) {}
}

#[derive(Debug, Clone)]
pub struct OpenTelemetryObserver {
    service_name: String,
    tracer_name: &'static str,
}

struct OpenTelemetrySpan {
    span: opentelemetry::global::BoxedSpan,
}

impl OpenTelemetryObserver {
    pub fn new(service_name: impl Into<String>) -> Self {
        Self {
            service_name: service_name.into(),
            tracer_name: "shared-kernel-rust",
        }
    }

    pub fn from_env(service_name: impl Into<String>) -> Arc<dyn Observer> {
        let exporter = std::env::var("OTEL_TRACES_EXPORTER")
            .unwrap_or_default()
            .trim()
            .to_ascii_lowercase();
        if exporter.is_empty() || exporter == "none" {
            Arc::new(NoopObserver)
        } else {
            let configured = std::env::var("OTEL_SERVICE_NAME").unwrap_or_default();
            let fallback = service_name.into();
            let service_name = if configured.trim().is_empty() {
                fallback
            } else {
                configured
            };
            Arc::new(Self::new(service_name))
        }
    }
}

impl Observer for OpenTelemetryObserver {
    fn start(&self, context: &RequestContext, operation: &str, method: &str) -> Box<dyn Span> {
        let tracer = global::tracer(self.tracer_name);
        let span = tracer
            .span_builder(operation.to_owned())
            .with_kind(opentelemetry::trace::SpanKind::Server)
            .with_attributes(vec![
                KeyValue::new("service.name", self.service_name.clone()),
                KeyValue::new("http.route", operation.to_owned()),
                KeyValue::new("url.path", operation.to_owned()),
                KeyValue::new("http.request.method", method.to_owned()),
                KeyValue::new("http.method", method.to_owned()),
                KeyValue::new("http.request_id", context.request_id().to_owned()),
                KeyValue::new("http.correlation_id", context.correlation_id().to_owned()),
            ])
            .start(&tracer);
        Box::new(OpenTelemetrySpan { span })
    }
}

impl Span for OpenTelemetrySpan {
    fn end(&mut self, status: StatusCode) {
        self.span.set_attribute(KeyValue::new(
            "http.response.status_code",
            i64::from(status.as_u16()),
        ));
        self.span.set_attribute(KeyValue::new(
            "http.status_code",
            i64::from(status.as_u16()),
        ));
        if status.is_server_error() {
            self.span
                .set_status(opentelemetry::trace::Status::error(format!(
                    "http status {}",
                    status.as_u16()
                )));
        }
        self.span.end();
    }
}

#[derive(Clone)]
pub struct RuntimeConfig {
    pub metadata: Value,
    pub observer: Arc<dyn Observer>,
}

impl RuntimeConfig {
    pub fn new(profile: ServiceProfile) -> Self {
        Self::from_metadata(profile)
    }

    pub fn from_metadata(metadata: impl Serialize) -> Self {
        Self {
            metadata: serde_json::to_value(metadata).unwrap_or_else(|_| json!({})),
            observer: Arc::new(NoopObserver),
        }
    }

    pub fn with_observer(mut self, observer: Arc<dyn Observer>) -> Self {
        self.observer = observer;
        self
    }
}

/// Construct the standard Axum-compatible service router.
pub fn router() -> Router {
    router_with_config(RuntimeConfig::new(profile()))
}

pub fn router_with_config(config: RuntimeConfig) -> Router {
    let metadata = config.metadata.clone();
    let standard_routes = Router::new()
        .route("/health", get(health_handler))
        .route("/healthz", get(health_handler))
        .route("/live", get(live_handler))
        .route("/livez", get(live_handler))
        .route("/ready", get(ready_handler))
        .route("/readyz", get(ready_handler))
        .route(
            "/metadata",
            get(move || {
                let metadata = metadata.clone();
                async move { Json(metadata) }
            }),
        );
    apply_runtime(standard_routes, config)
}

/// Apply the shared runtime middleware to a router with service-specific routes.
pub fn apply_runtime(router: Router, config: RuntimeConfig) -> Router {
    router.layer(middleware::from_fn_with_state(config, runtime_middleware))
}

#[derive(Debug, Serialize)]
struct ProbeResponse {
    status: &'static str,
}

async fn health_handler() -> Json<ProbeResponse> {
    Json(ProbeResponse { status: health() })
}

async fn live_handler() -> Json<ProbeResponse> {
    Json(ProbeResponse { status: "alive" })
}

async fn ready_handler() -> Json<ProbeResponse> {
    Json(ProbeResponse { status: "ready" })
}

async fn runtime_middleware(
    State(config): State<RuntimeConfig>,
    mut request: Request,
    next: Next,
) -> Response {
    let request_id = header_or_generate(&request, REQUEST_ID_HEADER);
    let correlation_id =
        header_value(&request, CORRELATION_ID_HEADER).unwrap_or_else(|| request_id.clone());
    let context = RequestContext::new(request_id.clone(), correlation_id.clone());
    let operation = request.uri().path().to_string();
    let mut span = config
        .observer
        .start(&context, &operation, request.method().as_str());

    request.extensions_mut().insert(context);
    let mut response = next.run(request).await;
    insert_header(response.headers_mut(), REQUEST_ID_HEADER, &request_id);
    insert_header(
        response.headers_mut(),
        CORRELATION_ID_HEADER,
        &correlation_id,
    );
    span.end(response.status());
    response
}

fn header_value(request: &Request, name: &'static str) -> Option<String> {
    request
        .headers()
        .get(name)
        .and_then(|value| value.to_str().ok())
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToOwned::to_owned)
}

fn header_or_generate(request: &Request, name: &'static str) -> String {
    header_value(request, name).unwrap_or_else(generate_request_id)
}

fn insert_header(headers: &mut axum::http::HeaderMap, name: &'static str, value: &str) {
    if let Ok(value) = HeaderValue::from_str(value) {
        headers.insert(name, value);
    }
}

pub fn generate_request_id() -> String {
    let sequence = REQUEST_ID_SEQUENCE.fetch_add(1, Ordering::Relaxed);
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or_default();
    format!("req-{nanos:x}-{sequence:x}")
}

#[cfg(test)]
async fn request_context_handler(
    axum::extract::Extension(context): axum::extract::Extension<RequestContext>,
) -> String {
    format!("{}:{}", context.request_id(), context.correlation_id())
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ContractError {
    EmptyId { kind: &'static str },
    InvalidCurrencyCode(String),
    InvalidTimeWindow,
    EmptyEventType,
    InvalidSchemaVersion,
}

impl fmt::Display for ContractError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::EmptyId { kind } => write!(f, "{kind} must not be empty"),
            Self::InvalidCurrencyCode(code) => {
                write!(
                    f,
                    "currency code must be three uppercase ASCII letters: {code}"
                )
            }
            Self::InvalidTimeWindow => write!(f, "time window end must be after start"),
            Self::EmptyEventType => write!(f, "event type must not be empty"),
            Self::InvalidSchemaVersion => write!(f, "schema version must be greater than zero"),
        }
    }
}

impl std::error::Error for ContractError {}

macro_rules! id_type {
    ($name:ident) => {
        #[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
        pub struct $name(String);

        impl $name {
            pub fn new(value: impl Into<String>) -> Result<Self, ContractError> {
                let value = value.into();
                if value.trim().is_empty() {
                    return Err(ContractError::EmptyId {
                        kind: stringify!($name),
                    });
                }
                Ok(Self(value))
            }

            pub fn as_str(&self) -> &str {
                &self.0
            }
        }

        impl fmt::Display for $name {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str(&self.0)
            }
        }

        impl FromStr for $name {
            type Err = ContractError;

            fn from_str(value: &str) -> Result<Self, Self::Err> {
                Self::new(value)
            }
        }

        impl TryFrom<&str> for $name {
            type Error = ContractError;

            fn try_from(value: &str) -> Result<Self, Self::Error> {
                Self::new(value)
            }
        }
    };
}

id_type!(PlaceRef);
id_type!(TravelerRef);
id_type!(SegmentRef);
id_type!(EventId);
id_type!(CausationId);
id_type!(CorrelationId);

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct CurrencyCode(String);

impl CurrencyCode {
    pub fn new(code: impl Into<String>) -> Result<Self, ContractError> {
        let code = code.into();
        let valid = code.len() == 3 && code.bytes().all(|b| b.is_ascii_uppercase());
        if !valid {
            return Err(ContractError::InvalidCurrencyCode(code));
        }
        Ok(Self(code))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for CurrencyCode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl FromStr for CurrencyCode {
    type Err = ContractError;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        Self::new(value)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Money {
    amount_minor: i64,
    currency: CurrencyCode,
}

impl Money {
    pub fn new_minor(amount_minor: i64, currency: CurrencyCode) -> Self {
        Self {
            amount_minor,
            currency,
        }
    }

    pub fn amount_minor(&self) -> i64 {
        self.amount_minor
    }

    pub fn currency(&self) -> &CurrencyCode {
        &self.currency
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct UnixMillis(u64);

impl UnixMillis {
    pub fn new(value: u64) -> Self {
        Self(value)
    }

    pub fn as_u64(self) -> u64 {
        self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TimeWindow {
    start: UnixMillis,
    end: UnixMillis,
}

impl TimeWindow {
    pub fn new(start: UnixMillis, end: UnixMillis) -> Result<Self, ContractError> {
        if end <= start {
            return Err(ContractError::InvalidTimeWindow);
        }
        Ok(Self { start, end })
    }

    pub fn start(self) -> UnixMillis {
        self.start
    }

    pub fn end(self) -> UnixMillis {
        self.end
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EventEnvelope {
    event_id: EventId,
    event_type: String,
    schema_version: u32,
    occurred_at: UnixMillis,
    correlation_id: CorrelationId,
    causation_id: Option<CausationId>,
    traceparent: Option<String>,
}

impl EventEnvelope {
    pub fn new(
        event_id: EventId,
        event_type: impl Into<String>,
        schema_version: u32,
        occurred_at: UnixMillis,
        correlation_id: CorrelationId,
        causation_id: Option<CausationId>,
    ) -> Result<Self, ContractError> {
        let event_type = event_type.into();
        if event_type.trim().is_empty() {
            return Err(ContractError::EmptyEventType);
        }
        if schema_version == 0 {
            return Err(ContractError::InvalidSchemaVersion);
        }
        Ok(Self {
            event_id,
            event_type,
            schema_version,
            occurred_at,
            correlation_id,
            causation_id,
            traceparent: None,
        })
    }

    pub fn event_id(&self) -> &EventId {
        &self.event_id
    }

    pub fn event_type(&self) -> &str {
        &self.event_type
    }

    pub fn schema_version(&self) -> u32 {
        self.schema_version
    }

    pub fn occurred_at(&self) -> UnixMillis {
        self.occurred_at
    }

    pub fn correlation_id(&self) -> &CorrelationId {
        &self.correlation_id
    }

    pub fn causation_id(&self) -> Option<&CausationId> {
        self.causation_id.as_ref()
    }

    pub fn traceparent(&self) -> Option<&str> {
        self.traceparent.as_deref()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn skeleton_profile_matches_domain() {
        let profile = profile();
        assert_eq!(profile.service_id, "shared-kernel");
        assert_eq!(profile.domain, "Shared Kernel / Platform");
        assert_eq!(profile.work_packages, &["REQ-003"]);
        assert_eq!(health(), "ok");
    }

    #[test]
    fn axum_router_can_be_constructed() {
        let _router = router();
    }

    #[tokio::test]
    async fn standard_runtime_endpoints_are_exposed() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        for path in [
            "/health",
            "/healthz",
            "/live",
            "/livez",
            "/ready",
            "/readyz",
            "/metadata",
        ] {
            let response = router()
                .oneshot(Request::builder().uri(path).body(Body::empty()).unwrap())
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::OK, "{path}");
            assert!(!response.headers()[REQUEST_ID_HEADER].is_empty());
            assert!(!response.headers()[CORRELATION_ID_HEADER].is_empty());
        }
    }

    #[tokio::test]
    async fn request_and_correlation_ids_are_propagated_to_handlers() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let app = apply_runtime(
            Router::new().route("/context", get(request_context_handler)),
            RuntimeConfig::new(profile()),
        );
        let response = app
            .oneshot(
                Request::builder()
                    .uri("/context")
                    .header(REQUEST_ID_HEADER, "req-in")
                    .header(CORRELATION_ID_HEADER, "corr-in")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.headers()[REQUEST_ID_HEADER], "req-in");
        assert_eq!(response.headers()[CORRELATION_ID_HEADER], "corr-in");
        let body = axum::body::to_bytes(response.into_body(), usize::MAX)
            .await
            .unwrap();
        assert_eq!(&body[..], b"req-in:corr-in");
    }

    #[tokio::test]
    async fn observer_seam_is_opt_in() {
        use axum::body::Body;
        use axum::http::Request;
        use std::sync::Mutex;
        use tower::ServiceExt;

        #[derive(Default)]
        struct RecordingObserver {
            operations: Mutex<Vec<String>>,
        }

        impl Observer for RecordingObserver {
            fn start(
                &self,
                context: &RequestContext,
                operation: &str,
                _method: &str,
            ) -> Box<dyn Span> {
                self.operations.lock().unwrap().push(format!(
                    "{}:{}",
                    operation,
                    context.request_id()
                ));
                Box::new(NoopSpan)
            }
        }

        let observer = Arc::new(RecordingObserver::default());
        let app = router_with_config(RuntimeConfig::new(profile()).with_observer(observer.clone()));
        let response = app
            .oneshot(
                Request::builder()
                    .uri("/health")
                    .header(REQUEST_ID_HEADER, "req-observed")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(
            observer.operations.lock().unwrap().as_slice(),
            &["/health:req-observed"]
        );
    }

    #[test]
    fn id_refs_reject_blank_values() {
        assert_eq!(
            TravelerRef::new("   ").unwrap_err(),
            ContractError::EmptyId {
                kind: "TravelerRef"
            }
        );
        assert_eq!(SegmentRef::new("seg-123").unwrap().as_str(), "seg-123");
    }

    #[test]
    fn money_requires_iso_like_currency_code() {
        let money = Money::new_minor(12345, CurrencyCode::new("CNY").unwrap());
        assert_eq!(money.amount_minor(), 12345);
        assert_eq!(money.currency().as_str(), "CNY");
        assert!(CurrencyCode::new("cny").is_err());
        assert!(CurrencyCode::new("CNYY").is_err());
    }

    #[test]
    fn time_window_requires_forward_time() {
        let start = UnixMillis::new(1_800_000_000_000);
        let end = UnixMillis::new(1_800_003_600_000);
        let window = TimeWindow::new(start, end).unwrap();
        assert_eq!(window.start(), start);
        assert_eq!(window.end(), end);
        assert_eq!(
            TimeWindow::new(end, start).unwrap_err(),
            ContractError::InvalidTimeWindow
        );
    }

    #[test]
    fn event_envelope_carries_trace_ids() {
        let envelope = EventEnvelope::new(
            EventId::new("evt-1").unwrap(),
            "JourneyOrderCreated",
            1,
            UnixMillis::new(1_800_000_000_000),
            CorrelationId::new("corr-1").unwrap(),
            Some(CausationId::new("cmd-1").unwrap()),
        )
        .unwrap();

        assert_eq!(envelope.event_id().as_str(), "evt-1");
        assert_eq!(envelope.event_type(), "JourneyOrderCreated");
        assert_eq!(envelope.schema_version(), 1);
        assert_eq!(envelope.correlation_id().as_str(), "corr-1");
        assert_eq!(envelope.causation_id().unwrap().as_str(), "cmd-1");
    }
}
