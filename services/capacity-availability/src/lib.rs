pub mod adapters;
pub mod api;
pub mod application;
pub mod domain;
pub mod ports;

use axum::Router;
use serde::Serialize;
use shared_kernel::{OpenTelemetryObserver, RuntimeConfig, apply_runtime, router_with_config};

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
    let publisher = adapters::messaging::redis_publisher::RedisEventPublisher::new(&redis_url)
        .await
        .expect("failed to initialize Redis event publisher");
    let subscriber = adapters::messaging::redis_subscriber::RedisEventSubscriber::new(&redis_url)
        .await
        .expect("failed to initialize Redis event subscriber");

    let service = std::sync::Arc::new(CapacityService::new(std::sync::Arc::new(publisher)));
    let handler_service = service.clone();
    subscriber
        .subscribe(
            &[],
            CONSUMER_GROUP,
            &consumer_name(),
            Box::new(move |envelope| handler_service.handle_inbound_event(envelope)),
        )
        .expect("failed to start Redis event subscriber");

    router_with_service(service)
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
fn consumer_name() -> String {
    std::env::var("HOSTNAME")
        .or_else(|_| std::env::var("CONSUMER_NAME"))
        .unwrap_or_else(|_| format!("capacity-availability-{}", std::process::id()))
}

pub fn apply_service_runtime(router: Router) -> Router {
    apply_runtime(router, runtime_config())
}
