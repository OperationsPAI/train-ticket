use std::collections::{HashMap, HashSet, VecDeque};
use std::fmt;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use axum::{
    Json, Router,
    extract::{Extension, Path, RawQuery, State, rejection::JsonRejection},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use chrono::{DateTime, SecondsFormat, Utc};
use opentelemetry::Context as OtelContext;
use opentelemetry::propagation::{Injector, TextMapPropagator};
use rust_kit::messaging::AsyncEventSubscriber;
use rust_kit::{http as kit_http, idempotency as kit_idempotency};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use shared_kernel::{
    OpenTelemetryObserver, RequestContext, RuntimeConfig, apply_runtime, router_with_config,
};
use tokio::task::JoinHandle;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ServiceProfile {
    pub service_id: &'static str,
    pub domain: &'static str,
    pub language: &'static str,
    pub phase: &'static str,
    pub work_packages: &'static [&'static str],
    pub owns: &'static [&'static str],
}
pub fn profile() -> ServiceProfile {
    ServiceProfile {
        service_id: "waitlist",
        domain: "Waitlist",
        language: "rust",
        phase: "phase-1-activation",
        work_packages: &["REQ-107"],
        owns: &["WaitlistRequest", "WaitlistQueue", "FulfillmentWindow"],
    }
}
pub fn health() -> &'static str {
    "ok"
}
pub fn metadata() -> ServiceProfile {
    profile()
}
pub fn runtime_config() -> RuntimeConfig {
    RuntimeConfig::from_metadata(metadata())
        .with_observer(OpenTelemetryObserver::from_env(profile().service_id))
}
pub fn apply_service_runtime(router: Router) -> Router {
    apply_runtime(router, runtime_config())
}

pub async fn router() -> Router {
    let service = Arc::new(
        PostgresWaitlistService::from_env()
            .await
            .expect("failed to initialize Postgres waitlist storage"),
    );
    router_with_postgres_state(service)
}

pub async fn build_runtime() -> Result<(Router, JoinHandle<()>), SubscribeFailed> {
    let _otel = rust_kit::otel::init_from_env(profile().service_id)
        .map_err(|e| SubscribeFailed(e.to_string()))?;
    let redis_url =
        std::env::var("REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379".to_string());
    let service = Arc::new(
        PostgresWaitlistService::from_env()
            .await
            .map_err(|e| SubscribeFailed(e.to_string()))?,
    );
    rust_kit::storage::spawn_outbox_relay(service.pool().clone(), redis_url);
    let expiry_service = service.clone();
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(Duration::from_secs(30));
        loop {
            interval.tick().await;
            let _ = expiry_service
                .expire_due(current_rfc3339(), rust_kit::messaging::correlation_id())
                .await;
        }
    });
    let subscriber = rust_kit::messaging::redis_runtime::RedisEventSubscriber::from_env()?;
    let streams = vec![
        rust_kit::messaging::stream_for_producer("capacity-availability"),
        rust_kit::messaging::stream_for_producer("journey-order"),
    ];
    let consumer_name = std::env::var("HOSTNAME")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| format!("waitlist-{}", uuid::Uuid::now_v7()));
    let handler_service = service.clone();
    let handle = tokio::spawn(async move {
        subscriber
            .subscribe(
                streams,
                "waitlist".to_string(),
                consumer_name,
                Box::new(move |envelope| {
                    let service = handler_service.clone();
                    Box::pin(async move { service.handle_subscribed_event(envelope).await })
                }),
            )
            .await
            .expect("waitlist Redis subscriber stopped");
    });
    Ok((router_with_postgres_state(service), handle))
}

pub fn router_with_state<S: WaitlistApi + 'static>(service: Arc<S>) -> Router {
    apply_service_runtime(router_with_config(runtime_config()).merge(waitlist_routes(service)))
}
pub fn router_with_postgres_state(service: Arc<PostgresWaitlistService>) -> Router {
    let metadata = serde_json::to_value(metadata()).unwrap_or_else(|_| json!({}));
    let standard = Router::new()
        .route("/health", get(health_handler))
        .route("/healthz", get(health_handler))
        .route("/live", get(live_handler))
        .route("/livez", get(live_handler))
        .route("/ready", get(postgres_ready_handler))
        .route("/readyz", get(postgres_ready_handler))
        .route(
            "/metadata",
            get(move || {
                let metadata = metadata.clone();
                async move { Json(metadata) }
            }),
        )
        .layer(Extension(service.clone()));
    apply_service_runtime(standard.merge(waitlist_routes(service)))
}
fn waitlist_routes<S: WaitlistApi + 'static>(service: Arc<S>) -> Router {
    Router::new()
        .route(
            "/api/v1/waitlist-requests",
            post(create_waitlist::<S>).get(list_waitlist::<S>),
        )
        .route(
            "/api/v1/waitlist-requests/{waitlist_request_id}",
            get(get_waitlist::<S>),
        )
        .route(
            "/api/v1/waitlist-requests/{waitlist_request_id}/cancel",
            post(cancel_waitlist::<S>),
        )
        .with_state(ApiState { service })
}
#[derive(Debug, Serialize)]
struct ProbeResponse {
    status: &'static str,
}
async fn health_handler() -> Json<ProbeResponse> {
    Json(ProbeResponse { status: health() })
}
async fn live_handler() -> Json<ProbeResponse> {
    Json(ProbeResponse { status: "alive" })
}
async fn postgres_ready_handler(
    Extension(service): Extension<Arc<PostgresWaitlistService>>,
) -> (StatusCode, Json<ProbeResponse>) {
    if service.is_ready().await {
        (StatusCode::OK, Json(ProbeResponse { status: "ready" }))
    } else {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(ProbeResponse {
                status: "not_ready",
            }),
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WaitlistStatus {
    Draft,
    Queued,
    Matching,
    Fulfilled,
    Expired,
    Cancelled,
    Suspended,
    Closed,
}
impl WaitlistStatus {
    pub fn as_contract(self) -> &'static str {
        match self {
            Self::Draft => "DRAFT",
            Self::Queued => "QUEUED",
            Self::Matching => "MATCHING",
            Self::Fulfilled => "FULFILLED",
            Self::Expired => "EXPIRED",
            Self::Cancelled => "CANCELLED",
            Self::Suspended => "SUSPENDED",
            Self::Closed => "CLOSED",
        }
    }
    fn is_active(self) -> bool {
        matches!(
            self,
            Self::Draft | Self::Queued | Self::Matching | Self::Suspended
        )
    }
    fn can_transition_to(self, next: Self) -> bool {
        matches!(
            (self, next),
            (Self::Draft, Self::Queued)
                | (Self::Draft, Self::Cancelled)
                | (Self::Queued, Self::Matching)
                | (Self::Queued, Self::Expired)
                | (Self::Queued, Self::Cancelled)
                | (Self::Queued, Self::Suspended)
                | (Self::Matching, Self::Fulfilled)
                | (Self::Matching, Self::Queued)
                | (Self::Fulfilled, Self::Closed)
                | (Self::Expired, Self::Closed)
                | (Self::Cancelled, Self::Closed)
                | (Self::Suspended, Self::Queued)
                | (Self::Suspended, Self::Cancelled)
        )
    }
}
impl fmt::Display for WaitlistStatus {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_contract())
    }
}
impl std::str::FromStr for WaitlistStatus {
    type Err = WaitlistError;
    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s {
            "DRAFT" => Ok(Self::Draft),
            "QUEUED" => Ok(Self::Queued),
            "MATCHING" => Ok(Self::Matching),
            "FULFILLED" => Ok(Self::Fulfilled),
            "EXPIRED" => Ok(Self::Expired),
            "CANCELLED" => Ok(Self::Cancelled),
            "SUSPENDED" => Ok(Self::Suspended),
            "CLOSED" => Ok(Self::Closed),
            _ => Err(WaitlistError::ValidationFailed(
                "invalid waitlist status".into(),
            )),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WaitlistRequest {
    pub waitlist_request_id: String,
    pub traveler_ref: String,
    pub segment_ref: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub travel_class: Option<String>,
    pub deadline: String,
    pub payment_guarantee_ref: String,
    pub intent_fingerprint: String,
    pub status: WaitlistStatus,
    pub created_at: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub queued_at: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub order_ref: Option<String>,
    #[serde(default)]
    pub version: i64,
}
impl WaitlistRequest {
    pub fn create(
        command: CreateWaitlistCommand,
        now: String,
    ) -> Result<(Self, Vec<WaitlistEvent>), WaitlistError> {
        validate_prefixed_uuid(&command.traveler_ref, "travelerRef", "tvl-")?;
        validate_non_empty(&command.segment_ref, "segmentRef")?;
        validate_non_empty(&command.intent_fingerprint, "intentFingerprint")?;
        validate_payment_guarantee(&command.payment_guarantee_ref)?;
        if !deadline_after(&command.deadline, &now) {
            return Err(WaitlistError::DomainRuleViolation(
                "deadline must be in the future".into(),
            ));
        }
        let mut r = Self {
            waitlist_request_id: format!("wlr-{}", uuid::Uuid::now_v7()),
            traveler_ref: command.traveler_ref,
            segment_ref: command.segment_ref,
            travel_class: command.travel_class,
            deadline: command.deadline,
            payment_guarantee_ref: command.payment_guarantee_ref,
            intent_fingerprint: command.intent_fingerprint,
            status: WaitlistStatus::Draft,
            created_at: now.clone(),
            queued_at: None,
            order_ref: None,
            version: 1,
        };
        let created = WaitlistEvent::created(&r, now.clone());
        let payment = WaitlistEvent::payment(&r, now.clone());
        r.transition(WaitlistStatus::Queued)?;
        r.queued_at = Some(now.clone());
        let queued = WaitlistEvent::queued(&r, now, None);
        Ok((r, vec![created, payment, queued]))
    }
    pub fn transition(&mut self, next: WaitlistStatus) -> Result<(), WaitlistError> {
        if self.status.can_transition_to(next) {
            self.status = next;
            self.version += 1;
            Ok(())
        } else {
            Err(WaitlistError::DomainRuleViolation(format!(
                "invalid waitlist transition {} -> {}",
                self.status, next
            )))
        }
    }
    pub fn start_matching(
        &mut self,
        release: String,
        now: String,
    ) -> Result<WaitlistEvent, WaitlistError> {
        self.transition(WaitlistStatus::Matching)?;
        Ok(WaitlistEvent::match_started(
            self,
            release,
            journey_order_idempotency_key(&self.waitlist_request_id),
            now,
        ))
    }
    pub fn record_order_ref(&mut self, order: String) {
        self.order_ref = Some(order);
    }
    pub fn requeue_after_order_cancelled(
        &mut self,
        now: String,
    ) -> Result<WaitlistEvent, WaitlistError> {
        let order = self.order_ref.clone();
        if self.status == WaitlistStatus::Matching {
            self.transition(WaitlistStatus::Queued)?;
        }
        self.queued_at = Some(now.clone());
        self.order_ref = None;
        Ok(WaitlistEvent::queued(self, now, order))
    }
    pub fn fulfill_and_close(
        &mut self,
        order: String,
        now: String,
    ) -> Result<Vec<WaitlistEvent>, WaitlistError> {
        self.order_ref = Some(order.clone());
        self.transition(WaitlistStatus::Fulfilled)?;
        let ev = WaitlistEvent::fulfilled(self, order, now);
        self.transition(WaitlistStatus::Closed)?;
        Ok(vec![ev])
    }
    pub fn cancel(&mut self, reason: String, now: String) -> Result<WaitlistEvent, WaitlistError> {
        validate_non_empty(&reason, "reason")?;
        if !matches!(
            self.status,
            WaitlistStatus::Draft | WaitlistStatus::Queued | WaitlistStatus::Suspended
        ) {
            return Err(WaitlistError::PreconditionFailed(
                "terminal or matching waitlist request cannot be cancelled".into(),
            ));
        }
        self.transition(WaitlistStatus::Cancelled)?;
        Ok(WaitlistEvent::cancelled(self, reason, now))
    }
    pub fn expire_and_close(&mut self, now: String) -> Result<Vec<WaitlistEvent>, WaitlistError> {
        match self.status {
            WaitlistStatus::Queued => {}
            WaitlistStatus::Suspended => self.transition(WaitlistStatus::Queued)?,
            _ => {
                return Err(WaitlistError::PreconditionFailed(
                    "only queued or suspended requests can expire".into(),
                ));
            }
        };
        self.transition(WaitlistStatus::Expired)?;
        let ev = WaitlistEvent::expired(self, now);
        self.transition(WaitlistStatus::Closed)?;
        Ok(vec![ev])
    }
}

#[derive(Debug, Default, Clone)]
pub struct WaitlistQueue {
    partitions: HashMap<String, VecDeque<String>>,
}
impl WaitlistQueue {
    pub fn rebuild(requests: impl IntoIterator<Item = WaitlistRequest>) -> Self {
        let mut grouped: HashMap<String, Vec<WaitlistRequest>> = HashMap::new();
        for r in requests {
            if r.status == WaitlistStatus::Queued {
                grouped.entry(r.segment_ref.clone()).or_default().push(r);
            }
        }
        let mut partitions = HashMap::new();
        for (seg, mut rs) in grouped {
            rs.sort_by(|a, b| {
                a.queued_at
                    .as_deref()
                    .unwrap_or(&a.created_at)
                    .cmp(b.queued_at.as_deref().unwrap_or(&b.created_at))
                    .then(a.waitlist_request_id.cmp(&b.waitlist_request_id))
            });
            partitions.insert(seg, rs.into_iter().map(|r| r.waitlist_request_id).collect());
        }
        Self { partitions }
    }
    pub fn head_for_segment(&self, segment: &str) -> Option<&str> {
        self.partitions.get(segment)?.front().map(String::as_str)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WaitlistEvent {
    Created(Value),
    Payment(Value),
    Queued(Value),
    MatchStarted(Value),
    HoldAuthorized(Value),
    Fulfilled(Value),
    Cancelled(Value),
    Expired(Value),
}
impl WaitlistEvent {
    fn event_type(&self) -> &'static str {
        match self {
            Self::Created(_) => "WaitlistRequestCreated",
            Self::Payment(_) => "WaitlistPaymentAuthorizationRequested",
            Self::Queued(_) => "WaitlistQueued",
            Self::MatchStarted(_) => "WaitlistMatchStarted",
            Self::HoldAuthorized(_) => "WaitlistHoldAuthorized",
            Self::Fulfilled(_) => "WaitlistFulfilled",
            Self::Cancelled(_) => "WaitlistCancelled",
            Self::Expired(_) => "WaitlistExpired",
        }
    }
    fn payload(&self) -> &Value {
        match self {
            Self::Created(v)
            | Self::Payment(v)
            | Self::Queued(v)
            | Self::MatchStarted(v)
            | Self::HoldAuthorized(v)
            | Self::Fulfilled(v)
            | Self::Cancelled(v)
            | Self::Expired(v) => v,
        }
    }
    fn envelope(
        self,
        id: &str,
        version: i64,
        corr: String,
        cause: Option<String>,
    ) -> rust_kit::messaging::EventEnvelope {
        let ty = self.event_type();
        let mut e = rust_kit::messaging::EventEnvelope::canonical(
            ty,
            rust_kit::messaging::valid_or_generated_correlation_id(corr),
            cause,
            profile().service_id,
            self.payload().clone(),
        );
        e.event_id = deterministic_event_id(ty, id, version);
        e
    }
    fn created(r: &WaitlistRequest, t: String) -> Self {
        Self::Created(
            json!({"waitlistRequestId":r.waitlist_request_id,"travelerRef":r.traveler_ref,"segmentRef":r.segment_ref,"travelClass":r.travel_class,"deadline":r.deadline,"paymentGuaranteeRef":r.payment_guarantee_ref,"intentFingerprint":r.intent_fingerprint,"status":"DRAFT","createdAt":t}),
        )
    }
    fn payment(r: &WaitlistRequest, t: String) -> Self {
        Self::Payment(
            json!({"waitlistRequestId":r.waitlist_request_id,"travelerRef":r.traveler_ref,"paymentGuaranteeRef":r.payment_guarantee_ref,"requestedAt":t,"status":r.status.as_contract()}),
        )
    }
    fn queued(r: &WaitlistRequest, t: String, order: Option<String>) -> Self {
        let mut v = json!({"waitlistRequestId":r.waitlist_request_id,"travelerRef":r.traveler_ref,"segmentRef":r.segment_ref,"travelClass":r.travel_class,"intentFingerprint":r.intent_fingerprint,"queuedAt":t,"status":"QUEUED"});
        if let Some(o) = order {
            v["journeyOrderRef"] = json!(o)
        }
        Self::Queued(v)
    }
    fn match_started(r: &WaitlistRequest, rel: String, key: String, t: String) -> Self {
        Self::MatchStarted(
            json!({"waitlistRequestId":r.waitlist_request_id,"travelerRef":r.traveler_ref,"segmentRef":r.segment_ref,"travelClass":r.travel_class,"matchedCapacityReleaseRef":rel,"journeyOrderIdempotencyKey":key,"startedAt":t,"status":"MATCHING"}),
        )
    }
    fn fulfilled(r: &WaitlistRequest, order: String, t: String) -> Self {
        Self::Fulfilled(
            json!({"waitlistRequestId":r.waitlist_request_id,"travelerRef":r.traveler_ref,"segmentRef":r.segment_ref,"travelClass":r.travel_class,"journeyOrderRef":order,"fulfilledAt":t,"status":"FULFILLED"}),
        )
    }
    fn cancelled(r: &WaitlistRequest, reason: String, t: String) -> Self {
        Self::Cancelled(
            json!({"waitlistRequestId":r.waitlist_request_id,"travelerRef":r.traveler_ref,"segmentRef":r.segment_ref,"travelClass":r.travel_class,"cancelledAt":t,"reason":reason,"status":"CANCELLED"}),
        )
    }
    fn expired(r: &WaitlistRequest, t: String) -> Self {
        Self::Expired(
            json!({"waitlistRequestId":r.waitlist_request_id,"travelerRef":r.traveler_ref,"segmentRef":r.segment_ref,"travelClass":r.travel_class,"deadline":r.deadline,"expiredAt":t,"status":"EXPIRED"}),
        )
    }
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CreateWaitlistCommand {
    pub traveler_ref: String,
    pub segment_ref: String,
    pub travel_class: Option<String>,
    pub deadline: String,
    pub payment_guarantee_ref: String,
    pub intent_fingerprint: String,
}
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CancelWaitlistCommand {
    pub reason: String,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct WaitlistResource {
    pub waitlist_request_id: String,
    pub traveler_ref: String,
    pub segment_ref: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub travel_class: Option<String>,
    pub deadline: String,
    pub payment_guarantee_ref: String,
    pub intent_fingerprint: String,
    pub status: WaitlistStatus,
}
impl From<&WaitlistRequest> for WaitlistResource {
    fn from(r: &WaitlistRequest) -> Self {
        Self {
            waitlist_request_id: r.waitlist_request_id.clone(),
            traveler_ref: r.traveler_ref.clone(),
            segment_ref: r.segment_ref.clone(),
            travel_class: r.travel_class.clone(),
            deadline: r.deadline.clone(),
            payment_guarantee_ref: r.payment_guarantee_ref.clone(),
            intent_fingerprint: r.intent_fingerprint.clone(),
            status: r.status,
        }
    }
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CancelWaitlistResponse {
    pub waitlist_request_id: String,
    pub status: WaitlistStatus,
    pub cancelled_at: String,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PaginatedWaitlistRequests {
    pub items: Vec<WaitlistResource>,
    pub total: usize,
    pub limit: usize,
    pub offset: usize,
}

#[derive(Debug, Clone)]
pub enum WaitlistError {
    ValidationFailed(String),
    NotFound(String),
    Conflict(String),
    IdempotencyKeyReused(String),
    PreconditionFailed(String),
    DomainRuleViolation(String),
    Unavailable(String),
    Internal(String),
}
impl WaitlistError {
    fn status(&self) -> StatusCode {
        match self {
            Self::ValidationFailed(_) => StatusCode::BAD_REQUEST,
            Self::NotFound(_) => StatusCode::NOT_FOUND,
            Self::Conflict(_) => StatusCode::CONFLICT,
            Self::IdempotencyKeyReused(_) => StatusCode::UNPROCESSABLE_ENTITY,
            Self::PreconditionFailed(_) => StatusCode::PRECONDITION_FAILED,
            Self::DomainRuleViolation(_) => StatusCode::UNPROCESSABLE_ENTITY,
            Self::Unavailable(_) => StatusCode::SERVICE_UNAVAILABLE,
            Self::Internal(_) => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }
    fn code(&self) -> &'static str {
        match self {
            Self::ValidationFailed(_) => "VALIDATION_FAILED",
            Self::NotFound(_) => "NOT_FOUND",
            Self::Conflict(_) => "CONFLICT",
            Self::IdempotencyKeyReused(_) => "IDEMPOTENCY_KEY_REUSED",
            Self::PreconditionFailed(_) => "PRECONDITION_FAILED",
            Self::DomainRuleViolation(_) => "DOMAIN_RULE_VIOLATION",
            Self::Unavailable(_) | Self::Internal(_) => "UNAVAILABLE",
        }
    }
    fn message(&self) -> &str {
        match self {
            Self::ValidationFailed(m)
            | Self::NotFound(m)
            | Self::Conflict(m)
            | Self::IdempotencyKeyReused(m)
            | Self::PreconditionFailed(m)
            | Self::DomainRuleViolation(m)
            | Self::Unavailable(m)
            | Self::Internal(m) => m,
        }
    }
}
impl fmt::Display for WaitlistError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.message())
    }
}
impl std::error::Error for WaitlistError {}
fn api_error_response(e: WaitlistError, corr: String) -> Response {
    kit_http::error_response(
        e.status(),
        e.code(),
        e.message().to_string(),
        corr,
        Some(json!({"domainCode":e.code()})),
    )
}

pub struct ApiState<S: WaitlistApi + 'static> {
    service: Arc<S>,
}
impl<S: WaitlistApi + 'static> Clone for ApiState<S> {
    fn clone(&self) -> Self {
        Self {
            service: Arc::clone(&self.service),
        }
    }
}
#[async_trait]
pub trait WaitlistApi: Send + Sync {
    async fn create(
        &self,
        c: CreateWaitlistCommand,
        key: String,
        corr: String,
    ) -> Result<WaitlistResource, WaitlistError>;
    async fn get(&self, id: String) -> Result<WaitlistResource, WaitlistError>;
    async fn cancel(
        &self,
        id: String,
        c: CancelWaitlistCommand,
        key: String,
        corr: String,
    ) -> Result<CancelWaitlistResponse, WaitlistError>;
    async fn list(
        &self,
        traveler: String,
        status: Option<WaitlistStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<PaginatedWaitlistRequests, WaitlistError>;
}

async fn create_waitlist<S: WaitlistApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    headers: HeaderMap,
    body: Result<Json<CreateWaitlistCommand>, JsonRejection>,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    let key = match idempotency_key(&headers) {
        Ok(k) => k,
        Err(e) => return idempotency_error_response(e, corr),
    };
    let Json(cmd) = match body {
        Ok(b) => b,
        Err(e) => return validation_error(corr, e.body_text()),
    };
    match st.service.create(cmd, key, corr.clone()).await {
        Ok(r) => (StatusCode::CREATED, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
async fn get_waitlist<S: WaitlistApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    Path(id): Path<String>,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    match st.service.get(id).await {
        Ok(r) => (StatusCode::OK, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
async fn cancel_waitlist<S: WaitlistApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    headers: HeaderMap,
    Path(id): Path<String>,
    body: Result<Json<CancelWaitlistCommand>, JsonRejection>,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    let key = match idempotency_key(&headers) {
        Ok(k) => k,
        Err(e) => return idempotency_error_response(e, corr),
    };
    let Json(cmd) = match body {
        Ok(b) => b,
        Err(e) => return validation_error(corr, e.body_text()),
    };
    match st.service.cancel(id, cmd, key, corr.clone()).await {
        Ok(r) => (StatusCode::OK, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
async fn list_waitlist<S: WaitlistApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    RawQuery(raw): RawQuery,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    let q = match parse_list_query(raw.as_deref()) {
        Ok(q) => q,
        Err(m) => return validation_error(corr, m),
    };
    match st
        .service
        .list(
            q.traveler_ref,
            q.status,
            q.limit.unwrap_or(20).min(100),
            q.offset.unwrap_or(0),
        )
        .await
    {
        Ok(r) => (StatusCode::OK, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
fn validation_error(corr: String, msg: impl Into<String>) -> Response {
    kit_http::error_response(
        StatusCode::BAD_REQUEST,
        "VALIDATION_FAILED",
        msg.into(),
        corr,
        Some(json!({"domainCode":"VALIDATION_FAILED"})),
    )
}
fn idempotency_error_response(e: kit_idempotency::IdempotencyError, corr: String) -> Response {
    match e {
        kit_idempotency::IdempotencyError::Reused => api_error_response(
            WaitlistError::IdempotencyKeyReused(
                "Idempotency-Key was reused with a different request body".into(),
            ),
            corr,
        ),
        _ => validation_error(
            corr,
            "Idempotency-Key header is required and must be a valid UUID v7",
        ),
    }
}
fn idempotency_key(headers: &HeaderMap) -> Result<String, kit_idempotency::IdempotencyError> {
    kit_idempotency::require_idempotency_key(headers)
}
struct ListQuery {
    traveler_ref: String,
    status: Option<WaitlistStatus>,
    limit: Option<usize>,
    offset: Option<usize>,
}
fn parse_list_query(raw: Option<&str>) -> Result<ListQuery, String> {
    let raw = raw.ok_or_else(|| "travelerRef query parameter is required".to_string())?;
    let (mut traveler, mut status, mut limit, mut offset) = (None, None, None, None);
    for pair in raw.split('&').filter(|p| !p.is_empty()) {
        let (n, v) = pair.split_once('=').unwrap_or((pair, ""));
        match n {
            "travelerRef" => traveler = Some(v.to_string()),
            "status" => {
                status = Some(
                    v.parse()
                        .map_err(|_| "status query parameter is invalid".to_string())?,
                )
            }
            "limit" => {
                limit = Some(v.parse().map_err(|_| {
                    "limit query parameter must be a non-negative integer".to_string()
                })?)
            }
            "offset" => {
                offset = Some(v.parse().map_err(|_| {
                    "offset query parameter must be a non-negative integer".to_string()
                })?)
            }
            _ => {}
        }
    }
    Ok(ListQuery {
        traveler_ref: traveler
            .filter(|v| !v.trim().is_empty())
            .ok_or_else(|| "travelerRef query parameter is required".to_string())?,
        status,
        limit,
        offset,
    })
}

pub struct InMemoryWaitlistService {
    state: Mutex<InMemoryState>,
    publisher: Arc<dyn EventPublisher>,
    order_client: Arc<dyn JourneyOrderClient>,
}

impl Default for InMemoryWaitlistService {
    fn default() -> Self {
        Self::new(
            Arc::new(InMemoryEventPublisher::default()),
            Arc::new(NoopJourneyOrderClient),
        )
    }
}
impl InMemoryWaitlistService {
    pub fn new(
        publisher: Arc<dyn EventPublisher>,
        order_client: Arc<dyn JourneyOrderClient>,
    ) -> Self {
        Self {
            state: Mutex::new(InMemoryState::default()),
            publisher,
            order_client,
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
            let key = journey_order_idempotency_key(&request.waitlist_request_id);
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
            .order_client
            .create_order(&request, &key, &envelope.correlation_id)
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
            Err(OrderClientError::Transient(message)) => Err(WaitlistError::Unavailable(message)),
            Err(OrderClientError::Rejected(message)) => {
                log::warn!(
                    "journey-order rejected waitlist fulfillment requestId={}: {message}",
                    request.waitlist_request_id
                );
                let queued = {
                    let mut state = self.state.lock().expect("waitlist state lock poisoned");
                    state
                        .requests
                        .get_mut(&request.waitlist_request_id)
                        .expect("request exists")
                        .requeue_after_order_cancelled(current_rfc3339())?
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
struct InMemoryState {
    requests: HashMap<String, WaitlistRequest>,
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
pub trait JourneyOrderClient: Send + Sync {
    async fn create_order(
        &self,
        request: &WaitlistRequest,
        idempotency_key: &str,
        correlation_id: &str,
    ) -> Result<String, OrderClientError>;
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum OrderClientError {
    Transient(String),
    Rejected(String),
}
#[derive(Default)]
pub struct NoopJourneyOrderClient;
#[async_trait]
impl JourneyOrderClient for NoopJourneyOrderClient {
    async fn create_order(
        &self,
        _: &WaitlistRequest,
        _: &str,
        _: &str,
    ) -> Result<String, OrderClientError> {
        Ok(format!("ord-{}", uuid::Uuid::now_v7()))
    }
}

pub struct ReqwestJourneyOrderClient {
    base_url: String,
    client: reqwest::Client,
}
impl ReqwestJourneyOrderClient {
    pub fn from_env() -> Self {
        Self {
            base_url: std::env::var("JOURNEY_ORDER_BASE_URL")
                .unwrap_or_else(|_| "http://journey-order:8080".into()),
            client: reqwest::Client::new(),
        }
    }
}
#[async_trait]
impl JourneyOrderClient for ReqwestJourneyOrderClient {
    async fn create_order(
        &self,
        request: &WaitlistRequest,
        key: &str,
        _: &str,
    ) -> Result<String, OrderClientError> {
        let mut builder = self.client.post(format!("{}/api/v1/journey-orders", self.base_url.trim_end_matches('/'))).header("Idempotency-Key", key).json(&json!({ "accountId": request.traveler_ref, "offerId": format!("off-waitlist-{}", request.waitlist_request_id), "offerVersion": 1, "travelerRefs": [request.traveler_ref.clone()], "segmentRefs": [request.segment_ref.clone()] }));
        for (key, value) in active_trace_headers() {
            builder = builder.header(key, value);
        }
        let response = builder
            .send()
            .await
            .map_err(|error| OrderClientError::Transient(error.to_string()))?;
        let status = response.status();
        if status.is_server_error() {
            return Err(OrderClientError::Transient(format!(
                "journey-order returned {status}"
            )));
        }
        if status.is_client_error() {
            return Err(OrderClientError::Rejected(format!(
                "journey-order returned {status}"
            )));
        }
        let body: Value = response
            .json()
            .await
            .map_err(|error| OrderClientError::Transient(error.to_string()))?;
        body.get("orderId")
            .and_then(Value::as_str)
            .map(str::to_string)
            .ok_or_else(|| {
                OrderClientError::Transient("journey-order response missing orderId".into())
            })
    }
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

pub struct PostgresWaitlistService {
    storage: rust_kit::storage::Storage,
    order_client: Arc<dyn JourneyOrderClient>,
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
            order_client: Arc::new(ReqwestJourneyOrderClient::from_env()),
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
        sqlx::query("INSERT INTO waitlist_requests (waitlist_request_id, traveler_ref, intent_fingerprint, segment_ref, status, deadline, queued_at, order_ref, version, data) VALUES ($1,$2,$3,$4,$5,$6::timestamptz,$7::timestamptz,$8,$9,$10) ON CONFLICT (waitlist_request_id) DO UPDATE SET traveler_ref=EXCLUDED.traveler_ref,intent_fingerprint=EXCLUDED.intent_fingerprint,segment_ref=EXCLUDED.segment_ref,status=EXCLUDED.status,deadline=EXCLUDED.deadline,queued_at=EXCLUDED.queued_at,order_ref=EXCLUDED.order_ref,version=EXCLUDED.version,data=EXCLUDED.data,updated_at=now()")
            .bind(&request.waitlist_request_id).bind(&request.traveler_ref).bind(&request.intent_fingerprint).bind(&request.segment_ref).bind(request.status.as_contract()).bind(&request.deadline).bind(&request.queued_at).bind(&request.order_ref).bind(request.version).bind(data).execute(&mut **tx).await.map_err(db_error)?;
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
            "events:capacity-availability",
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
        let key = journey_order_idempotency_key(&request.waitlist_request_id);
        match self
            .order_client
            .create_order(&request, &key, &envelope.correlation_id)
            .await
        {
            Ok(order) => {
                sqlx::query("UPDATE waitlist_requests SET order_ref=$2, data=jsonb_set(data,'{orderRef}',to_jsonb($2::text),true), updated_at=now() WHERE waitlist_request_id=$1").bind(&request.waitlist_request_id).bind(order).execute(self.pool()).await.map_err(db_error)?;
                Ok(())
            }
            Err(OrderClientError::Transient(message)) => Err(WaitlistError::Unavailable(message)),
            Err(OrderClientError::Rejected(message)) => {
                log::warn!(
                    "journey-order rejected waitlist fulfillment requestId={}: {message}",
                    request.waitlist_request_id
                );
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
            "events:journey-order",
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

#[derive(Debug, Clone)]
pub struct SubscribeFailed(pub String);
impl fmt::Display for SubscribeFailed {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}
impl std::error::Error for SubscribeFailed {}
impl From<rust_kit::messaging::SubscribeFailed> for SubscribeFailed {
    fn from(error: rust_kit::messaging::SubscribeFailed) -> Self {
        Self(error.to_string())
    }
}
fn handler_error(error: WaitlistError) -> rust_kit::messaging::HandlerError {
    match error {
        WaitlistError::Unavailable(message) | WaitlistError::Internal(message) => {
            rust_kit::messaging::HandlerError::Transient(message)
        }
        other => rust_kit::messaging::HandlerError::Fatal(other.to_string()),
    }
}
fn storage_error(error: rust_kit::storage::StorageError) -> WaitlistError {
    match error {
        rust_kit::storage::StorageError::IdempotencyKeyReused => {
            WaitlistError::IdempotencyKeyReused(
                "Idempotency-Key was reused with a different request body".into(),
            )
        }
        other => WaitlistError::Internal(other.to_string()),
    }
}
fn db_error(error: sqlx::Error) -> WaitlistError {
    let message = error.to_string();
    if message.contains("active_waitlist_request") {
        WaitlistError::Conflict(
            "traveler already has an active waitlist request for this intent".into(),
        )
    } else {
        WaitlistError::Internal(message)
    }
}
fn current_rfc3339() -> String {
    Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true)
}
fn validate_non_empty(value: &str, field: &'static str) -> Result<(), WaitlistError> {
    if value.trim().is_empty() {
        Err(WaitlistError::ValidationFailed(format!(
            "{field} must not be blank"
        )))
    } else {
        Ok(())
    }
}
fn validate_prefixed_uuid(
    value: &str,
    field: &'static str,
    prefix: &'static str,
) -> Result<(), WaitlistError> {
    validate_non_empty(value, field)?;
    let Some(uuid_part) = value.strip_prefix(prefix) else {
        return Err(WaitlistError::ValidationFailed(format!(
            "{field} must use {prefix}<uuid> format"
        )));
    };
    uuid::Uuid::parse_str(uuid_part).map_err(|_| {
        WaitlistError::ValidationFailed(format!("{field} must use {prefix}<uuid> format"))
    })?;
    Ok(())
}
fn validate_payment_guarantee(value: &str) -> Result<(), WaitlistError> {
    validate_non_empty(value, "paymentGuaranteeRef")?;
    if value.starts_with("pay-auth-") {
        Ok(())
    } else {
        validate_prefixed_uuid(value, "paymentGuaranteeRef", "pi-")
    }
}
fn deadline_after(deadline: &str, now: &str) -> bool {
    let Ok(deadline) = DateTime::parse_from_rfc3339(deadline) else {
        return false;
    };
    let Ok(now) = DateTime::parse_from_rfc3339(now) else {
        return false;
    };
    deadline > now
}
fn deterministic_event_id(event_type: &str, id: &str, version: i64) -> String {
    let seed = format!("waitlist:{event_type}:{id}:{version}");
    format!("evt-wl-{:x}", Sha256::digest(seed.as_bytes()))
}
fn journey_order_idempotency_key(id: &str) -> String {
    format!("wl-fulfill:{id}")
}
fn string_field(payload: &Value, field: &str) -> Option<String> {
    payload
        .get(field)
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty())
        .map(str::to_string)
}
fn segment_from_payload(payload: &Value) -> Option<String> {
    string_field(payload, "segmentRef")
        .or_else(|| string_field(payload, "serviceSegmentRef"))
        .or_else(|| string_field(payload, "capacityUnitRef"))
}
fn order_ref_from_payload(payload: &Value) -> Option<String> {
    string_field(payload, "orderRef")
        .or_else(|| string_field(payload, "orderId"))
        .or_else(|| string_field(payload, "journeyOrderRef"))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn tvl(suffix: &str) -> String {
        format!("tvl-0194f2e0-7b3e-7610-8284-5c26e8b0{suffix}")
    }
    fn seg(suffix: &str) -> String {
        format!("seg-0194f2e0-7b3e-7610-8284-5c26e8b0{suffix}")
    }
    fn key(suffix: &str) -> String {
        format!("0194f2e0-7b3e-7610-8284-5c26e8b0{suffix}")
    }
    fn ord(suffix: &str) -> String {
        format!("ord-0194f2e0-7b3e-7610-8284-5c26e8b0{suffix}")
    }
    fn command(traveler: String, segment: String) -> CreateWaitlistCommand {
        CreateWaitlistCommand {
            traveler_ref: traveler,
            segment_ref: segment,
            travel_class: Some("SECOND".into()),
            deadline: "2099-01-01T00:00:00.000Z".into(),
            payment_guarantee_ref: "pay-auth-test".into(),
            intent_fingerprint: "intent-a".into(),
        }
    }
    fn request() -> WaitlistRequest {
        WaitlistRequest::create(
            command(tvl("aa11"), seg("aa12")),
            "2026-01-01T00:00:00.000Z".into(),
        )
        .unwrap()
        .0
    }
    fn envelope(event_type: &str, payload: Value) -> rust_kit::messaging::EventEnvelope {
        rust_kit::messaging::EventEnvelope::canonical(
            event_type,
            rust_kit::messaging::correlation_id(),
            Some(rust_kit::messaging::command_id()),
            "test",
            payload,
        )
    }

    #[test]
    fn profile_matches_domain() {
        assert_eq!(profile().service_id, "waitlist");
        assert_eq!(health(), "ok");
    }
    #[test]
    fn state_machine_full_transition_table() {
        let statuses = [
            WaitlistStatus::Draft,
            WaitlistStatus::Queued,
            WaitlistStatus::Matching,
            WaitlistStatus::Fulfilled,
            WaitlistStatus::Expired,
            WaitlistStatus::Cancelled,
            WaitlistStatus::Suspended,
            WaitlistStatus::Closed,
        ];
        for from in statuses {
            for to in statuses {
                let allowed = matches!(
                    (from, to),
                    (WaitlistStatus::Draft, WaitlistStatus::Queued)
                        | (WaitlistStatus::Draft, WaitlistStatus::Cancelled)
                        | (WaitlistStatus::Queued, WaitlistStatus::Matching)
                        | (WaitlistStatus::Queued, WaitlistStatus::Expired)
                        | (WaitlistStatus::Queued, WaitlistStatus::Cancelled)
                        | (WaitlistStatus::Queued, WaitlistStatus::Suspended)
                        | (WaitlistStatus::Matching, WaitlistStatus::Fulfilled)
                        | (WaitlistStatus::Matching, WaitlistStatus::Queued)
                        | (WaitlistStatus::Fulfilled, WaitlistStatus::Closed)
                        | (WaitlistStatus::Expired, WaitlistStatus::Closed)
                        | (WaitlistStatus::Cancelled, WaitlistStatus::Closed)
                        | (WaitlistStatus::Suspended, WaitlistStatus::Queued)
                        | (WaitlistStatus::Suspended, WaitlistStatus::Cancelled)
                );
                assert_eq!(from.can_transition_to(to), allowed);
            }
        }
        assert!(request().transition(WaitlistStatus::Fulfilled).is_err());
    }
    #[tokio::test]
    async fn runtime_endpoints() {
        use axum::body::Body;
        use axum::http::Request;
        use shared_kernel::{CORRELATION_ID_HEADER, REQUEST_ID_HEADER};
        use tower::ServiceExt;
        let router = router_with_state(Arc::new(InMemoryWaitlistService::default()));
        for path in [
            "/health",
            "/live",
            "/livez",
            "/ready",
            "/readyz",
            "/metadata",
        ] {
            let response = router
                .clone()
                .oneshot(Request::builder().uri(path).body(Body::empty()).unwrap())
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::OK);
            assert!(!response.headers()[REQUEST_ID_HEADER].is_empty());
            assert!(!response.headers()[CORRELATION_ID_HEADER].is_empty());
        }
    }
    #[tokio::test]
    async fn mutual_exclusion() {
        let service = InMemoryWaitlistService::default();
        service
            .create(
                command(tvl("bb11"), seg("bb12")),
                key("bb01"),
                "corr".into(),
            )
            .await
            .unwrap();
        assert!(matches!(
            service
                .create(
                    command(tvl("bb11"), seg("bb13")),
                    key("bb02"),
                    "corr".into()
                )
                .await
                .unwrap_err(),
            WaitlistError::Conflict(_)
        ));
    }
    #[test]
    fn queue_fifo_stable() {
        let mut first = request();
        first.waitlist_request_id = "wlr-0194f2e0-7b3e-7610-8284-5c26e8b0c001".into();
        first.queued_at = Some("2026-01-01T00:00:00.000Z".into());
        let mut second = request();
        second.waitlist_request_id = "wlr-0194f2e0-7b3e-7610-8284-5c26e8b0c002".into();
        second.queued_at = first.queued_at.clone();
        let queue = WaitlistQueue::rebuild([second, first]);
        assert_eq!(
            queue.head_for_segment(&seg("aa12")),
            Some("wlr-0194f2e0-7b3e-7610-8284-5c26e8b0c001")
        );
    }
    #[derive(Default)]
    struct FakeOrder {
        result: Mutex<Option<Result<String, OrderClientError>>>,
    }
    #[async_trait]
    impl JourneyOrderClient for FakeOrder {
        async fn create_order(
            &self,
            _: &WaitlistRequest,
            _: &str,
            _: &str,
        ) -> Result<String, OrderClientError> {
            self.result
                .lock()
                .unwrap()
                .take()
                .unwrap_or_else(|| Ok(ord("cc01")))
        }
    }
    #[tokio::test]
    async fn capacity_released_match_and_skip() {
        let publisher = Arc::new(InMemoryEventPublisher::default());
        let service =
            InMemoryWaitlistService::new(publisher.clone(), Arc::new(FakeOrder::default()));
        let created = service
            .create(
                command(tvl("cc11"), seg("cc12")),
                key("cc02"),
                "corr".into(),
            )
            .await
            .unwrap();
        service
            .apply_subscribed_event(envelope(
                "CapacityReleased",
                json!({"segmentRef": seg("cc12")}),
            ))
            .await
            .unwrap();
        let stored = service
            .state
            .lock()
            .unwrap()
            .requests
            .get(&created.waitlist_request_id)
            .unwrap()
            .clone();
        assert_eq!(stored.status, WaitlistStatus::Matching);
        assert!(stored.order_ref.is_some());
        let before = publisher.published().len();
        service
            .apply_subscribed_event(envelope(
                "CapacityReleased",
                json!({"segmentRef": seg("dd12")}),
            ))
            .await
            .unwrap();
        assert_eq!(publisher.published().len(), before);
    }
    #[tokio::test]
    async fn order_confirm_cancel() {
        let service = InMemoryWaitlistService::default();
        let created = service
            .create(
                command(tvl("dd11"), seg("dd12")),
                key("dd01"),
                "corr".into(),
            )
            .await
            .unwrap();
        {
            let mut state = service.state.lock().unwrap();
            let request = state
                .requests
                .get_mut(&created.waitlist_request_id)
                .unwrap();
            request.transition(WaitlistStatus::Matching).unwrap();
            request.record_order_ref(ord("dd02"));
        }
        service
            .apply_subscribed_event(envelope(
                "JourneyOrderCancelled",
                json!({"orderId": ord("dd02")}),
            ))
            .await
            .unwrap();
        assert_eq!(
            service
                .state
                .lock()
                .unwrap()
                .requests
                .get(&created.waitlist_request_id)
                .unwrap()
                .status,
            WaitlistStatus::Queued
        );
        {
            let mut state = service.state.lock().unwrap();
            let request = state
                .requests
                .get_mut(&created.waitlist_request_id)
                .unwrap();
            request.transition(WaitlistStatus::Matching).unwrap();
            request.record_order_ref(ord("dd03"));
        }
        service
            .apply_subscribed_event(envelope(
                "JourneyOrderConfirmed",
                json!({"orderId": ord("dd03")}),
            ))
            .await
            .unwrap();
        assert_eq!(
            service
                .state
                .lock()
                .unwrap()
                .requests
                .get(&created.waitlist_request_id)
                .unwrap()
                .status,
            WaitlistStatus::Closed
        );
    }
    #[tokio::test]
    async fn expiry_scan() {
        let service = InMemoryWaitlistService::default();
        let mut cmd = command(tvl("ee11"), seg("ee12"));
        cmd.deadline = "2026-01-01T00:00:01.000Z".into();
        let (request, _) = WaitlistRequest::create(cmd, "2026-01-01T00:00:00.000Z".into()).unwrap();
        let id = request.waitlist_request_id.clone();
        service
            .state
            .lock()
            .unwrap()
            .requests
            .insert(id.clone(), request);
        assert_eq!(
            service
                .expire_due("2026-01-01T00:00:02.000Z".into(), "corr".into())
                .await
                .unwrap(),
            1
        );
        assert_eq!(
            service
                .state
                .lock()
                .unwrap()
                .requests
                .get(&id)
                .unwrap()
                .status,
            WaitlistStatus::Closed
        );
    }
    #[tokio::test]
    async fn idempotency_replay() {
        let service = InMemoryWaitlistService::default();
        let response = service
            .create(
                command(tvl("ff11"), seg("ff12")),
                key("ff01"),
                "corr".into(),
            )
            .await
            .unwrap();
        assert_eq!(
            response,
            service
                .create(
                    command(tvl("ff11"), seg("ff12")),
                    key("ff01"),
                    "corr".into()
                )
                .await
                .unwrap()
        );
        assert!(matches!(
            service
                .create(
                    command(tvl("ff11"), seg("ff13")),
                    key("ff01"),
                    "corr".into()
                )
                .await
                .unwrap_err(),
            WaitlistError::IdempotencyKeyReused(_)
        ));
    }
}
