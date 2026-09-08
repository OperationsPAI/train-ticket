use axum::serve;
use invoicing::{build_runtime, init_logging};
use tokio::net::TcpListener;

#[tokio::main]
async fn main() {
    init_logging();
    let port = std::env::var("PORT")
        .or_else(|_| std::env::var("SERVER_PORT"))
        .unwrap_or_else(|_| "8080".to_string());
    let addr = format!("0.0.0.0:{port}");
    let (app, subscriber) = build_runtime().await.expect("invoicing runtime");
    let listener = TcpListener::bind(&addr).await.expect("bind invoicing");
    log::info!("invoicing listening on {addr}");

    // The subscriber handle used to be bound to `_subscriber` and never looked at.
    // build_runtime() spawns the Redis subscription as a background task, so when
    // that task died -- it panicked on startup with "pool timed out while waiting
    // for an open connection" -- nothing noticed: the process stayed up, the HTTP
    // listener kept serving, /healthz answered 200, and the pod reported 1/1
    // Running while consuming no events at all. On the live cluster invoicing had
    // NO consumer group on any stream, and every booking saga stopped at its
    // INVOICING step with nothing anywhere to explain why.
    //
    // select! makes either failure terminate the process, so Kubernetes restarts
    // it rather than leaving a healthy-looking service that does nothing. Crashing
    // is the correct outcome: the subscription is not optional, and a half-running
    // invoicing silently stalls every saga that reaches it.
    tokio::select! {
        result = serve(listener, app) => {
            result.expect("serve invoicing");
            log::error!("invoicing HTTP server returned; shutting down");
        }
        result = subscriber => {
      match result {
        Ok(()) => log::error!("invoicing Redis subscriber stopped unexpectedly"),
       Err(error) => log::error!("invoicing Redis subscriber task failed: {error}"),
       }
        std::process::exit(1);
        }
    }
}
