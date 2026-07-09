pub fn subscribed_streams() -> Vec<String> {
    vec![entitlement_stream(), capacity_stream()]
}
pub fn entitlement_stream() -> String {
    rust_kit::messaging::stream_for_producer("entitlement-ticketing")
}
pub fn capacity_stream() -> String {
    rust_kit::messaging::stream_for_producer("capacity-availability")
}
