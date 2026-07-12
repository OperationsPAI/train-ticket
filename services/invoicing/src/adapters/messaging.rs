pub fn subscribed_streams() -> Vec<String> {
    vec![
        journey_order_stream(),
        finance_stream(),
        post_sales_stream(),
        admin_audit_stream(),
        booking_orchestration_stream(),
    ]
}
pub fn journey_order_stream() -> String {
    rust_kit::messaging::stream_for_producer("journey-order")
}
pub fn finance_stream() -> String {
    rust_kit::messaging::stream_for_producer("finance-settlement")
}
pub fn post_sales_stream() -> String {
    rust_kit::messaging::stream_for_producer("post-sales")
}
pub fn admin_audit_stream() -> String {
    rust_kit::messaging::stream_for_producer("admin-audit")
}
pub fn booking_orchestration_stream() -> String {
    rust_kit::messaging::stream_for_producer("booking-orchestration")
}
