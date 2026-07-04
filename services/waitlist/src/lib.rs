use axum::Router;
use serde::Serialize;
use shared_kernel::{OpenTelemetryObserver, RuntimeConfig, apply_runtime, router_with_config};

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
        phase: "future-scope",
        work_packages: &[],
        owns: &["WaitlistRequest", "QueuePolicy", "FulfillmentWindow"],
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

pub fn router() -> Router {
    router_with_config(runtime_config())
}

pub fn apply_service_runtime(router: Router) -> Router {
    apply_runtime(router, runtime_config())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn skeleton_profile_matches_domain() {
        let profile = profile();
        assert_eq!(profile.service_id, "waitlist");
        assert_eq!(profile.domain, "Waitlist");
        assert_eq!(health(), "ok");
    }

    #[test]
    fn axum_router_can_be_constructed() {
        let _router = router();
    }

    #[tokio::test]
    async fn standard_runtime_endpoints_and_request_ids_are_available() {
        use axum::body::Body;
        use axum::http::{Request, StatusCode};
        use shared_kernel::{CORRELATION_ID_HEADER, REQUEST_ID_HEADER};
        use tower::ServiceExt;

        for path in [
            "/health",
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
}
