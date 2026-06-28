use axum::{routing::get, Router};

#[derive(Debug, Clone, PartialEq, Eq)]
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
        service_id: "capacity-availability",
        domain: "Capacity & Availability",
        language: "rust",
        phase: "phase-1-core",
        work_packages: &["WP-05"],
        owns: &["InventoryPool", "CapacityHold", "Quota"],
    }
}

pub fn health() -> &'static str {
    "ok"
}

pub fn router() -> Router {
    Router::new().route("/health", get(health_handler))
}

async fn health_handler() -> &'static str {
    health()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn skeleton_profile_matches_domain() {
        let profile = profile();
        assert_eq!(profile.service_id, "capacity-availability");
        assert_eq!(profile.domain, "Capacity & Availability");
        assert_eq!(health(), "ok");
    }

    #[test]
    fn axum_router_can_be_constructed() {
        let _router = router();
    }
}
