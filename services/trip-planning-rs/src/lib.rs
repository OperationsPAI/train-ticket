pub mod api;
pub mod application;
pub mod domain;
pub mod plan_index;

use axum::Router;
use axum::{Json, routing::get};
use serde::Serialize;
use shared_kernel::{RuntimeConfig, apply_runtime, OpenTelemetryObserver};
use std::io::Write;
use std::sync::Arc;

pub use application::AppState;
pub use plan_index::PlanIndex;

#[cfg(feature = "redis-impl")]
const DEFAULT_REDIS_URL: &str = "redis://localhost:6379";

#[cfg(feature = "redis-impl")]
const CONSUMER_GROUP: &str = "trip-planning";

#[cfg(feature = "redis-impl")]
const SUBSCRIPTION_STREAMS: &[&str] = &[
    "events:place-network",
    "events:service-plan",
    "events:capacity-availability",
];

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ServiceProfile {
    pub service_id: &'static str,
    pub domain: &'static str,
    pub language: &'static str,
    pub phase: &'static str,
    pub owns: &'static [&'static str],
}

pub fn profile() -> ServiceProfile {
    ServiceProfile {
        service_id: "trip-planning",
        domain: "Trip Planning",
        language: "rust",
        phase: "phase-1-search-foundation",
        owns: &[
            "TripIntent validation",
            "Itinerary candidates",
            "SearchResult ranking explanations",
            "Non-authoritative PriceHint and AvailabilityHint snapshots",
        ],
    }
}

pub fn health() -> &'static str {
    "ok"
}

pub fn runtime_config() -> RuntimeConfig {
    RuntimeConfig::from_metadata(shared_kernel::metadata())
        .with_observer(OpenTelemetryObserver::from_env(profile().service_id))
}

/// Construct the full router with Redis Streams publisher/subscriber wiring.
#[cfg(feature = "redis-impl")]
pub async fn build_runtime() -> Router {
    use rust_kit::messaging::AsyncEventSubscriber;

    let redis_url = std::env::var("REDIS_URL").unwrap_or_else(|_| DEFAULT_REDIS_URL.to_string());

    log::info!("trip-planning initializing Postgres storage");
    // Trip Planning MUST have persistence: it publishes ItineraryProposed via the
    // transactional outbox, and running degraded (pool = None) silently drops those
    // events, collapsing the downstream offer/purchase funnel. Retry a transient
    // startup outage, then fail hard so Kubernetes restarts the pod rather than
    // leaving it permanently degraded.
    let storage = {
        let mut attempt = 0u32;
        loop {
            match rust_kit::storage::Storage::from_env().await {
                Ok(storage) => break storage,
                Err(e) => {
                    attempt += 1;
                    if attempt >= 10 {
                        panic!("Postgres unavailable after {attempt} attempts: {e}");
                    }
                    log::warn!(
                        "Postgres not ready (attempt {attempt}/10), retrying in 3s: {e}"
                    );
                    tokio::time::sleep(std::time::Duration::from_secs(3)).await;
                }
            }
        }
    };
    let migrations_dir =
        std::env::var("MIGRATIONS_DIR").unwrap_or_else(|_| "./migrations".to_string());
    if let Err(e) = storage.migrate_dir(&migrations_dir).await {
        log::error!("migration failed: {}", e);
    }
    let pool = Some(storage.pool().clone());

    let plan_index = Arc::new(PlanIndex::new());

    // Load existing segments from DB into the in-memory index
    if let Some(ref pg_pool) = pool {
        load_index_from_db(pg_pool, &plan_index).await;
    }

    let state = Arc::new(AppState::new(pool.clone(), plan_index.clone()));

    // Start outbox relay
    if let Some(ref pg_pool) = pool {
        rust_kit::storage::spawn_outbox_relay(pg_pool.clone(), redis_url.clone());
    }

    // Start event subscriber
    log::info!("trip-planning initializing Redis subscriber");
    let subscriber = rust_kit::messaging::redis_runtime::RedisEventSubscriber::from_env()
        .expect("failed to create Redis event subscriber");

    let streams: Vec<String> = SUBSCRIPTION_STREAMS.iter().map(|s| s.to_string()).collect();
    let handler_state = state.clone();
    let consumer = consumer_name();

    tokio::spawn(async move {
        if let Err(e) = subscriber
            .subscribe(
                streams,
                CONSUMER_GROUP.to_string(),
                consumer,
                Box::new(move |envelope| {
                    let state = handler_state.clone();
                    Box::pin(async move {
                        state.handle_inbound_event(&envelope);
                        Ok(())
                    })
                }),
            )
            .await
        {
            log::error!("event subscriber failed: {}", e);
        }
    });

    log::info!("trip-planning Redis subscriber started");
    router_with_state(state)
}

#[cfg(feature = "redis-impl")]
async fn load_index_from_db(pool: &sqlx::PgPool, index: &PlanIndex) {
    // Load segments
    let rows: Result<Vec<(String, Option<String>, String, String, chrono::DateTime<chrono::Utc>, chrono::NaiveDate, chrono::DateTime<chrono::Utc>)>, _> =
        sqlx::query_as(
            "SELECT segment_ref, scheduled_service_ref, origin_stop_ref, destination_stop_ref, departure_time, departure_date, arrival_time FROM plan_segments"
        )
        .fetch_all(pool)
        .await;

    match rows {
        Ok(rows) => {
            for (seg_ref, svc_ref, origin, dest, dep, date, arr) in rows {
                index.upsert_segment(plan_index::SegmentEntry {
                    segment_ref: seg_ref,
                    scheduled_service_ref: svc_ref.unwrap_or_default(),
                    origin_stop_ref: origin,
                    destination_stop_ref: dest,
                    departure_time: dep,
                    departure_date: date,
                    arrival_time: arr,
                });
            }
            log::info!("loaded {} segments into plan index", index.segment_count());
        }
        Err(e) => {
            log::warn!("failed to load segments from DB: {}", e);
        }
    }

    // Load nodes
    let node_rows: Result<Vec<(String, String)>, _> =
        sqlx::query_as("SELECT node_id, place_id FROM plan_nodes")
            .fetch_all(pool)
            .await;

    match node_rows {
        Ok(rows) => {
            let count = rows.len();
            for (node_id, place_id) in rows {
                index.upsert_node(node_id, place_id);
            }
            log::info!("loaded {} node mappings into plan index", count);
        }
        Err(e) => {
            log::warn!("failed to load nodes from DB: {}", e);
        }
    }
}

/// Construct the full router wired to an AppState.
pub fn router_with_state(state: Arc<AppState>) -> Router {
    let api_router = api::router(state.clone());
    let metadata_json = serde_json::to_value(profile()).unwrap_or_else(|_| serde_json::json!({}));

    let standard_router = Router::new()
        .route("/health", get(health_handler))
        .route("/healthz", get(health_handler))
        .route("/live", get(live_handler))
        .route("/livez", get(live_handler))
        .route("/ready", get(ready_handler))
        .route("/readyz", get(ready_handler))
        .route(
            "/metadata",
            get(move || {
                let metadata = metadata_json.clone();
                async move { Json(metadata) }
            }),
        );

    let full_router = Router::new().merge(standard_router).merge(api_router);
    apply_runtime(full_router, runtime_config())
}

/// Construct a test-only router with no persistence.
pub fn router_for_test() -> (Router, Arc<AppState>) {
    let plan_index = Arc::new(PlanIndex::new());
    let state = Arc::new(AppState::new(None, plan_index));
    let router = router_with_state(state.clone());
    (router, state)
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

#[cfg(feature = "redis-impl")]
fn consumer_name() -> String {
    std::env::var("HOSTNAME")
        .or_else(|_| std::env::var("CONSUMER_NAME"))
        .unwrap_or_else(|_| format!("trip-planning-{}", std::process::id()))
}

pub fn init_logging() {
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
