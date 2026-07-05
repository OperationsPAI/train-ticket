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
pub fn router() -> Router {
    let service = std::sync::Arc::new(CapacityService::new(std::sync::Arc::new(
        adapters::messaging::InMemoryEventPublisher::new(),
    )));
    let api_router = api::router(service.clone());
    let standard_router = router_with_config(runtime_config());
    let full_router = axum::Router::new().merge(standard_router).merge(api_router);
    apply_runtime(full_router, runtime_config())
}

pub fn apply_service_runtime(router: Router) -> Router {
    apply_runtime(router, runtime_config())
}
