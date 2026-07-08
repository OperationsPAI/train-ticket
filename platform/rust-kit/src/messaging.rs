use std::collections::HashSet;
use std::fmt;
use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Mutex};

use async_trait::async_trait;
use chrono::{SecondsFormat, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;

pub const RETENTION_MAXLEN: usize = 100_000;
pub const MAX_DELIVERY_ATTEMPTS: u64 = 5;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct EventEnvelope {
    pub event_id: String,
    pub event_type: String,
    pub schema_version: u32,
    pub producer: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub causation_id: Option<String>,
    pub correlation_id: String,
    pub occurred_at: String,
    pub payload: Value,
}

impl EventEnvelope {
    pub fn try_new(
        event_type: impl Into<String>,
        occurred_at: impl Into<String>,
        correlation_id: impl Into<String>,
        causation_id: Option<impl Into<String>>,
        producer: impl Into<String>,
        payload: Value,
    ) -> Result<Self, EnvelopeIdError> {
        Ok(Self {
            event_id: event_id(),
            event_type: event_type.into(),
            schema_version: 1,
            producer: producer.into(),
            causation_id: validate_optional_causation_id(causation_id.map(Into::into))?,
            correlation_id: canonical_correlation_id(correlation_id.into())?,
            occurred_at: occurred_at.into(),
            payload,
        })
    }

    pub fn new(
        event_type: impl Into<String>,
        occurred_at: impl Into<String>,
        correlation_id: impl Into<String>,
        causation_id: Option<impl Into<String>>,
        producer: impl Into<String>,
        payload: Value,
    ) -> Self {
        Self::try_new(
            event_type,
            occurred_at,
            correlation_id,
            causation_id,
            producer,
            payload,
        )
        .expect("event envelope identifiers must be corr-/cmd-/evt-prefixed UUID v7 values")
    }

    pub fn try_canonical(
        event_type: impl Into<String>,
        correlation_id: impl Into<String>,
        causation_id: Option<impl Into<String>>,
        producer: impl Into<String>,
        payload: Value,
    ) -> Result<Self, EnvelopeIdError> {
        Self::try_new(
            event_type,
            now_rfc3339_utc(),
            correlation_id,
            causation_id,
            producer,
            payload,
        )
    }

    pub fn canonical(
        event_type: impl Into<String>,
        correlation_id: impl Into<String>,
        causation_id: Option<impl Into<String>>,
        producer: impl Into<String>,
        payload: Value,
    ) -> Self {
        Self::try_canonical(event_type, correlation_id, causation_id, producer, payload)
            .expect("event envelope identifiers must be corr-/cmd-/evt-prefixed UUID v7 values")
    }

    pub fn with_occurred_at(mut self, occurred_at: impl Into<String>) -> Self {
        self.occurred_at = occurred_at.into();
        self
    }
}

pub fn uuid_v7_string() -> String {
    uuid::Uuid::now_v7().to_string()
}
pub fn event_id() -> String {
    format!("evt-{}", uuid_v7_string())
}
pub fn command_id() -> String {
    format!("cmd-{}", uuid_v7_string())
}
pub fn correlation_id() -> String {
    format!("corr-{}", uuid_v7_string())
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EnvelopeIdError {
    message: String,
}

impl EnvelopeIdError {
    fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }
}

impl fmt::Display for EnvelopeIdError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}
impl std::error::Error for EnvelopeIdError {}

pub fn is_uuid_v7(value: &str) -> bool {
    uuid::Uuid::parse_str(value)
        .map(|uuid| uuid.get_version_num() == 7)
        .unwrap_or(false)
}

pub fn validate_prefixed_uuid_v7(
    value: &str,
    prefixes: &[&str],
) -> Result<String, EnvelopeIdError> {
    for prefix in prefixes {
        if let Some(uuid_part) = value.strip_prefix(prefix) {
            if is_uuid_v7(uuid_part) {
                return Ok(value.to_string());
            }
            return Err(EnvelopeIdError::new(format!(
                "identifier with prefix {prefix} must contain a UUID v7"
            )));
        }
    }
    Err(EnvelopeIdError::new(format!(
        "identifier must start with one of: {}",
        prefixes.join(", ")
    )))
}

pub fn canonical_correlation_id(value: String) -> Result<String, EnvelopeIdError> {
    validate_prefixed_uuid_v7(&value, &["corr-"])
}

pub fn valid_or_generated_correlation_id(value: impl Into<String>) -> String {
    canonical_correlation_id(value.into()).unwrap_or_else(|_| correlation_id())
}

pub fn validate_causation_id(value: String) -> Result<String, EnvelopeIdError> {
    validate_prefixed_uuid_v7(&value, &["cmd-", "evt-"])
}

fn validate_optional_causation_id(
    value: Option<String>,
) -> Result<Option<String>, EnvelopeIdError> {
    value.map(validate_causation_id).transpose()
}
pub fn now_rfc3339_utc() -> String {
    Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true)
}

#[derive(Debug, Clone)]
pub struct PublishFailed(pub String);
impl fmt::Display for PublishFailed {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "publish failed: {}", self.0)
    }
}
impl std::error::Error for PublishFailed {}

#[async_trait]
pub trait AsyncEventPublisher: Send + Sync + 'static {
    async fn publish(&self, envelope: EventEnvelope) -> Result<(), PublishFailed>;
}
pub trait EventPublisher: Send + Sync + 'static {
    fn publish(&self, envelope: &EventEnvelope) -> Result<(), PublishFailed>;
}

#[derive(Debug, Clone)]
pub struct SubscribeFailed(pub String);
impl fmt::Display for SubscribeFailed {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "subscribe failed: {}", self.0)
    }
}
impl std::error::Error for SubscribeFailed {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HandlerResult {
    Success,
    TransientError(String),
    FatalError(String),
}
#[derive(Debug, Clone)]
pub enum HandlerError {
    Transient(String),
    Fatal(String),
}
pub type HandlerFuture = Pin<Box<dyn Future<Output = Result<(), HandlerError>> + Send>>;

pub trait EventSubscriber: Send + Sync + 'static {
    fn subscribe(
        &self,
        streams: &[String],
        group: &str,
        consumer_name: &str,
        handler: Box<dyn Fn(EventEnvelope) -> HandlerResult + Send + Sync>,
    ) -> Result<(), SubscribeFailed>;
}

#[async_trait]
pub trait AsyncEventSubscriber: Send + Sync + 'static {
    async fn subscribe(
        &self,
        streams: Vec<String>,
        group: String,
        consumer_name: String,
        handler: Box<dyn Fn(EventEnvelope) -> HandlerFuture + Send + Sync>,
    ) -> Result<(), SubscribeFailed>;
}

pub async fn publish_after_commit<F, T, P>(
    operation: F,
    publisher: &P,
    events: Vec<EventEnvelope>,
) -> Result<T, PublishFailed>
where
    F: FnOnce() -> Result<T, PublishFailed>,
    P: EventPublisher + ?Sized,
{
    let value = operation()?;
    for event in &events {
        publisher.publish(event)?;
    }
    Ok(value)
}

pub async fn async_publish_after_commit<F, T, P>(
    operation: F,
    publisher: &P,
    events: Vec<EventEnvelope>,
) -> Result<T, PublishFailed>
where
    F: FnOnce() -> Result<T, PublishFailed>,
    P: AsyncEventPublisher + ?Sized,
{
    let value = operation()?;
    for event in events {
        publisher.publish(event).await?;
    }
    Ok(value)
}

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
    pub envelope: EventEnvelope,
    pub delivery_attempts: u64,
}

#[derive(Debug, Default)]
pub struct SubscriberState {
    dedup: Mutex<HashSet<String>>,
}
impl SubscriberState {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn has_seen(&self, event_id: &str) -> bool {
        self.dedup
            .lock()
            .expect("dedup lock poisoned")
            .contains(event_id)
    }
    pub fn mark_consumed(&self, event_id: &str) {
        self.dedup
            .lock()
            .expect("dedup lock poisoned")
            .insert(event_id.to_string());
    }
    pub fn decide_action(
        &self,
        event: &ReceivedEvent,
        handler: &dyn Fn(EventEnvelope) -> HandlerResult,
    ) -> SubscriberAction {
        if event.delivery_attempts >= MAX_DELIVERY_ATTEMPTS {
            return SubscriberAction::DeadLetterAndAck;
        }
        if self.has_seen(&event.envelope.event_id) {
            return SubscriberAction::Ack;
        }
        match handler(event.envelope.clone()) {
            HandlerResult::Success => SubscriberAction::Ack,
            HandlerResult::TransientError(_) => SubscriberAction::LeavePending,
            HandlerResult::FatalError(_) => SubscriberAction::DeadLetterAndAck,
        }
    }
    pub fn process_received(
        &self,
        event: &ReceivedEvent,
        handler: &dyn Fn(EventEnvelope) -> HandlerResult,
    ) -> SubscriberAction {
        let action = self.decide_action(event, handler);
        if matches!(
            action,
            SubscriberAction::Ack | SubscriberAction::DeadLetterAndAck
        ) {
            self.mark_consumed(&event.envelope.event_id);
        }
        action
    }
}

#[derive(Debug, Clone, Default)]
pub struct InMemoryEventPublisher {
    published: Arc<Mutex<Vec<EventEnvelope>>>,
    fail_next: Arc<Mutex<Option<String>>>,
}
impl InMemoryEventPublisher {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn published(&self) -> Vec<EventEnvelope> {
        self.published
            .lock()
            .expect("publisher lock poisoned")
            .clone()
    }
    pub fn fail_next(&self, message: impl Into<String>) {
        *self.fail_next.lock().expect("publisher lock poisoned") = Some(message.into());
    }
}
impl EventPublisher for InMemoryEventPublisher {
    fn publish(&self, envelope: &EventEnvelope) -> Result<(), PublishFailed> {
        if let Some(message) = self
            .fail_next
            .lock()
            .expect("publisher lock poisoned")
            .take()
        {
            return Err(PublishFailed(message));
        }
        self.published
            .lock()
            .expect("publisher lock poisoned")
            .push(envelope.clone());
        Ok(())
    }
}
#[async_trait]
impl AsyncEventPublisher for InMemoryEventPublisher {
    async fn publish(&self, envelope: EventEnvelope) -> Result<(), PublishFailed> {
        EventPublisher::publish(self, &envelope)
    }
}

#[derive(Clone, Default)]
pub struct InMemoryEventSubscriber {
    state: Arc<SubscriberState>,
}
impl InMemoryEventSubscriber {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn has_seen(&self, event_id: &str) -> bool {
        self.state.has_seen(event_id)
    }
    pub fn process_received(
        &self,
        event: &ReceivedEvent,
        handler: &dyn Fn(EventEnvelope) -> HandlerResult,
    ) -> SubscriberAction {
        self.state.process_received(event, handler)
    }
    pub fn receive(
        &self,
        envelope: &EventEnvelope,
        handler: &dyn Fn(EventEnvelope) -> HandlerResult,
    ) -> HandlerResult {
        let event = ReceivedEvent {
            entry_id: "in-memory".into(),
            stream: format!("events:{}", envelope.producer),
            envelope: envelope.clone(),
            delivery_attempts: 1,
        };
        match self.process_received(&event, handler) {
            SubscriberAction::Ack | SubscriberAction::DeadLetterAndAck => HandlerResult::Success,
            SubscriberAction::LeavePending => {
                HandlerResult::TransientError("event left pending".into())
            }
        }
    }
}
impl EventSubscriber for InMemoryEventSubscriber {
    fn subscribe(
        &self,
        _streams: &[String],
        _group: &str,
        _consumer_name: &str,
        _handler: Box<dyn Fn(EventEnvelope) -> HandlerResult + Send + Sync>,
    ) -> Result<(), SubscribeFailed> {
        Ok(())
    }
}
#[async_trait]
impl AsyncEventSubscriber for InMemoryEventSubscriber {
    async fn subscribe(
        &self,
        _streams: Vec<String>,
        _group: String,
        _consumer_name: String,
        _handler: Box<dyn Fn(EventEnvelope) -> HandlerFuture + Send + Sync>,
    ) -> Result<(), SubscribeFailed> {
        Ok(())
    }
}

pub fn stream_for_producer(producer: &str) -> String {
    format!("events:{producer}")
}

#[cfg(feature = "redis-impl")]
pub mod redis_runtime {
    use super::*;
    use redis::RedisResult;
    use std::collections::VecDeque;
    use tokio::sync::mpsc;
    use tokio::time::{Duration, interval, sleep};

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
                client: redis::Client::open(url)
                    .map_err(|error| PublishFailed(error.to_string()))?,
            })
        }
        pub fn queued(self) -> QueuedRedisEventPublisher {
            let (tx, rx) = mpsc::unbounded_channel();
            let pending = Arc::new(Mutex::new(VecDeque::new()));
            tokio::spawn(drain_loop(self.client, rx, Arc::clone(&pending)));
            QueuedRedisEventPublisher { tx, pending }
        }
    }
    #[async_trait]
    impl AsyncEventPublisher for RedisEventPublisher {
        async fn publish(&self, envelope: EventEnvelope) -> Result<(), PublishFailed> {
            publish_with_retry(&self.client, envelope).await
        }
    }

    #[derive(Clone)]
    pub struct QueuedRedisEventPublisher {
        tx: mpsc::UnboundedSender<EventEnvelope>,
        pending: Arc<Mutex<VecDeque<EventEnvelope>>>,
    }
    impl QueuedRedisEventPublisher {
        pub fn pending(&self) -> Vec<EventEnvelope> {
            self.pending
                .lock()
                .expect("redis pending queue lock poisoned")
                .iter()
                .cloned()
                .collect()
        }

        pub fn pending_count(&self) -> usize {
            self.pending
                .lock()
                .expect("redis pending queue lock poisoned")
                .len()
        }

        pub fn health_pending(&self) -> serde_json::Value {
            serde_json::json!({"pendingEvents": self.pending_count()})
        }
    }
    impl EventPublisher for QueuedRedisEventPublisher {
        fn publish(&self, envelope: &EventEnvelope) -> Result<(), PublishFailed> {
            self.tx
                .send(envelope.clone())
                .map_err(|error| PublishFailed(format!("redis publish queue unavailable: {error}")))
        }
    }
    async fn drain_loop(
        client: redis::Client,
        mut rx: mpsc::UnboundedReceiver<EventEnvelope>,
        pending: Arc<Mutex<VecDeque<EventEnvelope>>>,
    ) {
        let mut retry_tick = interval(Duration::from_secs(1));
        let mut receiver_open = true;
        loop {
            tokio::select! {
                maybe_envelope = rx.recv(), if receiver_open => {
                    match maybe_envelope {
                        Some(envelope) => park_envelope(&pending, envelope),
                        None => receiver_open = false,
                    }
                }
                _ = retry_tick.tick() => {}
            }

            if let Err(error) = try_drain_pending(&client, &pending).await {
                log::error!("redis publisher parked event after retries: {}", error.0);
            }

            if !receiver_open && pending_count(&pending) == 0 {
                break;
            }
        }
    }

    fn park_envelope(pending: &Arc<Mutex<VecDeque<EventEnvelope>>>, envelope: EventEnvelope) {
        pending
            .lock()
            .expect("redis pending queue lock poisoned")
            .push_back(envelope);
    }

    fn pending_count(pending: &Arc<Mutex<VecDeque<EventEnvelope>>>) -> usize {
        pending
            .lock()
            .expect("redis pending queue lock poisoned")
            .len()
    }

    async fn try_drain_pending(
        client: &redis::Client,
        pending: &Arc<Mutex<VecDeque<EventEnvelope>>>,
    ) -> Result<(), PublishFailed> {
        try_drain_pending_with(pending, |envelope| publish_with_retry(client, envelope)).await
    }

    async fn try_drain_pending_with<F, Fut>(
        pending: &Arc<Mutex<VecDeque<EventEnvelope>>>,
        mut publish: F,
    ) -> Result<(), PublishFailed>
    where
        F: FnMut(EventEnvelope) -> Fut,
        Fut: Future<Output = Result<(), PublishFailed>>,
    {
        loop {
            let Some(envelope) = pending
                .lock()
                .expect("redis pending queue lock poisoned")
                .front()
                .cloned()
            else {
                return Ok(());
            };

            if let Err(error) = publish(envelope.clone()).await {
                return Err(error);
            }

            let mut guard = pending.lock().expect("redis pending queue lock poisoned");
            if guard.front().map(|front| front.event_id.as_str())
                == Some(envelope.event_id.as_str())
            {
                guard.pop_front();
            }
        }
    }
    pub async fn publish_with_retry(
        client: &redis::Client,
        envelope: EventEnvelope,
    ) -> Result<(), PublishFailed> {
        let stream = stream_for_producer(&envelope.producer);
        let raw_envelope =
            serde_json::to_string(&envelope).map_err(|error| PublishFailed(error.to_string()))?;
        let mut last_error = None;
        for attempt in 0..3 {
            let result: RedisResult<String> = async {
                let mut connection = client.get_multiplexed_async_connection().await?;
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
                Ok(_) => return Ok(()),
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

    #[async_trait]
    trait StreamOps: Send {
        async fn create_group(&mut self, stream: &str, group: &str) -> Result<(), SubscribeFailed>;
        async fn recover_pending(
            &mut self,
            stream: &str,
            group: &str,
            consumer_name: &str,
        ) -> Result<Vec<StreamMessage>, SubscribeFailed>;
        async fn read_group(
            &mut self,
            streams: &[String],
            group: &str,
            consumer_name: &str,
        ) -> Result<Vec<StreamMessage>, SubscribeFailed>;
        async fn delivery_count(
            &mut self,
            stream: &str,
            group: &str,
            id: &str,
        ) -> Result<u64, SubscribeFailed>;
        async fn xadd_dlq(
            &mut self,
            dlq: &str,
            raw_envelope: &str,
            fields: Vec<(&'static str, String)>,
        ) -> Result<(), SubscribeFailed>;
        async fn ack(&mut self, stream: &str, group: &str, id: &str)
        -> Result<(), SubscribeFailed>;
    }

    struct RedisStreamOps {
        connection: redis::aio::MultiplexedConnection,
    }

    #[async_trait]
    impl StreamOps for RedisStreamOps {
        async fn create_group(&mut self, stream: &str, group: &str) -> Result<(), SubscribeFailed> {
            let result: RedisResult<String> = redis::cmd("XGROUP")
                .arg("CREATE")
                .arg(stream)
                .arg(group)
                .arg("$")
                .arg("MKSTREAM")
                .query_async(&mut self.connection)
                .await;
            if let Err(error) = result {
                if !error.to_string().contains("BUSYGROUP") {
                    return Err(SubscribeFailed(format!(
                        "failed to create consumer group for {stream}: {error}"
                    )));
                }
            }
            Ok(())
        }

        async fn recover_pending(
            &mut self,
            stream: &str,
            group: &str,
            consumer_name: &str,
        ) -> Result<Vec<StreamMessage>, SubscribeFailed> {
            let response: redis::Value = redis::cmd("XAUTOCLAIM")
                .arg(stream)
                .arg(group)
                .arg(consumer_name)
                .arg(60_000)
                .arg("0-0")
                .arg("COUNT")
                .arg(100)
                .query_async(&mut self.connection)
                .await
                .map_err(|error| SubscribeFailed(error.to_string()))?;
            Ok(parse_autoclaim_messages(stream, response))
        }

        async fn read_group(
            &mut self,
            streams: &[String],
            group: &str,
            consumer_name: &str,
        ) -> Result<Vec<StreamMessage>, SubscribeFailed> {
            let response: redis::Value = redis::cmd("XREADGROUP")
                .arg("GROUP")
                .arg(group)
                .arg(consumer_name)
                .arg("BLOCK")
                .arg(2_000)
                .arg("COUNT")
                .arg(10)
                .arg("STREAMS")
                .arg(streams)
                .arg(vec![">"; streams.len()])
                .query_async(&mut self.connection)
                .await
                .map_err(|error| SubscribeFailed(error.to_string()))?;
            Ok(parse_stream_messages(response))
        }

        async fn delivery_count(
            &mut self,
            stream: &str,
            group: &str,
            id: &str,
        ) -> Result<u64, SubscribeFailed> {
            pending_delivery_count(&mut self.connection, stream, group, id).await
        }

        async fn xadd_dlq(
            &mut self,
            dlq: &str,
            raw_envelope: &str,
            fields: Vec<(&'static str, String)>,
        ) -> Result<(), SubscribeFailed> {
            let _: String = redis::cmd("XADD")
                .arg(dlq)
                .arg("MAXLEN")
                .arg("~")
                .arg(RETENTION_MAXLEN)
                .arg("*")
                .arg("envelope")
                .arg(raw_envelope)
                .arg(fields)
                .query_async(&mut self.connection)
                .await
                .map_err(|error| SubscribeFailed(error.to_string()))?;
            Ok(())
        }

        async fn ack(
            &mut self,
            stream: &str,
            group: &str,
            id: &str,
        ) -> Result<(), SubscribeFailed> {
            ack(&mut self.connection, stream, group, id).await
        }
    }

    #[derive(Clone)]
    pub struct RedisEventSubscriber {
        client: redis::Client,
        state: Arc<SubscriberState>,
        stop: Arc<std::sync::atomic::AtomicBool>,
    }
    impl RedisEventSubscriber {
        pub fn from_env() -> Result<Self, SubscribeFailed> {
            let url =
                std::env::var("REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379".to_string());
            Self::new(&url)
        }
        pub fn new(url: &str) -> Result<Self, SubscribeFailed> {
            Ok(Self {
                client: redis::Client::open(url)
                    .map_err(|error| SubscribeFailed(error.to_string()))?,
                state: Arc::new(SubscriberState::new()),
                stop: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            })
        }
        pub fn shutdown(&self) {
            self.stop.store(true, std::sync::atomic::Ordering::SeqCst);
        }
        async fn create_groups_with_ops(
            ops: &mut dyn StreamOps,
            streams: &[String],
            group: &str,
        ) -> Result<(), SubscribeFailed> {
            for stream in streams {
                ops.create_group(stream, group).await?;
            }
            Ok(())
        }
        // A non-persistent Redis loses consumer groups on restart: a NOGROUP
        // error means recreate the groups and carry on. Every consume error
        // backs off so a dead connection never hot-spins the subscribe loop.
        async fn handle_consume_error_with_ops(
            ops: &mut dyn StreamOps,
            streams: &[String],
            group: &str,
            error: &str,
        ) {
            if error.to_uppercase().contains("NOGROUP") {
                let _ = Self::create_groups_with_ops(ops, streams, group).await;
            }
            sleep(Duration::from_secs(1)).await;
        }
        async fn recover_pending_with_ops(
            &self,
            ops: &mut dyn StreamOps,
            streams: &[String],
            group: &str,
            consumer_name: &str,
            handler: &(dyn Fn(EventEnvelope) -> HandlerFuture + Send + Sync),
        ) -> Result<(), SubscribeFailed> {
            for stream in streams {
                let mut messages = ops.recover_pending(stream, group, consumer_name).await?;
                for message in &mut messages {
                    message.delivery_count = ops
                        .delivery_count(&message.stream, group, &message.id)
                        .await
                        .unwrap_or(message.delivery_count);
                    if message.delivery_count >= MAX_DELIVERY_ATTEMPTS {
                        move_to_dlq(
                            ops,
                            &message.stream,
                            group,
                            consumer_name,
                            &message.id,
                            &message.raw_envelope,
                            "MaxDeliveryAttempts",
                            message.delivery_count,
                        )
                        .await?;
                    } else {
                        self.process_message(ops, group, consumer_name, message.clone(), handler)
                            .await?;
                    }
                }
            }
            Ok(())
        }
        async fn process_message(
            &self,
            ops: &mut dyn StreamOps,
            group: &str,
            consumer_name: &str,
            message: StreamMessage,
            handler: &(dyn Fn(EventEnvelope) -> HandlerFuture + Send + Sync),
        ) -> Result<(), SubscribeFailed> {
            let envelope: EventEnvelope = match serde_json::from_str(&message.raw_envelope) {
                Ok(envelope) => envelope,
                Err(error) => {
                    return move_to_dlq(
                        ops,
                        &message.stream,
                        group,
                        consumer_name,
                        &message.id,
                        &message.raw_envelope,
                        &error.to_string(),
                        message.delivery_count,
                    )
                    .await;
                }
            };
            if self.state.has_seen(&envelope.event_id) {
                return ops.ack(&message.stream, group, &message.id).await;
            }
            match handler(envelope.clone()).await {
                Ok(()) => {
                    self.state.mark_consumed(&envelope.event_id);
                    ops.ack(&message.stream, group, &message.id).await
                }
                Err(HandlerError::Transient(reason)) => {
                    // Formerly a silent swallow (same class of bug java-kit had):
                    // without this line a retried-to-death message reaches the
                    // DLQ with no trace of what actually failed.
                    log::warn!(
                        "service={} stream={} eventId={} deliveries={} handler transient failure; message stays pending for retry: {}",
                        group,
                        message.stream,
                        envelope.event_id,
                        message.delivery_count,
                        reason
                    );
                    Ok(())
                }
                Err(HandlerError::Fatal(reason)) => {
                    self.state.mark_consumed(&envelope.event_id);
                    move_to_dlq(
                        ops,
                        &message.stream,
                        group,
                        consumer_name,
                        &message.id,
                        &message.raw_envelope,
                        &reason,
                        message.delivery_count,
                    )
                    .await
                }
            }
        }

        async fn subscribe_with_ops(
            &self,
            ops: &mut dyn StreamOps,
            streams: Vec<String>,
            group: String,
            consumer_name: String,
            handler: Box<dyn Fn(EventEnvelope) -> HandlerFuture + Send + Sync>,
            run_once: bool,
        ) -> Result<(), SubscribeFailed> {
            Self::create_groups_with_ops(ops, &streams, &group).await?;
            loop {
                if self.stop.load(std::sync::atomic::Ordering::SeqCst) {
                    return Ok(());
                }
                if let Err(error) = self
                    .recover_pending_with_ops(
                        ops,
                        &streams,
                        &group,
                        &consumer_name,
                        handler.as_ref(),
                    )
                    .await
                {
                    Self::handle_consume_error_with_ops(ops, &streams, &group, &error.0).await;
                    if run_once {
                        return Err(error);
                    }
                    continue;
                }
                let messages = match ops.read_group(&streams, &group, &consumer_name).await {
                    Ok(messages) => messages,
                    Err(error) => {
                        Self::handle_consume_error_with_ops(ops, &streams, &group, &error.0).await;
                        if run_once {
                            return Err(error);
                        }
                        continue;
                    }
                };
                for message in messages {
                    self.process_message(ops, &group, &consumer_name, message, handler.as_ref())
                        .await?;
                }
                if run_once {
                    return Ok(());
                }
            }
        }
    }
    #[async_trait]
    impl AsyncEventSubscriber for RedisEventSubscriber {
        async fn subscribe(
            &self,
            streams: Vec<String>,
            group: String,
            consumer_name: String,
            handler: Box<dyn Fn(EventEnvelope) -> HandlerFuture + Send + Sync>,
        ) -> Result<(), SubscribeFailed> {
            let connection = self
                .client
                .get_multiplexed_async_connection()
                .await
                .map_err(|error| SubscribeFailed(error.to_string()))?;
            let mut ops = RedisStreamOps { connection };
            self.subscribe_with_ops(&mut ops, streams, group, consumer_name, handler, false)
                .await
        }
    }

    #[derive(Debug, Clone)]
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
                            delivery_count: 0,
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
    async fn ack(
        connection: &mut redis::aio::MultiplexedConnection,
        stream: &str,
        group: &str,
        id: &str,
    ) -> Result<(), SubscribeFailed> {
        let _: usize = redis::cmd("XACK")
            .arg(stream)
            .arg(group)
            .arg(id)
            .query_async(connection)
            .await
            .map_err(|error| SubscribeFailed(error.to_string()))?;
        Ok(())
    }
    async fn pending_delivery_count(
        connection: &mut redis::aio::MultiplexedConnection,
        stream: &str,
        group: &str,
        id: &str,
    ) -> Result<u64, SubscribeFailed> {
        let value: redis::Value = redis::cmd("XPENDING")
            .arg(stream)
            .arg(group)
            .arg(id)
            .arg(id)
            .arg(1)
            .query_async(connection)
            .await
            .map_err(|error| SubscribeFailed(error.to_string()))?;
        Ok(parse_xpending_delivery_count(value).unwrap_or(1))
    }
    pub fn parse_xpending_delivery_count(value: redis::Value) -> Option<u64> {
        let redis::Value::Bulk(entries) = value else {
            return None;
        };
        let redis::Value::Bulk(entry) = entries.first()? else {
            return None;
        };
        match entry.get(3)? {
            redis::Value::Int(count) => (*count).try_into().ok(),
            other => redis_value_to_string(other)?.parse().ok(),
        }
    }
    async fn move_to_dlq(
        ops: &mut dyn StreamOps,
        stream: &str,
        group: &str,
        consumer_name: &str,
        id: &str,
        raw_envelope: &str,
        reason: &str,
        attempts: u64,
    ) -> Result<(), SubscribeFailed> {
        let dlq = format!("{stream}:dlq");
        let failure_reason = truncate_failure_reason(reason);
        log::warn!(
            "service={} stream={} eventId={} failureReason={} moving message to DLQ",
            group,
            stream,
            event_id_for_log(raw_envelope),
            failure_reason
        );
        ops.xadd_dlq(
            &dlq,
            raw_envelope,
            dlq_metadata_fields(
                group,
                consumer_name,
                &failure_reason,
                attempts,
                &crate::messaging::now_rfc3339_utc(),
            ),
        )
        .await?;
        ops.ack(stream, group, id).await
    }

    fn truncate_failure_reason(reason: &str) -> String {
        reason.chars().take(500).collect()
    }

    fn event_id_for_log(raw_envelope: &str) -> String {
        serde_json::from_str::<EventEnvelope>(raw_envelope)
            .map(|envelope| envelope.event_id)
            .unwrap_or_else(|_| "unknown".to_string())
    }

    fn dlq_metadata_fields(
        group: &str,
        consumer_name: &str,
        failure_reason: &str,
        attempts: u64,
        dead_lettered_at: &str,
    ) -> Vec<(&'static str, String)> {
        vec![
            ("consumerGroup", group.to_string()),
            ("consumerName", consumer_name.to_string()),
            ("failureReason", failure_reason.to_string()),
            ("attempts", attempts.max(1).to_string()),
            ("deadLetteredAt", dead_lettered_at.to_string()),
        ]
    }
    fn redis_value_to_string(value: &redis::Value) -> Option<String> {
        match value {
            redis::Value::Data(bytes) => String::from_utf8(bytes.clone()).ok(),
            redis::Value::Status(value) => Some(value.clone()),
            redis::Value::Okay => Some("OK".to_string()),
            _ => None,
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        #[test]
        fn xpending_delivery_count_controls_dlq_threshold() {
            let value = redis::Value::Bulk(vec![redis::Value::Bulk(vec![
                redis::Value::Data(b"1700000000000-0".to_vec()),
                redis::Value::Data(b"consumer-a".to_vec()),
                redis::Value::Int(42),
                redis::Value::Int(5),
            ])]);
            assert_eq!(parse_xpending_delivery_count(value), Some(5));
        }

        #[test]
        fn dlq_metadata_fields_are_camel_case_and_attributed() {
            let fields = dlq_metadata_fields(
                "capacity-availability",
                "capacity-availability-1",
                "Fatal: missing segmentRef",
                0,
                "2026-07-07T12:00:00.000Z",
            );
            assert_eq!(
                fields,
                vec![
                    ("consumerGroup", "capacity-availability".to_string()),
                    ("consumerName", "capacity-availability-1".to_string()),
                    ("failureReason", "Fatal: missing segmentRef".to_string()),
                    ("attempts", "1".to_string()),
                    ("deadLetteredAt", "2026-07-07T12:00:00.000Z".to_string()),
                ]
            );
        }

        #[derive(Default)]
        struct FakeStreamOps {
            read_messages: Vec<StreamMessage>,
            dlq_entries: Vec<(String, String, Vec<(&'static str, String)>)>,
            acked: Vec<(String, String, String)>,
            created_groups: Vec<(String, String)>,
        }

        #[async_trait]
        impl StreamOps for FakeStreamOps {
            async fn create_group(
                &mut self,
                stream: &str,
                group: &str,
            ) -> Result<(), SubscribeFailed> {
                self.created_groups
                    .push((stream.to_string(), group.to_string()));
                Ok(())
            }

            async fn recover_pending(
                &mut self,
                _stream: &str,
                _group: &str,
                _consumer_name: &str,
            ) -> Result<Vec<StreamMessage>, SubscribeFailed> {
                Ok(Vec::new())
            }

            async fn read_group(
                &mut self,
                _streams: &[String],
                _group: &str,
                _consumer_name: &str,
            ) -> Result<Vec<StreamMessage>, SubscribeFailed> {
                Ok(std::mem::take(&mut self.read_messages))
            }

            async fn delivery_count(
                &mut self,
                _stream: &str,
                _group: &str,
                _id: &str,
            ) -> Result<u64, SubscribeFailed> {
                Ok(1)
            }

            async fn xadd_dlq(
                &mut self,
                dlq: &str,
                raw_envelope: &str,
                fields: Vec<(&'static str, String)>,
            ) -> Result<(), SubscribeFailed> {
                self.dlq_entries
                    .push((dlq.to_string(), raw_envelope.to_string(), fields));
                Ok(())
            }

            async fn ack(
                &mut self,
                stream: &str,
                group: &str,
                id: &str,
            ) -> Result<(), SubscribeFailed> {
                self.acked
                    .push((stream.to_string(), group.to_string(), id.to_string()));
                Ok(())
            }
        }

        #[tokio::test]
        async fn subscription_processing_fatal_handler_writes_dlq_metadata_warn_log_and_acks() {
            let mut logger = logtest::Logger::start();
            let subscriber = RedisEventSubscriber {
                client: redis::Client::open("redis://127.0.0.1:0").unwrap(),
                state: Arc::new(SubscriberState::new()),
                stop: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            };
            let envelope = EventEnvelope::canonical(
                "PoisonEvent",
                correlation_id(),
                Some(command_id()),
                "payment",
                serde_json::json!({"id":"1"}),
            );
            let raw = serde_json::to_string(&envelope).unwrap();
            let mut ops = FakeStreamOps {
                read_messages: vec![StreamMessage {
                    stream: "events:payment".to_string(),
                    id: "1-0".to_string(),
                    raw_envelope: raw.clone(),
                    delivery_count: 3,
                }],
                ..Default::default()
            };

            subscriber
                .subscribe_with_ops(
                    &mut ops,
                    vec!["events:payment".to_string()],
                    "journey-order".to_string(),
                    "consumer-1".to_string(),
                    Box::new(|_| {
                        Box::pin(async { Err(HandlerError::Fatal("poison root cause".into())) })
                    }),
                    true,
                )
                .await
                .unwrap();

            assert_eq!(
                ops.created_groups,
                vec![("events:payment".to_string(), "journey-order".to_string())]
            );
            assert_eq!(ops.dlq_entries.len(), 1);
            assert_eq!(ops.dlq_entries[0].0, "events:payment:dlq");
            assert_eq!(ops.dlq_entries[0].1, raw);
            let metadata: std::collections::HashMap<_, _> =
                ops.dlq_entries[0].2.iter().cloned().collect();
            assert_eq!(
                metadata.get("consumerGroup"),
                Some(&"journey-order".to_string())
            );
            assert_eq!(
                metadata.get("consumerName"),
                Some(&"consumer-1".to_string())
            );
            assert_eq!(
                metadata.get("failureReason"),
                Some(&"poison root cause".to_string())
            );
            assert_eq!(metadata.get("attempts"), Some(&"3".to_string()));
            assert!(metadata.get("deadLetteredAt").is_some());
            assert_eq!(
                ops.acked,
                vec![(
                    "events:payment".to_string(),
                    "journey-order".to_string(),
                    "1-0".to_string()
                )]
            );
            assert!(subscriber.state.has_seen(&envelope.event_id));
            let log = logger.pop().expect("expected WARN DLQ log");
            assert_eq!(log.level(), log::Level::Warn);
            assert!(log.args().contains("events:payment"));
            assert!(log.args().contains(&envelope.event_id));
            assert!(log.args().contains("poison root cause"));
        }

        #[tokio::test]
        async fn queued_publisher_parks_failed_events_and_drains_after_recovery() {
            let pending = Arc::new(Mutex::new(std::collections::VecDeque::new()));
            let envelope = EventEnvelope::canonical(
                "TestEvent",
                correlation_id(),
                Some(command_id()),
                "rust-kit-test",
                serde_json::json!({"ok": true}),
            );
            park_envelope(&pending, envelope.clone());
            assert_eq!(pending_count(&pending), 1);

            let first_attempt = try_drain_pending_with(&pending, |_| async {
                Err(PublishFailed("redis unavailable".into()))
            })
            .await;
            assert!(first_attempt.is_err());
            assert_eq!(pending_count(&pending), 1);

            let delivered = Arc::new(Mutex::new(Vec::new()));
            let delivered_for_publish = Arc::clone(&delivered);
            try_drain_pending_with(&pending, move |event| {
                let delivered = Arc::clone(&delivered_for_publish);
                async move {
                    delivered
                        .lock()
                        .expect("delivered lock poisoned")
                        .push(event);
                    Ok(())
                }
            })
            .await
            .unwrap();

            assert_eq!(pending_count(&pending), 0);
            assert_eq!(delivered.lock().expect("delivered lock poisoned").len(), 1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn envelope_has_canonical_contract_fields() {
        let envelope = EventEnvelope::canonical(
            "EntitlementIssued",
            correlation_id(),
            Some(command_id()),
            "entitlement-ticketing",
            serde_json::json!({"a":1}),
        );
        let value = serde_json::to_value(&envelope).unwrap();
        let object = value.as_object().unwrap();
        assert_eq!(object.len(), 8);
        assert!(envelope.event_id.starts_with("evt-"));
        assert!(envelope.correlation_id.starts_with("corr-"));
        assert!(envelope.occurred_at.ends_with('Z'));
    }

    #[test]
    fn envelope_rejects_invalid_correlation_and_causation_ids() {
        assert!(
            EventEnvelope::try_canonical(
                "Test",
                "corr-not-a-uuid-v7",
                Some(command_id()),
                "test",
                serde_json::json!({}),
            )
            .is_err()
        );
        assert!(
            EventEnvelope::try_canonical(
                "Test",
                correlation_id(),
                Some("cmd-not-a-uuid-v7"),
                "test",
                serde_json::json!({}),
            )
            .is_err()
        );
        assert!(
            EventEnvelope::try_canonical(
                "Test",
                correlation_id(),
                None::<String>,
                "test",
                serde_json::json!({}),
            )
            .is_ok()
        );
    }
    #[test]
    fn dedup_records_event_id_only_after_success() {
        let subscriber = InMemoryEventSubscriber::new();
        let envelope = EventEnvelope::canonical(
            "Test",
            correlation_id(),
            Some(command_id()),
            "test",
            serde_json::json!({}),
        );
        let event = ReceivedEvent {
            entry_id: "1-0".into(),
            stream: "events:test".into(),
            envelope: envelope.clone(),
            delivery_attempts: 1,
        };
        assert_eq!(
            subscriber.process_received(&event, &|_| HandlerResult::TransientError(
                "temporary".into()
            )),
            SubscriberAction::LeavePending
        );
        assert!(!subscriber.has_seen(&envelope.event_id));
        assert_eq!(
            subscriber.process_received(&event, &|_| HandlerResult::Success),
            SubscriberAction::Ack
        );
        assert!(subscriber.has_seen(&envelope.event_id));
    }
}
