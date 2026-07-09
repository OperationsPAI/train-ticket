use crate::utils::*;
use crate::*;
use async_trait::async_trait;
use serde_json::Value;

pub struct PostgresSeatAssignmentService {
    storage: rust_kit::storage::Storage,
}
impl PostgresSeatAssignmentService {
    pub async fn from_env() -> Result<Self, SeatAssignmentError> {
        let storage = rust_kit::storage::Storage::from_env()
            .await
            .map_err(storage_error)?;
        let dir = std::env::var("MIGRATIONS_DIR").unwrap_or_else(|_| "/app/migrations".into());
        match storage.migrate_dir(&dir).await {
            Ok(()) => {}
            Err(e) => {
                if dir == "/app/migrations" {
                    storage
                        .migrate_dir("services/seat-assignment/migrations")
                        .await
                        .map_err(storage_error)?
                } else {
                    return Err(storage_error(e));
                }
            }
        };
        Ok(Self { storage })
    }
    pub fn pool(&self) -> &sqlx::PgPool {
        self.storage.pool()
    }
    pub async fn is_ready(&self) -> bool {
        self.storage.is_ready().await
    }
    async fn save_map(
        tx: &mut rust_kit::storage::PgTransaction<'_>,
        m: &SeatMap,
    ) -> Result<(), SeatAssignmentError> {
        sqlx::query("INSERT INTO seat_maps (seat_map_id,scheduled_service_ref,service_date,status,version,data,created_at) VALUES ($1,$2,$3,$4,$5,$6,$7) ON CONFLICT (seat_map_id) DO UPDATE SET scheduled_service_ref=EXCLUDED.scheduled_service_ref,service_date=EXCLUDED.service_date,status=EXCLUDED.status,version=EXCLUDED.version,data=EXCLUDED.data,updated_at=to_char(now() AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')").bind(&m.seat_map_id).bind(&m.scheduled_service_ref).bind(&m.service_date).bind(m.status.as_contract()).bind(m.seat_map_version).bind(serde_json::to_value(m).map_err(|e|SeatAssignmentError::Internal(e.to_string()))?).bind(&m.created_at).execute(&mut **tx).await.map_err(db_error)?;
        Ok(())
    }
    async fn load_map(
        tx: &mut rust_kit::storage::PgTransaction<'_>,
        id: &str,
    ) -> Result<Option<SeatMap>, SeatAssignmentError> {
        let row: Option<(Value,)> =
            sqlx::query_as("SELECT data FROM seat_maps WHERE seat_map_id=$1 FOR UPDATE")
                .bind(id)
                .fetch_optional(&mut **tx)
                .await
                .map_err(db_error)?;
        row.map(|(v,)| {
            serde_json::from_value(v).map_err(|e| SeatAssignmentError::Internal(e.to_string()))
        })
        .transpose()
    }
    async fn save_allocation(
        tx: &mut rust_kit::storage::PgTransaction<'_>,
        a: &SeatAllocation,
    ) -> Result<(), SeatAssignmentError> {
        sqlx::query("INSERT INTO seat_allocations (seat_allocation_id,segment_booking_id,capacity_hold_id,seat_map_id,seat_unit_ref,status,data,created_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8) ON CONFLICT (seat_allocation_id) DO UPDATE SET segment_booking_id=EXCLUDED.segment_booking_id,capacity_hold_id=EXCLUDED.capacity_hold_id,seat_map_id=EXCLUDED.seat_map_id,seat_unit_ref=EXCLUDED.seat_unit_ref,status=EXCLUDED.status,data=EXCLUDED.data,updated_at=to_char(now() AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')").bind(&a.seat_allocation_id).bind(&a.segment_booking_id).bind(&a.capacity_hold_id).bind(&a.seat_ref.seat_map_id).bind(&a.seat_ref.seat_unit_ref).bind(a.status.as_contract()).bind(serde_json::to_value(a).map_err(|e|SeatAssignmentError::Internal(e.to_string()))?).bind(&a.created_at).execute(&mut **tx).await.map_err(db_error)?;
        Ok(())
    }
    async fn append(
        tx: &mut rust_kit::storage::PgTransaction<'_>,
        id: &str,
        version: i64,
        events: Vec<SeatAssignmentEvent>,
        corr: String,
        cause: Option<String>,
    ) -> Result<(), SeatAssignmentError> {
        for (i, e) in events.into_iter().enumerate() {
            if let Some(env) = e.envelope(id, version + i as i64, corr.clone(), cause.clone()) {
                rust_kit::storage::OutboxAppender::append(
                    tx,
                    &rust_kit::messaging::stream_for_producer(profile().service_id),
                    &env,
                )
                .await
                .map_err(storage_error)?;
            }
        }
        Ok(())
    }
    pub async fn handle_subscribed_event(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), rust_kit::messaging::HandlerError> {
        self.apply_subscribed_event(envelope)
            .await
            .map_err(handler_error)
    }
    pub async fn apply_subscribed_event(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), SeatAssignmentError> {
        match envelope.event_type.as_str() {
            "EntitlementIssued" => self.confirm_from_entitlement(envelope).await,
            "EntitlementVoided" => {
                self.release_from_entitlement(envelope, ReleaseReason::Voided)
                    .await
            }
            "EntitlementIssueFailed" => {
                self.release_from_entitlement(envelope, ReleaseReason::IssueFailed)
                    .await
            }
            "CapacityReleased" => {
                self.release_by_hold(envelope, ReleaseReason::CapacityReleased, false)
                    .await
            }
            "CapacityHoldExpired" => {
                self.release_by_hold(envelope, ReleaseReason::HoldExpired, true)
                    .await
            }
            _ => Ok(()),
        }
    }
    async fn mark_processed(
        tx: &mut rust_kit::storage::PgTransaction<'_>,
        event_id: &str,
        stream: &str,
    ) -> Result<bool, SeatAssignmentError> {
        let n=sqlx::query("INSERT INTO seat_assignment_processed_events (event_id,stream) VALUES ($1,$2) ON CONFLICT DO NOTHING").bind(event_id).bind(stream).execute(&mut **tx).await.map_err(db_error)?.rows_affected();
        Ok(n > 0)
    }
    async fn confirm_from_entitlement(
        &self,
        e: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), SeatAssignmentError> {
        let Some(id) = string_field(&e.payload, "seatAllocationId").or_else(|| {
            e.payload
                .get("seatRef")
                .and_then(|v| string_field(v, "seatAllocationId"))
        }) else {
            return Ok(());
        };
        let ent = string_field(&e.payload, "entitlementId").unwrap_or_default();
        let mut tx = self.pool().begin().await.map_err(db_error)?;
        if !Self::mark_processed(
            &mut tx,
            &e.event_id,
            &crate::adapters::messaging::entitlement_stream(),
        )
        .await?
        {
            tx.commit().await.map_err(db_error)?;
            return Ok(());
        }
        let row: Option<(Value,)> = sqlx::query_as(
            "SELECT data FROM seat_allocations WHERE seat_allocation_id=$1 FOR UPDATE",
        )
        .bind(&id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(db_error)?;
        if let Some((v,)) = row {
            let mut a: SeatAllocation = serde_json::from_value(v)
                .map_err(|er| SeatAssignmentError::Internal(er.to_string()))?;
            let ev = a.confirm(ent, e.event_id.clone(), e.occurred_at.clone())?;
            Self::save_allocation(&mut tx, &a).await?;
            Self::append(
                &mut tx,
                &a.seat_allocation_id,
                a.version,
                vec![ev],
                e.correlation_id,
                Some(e.event_id),
            )
            .await?;
        }
        tx.commit().await.map_err(db_error)?;
        Ok(())
    }
    async fn release_from_entitlement(
        &self,
        e: rust_kit::messaging::EventEnvelope,
        reason: ReleaseReason,
    ) -> Result<(), SeatAssignmentError> {
        let id = string_field(&e.payload, "seatAllocationId").or_else(|| {
            e.payload
                .get("seatRef")
                .and_then(|v| string_field(v, "seatAllocationId"))
        });
        let sb = string_field(&e.payload, "segmentBookingId");
        let mut tx = self.pool().begin().await.map_err(db_error)?;
        if !Self::mark_processed(
            &mut tx,
            &e.event_id,
            &crate::adapters::messaging::entitlement_stream(),
        )
        .await?
        {
            tx.commit().await.map_err(db_error)?;
            return Ok(());
        }
        let row: Option<(Value,)> = if let Some(id) = id {
            sqlx::query_as(
                "SELECT data FROM seat_allocations WHERE seat_allocation_id=$1 FOR UPDATE",
            )
            .bind(id)
            .fetch_optional(&mut *tx)
            .await
            .map_err(db_error)?
        } else if let Some(sb) = sb {
            sqlx::query_as("SELECT data FROM seat_allocations WHERE segment_booking_id=$1 AND status IN ('ALLOCATED','STANDING','CONFIRMED') LIMIT 1 FOR UPDATE").bind(sb).fetch_optional(&mut *tx).await.map_err(db_error)?
        } else {
            None
        };
        if let Some((v,)) = row {
            let mut a: SeatAllocation = serde_json::from_value(v)
                .map_err(|er| SeatAssignmentError::Internal(er.to_string()))?;
            if let Some(ev) = a.release(reason, Some(e.event_id.clone()), e.occurred_at.clone())? {
                Self::save_allocation(&mut tx, &a).await?;
                Self::append(
                    &mut tx,
                    &a.seat_allocation_id,
                    a.version,
                    vec![ev],
                    e.correlation_id,
                    Some(e.event_id),
                )
                .await?;
            }
        }
        tx.commit().await.map_err(db_error)?;
        Ok(())
    }
    async fn release_by_hold(
        &self,
        e: rust_kit::messaging::EventEnvelope,
        reason: ReleaseReason,
        expire: bool,
    ) -> Result<(), SeatAssignmentError> {
        let Some(hold) = string_field(&e.payload, "holdId")
            .or_else(|| string_field(&e.payload, "capacityHoldId"))
        else {
            return Ok(());
        };
        let mut tx = self.pool().begin().await.map_err(db_error)?;
        if !Self::mark_processed(
            &mut tx,
            &e.event_id,
            &crate::adapters::messaging::capacity_stream(),
        )
        .await?
        {
            tx.commit().await.map_err(db_error)?;
            return Ok(());
        }
        let rows:Vec<(Value,)>=sqlx::query_as("SELECT data FROM seat_allocations WHERE capacity_hold_id=$1 AND status IN ('ALLOCATED','STANDING','CONFIRMED') FOR UPDATE").bind(hold).fetch_all(&mut *tx).await.map_err(db_error)?;
        for (v,) in rows {
            let mut a: SeatAllocation = serde_json::from_value(v)
                .map_err(|er| SeatAssignmentError::Internal(er.to_string()))?;
            let ev = if expire {
                a.expire(e.event_id.clone(), e.occurred_at.clone())?
            } else {
                a.release(reason, Some(e.event_id.clone()), e.occurred_at.clone())?
            };
            if let Some(ev) = ev {
                Self::save_allocation(&mut tx, &a).await?;
                Self::append(
                    &mut tx,
                    &a.seat_allocation_id,
                    a.version,
                    vec![ev],
                    e.correlation_id.clone(),
                    Some(e.event_id.clone()),
                )
                .await?;
            }
        }
        tx.commit().await.map_err(db_error)?;
        Ok(())
    }
}

#[async_trait]
impl SeatAssignmentApi for PostgresSeatAssignmentService {
    async fn create_seat_map(
        &self,
        cmd: CreateSeatMapCommand,
        key: String,
        corr: String,
    ) -> Result<SeatMap, SeatAssignmentError> {
        validate_uuid_v7_key(&key)?;
        let fp = normalize_json(&cmd);
        let mut tx = self.pool().begin().await.map_err(db_error)?;
        if let rust_kit::storage::IdempotencyTxDecision::Replay(r) =
            rust_kit::storage::DbIdempotencyStore::claim_response(&mut tx, &key, &fp)
                .await
                .map_err(storage_error)?
        {
            tx.commit().await.map_err(db_error)?;
            return serde_json::from_value(r.body)
                .map_err(|e| SeatAssignmentError::Internal(e.to_string()));
        }
        let (map, events) = SeatMap::build(cmd, current_rfc3339())?;
        Self::save_map(&mut tx, &map).await?;
        Self::append(&mut tx, &map.seat_map_id, 1, events, corr, None).await?;
        rust_kit::storage::DbIdempotencyStore::record_response(
            &mut tx,
            &key,
            &fp,
            201,
            serde_json::to_value(&map).unwrap(),
        )
        .await
        .map_err(storage_error)?;
        tx.commit().await.map_err(db_error)?;
        Ok(map)
    }
    async fn publish_seat_map(
        &self,
        id: String,
        c: PublishSeatMapCommand,
        key: String,
        corr: String,
    ) -> Result<SeatMap, SeatAssignmentError> {
        self.mutate_map(id, key, corr, normalize_json(&c), |m, now| {
            m.publish(c.expected_seat_map_version, c.operator_ref, now)
        })
        .await
    }
    async fn retire_seat_map(
        &self,
        id: String,
        c: RetireSeatMapCommand,
        key: String,
        corr: String,
    ) -> Result<SeatMap, SeatAssignmentError> {
        self.mutate_map(id, key, corr, normalize_json(&c), |m, now| {
            m.retire(
                c.expected_seat_map_version,
                c.retire_reason,
                c.operator_ref,
                now,
            )
        })
        .await
    }
    async fn mark_unavailable(
        &self,
        id: String,
        su: String,
        c: MarkUnavailableCommand,
        key: String,
        corr: String,
    ) -> Result<SeatMap, SeatAssignmentError> {
        self.mutate_map(
            id,
            key,
            corr,
            format!("{su}:{}", normalize_json(&c)),
            |m, now| {
                m.mark_unavailable(
                    &su,
                    c.expected_seat_map_version,
                    c.unavailable_reason,
                    c.operator_ref,
                    now,
                )
            },
        )
        .await
    }
    async fn reopen(
        &self,
        id: String,
        su: String,
        c: ReopenSeatUnitCommand,
        key: String,
        corr: String,
    ) -> Result<SeatMap, SeatAssignmentError> {
        self.mutate_map(
            id,
            key,
            corr,
            format!("{su}:{}", normalize_json(&c)),
            |m, now| {
                m.reopen(
                    &su,
                    c.expected_seat_map_version,
                    c.reopen_reason,
                    c.operator_ref,
                    now,
                )
            },
        )
        .await
    }
    async fn get_seat_map(&self, id: String) -> Result<SeatMap, SeatAssignmentError> {
        let row: Option<(Value,)> =
            sqlx::query_as("SELECT data FROM seat_maps WHERE seat_map_id=$1")
                .bind(id)
                .fetch_optional(self.pool())
                .await
                .map_err(db_error)?;
        serde_json::from_value(
            row.ok_or_else(|| SeatAssignmentError::NotFound("SeatMap not found".into()))?
                .0,
        )
        .map_err(|e| SeatAssignmentError::Internal(e.to_string()))
    }
    async fn list_seat_maps(
        &self,
        scheduled: String,
        date: String,
        status: Option<SeatMapStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<PaginatedSeatMaps, SeatAssignmentError> {
        let status = status.map(|s| s.as_contract().to_string());
        let rows:Vec<(Value,i64)>=sqlx::query_as("SELECT data, count(*) OVER() AS total FROM seat_maps WHERE scheduled_service_ref=$1 AND service_date=$2 AND ($3::text IS NULL OR status=$3) ORDER BY seat_map_id LIMIT $4 OFFSET $5").bind(scheduled).bind(date).bind(status).bind(limit as i64).bind(offset as i64).fetch_all(self.pool()).await.map_err(db_error)?;
        let total = rows.first().map(|r| r.1 as usize).unwrap_or(0);
        let items = rows
            .into_iter()
            .map(|(v, _)| {
                serde_json::from_value(v).map_err(|e| SeatAssignmentError::Internal(e.to_string()))
            })
            .collect::<Result<Vec<_>, _>>()?;
        Ok(PaginatedSeatMaps {
            items,
            total,
            limit,
            offset,
        })
    }
    async fn allocate(
        &self,
        cmd: AllocateSeatCommand,
        key: String,
        corr: String,
    ) -> Result<AllocateSeatResponse, SeatAssignmentError> {
        validate_uuid_v7_key(&key)?;
        let fp = normalize_json(&cmd);
        let mut tx = self.pool().begin().await.map_err(db_error)?;
        if let rust_kit::storage::IdempotencyTxDecision::Replay(r) =
            rust_kit::storage::DbIdempotencyStore::claim_response(&mut tx, &key, &fp)
                .await
                .map_err(storage_error)?
        {
            tx.commit().await.map_err(db_error)?;
            return serde_json::from_value(r.body)
                .map_err(|e| SeatAssignmentError::Internal(e.to_string()));
        }
        let row:Option<(Value,)>=sqlx::query_as("SELECT data FROM seat_maps WHERE scheduled_service_ref=$1 AND service_date=$2 AND status='PUBLISHED' ORDER BY version DESC LIMIT 1 FOR UPDATE").bind(&cmd.scheduled_service_ref).bind(&cmd.service_date).fetch_optional(&mut *tx).await.map_err(db_error)?;
        let map: SeatMap = serde_json::from_value(
            row.ok_or_else(|| {
                SeatAssignmentError::PreconditionFailed(
                    "no published SeatMap matches the requested scheduled service/date".into(),
                )
            })?
            .0,
        )
        .map_err(|e| SeatAssignmentError::Internal(e.to_string()))?;
        let rows:Vec<(Value,)>=sqlx::query_as("SELECT data FROM seat_allocations WHERE status IN ('ALLOCATED','STANDING','CONFIRMED') FOR UPDATE").fetch_all(&mut *tx).await.map_err(db_error)?;
        let occupied = rows
            .into_iter()
            .map(|(v,)| {
                serde_json::from_value(v).map_err(|e| SeatAssignmentError::Internal(e.to_string()))
            })
            .collect::<Result<Vec<_>, _>>()?;
        let (allocation, events) =
            SeatAllocation::allocate(cmd, &map, &occupied, current_rfc3339())?;
        let response = AllocateSeatResponse::from(&allocation);
        Self::save_allocation(&mut tx, &allocation).await?;
        Self::append(
            &mut tx,
            &allocation.seat_allocation_id,
            allocation.version,
            events,
            corr,
            Some(command_id_from_key(&key)),
        )
        .await?;
        rust_kit::storage::DbIdempotencyStore::record_response(
            &mut tx,
            &key,
            &fp,
            201,
            serde_json::to_value(&response).unwrap(),
        )
        .await
        .map_err(storage_error)?;
        tx.commit().await.map_err(db_error)?;
        Ok(response)
    }
    async fn get_allocation(&self, id: String) -> Result<SeatAllocation, SeatAssignmentError> {
        let row: Option<(Value,)> =
            sqlx::query_as("SELECT data FROM seat_allocations WHERE seat_allocation_id=$1")
                .bind(id)
                .fetch_optional(self.pool())
                .await
                .map_err(db_error)?;
        serde_json::from_value(
            row.ok_or_else(|| SeatAssignmentError::NotFound("SeatAllocation not found".into()))?
                .0,
        )
        .map_err(|e| SeatAssignmentError::Internal(e.to_string()))
    }
    async fn list_allocations(
        &self,
        sb: String,
        status: Option<AllocationStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<PaginatedSeatAllocations, SeatAssignmentError> {
        let status = status.map(|s| s.as_contract().to_string());
        let rows:Vec<(Value,i64)>=sqlx::query_as("SELECT data, count(*) OVER() AS total FROM seat_allocations WHERE segment_booking_id=$1 AND ($2::text IS NULL OR status=$2) ORDER BY seat_allocation_id LIMIT $3 OFFSET $4").bind(sb).bind(status).bind(limit as i64).bind(offset as i64).fetch_all(self.pool()).await.map_err(db_error)?;
        let total = rows.first().map(|r| r.1 as usize).unwrap_or(0);
        let items = rows
            .into_iter()
            .map(|(v, _)| {
                serde_json::from_value(v).map_err(|e| SeatAssignmentError::Internal(e.to_string()))
            })
            .collect::<Result<Vec<_>, _>>()?;
        Ok(PaginatedSeatAllocations {
            items,
            total,
            limit,
            offset,
        })
    }
}
impl PostgresSeatAssignmentService {
    async fn mutate_map<F>(
        &self,
        id: String,
        key: String,
        corr: String,
        fp: String,
        fun: F,
    ) -> Result<SeatMap, SeatAssignmentError>
    where
        F: FnOnce(&mut SeatMap, String) -> Result<SeatAssignmentEvent, SeatAssignmentError>,
    {
        validate_uuid_v7_key(&key)?;
        let mut tx = self.pool().begin().await.map_err(db_error)?;
        if let rust_kit::storage::IdempotencyTxDecision::Replay(r) =
            rust_kit::storage::DbIdempotencyStore::claim_response(&mut tx, &key, &fp)
                .await
                .map_err(storage_error)?
        {
            tx.commit().await.map_err(db_error)?;
            return serde_json::from_value(r.body)
                .map_err(|e| SeatAssignmentError::Internal(e.to_string()));
        }
        let mut map = Self::load_map(&mut tx, &id)
            .await?
            .ok_or_else(|| SeatAssignmentError::NotFound("SeatMap not found".into()))?;
        let ev = fun(&mut map, current_rfc3339())?;
        Self::save_map(&mut tx, &map).await?;
        Self::append(
            &mut tx,
            &map.seat_map_id,
            map.seat_map_version,
            vec![ev],
            corr,
            None,
        )
        .await?;
        rust_kit::storage::DbIdempotencyStore::record_response(
            &mut tx,
            &key,
            &fp,
            200,
            serde_json::to_value(&map).unwrap(),
        )
        .await
        .map_err(storage_error)?;
        tx.commit().await.map_err(db_error)?;
        Ok(map)
    }
}
