use shared_kernel::{
    ContractError, CorrelationId, CurrencyCode, EventEnvelope, EventId, Money, PlaceRef,
    SegmentRef, TimeWindow, TravelerRef, UnixMillis, health, profile, router,
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
    let place = PlaceRef::new("plc-1").unwrap();
    let traveler = TravelerRef::new("tvl-1").unwrap();
    let segment = SegmentRef::new("seg-1").unwrap();
    let price = Money::new_minor(12800, CurrencyCode::new("CNY").unwrap());
    let window = TimeWindow::new(UnixMillis::new(1), UnixMillis::new(2)).unwrap();
    let envelope = EventEnvelope::new(
        EventId::new("evt-1").unwrap(),
        "SharedKernelContractAccepted",
        1,
        UnixMillis::new(1),
        CorrelationId::new("corr-1").unwrap(),
        None,
    )
    .unwrap();

    assert_eq!(place.as_str(), "plc-1");
    assert_eq!(traveler.as_str(), "tvl-1");
    assert_eq!(segment.as_str(), "seg-1");
    assert_eq!(price.currency().as_str(), "CNY");
    assert_eq!(window.end().as_u64(), 2);
    assert_eq!(envelope.event_type(), "SharedKernelContractAccepted");
}

#[test]
fn every_reference_construction_path_checks_the_prefix() {
    assert_eq!(
        "segment-1".parse::<SegmentRef>().unwrap_err(),
        ContractError::InvalidIdPrefix {
            kind: "SegmentRef",
            expected: "seg-"
        }
    );
    assert_eq!(
        TravelerRef::try_from("traveler-1").unwrap_err(),
        ContractError::InvalidIdPrefix {
            kind: "TravelerRef",
            expected: "tvl-"
        }
    );
    assert_eq!(
        EventId::try_from("cmd-1").unwrap_err(),
        ContractError::InvalidIdPrefix {
            kind: "EventId",
            expected: "evt-"
        }
    );
}
