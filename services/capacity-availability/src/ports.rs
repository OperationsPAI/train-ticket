// ---------------------------------------------------------------------------
// Abstract ports per docs/08-contracts/messaging.md §10
// Broker-neutral — no Redis types, no stream-key strings.
// ---------------------------------------------------------------------------

pub use rust_kit::messaging::{
    EventEnvelope as WireEnvelope, EventPublisher, EventSubscriber, HandlerResult, PublishFailed,
    SubscribeFailed,
};
