//! Broker-independent application-layer messaging ports.

use std::{fmt, future::Future, pin::Pin};

use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EventEnvelope {
    pub event_id: String,
    pub event_type: String,
    pub schema_version: u32,
    pub occurred_at: String,
    pub correlation_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub causation_id: Option<String>,
    pub producer: String,
    pub payload: Value,
}

impl EventEnvelope {
    pub fn new(
        event_type: impl Into<String>,
        occurred_at: impl Into<String>,
        correlation_id: impl Into<String>,
        causation_id: Option<impl Into<String>>,
        producer: impl Into<String>,
        payload: Value,
    ) -> Self {
        Self {
            event_id: format!("evt-{}", uuid::Uuid::now_v7()),
            event_type: event_type.into(),
            schema_version: 1,
            occurred_at: occurred_at.into(),
            correlation_id: correlation_id.into(),
            causation_id: causation_id.map(Into::into),
            producer: producer.into(),
            payload,
        }
    }
}

#[derive(Debug, Clone)]
pub struct PublishFailed(pub String);

impl fmt::Display for PublishFailed {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "publish failed: {}", self.0)
    }
}

impl std::error::Error for PublishFailed {}

#[async_trait::async_trait]
pub trait EventPublisher: Send + Sync + 'static {
    async fn publish(&self, envelope: EventEnvelope) -> Result<(), PublishFailed>;
}

pub type HandlerFuture = Pin<Box<dyn Future<Output = Result<(), HandlerError>> + Send>>;

#[derive(Debug, Clone)]
pub struct SubscribeFailed(pub String);

impl fmt::Display for SubscribeFailed {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "subscribe failed: {}", self.0)
    }
}

impl std::error::Error for SubscribeFailed {}

#[derive(Debug, Clone)]
pub enum HandlerError {
    Transient(String),
    Fatal(String),
}

#[async_trait::async_trait]
pub trait EventSubscriber: Send + Sync + 'static {
    async fn subscribe(
        &self,
        streams: Vec<String>,
        group: String,
        consumer_name: String,
        handler: Box<dyn Fn(EventEnvelope) -> HandlerFuture + Send + Sync>,
    ) -> Result<(), SubscribeFailed>;
}
