use entitlement_ticketing::{health, profile, router};

#[test]
fn profile_exports_req_013_contract_metadata() {
    let profile = profile();
    assert_eq!(profile.service_id, "entitlement-ticketing");
    assert_eq!(profile.domain, "Entitlement & Ticketing");
    assert_eq!(profile.phase, "phase-1-domain-foundation");
    assert_eq!(profile.work_packages, &["REQ-013"]);
    assert!(
        profile
            .owns
            .iter()
            .any(|entry| entry.contains("issuance preconditions"))
    );
    assert_eq!(health(), "ok");
    let _router = router();
}
