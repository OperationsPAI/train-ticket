use invoicing::adapters::storage::PostgresInvoicingService;
use rust_kit::messaging::EventEnvelope;

#[tokio::test]
#[ignore = "requires disposable DATABASE_URL, MIGRATIONS_DIR and TEST_INVOICING_EVENTS_FILE"]
async fn unhandled_events_need_no_database_and_order_projections_remain_durable() {
    let events: Vec<EventEnvelope> = serde_json::from_str(&std::fs::read_to_string(std::env::var("TEST_INVOICING_EVENTS_FILE").unwrap()).unwrap()).unwrap();
    let service = PostgresInvoicingService::from_env().await.unwrap();
    let mut ignored = 0;
    for event in events.iter().filter(|event| !PostgresInvoicingService::handles_event(event)) {
        service.handle_subscribed_event(event.clone()).await.unwrap();
        ignored += 1;
    }
    assert!(ignored >= 100);
    assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM processed_events").fetch_one(service.pool()).await.unwrap(), 0);
    let order = events.iter().find(|event| event.event_type == "JourneyOrderConfirmed").unwrap();
    assert!(PostgresInvoicingService::handles_event(order));
    service.handle_subscribed_event(order.clone()).await.unwrap();
    let saved: serde_json::Value = sqlx::query_scalar("SELECT data FROM invoicing_projection_rows WHERE kind='orders' AND id=$1")
        .bind(order.payload["orderId"].as_str().unwrap()).fetch_one(service.pool()).await.unwrap();
    assert!(saved.is_object());
    let restarted = PostgresInvoicingService::from_env().await.unwrap();
    restarted.handle_subscribed_event(order.clone()).await.unwrap();
    assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM processed_events").fetch_one(restarted.pool()).await.unwrap(), 1);
    assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM invoicing_projection_rows").fetch_one(restarted.pool()).await.unwrap(), 1);
    restarted.pool().close().await;
    let ignored = events.iter().find(|event| !PostgresInvoicingService::handles_event(event)).unwrap();
    restarted.handle_subscribed_event(ignored.clone()).await.unwrap();
    assert!(restarted.handle_subscribed_event(order.clone()).await.is_err());
    service.pool().close().await;
}
