#[tokio::main]
async fn main() {
    waitlist::init_logging();
    let (router, subscriber) = waitlist::build_runtime().await.expect("waitlist runtime");
    let port = std::env::var("PORT")
        .or_else(|_| std::env::var("SERVER_PORT"))
        .unwrap_or_else(|_| "8080".to_string());
    let listener = tokio::net::TcpListener::bind(format!("0.0.0.0:{port}"))
        .await
        .expect("bind waitlist");
    let server = axum::serve(listener, router);
    tokio::select! {
        result = server => result.expect("waitlist server"),
        result = subscriber => result.expect("waitlist subscriber task"),
        _ = tokio::signal::ctrl_c() => {},
    }
}
