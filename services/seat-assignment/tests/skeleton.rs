#[test]
fn service_metadata_is_available() {
    let profile = seat_assignment::profile();
    assert_eq!(profile.service_id, "seat-assignment");
}
