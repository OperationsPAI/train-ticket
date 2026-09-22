use entitlement_ticketing::{EntitlementApi, IssueEntitlementRequest, adapters::storage::PostgresEntitlementService};
use rust_kit::storage::Storage;

#[tokio::test]
async fn live_issue_requires_confirmed_reservation_and_capacity() {
    let Ok(database_url) = std::env::var("TEST_DATABASE_URL") else { return; };
    let Ok(request_json) = std::env::var("TEST_LIVE_ISSUE_REQUEST") else { return; };
    let command: IssueEntitlementRequest = serde_json::from_str(&request_json).unwrap();
    let storage = Storage::connect(&database_url).await.unwrap();
    storage.migrate_dir("migrations").await.unwrap();
    let service = PostgresEntitlementService::from_storage(storage).unwrap();
    let key = uuid::Uuid::now_v7().to_string();
    let result = service.issue(command, key.clone(), format!("corr-{}", uuid::Uuid::now_v7())).await;
    if std::env::var("TEST_LIVE_ISSUE_EXPECT").as_deref() == Ok("rejected") {
        assert!(matches!(result, Err(entitlement_ticketing::ApiErrorKind::PreconditionFailed(_))), "{result:?}");
    } else {
        let issued = result.unwrap();
        assert_eq!(issued.status, entitlement_ticketing::EntitlementStatusDto::Issued);
        println!("issued entitlement={} booking={}", issued.entitlement_id, issued.segment_booking_id);
    }
}
