pub const CAPACITY_AVAILABILITY_PRODUCER: &str = "capacity-availability";
pub const JOURNEY_ORDER_PRODUCER: &str = "journey-order";

pub fn subscribed_streams() -> Vec<String> {
    vec![
        rust_kit::messaging::stream_for_producer(CAPACITY_AVAILABILITY_PRODUCER),
        rust_kit::messaging::stream_for_producer(JOURNEY_ORDER_PRODUCER),
    ]
}

pub fn capacity_availability_stream() -> String {
    rust_kit::messaging::stream_for_producer(CAPACITY_AVAILABILITY_PRODUCER)
}

pub fn journey_order_stream() -> String {
    rust_kit::messaging::stream_for_producer(JOURNEY_ORDER_PRODUCER)
}
