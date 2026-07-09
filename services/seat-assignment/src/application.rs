use crate::utils::*;
use crate::*;
use async_trait::async_trait;
use std::collections::{HashMap, HashSet};
use std::fmt;
use std::sync::{Arc, Mutex};

pub struct InMemorySeatAssignmentService {
    state: Mutex<InMemoryState>,
    publisher: Arc<dyn EventPublisher>,
}
impl Default for InMemorySeatAssignmentService {
    fn default() -> Self {
        Self::new(Arc::new(InMemoryEventPublisher::default()))
    }
}
impl InMemorySeatAssignmentService {
    pub fn new(publisher: Arc<dyn EventPublisher>) -> Self {
        Self {
            state: Mutex::new(InMemoryState::default()),
            publisher,
        }
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
        if self
            .state
            .lock()
            .expect("seat state")
            .processed_events
            .contains(&envelope.event_id)
        {
            return Ok(());
        }
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
    async fn confirm_from_entitlement(
        &self,
        e: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), SeatAssignmentError> {
        let allocation = string_field(&e.payload, "seatAllocationId").or_else(|| {
            e.payload
                .get("seatRef")
                .and_then(|v| string_field(v, "seatAllocationId"))
        });
        let Some(id) = allocation else { return Ok(()) };
        let entitlement = string_field(&e.payload, "entitlementId").unwrap_or_default();
        let event_id = e.event_id.clone();
        let pending = {
            let mut s = self.state.lock().unwrap();
            let Some(a) = s.allocations.get_mut(&id) else {
                s.processed_events.insert(event_id);
                return Ok(());
            };
            let ev = a.confirm(entitlement, e.event_id.clone(), e.occurred_at.clone())?;
            let pending = vec![PendingEnvelope::new(
                &a.seat_allocation_id,
                a.version,
                ev,
                e.correlation_id,
                Some(e.event_id),
            )];
            s.processed_events.insert(event_id);
            pending
        };
        publish_events(self.publisher.as_ref(), pending).await
    }
    async fn release_from_entitlement(
        &self,
        e: rust_kit::messaging::EventEnvelope,
        reason: ReleaseReason,
    ) -> Result<(), SeatAssignmentError> {
        let allocation = string_field(&e.payload, "seatAllocationId").or_else(|| {
            e.payload
                .get("seatRef")
                .and_then(|v| string_field(v, "seatAllocationId"))
        });
        let segment = string_field(&e.payload, "segmentBookingId");
        let event_id = e.event_id.clone();
        let pending = {
            let mut s = self.state.lock().unwrap();
            let id = allocation.or_else(|| {
                segment.and_then(|sb| {
                    s.allocations
                        .values()
                        .find(|a| a.segment_booking_id == sb)
                        .map(|a| a.seat_allocation_id.clone())
                })
            });
            let Some(id) = id else {
                s.processed_events.insert(event_id);
                return Ok(());
            };
            let Some(a) = s.allocations.get_mut(&id) else {
                s.processed_events.insert(event_id);
                return Ok(());
            };
            let pending = match a.release(reason, Some(e.event_id.clone()), e.occurred_at.clone())? {
                Some(ev) => vec![PendingEnvelope::new(
                    &a.seat_allocation_id,
                    a.version,
                    ev,
                    e.correlation_id,
                    Some(e.event_id),
                )],
                None => vec![],
            };
            s.processed_events.insert(event_id);
            pending
        };
        publish_events(self.publisher.as_ref(), pending).await
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
        let event_id = e.event_id.clone();
        let pending = {
            let mut s = self.state.lock().unwrap();
            let mut out = Vec::new();
            for a in s
                .allocations
                .values_mut()
                .filter(|a| a.capacity_hold_id == hold)
            {
                let ev = if expire {
                    a.expire(e.event_id.clone(), e.occurred_at.clone())?
                } else {
                    a.release(reason, Some(e.event_id.clone()), e.occurred_at.clone())?
                };
                if let Some(ev) = ev {
                    out.push(PendingEnvelope::new(
                        &a.seat_allocation_id,
                        a.version,
                        ev,
                        e.correlation_id.clone(),
                        Some(e.event_id.clone()),
                    ))
                }
            }
            s.processed_events.insert(event_id);
            out
        };
        publish_events(self.publisher.as_ref(), pending).await
    }
}

#[derive(Default)]
struct InMemoryState {
    maps: HashMap<String, SeatMap>,
    allocations: HashMap<String, SeatAllocation>,
    idempotency: HashMap<String, IdempotentRecord>,
    processed_events: HashSet<String>,
}
#[derive(Clone)]
struct IdempotentRecord {
    op: &'static str,
    fingerprint: String,
    response: IdempotentResponse,
}
#[derive(Clone)]
enum IdempotentResponse {
    Map(SeatMap),
    Allocation(AllocateSeatResponse),
}

#[async_trait]
impl SeatAssignmentApi for InMemorySeatAssignmentService {
    async fn create_seat_map(
        &self,
        cmd: CreateSeatMapCommand,
        key: String,
        corr: String,
    ) -> Result<SeatMap, SeatAssignmentError> {
        validate_uuid_v7_key(&key)?;
        let fp = normalize_json(&cmd);
        let (map, events) = {
            let mut s = self.state.lock().unwrap();
            if let Some(r) = s.idempotency.get(&key) {
                if r.fp_ne("create_map", &fp) {
                    return Err(SeatAssignmentError::IdempotencyKeyReused(
                        "Idempotency-Key was reused with a different request body".into(),
                    ));
                }
                if let IdempotentResponse::Map(m) = &r.response {
                    return Ok(m.clone());
                }
            }
            let (map, events) = SeatMap::build(cmd, current_rfc3339())?;
            s.idempotency.insert(
                key,
                IdempotentRecord {
                    op: "create_map",
                    fingerprint: fp,
                    response: IdempotentResponse::Map(map.clone()),
                },
            );
            s.maps.insert(map.seat_map_id.clone(), map.clone());
            (map, events)
        };
        publish_events(
            self.publisher.as_ref(),
            events
                .into_iter()
                .enumerate()
                .map(|(i, e)| {
                    PendingEnvelope::new(
                        &map.seat_map_id,
                        map.seat_map_version + i as i64,
                        e,
                        corr.clone(),
                        None,
                    )
                })
                .collect(),
        )
        .await?;
        Ok(map)
    }
    async fn publish_seat_map(
        &self,
        id: String,
        cmd: PublishSeatMapCommand,
        key: String,
        corr: String,
    ) -> Result<SeatMap, SeatAssignmentError> {
        self.mutate_map(
            id,
            cmd.expected_seat_map_version,
            key,
            corr,
            "publish_map",
            normalize_json(&cmd),
            |m, now| m.publish(cmd.expected_seat_map_version, cmd.operator_ref, now),
        )
        .await
    }
    async fn retire_seat_map(
        &self,
        id: String,
        cmd: RetireSeatMapCommand,
        key: String,
        corr: String,
    ) -> Result<SeatMap, SeatAssignmentError> {
        self.mutate_map(
            id,
            cmd.expected_seat_map_version,
            key,
            corr,
            "retire_map",
            normalize_json(&cmd),
            |m, now| {
                m.retire(
                    cmd.expected_seat_map_version,
                    cmd.retire_reason,
                    cmd.operator_ref,
                    now,
                )
            },
        )
        .await
    }
    async fn mark_unavailable(
        &self,
        id: String,
        su: String,
        cmd: MarkUnavailableCommand,
        key: String,
        corr: String,
    ) -> Result<SeatMap, SeatAssignmentError> {
        self.mutate_map(
            id.clone(),
            cmd.expected_seat_map_version,
            key,
            corr,
            "mark_unavailable",
            format!("{su}:{}", normalize_json(&cmd)),
            |m, now| {
                m.mark_unavailable(
                    &su,
                    cmd.expected_seat_map_version,
                    cmd.unavailable_reason,
                    cmd.operator_ref,
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
        cmd: ReopenSeatUnitCommand,
        key: String,
        corr: String,
    ) -> Result<SeatMap, SeatAssignmentError> {
        self.mutate_map(
            id.clone(),
            cmd.expected_seat_map_version,
            key,
            corr,
            "reopen",
            format!("{su}:{}", normalize_json(&cmd)),
            |m, now| {
                m.reopen(
                    &su,
                    cmd.expected_seat_map_version,
                    cmd.reopen_reason,
                    cmd.operator_ref,
                    now,
                )
            },
        )
        .await
    }
    async fn get_seat_map(&self, id: String) -> Result<SeatMap, SeatAssignmentError> {
        self.state
            .lock()
            .unwrap()
            .maps
            .get(&id)
            .cloned()
            .ok_or_else(|| SeatAssignmentError::NotFound("SeatMap not found".into()))
    }
    async fn list_seat_maps(
        &self,
        scheduled: String,
        date: String,
        status: Option<SeatMapStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<PaginatedSeatMaps, SeatAssignmentError> {
        let mut items: Vec<_> = self
            .state
            .lock()
            .unwrap()
            .maps
            .values()
            .filter(|m| {
                m.scheduled_service_ref == scheduled
                    && m.service_date == date
                    && status.is_none_or(|s| m.status == s)
            })
            .cloned()
            .collect();
        items.sort_by(|a, b| a.seat_map_id.cmp(&b.seat_map_id));
        let total = items.len();
        Ok(PaginatedSeatMaps {
            items: items.into_iter().skip(offset).take(limit).collect(),
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
        let (response, pending) = {
            let mut s = self.state.lock().unwrap();
            if let Some(r) = s.idempotency.get(&key) {
                if r.fp_ne("allocate", &fp) {
                    return Err(SeatAssignmentError::IdempotencyKeyReused(
                        "Idempotency-Key was reused with a different request body".into(),
                    ));
                }
                if let IdempotentResponse::Allocation(a) = &r.response {
                    return Ok(a.clone());
                }
            }
            let map = s
                .maps
                .values()
                .filter(|m| {
                    m.scheduled_service_ref == cmd.scheduled_service_ref
                        && m.service_date == cmd.service_date
                        && m.status == SeatMapStatus::Published
                })
                .max_by_key(|m| m.seat_map_version)
                .cloned()
                .ok_or_else(|| {
                    SeatAssignmentError::PreconditionFailed(
                        "no published SeatMap matches the requested scheduled service/date".into(),
                    )
                })?;
            let occupied: Vec<_> = s.allocations.values().cloned().collect();
            let (allocation, events) =
                SeatAllocation::allocate(cmd, &map, &occupied, current_rfc3339())?;
            let response = AllocateSeatResponse::from(&allocation);
            let pending = events
                .into_iter()
                .enumerate()
                .map(|(i, e)| {
                    PendingEnvelope::new(
                        &allocation.seat_allocation_id,
                        allocation.version + i as i64,
                        e,
                        corr.clone(),
                        Some(command_id_from_key(&key)),
                    )
                })
                .collect();
            s.idempotency.insert(
                key,
                IdempotentRecord {
                    op: "allocate",
                    fingerprint: fp,
                    response: IdempotentResponse::Allocation(response.clone()),
                },
            );
            s.allocations
                .insert(allocation.seat_allocation_id.clone(), allocation);
            (response, pending)
        };
        publish_events(self.publisher.as_ref(), pending).await?;
        Ok(response)
    }
    async fn get_allocation(&self, id: String) -> Result<SeatAllocation, SeatAssignmentError> {
        self.state
            .lock()
            .unwrap()
            .allocations
            .get(&id)
            .cloned()
            .ok_or_else(|| SeatAssignmentError::NotFound("SeatAllocation not found".into()))
    }
    async fn list_allocations(
        &self,
        sb: String,
        status: Option<AllocationStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<PaginatedSeatAllocations, SeatAssignmentError> {
        let mut items: Vec<_> = self
            .state
            .lock()
            .unwrap()
            .allocations
            .values()
            .filter(|a| a.segment_booking_id == sb && status.is_none_or(|s| a.status == s))
            .cloned()
            .collect();
        items.sort_by(|a, b| a.seat_allocation_id.cmp(&b.seat_allocation_id));
        let total = items.len();
        Ok(PaginatedSeatAllocations {
            items: items.into_iter().skip(offset).take(limit).collect(),
            total,
            limit,
            offset,
        })
    }
}
impl IdempotentRecord {
    fn fp_ne(&self, op: &str, fp: &str) -> bool {
        self.operation_mismatch(op) || self.fingerprint != fp
    }
    fn operation_mismatch(&self, op: &str) -> bool {
        self.op != op
    }
}
impl InMemorySeatAssignmentService {
    async fn mutate_map<F>(
        &self,
        id: String,
        _expected: i64,
        key: String,
        corr: String,
        op: &'static str,
        fp: String,
        fun: F,
    ) -> Result<SeatMap, SeatAssignmentError>
    where
        F: FnOnce(&mut SeatMap, String) -> Result<SeatAssignmentEvent, SeatAssignmentError>,
    {
        validate_uuid_v7_key(&key)?;
        let (map, event) = {
            let mut s = self.state.lock().unwrap();
            if let Some(r) = s.idempotency.get(&key) {
                if r.fp_ne(op, &fp) {
                    return Err(SeatAssignmentError::IdempotencyKeyReused(
                        "Idempotency-Key was reused with a different request body".into(),
                    ));
                }
                if let IdempotentResponse::Map(m) = &r.response {
                    return Ok(m.clone());
                }
            }
            let map = s
                .maps
                .get_mut(&id)
                .ok_or_else(|| SeatAssignmentError::NotFound("SeatMap not found".into()))?;
            let event = fun(map, current_rfc3339())?;
            let map = map.clone();
            s.idempotency.insert(
                key,
                IdempotentRecord {
                    op,
                    fingerprint: fp,
                    response: IdempotentResponse::Map(map.clone()),
                },
            );
            (map, event)
        };
        publish_events(
            self.publisher.as_ref(),
            vec![PendingEnvelope::new(
                &map.seat_map_id,
                map.seat_map_version,
                event,
                corr,
                None,
            )],
        )
        .await?;
        Ok(map)
    }
}

#[async_trait]
pub trait EventPublisher: Send + Sync {
    async fn publish(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), PublishFailed>;
}
#[derive(Debug, Clone)]
pub struct PublishFailed(pub String);
impl fmt::Display for PublishFailed {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}
impl std::error::Error for PublishFailed {}
#[derive(Default)]
pub struct InMemoryEventPublisher {
    published: Mutex<Vec<rust_kit::messaging::EventEnvelope>>,
}
impl InMemoryEventPublisher {
    pub fn published(&self) -> Vec<rust_kit::messaging::EventEnvelope> {
        self.published.lock().unwrap().clone()
    }
}
#[async_trait]
impl EventPublisher for InMemoryEventPublisher {
    async fn publish(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), PublishFailed> {
        self.published.lock().unwrap().push(envelope);
        Ok(())
    }
}
#[async_trait]
impl EventPublisher for rust_kit::messaging::redis_runtime::RedisEventPublisher {
    async fn publish(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), PublishFailed> {
        rust_kit::messaging::AsyncEventPublisher::publish(self, envelope)
            .await
            .map_err(|e| PublishFailed(e.to_string()))
    }
}
#[derive(Debug, Clone)]
struct PendingEnvelope {
    id: String,
    version: i64,
    event: SeatAssignmentEvent,
    correlation_id: String,
    causation_id: Option<String>,
}
impl PendingEnvelope {
    fn new(
        id: &str,
        version: i64,
        event: SeatAssignmentEvent,
        correlation_id: String,
        causation_id: Option<String>,
    ) -> Self {
        Self {
            id: id.into(),
            version,
            event,
            correlation_id,
            causation_id,
        }
    }
}
async fn publish_events(
    publisher: &dyn EventPublisher,
    events: Vec<PendingEnvelope>,
) -> Result<(), SeatAssignmentError> {
    for event in events {
        if let Some(env) = event.event.envelope(
            &event.id,
            event.version,
            event.correlation_id,
            event.causation_id,
        ) {
            publisher
                .publish(env)
                .await
                .map_err(|e| SeatAssignmentError::Unavailable(e.to_string()))?;
        }
    }
    Ok(())
}
