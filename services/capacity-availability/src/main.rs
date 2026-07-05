#[cfg(feature = "redis-impl")]
#[tokio::main]
async fn main() {
    let app = capacity_availability::build_runtime().await;
    let port = std::env::var("PORT")
        .ok()
        .and_then(|value| value.parse::<u16>().ok())
        .unwrap_or(8080);
    let listener = tokio::net::TcpListener::bind(("0.0.0.0", port))
        .await
        .expect("failed to bind HTTP listener");
    axum::serve(listener, app)
        .await
        .expect("capacity-availability HTTP server failed");
}

#[cfg(not(feature = "redis-impl"))]
fn main() {
    eprintln!(
        "capacity-availability binary requires the redis-impl feature for production startup"
    );
}
