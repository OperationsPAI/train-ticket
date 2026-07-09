use axum::{
    Json, Router,
    extract::Extension,
    http::StatusCode,
    routing::{get, post},
};
use rust_kit::messaging::AsyncEventSubscriber;
use serde::Serialize;
use serde_json::json;
use shared_kernel::{OpenTelemetryObserver, RuntimeConfig, apply_runtime, router_with_config};
use std::sync::Arc;
use tokio::task::JoinHandle;

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
        service_id: "seat-assignment",
        domain: "Seat Assignment",
        language: "rust",
        phase: "phase-1-activation",
        work_packages: &["REQ-140"],
        owns: &[
            "SeatMap",
            "SeatAllocation",
            "AdjacencyGroup",
            "BerthPreference",
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
pub fn apply_service_runtime(router: Router) -> Router {
    apply_runtime(router, runtime_config())
}
pub async fn router() -> Router {
    let service = Arc::new(
        PostgresSeatAssignmentService::from_env()
            .await
            .expect("failed to initialize Postgres seat-assignment storage"),
    );
    router_with_postgres_state(service)
}
pub async fn build_runtime() -> Result<(Router, JoinHandle<()>), SubscribeFailed> {
    let _otel = rust_kit::otel::init_from_env(profile().service_id)
        .map_err(|e| SubscribeFailed(e.to_string()))?;
    let redis_url = std::env::var("REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379".into());
    let service = Arc::new(
        PostgresSeatAssignmentService::from_env()
            .await
            .map_err(|e| SubscribeFailed(e.to_string()))?,
    );
    rust_kit::storage::spawn_outbox_relay(service.pool().clone(), redis_url);
    let subscriber = rust_kit::messaging::redis_runtime::RedisEventSubscriber::from_env()?;
    let streams = adapters::messaging::subscribed_streams();
    let consumer = std::env::var("HOSTNAME")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| format!("seat-assignment-{}", uuid::Uuid::now_v7()));
    let hs = service.clone();
    let handle = tokio::spawn(async move {
        subscriber
            .subscribe(
                streams,
                "seat-assignment".into(),
                consumer,
                Box::new(move |e| {
                    let svc = hs.clone();
                    Box::pin(async move { svc.handle_subscribed_event(e).await })
                }),
            )
            .await
            .expect("seat-assignment Redis subscriber stopped")
    });
    Ok((router_with_postgres_state(service), handle))
}
pub fn router_with_state<S: SeatAssignmentApi + 'static>(service: Arc<S>) -> Router {
    apply_service_runtime(router_with_config(runtime_config()).merge(routes(service)))
}
pub fn router_with_postgres_state(service: Arc<PostgresSeatAssignmentService>) -> Router {
    let metadata = serde_json::to_value(metadata()).unwrap_or_else(|_| json!({}));
    let standard = Router::new()
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
        .layer(Extension(service.clone()));
    apply_service_runtime(standard.merge(routes(service)))
}
pub(crate) fn routes<S: SeatAssignmentApi + 'static>(service: Arc<S>) -> Router {
    Router::new()
        .route(
            "/api/v1/seat-maps",
            post(create_map::<S>).get(list_maps::<S>),
        )
        .route("/api/v1/seat-maps/{seat_map_id}", get(get_map::<S>))
        .route(
            "/api/v1/seat-maps/{seat_map_id}/publish",
            post(publish_map::<S>),
        )
        .route(
            "/api/v1/seat-maps/{seat_map_id}/retire",
            post(retire_map::<S>),
        )
        .route(
            "/api/v1/seat-maps/{seat_map_id}/seat-units/{seat_unit_ref}/mark-unavailable",
            post(mark_unavailable::<S>),
        )
        .route(
            "/api/v1/seat-maps/{seat_map_id}/seat-units/{seat_unit_ref}/reopen",
            post(reopen::<S>),
        )
        .route("/api/v1/internal/seat-allocations", post(allocate::<S>))
        .route("/api/v1/seat-allocations", get(list_allocations::<S>))
        .route(
            "/api/v1/seat-allocations/{seat_allocation_id}",
            get(get_allocation::<S>),
        )
        .with_state(ApiState { service })
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
async fn postgres_ready_handler(
    Extension(service): Extension<Arc<PostgresSeatAssignmentService>>,
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
mod domain;
pub use domain::*;
mod application;
pub use application::*;
pub mod adapters;
pub use adapters::http::*;
pub use adapters::storage::PostgresSeatAssignmentService;
mod utils;
pub use utils::SubscribeFailed;
pub fn init_logging() {
    use std::io::Write;
    let mut builder =
        env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info"));
    builder
        .target(env_logger::Target::Stdout)
        .format(|buf, record| {
            writeln!(
                buf,
                "{} {} {} - {}",
                rust_kit::messaging::now_rfc3339_utc(),
                record.level(),
                record.target(),
                record.args()
            )
        });
    let _ = builder.try_init();
}
#[cfg(test)]
mod tests;
