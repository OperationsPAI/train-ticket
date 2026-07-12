pub use rust_kit::messaging::InMemoryEventPublisher;

#[derive(Clone)]
pub struct RedisEventPublisher {
    inner: rust_kit::messaging::redis_runtime::RedisEventPublisher,
}

impl RedisEventPublisher {
    pub fn from_env() -> Result<Self, rust_kit::messaging::PublishFailed> {
        Ok(Self {
            inner: rust_kit::messaging::redis_runtime::RedisEventPublisher::from_env()?,
        })
    }

    pub fn new(url: &str) -> Result<Self, rust_kit::messaging::PublishFailed> {
        Ok(Self {
            inner: rust_kit::messaging::redis_runtime::RedisEventPublisher::new(url)?,
        })
    }
}

#[async_trait::async_trait]
impl rust_kit::messaging::AsyncEventPublisher for RedisEventPublisher {
    async fn publish(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), rust_kit::messaging::PublishFailed> {
        rust_kit::messaging::AsyncEventPublisher::publish(&self.inner, envelope).await
    }
}

#[derive(Clone)]
pub struct RedisEventSubscriber {
    inner: rust_kit::messaging::redis_runtime::RedisEventSubscriber,
}

impl RedisEventSubscriber {
    pub fn from_env() -> Result<Self, rust_kit::messaging::SubscribeFailed> {
        Ok(Self {
            inner: rust_kit::messaging::redis_runtime::RedisEventSubscriber::from_env()?,
        })
    }

    pub fn new(url: &str) -> Result<Self, rust_kit::messaging::SubscribeFailed> {
        Ok(Self {
            inner: rust_kit::messaging::redis_runtime::RedisEventSubscriber::new(url)?,
        })
    }

    pub fn entitlement_streams() -> Vec<String> {
        [
            "events:booking-orchestration",
            "events:fulfillment",
            "events:post-sales",
        ]
        .into_iter()
        .map(ToOwned::to_owned)
        .collect()
    }

    pub fn entitlement_group() -> &'static str {
        "entitlement-ticketing"
    }

    pub fn shutdown(&self) {
        self.inner.shutdown();
    }
}

#[async_trait::async_trait]
impl rust_kit::messaging::AsyncEventSubscriber for RedisEventSubscriber {
    async fn subscribe(
        &self,
        streams: Vec<String>,
        group: String,
        consumer_name: String,
        handler: Box<
            dyn Fn(rust_kit::messaging::EventEnvelope) -> rust_kit::messaging::HandlerFuture
                + Send
                + Sync,
        >,
    ) -> Result<(), rust_kit::messaging::SubscribeFailed> {
        rust_kit::messaging::AsyncEventSubscriber::subscribe(
            &self.inner,
            streams,
            group,
            consumer_name,
            handler,
        )
        .await
    }
}

#[derive(Default)]
pub struct DeduplicatingEventHandler {
    seen: std::sync::Mutex<std::collections::HashSet<String>>,
    handled: std::sync::Mutex<Vec<rust_kit::messaging::EventEnvelope>>,
}

impl DeduplicatingEventHandler {
    pub fn handle(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), rust_kit::messaging::HandlerError> {
        if self
            .seen
            .lock()
            .expect("dedup lock poisoned")
            .contains(&envelope.event_id)
        {
            return Ok(());
        }
        self.seen
            .lock()
            .expect("dedup lock poisoned")
            .insert(envelope.event_id.clone());
        self.handled
            .lock()
            .expect("handled lock poisoned")
            .push(envelope);
        Ok(())
    }

    pub fn handled_count(&self) -> usize {
        self.handled.lock().expect("handled lock poisoned").len()
    }
}
