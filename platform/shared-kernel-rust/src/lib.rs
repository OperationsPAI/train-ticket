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
        service_id: "shared-kernel",
        domain: "Shared Kernel / Platform",
        language: "rust",
        phase: "phase-1-foundation",
        work_packages: &["WP-01"],
        owns: &["reference implementation for IDs, value objects, and event envelope", "language-neutral contract source before code generation exists"],
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
        assert_eq!(profile.service_id, "shared-kernel");
        assert_eq!(profile.domain, "Shared Kernel / Platform");
        assert_eq!(health(), "ok");
    }

    #[test]
    fn axum_router_can_be_constructed() {
        let _router = router();
    }
}
