use axum::serve;
use seat_assignment::{build_runtime, init_logging};
use tokio::net::TcpListener;

#[tokio::main]
async fn main() {
    init_logging();
    let port = std::env::var("PORT")
        .or_else(|_| std::env::var("SERVER_PORT"))
        .unwrap_or_else(|_| "8080".to_string());
    let addr = format!("0.0.0.0:{port}");
    let (app, _subscriber) = build_runtime().await.expect("seat-assignment runtime");
    let listener = TcpListener::bind(&addr)
        .await
        .expect("bind seat-assignment");
    log::info!("seat-assignment listening on {addr}");
    serve(listener, app).await.expect("serve seat-assignment");
}
