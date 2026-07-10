#[test]
fn profile_names_invoicing() {
    let profile = invoicing::profile();
    assert_eq!(profile.service_id, "invoicing");
    assert!(profile.owns.contains(&"EInvoice"));
}
