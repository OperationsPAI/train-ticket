use super::*;
use futures_util::{StreamExt, TryStreamExt, stream};

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
                .bind(serde_json::to_value(&resolved).map_err(|e| AppError::Internal(e.to_string()))?)
                .execute(self.pool()).await.map_err(to_app_storage)?;
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
    ) -> Result<(SeatSupply, Vec<String>), AppError> {
        let rows: Vec<(Value, Option<Value>)> = sqlx::query_as(
            "SELECT hold, facts.data FROM inventory_pool_snapshots pool
             CROSS JOIN LATERAL jsonb_array_elements(pool.data->'holds') hold
             LEFT JOIN reservation_supply_facts facts ON facts.segment_booking_id=hold->'references'->>'segmentBookingRef'
             WHERE (pool.service_segment_ref=ANY($1) OR pool.scheduled_service_ref=$2)
               AND (hold->>'state'='Confirmed' OR (hold->>'state'='Held' AND (hold->>'expiresAt')::bigint>$3))")
            .bind(&segments.segments).bind(&facts.scheduled_service_ref).bind(now as i64)
            .fetch_all(&mut **tx).await.map_err(to_app_storage)?;
        let mut occupancy = Vec::new();
        for (hold, recorded) in rows {
            let hold: CapacityHoldSnapshot =
                serde_json::from_value(hold).map_err(|e| AppError::Internal(e.to_string()))?;
            let recorded = recorded.ok_or_else(|| {
                AppError::Unavailable("active hold has no persisted supply facts".into())
            })?;
            let hold_facts: ReservationSupply =
                serde_json::from_value(recorded).map_err(|e| AppError::Internal(e.to_string()))?;
            if hold.references.segment_booking_ref.as_deref()
                != Some(hold_facts.segment_booking_id.as_str())
                || hold.references.traveler_ref.as_deref() != Some(hold_facts.traveler_ref.as_str())
                || hold.segment_ref() != hold_facts.segment_ref
            {
                return Err(AppError::Unavailable(
                    "active hold differs from its persisted reservation".into(),
                ));
            }
            let occupancy_class = hold.supply_evidence.as_ref()
                .map(|evidence| evidence.occupancy_class_ref.as_str())
                .unwrap_or(hold_facts.class_ref.as_str());
            if hold_facts.scheduled_service_ref != facts.scheduled_service_ref
                || hold_facts.service_date != facts.service_date
                || occupancy_class != facts.class_ref {
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
            occupancy.push(PendingOccupancy {
                hold_id: hold.hold_id,
                segment_booking_id: hold_facts.segment_booking_id,
                journey_order_id: hold_facts.journey_order_id,
                traveler_ref: hold_facts.traveler_ref,
                capacity_unit_ref: hold.capacity_unit_ref,
                interval: SupplyInterval {
                    from_seq: hold.from_seq,
                    to_seq: hold.to_seq,
                },
            });
        }
        let route = segments.route(&facts.segment_ref)?;
        if route.service_date != facts.service_date {
            return Err(AppError::PreconditionFailed(
                "reservation date differs from its validated route".into(),
            ));
        }
        let supply = self.supply_client.seats(facts, correlation).await?;
        let available = supply.available_refs(&route.interval, &occupancy)?;
        sqlx::query("INSERT INTO capacity_supply_observations(inventory_pool_id,seat_map_id,seat_map_version,sellable_capacity,data) VALUES($1,$2,$3,$4,$5) ON CONFLICT(inventory_pool_id) DO UPDATE SET seat_map_id=EXCLUDED.seat_map_id,seat_map_version=EXCLUDED.seat_map_version,sellable_capacity=EXCLUDED.sellable_capacity,data=EXCLUDED.data,observed_at=now()")
            .bind(pool_id_for(&facts.segment_ref,&facts.class_ref)).bind(&supply.seat_map_id).bind(supply.seat_map_version as i64)
            .bind(supply.capacity() as i32).bind(serde_json::to_value(&supply).map_err(|e| AppError::Internal(e.to_string()))?)
            .execute(&mut **tx).await.map_err(to_app_storage)?;
        Ok((supply, available))
    }

    pub(super) async fn admission_supply(
        &self,
        tx: &mut PgTransaction<'_>,
        accepted: &ReservationSupply,
        segments: &PreparedSupply,
        correlation: &str,
        now: u64,
    ) -> Result<(ReservationSupply, SeatSupply, Vec<String>), AppError> {
        let (supply, available) = self.available_supply(tx, accepted, segments, correlation, now).await?;
        if !available.is_empty() || accepted.class_ref == "STANDING" || !accepted.standing_accepted {
            return Ok((accepted.clone(), supply, available));
        }
        let mut standing = accepted.clone();
        standing.class_ref = "STANDING".into();
        let (supply, available) = self.available_supply(tx, &standing, segments, correlation, now).await?;
        Ok((standing, supply, available))
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
