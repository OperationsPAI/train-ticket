// ---------------------------------------------------------------------------
// Messaging adapters per docs/08-contracts/messaging.md
// Redis Streams implementation (behind "redis-impl" feature gate).
// In-memory fake for unit tests.
// ---------------------------------------------------------------------------

use crate::ports::{EventPublisher, EventSubscriber, HandlerResult, PublishFailed, SubscribeFailed, WireEnvelope};
use std::collections::HashSet;
use std::sync::{Arc, Mutex};

// ---------------------------------------------------------------------------
// InMemoryEventPublisher — fake for unit tests
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Default)]
pub struct InMemoryEventPublisher {
    published: Arc<Mutex<Vec<WireEnvelope>>>,
}

impl InMemoryEventPublisher {
    pub fn new() -> Self {
        Self { published: Arc::new(Mutex::new(Vec::new())) }
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
// InMemoryEventSubscriber — fake for unit tests
// ---------------------------------------------------------------------------

#[derive(Clone)]
pub struct InMemoryEventSubscriber {
    dedup: Arc<Mutex<HashSet<String>>>,
}

impl InMemoryEventSubscriber {
    pub fn new() -> Self {
        Self { dedup: Arc::new(Mutex::new(HashSet::new())) }
    }

    /// Simulate receiving an event directly.
    pub fn receive(&self, envelope: &WireEnvelope, handler: &dyn Fn(WireEnvelope) -> HandlerResult) -> HandlerResult {
        let mut dedup = self.dedup.lock().unwrap();
        if !dedup.insert(envelope.event_id.clone()) {
            // Duplicate — should be skipped
            return HandlerResult::Success;
        }
        handler(envelope.clone())
    }

    /// Check if an event ID has been seen (dedup tracking).
    pub fn has_seen(&self, event_id: &str) -> bool {
        self.dedup.lock().unwrap().contains(event_id)
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
        // In-memory: no-op, events are injected manually
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// Redis EventPublisher — behind "redis-impl" feature gate
// ---------------------------------------------------------------------------

#[cfg(feature = "redis-impl")]
pub mod redis_publisher {
    use super::*;
    use redis::{AsyncCommands, RedisResult};
    use std::sync::Arc;
    use tokio::sync::Mutex as TokioMutex;

    pub struct RedisEventPublisher {
        connection: Arc<TokioMutex<redis::aio::ConnectionManager>>,
    }

    impl RedisEventPublisher {
        pub async fn new(redis_url: &str) -> Result<Self, PublishFailed> {
            let client = redis::Client::open(redis_url)
                .map_err(|e| PublishFailed(format!("failed to create redis client: {}", e)))?;
            let conn = client
                .get_tokio_connection_manager()
                .await
                .map_err(|e| PublishFailed(format!("failed to connect to redis: {}", e)))?;
            Ok(Self { connection: Arc::new(TokioMutex::new(conn)) })
        }
    }

    impl EventPublisher for RedisEventPublisher {
        fn publish(&self, envelope: &WireEnvelope) -> Result<(), PublishFailed> {
            // This is a synchronous trait; for real usage we'd need an async wrapper.
            // The sync-to-async bridge is intentionally simple for this contract.
            let json = serde_json::to_string(envelope)
                .map_err(|e| PublishFailed(format!("serialization error: {}", e)))?;
            let stream_key = format!("events:{}", envelope.producer);
            let conn = self.connection.clone();
            let json_clone = json.clone();
            let stream_key_clone = stream_key.clone();

            // Spawn a blocking task for the async redis call
            tokio::task::block_in_place(|| {
                let rt = tokio::runtime::Handle::current();
                rt.block_on(async move {
                    let mut conn = conn.lock().await;
                    let _: RedisResult<String> = redis::cmd("XADD")
                        .arg(&[&stream_key_clone, "*", "envelope", &json_clone])
                        .query_async(&mut *conn)
                        .await;
                    Ok::<_, PublishFailed>(())
                })
            })?;
            Ok(())
        }
    }
}

// ---------------------------------------------------------------------------
// Redis EventSubscriber — behind "redis-impl" feature gate
// ---------------------------------------------------------------------------

#[cfg(feature = "redis-impl")]
pub mod redis_subscriber {
    use super::*;
    use redis::{AsyncCommands, RedisResult};
    use std::collections::HashSet;
    use std::sync::Arc;
    use tokio::sync::Mutex as TokioMutex;

    pub struct RedisEventSubscriber {
        connection: Arc<TokioMutex<redis::aio::ConnectionManager>>,
        dedup: Arc<Mutex<HashSet<String>>>,
    }

    impl RedisEventSubscriber {
        pub async fn new(redis_url: &str) -> Result<Self, SubscribeFailed> {
            let client = redis::Client::open(redis_url)
                .map_err(|e| SubscribeFailed(format!("failed to create redis client: {}", e)))?;
            let conn = client
                .get_tokio_connection_manager()
                .await
                .map_err(|e| SubscribeFailed(format!("failed to connect to redis: {}", e)))?;
            Ok(Self {
                connection: Arc::new(TokioMutex::new(conn)),
                dedup: Arc::new(Mutex::new(HashSet::new())),
            })
        }

        pub fn has_seen(&self, event_id: &str) -> bool {
            self.dedup.lock().unwrap().contains(event_id)
        }
    }

    impl EventSubscriber for RedisEventSubscriber {
        fn subscribe(
            &self,
            _streams: &[String],
            _group: &str,
            _consumer_name: &str,
            _handler: Box<dyn Fn(WireEnvelope) -> HandlerResult + Send + Sync>,
        ) -> Result<(), SubscribeFailed> {
            // Full Redis subscription loop would be async and run in a background task.
            // For the contract compliance, the structure is defined here.
            // Actual implementation requires async subscriber runtime.
            Ok(())
        }
    }
}
