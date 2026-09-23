use super::*;

#[tokio::test]
#[ignore = "requires a recorded full class replacement and live Seat Assignment"]
async fn full_class_retains_business_rejection_without_ticket_issuance() {
    let source: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(std::env::var("TEST_FULL_CLASS_CHANGE").unwrap()).unwrap(),
    ).unwrap();
    let operations = source["changeOperations"].as_object().unwrap();
    let target = &operations["target"];
    let confirmed: Vec<_> = operations.iter().filter(|(name, _)| name.starts_with("confirmed:")).collect();
    assert_eq!(confirmed.len(), 1);
    assert!(!operations.keys().any(|name| name.starts_with("ticket:")));
    let booking = &confirmed[0].1["booking"];
    let hold = &confirmed[0].1["hold"];
    let leg = target["itinerary"]["legs"].as_array().unwrap().iter()
        .find(|leg| leg["serviceSegmentRef"] == booking["segmentRef"]).unwrap();
    let class = &target["fare"]["breakdown"]["seatClassMultiplier"]["seatClass"];
    let date = chrono::DateTime::parse_from_rfc3339(leg["departureTime"].as_str().unwrap()).unwrap()
        .with_timezone(&chrono::Utc).date_naive().to_string();
    let base = std::env::var("SEAT_ASSIGNMENT_BASE_URL").unwrap();
    let facts_url = format!("{base}/api/v1/internal/capacity-facts");
    let client = reqwest::Client::new();
    let query = [("scheduledServiceRef",leg["servicePlanRef"].as_str().unwrap()),("serviceDate",date.as_str()),("classRef",class.as_str().unwrap())];
    let before: serde_json::Value = client.get(&facts_url).query(&query).send().await.unwrap().error_for_status().unwrap().json().await.unwrap();
    assert_eq!(before["units"].as_array().unwrap().len(),36);
    assert_eq!(before["allocations"].as_array().unwrap().len(),36);
    let command: IssueEntitlementRequest = serde_json::from_value(json!({
        "segmentBookingId":booking["segmentBookingId"], "journeyOrderId":source["journeyOrderId"],
        "travelerRef":booking["travelerRef"], "segmentRef":booking["segmentRef"], "issuePurpose":"REPLACEMENT",
        "scheduledServiceRef":leg["servicePlanRef"], "serviceDate":date, "capacityHoldId":hold["holdId"],
        "capacityUnitRef":hold["capacityUnitRef"], "interval":hold["interval"], "classRef":class,
        "expiresAt":leg["departureTime"]
    })).unwrap();
    let key = seat_assignment_idempotency_key(&command).unwrap();
    for _ in 0..2 {
        let result = allocate_seat_for_issue(&command,&key,&format!("corr-{}",uuid::Uuid::now_v7())).await;
        assert!(matches!(result,Err(ApiErrorKind::DomainRuleViolation(ref message)) if message=="no compatible seat exists and standing is not accepted"),"{result:?}");
    }
    let after: serde_json::Value = client.get(&facts_url).query(&query).send().await.unwrap().error_for_status().unwrap().json().await.unwrap();
    assert_eq!(before["allocations"],after["allocations"]);
    assert_eq!(before["units"],after["units"]);
}
