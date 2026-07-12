#[cfg(feature = "redis-impl")]
#[tokio::main]
async fn main() {
    let otel = rust_kit::otel::init_from_env(trip_planning::profile().service_id)
        .expect("failed to initialize OpenTelemetry");
    let _otel_guard = otel;
    trip_planning::init_logging();
    log::info!("trip-planning runtime starting");
    let app = trip_planning::build_runtime().await;
    let port = std::env::var("PORT")
        .ok()
        .and_then(|value| value.parse::<u16>().ok())
        .unwrap_or(8080);
    let listener = tokio::net::TcpListener::bind(("0.0.0.0", port))
        .await
        .expect("failed to bind HTTP listener");
    log::info!("trip-planning HTTP server listening on 0.0.0.0:{port}");
    axum::serve(listener, app)
        .await
        .expect("trip-planning HTTP server failed");
}

#[cfg(not(feature = "redis-impl"))]
fn main() {
    trip_planning::init_logging();
    log::error!(
        "trip-planning binary requires the redis-impl feature for production startup"
    );
}
