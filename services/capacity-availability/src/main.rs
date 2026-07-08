#[cfg(feature = "redis-impl")]
#[tokio::main]
async fn main() {
    let otel = rust_kit::otel::init_from_env(capacity_availability::profile().service_id)
        .expect("failed to initialize OpenTelemetry");
    let _otel_guard = otel;
    capacity_availability::init_logging();
    log::info!("capacity-availability runtime starting");
    let app = capacity_availability::build_runtime().await;
    let port = std::env::var("PORT")
        .ok()
        .and_then(|value| value.parse::<u16>().ok())
        .unwrap_or(8080);
    let listener = tokio::net::TcpListener::bind(("0.0.0.0", port))
        .await
        .expect("failed to bind HTTP listener");
    log::info!("capacity-availability HTTP server listening on 0.0.0.0:{port}");
    axum::serve(listener, app)
        .await
        .expect("capacity-availability HTTP server failed");
}

#[cfg(not(feature = "redis-impl"))]
fn main() {
    capacity_availability::init_logging();
    log::error!(
        "capacity-availability binary requires the redis-impl feature for production startup"
    );
}
