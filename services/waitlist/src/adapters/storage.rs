use crate::utils::*;
use crate::*;
use async_trait::async_trait;
use serde_json::Value;
use std::sync::Arc;

pub struct PostgresWaitlistService {
    storage: rust_kit::storage::Storage,
    fulfillment_client: Arc<dyn FulfillmentClient>,
}
impl PostgresWaitlistService {
    pub async fn from_env() -> Result<Self, WaitlistError> {
        let storage = rust_kit::storage::Storage::from_env()
            .await
            .map_err(storage_error)?;
        storage
            .migrate_dir("services/waitlist/migrations")
            .await
            .map_err(storage_error)?;
        Ok(Self {
            storage,
            fulfillment_client: Arc::new(ReqwestFulfillmentClient::from_env()),
        })
    }
    pub fn pool(&self) -> &sqlx::PgPool {
        self.storage.pool()
    }
    pub async fn is_ready(&self) -> bool {
        self.storage.is_ready().await
    }
    async fn load_request_for_update(
        tx: &mut rust_kit::storage::PgTransaction<'_>,
        id: &str,
    ) -> Result<Option<WaitlistRequest>, WaitlistError> {
        let row: Option<(Value,)> = sqlx::query_as(
            "SELECT data FROM waitlist_requests WHERE waitlist_request_id = $1 FOR UPDATE",
        )
        .bind(id)
        .fetch_optional(&mut **tx)
        .await
        .map_err(db_error)?;
        row.map(|(value,)| {
            serde_json::from_value(value)
                .map_err(|error| WaitlistError::Internal(error.to_string()))
        })
        .transpose()
    }
    async fn save_request(
        tx: &mut rust_kit::storage::PgTransaction<'_>,
        request: &WaitlistRequest,
    ) -> Result<(), WaitlistError> {
        let data = serde_json::to_value(request)
            .map_err(|error| WaitlistError::Internal(error.to_string()))?;
        sqlx::query("INSERT INTO waitlist_requests (waitlist_request_id, account_id, traveler_ref, intent_fingerprint, segment_ref, status, deadline, queued_at, order_ref, version, data) VALUES ($1,$2,$3,$4,$5,$6,$7::timestamptz,$8::timestamptz,$9,$10,$11) ON CONFLICT (waitlist_request_id) DO UPDATE SET account_id=EXCLUDED.account_id,traveler_ref=EXCLUDED.traveler_ref,intent_fingerprint=EXCLUDED.intent_fingerprint,segment_ref=EXCLUDED.segment_ref,status=EXCLUDED.status,deadline=EXCLUDED.deadline,queued_at=EXCLUDED.queued_at,order_ref=EXCLUDED.order_ref,version=EXCLUDED.version,data=EXCLUDED.data,updated_at=now()")
            .bind(&request.waitlist_request_id).bind(&request.account_id).bind(&request.traveler_ref).bind(&request.intent_fingerprint).bind(&request.segment_ref).bind(request.status.as_contract()).bind(&request.deadline).bind(&request.queued_at).bind(&request.order_ref).bind(request.version).bind(data).execute(&mut **tx).await.map_err(db_error)?;
        Ok(())
    }
    async fn append_events(
        tx: &mut rust_kit::storage::PgTransaction<'_>,
        id: &str,
        version: i64,
        events: Vec<WaitlistEvent>,
        correlation_id: String,
        causation_id: Option<String>,
    ) -> Result<(), WaitlistError> {
        for (index, event) in events.into_iter().enumerate() {
            let envelope = event.envelope(
                id,
                version + index as i64,
                correlation_id.clone(),
                causation_id.clone(),
            );
            rust_kit::storage::OutboxAppender::append(
                tx,
                &rust_kit::messaging::stream_for_producer(profile().service_id),
                &envelope,
            )
            .await
            .map_err(storage_error)?;
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
    ) -> Result<(), WaitlistError> {
        match envelope.event_type.as_str() {
            "CapacityReleased" => self.handle_capacity_released(envelope).await,
            "JourneyOrderConfirmed" => self.handle_order_terminal(envelope, true).await,
            "JourneyOrderCancelled" => self.handle_order_terminal(envelope, false).await,
            _ => Ok(()),
        }
    }
    pub async fn expire_due(
        &self,
        now: String,
        correlation_id: String,
    ) -> Result<usize, WaitlistError> {
        let mut tx = self.pool().begin().await.map_err(db_error)?;
        let rows: Vec<(String,)> = sqlx::query_as("SELECT waitlist_request_id FROM waitlist_requests WHERE status IN ('QUEUED','SUSPENDED') AND deadline <= $1::timestamptz FOR UPDATE SKIP LOCKED").bind(&now).fetch_all(&mut *tx).await.map_err(db_error)?;
        let mut count = 0;
        for (id,) in rows {
            if let Some(mut request) = Self::load_request_for_update(&mut tx, &id).await? {
                let version = request.version;
                let events = request.expire_and_close(now.clone())?;
                Self::save_request(&mut tx, &request).await?;
                Self::append_events(
                    &mut tx,
                    &request.waitlist_request_id,
                    version + 1,
                    events,
                    correlation_id.clone(),
                    None,
                )
                .await?;
                count += 1;
            }
        }
        tx.commit().await.map_err(db_error)?;
        Ok(count)
    }
    async fn handle_capacity_released(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), WaitlistError> {
        let segment = segment_from_payload(&envelope.payload).ok_or_else(|| {
            WaitlistError::ValidationFailed("CapacityReleased missing segmentRef".into())
        })?;
        let mut tx = self.pool().begin().await.map_err(db_error)?;
        if !rust_kit::storage::mark_event_processing(
            &mut tx,
            &envelope.event_id,
            &crate::adapters::messaging::capacity_availability_stream(),
        )
        .await
        .map_err(storage_error)?
        {
            tx.commit().await.map_err(db_error)?;
            return Ok(());
        }
        let row: Option<(String,)> = sqlx::query_as("SELECT waitlist_request_id FROM waitlist_requests WHERE segment_ref=$1 AND status='QUEUED' ORDER BY queued_at ASC NULLS LAST, created_at ASC, waitlist_request_id ASC LIMIT 1 FOR UPDATE SKIP LOCKED").bind(&segment).fetch_optional(&mut *tx).await.map_err(db_error)?;
        let Some((id,)) = row else {
            tx.commit().await.map_err(db_error)?;
            return Ok(());
        };
        let mut request = Self::load_request_for_update(&mut tx, &id)
            .await?
            .ok_or_else(|| WaitlistError::NotFound("waitlist request not found".into()))?;
        let version = request.version;
        let event =
            request.start_matching(envelope.event_id.clone(), envelope.occurred_at.clone())?;

        let key = journey_order_idempotency_key(&request.waitlist_request_id);
        match self
            .fulfillment_client
            .fulfill(&request, &key, &envelope.correlation_id)
            .await
        {
            Ok(order) => {
                request.record_order_ref(order);
                Self::save_request(&mut tx, &request).await?;
                Self::append_events(
                    &mut tx,
                    &request.waitlist_request_id,
                    version + 1,
                    vec![event],
                    envelope.correlation_id.clone(),
                    Some(envelope.event_id.clone()),
                )
                .await?;
                tx.commit().await.map_err(db_error)?;
                Ok(())
            }
            Err(FulfillmentClientError::Transient(message)) => {
                Err(WaitlistError::Unavailable(message))
            }
            Err(FulfillmentClientError::Rejected(message)) => {
                log::warn!(
                    "waitlist fulfillment chain rejected requestId={}: {message}",
                    request.waitlist_request_id
                );
                let queued =
                    request.requeue_after_fulfillment_rejected(message, current_rfc3339())?;
                Self::save_request(&mut tx, &request).await?;
                Self::append_events(
                    &mut tx,
                    &request.waitlist_request_id,
                    version + 2,
                    vec![queued],
                    envelope.correlation_id,
                    Some(envelope.event_id),
                )
                .await?;
                tx.commit().await.map_err(db_error)?;
                Ok(())
            }
        }
    }
    async fn handle_order_terminal(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
        confirmed: bool,
    ) -> Result<(), WaitlistError> {
        let Some(order) = order_ref_from_payload(&envelope.payload) else {
            return Ok(());
        };
        let mut tx = self.pool().begin().await.map_err(db_error)?;
        if !rust_kit::storage::mark_event_processing(
            &mut tx,
            &envelope.event_id,
            &crate::adapters::messaging::journey_order_stream(),
        )
        .await
        .map_err(storage_error)?
        {
            tx.commit().await.map_err(db_error)?;
            return Ok(());
        }
        let row: Option<(String,)> = sqlx::query_as("SELECT waitlist_request_id FROM waitlist_requests WHERE order_ref=$1 LIMIT 1 FOR UPDATE").bind(&order).fetch_optional(&mut *tx).await.map_err(db_error)?;
        let Some((id,)) = row else {
            tx.commit().await.map_err(db_error)?;
            return Ok(());
        };
        let mut request = Self::load_request_for_update(&mut tx, &id)
            .await?
            .ok_or_else(|| WaitlistError::NotFound("waitlist request not found".into()))?;
        let version = request.version;
        let events = if confirmed {
            request.fulfill_and_close(order, envelope.occurred_at.clone())?
        } else {
            vec![request.requeue_after_order_cancelled(envelope.occurred_at.clone())?]
        };
        Self::save_request(&mut tx, &request).await?;
        Self::append_events(
            &mut tx,
            &request.waitlist_request_id,
            version + 1,
            events,
            envelope.correlation_id,
            Some(envelope.event_id),
        )
        .await?;
        tx.commit().await.map_err(db_error)?;
        Ok(())
    }
}

#[async_trait]
impl WaitlistApi for PostgresWaitlistService {
    async fn create(
        &self,
        command: CreateWaitlistCommand,
        key: String,
        correlation_id: String,
    ) -> Result<WaitlistResource, WaitlistError> {
        let fingerprint = serde_json::to_string(&command).unwrap_or_default();
        let mut tx = self.pool().begin().await.map_err(db_error)?;
        if let rust_kit::storage::IdempotencyTxDecision::Replay(record) =
            rust_kit::storage::DbIdempotencyStore::claim_response(&mut tx, &key, &fingerprint)
                .await
                .map_err(storage_error)?
        {
            tx.commit().await.map_err(db_error)?;
            return serde_json::from_value(record.body)
                .map_err(|error| WaitlistError::Internal(error.to_string()));
        }
        let (request, events) = WaitlistRequest::create(command, current_rfc3339())?;
        let response = WaitlistResource::from(&request);
        Self::save_request(&mut tx, &request).await?;
        Self::append_events(
            &mut tx,
            &request.waitlist_request_id,
            1,
            events,
            correlation_id,
            None,
        )
        .await?;
        rust_kit::storage::DbIdempotencyStore::record_response(
            &mut tx,
            &key,
            &fingerprint,
            201,
            serde_json::to_value(&response).unwrap(),
        )
        .await
        .map_err(storage_error)?;
        tx.commit().await.map_err(db_error)?;
        Ok(response)
    }
    async fn get(&self, id: String) -> Result<WaitlistResource, WaitlistError> {
        validate_prefixed_uuid(&id, "waitlistRequestId", "wlr-")?;
        let row: Option<(Value,)> =
            sqlx::query_as("SELECT data FROM waitlist_requests WHERE waitlist_request_id=$1")
                .bind(&id)
                .fetch_optional(self.pool())
                .await
                .map_err(db_error)?;
        let request: WaitlistRequest = serde_json::from_value(
            row.ok_or_else(|| WaitlistError::NotFound("waitlist request not found".into()))?
                .0,
        )
        .map_err(|error| WaitlistError::Internal(error.to_string()))?;
        Ok(WaitlistResource::from(&request))
    }
    async fn cancel(
        &self,
        id: String,
        command: CancelWaitlistCommand,
        key: String,
        correlation_id: String,
    ) -> Result<CancelWaitlistResponse, WaitlistError> {
        validate_prefixed_uuid(&id, "waitlistRequestId", "wlr-")?;
        let fingerprint = format!(
            "{}:{}",
            id,
            serde_json::to_string(&command).unwrap_or_default()
        );
        let mut tx = self.pool().begin().await.map_err(db_error)?;
        if let rust_kit::storage::IdempotencyTxDecision::Replay(record) =
            rust_kit::storage::DbIdempotencyStore::claim_response(&mut tx, &key, &fingerprint)
                .await
                .map_err(storage_error)?
        {
            tx.commit().await.map_err(db_error)?;
            return serde_json::from_value(record.body)
                .map_err(|error| WaitlistError::Internal(error.to_string()));
        }
        let mut request = Self::load_request_for_update(&mut tx, &id)
            .await?
            .ok_or_else(|| WaitlistError::NotFound("waitlist request not found".into()))?;
        let version = request.version;
        let cancelled_at = current_rfc3339();
        let event = request.cancel(command.reason, cancelled_at.clone())?;
        let response = CancelWaitlistResponse {
            waitlist_request_id: id.clone(),
            status: WaitlistStatus::Cancelled,
            cancelled_at,
        };
        Self::save_request(&mut tx, &request).await?;
        Self::append_events(&mut tx, &id, version + 1, vec![event], correlation_id, None).await?;
        rust_kit::storage::DbIdempotencyStore::record_response(
            &mut tx,
            &key,
            &fingerprint,
            200,
            serde_json::to_value(&response).unwrap(),
        )
        .await
        .map_err(storage_error)?;
        tx.commit().await.map_err(db_error)?;
        Ok(response)
    }
    async fn list(
        &self,
        traveler: String,
        status: Option<WaitlistStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<PaginatedWaitlistRequests, WaitlistError> {
        validate_prefixed_uuid(&traveler, "travelerRef", "tvl-")?;
        let status_value = status.map(|status| status.as_contract().to_string());
        let rows: Vec<(Value, i64)> = sqlx::query_as("SELECT data, count(*) OVER() AS total FROM waitlist_requests WHERE traveler_ref=$1 AND ($2::text IS NULL OR status=$2) ORDER BY created_at DESC, waitlist_request_id ASC LIMIT $3 OFFSET $4").bind(&traveler).bind(&status_value).bind(limit as i64).bind(offset as i64).fetch_all(self.pool()).await.map_err(db_error)?;
        let total = rows.first().map(|(_, total)| *total as usize).unwrap_or(0);
        let items = rows
            .into_iter()
            .map(|(value, _)| {
                serde_json::from_value::<WaitlistRequest>(value)
                    .map(|request| WaitlistResource::from(&request))
                    .map_err(|error| WaitlistError::Internal(error.to_string()))
            })
            .collect::<Result<Vec<_>, _>>()?;
        Ok(PaginatedWaitlistRequests {
            items,
            total,
            limit,
            offset,
        })
    }
}
