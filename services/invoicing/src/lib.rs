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
        service_id: "invoicing",
        domain: "Invoicing",
        language: "rust",
        phase: "phase-1-activation",
        work_packages: &["REQ-143"],
        owns: &["InvoiceTitle", "EInvoice", "RedFlush", "ItineraryReceipt"],
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
        .with_http_metrics(rust_kit::metrics::HttpServerMetrics::recorder_from_env(
            profile().service_id,
        ))
}
pub fn apply_service_runtime(router: Router) -> Router {
    apply_runtime(router, runtime_config())
}
pub async fn router() -> Router {
    let service = Arc::new(
        PostgresInvoicingService::from_env()
            .await
            .expect("failed to initialize Postgres invoicing storage"),
    );
    router_with_postgres_state(service)
}
pub async fn build_runtime() -> Result<(Router, JoinHandle<()>), SubscribeFailed> {
    let _otel = rust_kit::metrics::init_telemetry_from_env(profile().service_id)
        .map_err(|e| SubscribeFailed(e.to_string()))?;
    let redis_url = std::env::var("REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379".into());
    let service = Arc::new(
        PostgresInvoicingService::from_env()
            .await
            .map_err(|e| SubscribeFailed(e.to_string()))?,
    );
    rust_kit::storage::spawn_outbox_relay(service.pool().clone(), redis_url);
    let streams = adapters::messaging::subscribed_streams();
    let consumer = std::env::var("HOSTNAME")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| format!("invoicing-{}", uuid::Uuid::now_v7()));
    let hs = service.clone();
    let handle = tokio::spawn(async move {
        let mut tasks = tokio::task::JoinSet::new();
        for (index, stream) in streams.into_iter().enumerate() {
            // Eight workers on the two streams that carry the facts an invoice
            // is built from. Measured at the cycle's burst: the group's lag on
            // `events:finance-settlement` reached 3676 entries with one consumer,
            // so every amount-basis read answered 404 -- not a missing
            // recognition, because finance-settlement had the capture and the
            // recognition both recorded for the same order.
            //
            // Four was enough there and is not enough at the raised burst, where
            // the two projections an amount basis is read against drift apart:
            // at revision 446's peak the group's oldest undelivered entry on
            // `events:journey-order` reached 30.9 seconds while the finance stream
            // stayed at zero, so the `amounts` row existed and the `orders` row
            // that has to carry the same expected total did not. That is the
            // 404 "complete amount basis not yet available" -- 150 of the
            // window's polls, and 14 failed journeys at the peak minute.
            let workers = if stream == adapters::messaging::journey_order_stream()
                || stream == adapters::messaging::finance_stream()
            {
                8
            } else {
                1
            };
            for worker in 0..workers {
                let hs = hs.clone();
                let stream = stream.clone();
                let consumer = format!("{consumer}-{index}-{worker}");
                tasks.spawn(async move {
                    let subscriber = rust_kit::messaging::redis_runtime::RedisEventSubscriber::from_env()
                        .expect("Redis subscriber configuration")
                        .with_event_filter(PostgresInvoicingService::handles_event);
                    subscriber.subscribe(
                        vec![stream],
                        "invoicing".into(),
                        consumer,
                        Box::new(move |e| {
                            let svc = hs.clone();
                            Box::pin(async move { svc.handle_subscribed_event(e).await })
                        }),
                    )
                    .await
                    .expect("invoicing Redis subscriber stopped");
                });
            }
        }
        while let Some(result) = tasks.join_next().await { result.expect("invoicing event stream task failed"); }
    });
    Ok((router_with_postgres_state(service), handle))
}
pub fn router_with_state<S: InvoicingApi + 'static>(service: Arc<S>) -> Router {
    apply_service_runtime(router_with_config(runtime_config()).merge(routes(service)))
}
pub fn router_with_postgres_state(service: Arc<PostgresInvoicingService>) -> Router {
    let metadata = serde_json::to_value(metadata()).unwrap_or_else(|_| json!({}));
    let standard = Router::new()
        .route("/api/v1/internal/invoice-amount-bases/{order_id}/rebuild",post(rebuild_amount_basis))
        .route("/api/v1/internal/red-flush-reconciliations/{case_id}",post(reconcile_red_flush))
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

async fn rebuild_amount_basis(
    Extension(service): Extension<Arc<PostgresInvoicingService>>,
    Extension(context): Extension<shared_kernel::RequestContext>,
    axum::extract::Path(order_id): axum::extract::Path<String>,
) -> axum::response::Response {
    use axum::response::IntoResponse;
    match service.rebuild_amount_basis(&order_id,context.correlation_id()).await {
        Ok(basis) => Json(basis).into_response(),
        Err(error) => api_error_response(error,crate::utils::canonical_corr(context.correlation_id())),
    }
}

async fn reconcile_red_flush(
    Extension(service):Extension<Arc<PostgresInvoicingService>>,
    Extension(context):Extension<shared_kernel::RequestContext>,
    axum::extract::Path(case_id):axum::extract::Path<String>,
) -> axum::response::Response {
    use axum::response::IntoResponse;
    match service.reconcile_red_flush(&case_id,context.correlation_id()).await {
        Ok(())=>Json(json!({"caseId":case_id,"status":"RECONCILED"})).into_response(),
        Err(error)=>api_error_response(error,crate::utils::canonical_corr(context.correlation_id())),
    }
}
pub(crate) fn routes<S: InvoicingApi + 'static>(service: Arc<S>) -> Router {
    Router::new()
        .route(
            "/api/v1/invoice-titles",
            post(create_title::<S>).get(list_titles::<S>),
        )
        .route(
            "/api/v1/invoice-titles/{title_id}",
            get(get_title::<S>)
                .put(update_title::<S>)
                .delete(deactivate_title::<S>),
        )
        .route(
            "/api/v1/invoice-titles/{title_id}/set-default",
            post(set_default_title::<S>),
        )
        .route("/api/v1/e-invoice-requests", post(request_invoice::<S>))
        .route("/api/v1/invoice-amount-bases/{order_id}", get(get_amount_basis::<S>))
        .route(
            "/api/v1/e-invoice-requests/{invoice_request_id}",
            get(get_request::<S>),
        )
        .route("/api/v1/e-invoices", get(list_invoices::<S>))
        .route("/api/v1/e-invoices/{e_invoice_id}", get(get_invoice::<S>))
        .route("/api/v1/red-flushes", get(list_red_flushes::<S>))
        .route(
            "/api/v1/red-flushes/{red_flush_id}",
            get(get_red_flush::<S>),
        )
        .route(
            "/api/v1/itinerary-receipts/generated",
            get(generate_itinerary::<S>),
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
    Extension(service): Extension<Arc<PostgresInvoicingService>>,
) -> (StatusCode, Json<ProbeResponse>) {
    if service.is_ready().await {
        (StatusCode::OK, Json(ProbeResponse { status: "ready" }))
    } else {
        log::warn!("invoicing readiness probe returning 503: postgres storage is not ready");
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
pub use adapters::storage::PostgresInvoicingService;
mod utils;
pub use utils::SubscribeFailed;
pub fn init_logging() {
    use std::io::Write;
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
#[cfg(test)]
mod tests;
