use capacity_availability::supply_facts::{
    Interval, PendingOccupancy, ReservationSupply, SeatSupply, SupplyClient,
};

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
