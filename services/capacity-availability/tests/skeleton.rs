use capacity_availability::{health, profile, router};

#[test]
fn profile_exports_contract_metadata() {
    let profile = profile();
    assert_eq!(profile.service_id, "capacity-availability");
    assert_eq!(health(), "ok");
    let _router = router();
}
