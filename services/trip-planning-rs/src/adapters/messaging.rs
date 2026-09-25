pub const ITINERARY_STREAM: &str = "events:trip-planning";

#[cfg(feature = "redis-impl")]
pub const CONSUMER_GROUP: &str = "trip-planning";

#[cfg(feature = "redis-impl")]
pub const SUBSCRIPTION_STREAMS: &[&str] = &[
    "events:place-network",
    "events:service-plan",
    "events:transfer-management",
];
