#![cfg(feature = "redis-impl")]

use rust_kit::messaging::{AsyncEventSubscriber, EventEnvelope, HandlerError};
use rust_kit::messaging::redis_runtime::RedisEventSubscriber;
use std::sync::{Arc, atomic::{AtomicUsize, Ordering}};
use std::time::Duration;
use redis::AsyncCommands;

#[tokio::test]
#[ignore = "requires TEST_REDIS_URL and TEST_SUBSCRIBER_EVENTS_FILE"]
async fn filtered_batches_preserve_relevant_pending_events_and_malformed_messages() {
    let events: Vec<EventEnvelope> = serde_json::from_str(&std::fs::read_to_string(std::env::var("TEST_SUBSCRIBER_EVENTS_FILE").unwrap()).unwrap()).unwrap();
    let ignored: Vec<_> = events.iter().filter(|event| event.event_type != "PaymentCaptured").take(100).collect();
    assert_eq!(ignored.len(), 100);
    let payment = events.iter().find(|event| event.event_type == "PaymentCaptured").unwrap().clone();
    let redis_url = std::env::var("TEST_REDIS_URL").unwrap();
    let redis = redis::Client::open(redis_url.as_str()).unwrap();
    let mut connection = redis.get_multiplexed_async_connection().await.unwrap();
    let stream = format!("test-filter-{}", uuid::Uuid::now_v7());
    let group = "filtered";
    let _: String = redis::cmd("XGROUP").arg("CREATE").arg(&stream).arg(group).arg("0").arg("MKSTREAM").query_async(&mut connection).await.unwrap();
    for event in ignored {
        let _: String = connection.xadd(&stream, "*", &[("envelope", serde_json::to_string(event).unwrap())]).await.unwrap();
    }
    let payment_id: String = connection.xadd(&stream, "*", &[("envelope", serde_json::to_string(&payment).unwrap())]).await.unwrap();
    let _: String = connection.xadd(&stream, "*", &[("envelope", "{")]).await.unwrap();
    let calls = Arc::new(AtomicUsize::new(0));
    let handled = calls.clone();
    let subscriber = RedisEventSubscriber::new(&redis_url).unwrap().with_event_filter(|event| event.event_type == "PaymentCaptured");
    let running = subscriber.clone();
    let subscribed_stream = stream.clone();
    let worker = tokio::spawn(async move {
        running.subscribe(vec![subscribed_stream], group.to_string(), "reader".to_string(), Box::new(move |envelope| {
            assert_eq!(envelope.event_id, payment.event_id);
            let attempt = handled.fetch_add(1, Ordering::SeqCst);
            Box::pin(async move { if attempt == 0 { Err(HandlerError::Transient("dependency unavailable".into())) } else { Ok(()) } })
        })).await.unwrap();
    });
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let dlq: usize = connection.xlen(format!("{stream}:dlq")).await.unwrap();
            if dlq == 1 && calls.load(Ordering::SeqCst) == 1 { break; }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    }).await.unwrap();
    let pending: redis::streams::StreamPendingReply = connection.xpending(&stream, group).await.unwrap();
    match pending { redis::streams::StreamPendingReply::Data(data) => assert_eq!(data.count, 1), _ => panic!("relevant failure must remain pending") }
    let _: redis::Value = redis::cmd("XCLAIM").arg(&stream).arg(group).arg("reader").arg(0).arg(&payment_id).arg("IDLE").arg(60_001).arg("JUSTID").query_async(&mut connection).await.unwrap();
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let pending: redis::streams::StreamPendingReply = connection.xpending(&stream, group).await.unwrap();
            if matches!(pending, redis::streams::StreamPendingReply::Empty) && calls.load(Ordering::SeqCst) == 2 { break; }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    }).await.unwrap();
    subscriber.shutdown();
    tokio::time::timeout(Duration::from_secs(5), worker).await.unwrap().unwrap();
    let _: i64 = connection.del(&[stream.clone(), format!("{stream}:dlq")]).await.unwrap();
}
