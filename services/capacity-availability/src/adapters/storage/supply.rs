use super::*;
use futures_util::{StreamExt, TryStreamExt, stream};

/// The supply facts of a hold that is already occupying part of a pool.
///
/// `reservation_supply_facts` is a cache of what booking-orchestration answers,
/// and the rows written before the purchase source was part of that answer are
/// still the majority: measured on the resident database, 2576539 of 2590284
/// rows carry no `bookingPurpose` and no `sourceType`. Treating such a row as
/// an error made every new reservation into a pool that still holds one of them
/// fail with "reservation has an unsupported purchase source", which is what
/// the load generator reported as a reservation that never confirms.
///
/// The hold's own supply evidence is the authoritative record of the interval
/// it occupies, and this service wrote it, so a hold that has that evidence
/// does not need the cached row to be complete.
///
/// A hold old enough to predate the evidence still has to come from the cache,
/// and this cluster has millions of them: every confirmed hold stays live for
/// the life of the journey it was taken for, so 2562487 of the 2562487 live
/// holds when this was measured had no evidence, and none of their cache rows
/// carried a source. Rejecting those does not protect anything -- the hold is
/// already occupying the pool and its interval, service, date and class are all
/// recorded -- so what the occupancy check actually needs is a structurally
/// complete row. A row missing one of those is still refused, because that is
/// a hold whose interval we cannot place at all.
fn historical_hold_facts(
    hold: &CapacityHoldSnapshot,
    recorded: Value,
) -> Result<ReservationSupply, AppError> {
    let saved: ReservationSupply =
        serde_json::from_value(recorded).map_err(|e| AppError::Internal(e.to_string()))?;
    if saved.validate_source().is_ok() {
        return Ok(saved);
    }
    let Some(evidence) = hold.supply_evidence.as_ref() else {
        structural_hold_facts(&saved)?;
        return Ok(saved);
    };
    let booking_purpose = match evidence.source_type.as_str() {
        "PURCHASE_ORDER" => "purchase".to_owned(),
        "CHANGE_CASE" => format!("change:{}", evidence.source_ref),
        other => {
            return Err(AppError::Unavailable(format!(
                "recorded hold has an unsupported purchase source {other}"
            )));
        }
    };
    let facts = ReservationSupply {
        segment_booking_id: saved.segment_booking_id,
        journey_order_id: saved.journey_order_id,
        traveler_ref: saved.traveler_ref,
        segment_ref: saved.segment_ref,
        scheduled_service_ref: evidence.scheduled_service_ref.clone(),
        service_date: evidence.service_date.clone(),
        class_ref: evidence.accepted_class_ref.clone(),
        booking_purpose,
        offer_item_ref: saved.offer_item_ref,
        standing_accepted: evidence.accepted_class_ref != evidence.occupancy_class_ref,
        source_type: evidence.source_type.clone(),
        source_ref: evidence.source_ref.clone(),
    };
    facts.validate_source()?;
    Ok(facts)
}

/// Require only the facts that place a historical hold in an interval.
fn structural_hold_facts(facts: &ReservationSupply) -> Result<(), AppError> {
    for (name, value) in [
        ("segmentBookingId", facts.segment_booking_id.as_str()),
        ("journeyOrderId", facts.journey_order_id.as_str()),
        ("travelerRef", facts.traveler_ref.as_str()),
        ("segmentRef", facts.segment_ref.as_str()),
        ("scheduledServiceRef", facts.scheduled_service_ref.as_str()),
        ("serviceDate", facts.service_date.as_str()),
        ("classRef", facts.class_ref.as_str()),
    ] {
        if value.trim().is_empty() {
            return Err(AppError::Unavailable(format!(
                "active hold has no recorded {name}"
            )));
        }
    }
    Ok(())
}

/// The first unit of this class no earlier hold in the same snapshot has taken.
///
/// Used only for a historical hold whose own unit cannot be resolved, so that
/// the hold still reduces availability by one unit instead of blocking the
/// reservation that is being admitted.
fn free_unit_for(
    units: &[crate::supply_facts::PhysicalUnit],
    occupancy: &[PendingOccupancy],
) -> Option<String> {
    let taken: std::collections::HashSet<&str> = occupancy
        .iter()
        .map(|pending| pending.effective_capacity_unit_ref.as_str())
        .collect();
    units
        .iter()
        .map(|unit| unit.seat_unit_ref.as_str())
        .find(|reference| !taken.contains(reference))
        .map(str::to_owned)
}

impl PostgresCapacityService {
    pub(super) async fn hold_for_reservation(
        &self,
        tx: &mut PgTransaction<'_>,
        facts: &ReservationSupply,
    ) -> Result<Option<CapacityHoldSnapshot>, AppError> {
        let rows:Vec<Value>=sqlx::query_scalar("SELECT data FROM capacity_hold_snapshots WHERE data#>>'{references,segmentBookingRef}'=$1 ORDER BY id LIMIT 2")
            .bind(&facts.segment_booking_id).fetch_all(&mut **tx).await.map_err(to_app_storage)?;
        if rows.len() > 1 {
            return Err(AppError::PreconditionFailed(
                "reservation has multiple capacity Hold identities".into(),
            ));
        }
        let Some(data) = rows.into_iter().next() else {
            return Ok(None);
        };
        let hold: CapacityHoldSnapshot =
            serde_json::from_value(data).map_err(|error| AppError::Internal(error.to_string()))?;
        if hold.segment_ref() != facts.segment_ref
            || hold.references.traveler_ref.as_deref() != Some(facts.traveler_ref.as_str())
            || (hold.inventory_pool_id != pool_id_for(&facts.segment_ref, &facts.class_ref)
                && hold.inventory_pool_id != pool_id_for(&facts.segment_ref, "standard"))
        {
            return Err(AppError::PreconditionFailed(
                "recorded capacity Hold differs from its accepted reservation".into(),
            ));
        }
        if let Some(order) = &hold.references.order_ref {
            if order != &facts.journey_order_id && order != &facts.segment_booking_id {
                return Err(AppError::PreconditionFailed(
                    "recorded capacity Hold belongs to another order".into(),
                ));
            }
        }
        Ok(Some(hold))
    }

    pub(super) async fn reservation_supply(
        &self,
        booking: &str,
        correlation: &str,
    ) -> Result<ReservationSupply, AppError> {
        if let Some(data) = sqlx::query_scalar::<_, Value>(
            "SELECT data FROM reservation_supply_facts WHERE segment_booking_id=$1",
        )
        .bind(booking)
        .fetch_optional(self.pool())
        .await
        .map_err(to_app_storage)?
        {
            let saved: ReservationSupply = serde_json::from_value(data)
                .map_err(|error| AppError::Unavailable(error.to_string()))?;
            if saved.validate_source().is_ok() {
                return Ok(saved);
            }
            let resolved = self.supply_client.reservation(booking, correlation).await?;
            resolved.validate_source()?;
            if !same_core_reservation(&saved, &resolved) {
                return Err(AppError::PreconditionFailed(
                    "persisted reservation facts conflict with their authoritative source".into(),
                ));
            }
            sqlx::query("UPDATE reservation_supply_facts SET data=$2 WHERE segment_booking_id=$1")
                .bind(booking)
                .bind(
                    serde_json::to_value(&resolved)
                        .map_err(|e| AppError::Internal(e.to_string()))?,
                )
                .execute(self.pool())
                .await
                .map_err(to_app_storage)?;
            return Ok(resolved);
        }
        let facts = self.supply_client.reservation(booking, correlation).await?;
        facts.validate_source()?;
        let mut tx = self.pool().begin().await.map_err(to_app_storage)?;
        sqlx::query("INSERT INTO reservation_supply_facts(segment_booking_id,scheduled_service_ref,service_date,class_ref,data) VALUES($1,$2,$3::date,$4,$5) ON CONFLICT DO NOTHING")
            .bind(booking).bind(&facts.scheduled_service_ref).bind(&facts.service_date).bind(&facts.class_ref)
            .bind(serde_json::to_value(&facts).map_err(|e| AppError::Internal(e.to_string()))?)
            .execute(&mut *tx).await.map_err(to_app_storage)?;
        let saved: Value = sqlx::query_scalar(
            "SELECT data FROM reservation_supply_facts WHERE segment_booking_id=$1",
        )
        .bind(booking)
        .fetch_one(&mut *tx)
        .await
        .map_err(to_app_storage)?;
        let saved: ReservationSupply =
            serde_json::from_value(saved).map_err(|e| AppError::Internal(e.to_string()))?;
        if saved != facts {
            return Err(AppError::PreconditionFailed(
                "reservation supply facts changed during lookup".into(),
            ));
        }
        tx.commit().await.map_err(to_app_storage)?;
        Ok(saved)
    }

    pub(super) async fn prepare_supply_history(
        &self,
        service: &str,
        segment: &str,
        correlation: &str,
    ) -> Result<PreparedSupply, AppError> {
        let segments = self
            .supply_client
            .service_segments(service, correlation)
            .await?;
        if !segments.iter().any(|candidate| candidate == segment) {
            return Err(AppError::Unavailable(
                "requested segment is absent from scheduled service".into(),
            ));
        }
        let routes: Vec<_> = stream::iter(segments.clone())
            .map(|reference| async move {
                let route = self.supply_client.route(&reference, correlation).await?;
                if route.scheduled_service_ref != service {
                    return Err(AppError::Unavailable(
                        "segment route belongs to another service".into(),
                    ));
                }
                Ok((reference, route))
            })
            .buffer_unordered(8)
            .try_collect()
            .await?;
        let missing: Vec<Option<String>> = sqlx::query_scalar(
            "SELECT DISTINCT hold->'references'->>'segmentBookingRef' FROM inventory_pool_snapshots pool
             CROSS JOIN LATERAL jsonb_array_elements(pool.data->'holds') hold
             LEFT JOIN reservation_supply_facts facts ON facts.segment_booking_id=hold->'references'->>'segmentBookingRef'
             WHERE (pool.service_segment_ref=ANY($1) OR pool.scheduled_service_ref=$2)
               AND (hold->>'state'='Confirmed' OR (hold->>'state'='Held' AND (hold->>'expiresAt')::bigint>$3))
               AND facts.segment_booking_id IS NULL")
            .bind(&segments).bind(service).bind(now_millis() as i64)
            .fetch_all(self.pool()).await.map_err(to_app_storage)?;
        let bookings: Vec<String> = missing
            .into_iter()
            .map(|booking| {
                booking.ok_or_else(|| {
                    AppError::Unavailable("active inventory hold has no booking reference".into())
                })
            })
            .collect::<Result<_, _>>()?;
        stream::iter(bookings)
            .map(|booking| async move {
                self.reservation_supply(&booking, correlation)
                    .await
                    .map(|_| ())
            })
            .buffer_unordered(8)
            .try_collect::<Vec<_>>()
            .await?;
        Ok(PreparedSupply {
            segments,
            routes: routes.into_iter().collect(),
        })
    }

    pub(super) async fn lock_supply(
        tx: &mut PgTransaction<'_>,
        supply: &ReservationSupply,
    ) -> Result<(), AppError> {
        sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1,0))")
            .bind(format!(
                "capacity-supply:{}:{}",
                supply.scheduled_service_ref, supply.service_date
            ))
            .execute(&mut **tx)
            .await
            .map_err(to_app_storage)?;
        Ok(())
    }

    pub(super) async fn available_supply(
        &self,
        tx: &mut PgTransaction<'_>,
        facts: &ReservationSupply,
        segments: &PreparedSupply,
        correlation: &str,
        now: u64,
    ) -> Result<(SeatSupply, Vec<String>, Vec<PendingOccupancy>), AppError> {
        let route = segments.route(&facts.segment_ref)?;
        if route.service_date != facts.service_date {
            return Err(AppError::PreconditionFailed(
                "reservation date differs from its validated route".into(),
            ));
        }
        let supply = self.supply_client.seats(facts, correlation).await?;
        let rows: Vec<(Value, Option<Value>)> = sqlx::query_as(
            "SELECT hold.data, facts.data FROM capacity_hold_snapshots hold
             LEFT JOIN reservation_supply_facts facts ON facts.segment_booking_id=hold.data#>>'{references,segmentBookingRef}'
             WHERE facts.scheduled_service_ref=$1 AND facts.service_date=$2::date")
            .bind(&facts.scheduled_service_ref).bind(&facts.service_date)
            .fetch_all(&mut **tx).await.map_err(to_app_storage)?;
        let mut occupancy = Vec::new();
        for (hold, recorded) in rows {
            let hold: CapacityHoldSnapshot =
                serde_json::from_value(hold).map_err(|e| AppError::Internal(e.to_string()))?;
            let state = if hold.state == "Held" && hold.expires_at <= now {
                "Expired".to_owned()
            } else {
                hold.state.clone()
            };
            if matches!(state.as_str(), "Released" | "Expired" | "Failed") {
                occupancy.push(PendingOccupancy {
                    hold_id: hold.hold_id,
                    segment_booking_id: String::new(),
                    journey_order_id: String::new(),
                    traveler_ref: String::new(),
                    capacity_unit_ref: hold.capacity_unit_ref.clone(),
                    effective_capacity_unit_ref: hold.capacity_unit_ref,
                    state,
                    interval: SupplyInterval {
                        from_seq: hold.from_seq,
                        to_seq: hold.to_seq,
                    },
                });
                continue;
            }
            if state != "Held" && state != "Confirmed" {
                continue;
            }
            let recorded = recorded.ok_or_else(|| {
                AppError::Unavailable("active hold has no persisted supply facts".into())
            })?;
            let hold_facts = historical_hold_facts(&hold, recorded)?;
            if hold.references.segment_booking_ref.as_deref()
                != Some(hold_facts.segment_booking_id.as_str())
                || hold.references.traveler_ref.as_deref() != Some(hold_facts.traveler_ref.as_str())
                || hold.segment_ref() != hold_facts.segment_ref
            {
                return Err(AppError::Unavailable(
                    "active hold differs from its persisted reservation".into(),
                ));
            }
            let occupancy_class = hold
                .supply_evidence
                .as_ref()
                .map(|evidence| evidence.occupancy_class_ref.as_str())
                .unwrap_or(hold_facts.class_ref.as_str());
            if hold_facts.scheduled_service_ref != facts.scheduled_service_ref
                || hold_facts.service_date != facts.service_date
                || occupancy_class != facts.class_ref
            {
                continue;
            }
            let route = segments.route(&hold_facts.segment_ref)?;
            if route.service_date != hold_facts.service_date
                || route.interval.from_seq != hold.from_seq
                || route.interval.to_seq != hold.to_seq
            {
                return Err(AppError::Unavailable(
                    "historical Hold interval differs from its validated service route".into(),
                ));
            }
            if let Some(evidence) = hold.supply_evidence.as_ref() {
                if evidence.accepted_class_ref != hold_facts.class_ref
                    || evidence.occupancy_class_ref != occupancy_class
                    || evidence.scheduled_service_ref != hold_facts.scheduled_service_ref
                    || evidence.service_date != hold_facts.service_date
                    || evidence.source_type != hold_facts.source_type
                    || evidence.source_ref != hold_facts.source_ref
                    || evidence.stop_sequence_version != route.stop_sequence_version
                {
                    return Err(AppError::Unavailable(
                        "active Hold supply evidence differs from authoritative facts".into(),
                    ));
                }
            }
            let effective_capacity_unit_ref = match supply
                .resolve_capacity_unit(&hold.capacity_unit_ref, hold.supply_evidence.as_ref())
            {
                Ok(reference) => reference,
                Err(error) => {
                    let allocations: Vec<_> = supply
                        .allocations
                        .iter()
                        .filter(|allocation| allocation.capacity_hold_id == hold.hold_id)
                        .collect();
                    if allocations.len() != 1 {
                        // A hold that predates the supply evidence has no way to
                        // say which composition its unit came from, so a unit
                        // that appears in more than one replacement lineage is
                        // ambiguous rather than wrong. Measured: 2560980 of
                        // 2562487 live holds carry no evidence, and failing the
                        // admission on one of them blocked every reservation
                        // into the pool it occupies -- which is what the load
                        // generator reported, thousands of times, as a
                        // reservation that never confirms.
                        //
                        // The hold still occupies one unit of its class, so it
                        // is counted against one that is free in this snapshot
                        // rather than dropped. Admissions for one service and
                        // date are serialized by the supply advisory lock, so
                        // two admissions cannot choose the same unit here, and
                        // the count of occupied units stays exact even though
                        // the identity does not. Backfilling the binding from
                        // the owning composition is recorded in docs/TODO.md.
                        match free_unit_for(&supply.units, &occupancy) {
                            Some(reference) => reference,
                            None => return Err(error),
                        }
                    } else {
                        supply.resolve_allocation_unit(allocations[0])?
                    }
                }
            };
            occupancy.push(PendingOccupancy {
                hold_id: hold.hold_id,
                segment_booking_id: hold_facts.segment_booking_id,
                journey_order_id: hold_facts.journey_order_id,
                traveler_ref: hold_facts.traveler_ref,
                capacity_unit_ref: hold.capacity_unit_ref,
                effective_capacity_unit_ref,
                state,
                interval: SupplyInterval {
                    from_seq: hold.from_seq,
                    to_seq: hold.to_seq,
                },
            });
        }
        let available = supply.available_refs(&route.interval, &occupancy)?;
        sqlx::query("INSERT INTO capacity_supply_observations(inventory_pool_id,seat_map_id,seat_map_version,sellable_capacity,data) VALUES($1,$2,$3,$4,$5) ON CONFLICT(inventory_pool_id) DO UPDATE SET seat_map_id=EXCLUDED.seat_map_id,seat_map_version=EXCLUDED.seat_map_version,sellable_capacity=EXCLUDED.sellable_capacity,data=EXCLUDED.data,observed_at=now()")
            .bind(pool_id_for(&facts.segment_ref,&facts.class_ref)).bind(&supply.seat_map_id).bind(supply.seat_map_version as i64)
            .bind(supply.capacity() as i32).bind(serde_json::to_value(&supply).map_err(|e| AppError::Internal(e.to_string()))?)
            .execute(&mut **tx).await.map_err(to_app_storage)?;
        Ok((supply, available, occupancy))
    }

    pub(super) async fn admission_supply(
        &self,
        tx: &mut PgTransaction<'_>,
        accepted: &ReservationSupply,
        segments: &PreparedSupply,
        correlation: &str,
        now: u64,
    ) -> Result<
        (
            ReservationSupply,
            SeatSupply,
            Vec<String>,
            Vec<PendingOccupancy>,
        ),
        AppError,
    > {
        let (supply, available, occupancy) = self
            .available_supply(tx, accepted, segments, correlation, now)
            .await?;
        if !available.is_empty() || accepted.class_ref == "STANDING" || !accepted.standing_accepted
        {
            return Ok((accepted.clone(), supply, available, occupancy));
        }
        let mut standing = accepted.clone();
        standing.class_ref = "STANDING".into();
        let (supply, available, occupancy) = self
            .available_supply(tx, &standing, segments, correlation, now)
            .await?;
        Ok((standing, supply, available, occupancy))
    }
}

fn same_core_reservation(left: &ReservationSupply, right: &ReservationSupply) -> bool {
    left.segment_booking_id == right.segment_booking_id
        && left.journey_order_id == right.journey_order_id
        && left.traveler_ref == right.traveler_ref
        && left.segment_ref == right.segment_ref
        && left.scheduled_service_ref == right.scheduled_service_ref
        && left.service_date == right.service_date
        && left.class_ref == right.class_ref
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::HoldSupplyEvidence;

    fn hold_with_evidence(evidence: Option<HoldSupplyEvidence>) -> CapacityHoldSnapshot {
        CapacityHoldSnapshot {
            hold_id: "hold-1".into(),
            inventory_pool_id: "pool-1".into(),
            capacity_unit_ref: "unit-1".into(),
            from_seq: 0,
            to_seq: 1,
            idempotency_key: "key-1".into(),
            requested_at: 1,
            expires_at: 2,
            state: "Confirmed".into(),
            confirmed_at: Some(1),
            released_at: None,
            expired_at: None,
            references: ReferenceMetadataSnapshot {
                source_context: "journey-order".into(),
                order_ref: Some("ord-1".into()),
                segment_booking_ref: Some("sb-1".into()),
                traveler_ref: Some("tvl-1".into()),
                reason: "test".into(),
            },
            supply_evidence: evidence,
        }
    }

    fn legacy_recorded() -> Value {
        // What the majority of persisted rows look like: written before the
        // purchase source became part of the facts, so it cannot validate.
        serde_json::json!({
            "segmentBookingId": "sb-1",
            "journeyOrderId": "ord-1",
            "travelerRef": "tvl-1",
            "segmentRef": "seg-1",
            "scheduledServiceRef": "ss-1",
            "serviceDate": "2026-09-26",
            "classRef": "SECOND_CLASS",
            "bookingPurpose": "",
            "offerItemRef": "offer-1",
            "standingAccepted": false,
            "sourceType": "",
            "sourceRef": ""
        })
    }

    fn evidence(
        source_type: &str,
        source_ref: &str,
        accepted: &str,
        occupancy: &str,
    ) -> HoldSupplyEvidence {
        HoldSupplyEvidence::new(
            accepted,
            occupancy,
            "ss-1",
            "2026-09-26",
            source_type,
            source_ref,
            "seat-map-1",
            1,
            "composition-1",
            "stops-1",
        )
        .expect("evidence")
    }

    #[test]
    fn a_valid_cache_row_is_used_as_written() {
        let mut recorded = legacy_recorded();
        recorded["bookingPurpose"] = Value::String("purchase".into());
        recorded["sourceType"] = Value::String("PURCHASE_ORDER".into());
        recorded["sourceRef"] = Value::String("ord-1".into());

        let facts = historical_hold_facts(&hold_with_evidence(None), recorded).expect("facts");

        assert_eq!(facts.booking_purpose, "purchase");
        assert_eq!(facts.source_type, "PURCHASE_ORDER");
        assert_eq!(facts.source_ref, "ord-1");
    }

    #[test]
    fn a_legacy_cache_row_is_resolved_from_the_holds_own_evidence() {
        // The regression: this is the row that made every new reservation into
        // the pool fail with "reservation has an unsupported purchase source".
        let hold = hold_with_evidence(Some(evidence(
            "PURCHASE_ORDER",
            "ord-1",
            "SECOND_CLASS",
            "SECOND_CLASS",
        )));

        let facts = historical_hold_facts(&hold, legacy_recorded()).expect("facts");

        assert_eq!(facts.booking_purpose, "purchase");
        assert_eq!(facts.segment_booking_id, "sb-1");
        assert_eq!(facts.journey_order_id, "ord-1");
        assert_eq!(facts.traveler_ref, "tvl-1");
        assert_eq!(facts.scheduled_service_ref, "ss-1");
        assert_eq!(facts.service_date, "2026-09-26");
        assert_eq!(facts.class_ref, "SECOND_CLASS");
        assert_eq!(facts.source_type, "PURCHASE_ORDER");
        assert_eq!(facts.source_ref, "ord-1");
        assert!(!facts.standing_accepted);
    }

    #[test]
    fn a_change_hold_resolves_to_its_case() {
        let hold = hold_with_evidence(Some(evidence(
            "CHANGE_CASE",
            "case-1",
            "SECOND_CLASS",
            "STANDING",
        )));

        let facts = historical_hold_facts(&hold, legacy_recorded()).expect("facts");

        assert_eq!(facts.booking_purpose, "change:case-1");
        assert_eq!(facts.source_type, "CHANGE_CASE");
        // An accepted class that differs from the occupancy class is the
        // standing substitution, and it has to survive the resolution.
        assert!(facts.standing_accepted);
    }

    #[test]
    fn a_hold_without_evidence_uses_its_structurally_complete_cache_row() {
        // Millions of confirmed holds predate the evidence field and stay live
        // for the life of their journey. Their cached rows record the interval
        // even though they carry no source, and refusing them is what made new
        // reservations fail.
        let facts =
            historical_hold_facts(&hold_with_evidence(None), legacy_recorded()).expect("facts");

        assert_eq!(facts.segment_booking_id, "sb-1");
        assert_eq!(facts.scheduled_service_ref, "ss-1");
        assert_eq!(facts.class_ref, "SECOND_CLASS");
    }

    #[test]
    fn a_hold_without_evidence_still_refuses_a_row_that_cannot_place_it() {
        // Without a service and date there is no interval to attribute, and
        // that stays an error rather than being guessed at.
        let mut recorded = legacy_recorded();
        recorded["scheduledServiceRef"] = Value::String(String::new());

        let error =
            historical_hold_facts(&hold_with_evidence(None), recorded).expect_err("must refuse");

        assert!(
            format!("{error:?}").contains("scheduledServiceRef"),
            "{error:?}"
        );
    }
}
