use crate::utils::*;
use crate::*;
use async_trait::async_trait;
use opentelemetry::Context as OtelContext;
use opentelemetry::propagation::{Injector, TextMapPropagator};
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
use std::fmt;
use std::sync::{Arc, Mutex};

pub struct InMemoryWaitlistService {
    pub(crate) state: Mutex<InMemoryState>,
    publisher: Arc<dyn EventPublisher>,
    fulfillment_client: Arc<dyn FulfillmentClient>,
}

impl Default for InMemoryWaitlistService {
    fn default() -> Self {
        Self::new(
            Arc::new(InMemoryEventPublisher::default()),
            Arc::new(NoopFulfillmentClient),
        )
    }
}
impl InMemoryWaitlistService {
    pub fn new(
        publisher: Arc<dyn EventPublisher>,
        fulfillment_client: Arc<dyn FulfillmentClient>,
    ) -> Self {
        Self {
            state: Mutex::new(InMemoryState::default()),
            publisher,
            fulfillment_client,
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
    ) -> Result<(), WaitlistError> {
        {
            let mut state = self.state.lock().expect("waitlist state lock poisoned");
            if !state.processed_events.insert(envelope.event_id.clone()) {
                return Ok(());
            }
        }
        match envelope.event_type.as_str() {
            "CapacityReleased" => self.handle_capacity_released(envelope).await,
            "JourneyOrderConfirmed" => self.handle_journey_order_confirmed(envelope).await,
            "JourneyOrderCancelled" => self.handle_journey_order_cancelled(envelope).await,
            _ => Ok(()),
        }
    }

    async fn handle_capacity_released(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), WaitlistError> {
        let segment = segment_from_payload(&envelope.payload).ok_or_else(|| {
            WaitlistError::ValidationFailed("CapacityReleased missing segmentRef".into())
        })?;
        let (request, event, key) = {
            let mut state = self.state.lock().expect("waitlist state lock poisoned");
            let queue = WaitlistQueue::rebuild(state.requests.values().cloned());
            let Some(id) = queue.head_for_segment(&segment).map(str::to_string) else {
                return Ok(());
            };
            let request = state.requests.get_mut(&id).expect("queue head exists");
            let event =
                request.start_matching(envelope.event_id.clone(), envelope.occurred_at.clone())?;
            let key = request
                .fulfillment_idempotency_keys
                .as_ref()
                .expect("matching creates fulfillment idempotency keys")
                .order
                .clone();
            (request.clone(), event, key)
        };
        publish_events(
            self.publisher.as_ref(),
            vec![PendingEnvelope::new(
                &request.waitlist_request_id,
                request.version,
                event,
                envelope.correlation_id.clone(),
                Some(envelope.event_id.clone()),
            )],
        )
        .await?;
        match self
            .fulfillment_client
            .fulfill(&request, &key, &envelope.correlation_id)
            .await
        {
            Ok(order) => {
                if let Some(stored) = self
                    .state
                    .lock()
                    .expect("waitlist state lock poisoned")
                    .requests
                    .get_mut(&request.waitlist_request_id)
                {
                    stored.record_order_ref(order);
                }
                Ok(())
            }
            Err(
                FulfillmentClientError::Transient(message)
                | FulfillmentClientError::ProjectionLag(message),
            ) => {
                Err(WaitlistError::Unavailable(message))
            }
            Err(FulfillmentClientError::Rejected(message)) => {
                log::warn!(
                    "waitlist fulfillment chain rejected requestId={}: {message}",
                    request.waitlist_request_id
                );
                let queued = {
                    let mut state = self.state.lock().expect("waitlist state lock poisoned");
                    state
                        .requests
                        .get_mut(&request.waitlist_request_id)
                        .expect("request exists")
                        .requeue_after_fulfillment_rejected(message, current_rfc3339())?
                };
                publish_events(
                    self.publisher.as_ref(),
                    vec![PendingEnvelope::new(
                        &request.waitlist_request_id,
                        request.version + 1,
                        queued,
                        envelope.correlation_id,
                        Some(envelope.event_id),
                    )],
                )
                .await
            }
        }
    }

    async fn handle_journey_order_confirmed(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), WaitlistError> {
        let Some(order) = order_ref_from_payload(&envelope.payload) else {
            return Ok(());
        };
        let pending = {
            let mut state = self.state.lock().expect("waitlist state lock poisoned");
            let Some(request) = state
                .requests
                .values_mut()
                .find(|request| request.order_ref.as_deref() == Some(order.as_str()))
            else {
                return Ok(());
            };
            let events = request.fulfill_and_close(order, envelope.occurred_at.clone())?;
            events
                .into_iter()
                .map(|event| {
                    PendingEnvelope::new(
                        &request.waitlist_request_id,
                        request.version,
                        event,
                        envelope.correlation_id.clone(),
                        Some(envelope.event_id.clone()),
                    )
                })
                .collect()
        };
        publish_events(self.publisher.as_ref(), pending).await
    }

    async fn handle_journey_order_cancelled(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), WaitlistError> {
        let Some(order) = order_ref_from_payload(&envelope.payload) else {
            return Ok(());
        };
        let pending = {
            let mut state = self.state.lock().expect("waitlist state lock poisoned");
            let Some(request) = state
                .requests
                .values_mut()
                .find(|request| request.order_ref.as_deref() == Some(order.as_str()))
            else {
                return Ok(());
            };
            let event = request.requeue_after_order_cancelled(envelope.occurred_at.clone())?;
            vec![PendingEnvelope::new(
                &request.waitlist_request_id,
                request.version,
                event,
                envelope.correlation_id.clone(),
                Some(envelope.event_id.clone()),
            )]
        };
        publish_events(self.publisher.as_ref(), pending).await
    }

    pub async fn expire_due(
        &self,
        now: String,
        correlation_id: String,
    ) -> Result<usize, WaitlistError> {
        let pending = {
            let mut state = self.state.lock().expect("waitlist state lock poisoned");
            let mut pending = Vec::new();
            for request in state.requests.values_mut() {
                if matches!(
                    request.status,
                    WaitlistStatus::Queued | WaitlistStatus::Suspended
                ) && !deadline_after(&request.deadline, &now)
                {
                    let events = request.expire_and_close(now.clone())?;
                    pending.extend(events.into_iter().map(|event| {
                        PendingEnvelope::new(
                            &request.waitlist_request_id,
                            request.version,
                            event,
                            correlation_id.clone(),
                            None,
                        )
                    }));
                }
            }
            pending
        };
        let count = pending.len();
        publish_events(self.publisher.as_ref(), pending).await?;
        Ok(count)
    }
}

#[derive(Default)]
pub(crate) struct InMemoryState {
    pub(crate) requests: HashMap<String, WaitlistRequest>,
    active_index: HashMap<(String, String), String>,
    idempotency: HashMap<String, IdempotentRecord>,
    processed_events: HashSet<String>,
}

#[derive(Clone)]
struct IdempotentRecord {
    operation: &'static str,
    fingerprint: String,
    response: IdempotentResponse,
}
#[derive(Clone)]
enum IdempotentResponse {
    Create(WaitlistResource),
    Cancel(CancelWaitlistResponse),
}

#[async_trait]
impl WaitlistApi for InMemoryWaitlistService {
    async fn create(
        &self,
        command: CreateWaitlistCommand,
        key: String,
        correlation_id: String,
    ) -> Result<WaitlistResource, WaitlistError> {
        let fingerprint = serde_json::to_string(&command).unwrap_or_default();
        let (id, version, response, events) = {
            let mut state = self.state.lock().expect("waitlist state lock poisoned");
            if let Some(record) = state.idempotency.get(&key) {
                if record.fingerprint != fingerprint || record.operation != "create" {
                    return Err(WaitlistError::IdempotencyKeyReused(
                        "Idempotency-Key was reused with a different request body".into(),
                    ));
                }
                if let IdempotentResponse::Create(response) = &record.response {
                    return Ok(response.clone());
                }
            }
            let (request, events) = WaitlistRequest::create(command, current_rfc3339())?;
            let active_key = (
                request.traveler_ref.clone(),
                request.intent_fingerprint.clone(),
            );
            if let Some(existing) = state.active_index.get(&active_key) {
                if state
                    .requests
                    .get(existing)
                    .is_some_and(|request| request.status.is_active())
                {
                    return Err(WaitlistError::Conflict(
                        "traveler already has an active waitlist request for this intent".into(),
                    ));
                }
            }
            let response = WaitlistResource::from(&request);
            let id = request.waitlist_request_id.clone();
            let version = request.version;
            state.active_index.insert(active_key, id.clone());
            state.requests.insert(id.clone(), request);
            state.idempotency.insert(
                key,
                IdempotentRecord {
                    operation: "create",
                    fingerprint,
                    response: IdempotentResponse::Create(response.clone()),
                },
            );
            (id, version, response, events)
        };
        publish_events(
            self.publisher.as_ref(),
            events
                .into_iter()
                .enumerate()
                .map(|(index, event)| {
                    PendingEnvelope::new(
                        &id,
                        version + index as i64,
                        event,
                        correlation_id.clone(),
                        None,
                    )
                })
                .collect(),
        )
        .await?;
        Ok(response)
    }

    async fn get(&self, id: String) -> Result<WaitlistResource, WaitlistError> {
        validate_prefixed_uuid(&id, "waitlistRequestId", "wlr-")?;
        self.state
            .lock()
            .expect("waitlist state lock poisoned")
            .requests
            .get(&id)
            .map(WaitlistResource::from)
            .ok_or_else(|| WaitlistError::NotFound("waitlist request not found".into()))
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
        let (version, response, event) = {
            let mut state = self.state.lock().expect("waitlist state lock poisoned");
            if let Some(record) = state.idempotency.get(&key) {
                if record.fingerprint != fingerprint || record.operation != "cancel" {
                    return Err(WaitlistError::IdempotencyKeyReused(
                        "Idempotency-Key was reused with a different request body".into(),
                    ));
                }
                if let IdempotentResponse::Cancel(response) = &record.response {
                    return Ok(response.clone());
                }
            }
            let request = state
                .requests
                .get_mut(&id)
                .ok_or_else(|| WaitlistError::NotFound("waitlist request not found".into()))?;
            let cancelled_at = current_rfc3339();
            let event = request.cancel(command.reason, cancelled_at.clone())?;
            let response = CancelWaitlistResponse {
                waitlist_request_id: id.clone(),
                status: WaitlistStatus::Cancelled,
                cancelled_at,
            };
            let active_key = (
                request.traveler_ref.clone(),
                request.intent_fingerprint.clone(),
            );
            let version = request.version;
            state.active_index.remove(&active_key);
            state.idempotency.insert(
                key,
                IdempotentRecord {
                    operation: "cancel",
                    fingerprint,
                    response: IdempotentResponse::Cancel(response.clone()),
                },
            );
            (version, response, event)
        };
        publish_events(
            self.publisher.as_ref(),
            vec![PendingEnvelope::new(
                &id,
                version,
                event,
                correlation_id,
                None,
            )],
        )
        .await?;
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
        let mut items: Vec<_> = self
            .state
            .lock()
            .expect("waitlist state lock poisoned")
            .requests
            .values()
            .filter(|request| {
                request.traveler_ref == traveler
                    && status.is_none_or(|status| request.status == status)
            })
            .map(WaitlistResource::from)
            .collect();
        items.sort_by(|left, right| left.waitlist_request_id.cmp(&right.waitlist_request_id));
        let total = items.len();
        Ok(PaginatedWaitlistRequests {
            items: items.into_iter().skip(offset).take(limit).collect(),
            total,
            limit,
            offset,
        })
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
    fail_next: Mutex<Option<String>>,
}
impl InMemoryEventPublisher {
    pub fn published(&self) -> Vec<rust_kit::messaging::EventEnvelope> {
        self.published
            .lock()
            .expect("publisher lock poisoned")
            .clone()
    }
    pub fn fail_next(&self, message: impl Into<String>) {
        *self.fail_next.lock().expect("publisher lock poisoned") = Some(message.into());
    }
}
#[async_trait]
impl EventPublisher for InMemoryEventPublisher {
    async fn publish(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), PublishFailed> {
        if let Some(message) = self
            .fail_next
            .lock()
            .expect("publisher lock poisoned")
            .take()
        {
            return Err(PublishFailed(message));
        }
        self.published
            .lock()
            .expect("publisher lock poisoned")
            .push(envelope);
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
            .map_err(|error| PublishFailed(error.to_string()))
    }
}

#[derive(Debug, Clone)]
struct PendingEnvelope {
    id: String,
    version: i64,
    event: WaitlistEvent,
    correlation_id: String,
    causation_id: Option<String>,
}
impl PendingEnvelope {
    fn new(
        id: &str,
        version: i64,
        event: WaitlistEvent,
        correlation_id: String,
        causation_id: Option<String>,
    ) -> Self {
        Self {
            id: id.to_string(),
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
) -> Result<(), WaitlistError> {
    for event in events {
        publisher
            .publish(event.event.envelope(
                &event.id,
                event.version,
                event.correlation_id,
                event.causation_id,
            ))
            .await
            .map_err(|error| WaitlistError::Unavailable(error.to_string()))?;
    }
    Ok(())
}

#[async_trait]
pub trait FulfillmentClient: Send + Sync {
    async fn fulfill(
        &self,
        request: &WaitlistRequest,
        idempotency_key: &str,
        correlation_id: &str,
    ) -> Result<String, FulfillmentClientError>;
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FulfillmentClientError {
    Transient(String),
    /// A downstream 4xx caused by an un-consumed upstream snapshot; retried
    /// in-process with bounded backoff before escalating to Transient.
    ProjectionLag(String),
    Rejected(String),
}
#[derive(Default)]
pub struct NoopFulfillmentClient;
#[async_trait]
impl FulfillmentClient for NoopFulfillmentClient {
    async fn fulfill(
        &self,
        _: &WaitlistRequest,
        _: &str,
        _: &str,
    ) -> Result<String, FulfillmentClientError> {
        Ok(format!("ord-{}", uuid::Uuid::now_v7()))
    }
}

pub struct ReqwestFulfillmentClient {
    fare_pricing_base_url: String,
    offer_management_base_url: String,
    journey_order_base_url: String,
    payment_base_url: String,
    channel: String,
    product_code: String,
    client: reqwest::Client,
}
impl ReqwestFulfillmentClient {
    pub fn from_env() -> Self {
        Self {
            fare_pricing_base_url: std::env::var("FARE_PRICING_BASE_URL")
                .unwrap_or_else(|_| "http://fare-pricing:8080".into()),
            offer_management_base_url: std::env::var("OFFER_MANAGEMENT_BASE_URL")
                .unwrap_or_else(|_| "http://offer-management:8080".into()),
            journey_order_base_url: std::env::var("JOURNEY_ORDER_BASE_URL")
                .unwrap_or_else(|_| "http://journey-order:8080".into()),
            payment_base_url: std::env::var("PAYMENT_BASE_URL")
                .unwrap_or_else(|_| "http://payment:8080".into()),
            channel: std::env::var("WAITLIST_CHANNEL").unwrap_or_else(|_| "WEB".into()),
            product_code: std::env::var("WAITLIST_PRODUCT_CODE")
                .unwrap_or_else(|_| "rail-standard".into()),
            client: reqwest::Client::new(),
        }
    }
}
#[async_trait]
impl FulfillmentClient for ReqwestFulfillmentClient {
    async fn fulfill(
        &self,
        request: &WaitlistRequest,
        key: &str,
        _: &str,
    ) -> Result<String, FulfillmentClientError> {
        let keys = request
            .fulfillment_idempotency_keys
            .as_ref()
            .ok_or_else(|| {
                FulfillmentClientError::Transient("missing fulfillment idempotency keys".into())
            })?;
        let quote_id = self
            .post_for_string(
                &self.fare_pricing_base_url,
                "/api/v1/fare-quotes",
                &keys.quote,
                json!({
                    "travelerRefs": [request.traveler_ref.clone()],
                    "channel": self.channel,
                    "segmentRefs": [request.segment_ref.clone()],
                    "productCode": self.product_code,
                }),
                "quoteId",
                "fare-pricing",
            )
            .await?;
        let offer_body = self
            .post_for_json(
                &self.offer_management_base_url,
                "/api/v1/offers",
                &keys.offer,
                json!({
                    "accountId": request.account_id,
                    "channelId": self.channel,
                    "itineraryRef": request.itinerary_ref,
                    "travelerRefs": [request.traveler_ref.clone()],
                    "quoteRequestId": quote_id,
                }),
                "offer-management",
            )
            .await?;
        let offer_id = string_at(&offer_body, &["offerId"]).ok_or_else(|| {
            FulfillmentClientError::Transient("offer-management response missing offerId".into())
        })?;
        let order_id = self
            .post_for_string(
                &self.journey_order_base_url,
                "/api/v1/journey-orders",
                key,
                json!({
                    "accountId": request.account_id,
                    "offerId": offer_id,
                    "offerVersion": 1,
                    "travelerRefs": [request.traveler_ref.clone()],
                    "segmentRefs": [request.segment_ref.clone()],
                }),
                "orderId",
                "journey-order",
            )
            .await?;

        // The normal chain confirms only after payment: capture against the
        // waitlist payment guarantee on the customer's behalf.
        let payment_key = keys.payment.as_deref().ok_or_else(|| {
            FulfillmentClientError::Transient("missing persisted payment idempotency key".into())
        })?;
        let capture_key = keys.capture.as_deref().ok_or_else(|| {
            FulfillmentClientError::Transient("missing persisted capture idempotency key".into())
        })?;
        let amount = offer_body
            .get("total")
            .cloned()
            .filter(|value| value.get("minorUnits").is_some())
            .ok_or_else(|| {
                FulfillmentClientError::Transient(
                    "offer-management response missing total money".into(),
                )
            })?;
        let intent_id = self
            .post_for_string(
                &self.payment_base_url,
                "/api/v1/payment-intents",
                payment_key,
                json!({
                    "businessRef": order_id,
                    "purpose": "purchase",
                    "amount": amount,
                    "payerRef": request.account_id,
                }),
                "paymentIntentId",
                "payment",
            )
            .await?;
        self.post_for_json(
            &self.payment_base_url,
            &format!("/api/v1/payment-intents/{intent_id}/capture"),
            capture_key,
            json!({}),
            "payment",
        )
        .await?;
        Ok(order_id)
    }
}
impl ReqwestFulfillmentClient {
    /// Bounded in-process retries for projection lag (same ruling as
    /// legacy-acl): redelivery-based retry costs a pending-claim cycle per
    /// attempt, far slower than the snapshot propagation it waits for.
    async fn post_for_json(
        &self,
        base_url: &str,
        path: &str,
        idempotency_key: &str,
        body: Value,
        service_name: &str,
    ) -> Result<Value, FulfillmentClientError> {
        const PROJECTION_RETRY_DELAYS_MS: [u64; 5] = [500, 1000, 2000, 4000, 8000];
        let mut attempt = 0usize;
        loop {
            match self
                .post_for_json_once(base_url, path, idempotency_key, body.clone(), service_name)
                .await
            {
                Err(FulfillmentClientError::ProjectionLag(message)) => {
                    if attempt >= PROJECTION_RETRY_DELAYS_MS.len() {
                        return Err(FulfillmentClientError::Transient(message));
                    }
                    tokio::time::sleep(std::time::Duration::from_millis(
                        PROJECTION_RETRY_DELAYS_MS[attempt],
                    ))
                    .await;
                    attempt += 1;
                }
                other => return other,
            }
        }
    }

    async fn post_for_json_once(
        &self,
        base_url: &str,
        path: &str,
        idempotency_key: &str,
        body: Value,
        service_name: &str,
    ) -> Result<Value, FulfillmentClientError> {
        let mut builder = self
            .client
            .post(format!("{}{}", base_url.trim_end_matches('/'), path))
            .header("Idempotency-Key", idempotency_key)
            .json(&body);
        for (key, value) in active_trace_headers() {
            builder = builder.header(key, value);
        }
        let response = builder
            .send()
            .await
            .map_err(|error| FulfillmentClientError::Transient(error.to_string()))?;
        let status = response.status();
        if status.is_server_error() {
            return Err(FulfillmentClientError::Transient(format!(
                "{service_name} returned {status}"
            )));
        }
        if status.is_client_error() {
            // Error envelope convention: the specific code rides in
            // details.domainCode. Projection-lag rejections (downstream has
            // not consumed the just-published snapshot yet) are transient —
            // the redelivery retries with the same persisted keys. Same
            // ruling as legacy-acl's projection retry.
            let detail: Value = response.json().await.unwrap_or(Value::Null);
            let domain_code = detail
                .get("details")
                .and_then(|d| d.get("domainCode"))
                .and_then(Value::as_str)
                .or_else(|| detail.get("code").and_then(Value::as_str))
                .unwrap_or("")
                .to_string();
            const PROJECTION_LAG_CODES: [&str; 3] = [
                "MISSING_ITINERARY_SNAPSHOT",
                "MISSING_FARE_QUOTE",
                "MISSING_TRAVELER_SNAPSHOT",
            ];
            if PROJECTION_LAG_CODES.contains(&domain_code.as_str()) {
                return Err(FulfillmentClientError::ProjectionLag(format!(
                    "{service_name} projection lag: {domain_code}"
                )));
            }
            return Err(FulfillmentClientError::Rejected(format!(
                "{service_name} returned {status} ({domain_code})"
            )));
        }
        let response_body: Value = response
            .json()
            .await
            .map_err(|error| FulfillmentClientError::Transient(error.to_string()))?;
        Ok(response_body)
    }

    async fn post_for_string(
        &self,
        base_url: &str,
        path: &str,
        idempotency_key: &str,
        body: Value,
        response_field: &str,
        service_name: &str,
    ) -> Result<String, FulfillmentClientError> {
        let response_body = self
            .post_for_json(base_url, path, idempotency_key, body, service_name)
            .await?;
        string_at(&response_body, &[response_field]).ok_or_else(|| {
            FulfillmentClientError::Transient(format!(
                "{service_name} response missing {response_field}"
            ))
        })
    }
}

fn string_at(value: &Value, path: &[&str]) -> Option<String> {
    let mut current = value;
    for key in path {
        current = current.get(key)?;
    }
    current.as_str().map(str::to_string)
}
#[derive(Default)]
struct HeaderInjector(HashMap<String, String>);
impl Injector for HeaderInjector {
    fn set(&mut self, key: &str, value: String) {
        self.0.insert(key.to_string(), value);
    }
}
fn active_trace_headers() -> HashMap<String, String> {
    let mut injector = HeaderInjector::default();
    opentelemetry_sdk::propagation::TraceContextPropagator::new()
        .inject_context(&OtelContext::current(), &mut injector);
    injector.0
}
