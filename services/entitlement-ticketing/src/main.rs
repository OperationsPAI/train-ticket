use std::net::SocketAddr;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let (app, subscriber_handle) = entitlement_ticketing::build_runtime().await?;
    let port = std::env::var("PORT")
        .ok()
        .and_then(|value| value.parse::<u16>().ok())
        .unwrap_or(8080);
    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal(subscriber_handle))
        .await?;
    Ok(())
}

async fn shutdown_signal(subscriber_handle: tokio::task::JoinHandle<()>) {
    let _ = tokio::signal::ctrl_c().await;
    subscriber_handle.abort();
}
