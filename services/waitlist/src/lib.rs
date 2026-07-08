use std::sync::Arc;
use std::time::Duration;

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
        service_id: "waitlist",
        domain: "Waitlist",
        language: "rust",
        phase: "phase-1-activation",
        work_packages: &["REQ-107"],
        owns: &["WaitlistRequest", "WaitlistQueue", "FulfillmentWindow"],
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
        PostgresWaitlistService::from_env()
            .await
            .expect("failed to initialize Postgres waitlist storage"),
    );
    router_with_postgres_state(service)
}

pub async fn build_runtime() -> Result<(Router, JoinHandle<()>), SubscribeFailed> {
    let _otel = rust_kit::otel::init_from_env(profile().service_id)
        .map_err(|e| SubscribeFailed(e.to_string()))?;
    let redis_url =
        std::env::var("REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379".to_string());
    let service = Arc::new(
        PostgresWaitlistService::from_env()
            .await
            .map_err(|e| SubscribeFailed(e.to_string()))?,
    );
    rust_kit::storage::spawn_outbox_relay(service.pool().clone(), redis_url);
    let expiry_service = service.clone();
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(Duration::from_secs(30));
        loop {
            interval.tick().await;
            let _ = expiry_service
                .expire_due(current_rfc3339(), rust_kit::messaging::correlation_id())
                .await;
        }
    });
    let subscriber = rust_kit::messaging::redis_runtime::RedisEventSubscriber::from_env()?;
    let streams = adapters::messaging::subscribed_streams();
    let consumer_name = std::env::var("HOSTNAME")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| format!("waitlist-{}", uuid::Uuid::now_v7()));
    let handler_service = service.clone();
    let handle = tokio::spawn(async move {
        subscriber
            .subscribe(
                streams,
                "waitlist".to_string(),
                consumer_name,
                Box::new(move |envelope| {
                    let service = handler_service.clone();
                    Box::pin(async move { service.handle_subscribed_event(envelope).await })
                }),
            )
            .await
            .expect("waitlist Redis subscriber stopped");
    });
    Ok((router_with_postgres_state(service), handle))
}

pub fn router_with_state<S: WaitlistApi + 'static>(service: Arc<S>) -> Router {
    apply_service_runtime(router_with_config(runtime_config()).merge(waitlist_routes(service)))
}
pub fn router_with_postgres_state(service: Arc<PostgresWaitlistService>) -> Router {
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
    apply_service_runtime(standard.merge(waitlist_routes(service)))
}
pub(crate) fn waitlist_routes<S: WaitlistApi + 'static>(service: Arc<S>) -> Router {
    Router::new()
        .route(
            "/api/v1/waitlist-requests",
            post(create_waitlist::<S>).get(list_waitlist::<S>),
        )
        .route(
            "/api/v1/waitlist-requests/{waitlist_request_id}",
            get(get_waitlist::<S>),
        )
        .route(
            "/api/v1/waitlist-requests/{waitlist_request_id}/cancel",
            post(cancel_waitlist::<S>),
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
    Extension(service): Extension<Arc<PostgresWaitlistService>>,
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
pub use adapters::storage::PostgresWaitlistService;
mod utils;
pub use utils::SubscribeFailed;
use utils::current_rfc3339;

include!("tests.rs");
