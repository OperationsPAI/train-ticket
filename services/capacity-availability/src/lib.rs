pub mod adapters;
pub mod api;
pub mod application;
pub mod domain;
pub mod ports;

use axum::Router;
#[cfg(feature = "redis-impl")]
use axum::{Json, http::StatusCode, routing::get};
use serde::Serialize;
use shared_kernel::{OpenTelemetryObserver, RuntimeConfig, apply_runtime, router_with_config};
use std::io::Write;

#[cfg(feature = "redis-impl")]
pub use adapters::storage::PostgresCapacityService;
pub use application::CapacityService;
pub use domain::*;
pub use ports::*;

#[cfg(feature = "redis-impl")]
const DEFAULT_REDIS_URL: &str = "redis://localhost:6379";

#[cfg(feature = "redis-impl")]
const CONSUMER_GROUP: &str = "capacity-availability";

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ServiceProfile {
    pub service_id: &'static str,
    pub domain: &'static str,
    pub language: &'static str,
    pub phase: &'static str,
    pub requirement: &'static str,
    pub owns: &'static [&'static str],
}

pub fn profile() -> ServiceProfile {
    ServiceProfile {
        service_id: "capacity-availability",
        domain: "Capacity & Availability",
        language: "rust",
        phase: "phase-1-core",
        requirement: "REQ-006 Capacity & Availability domain foundation",
        owns: &[
            "InventoryPool",
            "StationInterval",
            "CapacityHold",
            "AvailabilitySnapshot",
        ],
    }
}

pub fn health() -> &'static str {
    "ok"
}

pub fn metadata() -> ServiceProfile {
    profile()
}

pub fn runtime_config() -> RuntimeConfig {
    RuntimeConfig::from_metadata(metadata())
        .with_observer(OpenTelemetryObserver::from_env(profile().service_id))
}

/// Construct the full router with API routes and standard runtime middleware.
#[cfg(not(feature = "redis-impl"))]
pub fn router() -> Router {
    router_with_service(std::sync::Arc::new(CapacityService::new(
        std::sync::Arc::new(adapters::messaging::InMemoryEventPublisher::new()),
    )))
}

/// Construct the full router with Redis Streams publisher/subscriber wiring.
#[cfg(feature = "redis-impl")]
pub async fn build_runtime() -> Router {
    use crate::ports::EventSubscriber;

    let redis_url = std::env::var("REDIS_URL").unwrap_or_else(|_| DEFAULT_REDIS_URL.to_string());
    log::info!("capacity-availability initializing Redis subscriber");
    let subscriber = adapters::messaging::redis_subscriber::RedisEventSubscriber::new(&redis_url)
        .await
        .expect("failed to initialize Redis event subscriber");

    log::info!("capacity-availability initializing Postgres storage");
    let service = std::sync::Arc::new(
        adapters::storage::PostgresCapacityService::from_env()
            .await
            .expect("failed to initialize Postgres capacity storage"),
    );
    rust_kit::storage::spawn_outbox_relay(service.pool().clone(), redis_url.clone());
    let selected_streams = adapters::messaging::redis_subscriber::default_subscription_streams();
    log::info!(
        "capacity-availability starting Redis subscriber group={CONSUMER_GROUP} streams={}",
        selected_streams.join(",")
    );
    let handler_service = service.clone();
    subscriber
        .subscribe(
            &selected_streams,
            CONSUMER_GROUP,
            &consumer_name(),
            Box::new(move |envelope| {
                let service = handler_service.clone();
                tokio::task::block_in_place(|| {
                    tokio::runtime::Handle::current()
                        .block_on(service.handle_inbound_event(envelope))
                })
            }),
        )
        .expect("failed to start Redis event subscriber");
    log::info!("capacity-availability Redis subscriber started");

    router_with_postgres_service(service)
}

/// Synchronous router construction is reserved for tests when redis-impl is enabled.
#[cfg(all(feature = "redis-impl", test))]
pub fn router() -> Router {
    router_with_service(std::sync::Arc::new(CapacityService::new(
        std::sync::Arc::new(adapters::messaging::InMemoryEventPublisher::new()),
    )))
}

/// Construct a router for tests or alternate bootstraps that provide their own application service.
pub fn router_with_service(service: std::sync::Arc<CapacityService>) -> Router {
    let api_router = api::router(service);
    let standard_router = router_with_config(runtime_config());
    let full_router = axum::Router::new().merge(standard_router).merge(api_router);
    apply_runtime(full_router, runtime_config())
}

#[cfg(feature = "redis-impl")]
pub fn router_with_postgres_service(
    service: std::sync::Arc<adapters::storage::PostgresCapacityService>,
) -> Router {
    let api_router = api::postgres_router(service.clone());
    let metadata = serde_json::to_value(metadata()).unwrap_or_else(|_| serde_json::json!({}));
    let standard_router = Router::new()
        .route("/health", get(health_handler))
        .route("/healthz", get(health_handler))
        .route("/live", get(live_handler))
        .route("/livez", get(live_handler))
        .route("/ready", get(postgres_ready_handler))
        .route("/readyz", get(postgres_ready_handler))
        .route(
            "/metadata",
            get(move || {
                let metadata = metadata.clone();
                async move { Json(metadata) }
            }),
        )
        .layer(axum::extract::Extension(service));
    let full_router = axum::Router::new().merge(standard_router).merge(api_router);
    apply_runtime(full_router, runtime_config())
}

#[cfg(feature = "redis-impl")]
#[derive(Debug, Serialize)]
struct ProbeResponse {
    status: &'static str,
}

#[cfg(feature = "redis-impl")]
async fn health_handler() -> Json<ProbeResponse> {
    Json(ProbeResponse { status: health() })
}

#[cfg(feature = "redis-impl")]
async fn live_handler() -> Json<ProbeResponse> {
    Json(ProbeResponse { status: "alive" })
}

#[cfg(feature = "redis-impl")]
async fn postgres_ready_handler(
    axum::extract::Extension(service): axum::extract::Extension<
        std::sync::Arc<adapters::storage::PostgresCapacityService>,
    >,
) -> (StatusCode, Json<ProbeResponse>) {
    if service.is_ready().await {
        (StatusCode::OK, Json(ProbeResponse { status: "ready" }))
    } else {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(ProbeResponse {
                status: "not_ready",
            }),
        )
    }
}

#[cfg(feature = "redis-impl")]
fn consumer_name() -> String {
    std::env::var("HOSTNAME")
        .or_else(|_| std::env::var("CONSUMER_NAME"))
        .unwrap_or_else(|_| format!("capacity-availability-{}", std::process::id()))
}

pub fn init_logging() {
    let mut builder =
        env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info"));
    builder
        .target(env_logger::Target::Stdout)
        // trace_id/span_id are injected here rather than at each `log::` call
        // site: the formatter is the one place every line passes through, and it
        // is the `log` crate's equivalent of a logging pattern. `log_fields()`
        // renders nothing at all when no span is active, so startup and shutdown
        // lines are unchanged, and it never renders an all-zero id -- that would
        // look real and join every unrelated line together.
        .format(|buf, record| {
            writeln!(
                buf,
                "{} {} {} {}- {}",
                rust_kit::messaging::now_rfc3339_utc(),
                record.level(),
                record.target(),
                rust_kit::trace_logging::log_fields(),
                record.args()
            )
        });
    let _ = builder.try_init();
}

pub fn apply_service_runtime(router: Router) -> Router {
    apply_runtime(router, runtime_config())
}
