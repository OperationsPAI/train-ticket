// ---------------------------------------------------------------------------
// Abstract ports per docs/08-contracts/messaging.md §10
// Broker-neutral — no Redis types, no stream-key strings.
// ---------------------------------------------------------------------------

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// Wire-level event envelope matching shared-primitives.md §1.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WireEnvelope {
    pub event_id: String,
    pub event_type: String,
    pub schema_version: u32,
    pub producer: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub causation_id: Option<String>,
    pub correlation_id: String,
    pub occurred_at: String, // RFC3339 UTC
    pub payload: Value,
}

/// Error returned when publishing fails.
#[derive(Debug, Clone)]
pub struct PublishFailed(pub String);

impl std::fmt::Display for PublishFailed {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "publish failed: {}", self.0)
    }
}

impl std::error::Error for PublishFailed {}

/// Abstract port: EventPublisher
///
/// Domain and application layers depend ONLY on this trait.
/// No Redis type or stream-key string may appear outside the adapter.
pub trait EventPublisher: Send + Sync + 'static {
    /// Publish a domain event to the event bus.
    ///
    /// The implementation MUST determine the target stream from the `producer`
    /// field of the envelope.
    fn publish(&self, envelope: &WireEnvelope) -> Result<(), PublishFailed>;
}

/// Error returned when subscribing fails.
#[derive(Debug, Clone)]
pub struct SubscribeFailed(pub String);

impl std::fmt::Display for SubscribeFailed {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "subscribe failed: {}", self.0)
    }
}

impl std::error::Error for SubscribeFailed {}

/// Result from an event handler.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HandlerResult {
    /// Event processed successfully.
    Success,
    /// Transient error — do NOT XACK; let XAUTOCLAIM recover.
    TransientError(String),
    /// Fatal error — move to DLQ and XACK.
    FatalError(String),
}

/// Abstract port: EventSubscriber
///
/// Domain and application layers depend ONLY on this port.
pub trait EventSubscriber: Send + Sync + 'static {
    /// Subscribe to one or more event streams as a consumer group member.
    fn subscribe(
        &self,
        streams: &[String],
        group: &str,
        consumer_name: &str,
        handler: Box<dyn Fn(WireEnvelope) -> HandlerResult + Send + Sync>,
    ) -> Result<(), SubscribeFailed>;
}
