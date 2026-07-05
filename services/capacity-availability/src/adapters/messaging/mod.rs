// ---------------------------------------------------------------------------
// Shared Rust platform-kit messaging adapters.
// ---------------------------------------------------------------------------

pub use rust_kit::messaging::{
    InMemoryEventPublisher, InMemoryEventSubscriber, ReceivedEvent, SubscriberAction,
    SubscriberState,
};

#[cfg(feature = "redis-impl")]
pub mod redis_publisher {
    pub use rust_kit::messaging::redis_runtime::{QueuedRedisEventPublisher, publish_with_retry};

    #[derive(Clone)]
    pub struct RedisEventPublisher {
        inner: QueuedRedisEventPublisher,
    }

    impl RedisEventPublisher {
        pub async fn new(redis_url: &str) -> Result<Self, rust_kit::messaging::PublishFailed> {
            let publisher =
                rust_kit::messaging::redis_runtime::RedisEventPublisher::new(redis_url)?;
            Ok(Self {
                inner: publisher.queued(),
            })
        }
    }

    impl rust_kit::messaging::EventPublisher for RedisEventPublisher {
        fn publish(
            &self,
            envelope: &rust_kit::messaging::EventEnvelope,
        ) -> Result<(), rust_kit::messaging::PublishFailed> {
            rust_kit::messaging::EventPublisher::publish(&self.inner, envelope)
        }
    }
}

#[cfg(feature = "redis-impl")]
pub mod redis_subscriber {

    #[derive(Debug, Clone)]
    pub struct StreamAutoClaimReply {
        pub next_cursor: String,
        pub key: String,
        pub ids: Vec<redis::streams::StreamId>,
        pub deleted_ids: Vec<String>,
    }

    pub fn parse_xautoclaim_reply(
        stream: &str,
        value: &redis::Value,
    ) -> Result<StreamAutoClaimReply, rust_kit::messaging::SubscribeFailed> {
        let redis::Value::Bulk(parts) = value else {
            return Err(rust_kit::messaging::SubscribeFailed(
                "XAUTOCLAIM parse failed: expected top-level bulk reply".into(),
            ));
        };
        let [cursor_value, entries_value, deleted_value] = parts.as_slice() else {
            return Err(rust_kit::messaging::SubscribeFailed(format!(
                "XAUTOCLAIM parse failed: expected 3 reply elements, got {}",
                parts.len()
            )));
        };
        let next_cursor: String = redis::from_redis_value(cursor_value).map_err(|error| {
            rust_kit::messaging::SubscribeFailed(format!("XAUTOCLAIM cursor parse failed: {error}"))
        })?;
        let deleted_ids: Vec<String> = redis::from_redis_value(deleted_value).map_err(|error| {
            rust_kit::messaging::SubscribeFailed(format!(
                "XAUTOCLAIM deleted IDs parse failed: {error}"
            ))
        })?;
        let redis::Value::Bulk(entries) = entries_value else {
            return Err(rust_kit::messaging::SubscribeFailed(
                "XAUTOCLAIM parse failed: expected entries bulk reply".into(),
            ));
        };
        let mut ids = Vec::with_capacity(entries.len());
        for entry in entries {
            let redis::Value::Bulk(entry_parts) = entry else {
                return Err(rust_kit::messaging::SubscribeFailed(
                    "XAUTOCLAIM parse failed: expected entry bulk reply".into(),
                ));
            };
            let [id_value, fields_value] = entry_parts.as_slice() else {
                return Err(rust_kit::messaging::SubscribeFailed(format!(
                    "XAUTOCLAIM parse failed: expected 2 entry elements, got {}",
                    entry_parts.len()
                )));
            };
            let id: String = redis::from_redis_value(id_value).map_err(|error| {
                rust_kit::messaging::SubscribeFailed(format!(
                    "XAUTOCLAIM entry ID parse failed: {error}"
                ))
            })?;
            let map = redis::from_redis_value(fields_value).map_err(|error| {
                rust_kit::messaging::SubscribeFailed(format!(
                    "XAUTOCLAIM fields parse failed: {error}"
                ))
            })?;
            ids.push(redis::streams::StreamId { id, map });
        }
        Ok(StreamAutoClaimReply {
            next_cursor,
            key: stream.to_string(),
            ids,
            deleted_ids,
        })
    }
    #[derive(Clone)]
    pub struct RedisEventSubscriber {
        inner: rust_kit::messaging::redis_runtime::RedisEventSubscriber,
    }

    impl RedisEventSubscriber {
        pub async fn new(redis_url: &str) -> Result<Self, rust_kit::messaging::SubscribeFailed> {
            Ok(Self {
                inner: rust_kit::messaging::redis_runtime::RedisEventSubscriber::new(redis_url)?,
            })
        }

        pub fn shutdown(&self) {
            self.inner.shutdown();
        }
    }

    impl rust_kit::messaging::EventSubscriber for RedisEventSubscriber {
        fn subscribe(
            &self,
            streams: &[String],
            group: &str,
            consumer_name: &str,
            handler: Box<
                dyn Fn(rust_kit::messaging::EventEnvelope) -> rust_kit::messaging::HandlerResult
                    + Send
                    + Sync,
            >,
        ) -> Result<(), rust_kit::messaging::SubscribeFailed> {
            let selected_streams = if streams.is_empty() {
                vec![
                    "events:booking-orchestration".to_string(),
                    "events:post-sales".to_string(),
                ]
            } else {
                streams.to_vec()
            };
            let selected_group = if group.is_empty() {
                "capacity-availability".to_string()
            } else {
                group.to_string()
            };
            let selected_consumer = consumer_name.to_string();
            let inner = self.inner.clone();
            tokio::spawn(async move {
                let _ = rust_kit::messaging::AsyncEventSubscriber::subscribe(
                    &inner,
                    selected_streams,
                    selected_group,
                    selected_consumer,
                    Box::new(move |envelope| {
                        let result = handler(envelope);
                        Box::pin(async move {
                            match result {
                                rust_kit::messaging::HandlerResult::Success => Ok(()),
                                rust_kit::messaging::HandlerResult::TransientError(message) => {
                                    Err(rust_kit::messaging::HandlerError::Transient(message))
                                }
                                rust_kit::messaging::HandlerResult::FatalError(message) => {
                                    Err(rust_kit::messaging::HandlerError::Fatal(message))
                                }
                            }
                        })
                    }),
                )
                .await;
            });
            Ok(())
        }
    }
}
