// ---------------------------------------------------------------------------
// Messaging adapters per docs/08-contracts/messaging.md
// Redis Streams implementation (behind "redis-impl" feature gate).
// In-memory fake for unit tests.
// ---------------------------------------------------------------------------

use crate::ports::{
    EventPublisher, EventSubscriber, HandlerResult, PublishFailed, SubscribeFailed, WireEnvelope,
};
use std::collections::HashSet;
use std::sync::{Arc, Mutex};

const MAX_DELIVERY_ATTEMPTS: u64 = 5;

// ---------------------------------------------------------------------------
// InMemoryEventPublisher — fake for unit tests
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Default)]
pub struct InMemoryEventPublisher {
    published: Arc<Mutex<Vec<WireEnvelope>>>,
}

impl InMemoryEventPublisher {
    pub fn new() -> Self {
        Self {
            published: Arc::new(Mutex::new(Vec::new())),
        }
    }

    pub fn published(&self) -> Vec<WireEnvelope> {
        self.published.lock().unwrap().clone()
    }

    pub fn clear(&self) {
        self.published.lock().unwrap().clear();
    }
}

impl EventPublisher for InMemoryEventPublisher {
    fn publish(&self, envelope: &WireEnvelope) -> Result<(), PublishFailed> {
        self.published.lock().unwrap().push(envelope.clone());
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// Subscriber decision engine — broker independent, unit-testable
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SubscriberAction {
    Ack,
    LeavePending,
    DeadLetterAndAck,
}

#[derive(Debug, Clone)]
pub struct ReceivedEvent {
    pub entry_id: String,
    pub stream: String,
    pub envelope: WireEnvelope,
    pub delivery_attempts: u64,
}

#[derive(Debug, Default)]
pub struct SubscriberState {
    dedup: Mutex<HashSet<String>>,
}

impl SubscriberState {
    pub fn new() -> Self {
        Self {
            dedup: Mutex::new(HashSet::new()),
        }
    }

    pub fn has_seen(&self, event_id: &str) -> bool {
        self.dedup.lock().unwrap().contains(event_id)
    }

    pub fn process_received(
        &self,
        event: &ReceivedEvent,
        handler: &dyn Fn(WireEnvelope) -> HandlerResult,
    ) -> SubscriberAction {
        if event.delivery_attempts >= MAX_DELIVERY_ATTEMPTS {
            self.dedup
                .lock()
                .unwrap()
                .insert(event.envelope.event_id.clone());
            return SubscriberAction::DeadLetterAndAck;
        }

        {
            let mut dedup = self.dedup.lock().unwrap();
            if !dedup.insert(event.envelope.event_id.clone()) {
                return SubscriberAction::Ack;
            }
        }

        match handler(event.envelope.clone()) {
            HandlerResult::Success => SubscriberAction::Ack,
            HandlerResult::TransientError(_) => {
                self.dedup.lock().unwrap().remove(&event.envelope.event_id);
                SubscriberAction::LeavePending
            }
            HandlerResult::FatalError(_) => SubscriberAction::DeadLetterAndAck,
        }
    }
}

// ---------------------------------------------------------------------------
// InMemoryEventSubscriber — fake for unit tests
// ---------------------------------------------------------------------------

#[derive(Clone, Default)]
pub struct InMemoryEventSubscriber {
    state: Arc<SubscriberState>,
}

impl InMemoryEventSubscriber {
    pub fn new() -> Self {
        Self {
            state: Arc::new(SubscriberState::new()),
        }
    }

    /// Simulate receiving an event directly.
    pub fn receive(
        &self,
        envelope: &WireEnvelope,
        handler: &dyn Fn(WireEnvelope) -> HandlerResult,
    ) -> HandlerResult {
        let received = ReceivedEvent {
            entry_id: "in-memory".to_string(),
            stream: envelope.producer.clone(),
            envelope: envelope.clone(),
            delivery_attempts: 1,
        };
        match self.state.process_received(&received, handler) {
            SubscriberAction::Ack | SubscriberAction::DeadLetterAndAck => HandlerResult::Success,
            SubscriberAction::LeavePending => {
                HandlerResult::TransientError("event left pending".to_string())
            }
        }
    }

    pub fn process_received(
        &self,
        event: &ReceivedEvent,
        handler: &dyn Fn(WireEnvelope) -> HandlerResult,
    ) -> SubscriberAction {
        self.state.process_received(event, handler)
    }

    /// Check if an event ID has been seen (dedup tracking).
    pub fn has_seen(&self, event_id: &str) -> bool {
        self.state.has_seen(event_id)
    }
}

impl EventSubscriber for InMemoryEventSubscriber {
    fn subscribe(
        &self,
        _streams: &[String],
        _group: &str,
        _consumer_name: &str,
        _handler: Box<dyn Fn(WireEnvelope) -> HandlerResult + Send + Sync>,
    ) -> Result<(), SubscribeFailed> {
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// Redis EventPublisher — behind "redis-impl" feature gate
// ---------------------------------------------------------------------------

#[cfg(feature = "redis-impl")]
pub mod redis_publisher {
    use super::*;
    use redis::RedisResult;
    use std::sync::Arc;
    use tokio::sync::Mutex as TokioMutex;

    const RETENTION_MAXLEN: usize = 100_000;

    pub struct RedisEventPublisher {
        connection: Arc<TokioMutex<redis::aio::ConnectionManager>>,
    }

    impl RedisEventPublisher {
        pub async fn new(redis_url: &str) -> Result<Self, PublishFailed> {
            let client = redis::Client::open(redis_url)
                .map_err(|e| PublishFailed(format!("failed to create redis client: {}", e)))?;
            let conn = client
                .get_connection_manager()
                .await
                .map_err(|e| PublishFailed(format!("failed to connect to redis: {}", e)))?;
            Ok(Self {
                connection: Arc::new(TokioMutex::new(conn)),
            })
        }
    }

    impl EventPublisher for RedisEventPublisher {
        fn publish(&self, envelope: &WireEnvelope) -> Result<(), PublishFailed> {
            let json = serde_json::to_string(envelope)
                .map_err(|e| PublishFailed(format!("serialization error: {}", e)))?;
            let stream_key = format!("events:{}", envelope.producer);
            let conn = self.connection.clone();

            tokio::runtime::Handle::current().block_on(async move {
                let mut last_error = None;
                for attempt in 0..3 {
                    let mut conn = conn.lock().await;
                    let result: RedisResult<String> = redis::cmd("XADD")
                        .arg(&stream_key)
                        .arg("MAXLEN")
                        .arg("~")
                        .arg(RETENTION_MAXLEN)
                        .arg("*")
                        .arg("envelope")
                        .arg(&json)
                        .query_async(&mut *conn)
                        .await;
                    match result {
                        Ok(_) => return Ok(()),
                        Err(err) => last_error = Some(err.to_string()),
                    }
                    drop(conn);
                    if attempt < 2 {
                        let backoff_ms = 50u64 * (1u64 << attempt);
                        tokio::time::sleep(std::time::Duration::from_millis(backoff_ms)).await;
                    }
                }
                Err(PublishFailed(format!(
                    "redis XADD failed after retries: {}",
                    last_error.unwrap_or_else(|| "unknown error".to_string())
                )))
            })
        }
    }
}

// ---------------------------------------------------------------------------
// Redis EventSubscriber — behind "redis-impl" feature gate
// ---------------------------------------------------------------------------

#[cfg(feature = "redis-impl")]
pub mod redis_subscriber {
    use super::*;
    use redis::{
        RedisResult, Value,
        streams::{StreamId, StreamKey, StreamReadReply},
    };
    use std::sync::Arc;
    use tokio::sync::Mutex as TokioMutex;

    const BLOCK_MS: usize = 2_000;
    const COUNT: usize = 10;
    const MIN_IDLE_MS: usize = 60_000;
    const GROUP: &str = "capacity-availability";
    const SUBSCRIBED_STREAMS: [&str; 2] = ["events:booking-orchestration", "events:post-sales"];
    const DLQ_SUFFIX: &str = ":dlq";

    #[derive(Debug, Clone)]
    pub struct StreamAutoClaimReply {
        pub next_cursor: String,
        pub key: String,
        pub ids: Vec<StreamId>,
        pub deleted_ids: Vec<String>,
    }

    impl StreamAutoClaimReply {
        pub fn into_stream_read_reply(self) -> StreamReadReply {
            StreamReadReply {
                keys: vec![StreamKey {
                    key: self.key,
                    ids: self.ids,
                }],
            }
        }
    }

    pub fn parse_xautoclaim_reply(
        stream: &str,
        value: &Value,
    ) -> Result<StreamAutoClaimReply, SubscribeFailed> {
        let Value::Bulk(parts) = value else {
            return Err(SubscribeFailed(
                "XAUTOCLAIM parse failed: expected top-level bulk reply".into(),
            ));
        };
        let [cursor_value, entries_value, deleted_value] = parts.as_slice() else {
            return Err(SubscribeFailed(format!(
                "XAUTOCLAIM parse failed: expected 3 reply elements, got {}",
                parts.len()
            )));
        };

        let next_cursor: String = redis::from_redis_value(cursor_value)
            .map_err(|e| SubscribeFailed(format!("XAUTOCLAIM cursor parse failed: {}", e)))?;
        let deleted_ids: Vec<String> = redis::from_redis_value(deleted_value)
            .map_err(|e| SubscribeFailed(format!("XAUTOCLAIM deleted IDs parse failed: {}", e)))?;

        let Value::Bulk(entries) = entries_value else {
            return Err(SubscribeFailed(
                "XAUTOCLAIM parse failed: expected entries bulk reply".into(),
            ));
        };
        let mut ids = Vec::with_capacity(entries.len());
        for entry in entries {
            let Value::Bulk(entry_parts) = entry else {
                return Err(SubscribeFailed(
                    "XAUTOCLAIM parse failed: expected entry bulk reply".into(),
                ));
            };
            let [id_value, fields_value] = entry_parts.as_slice() else {
                return Err(SubscribeFailed(format!(
                    "XAUTOCLAIM parse failed: expected 2 entry elements, got {}",
                    entry_parts.len()
                )));
            };
            let id: String = redis::from_redis_value(id_value)
                .map_err(|e| SubscribeFailed(format!("XAUTOCLAIM entry ID parse failed: {}", e)))?;
            let map: std::collections::HashMap<String, Value> =
                redis::from_redis_value(fields_value).map_err(|e| {
                    SubscribeFailed(format!("XAUTOCLAIM fields parse failed: {}", e))
                })?;
            ids.push(StreamId { id, map });
        }

        Ok(StreamAutoClaimReply {
            next_cursor,
            key: stream.to_string(),
            ids,
            deleted_ids,
        })
    }

    pub struct RedisEventSubscriber {
        connection: Arc<TokioMutex<redis::aio::ConnectionManager>>,
        state: Arc<SubscriberState>,
    }

    impl RedisEventSubscriber {
        pub async fn new(redis_url: &str) -> Result<Self, SubscribeFailed> {
            let client = redis::Client::open(redis_url)
                .map_err(|e| SubscribeFailed(format!("failed to create redis client: {}", e)))?;
            let conn = client
                .get_connection_manager()
                .await
                .map_err(|e| SubscribeFailed(format!("failed to connect to redis: {}", e)))?;
            Ok(Self {
                connection: Arc::new(TokioMutex::new(conn)),
                state: Arc::new(SubscriberState::new()),
            })
        }

        pub fn has_seen(&self, event_id: &str) -> bool {
            self.state.has_seen(event_id)
        }

        async fn create_groups(
            conn: &mut redis::aio::ConnectionManager,
            streams: &[String],
            group: &str,
        ) -> Result<(), SubscribeFailed> {
            for stream in streams {
                let result: RedisResult<Value> = redis::cmd("XGROUP")
                    .arg("CREATE")
                    .arg(stream)
                    .arg(group)
                    .arg("$")
                    .arg("MKSTREAM")
                    .query_async(conn)
                    .await;
                if let Err(err) = result {
                    if !err.to_string().contains("BUSYGROUP") {
                        return Err(SubscribeFailed(format!(
                            "failed to create consumer group for {}: {}",
                            stream, err
                        )));
                    }
                }
            }
            Ok(())
        }

        async fn subscription_loop(
            connection: Arc<TokioMutex<redis::aio::ConnectionManager>>,
            state: Arc<SubscriberState>,
            streams: Vec<String>,
            group: String,
            consumer_name: String,
            handler: Box<dyn Fn(WireEnvelope) -> HandlerResult + Send + Sync>,
        ) {
            loop {
                if let Err(err) = Self::recover_pending(
                    connection.clone(),
                    state.clone(),
                    &streams,
                    &group,
                    &consumer_name,
                    handler.as_ref(),
                )
                .await
                {
                    eprintln!(
                        "capacity-availability subscriber XAUTOCLAIM recovery failed: {}",
                        err.0
                    );
                }

                let read_result = {
                    let mut conn = connection.lock().await;
                    redis::cmd("XREADGROUP")
                        .arg("GROUP")
                        .arg(&group)
                        .arg(&consumer_name)
                        .arg("BLOCK")
                        .arg(BLOCK_MS)
                        .arg("COUNT")
                        .arg(COUNT)
                        .arg("STREAMS")
                        .arg(streams.clone())
                        .arg(vec![">"; streams.len()])
                        .query_async::<_, StreamReadReply>(&mut *conn)
                        .await
                };

                match read_result {
                    Ok(reply) => {
                        if let Err(err) = Self::dispatch_reply(
                            connection.clone(),
                            state.clone(),
                            reply,
                            &group,
                            handler.as_ref(),
                        )
                        .await
                        {
                            eprintln!(
                                "capacity-availability subscriber dispatch failed: {}",
                                err.0
                            );
                        }
                    }
                    Err(err) => {
                        eprintln!(
                            "capacity-availability subscriber XREADGROUP failed: {}",
                            err
                        );
                        tokio::time::sleep(std::time::Duration::from_millis(500)).await;
                    }
                }
            }
        }

        async fn recover_pending(
            connection: Arc<TokioMutex<redis::aio::ConnectionManager>>,
            state: Arc<SubscriberState>,
            streams: &[String],
            group: &str,
            consumer_name: &str,
            handler: &(dyn Fn(WireEnvelope) -> HandlerResult + Send + Sync),
        ) -> Result<(), SubscribeFailed> {
            for stream in streams {
                let claimed = {
                    let mut conn = connection.lock().await;
                    redis::cmd("XAUTOCLAIM")
                        .arg(stream)
                        .arg(group)
                        .arg(consumer_name)
                        .arg(MIN_IDLE_MS)
                        .arg("0-0")
                        .arg("COUNT")
                        .arg(COUNT)
                        .query_async::<_, Value>(&mut *conn)
                        .await
                        .map_err(|e| SubscribeFailed(format!("XAUTOCLAIM failed: {}", e)))?
                };
                let reply = parse_xautoclaim_reply(stream, &claimed)?.into_stream_read_reply();
                Self::dispatch_reply(connection.clone(), state.clone(), reply, group, handler)
                    .await?;
            }
            Ok(())
        }

        async fn dispatch_reply(
            connection: Arc<TokioMutex<redis::aio::ConnectionManager>>,
            state: Arc<SubscriberState>,
            reply: StreamReadReply,
            group: &str,
            handler: &(dyn Fn(WireEnvelope) -> HandlerResult + Send + Sync),
        ) -> Result<(), SubscribeFailed> {
            for stream_key in reply.keys {
                for stream_id in stream_key.ids {
                    let Some(raw_envelope) = stream_id.map.get("envelope") else {
                        Self::ack(connection.clone(), &stream_key.key, group, &stream_id.id)
                            .await?;
                        continue;
                    };
                    let envelope_json: String = redis::from_redis_value(raw_envelope)
                        .map_err(|e| SubscribeFailed(format!("invalid envelope field: {}", e)))?;
                    let envelope: WireEnvelope = serde_json::from_str(&envelope_json)
                        .map_err(|e| SubscribeFailed(format!("invalid envelope JSON: {}", e)))?;
                    let delivery_attempts = Self::delivery_attempts(
                        connection.clone(),
                        &stream_key.key,
                        group,
                        &stream_id.id,
                    )
                    .await?;
                    let received = ReceivedEvent {
                        entry_id: stream_id.id.clone(),
                        stream: stream_key.key.clone(),
                        envelope,
                        delivery_attempts,
                    };
                    match state.process_received(&received, handler) {
                        SubscriberAction::Ack => {
                            Self::ack(
                                connection.clone(),
                                &received.stream,
                                group,
                                &received.entry_id,
                            )
                            .await?;
                        }
                        SubscriberAction::LeavePending => {}
                        SubscriberAction::DeadLetterAndAck => {
                            Self::dead_letter(connection.clone(), &received.stream, &envelope_json)
                                .await?;
                            Self::ack(
                                connection.clone(),
                                &received.stream,
                                group,
                                &received.entry_id,
                            )
                            .await?;
                        }
                    }
                }
            }
            Ok(())
        }

        async fn delivery_attempts(
            connection: Arc<TokioMutex<redis::aio::ConnectionManager>>,
            stream: &str,
            group: &str,
            entry_id: &str,
        ) -> Result<u64, SubscribeFailed> {
            let pending = {
                let mut conn = connection.lock().await;
                redis::cmd("XPENDING")
                    .arg(stream)
                    .arg(group)
                    .arg(entry_id)
                    .arg(entry_id)
                    .arg(1)
                    .query_async::<_, redis::streams::StreamPendingCountReply>(&mut *conn)
                    .await
                    .map_err(|e| {
                        SubscribeFailed(format!("XPENDING delivery count failed: {}", e))
                    })?
            };

            Ok(pending
                .ids
                .iter()
                .find(|pending_id| pending_id.id == entry_id)
                .map(|pending_id| pending_id.times_delivered as u64)
                .unwrap_or(1))
        }

        async fn ack(
            connection: Arc<TokioMutex<redis::aio::ConnectionManager>>,
            stream: &str,
            group: &str,
            entry_id: &str,
        ) -> Result<(), SubscribeFailed> {
            let mut conn = connection.lock().await;
            redis::cmd("XACK")
                .arg(stream)
                .arg(group)
                .arg(entry_id)
                .query_async::<_, usize>(&mut *conn)
                .await
                .map_err(|e| SubscribeFailed(format!("XACK failed: {}", e)))?;
            Ok(())
        }

        async fn dead_letter(
            connection: Arc<TokioMutex<redis::aio::ConnectionManager>>,
            stream: &str,
            envelope_json: &str,
        ) -> Result<(), SubscribeFailed> {
            let dlq_stream = format!("{}{}", stream, DLQ_SUFFIX);
            let mut conn = connection.lock().await;
            redis::cmd("XADD")
                .arg(&dlq_stream)
                .arg("MAXLEN")
                .arg("~")
                .arg(100_000)
                .arg("*")
                .arg("envelope")
                .arg(envelope_json)
                .query_async::<_, String>(&mut *conn)
                .await
                .map_err(|e| SubscribeFailed(format!("DLQ XADD failed: {}", e)))?;
            Ok(())
        }
    }

    impl EventSubscriber for RedisEventSubscriber {
        fn subscribe(
            &self,
            streams: &[String],
            group: &str,
            consumer_name: &str,
            handler: Box<dyn Fn(WireEnvelope) -> HandlerResult + Send + Sync>,
        ) -> Result<(), SubscribeFailed> {
            let selected_streams = if streams.is_empty() {
                SUBSCRIBED_STREAMS.iter().map(|s| s.to_string()).collect()
            } else {
                streams.to_vec()
            };
            let selected_group = if group.is_empty() { GROUP } else { group }.to_string();
            let consumer = consumer_name.to_string();
            let connection = self.connection.clone();
            let state = self.state.clone();

            tokio::runtime::Handle::current().block_on(async {
                let mut conn = connection.lock().await;
                Self::create_groups(&mut conn, &selected_streams, &selected_group).await
            })?;

            tokio::spawn(Self::subscription_loop(
                connection,
                state,
                selected_streams,
                selected_group,
                consumer,
                handler,
            ));
            Ok(())
        }
    }
}
