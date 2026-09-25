use capacity_availability::supply_facts::{
    Allocation, Interval, PendingOccupancy, ReservationSupply, SeatSupply, SupplyClient,
};

fn recorded_supply() -> SeatSupply {
    serde_json::from_str(
        &std::fs::read_to_string(std::env::var("TEST_SEAT_SUPPLY").unwrap()).unwrap(),
    )
    .unwrap()
}

fn pending_from(allocation: &Allocation) -> PendingOccupancy {
    PendingOccupancy {
        hold_id: allocation.capacity_hold_id.clone(),
        journey_order_id: allocation.journey_order_id.clone(),
        segment_booking_id: allocation.segment_booking_id.clone(),
        traveler_ref: allocation.traveler_ref.clone(),
        capacity_unit_ref: allocation.capacity_unit_ref.clone(),
        interval: allocation.interval.clone(),
    }
}

#[test]
#[ignore = "requires TEST_SEAT_SUPPLY from deployed capacity facts"]
fn recorded_full_business_class_has_no_remaining_capacity_and_deduplicates_holds() {
    let source = std::fs::read_to_string(std::env::var("TEST_SEAT_SUPPLY").unwrap()).unwrap();
    let supply: SeatSupply = serde_json::from_str(&source).unwrap();
    assert_eq!(supply.units.len(), 36);
    assert_eq!(supply.allocations.len(), 36);
    let first = supply.allocations.first().unwrap();
    let request = ReservationSupply {
        segment_booking_id: first.segment_booking_id.clone(),
        journey_order_id: first.journey_order_id.clone(),
        traveler_ref: first.traveler_ref.clone(),
        segment_ref: std::env::var("TEST_SUPPLY_SEGMENT").unwrap(),
        scheduled_service_ref: supply.scheduled_service_ref.clone(),
        service_date: supply.service_date.clone(),
        class_ref: supply.class_ref.clone(),
        booking_purpose: "purchase".into(),
        offer_item_ref: "ofi-recorded".into(),
        standing_accepted: false,
        source_type: "PURCHASE_ORDER".into(),
        source_ref: first.journey_order_id.clone(),
    };
    supply.validate(&request).unwrap();
    let interval = Interval {
        from_seq: 0,
        to_seq: 1,
    };
    assert_eq!(supply.remaining(&interval, &[]).unwrap(), 0);
    let holds: Vec<_> = supply
        .allocations
        .iter()
        .map(|a| PendingOccupancy {
            hold_id: a.capacity_hold_id.clone(),
            journey_order_id: a.journey_order_id.clone(),
            segment_booking_id: a.segment_booking_id.clone(),
            traveler_ref: a.traveler_ref.clone(),
            capacity_unit_ref: a.capacity_unit_ref.clone(),
            interval: a.interval.clone(),
        })
        .collect();
    assert_eq!(supply.remaining(&interval, &holds).unwrap(), 0);
    let mut repeated = holds.clone();
    repeated.push(holds[0].clone());
    assert!(
        supply.remaining(&interval, &repeated).is_err(),
        "an allocated Hold must not bypass duplicate occupancy validation"
    );
    let mut changed = holds.clone();
    changed[0].interval.to_seq += 1;
    assert!(
        supply.remaining(&interval, &changed).is_err(),
        "allocation interval must match its Hold"
    );
    let mut more_units = supply.clone();
    more_units.allocations.clear();
    assert_eq!(more_units.remaining(&interval, &holds).unwrap(), 0);
    let half = &holds[..18];
    assert_eq!(more_units.remaining(&interval, half).unwrap(), 18);
    let mut duplicate = holds.clone();
    duplicate.push(holds[0].clone());
    assert!(more_units.remaining(&interval, &duplicate).is_err());
    let mut wrong = request.clone();
    wrong.class_ref = "FIRST_CLASS".into();
    assert!(supply.validate(&wrong).is_err());
    let mut duplicate_seat = supply.clone();
    duplicate_seat.units.push(supply.units[0].clone());
    assert!(duplicate_seat.validate(&request).is_err());
}

#[tokio::test]
#[ignore = "requires TEST_SUPPLY_BOOKING and TEST_SUPPLY_BASE_URL_TEMPLATE"]
async fn deployed_reservation_resolves_actual_offer_date_and_physical_supply() {
    let client =
        SupplyClient::new(std::env::var("TEST_SUPPLY_BASE_URL_TEMPLATE").unwrap()).unwrap();
    let facts = client
        .reservation(
            &std::env::var("TEST_SUPPLY_BOOKING").unwrap(),
            "corr-01a0cdac-da33-7fd4-a442-bdafdf12c14d",
        )
        .await
        .unwrap();
    assert_eq!(facts.class_ref, "BUSINESS_CLASS");
    assert_eq!(facts.service_date, "2026-09-25");
    assert_eq!(
        facts.scheduled_service_ref,
        "ss-01a0c74d-aad0-7000-a88a-ddd76e217abf"
    );
    let seats = client
        .seats(&facts, "corr-01a0cdac-da33-7fd4-a442-bdafdf12c14d")
        .await
        .unwrap();
    assert_eq!(seats.units.len(), 36);
    assert_eq!(
        seats
            .remaining(
                &Interval {
                    from_seq: 0,
                    to_seq: 1
                },
                &[]
            )
            .unwrap(),
        0
        );
}

/**
 * Recorded allocations all cover the first interval, so a later interval must not inherit their
 * occupancy. Overlap validation has to be interval bound, not merely per seat.
 */
#[test]
#[ignore = "requires TEST_SEAT_SUPPLY from deployed capacity facts"]
fn recorded_allocations_do_not_occupy_a_later_interval() {
    let supply = recorded_supply();
    let holds: Vec<_> = supply.allocations.iter().map(pending_from).collect();
    let later = Interval {
        from_seq: 1,
        to_seq: 2,
    };
    assert_eq!(
        supply.remaining(&later, &holds).unwrap(),
        supply.units.len(),
        "an interval after every recorded allocation keeps the full published supply"
    );
    let mut spanning = vec![holds[0].clone()];
    spanning[0].interval = Interval {
        from_seq: 0,
        to_seq: 2,
    };
    assert_eq!(
        supply.remaining(&later, &spanning).unwrap(),
        supply.units.len() - 1,
        "a Hold that reaches into the later interval still consumes one unit there"
    );
    let original = Interval {
        from_seq: 0,
        to_seq: 1,
    };
    let mut changed = vec![holds[0].clone()];
    changed[0].segment_booking_id = "sb-00000000-0000-7000-8000-000000000007".into();
    assert!(
        supply.remaining(&original, &changed).is_err(),
        "a Hold that repeats an allocated hold identifier with another booking is rejected"
    );
}

/**
 * A composition change publishes new units. Admission must follow the published units while still
 * counting the allocations and Holds that were recorded against the previous composition.
 */
#[test]
#[ignore = "requires TEST_SEAT_SUPPLY from deployed capacity facts"]
fn composition_change_counts_recorded_allocations_and_pending_holds() {
    let supply = recorded_supply();
    let interval = Interval {
        from_seq: 0,
        to_seq: 1,
    };
    assert_eq!(supply.remaining(&interval, &[]).unwrap(), 0);

    let mut grown = supply.clone();
    let mut added = supply.units[0].clone();
    added.seat_unit_ref = "su-00000000-0000-7000-8000-000000000000".into();
    grown.units.push(added);
    grown.seat_map_version += 1;
    grown.composition_version = format!("{}-grown", supply.composition_version);
    assert_eq!(
        grown.remaining(&interval, &[]).unwrap(),
        1,
        "one new unit in the published composition admits exactly one traveler"
    );

    let pending = PendingOccupancy {
        hold_id: "hold-00000000-0000-7000-8000-000000000001".into(),
        journey_order_id: "ord-00000000-0000-7000-8000-000000000002".into(),
        segment_booking_id: "sb-00000000-0000-7000-8000-000000000003".into(),
        traveler_ref: "tvl-00000000-0000-7000-8000-000000000004".into(),
        capacity_unit_ref: grown.units.last().unwrap().seat_unit_ref.clone(),
        interval: interval.clone(),
    };
    assert_eq!(
        grown.remaining(&interval, &[pending]).unwrap(),
        0,
        "a Hold without an allocation consumes the free unit"
    );

    let mut shrunk = supply.clone();
    shrunk.units.pop();
    shrunk.seat_map_version += 1;
    assert_eq!(
        shrunk.remaining(&interval, &[]).unwrap(),
        0,
        "a smaller composition cannot report negative remaining capacity"
    );
}

/**
 * Two overlapping allocations on one seat contradict each other. Admission must reject the source
 * facts instead of quietly counting the seat once.
 */
#[test]
#[ignore = "requires TEST_SEAT_SUPPLY from deployed capacity facts"]
fn overlapping_allocations_on_one_seat_are_rejected() {
    let supply = recorded_supply();
    let interval = Interval {
        from_seq: 0,
        to_seq: 1,
    };
    let mut conflicting = supply.clone();
    let mut duplicate = conflicting.allocations[0].clone();
    duplicate.capacity_hold_id = "hold-00000000-0000-7000-8000-000000000005".into();
    duplicate.segment_booking_id = "sb-00000000-0000-7000-8000-000000000006".into();
    conflicting.allocations.push(duplicate);
    assert!(
        conflicting.remaining(&interval, &[]).is_err(),
        "one seat must not carry two overlapping allocations"
    );
}
