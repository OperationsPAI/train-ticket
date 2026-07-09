use super::*;
use std::sync::Arc;

fn uuid7() -> String {
    uuid::Uuid::now_v7().to_string()
}
fn prefs(standing: bool, group: Option<&str>) -> SeatPreferences {
    SeatPreferences {
        accept_standing: standing,
        adjacency_preference: group.map(|_| AdjacencyPreference::Adjacent),
        adjacency_group_ref: group.map(str::to_string),
        preferred_seat_positions: None,
        preferred_berth_positions: None,
        same_compartment: None,
        avoid_seat_unit_refs: None,
        preference_version: "pv1".into(),
    }
}
fn map_cmd(svc: &str, scenario: Option<&str>) -> CreateSeatMapCommand {
    CreateSeatMapCommand {
        scheduled_service_ref: svc.into(),
        service_date: "2026-08-02".into(),
        composition_version: "v1".into(),
        composition_seed: scenario.unwrap_or("BASE").into(),
        mapping_version: "sim-v1".into(),
        change_scenario: scenario.map(str::to_string),
        operator_ref: "op-seat".into(),
    }
}
fn alloc_cmd(
    svc: &str,
    sb: &str,
    traveler: &str,
    pref: Option<SeatPreferences>,
) -> AllocateSeatCommand {
    AllocateSeatCommand {
        segment_booking_id: sb.into(),
        journey_order_id: format!("ord-{}", uuid7()),
        traveler_ref: traveler.into(),
        segment_ref: format!("seg-{}", uuid7()),
        scheduled_service_ref: svc.into(),
        service_date: "2026-08-02".into(),
        capacity_hold_id: format!("hold-{}", uuid7()),
        capacity_unit_ref: "cap-standard".into(),
        interval: StationInterval {
            from_seq: 1,
            to_seq: 3,
        },
        class_ref: "standard".into(),
        issue_purpose: "INITIAL".into(),
        seat_preferences: pref,
        expires_at: "2026-08-02T08:00:00Z".into(),
    }
}

#[tokio::test]
async fn state_machine_immutable_published_and_standing_success() {
    let publisher = Arc::new(InMemoryEventPublisher::default());
    let svc = InMemorySeatAssignmentService::new(publisher);
    let ss = format!("ss-{}", uuid7());
    let map = svc
        .create_seat_map(
            map_cmd(&ss, Some("SMALL")),
            uuid7(),
            rust_kit::messaging::correlation_id(),
        )
        .await
        .unwrap();
    let map = svc
        .publish_seat_map(
            map.seat_map_id.clone(),
            PublishSeatMapCommand {
                expected_seat_map_version: 1,
                publish_reason: "ready".into(),
                operator_ref: "op".into(),
            },
            uuid7(),
            rust_kit::messaging::correlation_id(),
        )
        .await
        .unwrap();
    let su = map.coaches[0].seat_units[0].seat_unit_ref.clone();
    let blocked = svc
        .mark_unavailable(
            map.seat_map_id.clone(),
            su,
            MarkUnavailableCommand {
                expected_seat_map_version: 2,
                unavailable_reason: "MAINTENANCE".into(),
                operator_ref: "op".into(),
            },
            uuid7(),
            rust_kit::messaging::correlation_id(),
        )
        .await;
    assert!(matches!(
        blocked,
        Err(SeatAssignmentError::DomainRuleViolation(_))
    ));
    for i in 0..2 {
        let sb = format!("sb-{}", uuid7());
        let tvl = format!("tvl-{}", uuid7());
        let r = svc
            .allocate(
                alloc_cmd(&ss, &sb, &tvl, None),
                uuid7(),
                rust_kit::messaging::correlation_id(),
            )
            .await
            .unwrap();
        assert_eq!(r.status, AllocationStatus::Allocated, "allocated {i}");
    }
    let sb = format!("sb-{}", uuid7());
    let tvl = format!("tvl-{}", uuid7());
    let r = svc
        .allocate(
            alloc_cmd(&ss, &sb, &tvl, Some(prefs(true, None))),
            uuid7(),
            rust_kit::messaging::correlation_id(),
        )
        .await
        .unwrap();
    assert_eq!(r.status, AllocationStatus::Standing);
    assert_eq!(r.seat_ref.allocation_type, AllocationType::Standing);
    assert_eq!(r.seat_ref.display_label, "STANDING");
}

#[tokio::test]
async fn idempotency_replay_and_reuse_are_enforced() {
    let svc = InMemorySeatAssignmentService::new(Arc::new(InMemoryEventPublisher::default()));
    let ss = format!("ss-{}", uuid7());
    let key = uuid7();
    let first = svc
        .create_seat_map(
            map_cmd(&ss, None),
            key.clone(),
            rust_kit::messaging::correlation_id(),
        )
        .await
        .unwrap();
    let replay = svc
        .create_seat_map(
            map_cmd(&ss, None),
            key.clone(),
            rust_kit::messaging::correlation_id(),
        )
        .await
        .unwrap();
    assert_eq!(first.seat_map_id, replay.seat_map_id);
    let reused = svc
        .create_seat_map(
            map_cmd(&format!("ss-{}", uuid7()), None),
            key,
            rust_kit::messaging::correlation_id(),
        )
        .await;
    assert!(matches!(
        reused,
        Err(SeatAssignmentError::IdempotencyKeyReused(_))
    ));
}

#[tokio::test]
async fn deterministic_event_ids_are_uuid_v7_shaped_and_payload_fields_match_contract() {
    let publisher = Arc::new(InMemoryEventPublisher::default());
    let svc = InMemorySeatAssignmentService::new(publisher.clone());
    let ss = format!("ss-{}", uuid7());
    let map = svc
        .create_seat_map(
            map_cmd(&ss, None),
            uuid7(),
            rust_kit::messaging::correlation_id(),
        )
        .await
        .unwrap();
    svc.publish_seat_map(
        map.seat_map_id,
        PublishSeatMapCommand {
            expected_seat_map_version: 1,
            publish_reason: "ready".into(),
            operator_ref: "op".into(),
        },
        uuid7(),
        rust_kit::messaging::correlation_id(),
    )
    .await
    .unwrap();
    let events = publisher.published();
    assert!(events.iter().all(|e| e.event_id.starts_with("evt-")
        && rust_kit::messaging::is_uuid_v7(e.event_id.trim_start_matches("evt-"))));
    let built = events
        .iter()
        .find(|e| e.event_type == "SeatMapBuilt")
        .unwrap();
    assert!(built.payload.get("seatMapId").is_some());
    assert_eq!(built.payload["status"], "DRAFT");
    assert!(
        built
            .payload
            .get("builtAt")
            .unwrap()
            .as_str()
            .unwrap()
            .ends_with('Z')
    );
}

#[tokio::test]
async fn consumed_entitlement_and_capacity_events_confirm_and_recycle() {
    let svc = InMemorySeatAssignmentService::new(Arc::new(InMemoryEventPublisher::default()));
    let ss = format!("ss-{}", uuid7());
    let map = svc
        .create_seat_map(
            map_cmd(&ss, None),
            uuid7(),
            rust_kit::messaging::correlation_id(),
        )
        .await
        .unwrap();
    svc.publish_seat_map(
        map.seat_map_id,
        PublishSeatMapCommand {
            expected_seat_map_version: 1,
            publish_reason: "ready".into(),
            operator_ref: "op".into(),
        },
        uuid7(),
        rust_kit::messaging::correlation_id(),
    )
    .await
    .unwrap();
    let sb = format!("sb-{}", uuid7());
    let tvl = format!("tvl-{}", uuid7());
    let r = svc
        .allocate(
            alloc_cmd(&ss, &sb, &tvl, None),
            uuid7(),
            rust_kit::messaging::correlation_id(),
        )
        .await
        .unwrap();
    let issued = rust_kit::messaging::EventEnvelope::canonical(
        "EntitlementIssued",
        rust_kit::messaging::correlation_id(),
        None::<String>,
        "entitlement-ticketing",
        serde_json::json!({"entitlementId":format!("ent-{}",uuid7()),"segmentBookingId":sb,"seatAllocationId":r.seat_allocation_id,"seatRef":r.seat_ref}),
    );
    svc.apply_subscribed_event(issued.clone()).await.unwrap();
    assert_eq!(
        svc.get_allocation(r.seat_allocation_id.clone())
            .await
            .unwrap()
            .status,
        AllocationStatus::Confirmed
    );
    let released = rust_kit::messaging::EventEnvelope::canonical(
        "CapacityReleased",
        issued.correlation_id,
        None::<String>,
        "capacity-availability",
        serde_json::json!({"holdId":svc.get_allocation(r.seat_allocation_id.clone()).await.unwrap().capacity_hold_id,"capacityUnitRef":"cap-standard","interval":{"fromSeq":1,"toSeq":3},"releasedAt":"2026-08-02T00:00:00Z","releaseReason":"REFUND"}),
    );
    svc.apply_subscribed_event(released).await.unwrap();
    assert_eq!(
        svc.get_allocation(r.seat_allocation_id)
            .await
            .unwrap()
            .status,
        AllocationStatus::Released
    );
}

#[tokio::test]
async fn consumed_event_dedup_marks_only_after_successful_state_change() {
    let svc = InMemorySeatAssignmentService::new(Arc::new(InMemoryEventPublisher::default()));
    let ss = format!("ss-{}", uuid7());
    let map = svc
        .create_seat_map(
            map_cmd(&ss, None),
            uuid7(),
            rust_kit::messaging::correlation_id(),
        )
        .await
        .unwrap();
    svc.publish_seat_map(
        map.seat_map_id,
        PublishSeatMapCommand {
            expected_seat_map_version: 1,
            publish_reason: "ready".into(),
            operator_ref: "op".into(),
        },
        uuid7(),
        rust_kit::messaging::correlation_id(),
    )
    .await
    .unwrap();
    let sb = format!("sb-{}", uuid7());
    let tvl = format!("tvl-{}", uuid7());
    let allocation = svc
        .allocate(
            alloc_cmd(&ss, &sb, &tvl, None),
            uuid7(),
            rust_kit::messaging::correlation_id(),
        )
        .await
        .unwrap();
    let event = rust_kit::messaging::EventEnvelope::canonical(
        "EntitlementIssued",
        rust_kit::messaging::correlation_id(),
        None::<String>,
        "entitlement-ticketing",
        serde_json::json!({"entitlementId":format!("ent-{}",uuid7()),"segmentBookingId":sb,"seatAllocationId":allocation.seat_allocation_id,"seatRef":allocation.seat_ref}),
    );
    svc.apply_subscribed_event(event.clone()).await.unwrap();
    assert_eq!(
        svc.get_allocation(allocation.seat_allocation_id.clone())
            .await
            .unwrap()
            .status,
        AllocationStatus::Confirmed
    );
    svc.apply_subscribed_event(event).await.unwrap();
    assert_eq!(
        svc.get_allocation(allocation.seat_allocation_id)
            .await
            .unwrap()
            .version,
        2
    );
}
