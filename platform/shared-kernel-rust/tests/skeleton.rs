use shared_kernel::{
    CorrelationId, CurrencyCode, EventEnvelope, EventId, Money, SegmentRef, TimeWindow,
    TravelerRef, UnixMillis, health, profile, router,
};

#[test]
fn profile_exports_contract_metadata() {
    let profile = profile();
    assert_eq!(profile.service_id, "shared-kernel");
    assert_eq!(health(), "ok");
    let _router = router();
}

#[test]
fn shared_contract_primitives_are_constructible() {
    let traveler = TravelerRef::new("traveler-1").unwrap();
    let segment = SegmentRef::new("segment-1").unwrap();
    let price = Money::new_minor(12800, CurrencyCode::new("CNY").unwrap());
    let window = TimeWindow::new(UnixMillis::new(1), UnixMillis::new(2)).unwrap();
    let envelope = EventEnvelope::new(
        EventId::new("event-1").unwrap(),
        "SharedKernelContractAccepted",
        1,
        UnixMillis::new(1),
        CorrelationId::new("corr-1").unwrap(),
        None,
    )
    .unwrap();

    assert_eq!(traveler.as_str(), "traveler-1");
    assert_eq!(segment.as_str(), "segment-1");
    assert_eq!(price.currency().as_str(), "CNY");
    assert_eq!(window.end().as_u64(), 2);
    assert_eq!(envelope.event_type(), "SharedKernelContractAccepted");
}
