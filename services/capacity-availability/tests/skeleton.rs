use capacity_availability::{health, profile, router};

#[test]
fn profile_exports_contract_metadata() {
    let profile = profile();
    assert_eq!(profile.service_id, "capacity-availability");
    assert_eq!(
        profile.requirement,
        "REQ-006 Capacity & Availability domain foundation"
    );
    assert!(profile.owns.contains(&"InventoryPool"));
    assert!(profile.owns.contains(&"CapacityHold"));
    assert_eq!(health(), "ok");
    let _router = router();
}
