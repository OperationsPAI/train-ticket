use shared_kernel::{health, profile, router};

#[test]
fn profile_exports_contract_metadata() {
    let profile = profile();
    assert_eq!(profile.service_id, "shared-kernel");
    assert_eq!(health(), "ok");
    let _router = router();
}
