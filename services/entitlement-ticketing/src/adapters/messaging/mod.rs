use std::collections::HashSet;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use redis::RedisResult;
use tokio::time::sleep;

use crate::application::{
    EventEnvelope, EventPublisher, EventSubscriber, HandlerError, PublishFailed, SubscribeFailed,
};

const RETENTION_MAXLEN: usize = 100_000;
const CONSUMER_GROUP: &str = "entitlement-ticketing";
const SUBSCRIBED_STREAMS: [&str; 3] = [
    "events:booking-orchestration",
    "events:fulfillment",
    "events:post-sales",
];

#[derive(Clone)]
pub struct RedisEventPublisher {
    client: redis::Client,
}

impl RedisEventPublisher {
    pub fn from_env() -> Result<Self, PublishFailed> {
        let url =
            std::env::var("REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379".to_string());
        Self::new(&url)
    }

    pub fn new(url: &str) -> Result<Self, PublishFailed> {
        Ok(Self {
            client: redis::Client::open(url).map_err(|error| PublishFailed(error.to_string()))?,
        })
    }
}

#[async_trait]
impl EventPublisher for RedisEventPublisher {
    async fn publish(&self, envelope: EventEnvelope) -> Result<(), PublishFailed> {
        let stream = stream_for_producer(&envelope.producer);
        let raw_envelope =
            serde_json::to_string(&envelope).map_err(|error| PublishFailed(error.to_string()))?;
        let mut last_error = None;
        for attempt in 0..3 {
            let result: RedisResult<()> = async {
                let mut connection = self.client.get_multiplexed_async_connection().await?;
                redis::cmd("XADD")
                    .arg(&stream)
                    .arg("MAXLEN")
                    .arg("~")
                    .arg(RETENTION_MAXLEN)
                    .arg("*")
                    .arg("envelope")
                    .arg(&raw_envelope)
                    .query_async(&mut connection)
                    .await
            }
            .await;
            match result {
                Ok(()) => return Ok(()),
                Err(error) => {
                    last_error = Some(error.to_string());
                    if attempt < 2 {
                        sleep(Duration::from_millis(50 * (1 << attempt))).await;
                    }
                }
            }
        }
        Err(PublishFailed(last_error.unwrap_or_else(|| {
            "unknown Redis publish failure".to_string()
        })))
    }
}

#[derive(Clone)]
pub struct RedisEventSubscriber {
    client: redis::Client,
    seen_event_ids: Arc<Mutex<HashSet<String>>>,
}

impl RedisEventSubscriber {
    pub fn from_env() -> Result<Self, SubscribeFailed> {
        let url =
            std::env::var("REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379".to_string());
        Self::new(&url)
    }

    pub fn new(url: &str) -> Result<Self, SubscribeFailed> {
        Ok(Self {
            client: redis::Client::open(url).map_err(|error| SubscribeFailed(error.to_string()))?,
            seen_event_ids: Arc::new(Mutex::new(HashSet::new())),
        })
    }

    pub fn entitlement_streams() -> Vec<String> {
        SUBSCRIBED_STREAMS
            .iter()
            .map(|stream| (*stream).to_string())
            .collect()
    }

    pub fn entitlement_group() -> &'static str {
        CONSUMER_GROUP
    }
}

#[async_trait]
impl EventSubscriber for RedisEventSubscriber {
    async fn subscribe(
        &self,
        streams: Vec<String>,
        group: String,
        consumer_name: String,
        handler: Box<dyn Fn(EventEnvelope) -> Result<(), HandlerError> + Send + Sync>,
    ) -> Result<(), SubscribeFailed> {
        let mut connection = self
            .client
            .get_multiplexed_async_connection()
            .await
            .map_err(|error| SubscribeFailed(error.to_string()))?;
        for stream in &streams {
            let _: RedisResult<()> = redis::cmd("XGROUP")
                .arg("CREATE")
                .arg(stream)
                .arg(&group)
                .arg("$")
                .arg("MKSTREAM")
                .query_async(&mut connection)
                .await;
        }
        loop {
            self.recover_pending(&streams, &group, &consumer_name, handler.as_ref())
                .await?;
            let response: redis::Value = redis::cmd("XREADGROUP")
                .arg("GROUP")
                .arg(&group)
                .arg(&consumer_name)
                .arg("BLOCK")
                .arg(2_000)
                .arg("COUNT")
                .arg(10)
                .arg("STREAMS")
                .arg(&streams)
                .arg(vec![">"; streams.len()])
                .query_async(&mut connection)
                .await
                .map_err(|error| SubscribeFailed(error.to_string()))?;
            for message in parse_stream_messages(response) {
                self.process_message(&mut connection, &group, message, handler.as_ref())
                    .await?;
            }
        }
    }
}

impl RedisEventSubscriber {
    async fn recover_pending(
        &self,
        streams: &[String],
        group: &str,
        consumer_name: &str,
        handler: &(dyn Fn(EventEnvelope) -> Result<(), HandlerError> + Send + Sync),
    ) -> Result<(), SubscribeFailed> {
        let mut connection = self
            .client
            .get_multiplexed_async_connection()
            .await
            .map_err(|error| SubscribeFailed(error.to_string()))?;
        for stream in streams {
            let response: redis::Value = redis::cmd("XAUTOCLAIM")
                .arg(stream)
                .arg(group)
                .arg(consumer_name)
                .arg(60_000)
                .arg("0")
                .arg("COUNT")
                .arg(100)
                .query_async(&mut connection)
                .await
                .map_err(|error| SubscribeFailed(error.to_string()))?;
            for message in parse_autoclaim_messages(stream, response) {
                if message.delivery_count >= 5 {
                    move_to_dlq(
                        &mut connection,
                        &message.stream,
                        group,
                        &message.id,
                        &message.raw_envelope,
                    )
                    .await?;
                } else {
                    self.process_message(&mut connection, group, message, handler)
                        .await?;
                }
            }
        }
        Ok(())
    }

    async fn process_message(
        &self,
        connection: &mut redis::aio::MultiplexedConnection,
        group: &str,
        message: StreamMessage,
        handler: &(dyn Fn(EventEnvelope) -> Result<(), HandlerError> + Send + Sync),
    ) -> Result<(), SubscribeFailed> {
        let envelope: EventEnvelope = serde_json::from_str(&message.raw_envelope)
            .map_err(|error| SubscribeFailed(error.to_string()))?;
        let duplicate = {
            let mut seen = self
                .seen_event_ids
                .lock()
                .expect("seen-event lock poisoned");
            !seen.insert(envelope.event_id.clone())
        };
        if duplicate {
            return ack(connection, &message.stream, group, &message.id).await;
        }
        match handler(envelope) {
            Ok(()) => ack(connection, &message.stream, group, &message.id).await,
            Err(HandlerError::Transient(_)) => Ok(()),
            Err(HandlerError::Fatal(_)) => {
                move_to_dlq(
                    connection,
                    &message.stream,
                    group,
                    &message.id,
                    &message.raw_envelope,
                )
                .await
            }
        }
    }
}

fn stream_for_producer(producer: &str) -> String {
    format!("events:{producer}")
}

async fn ack(
    connection: &mut redis::aio::MultiplexedConnection,
    stream: &str,
    group: &str,
    id: &str,
) -> Result<(), SubscribeFailed> {
    let _: () = redis::cmd("XACK")
        .arg(stream)
        .arg(group)
        .arg(id)
        .query_async(connection)
        .await
        .map_err(|error| SubscribeFailed(error.to_string()))?;
    Ok(())
}

async fn move_to_dlq(
    connection: &mut redis::aio::MultiplexedConnection,
    stream: &str,
    group: &str,
    id: &str,
    raw_envelope: &str,
) -> Result<(), SubscribeFailed> {
    let dlq = format!("{stream}:dlq");
    let _: () = redis::cmd("XADD")
        .arg(dlq)
        .arg("MAXLEN")
        .arg("~")
        .arg(RETENTION_MAXLEN)
        .arg("*")
        .arg("envelope")
        .arg(raw_envelope)
        .query_async(connection)
        .await
        .map_err(|error| SubscribeFailed(error.to_string()))?;
    ack(connection, stream, group, id).await
}

struct StreamMessage {
    stream: String,
    id: String,
    raw_envelope: String,
    delivery_count: u64,
}

fn parse_stream_messages(value: redis::Value) -> Vec<StreamMessage> {
    let mut messages = Vec::new();
    if let redis::Value::Bulk(streams) = value {
        for stream_value in streams {
            if let redis::Value::Bulk(parts) = stream_value {
                if parts.len() != 2 {
                    continue;
                }
                let stream = redis_value_to_string(&parts[0]).unwrap_or_default();
                if let redis::Value::Bulk(entries) = &parts[1] {
                    for entry in entries {
                        if let Some((id, raw_envelope)) = parse_entry(entry) {
                            messages.push(StreamMessage {
                                stream: stream.clone(),
                                id,
                                raw_envelope,
                                delivery_count: 1,
                            });
                        }
                    }
                }
            }
        }
    }
    messages
}

fn parse_autoclaim_messages(stream: &str, value: redis::Value) -> Vec<StreamMessage> {
    let mut messages = Vec::new();
    if let redis::Value::Bulk(parts) = value {
        if let Some(redis::Value::Bulk(entries)) = parts.get(1) {
            for entry in entries {
                if let Some((id, raw_envelope)) = parse_entry(entry) {
                    messages.push(StreamMessage {
                        stream: stream.to_string(),
                        id,
                        raw_envelope,
                        delivery_count: 1,
                    });
                }
            }
        }
    }
    messages
}

fn parse_entry(value: &redis::Value) -> Option<(String, String)> {
    let redis::Value::Bulk(parts) = value else {
        return None;
    };
    if parts.len() != 2 {
        return None;
    }
    let id = redis_value_to_string(&parts[0])?;
    let redis::Value::Bulk(fields) = &parts[1] else {
        return None;
    };
    let mut index = 0;
    while index + 1 < fields.len() {
        if redis_value_to_string(&fields[index]).as_deref() == Some("envelope") {
            return Some((id, redis_value_to_string(&fields[index + 1])?));
        }
        index += 2;
    }
    None
}

fn redis_value_to_string(value: &redis::Value) -> Option<String> {
    match value {
        redis::Value::Data(bytes) => String::from_utf8(bytes.clone()).ok(),
        redis::Value::Status(value) => Some(value.clone()),
        redis::Value::Okay => Some("OK".to_string()),
        _ => None,
    }
}

#[derive(Default)]
pub struct InMemoryEventPublisher {
    envelopes: Mutex<Vec<EventEnvelope>>,
}

impl InMemoryEventPublisher {
    pub fn published(&self) -> Vec<EventEnvelope> {
        self.envelopes
            .lock()
            .expect("publisher lock poisoned")
            .clone()
    }
}

#[async_trait]
impl EventPublisher for InMemoryEventPublisher {
    async fn publish(&self, envelope: EventEnvelope) -> Result<(), PublishFailed> {
        self.envelopes
            .lock()
            .expect("publisher lock poisoned")
            .push(envelope);
        Ok(())
    }
}

#[derive(Default)]
pub struct DeduplicatingEventHandler {
    seen: Mutex<HashSet<String>>,
    handled: Mutex<Vec<EventEnvelope>>,
}

impl DeduplicatingEventHandler {
    pub fn handle(&self, envelope: EventEnvelope) -> Result<(), HandlerError> {
        let mut seen = self.seen.lock().expect("dedup lock poisoned");
        if seen.insert(envelope.event_id.clone()) {
            self.handled
                .lock()
                .expect("handled lock poisoned")
                .push(envelope);
        }
        Ok(())
    }

    pub fn handled_count(&self) -> usize {
        self.handled.lock().expect("handled lock poisoned").len()
    }
}
