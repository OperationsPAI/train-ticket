use std::collections::{HashMap, HashSet};
use std::fmt;
use std::sync::{Arc, Mutex};
use tokio::task::JoinHandle;

use axum::{
    Json, Router,
    extract::{Extension, Path, RawQuery, State, rejection::JsonRejection},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use rust_kit::{http as kit_http, idempotency as kit_idempotency};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use shared_kernel::{
    OpenTelemetryObserver, RequestContext, RuntimeConfig, apply_runtime, router_with_config,
};

pub mod adapters;
pub mod application;

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
        service_id: "entitlement-ticketing",
        domain: "Entitlement & Ticketing",
        language: "rust",
        phase: "phase-1-domain-foundation",
        work_packages: &["REQ-013"],
        owns: &[
            "Entitlement lifecycle and audit invariants",
            "ticket credential references and uniqueness",
            "issuance preconditions from accepted upstream facts",
            "void, suspend/freeze, resume, expire decisions",
            "domain events for ticketing state changes",
        ],
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

pub async fn router() -> Router {
    let service = Arc::new(
        adapters::storage::PostgresEntitlementService::from_env()
            .await
            .expect("failed to initialize Postgres entitlement storage"),
    );
    router_with_postgres_state(service)
}

pub async fn build_runtime() -> Result<(Router, JoinHandle<()>), application::SubscribeFailed> {
    use crate::application::EventSubscriber;

    let redis_url =
        std::env::var("REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379".to_string());
    let service = Arc::new(
        adapters::storage::PostgresEntitlementService::from_env()
            .await
            .map_err(|error| application::SubscribeFailed(error.to_string()))?,
    );
    rust_kit::storage::spawn_outbox_relay(service.pool().clone(), redis_url);
    let subscriber = adapters::messaging::RedisEventSubscriber::from_env()?;
    let streams = adapters::messaging::RedisEventSubscriber::entitlement_streams();
    let group = adapters::messaging::RedisEventSubscriber::entitlement_group().to_string();
    let consumer_name = std::env::var("HOSTNAME")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| format!("entitlement-ticketing-{}", uuid::Uuid::now_v7()));
    let handler_service = Arc::clone(&service);
    let subscriber_handle = tokio::spawn(async move {
        subscriber
            .subscribe(
                streams,
                group,
                consumer_name,
                Box::new(move |envelope| {
                    let service = Arc::clone(&handler_service);
                    Box::pin(async move { service.handle_subscribed_event(envelope).await })
                }),
            )
            .await
            .expect("entitlement-ticketing Redis subscriber stopped");
    });
    Ok((router_with_postgres_state(service), subscriber_handle))
}

pub fn router_with_state<S>(service: Arc<S>) -> Router
where
    S: EntitlementApi + 'static,
{
    let app_state = ApiState { service };
    let routes = Router::new()
        .route(
            "/api/v1/entitlements",
            post(issue_entitlement::<S>).get(list_entitlements::<S>),
        )
        .route(
            "/api/v1/entitlements/{entitlement_id}",
            get(get_entitlement::<S>),
        )
        .route(
            "/api/v1/entitlements/{entitlement_id}/void",
            post(void_entitlement::<S>),
        )
        .with_state(app_state);
    apply_service_runtime(router_with_config(runtime_config()).merge(routes))
}

pub fn router_with_postgres_state(
    service: Arc<adapters::storage::PostgresEntitlementService>,
) -> Router {
    let app_state = ApiState {
        service: service.clone(),
    };
    let routes = Router::new()
        .route(
            "/api/v1/entitlements",
            post(issue_entitlement::<adapters::storage::PostgresEntitlementService>)
                .get(list_entitlements::<adapters::storage::PostgresEntitlementService>),
        )
        .route(
            "/api/v1/entitlements/{entitlement_id}",
            get(get_entitlement::<adapters::storage::PostgresEntitlementService>),
        )
        .route(
            "/api/v1/entitlements/{entitlement_id}/void",
            post(void_entitlement::<adapters::storage::PostgresEntitlementService>),
        )
        .with_state(app_state);
    let metadata = serde_json::to_value(metadata()).unwrap_or_else(|_| serde_json::json!({}));
    let standard_router = Router::new()
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
        .layer(Extension(service));
    apply_service_runtime(Router::new().merge(standard_router).merge(routes))
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
    Extension(service): Extension<Arc<adapters::storage::PostgresEntitlementService>>,
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

pub fn apply_service_runtime(router: Router) -> Router {
    apply_runtime(router, runtime_config())
}

macro_rules! id_type {
    ($name:ident, $kind:literal) => {
        #[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
        pub struct $name(String);

        impl $name {
            pub fn new(value: impl Into<String>) -> Result<Self, EntitlementError> {
                let value = value.into();
                if value.trim().is_empty() {
                    return Err(EntitlementError::BlankReference { kind: $kind });
                }
                Ok(Self(value))
            }

            pub fn as_str(&self) -> &str {
                &self.0
            }
        }

        impl fmt::Display for $name {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str(&self.0)
            }
        }
    };
}

id_type!(EntitlementId, "EntitlementId");
id_type!(JourneyOrderRef, "JourneyOrderRef");
id_type!(SegmentBookingRef, "SegmentBookingRef");
id_type!(SegmentRef, "SegmentRef");
id_type!(TravelerRef, "TravelerRef");
id_type!(BookingFactRef, "BookingFactRef");
id_type!(CapacityFactRef, "CapacityFactRef");
id_type!(PaymentFactRef, "PaymentFactRef");
id_type!(TravelerFactRef, "TravelerFactRef");
id_type!(RiskFactRef, "RiskFactRef");
id_type!(RefundRequestFactRef, "RefundRequestFactRef");
id_type!(FulfillmentFactRef, "FulfillmentFactRef");
id_type!(BusinessCaseRef, "BusinessCaseRef");
id_type!(CredentialId, "CredentialId");
id_type!(ProviderRef, "ProviderRef");
id_type!(CorrelationId, "CorrelationId");
id_type!(CommandId, "CommandId");

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct UnixMillis(u64);

impl UnixMillis {
    pub fn new(value: u64) -> Self {
        Self(value)
    }

    pub fn as_u64(self) -> u64 {
        self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ValidityWindow {
    starts_at: UnixMillis,
    ends_at: UnixMillis,
}

impl ValidityWindow {
    pub fn new(starts_at: UnixMillis, ends_at: UnixMillis) -> Result<Self, EntitlementError> {
        if ends_at <= starts_at {
            return Err(EntitlementError::InvalidValidityWindow);
        }
        Ok(Self { starts_at, ends_at })
    }

    pub fn starts_at(self) -> UnixMillis {
        self.starts_at
    }

    pub fn ends_at(self) -> UnixMillis {
        self.ends_at
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum IssuePurpose {
    Initial,
    Replacement,
    ManualRecovery,
    ProviderRebuild,
    DisruptionReplacement,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct IssueIdempotencyKey {
    segment_booking_ref: SegmentBookingRef,
    traveler_ref: TravelerRef,
    purpose: IssuePurpose,
}

impl IssueIdempotencyKey {
    pub fn new(
        segment_booking_ref: SegmentBookingRef,
        traveler_ref: TravelerRef,
        purpose: IssuePurpose,
    ) -> Self {
        Self {
            segment_booking_ref,
            traveler_ref,
            purpose,
        }
    }

    pub fn segment_booking_ref(&self) -> &SegmentBookingRef {
        &self.segment_booking_ref
    }

    pub fn traveler_ref(&self) -> &TravelerRef {
        &self.traveler_ref
    }

    pub fn purpose(&self) -> &IssuePurpose {
        &self.purpose
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BookingAcceptance {
    SegmentReservationConfirmed,
    SegmentTicketingRequested,
    ReplacementBookingConfirmed,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CapacityAcceptance {
    CapacityCommitted,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PaymentAcceptance {
    PaymentCaptured,
    ZeroAmountAuthorized,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TravelerAcceptance {
    TravelerSnapshotAccepted,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RiskAcceptance {
    Allowed,
    ChallengePassed,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IssuePreconditions {
    pub booking_fact: AcceptedFact<BookingFactRef, BookingAcceptance>,
    pub capacity_fact: AcceptedFact<CapacityFactRef, CapacityAcceptance>,
    pub payment_fact: AcceptedFact<PaymentFactRef, PaymentAcceptance>,
    pub traveler_fact: AcceptedFact<TravelerFactRef, TravelerAcceptance>,
    pub risk_fact: AcceptedFact<RiskFactRef, RiskAcceptance>,
}

impl IssuePreconditions {
    pub fn accepted(
        booking_fact: AcceptedFact<BookingFactRef, BookingAcceptance>,
        capacity_fact: AcceptedFact<CapacityFactRef, CapacityAcceptance>,
        payment_fact: AcceptedFact<PaymentFactRef, PaymentAcceptance>,
        traveler_fact: AcceptedFact<TravelerFactRef, TravelerAcceptance>,
        risk_fact: AcceptedFact<RiskFactRef, RiskAcceptance>,
    ) -> Self {
        Self {
            booking_fact,
            capacity_fact,
            payment_fact,
            traveler_fact,
            risk_fact,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AcceptedFact<Ref, Kind> {
    fact_ref: Ref,
    kind: Kind,
    accepted_at: UnixMillis,
}

impl<Ref, Kind> AcceptedFact<Ref, Kind> {
    pub fn new(fact_ref: Ref, kind: Kind, accepted_at: UnixMillis) -> Self {
        Self {
            fact_ref,
            kind,
            accepted_at,
        }
    }

    pub fn fact_ref(&self) -> &Ref {
        &self.fact_ref
    }

    pub fn kind(&self) -> &Kind {
        &self.kind
    }

    pub fn accepted_at(&self) -> UnixMillis {
        self.accepted_at
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RefundRequestFact {
    pub fact_ref: RefundRequestFactRef,
    pub case_ref: BusinessCaseRef,
    pub accepted_at: UnixMillis,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum CredentialType {
    ETicket,
    PaperTicket,
    PickupCode,
    BoardingPass,
    FerryTicket,
    CoachETicket,
    RideCode,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProviderCredentialRef {
    pub provider_ref: ProviderRef,
    pub confirmation_no: String,
    pub ticket_no: String,
    pub mapped_status: String,
}

impl ProviderCredentialRef {
    pub fn new(
        provider_ref: ProviderRef,
        confirmation_no: impl Into<String>,
        ticket_no: impl Into<String>,
        mapped_status: impl Into<String>,
    ) -> Result<Self, EntitlementError> {
        let confirmation_no = confirmation_no.into();
        let ticket_no = ticket_no.into();
        let mapped_status = mapped_status.into();
        if confirmation_no.trim().is_empty() || ticket_no.trim().is_empty() {
            return Err(EntitlementError::BlankReference {
                kind: "ProviderCredentialRef",
            });
        }
        Ok(Self {
            provider_ref,
            confirmation_no,
            ticket_no,
            mapped_status,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CredentialDisplayReference {
    pub masked_code: String,
    pub display_version: u32,
    pub barcode_format: Option<String>,
}

impl CredentialDisplayReference {
    pub fn new(
        masked_code: impl Into<String>,
        display_version: u32,
        barcode_format: Option<String>,
    ) -> Result<Self, EntitlementError> {
        let masked_code = masked_code.into();
        if masked_code.trim().is_empty() {
            return Err(EntitlementError::BlankReference {
                kind: "CredentialDisplayReference.masked_code",
            });
        }
        if display_version == 0 {
            return Err(EntitlementError::InvalidDisplayVersion);
        }
        Ok(Self {
            masked_code,
            display_version,
            barcode_format,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CredentialRef {
    pub credential_id: CredentialId,
    pub credential_type: CredentialType,
    pub credential_no: String,
    pub provider_credential_ref: Option<ProviderCredentialRef>,
    pub display: CredentialDisplayReference,
}

impl CredentialRef {
    pub fn new(
        credential_id: CredentialId,
        credential_type: CredentialType,
        credential_no: impl Into<String>,
        provider_credential_ref: Option<ProviderCredentialRef>,
        display: CredentialDisplayReference,
    ) -> Result<Self, EntitlementError> {
        let credential_no = credential_no.into();
        if credential_no.trim().is_empty() {
            return Err(EntitlementError::BlankReference {
                kind: "CredentialRef.credential_no",
            });
        }
        Ok(Self {
            credential_id,
            credential_type,
            credential_no,
            provider_credential_ref,
            display,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct CredentialFingerprint {
    provider_scope: Option<ProviderRef>,
    credential_type: CredentialType,
    credential_no: String,
}

impl CredentialFingerprint {
    fn from_ref(credential: &CredentialRef) -> Self {
        Self {
            provider_scope: credential
                .provider_credential_ref
                .as_ref()
                .map(|provider| provider.provider_ref.clone()),
            credential_type: credential.credential_type.clone(),
            credential_no: credential.credential_no.clone(),
        }
    }
}

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct CredentialRegistry {
    bindings: HashMap<CredentialFingerprint, EntitlementId>,
}

impl CredentialRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn register(
        &mut self,
        entitlement_id: &EntitlementId,
        credential: &CredentialRef,
    ) -> Result<CredentialRegistration, EntitlementError> {
        let fingerprint = CredentialFingerprint::from_ref(credential);
        match self.bindings.get(&fingerprint) {
            Some(existing) if existing == entitlement_id => {
                Ok(CredentialRegistration::AlreadyBound)
            }
            Some(existing) => Err(EntitlementError::DuplicateCredential {
                credential_no: credential.credential_no.clone(),
                existing_entitlement_id: existing.clone(),
            }),
            None => {
                self.bindings.insert(fingerprint, entitlement_id.clone());
                Ok(CredentialRegistration::Registered)
            }
        }
    }

    pub fn entitlement_for(&self, credential: &CredentialRef) -> Option<&EntitlementId> {
        self.bindings
            .get(&CredentialFingerprint::from_ref(credential))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CredentialRegistration {
    Registered,
    AlreadyBound,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ActorType {
    System,
    Operator,
    Saga,
    ProviderAdapter,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EntitlementLifecycleAudit {
    pub action: &'static str,
    pub reason: String,
    pub actor_type: ActorType,
    pub actor_id: String,
    pub occurred_at: UnixMillis,
    pub correlation_id: CorrelationId,
}

impl EntitlementLifecycleAudit {
    pub fn new(
        action: &'static str,
        reason: impl Into<String>,
        actor_type: ActorType,
        actor_id: impl Into<String>,
        occurred_at: UnixMillis,
        correlation_id: CorrelationId,
    ) -> Result<Self, EntitlementError> {
        let reason = reason.into();
        let actor_id = actor_id.into();
        if reason.trim().is_empty() {
            return Err(EntitlementError::MissingAuditReason);
        }
        if actor_id.trim().is_empty() {
            return Err(EntitlementError::BlankReference { kind: "actor_id" });
        }
        Ok(Self {
            action,
            reason,
            actor_type,
            actor_id,
            occurred_at,
            correlation_id,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EntitlementStatus {
    Requested,
    Issued,
    IssueFailed { retryable: bool },
    Suspended { previous: Box<EntitlementStatus> },
    Voided,
    Expired,
    NoShow,
}

impl EntitlementStatus {
    pub fn is_terminal(&self) -> bool {
        matches!(self, Self::Voided | Self::Expired | Self::NoShow)
    }

    pub fn is_frozen(&self) -> bool {
        matches!(self, Self::Suspended { .. })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FulfillmentUseState {
    NotUsed,
    CheckInAccepted { fact_ref: FulfillmentFactRef },
    BoardingVerified { fact_ref: FulfillmentFactRef },
    BoardingComplete { fact_ref: FulfillmentFactRef },
    NoShowRecorded { fact_ref: FulfillmentFactRef },
}

impl FulfillmentUseState {
    pub fn blocks_normal_void(&self) -> bool {
        matches!(
            self,
            Self::BoardingVerified { .. } | Self::BoardingComplete { .. }
        )
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entitlement {
    pub(crate) id: EntitlementId,
    pub(crate) journey_order_ref: JourneyOrderRef,
    pub(crate) segment_booking_ref: SegmentBookingRef,
    pub(crate) traveler_ref: TravelerRef,
    pub(crate) segment_ref: SegmentRef,
    pub(crate) purpose: IssuePurpose,
    pub(crate) idempotency_key: IssueIdempotencyKey,
    pub(crate) validity_window: ValidityWindow,
    pub(crate) status: EntitlementStatus,
    pub(crate) credential_ref: Option<CredentialRef>,
    pub(crate) fulfillment_use_state: FulfillmentUseState,
    pub(crate) audit_trail: Vec<EntitlementLifecycleAudit>,
    pub(crate) processed_command_ids: HashSet<CommandId>,
}

impl Entitlement {
    pub fn request(command: RequestEntitlement) -> Result<(Self, DomainEvent), EntitlementError> {
        let idempotency_key = IssueIdempotencyKey::new(
            command.segment_booking_ref.clone(),
            command.traveler_ref.clone(),
            command.purpose.clone(),
        );
        let audit = command.audit.for_action("request")?;
        let entitlement = Self {
            id: command.entitlement_id.clone(),
            journey_order_ref: command.journey_order_ref,
            segment_booking_ref: command.segment_booking_ref,
            traveler_ref: command.traveler_ref,
            segment_ref: command.segment_ref,
            purpose: command.purpose,
            idempotency_key,
            validity_window: command.validity_window,
            status: EntitlementStatus::Requested,
            credential_ref: None,
            fulfillment_use_state: FulfillmentUseState::NotUsed,
            audit_trail: vec![audit.clone()],
            processed_command_ids: HashSet::new(),
        };
        Ok((
            entitlement,
            DomainEvent::EntitlementIssueRequested {
                entitlement_id: command.entitlement_id,
                audit,
            },
        ))
    }

    pub fn id(&self) -> &EntitlementId {
        &self.id
    }

    pub fn status(&self) -> &EntitlementStatus {
        &self.status
    }

    pub fn credential_ref(&self) -> Option<&CredentialRef> {
        self.credential_ref.as_ref()
    }

    pub fn idempotency_key(&self) -> &IssueIdempotencyKey {
        &self.idempotency_key
    }

    pub fn fulfillment_use_state(&self) -> &FulfillmentUseState {
        &self.fulfillment_use_state
    }

    pub fn audit_trail(&self) -> &[EntitlementLifecycleAudit] {
        &self.audit_trail
    }

    pub fn issue(
        &mut self,
        command: IssueEntitlement,
        registry: &mut CredentialRegistry,
    ) -> Result<Vec<DomainEvent>, EntitlementError> {
        self.require_matching_issue_key(&command.idempotency_key)?;

        if self.processed_command_ids.contains(&command.command_id) {
            return Ok(vec![]);
        }

        match &self.status {
            EntitlementStatus::Issued => {
                if self.credential_ref.as_ref() == Some(&command.credential_ref) {
                    self.processed_command_ids
                        .insert(command.command_id.clone());
                    if let Some(refund_fact) = command.refund_request_fact {
                        return self.suspend_for_late_refund(refund_fact, command.audit);
                    }
                    return Ok(vec![]);
                }
                return Err(EntitlementError::DuplicateIssueAttempt);
            }
            EntitlementStatus::IssueFailed { retryable: true } | EntitlementStatus::Requested => {}
            EntitlementStatus::IssueFailed { retryable: false } => {
                return Err(EntitlementError::IssueFailureNotRetryable);
            }
            EntitlementStatus::Suspended { .. } => return Err(EntitlementError::EntitlementFrozen),
            EntitlementStatus::Voided | EntitlementStatus::Expired | EntitlementStatus::NoShow => {
                return Err(EntitlementError::TerminalStatus(self.status.clone()));
            }
        }

        registry.register(&self.id, &command.credential_ref)?;
        let audit = command.audit.for_action("issue")?;
        self.status = EntitlementStatus::Issued;
        self.credential_ref = Some(command.credential_ref.clone());
        self.audit_trail.push(audit.clone());
        self.processed_command_ids
            .insert(command.command_id.clone());

        let mut events = vec![DomainEvent::EntitlementIssued {
            entitlement_id: self.id.clone(),
            journey_order_ref: self.journey_order_ref.clone(),
            segment_booking_ref: self.segment_booking_ref.clone(),
            traveler_ref: self.traveler_ref.clone(),
            segment_ref: self.segment_ref.clone(),
            purpose: self.purpose.clone(),
            credential_ref: command.credential_ref,
            preconditions: command.preconditions,
            audit,
        }];

        if let Some(refund_fact) = command.refund_request_fact {
            events.extend(self.suspend_for_late_refund(refund_fact, command.audit)?);
        }

        Ok(events)
    }

    pub fn fail_issue(
        &mut self,
        command: FailEntitlementIssue,
    ) -> Result<Vec<DomainEvent>, EntitlementError> {
        if self.processed_command_ids.contains(&command.command_id) {
            return Ok(vec![]);
        }
        match &self.status {
            EntitlementStatus::Requested | EntitlementStatus::IssueFailed { retryable: true } => {}
            EntitlementStatus::Issued => return Err(EntitlementError::AlreadyIssued),
            EntitlementStatus::IssueFailed { retryable: false } => {
                return Err(EntitlementError::IssueFailureNotRetryable);
            }
            EntitlementStatus::Suspended { .. } => return Err(EntitlementError::EntitlementFrozen),
            EntitlementStatus::Voided | EntitlementStatus::Expired | EntitlementStatus::NoShow => {
                return Err(EntitlementError::TerminalStatus(self.status.clone()));
            }
        }
        let audit = command.audit.for_action("fail_issue")?;
        self.status = EntitlementStatus::IssueFailed {
            retryable: command.retryable,
        };
        self.audit_trail.push(audit.clone());
        self.processed_command_ids.insert(command.command_id);
        Ok(vec![DomainEvent::EntitlementIssueFailed {
            entitlement_id: self.id.clone(),
            retryable: command.retryable,
            failure_code: command.failure_code,
            failure_message: command.failure_message,
            audit,
        }])
    }

    pub fn suspend(
        &mut self,
        command: SuspendEntitlement,
    ) -> Result<Vec<DomainEvent>, EntitlementError> {
        if self.processed_command_ids.contains(&command.command_id) {
            return Ok(vec![]);
        }
        if matches!(
            self.status,
            EntitlementStatus::Voided | EntitlementStatus::Expired | EntitlementStatus::NoShow
        ) {
            return Err(EntitlementError::TerminalStatus(self.status.clone()));
        }
        if self.status.is_frozen() {
            self.processed_command_ids.insert(command.command_id);
            return Ok(vec![]);
        }
        let audit = command.audit.for_action("suspend")?;
        let previous = self.status.clone();
        self.status = EntitlementStatus::Suspended {
            previous: Box::new(previous),
        };
        self.audit_trail.push(audit.clone());
        self.processed_command_ids.insert(command.command_id);
        Ok(vec![DomainEvent::EntitlementSuspended {
            entitlement_id: self.id.clone(),
            reason: command.reason,
            business_case_ref: command.business_case_ref,
            audit,
        }])
    }

    pub fn resume(
        &mut self,
        command: ResumeEntitlement,
    ) -> Result<Vec<DomainEvent>, EntitlementError> {
        if self.processed_command_ids.contains(&command.command_id) {
            return Ok(vec![]);
        }
        let EntitlementStatus::Suspended { previous } = self.status.clone() else {
            return Err(EntitlementError::NotSuspended);
        };
        if previous.is_terminal() {
            return Err(EntitlementError::TerminalStatus(*previous));
        }
        let audit = command.audit.for_action("resume")?;
        self.status = *previous;
        self.audit_trail.push(audit.clone());
        self.processed_command_ids.insert(command.command_id);
        Ok(vec![DomainEvent::EntitlementResumed {
            entitlement_id: self.id.clone(),
            reason: command.reason,
            business_case_ref: command.business_case_ref,
            audit,
        }])
    }

    pub fn void(&mut self, command: VoidEntitlement) -> Result<Vec<DomainEvent>, EntitlementError> {
        if self.processed_command_ids.contains(&command.command_id) {
            return Ok(vec![]);
        }
        if matches!(self.status, EntitlementStatus::Voided) {
            self.processed_command_ids.insert(command.command_id);
            return Ok(vec![]);
        }
        if matches!(
            self.status,
            EntitlementStatus::Expired | EntitlementStatus::NoShow
        ) || self.fulfillment_use_state.blocks_normal_void()
        {
            return Err(EntitlementError::VoidRequiresCompensation);
        }
        if matches!(
            self.fulfillment_use_state,
            FulfillmentUseState::CheckInAccepted { .. }
        ) && !matches!(command.policy, VoidPolicy::ExceptionalRule)
        {
            return Err(EntitlementError::ExceptionalVoidRuleRequired);
        }
        if matches!(
            self.status,
            EntitlementStatus::Suspended { .. }
                | EntitlementStatus::Issued
                | EntitlementStatus::Requested
                | EntitlementStatus::IssueFailed { .. }
        ) {
            let audit = command.audit.for_action("void")?;
            self.status = EntitlementStatus::Voided;
            self.audit_trail.push(audit.clone());
            self.processed_command_ids.insert(command.command_id);
            return Ok(vec![DomainEvent::EntitlementVoided {
                entitlement_id: self.id.clone(),
                reason: command.reason,
                policy: command.policy,
                business_case_ref: command.business_case_ref,
                credential_ref: self.credential_ref.clone(),
                audit,
            }]);
        }
        Err(EntitlementError::TerminalStatus(self.status.clone()))
    }

    pub fn expire(
        &mut self,
        command: ExpireEntitlement,
    ) -> Result<Vec<DomainEvent>, EntitlementError> {
        if self.processed_command_ids.contains(&command.command_id) {
            return Ok(vec![]);
        }
        if matches!(
            self.status,
            EntitlementStatus::Voided | EntitlementStatus::NoShow
        ) {
            return Err(EntitlementError::TerminalStatus(self.status.clone()));
        }
        if matches!(self.status, EntitlementStatus::Expired) {
            self.processed_command_ids.insert(command.command_id);
            return Ok(vec![]);
        }
        let audit = command.audit.for_action("expire")?;
        self.status = EntitlementStatus::Expired;
        self.audit_trail.push(audit.clone());
        self.processed_command_ids.insert(command.command_id);
        Ok(vec![DomainEvent::EntitlementExpired {
            entitlement_id: self.id.clone(),
            expired_at: command.expired_at,
            audit,
        }])
    }

    pub fn accept_fulfillment_fact(
        &mut self,
        command: AcceptFulfillmentFact,
    ) -> Result<Vec<DomainEvent>, EntitlementError> {
        if self.processed_command_ids.contains(&command.command_id) {
            return Ok(vec![]);
        }
        match self.status {
            EntitlementStatus::Issued => {}
            EntitlementStatus::Suspended { .. } => return Err(EntitlementError::EntitlementFrozen),
            EntitlementStatus::Voided | EntitlementStatus::Expired | EntitlementStatus::NoShow => {
                return Err(EntitlementError::TerminalStatus(self.status.clone()));
            }
            EntitlementStatus::Requested | EntitlementStatus::IssueFailed { .. } => {
                return Err(EntitlementError::NotIssued);
            }
        }
        let audit = command.audit.for_action("accept_fulfillment_fact")?;
        self.processed_command_ids.insert(command.command_id);
        self.audit_trail.push(audit.clone());
        match command.fact {
            FulfillmentFact::CheckInSucceeded { fact_ref } => {
                self.fulfillment_use_state = FulfillmentUseState::CheckInAccepted {
                    fact_ref: fact_ref.clone(),
                };
                Ok(vec![DomainEvent::FulfillmentUseFactAccepted {
                    entitlement_id: self.id.clone(),
                    fact_ref,
                    use_state: self.fulfillment_use_state.clone(),
                    audit,
                }])
            }
            FulfillmentFact::BoardingVerified { fact_ref } => {
                self.fulfillment_use_state = FulfillmentUseState::BoardingVerified {
                    fact_ref: fact_ref.clone(),
                };
                Ok(vec![DomainEvent::FulfillmentUseFactAccepted {
                    entitlement_id: self.id.clone(),
                    fact_ref,
                    use_state: self.fulfillment_use_state.clone(),
                    audit,
                }])
            }
            FulfillmentFact::BoardingComplete { fact_ref } => {
                if !matches!(
                    self.fulfillment_use_state,
                    FulfillmentUseState::BoardingVerified { .. }
                ) {
                    return Err(EntitlementError::BoardingRequiredForCompletion);
                }
                self.fulfillment_use_state = FulfillmentUseState::BoardingComplete {
                    fact_ref: fact_ref.clone(),
                };
                Ok(vec![DomainEvent::FulfillmentUseFactAccepted {
                    entitlement_id: self.id.clone(),
                    fact_ref,
                    use_state: self.fulfillment_use_state.clone(),
                    audit,
                }])
            }
            FulfillmentFact::NoShowRecorded { fact_ref } => {
                if !matches!(
                    self.fulfillment_use_state,
                    FulfillmentUseState::NotUsed | FulfillmentUseState::CheckInAccepted { .. }
                ) {
                    return Err(EntitlementError::NoShowAfterBoarding);
                }
                self.fulfillment_use_state = FulfillmentUseState::NoShowRecorded {
                    fact_ref: fact_ref.clone(),
                };
                self.status = EntitlementStatus::NoShow;
                Ok(vec![DomainEvent::FulfillmentUseFactAccepted {
                    entitlement_id: self.id.clone(),
                    fact_ref,
                    use_state: self.fulfillment_use_state.clone(),
                    audit,
                }])
            }
        }
    }

    fn require_matching_issue_key(
        &self,
        key: &IssueIdempotencyKey,
    ) -> Result<(), EntitlementError> {
        if &self.idempotency_key == key {
            Ok(())
        } else {
            Err(EntitlementError::IdempotencyKeyMismatch)
        }
    }

    fn suspend_for_late_refund(
        &mut self,
        refund_fact: RefundRequestFact,
        audit_builder: AuditBuilder,
    ) -> Result<Vec<DomainEvent>, EntitlementError> {
        if self.status.is_frozen() {
            return Ok(vec![]);
        }
        let audit = audit_builder.for_action("suspend_after_late_refund")?;
        let previous = self.status.clone();
        self.status = EntitlementStatus::Suspended {
            previous: Box::new(previous),
        };
        self.audit_trail.push(audit.clone());
        Ok(vec![DomainEvent::EntitlementSuspended {
            entitlement_id: self.id.clone(),
            reason: SuspendReason::IssuedAfterRefundRequested,
            business_case_ref: refund_fact.case_ref,
            audit,
        }])
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AuditBuilder {
    pub reason: String,
    pub actor_type: ActorType,
    pub actor_id: String,
    pub occurred_at: UnixMillis,
    pub correlation_id: CorrelationId,
}

impl AuditBuilder {
    pub fn system(
        reason: impl Into<String>,
        occurred_at: UnixMillis,
        correlation_id: CorrelationId,
    ) -> Self {
        Self {
            reason: reason.into(),
            actor_type: ActorType::System,
            actor_id: "system".to_string(),
            occurred_at,
            correlation_id,
        }
    }

    fn for_action(
        &self,
        action: &'static str,
    ) -> Result<EntitlementLifecycleAudit, EntitlementError> {
        EntitlementLifecycleAudit::new(
            action,
            self.reason.clone(),
            self.actor_type.clone(),
            self.actor_id.clone(),
            self.occurred_at,
            self.correlation_id.clone(),
        )
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RequestEntitlement {
    pub command_id: CommandId,
    pub entitlement_id: EntitlementId,
    pub journey_order_ref: JourneyOrderRef,
    pub segment_booking_ref: SegmentBookingRef,
    pub traveler_ref: TravelerRef,
    pub segment_ref: SegmentRef,
    pub purpose: IssuePurpose,
    pub validity_window: ValidityWindow,
    pub audit: AuditBuilder,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IssueEntitlement {
    pub command_id: CommandId,
    pub idempotency_key: IssueIdempotencyKey,
    pub preconditions: IssuePreconditions,
    pub credential_ref: CredentialRef,
    pub refund_request_fact: Option<RefundRequestFact>,
    pub audit: AuditBuilder,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FailEntitlementIssue {
    pub command_id: CommandId,
    pub retryable: bool,
    pub failure_code: String,
    pub failure_message: String,
    pub audit: AuditBuilder,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SuspendReason {
    Risk,
    ProviderConflict,
    ManualReview,
    Disruption,
    IssuedAfterRefundRequested,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SuspendEntitlement {
    pub command_id: CommandId,
    pub reason: SuspendReason,
    pub business_case_ref: BusinessCaseRef,
    pub audit: AuditBuilder,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ResumeReason {
    RiskCleared,
    ProviderConflictResolved,
    ManualApproved,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResumeEntitlement {
    pub command_id: CommandId,
    pub reason: ResumeReason,
    pub business_case_ref: BusinessCaseRef,
    pub audit: AuditBuilder,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VoidReason {
    Refund,
    Change,
    Disruption,
    Risk,
    ManualCorrection,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VoidPolicy {
    Normal,
    ExceptionalRule,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VoidEntitlement {
    pub command_id: CommandId,
    pub reason: VoidReason,
    pub policy: VoidPolicy,
    pub business_case_ref: BusinessCaseRef,
    pub audit: AuditBuilder,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExpireEntitlement {
    pub command_id: CommandId,
    pub expired_at: UnixMillis,
    pub audit: AuditBuilder,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FulfillmentFact {
    CheckInSucceeded { fact_ref: FulfillmentFactRef },
    BoardingVerified { fact_ref: FulfillmentFactRef },
    BoardingComplete { fact_ref: FulfillmentFactRef },
    NoShowRecorded { fact_ref: FulfillmentFactRef },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AcceptFulfillmentFact {
    pub command_id: CommandId,
    pub fact: FulfillmentFact,
    pub audit: AuditBuilder,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DomainEvent {
    EntitlementIssueRequested {
        entitlement_id: EntitlementId,
        audit: EntitlementLifecycleAudit,
    },
    EntitlementIssued {
        entitlement_id: EntitlementId,
        journey_order_ref: JourneyOrderRef,
        segment_booking_ref: SegmentBookingRef,
        traveler_ref: TravelerRef,
        segment_ref: SegmentRef,
        purpose: IssuePurpose,
        credential_ref: CredentialRef,
        preconditions: IssuePreconditions,
        audit: EntitlementLifecycleAudit,
    },
    EntitlementIssueFailed {
        entitlement_id: EntitlementId,
        retryable: bool,
        failure_code: String,
        failure_message: String,
        audit: EntitlementLifecycleAudit,
    },
    EntitlementSuspended {
        entitlement_id: EntitlementId,
        reason: SuspendReason,
        business_case_ref: BusinessCaseRef,
        audit: EntitlementLifecycleAudit,
    },
    EntitlementResumed {
        entitlement_id: EntitlementId,
        reason: ResumeReason,
        business_case_ref: BusinessCaseRef,
        audit: EntitlementLifecycleAudit,
    },
    EntitlementVoided {
        entitlement_id: EntitlementId,
        reason: VoidReason,
        policy: VoidPolicy,
        business_case_ref: BusinessCaseRef,
        credential_ref: Option<CredentialRef>,
        audit: EntitlementLifecycleAudit,
    },
    EntitlementExpired {
        entitlement_id: EntitlementId,
        expired_at: UnixMillis,
        audit: EntitlementLifecycleAudit,
    },
    FulfillmentUseFactAccepted {
        entitlement_id: EntitlementId,
        fact_ref: FulfillmentFactRef,
        use_state: FulfillmentUseState,
        audit: EntitlementLifecycleAudit,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EntitlementError {
    BlankReference {
        kind: &'static str,
    },
    InvalidValidityWindow,
    InvalidDisplayVersion,
    MissingAuditReason,
    IdempotencyKeyMismatch,
    DuplicateIssueAttempt,
    DuplicateCredential {
        credential_no: String,
        existing_entitlement_id: EntitlementId,
    },
    IssueFailureNotRetryable,
    AlreadyIssued,
    EntitlementFrozen,
    NotSuspended,
    NotIssued,
    TerminalStatus(EntitlementStatus),
    ExceptionalVoidRuleRequired,
    VoidRequiresCompensation,
    BoardingRequiredForCompletion,
    NoShowAfterBoarding,
}

impl fmt::Display for EntitlementError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::BlankReference { kind } => write!(f, "{kind} must not be blank"),
            Self::InvalidValidityWindow => write!(f, "validity window end must be after start"),
            Self::InvalidDisplayVersion => write!(f, "display version must be greater than zero"),
            Self::MissingAuditReason => write!(f, "status changes require an audit reason"),
            Self::IdempotencyKeyMismatch => {
                write!(f, "issue idempotency key does not match entitlement")
            }
            Self::DuplicateIssueAttempt => write!(
                f,
                "entitlement is already issued with a different credential"
            ),
            Self::DuplicateCredential {
                credential_no,
                existing_entitlement_id,
            } => write!(
                f,
                "credential {credential_no} is already bound to entitlement {existing_entitlement_id}"
            ),
            Self::IssueFailureNotRetryable => write!(f, "previous issue failure is not retryable"),
            Self::AlreadyIssued => write!(f, "entitlement is already issued"),
            Self::EntitlementFrozen => write!(f, "entitlement is suspended/frozen"),
            Self::NotSuspended => write!(f, "entitlement is not suspended"),
            Self::NotIssued => write!(f, "entitlement is not issued"),
            Self::TerminalStatus(status) => write!(
                f,
                "terminal status does not allow this transition: {status:?}"
            ),
            Self::ExceptionalVoidRuleRequired => {
                write!(f, "void after check-in requires an exceptional rule")
            }
            Self::VoidRequiresCompensation => write!(
                f,
                "used or boarding-complete entitlements require compensation instead of normal void"
            ),
            Self::BoardingRequiredForCompletion => {
                write!(f, "segment completion requires prior boarding verification")
            }
            Self::NoShowAfterBoarding => write!(f, "no-show cannot be recorded after boarding"),
        }
    }
}

impl std::error::Error for EntitlementError {}

#[cfg(test)]
mod tests {
    use super::*;

    fn now() -> UnixMillis {
        UnixMillis::new(1_800_000_000_000)
    }

    fn audit(reason: &str) -> AuditBuilder {
        AuditBuilder::system(reason, now(), CorrelationId::new("corr-1").unwrap())
    }

    fn request_command(id: &str, traveler: &str, booking: &str) -> RequestEntitlement {
        RequestEntitlement {
            command_id: CommandId::new(format!("cmd-request-{id}")).unwrap(),
            entitlement_id: EntitlementId::new(id).unwrap(),
            journey_order_ref: JourneyOrderRef::new("order-1").unwrap(),
            segment_booking_ref: SegmentBookingRef::new(booking).unwrap(),
            traveler_ref: TravelerRef::new(traveler).unwrap(),
            segment_ref: SegmentRef::new("seg-1").unwrap(),
            purpose: IssuePurpose::Initial,
            validity_window: ValidityWindow::new(now(), UnixMillis::new(1_800_003_600_000))
                .unwrap(),
            audit: audit("booking requested ticket issue"),
        }
    }

    fn requested(id: &str, traveler: &str, booking: &str) -> Entitlement {
        Entitlement::request(request_command(id, traveler, booking))
            .unwrap()
            .0
    }

    fn preconditions() -> IssuePreconditions {
        IssuePreconditions::accepted(
            AcceptedFact::new(
                BookingFactRef::new("booking-fact-1").unwrap(),
                BookingAcceptance::SegmentReservationConfirmed,
                now(),
            ),
            AcceptedFact::new(
                CapacityFactRef::new("capacity-fact-1").unwrap(),
                CapacityAcceptance::CapacityCommitted,
                now(),
            ),
            AcceptedFact::new(
                PaymentFactRef::new("payment-fact-1").unwrap(),
                PaymentAcceptance::PaymentCaptured,
                now(),
            ),
            AcceptedFact::new(
                TravelerFactRef::new("traveler-fact-1").unwrap(),
                TravelerAcceptance::TravelerSnapshotAccepted,
                now(),
            ),
            AcceptedFact::new(
                RiskFactRef::new("risk-fact-1").unwrap(),
                RiskAcceptance::Allowed,
                now(),
            ),
        )
    }

    fn credential(no: &str) -> CredentialRef {
        CredentialRef::new(
            CredentialId::new(format!("cred-{no}")).unwrap(),
            CredentialType::ETicket,
            no,
            Some(
                ProviderCredentialRef::new(
                    ProviderRef::new("provider-rail").unwrap(),
                    "confirm-1",
                    no,
                    "issued",
                )
                .unwrap(),
            ),
            CredentialDisplayReference::new(format!("****{no}"), 1, Some("QR".to_string()))
                .unwrap(),
        )
        .unwrap()
    }

    fn issue_command(entitlement: &Entitlement, no: &str, command_id: &str) -> IssueEntitlement {
        IssueEntitlement {
            command_id: CommandId::new(command_id).unwrap(),
            idempotency_key: entitlement.idempotency_key().clone(),
            preconditions: preconditions(),
            credential_ref: credential(no),
            refund_request_fact: None,
            audit: audit("all issue preconditions accepted"),
        }
    }

    #[test]
    fn skeleton_profile_matches_domain() {
        let profile = profile();
        assert_eq!(profile.service_id, "entitlement-ticketing");
        assert_eq!(profile.domain, "Entitlement & Ticketing");
        assert_eq!(profile.work_packages, &["REQ-013"]);
        assert!(
            profile
                .owns
                .iter()
                .any(|entry| entry.contains("Entitlement lifecycle"))
        );
        assert_eq!(health(), "ok");
    }

    #[test]
    fn axum_router_can_be_constructed() {
        let _router = router_with_state(Arc::new(InMemoryEntitlementService::default()));
    }

    #[tokio::test]
    async fn standard_runtime_endpoints_and_request_ids_are_available() {
        use axum::body::Body;
        use axum::http::{Request, StatusCode};
        use shared_kernel::{CORRELATION_ID_HEADER, REQUEST_ID_HEADER};
        use tower::ServiceExt;

        for path in [
            "/health",
            "/live",
            "/livez",
            "/ready",
            "/readyz",
            "/metadata",
        ] {
            let response = router_with_state(Arc::new(InMemoryEntitlementService::default()))
                .oneshot(
                    Request::builder()
                        .uri(path)
                        .header(REQUEST_ID_HEADER, "entitlement-req")
                        .header(CORRELATION_ID_HEADER, "entitlement-corr")
                        .body(Body::empty())
                        .unwrap(),
                )
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::OK, "{path}");
            assert_eq!(response.headers()[REQUEST_ID_HEADER], "entitlement-req");
            assert_eq!(
                response.headers()[CORRELATION_ID_HEADER],
                "entitlement-corr"
            );
        }
    }

    #[test]
    fn issuance_requires_accepted_external_fact_references_without_owning_upstream_state() {
        let mut entitlement = requested("ent-1", "traveler-1", "booking-1");
        let mut registry = CredentialRegistry::new();
        let events = entitlement
            .issue(
                issue_command(&entitlement, "TICKET-1", "cmd-issue-1"),
                &mut registry,
            )
            .unwrap();

        assert_eq!(entitlement.status(), &EntitlementStatus::Issued);
        assert!(
            matches!(events.as_slice(), [DomainEvent::EntitlementIssued { preconditions, .. }] if preconditions.payment_fact.kind() == &PaymentAcceptance::PaymentCaptured)
        );
        assert_eq!(
            registry.entitlement_for(entitlement.credential_ref().unwrap()),
            Some(entitlement.id())
        );
    }

    #[test]
    fn idempotent_issue_returns_no_duplicate_event_for_same_command_or_same_credential() {
        let mut entitlement = requested("ent-2", "traveler-1", "booking-1");
        let mut registry = CredentialRegistry::new();
        let first = issue_command(&entitlement, "TICKET-2", "cmd-issue-2");
        assert_eq!(
            entitlement
                .issue(first.clone(), &mut registry)
                .unwrap()
                .len(),
            1
        );
        assert!(entitlement.issue(first, &mut registry).unwrap().is_empty());

        let same_business_retry = issue_command(&entitlement, "TICKET-2", "cmd-issue-2-retry");
        assert!(
            entitlement
                .issue(same_business_retry, &mut registry)
                .unwrap()
                .is_empty()
        );
    }

    #[test]
    fn credential_registry_prevents_duplicate_credentials_across_entitlements() {
        let mut first = requested("ent-3a", "traveler-1", "booking-1");
        let mut second = requested("ent-3b", "traveler-2", "booking-2");
        let mut registry = CredentialRegistry::new();
        first
            .issue(
                issue_command(&first, "DUPLICATE", "cmd-issue-3a"),
                &mut registry,
            )
            .unwrap();

        let error = second
            .issue(
                issue_command(&second, "DUPLICATE", "cmd-issue-3b"),
                &mut registry,
            )
            .unwrap_err();
        assert!(matches!(
            error,
            EntitlementError::DuplicateCredential { .. }
        ));
    }

    #[test]
    fn retryable_issue_failure_can_be_reissued_but_non_retryable_cannot() {
        let mut retryable = requested("ent-4a", "traveler-1", "booking-1");
        retryable
            .fail_issue(FailEntitlementIssue {
                command_id: CommandId::new("cmd-fail-4a").unwrap(),
                retryable: true,
                failure_code: "PROVIDER_TIMEOUT".to_string(),
                failure_message: "unknown provider state".to_string(),
                audit: audit("provider timeout can be retried"),
            })
            .unwrap();
        assert_eq!(
            retryable.status(),
            &EntitlementStatus::IssueFailed { retryable: true }
        );
        let mut registry = CredentialRegistry::new();
        retryable
            .issue(
                issue_command(&retryable, "TICKET-4", "cmd-issue-4a"),
                &mut registry,
            )
            .unwrap();
        assert_eq!(retryable.status(), &EntitlementStatus::Issued);

        let mut non_retryable = requested("ent-4b", "traveler-1", "booking-1");
        non_retryable
            .fail_issue(FailEntitlementIssue {
                command_id: CommandId::new("cmd-fail-4b").unwrap(),
                retryable: false,
                failure_code: "RISK_BLOCKED".to_string(),
                failure_message: "risk denied issue".to_string(),
                audit: audit("risk block is final for this issue"),
            })
            .unwrap();
        let error = non_retryable
            .issue(
                issue_command(&non_retryable, "TICKET-4B", "cmd-issue-4b"),
                &mut registry,
            )
            .unwrap_err();
        assert_eq!(error, EntitlementError::IssueFailureNotRetryable);
    }

    #[test]
    fn suspend_freezes_and_resume_restores_previous_status() {
        let mut entitlement = requested("ent-5", "traveler-1", "booking-1");
        let mut registry = CredentialRegistry::new();
        entitlement
            .issue(
                issue_command(&entitlement, "TICKET-5", "cmd-issue-5"),
                &mut registry,
            )
            .unwrap();
        entitlement
            .suspend(SuspendEntitlement {
                command_id: CommandId::new("cmd-suspend-5").unwrap(),
                reason: SuspendReason::Risk,
                business_case_ref: BusinessCaseRef::new("risk-case-5").unwrap(),
                audit: audit("risk review opened"),
            })
            .unwrap();
        assert!(entitlement.status().is_frozen());
        assert!(
            entitlement
                .accept_fulfillment_fact(AcceptFulfillmentFact {
                    command_id: CommandId::new("cmd-board-5").unwrap(),
                    fact: FulfillmentFact::BoardingVerified {
                        fact_ref: FulfillmentFactRef::new("boarding-fact-5").unwrap(),
                    },
                    audit: audit("gate accepted boarding fact"),
                })
                .is_err()
        );

        entitlement
            .resume(ResumeEntitlement {
                command_id: CommandId::new("cmd-resume-5").unwrap(),
                reason: ResumeReason::RiskCleared,
                business_case_ref: BusinessCaseRef::new("risk-case-5").unwrap(),
                audit: audit("risk cleared"),
            })
            .unwrap();
        assert_eq!(entitlement.status(), &EntitlementStatus::Issued);
    }

    #[test]
    fn normal_void_is_rejected_after_boarding_facts() {
        let mut entitlement = requested("ent-6", "traveler-1", "booking-1");
        let mut registry = CredentialRegistry::new();
        entitlement
            .issue(
                issue_command(&entitlement, "TICKET-6", "cmd-issue-6"),
                &mut registry,
            )
            .unwrap();
        entitlement
            .accept_fulfillment_fact(AcceptFulfillmentFact {
                command_id: CommandId::new("cmd-board-6").unwrap(),
                fact: FulfillmentFact::BoardingVerified {
                    fact_ref: FulfillmentFactRef::new("boarding-fact-6").unwrap(),
                },
                audit: audit("external fulfillment reported boarding"),
            })
            .unwrap();

        let error = entitlement
            .void(VoidEntitlement {
                command_id: CommandId::new("cmd-void-6").unwrap(),
                reason: VoidReason::Refund,
                policy: VoidPolicy::Normal,
                business_case_ref: BusinessCaseRef::new("refund-case-6").unwrap(),
                audit: audit("refund approved"),
            })
            .unwrap_err();
        assert_eq!(error, EntitlementError::VoidRequiresCompensation);
    }

    #[test]
    fn checked_in_entitlement_requires_exceptional_rule_to_void() {
        let mut entitlement = requested("ent-7", "traveler-1", "booking-1");
        let mut registry = CredentialRegistry::new();
        entitlement
            .issue(
                issue_command(&entitlement, "TICKET-7", "cmd-issue-7"),
                &mut registry,
            )
            .unwrap();
        entitlement
            .accept_fulfillment_fact(AcceptFulfillmentFact {
                command_id: CommandId::new("cmd-checkin-7").unwrap(),
                fact: FulfillmentFact::CheckInSucceeded {
                    fact_ref: FulfillmentFactRef::new("checkin-fact-7").unwrap(),
                },
                audit: audit("external check-in accepted"),
            })
            .unwrap();

        assert_eq!(
            entitlement
                .void(VoidEntitlement {
                    command_id: CommandId::new("cmd-void-7-normal").unwrap(),
                    reason: VoidReason::Refund,
                    policy: VoidPolicy::Normal,
                    business_case_ref: BusinessCaseRef::new("refund-case-7").unwrap(),
                    audit: audit("refund approved"),
                })
                .unwrap_err(),
            EntitlementError::ExceptionalVoidRuleRequired
        );

        let events = entitlement
            .void(VoidEntitlement {
                command_id: CommandId::new("cmd-void-7-exception").unwrap(),
                reason: VoidReason::Disruption,
                policy: VoidPolicy::ExceptionalRule,
                business_case_ref: BusinessCaseRef::new("disruption-case-7").unwrap(),
                audit: audit("disruption rule permits post check-in void"),
            })
            .unwrap();
        assert_eq!(entitlement.status(), &EntitlementStatus::Voided);
        assert!(matches!(
            events.as_slice(),
            [DomainEvent::EntitlementVoided { .. }]
        ));
    }

    #[test]
    fn late_issue_after_refund_request_suspends_for_compensation_instead_of_silent_success() {
        let mut entitlement = requested("ent-8", "traveler-1", "booking-1");
        let mut registry = CredentialRegistry::new();
        let mut command = issue_command(&entitlement, "TICKET-8", "cmd-issue-8");
        command.refund_request_fact = Some(RefundRequestFact {
            fact_ref: RefundRequestFactRef::new("refund-fact-8").unwrap(),
            case_ref: BusinessCaseRef::new("refund-case-8").unwrap(),
            accepted_at: now(),
        });

        let events = entitlement.issue(command, &mut registry).unwrap();
        assert!(entitlement.status().is_frozen());
        assert!(matches!(
            events.as_slice(),
            [
                DomainEvent::EntitlementIssued { .. },
                DomainEvent::EntitlementSuspended {
                    reason: SuspendReason::IssuedAfterRefundRequested,
                    ..
                }
            ]
        ));
    }

    #[test]
    fn expiry_is_terminal_for_normal_void() {
        let mut entitlement = requested("ent-9", "traveler-1", "booking-1");
        entitlement
            .expire(ExpireEntitlement {
                command_id: CommandId::new("cmd-expire-9").unwrap(),
                expired_at: UnixMillis::new(1_800_003_600_000),
                audit: audit("validity window elapsed"),
            })
            .unwrap();
        assert_eq!(entitlement.status(), &EntitlementStatus::Expired);
        assert!(matches!(
            entitlement
                .void(VoidEntitlement {
                    command_id: CommandId::new("cmd-void-9").unwrap(),
                    reason: VoidReason::Refund,
                    policy: VoidPolicy::Normal,
                    business_case_ref: BusinessCaseRef::new("refund-case-9").unwrap(),
                    audit: audit("refund approved"),
                })
                .unwrap_err(),
            EntitlementError::VoidRequiresCompensation
        ));
    }
}
// ---------------------------------------------------------------------------
// HTTP API and in-memory application service
// ---------------------------------------------------------------------------

pub struct ApiState<S: EntitlementApi + 'static> {
    service: Arc<S>,
}

impl<S: EntitlementApi + 'static> Clone for ApiState<S> {
    fn clone(&self) -> Self {
        Self {
            service: Arc::clone(&self.service),
        }
    }
}

#[async_trait::async_trait]
pub trait EntitlementApi: Send + Sync {
    async fn issue(
        &self,
        command: IssueEntitlementRequest,
        key: String,
        correlation_id: String,
    ) -> ApiResult<IssueEntitlementResponse>;
    async fn void(
        &self,
        entitlement_id: String,
        command: VoidEntitlementRequest,
        key: String,
        correlation_id: String,
    ) -> ApiResult<VoidEntitlementResponse>;
    async fn get(&self, entitlement_id: String) -> ApiResult<EntitlementDetails>;
    async fn list(
        &self,
        journey_order_id: String,
        limit: usize,
        offset: usize,
    ) -> ApiResult<PaginatedEntitlements>;
}

type ApiResult<T> = Result<T, ApiErrorKind>;

#[derive(Debug, Clone)]
pub enum ApiErrorKind {
    ValidationFailed(String),
    NotFound(String),
    Conflict(String),
    IdempotencyKeyReused(String),
    PreconditionFailed(String),
    DomainRuleViolation(String),
    Unavailable(String),
}

impl ApiErrorKind {
    fn status(&self) -> StatusCode {
        match self {
            Self::ValidationFailed(_) => StatusCode::BAD_REQUEST,
            Self::NotFound(_) => StatusCode::NOT_FOUND,
            Self::Conflict(_) => StatusCode::CONFLICT,
            Self::IdempotencyKeyReused(_) => StatusCode::UNPROCESSABLE_ENTITY,
            Self::PreconditionFailed(_) => StatusCode::PRECONDITION_FAILED,
            Self::DomainRuleViolation(_) => StatusCode::UNPROCESSABLE_ENTITY,
            Self::Unavailable(_) => StatusCode::SERVICE_UNAVAILABLE,
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
            Self::Unavailable(_) => "UNAVAILABLE",
        }
    }

    fn message(&self) -> &str {
        match self {
            Self::ValidationFailed(message)
            | Self::NotFound(message)
            | Self::Conflict(message)
            | Self::IdempotencyKeyReused(message)
            | Self::PreconditionFailed(message)
            | Self::DomainRuleViolation(message)
            | Self::Unavailable(message) => message,
        }
    }
}

fn api_error_response(error: ApiErrorKind, correlation_id: String) -> Response {
    kit_http::error_response(
        error.status(),
        error.code(),
        error.message().to_string(),
        correlation_id,
        Some(json!({})),
    )
}

fn validation_error(correlation_id: String, message: impl Into<String>) -> Response {
    kit_http::error_response(
        StatusCode::BAD_REQUEST,
        "VALIDATION_FAILED",
        message.into(),
        correlation_id,
        Some(json!({})),
    )
}

fn idempotency_key(headers: &HeaderMap) -> Result<String, kit_idempotency::IdempotencyError> {
    kit_idempotency::require_idempotency_key(headers)
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct IssueEntitlementRequest {
    pub segment_booking_id: String,
    pub journey_order_id: String,
    pub traveler_ref: String,
    pub segment_ref: String,
    pub issue_purpose: IssuePurposeDto,
    #[serde(default)]
    pub seat_preferences: Option<SeatPreferencesDto>,
    #[serde(default)]
    pub scheduled_service_ref: Option<String>,
    #[serde(default)]
    pub service_date: Option<String>,
    #[serde(default)]
    pub capacity_hold_id: Option<String>,
    #[serde(default)]
    pub capacity_unit_ref: Option<String>,
    #[serde(default)]
    pub interval: Option<StationIntervalDto>,
    #[serde(default)]
    pub class_ref: Option<String>,
    #[serde(default)]
    pub expires_at: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StationIntervalDto {
    pub from_seq: i32,
    pub to_seq: i32,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SeatPreferencesDto {
    pub accept_standing: bool,
    #[serde(default)]
    pub adjacency_preference: Option<AdjacencyPreferenceDto>,
    #[serde(default)]
    pub adjacency_group_ref: Option<String>,
    #[serde(default)]
    pub preferred_seat_positions: Option<Vec<SeatPositionDto>>,
    #[serde(default)]
    pub preferred_berth_positions: Option<Vec<BerthPositionDto>>,
    #[serde(default)]
    pub same_compartment: Option<bool>,
    #[serde(default)]
    pub avoid_seat_unit_refs: Option<Vec<String>>,
    pub preference_version: String,
}
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AdjacencyPreferenceDto {
    None,
    SameCoach,
    SameRow,
    Adjacent,
    SameCompartment,
}
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SeatPositionDto {
    Window,
    Aisle,
    Middle,
    LowerDeck,
    UpperDeck,
}
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum BerthPositionDto {
    Upper,
    Middle,
    Lower,
    SideUpper,
    SideLower,
}
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SeatRefDto {
    pub seat_allocation_id: String,
    pub allocation_type: AllocationTypeDto,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seat_map_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seat_map_version: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seat_unit_ref: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub coach_no: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seat_no: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub berth_position: Option<BerthPositionDto>,
    pub display_label: String,
    pub degraded: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub degradation_reason: Option<DegradationReasonDto>,
}
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AllocationTypeDto {
    Seat,
    Berth,
    Standing,
}
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DegradationReasonDto {
    None,
    NoAdjacentBlock,
    ClassMismatch,
    IntervalConflict,
    BerthPreferenceUnavailable,
    StandingAssigned,
    PolicyLimit,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum IssuePurposeDto {
    Initial,
    Replacement,
    ManualRecovery,
    ProviderRebuild,
    DisruptionReplacement,
}

impl From<IssuePurposeDto> for IssuePurpose {
    fn from(value: IssuePurposeDto) -> Self {
        match value {
            IssuePurposeDto::Initial => Self::Initial,
            IssuePurposeDto::Replacement => Self::Replacement,
            IssuePurposeDto::ManualRecovery => Self::ManualRecovery,
            IssuePurposeDto::ProviderRebuild => Self::ProviderRebuild,
            IssuePurposeDto::DisruptionReplacement => Self::DisruptionReplacement,
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct VoidEntitlementRequest {
    pub reason: VoidReasonDto,
    pub policy: VoidPolicyDto,
    pub business_case_ref: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum VoidReasonDto {
    Refund,
    Change,
    Disruption,
    Risk,
    ManualCorrection,
}

impl From<VoidReasonDto> for VoidReason {
    fn from(value: VoidReasonDto) -> Self {
        match value {
            VoidReasonDto::Refund => Self::Refund,
            VoidReasonDto::Change => Self::Change,
            VoidReasonDto::Disruption => Self::Disruption,
            VoidReasonDto::Risk => Self::Risk,
            VoidReasonDto::ManualCorrection => Self::ManualCorrection,
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum VoidPolicyDto {
    Normal,
    ExceptionalRule,
}

impl From<VoidPolicyDto> for VoidPolicy {
    fn from(value: VoidPolicyDto) -> Self {
        match value {
            VoidPolicyDto::Normal => Self::Normal,
            VoidPolicyDto::ExceptionalRule => Self::ExceptionalRule,
        }
    }
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct EntitlementIssuedPayload {
    entitlement_id: String,
    segment_booking_id: String,
    journey_order_id: String,
    traveler_ref: String,
    segment_ref: String,
    issue_purpose: &'static str,
    credential_no: String,
    credential_type: &'static str,
    issued_at: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    seat_ref: Option<SeatRefDto>,
    #[serde(skip_serializing_if = "Option::is_none")]
    seat_allocation_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct EntitlementVoidedPayload {
    entitlement_id: String,
    segment_booking_id: String,
    references: EntitlementVoidedReferences,
    voided_at: String,
    reason: &'static str,
    policy: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    business_case_ref: Option<String>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct EntitlementVoidedReferences {
    segment_booking_ref: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    order_ref: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    traveler_ref: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct IssueEntitlementResponse {
    pub entitlement_id: String,
    pub segment_booking_id: String,
    pub journey_order_id: String,
    pub credential_no: String,
    pub credential_type: CredentialTypeDto,
    pub status: EntitlementStatusDto,
    pub issued_at: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seat_ref: Option<SeatRefDto>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct VoidEntitlementResponse {
    pub entitlement_id: String,
    pub status: EntitlementStatusDto,
    pub voided_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct EntitlementDetails {
    pub entitlement_id: String,
    pub segment_booking_id: String,
    pub journey_order_id: String,
    pub traveler_ref: String,
    pub segment_ref: String,
    pub credential_no: String,
    pub credential_type: CredentialTypeDto,
    pub status: EntitlementStatusDto,
    pub issued_at: String,
    pub voided_at: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seat_ref: Option<SeatRefDto>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PaginatedEntitlements {
    pub items: Vec<EntitlementDetails>,
    pub total: usize,
    pub limit: usize,
    pub offset: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CredentialTypeDto {
    ETicket,
    PaperTicket,
    PickupCode,
    BoardingPass,
    FerryTicket,
    CoachETicket,
    RideCode,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum EntitlementStatusDto {
    Issued,
    Voided,
    Boarded,
    NoShow,
    Suspended,
}

impl IssuePurposeDto {
    pub(crate) fn to_contract(&self) -> &'static str {
        match self {
            Self::Initial => "INITIAL",
            Self::Replacement => "REPLACEMENT",
            Self::ManualRecovery => "MANUAL_RECOVERY",
            Self::ProviderRebuild => "PROVIDER_REBUILD",
            Self::DisruptionReplacement => "DISRUPTION_REPLACEMENT",
        }
    }
}

async fn allocate_seat_for_issue(
    command: &IssueEntitlementRequest,
    key: &str,
    correlation_id: &str,
) -> ApiResult<Option<SeatRefDto>> {
    let Some(scheduled_service_ref) = command.scheduled_service_ref.clone() else {
        return Ok(None);
    };
    let body = serde_json::json!({
        "segmentBookingId": command.segment_booking_id,
        "journeyOrderId": command.journey_order_id,
        "travelerRef": command.traveler_ref,
        "segmentRef": command.segment_ref,
        "scheduledServiceRef": scheduled_service_ref,
        "serviceDate": command.service_date.clone().ok_or_else(|| ApiErrorKind::ValidationFailed("serviceDate is required when seat assignment is requested".into()))?,
        "capacityHoldId": command.capacity_hold_id.clone().ok_or_else(|| ApiErrorKind::ValidationFailed("capacityHoldId is required when seat assignment is requested".into()))?,
        "capacityUnitRef": command.capacity_unit_ref.clone().unwrap_or_else(|| "cap-standard".into()),
        "interval": command.interval.clone().unwrap_or(StationIntervalDto{from_seq:1,to_seq:2}),
        "classRef": command.class_ref.clone().unwrap_or_else(|| "standard".into()),
        "issuePurpose": command.issue_purpose.to_contract(),
        "seatPreferences": command.seat_preferences,
        "expiresAt": command.expires_at.clone().unwrap_or_else(current_rfc3339),
    });
    let base = std::env::var("SEAT_ASSIGNMENT_BASE_URL")
        .unwrap_or_else(|_| "http://seat-assignment:8080".into());
    let response = reqwest::Client::new()
        .post(format!("{base}/api/v1/internal/seat-allocations"))
        .header("Idempotency-Key", key)
        .header("X-Correlation-Id", correlation_id)
        .json(&body)
        .send()
        .await
        .map_err(|e| {
            log::warn!("seat assignment downstream error code=SEND message={}", e);
            ApiErrorKind::Unavailable("seat assignment unavailable".into())
        })?;
    if !response.status().is_success() {
        let status = response.status();
        let text = response.text().await.unwrap_or_default();
        log::warn!(
            "seat assignment downstream error code={} message={}",
            status.as_u16(),
            text
        );
        return Err(ApiErrorKind::Unavailable(
            "seat assignment unavailable".into(),
        ));
    }
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct AllocationResponse {
        seat_ref: SeatRefDto,
    }
    let allocation: AllocationResponse = response
        .json()
        .await
        .map_err(|e| ApiErrorKind::Unavailable(e.to_string()))?;
    Ok(Some(allocation.seat_ref))
}

impl VoidReasonDto {
    pub(crate) fn to_contract(&self) -> &'static str {
        match self {
            Self::Refund => "REFUND",
            Self::Change => "CHANGE",
            Self::Disruption => "DISRUPTION",
            Self::Risk => "RISK",
            Self::ManualCorrection => "MANUAL_CORRECTION",
        }
    }
}

impl VoidPolicyDto {
    pub(crate) fn to_contract(&self) -> &'static str {
        match self {
            Self::Normal => "NORMAL",
            Self::ExceptionalRule => "EXCEPTIONAL_RULE",
        }
    }
}

impl CredentialTypeDto {
    pub(crate) fn to_contract(&self) -> &'static str {
        match self {
            Self::ETicket => "E_TICKET",
            Self::PaperTicket => "PAPER_TICKET",
            Self::PickupCode => "PICKUP_CODE",
            Self::BoardingPass => "BOARDING_PASS",
            Self::FerryTicket => "FERRY_TICKET",
            Self::CoachETicket => "COACH_E_TICKET",
            Self::RideCode => "RIDE_CODE",
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ListEntitlementsQuery {
    journey_order_id: String,
    limit: Option<usize>,
    offset: Option<usize>,
}

fn parse_list_query(raw_query: Option<&str>) -> Result<ListEntitlementsQuery, String> {
    let raw_query =
        raw_query.ok_or_else(|| "journeyOrderId query parameter is required".to_string())?;
    let mut journey_order_id = None;
    let mut limit = None;
    let mut offset = None;
    for pair in raw_query.split('&').filter(|pair| !pair.is_empty()) {
        let (name, value) = pair.split_once('=').unwrap_or((pair, ""));
        match name {
            "journeyOrderId" => journey_order_id = Some(value.to_string()),
            "limit" => {
                limit = Some(value.parse::<usize>().map_err(|_| {
                    "limit query parameter must be a non-negative integer".to_string()
                })?)
            }
            "offset" => {
                offset = Some(value.parse::<usize>().map_err(|_| {
                    "offset query parameter must be a non-negative integer".to_string()
                })?)
            }
            _ => {}
        }
    }
    let journey_order_id = journey_order_id
        .filter(|v| !v.trim().is_empty())
        .ok_or_else(|| "journeyOrderId query parameter is required".to_string())?;
    Ok(ListEntitlementsQuery {
        journey_order_id,
        limit,
        offset,
    })
}

async fn issue_entitlement<S>(
    State(state): State<ApiState<S>>,
    Extension(context): Extension<RequestContext>,
    headers: HeaderMap,
    body: Result<Json<IssueEntitlementRequest>, JsonRejection>,
) -> Response
where
    S: EntitlementApi + 'static,
{
    let correlation_id = context.correlation_id().to_string();
    let key = match idempotency_key(&headers) {
        Ok(key) => key,
        Err(_) => {
            return validation_error(
                correlation_id,
                "Idempotency-Key header is required and must be UUID v7",
            );
        }
    };
    let Json(request) = match body {
        Ok(body) => body,
        Err(rejection) => return validation_error(correlation_id, rejection.body_text()),
    };
    match state
        .service
        .issue(request, key, correlation_id.clone())
        .await
    {
        Ok(response) => (StatusCode::CREATED, Json(response)).into_response(),
        Err(error) => api_error_response(error, correlation_id),
    }
}

async fn void_entitlement<S>(
    State(state): State<ApiState<S>>,
    Extension(context): Extension<RequestContext>,
    headers: HeaderMap,
    Path(entitlement_id): Path<String>,
    body: Result<Json<VoidEntitlementRequest>, JsonRejection>,
) -> Response
where
    S: EntitlementApi + 'static,
{
    let correlation_id = context.correlation_id().to_string();
    let key = match idempotency_key(&headers) {
        Ok(key) => key,
        Err(_) => {
            return validation_error(
                correlation_id,
                "Idempotency-Key header is required and must be UUID v7",
            );
        }
    };
    let Json(request) = match body {
        Ok(body) => body,
        Err(rejection) => return validation_error(correlation_id, rejection.body_text()),
    };
    match state
        .service
        .void(entitlement_id, request, key, correlation_id.clone())
        .await
    {
        Ok(response) => (StatusCode::OK, Json(response)).into_response(),
        Err(error) => api_error_response(error, correlation_id),
    }
}

async fn get_entitlement<S>(
    State(state): State<ApiState<S>>,
    Extension(context): Extension<RequestContext>,
    Path(entitlement_id): Path<String>,
) -> Response
where
    S: EntitlementApi + 'static,
{
    let correlation_id = context.correlation_id().to_string();
    match state.service.get(entitlement_id).await {
        Ok(response) => (StatusCode::OK, Json(response)).into_response(),
        Err(error) => api_error_response(error, correlation_id),
    }
}

async fn list_entitlements<S>(
    State(state): State<ApiState<S>>,
    Extension(context): Extension<RequestContext>,
    RawQuery(raw_query): RawQuery,
) -> Response
where
    S: EntitlementApi + 'static,
{
    let correlation_id = context.correlation_id().to_string();
    let query = match parse_list_query(raw_query.as_deref()) {
        Ok(query) => query,
        Err(message) => return validation_error(correlation_id, message),
    };
    let limit = query.limit.unwrap_or(20).min(100);
    let offset = query.offset.unwrap_or(0);
    match state
        .service
        .list(query.journey_order_id, limit, offset)
        .await
    {
        Ok(response) => (StatusCode::OK, Json(response)).into_response(),
        Err(error) => api_error_response(error, correlation_id),
    }
}

pub struct InMemoryEntitlementService {
    state: Mutex<InMemoryState>,
    publisher: Arc<dyn application::EventPublisher>,
}

impl Default for InMemoryEntitlementService {
    fn default() -> Self {
        Self::new(Arc::new(
            adapters::messaging::InMemoryEventPublisher::default(),
        ))
    }
}

#[allow(dead_code)]
impl InMemoryEntitlementService {
    pub fn new(publisher: Arc<dyn application::EventPublisher>) -> Self {
        Self {
            state: Mutex::new(InMemoryState::default()),
            publisher,
        }
    }

    fn replayed_response(
        &self,
        key: &str,
        fingerprint: &str,
    ) -> ApiResult<Option<IdempotentResponse>> {
        let state = self
            .state
            .lock()
            .expect("entitlement service lock poisoned");
        if let Some(record) = state.idempotency.get(key) {
            if record.fingerprint != fingerprint {
                return Err(ApiErrorKind::IdempotencyKeyReused(
                    "Idempotency-Key was reused with a different request body".to_string(),
                ));
            }
            return Ok(Some(record.response.clone()));
        }
        Ok(None)
    }

    fn pending_publication(
        &self,
        key: &str,
        fingerprint: &str,
    ) -> ApiResult<Option<PendingPublication>> {
        let state = self
            .state
            .lock()
            .expect("entitlement service lock poisoned");
        if let Some(record) = state.idempotency.get(key) {
            if record.fingerprint != fingerprint {
                return Err(ApiErrorKind::IdempotencyKeyReused(
                    "Idempotency-Key was reused with a different request body".to_string(),
                ));
            }
            return Ok(None);
        }
        if let Some(pending) = state.pending_publications.get(key) {
            if pending.fingerprint != fingerprint {
                return Err(ApiErrorKind::IdempotencyKeyReused(
                    "Idempotency-Key was reused with a different request body".to_string(),
                ));
            }
            return Ok(Some(pending.clone()));
        }
        Ok(None)
    }

    fn record_idempotent_success(
        &self,
        key: String,
        fingerprint: String,
        response: IdempotentResponse,
    ) {
        let mut state = self
            .state
            .lock()
            .expect("entitlement service lock poisoned");
        state.pending_publications.remove(&key);
        state.idempotency.insert(
            key,
            IdempotentRecord {
                fingerprint,
                response,
            },
        );
    }

    fn pending_bus_publication(
        &self,
        key: &PendingEventKey,
    ) -> ApiResult<Option<PendingBusPublication>> {
        let state = self
            .state
            .lock()
            .expect("entitlement service lock poisoned");
        Ok(state.pending_events.get(key).cloned())
    }

    fn clear_pending_bus_publication(&self, key: &PendingEventKey) {
        self.state
            .lock()
            .expect("entitlement service lock poisoned")
            .pending_events
            .remove(key);
    }

    async fn handle_subscribed_event(
        &self,
        envelope: application::EventEnvelope,
    ) -> Result<(), application::HandlerError> {
        self.apply_subscribed_event(envelope)
            .await
            .map_err(|error| match error {
                ApiErrorKind::Unavailable(message) => application::HandlerError::Transient(message),
                other => application::HandlerError::Fatal(other.message().to_string()),
            })
    }

    async fn apply_subscribed_event(&self, envelope: application::EventEnvelope) -> ApiResult<()> {
        match envelope.event_type.as_str() {
            "BoardingVerified" => {
                self.apply_boarding_verified(envelope.payload, envelope.correlation_id)
            }
            "FulfillmentCompleted" => {
                self.apply_segment_completed(envelope.payload, envelope.correlation_id)
            }
            "NoShowRecorded" => {
                self.apply_no_show_recorded(envelope.payload, envelope.correlation_id)
            }
            "PostSalesApproved" => {
                self.apply_post_sales_approved(envelope.payload, envelope.correlation_id)
                    .await
            }
            "SegmentTicketed" => Ok(()),
            _ => Ok(()),
        }
    }

    fn apply_boarding_verified(&self, payload: Value, correlation_id: String) -> ApiResult<()> {
        let entitlement_id = payload
            .get("entitlementId")
            .and_then(Value::as_str)
            .ok_or_else(|| ApiErrorKind::ValidationFailed("entitlementId is required".to_string()))?
            .to_string();
        validate_prefixed_uuid(&entitlement_id, "entitlementId", "ent-")?;
        let fact_ref = payload
            .get("sourceEventId")
            .and_then(Value::as_str)
            .or_else(|| payload.get("fulfillmentRecordId").and_then(Value::as_str))
            .unwrap_or("fulfillment-fact");
        let mut state = self
            .state
            .lock()
            .expect("entitlement service lock poisoned");
        let aggregate = state
            .aggregates
            .get_mut(&entitlement_id)
            .ok_or_else(|| ApiErrorKind::NotFound("entitlement aggregate not found".to_string()))?;
        aggregate
            .accept_fulfillment_fact(AcceptFulfillmentFact {
                command_id: CommandId::new(format!("cmd-{}", uuid::Uuid::now_v7()))
                    .map_err(ApiErrorKind::from)?,
                fact: FulfillmentFact::BoardingVerified {
                    fact_ref: FulfillmentFactRef::new(fact_ref.to_string())
                        .map_err(ApiErrorKind::from)?,
                },
                audit: audit_builder(&correlation_id, "event bus boarding verified")
                    .map_err(ApiErrorKind::from)?,
            })
            .map_err(ApiErrorKind::from)?;
        if let Some(details) = state.entitlements.get_mut(&entitlement_id) {
            details.status = EntitlementStatusDto::Boarded;
        }
        Ok(())
    }

    fn apply_segment_completed(&self, payload: Value, correlation_id: String) -> ApiResult<()> {
        let entitlement_id = payload
            .get("entitlementId")
            .and_then(Value::as_str)
            .ok_or_else(|| ApiErrorKind::ValidationFailed("entitlementId is required".to_string()))?
            .to_string();
        validate_prefixed_uuid(&entitlement_id, "entitlementId", "ent-")?;
        let fact_ref = payload
            .get("fulfillmentRecordId")
            .and_then(Value::as_str)
            .ok_or_else(|| {
                ApiErrorKind::ValidationFailed("fulfillmentRecordId is required".to_string())
            })?
            .to_string();
        let mut state = self
            .state
            .lock()
            .expect("entitlement service lock poisoned");
        let aggregate = state
            .aggregates
            .get_mut(&entitlement_id)
            .ok_or_else(|| ApiErrorKind::NotFound("entitlement aggregate not found".to_string()))?;
        let accepted = aggregate.accept_fulfillment_fact(AcceptFulfillmentFact {
            command_id: CommandId::new(format!("cmd-{}", uuid::Uuid::now_v7()))
                .map_err(ApiErrorKind::from)?,
            fact: FulfillmentFact::BoardingComplete {
                fact_ref: FulfillmentFactRef::new(fact_ref).map_err(ApiErrorKind::from)?,
            },
            audit: audit_builder(&correlation_id, "event bus fulfillment completed")
                .map_err(ApiErrorKind::from)?,
        });
        if matches!(
            accepted,
            Err(EntitlementError::BoardingRequiredForCompletion)
        ) {
            return Ok(());
        }
        accepted.map_err(ApiErrorKind::from)?;
        if let Some(details) = state.entitlements.get_mut(&entitlement_id) {
            details.status = EntitlementStatusDto::Boarded;
        }
        Ok(())
    }

    fn apply_no_show_recorded(&self, payload: Value, correlation_id: String) -> ApiResult<()> {
        let entitlement_id = payload
            .get("entitlementId")
            .and_then(Value::as_str)
            .ok_or_else(|| ApiErrorKind::ValidationFailed("entitlementId is required".to_string()))?
            .to_string();
        validate_prefixed_uuid(&entitlement_id, "entitlementId", "ent-")?;
        let fact_ref = payload
            .get("fulfillmentRecordId")
            .and_then(Value::as_str)
            .ok_or_else(|| {
                ApiErrorKind::ValidationFailed("fulfillmentRecordId is required".to_string())
            })?
            .to_string();
        let mut state = self
            .state
            .lock()
            .expect("entitlement service lock poisoned");
        let aggregate = state
            .aggregates
            .get_mut(&entitlement_id)
            .ok_or_else(|| ApiErrorKind::NotFound("entitlement aggregate not found".to_string()))?;
        aggregate
            .accept_fulfillment_fact(AcceptFulfillmentFact {
                command_id: CommandId::new(format!("cmd-{}", uuid::Uuid::now_v7()))
                    .map_err(ApiErrorKind::from)?,
                fact: FulfillmentFact::NoShowRecorded {
                    fact_ref: FulfillmentFactRef::new(fact_ref).map_err(ApiErrorKind::from)?,
                },
                audit: audit_builder(&correlation_id, "event bus no-show recorded")
                    .map_err(ApiErrorKind::from)?,
            })
            .map_err(ApiErrorKind::from)?;
        if let Some(details) = state.entitlements.get_mut(&entitlement_id) {
            details.status = EntitlementStatusDto::NoShow;
        }
        Ok(())
    }

    async fn apply_post_sales_approved(
        &self,
        payload: Value,
        correlation_id: String,
    ) -> ApiResult<()> {
        let case_id = payload
            .get("caseId")
            .and_then(Value::as_str)
            .ok_or_else(|| ApiErrorKind::ValidationFailed("caseId is required".to_string()))?
            .to_string();
        let order_id = payload
            .get("orderId")
            .and_then(Value::as_str)
            .ok_or_else(|| ApiErrorKind::ValidationFailed("orderId is required".to_string()))?
            .to_string();
        validate_prefixed_uuid(&order_id, "orderId", "ord-")?;

        let actions = approved_void_actions(payload.get("approvedActions"))
            .map_err(ApiErrorKind::ValidationFailed)?;
        if actions.is_empty() {
            return Ok(());
        }

        let pending_keys =
            self.ensure_void_entitlements_for_post_sales(&order_id, &case_id, &actions)?;
        for key in pending_keys {
            let Some(pending) = self.pending_bus_publication(&key)? else {
                continue;
            };
            publish_api_event_value(
                self.publisher.as_ref(),
                pending.event_type,
                pending.payload.clone(),
                correlation_id.clone(),
            )
            .await?;
            self.clear_pending_bus_publication(&key);
        }
        Ok(())
    }

    fn ensure_void_entitlements_for_post_sales(
        &self,
        order_id: &str,
        case_id: &str,
        actions: &[ApprovedVoidAction],
    ) -> ApiResult<Vec<PendingEventKey>> {
        let mut state = self
            .state
            .lock()
            .expect("entitlement service lock poisoned");
        let entitlement_ids: Vec<String> = state
            .entitlements
            .values()
            .filter(|entitlement| entitlement.journey_order_id == order_id)
            .map(|entitlement| entitlement.entitlement_id.clone())
            .collect();
        if entitlement_ids.is_empty() {
            return Ok(Vec::new());
        }

        let mut pending_keys = Vec::new();
        for entitlement_id in entitlement_ids {
            let Some(action) = actions
                .iter()
                .find(|action| action.matches_entitlement(&entitlement_id))
            else {
                continue;
            };
            let reason = action.reason.clone();
            let policy = action.policy.clone();
            let entitlement_details = state
                .entitlements
                .get(&entitlement_id)
                .cloned()
                .ok_or_else(|| ApiErrorKind::NotFound("entitlement not found".to_string()))?;
            let segment_booking_id = entitlement_details.segment_booking_id.clone();
            let pending_key = PendingEventKey::new(&entitlement_id, "EntitlementVoided");
            if state.pending_events.contains_key(&pending_key) {
                pending_keys.push(pending_key);
                continue;
            }

            let aggregate = state.aggregates.get_mut(&entitlement_id).ok_or_else(|| {
                ApiErrorKind::NotFound("entitlement aggregate not found".to_string())
            })?;
            let events = aggregate
                .void(VoidEntitlement {
                    command_id: CommandId::new(format!("cmd-{}", uuid::Uuid::now_v7()))
                        .map_err(ApiErrorKind::from)?,
                    reason: reason.clone(),
                    policy: policy.clone(),
                    business_case_ref: BusinessCaseRef::new(case_id.to_string())
                        .map_err(ApiErrorKind::from)?,
                    audit: audit_builder("corr-subscriber", "event bus post-sales approved")
                        .map_err(ApiErrorKind::from)?,
                })
                .map_err(ApiErrorKind::from)?;
            if events.is_empty() {
                continue;
            }
            let voided_at = current_rfc3339();
            if let Some(entitlement) = state.entitlements.get_mut(&entitlement_id) {
                entitlement.status = EntitlementStatusDto::Voided;
                entitlement.voided_at = Some(voided_at.clone());
            }
            let payload = EntitlementVoidedPayload {
                entitlement_id: entitlement_id.clone(),
                segment_booking_id: segment_booking_id.clone(),
                references: EntitlementVoidedReferences {
                    segment_booking_ref: segment_booking_id,
                    order_ref: Some(entitlement_details.journey_order_id),
                    traveler_ref: Some(entitlement_details.traveler_ref),
                },
                voided_at,
                reason: reason_to_contract(&reason),
                policy: policy_to_contract(&policy),
                business_case_ref: Some(case_id.to_string()),
            };
            state.pending_events.insert(
                pending_key.clone(),
                PendingBusPublication {
                    event_type: "EntitlementVoided",
                    payload: serde_json::to_value(payload).unwrap_or_else(|_| json!({})),
                },
            );
            pending_keys.push(pending_key);
        }
        Ok(pending_keys)
    }
}

#[derive(Debug, Default)]
struct InMemoryState {
    entitlements: HashMap<String, EntitlementDetails>,
    aggregates: HashMap<String, Entitlement>,
    credential_registry: CredentialRegistry,
    idempotency: HashMap<String, IdempotentRecord>,
    pending_publications: HashMap<String, PendingPublication>,
    #[allow(dead_code)]
    pending_events: HashMap<PendingEventKey, PendingBusPublication>,
    sequence: u64,
}

#[derive(Debug, Clone)]
struct IdempotentRecord {
    fingerprint: String,
    response: IdempotentResponse,
}

#[derive(Debug, Clone)]
struct PendingPublication {
    fingerprint: String,
    event_type: &'static str,
    payload: Value,
    response: IdempotentResponse,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct PendingEventKey {
    aggregate_id: String,
    event_type: &'static str,
}

#[allow(dead_code)]
impl PendingEventKey {
    fn new(aggregate_id: &str, event_type: &'static str) -> Self {
        Self {
            aggregate_id: aggregate_id.to_string(),
            event_type,
        }
    }
}

#[derive(Debug, Clone)]
#[allow(dead_code)]
struct PendingBusPublication {
    event_type: &'static str,
    payload: Value,
}

#[derive(Debug, Clone)]
enum IdempotentResponse {
    Issue(IssueEntitlementResponse),
    Void(VoidEntitlementResponse),
}

#[async_trait::async_trait]
impl EntitlementApi for InMemoryEntitlementService {
    async fn issue(
        &self,
        command: IssueEntitlementRequest,
        key: String,
        correlation_id: String,
    ) -> ApiResult<IssueEntitlementResponse> {
        validate_prefixed_uuid(&command.segment_booking_id, "segmentBookingId", "sb-")?;
        validate_prefixed_uuid(&command.journey_order_id, "journeyOrderId", "ord-")?;
        validate_prefixed_uuid(&command.traveler_ref, "travelerRef", "tvl-")?;
        validate_prefixed_uuid(&command.segment_ref, "segmentRef", "seg-")?;
        let fingerprint = serde_json::to_string(&command).unwrap_or_default();
        if let Some(replayed) = self.replayed_response(&key, &fingerprint)? {
            if let IdempotentResponse::Issue(response) = replayed {
                return Ok(response);
            }
            return Err(ApiErrorKind::IdempotencyKeyReused(
                "Idempotency-Key was reused for a different operation".to_string(),
            ));
        }
        if let Some(pending) = self.pending_publication(&key, &fingerprint)? {
            let IdempotentResponse::Issue(response) = pending.response.clone() else {
                return Err(ApiErrorKind::IdempotencyKeyReused(
                    "Idempotency-Key was reused for a different operation".to_string(),
                ));
            };
            publish_api_event_value(
                self.publisher.as_ref(),
                pending.event_type,
                pending.payload.clone(),
                correlation_id,
            )
            .await?;
            self.record_idempotent_success(key, fingerprint, pending.response);
            return Ok(response);
        }
        let seat_ref = allocate_seat_for_issue(&command, &key, &correlation_id).await?;
        let (response, event_payload) = {
            let mut state = self
                .state
                .lock()
                .expect("entitlement service lock poisoned");
            state.sequence += 1;
            let entitlement_id = format!("ent-{}", uuid::Uuid::now_v7());
            let issued_at = current_rfc3339();
            let response = IssueEntitlementResponse {
                entitlement_id: entitlement_id.clone(),
                segment_booking_id: command.segment_booking_id.clone(),
                journey_order_id: command.journey_order_id.clone(),
                credential_no: format!("ETK-{:012}", state.sequence),
                credential_type: CredentialTypeDto::ETicket,
                status: EntitlementStatusDto::Issued,
                issued_at: issued_at.clone(),
                seat_ref: seat_ref.clone(),
            };
            let event_payload = EntitlementIssuedPayload {
                entitlement_id: entitlement_id.clone(),
                segment_booking_id: command.segment_booking_id.clone(),
                journey_order_id: command.journey_order_id.clone(),
                traveler_ref: command.traveler_ref.clone(),
                segment_ref: command.segment_ref.clone(),
                issue_purpose: command.issue_purpose.to_contract(),
                credential_no: response.credential_no.clone(),
                credential_type: response.credential_type.to_contract(),
                issued_at: issued_at.clone(),
                seat_ref: response.seat_ref.clone(),
                seat_allocation_id: response
                    .seat_ref
                    .as_ref()
                    .map(|s| s.seat_allocation_id.clone()),
            };
            let details = EntitlementDetails {
                entitlement_id: entitlement_id.clone(),
                segment_booking_id: command.segment_booking_id,
                journey_order_id: command.journey_order_id,
                traveler_ref: command.traveler_ref,
                segment_ref: command.segment_ref,
                credential_no: response.credential_no.clone(),
                credential_type: response.credential_type.clone(),
                status: response.status.clone(),
                issued_at,
                voided_at: None,
                seat_ref: response.seat_ref.clone(),
            };
            let (mut aggregate, _) = Entitlement::request(RequestEntitlement {
                command_id: CommandId::new(format!("cmd-{}", uuid::Uuid::now_v7()))
                    .map_err(ApiErrorKind::from)?,
                entitlement_id: EntitlementId::new(entitlement_id.clone())
                    .map_err(ApiErrorKind::from)?,
                journey_order_ref: JourneyOrderRef::new(details.journey_order_id.clone())
                    .map_err(ApiErrorKind::from)?,
                segment_booking_ref: SegmentBookingRef::new(details.segment_booking_id.clone())
                    .map_err(ApiErrorKind::from)?,
                traveler_ref: TravelerRef::new(details.traveler_ref.clone())
                    .map_err(ApiErrorKind::from)?,
                segment_ref: SegmentRef::new(details.segment_ref.clone())
                    .map_err(ApiErrorKind::from)?,
                purpose: command.issue_purpose.clone().into(),
                validity_window: ValidityWindow::new(
                    UnixMillis::new(current_unix_millis()),
                    UnixMillis::new(current_unix_millis().saturating_add(86_400_000)),
                )
                .map_err(ApiErrorKind::from)?,
                audit: audit_builder(&correlation_id, "HTTP issue entitlement")
                    .map_err(ApiErrorKind::from)?,
            })
            .map_err(ApiErrorKind::from)?;
            aggregate
                .issue(
                    IssueEntitlement {
                        command_id: CommandId::new(format!("cmd-{}", uuid::Uuid::now_v7()))
                            .map_err(ApiErrorKind::from)?,
                        idempotency_key: aggregate.idempotency_key().clone(),
                        preconditions: default_preconditions().map_err(ApiErrorKind::from)?,
                        credential_ref: credential_ref(&response.credential_no)
                            .map_err(ApiErrorKind::from)?,
                        refund_request_fact: None,
                        audit: audit_builder(&correlation_id, "HTTP issue entitlement")
                            .map_err(ApiErrorKind::from)?,
                    },
                    &mut state.credential_registry,
                )
                .map_err(ApiErrorKind::from)?;
            state.aggregates.insert(entitlement_id.clone(), aggregate);
            state.entitlements.insert(entitlement_id, details);
            let pending_payload =
                serde_json::to_value(&event_payload).unwrap_or_else(|_| json!({}));
            state.pending_publications.insert(
                key.clone(),
                PendingPublication {
                    fingerprint: fingerprint.clone(),
                    event_type: "EntitlementIssued",
                    payload: pending_payload,
                    response: IdempotentResponse::Issue(response.clone()),
                },
            );
            (response, event_payload)
        };
        publish_api_event(
            self.publisher.as_ref(),
            "EntitlementIssued",
            &event_payload,
            correlation_id,
        )
        .await?;
        self.record_idempotent_success(
            key,
            fingerprint,
            IdempotentResponse::Issue(response.clone()),
        );
        Ok(response)
    }

    async fn void(
        &self,
        entitlement_id: String,
        command: VoidEntitlementRequest,
        key: String,
        correlation_id: String,
    ) -> ApiResult<VoidEntitlementResponse> {
        validate_prefixed_uuid(&entitlement_id, "entitlementId", "ent-")?;
        if command
            .business_case_ref
            .as_deref()
            .is_some_and(|value| value.trim().is_empty())
        {
            return Err(ApiErrorKind::ValidationFailed(
                "businessCaseRef must not be blank when provided".to_string(),
            ));
        }
        let fingerprint = format!(
            "{}:{}",
            entitlement_id,
            serde_json::to_string(&command).unwrap_or_default()
        );
        if let Some(replayed) = self.replayed_response(&key, &fingerprint)? {
            if let IdempotentResponse::Void(response) = replayed {
                return Ok(response);
            }
            return Err(ApiErrorKind::IdempotencyKeyReused(
                "Idempotency-Key was reused for a different operation".to_string(),
            ));
        }
        if let Some(pending) = self.pending_publication(&key, &fingerprint)? {
            let IdempotentResponse::Void(response) = pending.response.clone() else {
                return Err(ApiErrorKind::IdempotencyKeyReused(
                    "Idempotency-Key was reused for a different operation".to_string(),
                ));
            };
            publish_api_event_value(
                self.publisher.as_ref(),
                pending.event_type,
                pending.payload.clone(),
                correlation_id,
            )
            .await?;
            self.record_idempotent_success(key, fingerprint, pending.response);
            return Ok(response);
        }
        let (response, event_payload) = {
            let mut state = self
                .state
                .lock()
                .expect("entitlement service lock poisoned");
            let entitlement_details = state
                .entitlements
                .get(&entitlement_id)
                .cloned()
                .ok_or_else(|| ApiErrorKind::NotFound("entitlement not found".to_string()))?;
            let segment_booking_id = entitlement_details.segment_booking_id.clone();
            let aggregate = state.aggregates.get_mut(&entitlement_id).ok_or_else(|| {
                ApiErrorKind::NotFound("entitlement aggregate not found".to_string())
            })?;
            let events = aggregate
                .void(VoidEntitlement {
                    command_id: CommandId::new(format!("cmd-{}", uuid::Uuid::now_v7()))
                        .map_err(ApiErrorKind::from)?,
                    reason: command.reason.clone().into(),
                    policy: command.policy.clone().into(),
                    business_case_ref: BusinessCaseRef::new(
                        command
                            .business_case_ref
                            .clone()
                            .unwrap_or_else(|| format!("case-{}", uuid::Uuid::now_v7())),
                    )
                    .map_err(ApiErrorKind::from)?,
                    audit: audit_builder(&correlation_id, "HTTP void entitlement")
                        .map_err(ApiErrorKind::from)?,
                })
                .map_err(ApiErrorKind::from)?;
            if events.is_empty() {
                return Err(ApiErrorKind::PreconditionFailed(
                    "entitlement is already voided".to_string(),
                ));
            }
            let voided_at = current_rfc3339();
            if let Some(entitlement) = state.entitlements.get_mut(&entitlement_id) {
                entitlement.status = EntitlementStatusDto::Voided;
                entitlement.voided_at = Some(voided_at.clone());
            }
            let event_payload = EntitlementVoidedPayload {
                entitlement_id: entitlement_id.clone(),
                segment_booking_id: segment_booking_id.clone(),
                references: EntitlementVoidedReferences {
                    segment_booking_ref: segment_booking_id,
                    order_ref: Some(entitlement_details.journey_order_id),
                    traveler_ref: Some(entitlement_details.traveler_ref),
                },
                voided_at: voided_at.clone(),
                reason: command.reason.to_contract(),
                policy: command.policy.to_contract(),
                business_case_ref: command.business_case_ref.clone(),
            };
            let response = VoidEntitlementResponse {
                entitlement_id,
                status: EntitlementStatusDto::Voided,
                voided_at,
            };
            let pending_payload =
                serde_json::to_value(&event_payload).unwrap_or_else(|_| json!({}));
            state.pending_publications.insert(
                key.clone(),
                PendingPublication {
                    fingerprint: fingerprint.clone(),
                    event_type: "EntitlementVoided",
                    payload: pending_payload,
                    response: IdempotentResponse::Void(response.clone()),
                },
            );
            (response, event_payload)
        };
        publish_api_event(
            self.publisher.as_ref(),
            "EntitlementVoided",
            &event_payload,
            correlation_id,
        )
        .await?;
        self.record_idempotent_success(
            key,
            fingerprint,
            IdempotentResponse::Void(response.clone()),
        );
        Ok(response)
    }

    async fn get(&self, entitlement_id: String) -> ApiResult<EntitlementDetails> {
        validate_prefixed_uuid(&entitlement_id, "entitlementId", "ent-")?;
        self.state
            .lock()
            .expect("entitlement service lock poisoned")
            .entitlements
            .get(&entitlement_id)
            .cloned()
            .ok_or_else(|| ApiErrorKind::NotFound("entitlement not found".to_string()))
    }

    async fn list(
        &self,
        journey_order_id: String,
        limit: usize,
        offset: usize,
    ) -> ApiResult<PaginatedEntitlements> {
        validate_prefixed_uuid(&journey_order_id, "journeyOrderId", "ord-")?;
        let mut items: Vec<_> = self
            .state
            .lock()
            .expect("entitlement service lock poisoned")
            .entitlements
            .values()
            .filter(|entitlement| entitlement.journey_order_id == journey_order_id)
            .cloned()
            .collect();
        items.sort_by(|left, right| left.entitlement_id.cmp(&right.entitlement_id));
        let total = items.len();
        Ok(PaginatedEntitlements {
            items: items.into_iter().skip(offset).take(limit).collect(),
            total,
            limit,
            offset,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ApprovedVoidAction {
    entitlement_ref: String,
    reason: VoidReason,
    policy: VoidPolicy,
}

impl ApprovedVoidAction {
    fn matches_entitlement(&self, entitlement_id: &str) -> bool {
        self.entitlement_ref == entitlement_id
    }
}

fn approved_void_actions(value: Option<&Value>) -> Result<Vec<ApprovedVoidAction>, String> {
    let Some(value) = value else {
        return Ok(Vec::new());
    };
    let mut actions = Vec::new();
    collect_void_actions(value, &mut actions)?;
    Ok(actions)
}

fn collect_void_actions(
    value: &Value,
    actions: &mut Vec<ApprovedVoidAction>,
) -> Result<(), String> {
    match value {
        Value::Array(items) => {
            for item in items {
                collect_void_actions(item, actions)?;
            }
        }
        Value::Object(map) => {
            if action_type(map.get("type")).is_some_and(is_void_action_type)
                || action_type(map.get("actionType")).is_some_and(is_void_action_type)
                || action_type(map.get("stepType")).is_some_and(is_void_action_type)
            {
                let entitlement_ref = map
                    .get("entitlementId")
                    .and_then(Value::as_str)
                    .filter(|v| !v.trim().is_empty())
                    .ok_or_else(|| "VOID_ENTITLEMENT missing entitlementId".to_string())?
                    .to_string();
                let reason = map
                    .get("reason")
                    .and_then(Value::as_str)
                    .ok_or_else(|| "VOID_ENTITLEMENT missing reason".to_string())
                    .and_then(parse_void_reason)?;
                let policy = map
                    .get("policy")
                    .and_then(Value::as_str)
                    .ok_or_else(|| "VOID_ENTITLEMENT missing policy".to_string())
                    .and_then(parse_void_policy)?;
                actions.push(ApprovedVoidAction {
                    entitlement_ref,
                    reason,
                    policy,
                });
            }
            for nested in map.values() {
                if nested.is_array() || nested.is_object() {
                    collect_void_actions(nested, actions)?;
                }
            }
        }
        _ => {}
    }
    Ok(())
}

fn action_type(value: Option<&Value>) -> Option<&str> {
    value.and_then(Value::as_str)
}

fn is_void_action_type(value: &str) -> bool {
    matches!(
        value,
        "VOID" | "VOID_ENTITLEMENT" | "VOID_OLD_ENTITLEMENT" | "VOID_TICKET"
    )
}

fn parse_void_reason(value: &str) -> Result<VoidReason, String> {
    match value {
        "REFUND" => Ok(VoidReason::Refund),
        "CHANGE" => Ok(VoidReason::Change),
        "DISRUPTION" => Ok(VoidReason::Disruption),
        "RISK" => Ok(VoidReason::Risk),
        "MANUAL_CORRECTION" => Ok(VoidReason::ManualCorrection),
        other => Err(format!("VOID_ENTITLEMENT invalid reason {other}")),
    }
}

pub(crate) fn reason_to_contract(value: &VoidReason) -> &'static str {
    match value {
        VoidReason::Refund => "REFUND",
        VoidReason::Change => "CHANGE",
        VoidReason::Disruption => "DISRUPTION",
        VoidReason::Risk => "RISK",
        VoidReason::ManualCorrection => "MANUAL_CORRECTION",
    }
}

fn parse_void_policy(value: &str) -> Result<VoidPolicy, String> {
    match value {
        "NORMAL" => Ok(VoidPolicy::Normal),
        "EXCEPTIONAL_RULE" => Ok(VoidPolicy::ExceptionalRule),
        other => Err(format!("VOID_ENTITLEMENT invalid policy {other}")),
    }
}

pub(crate) fn policy_to_contract(value: &VoidPolicy) -> &'static str {
    match value {
        VoidPolicy::Normal => "NORMAL",
        VoidPolicy::ExceptionalRule => "EXCEPTIONAL_RULE",
    }
}

fn validate_non_empty(value: &str, field: &'static str) -> ApiResult<()> {
    if value.trim().is_empty() {
        Err(ApiErrorKind::ValidationFailed(format!(
            "{field} must not be blank"
        )))
    } else {
        Ok(())
    }
}

fn validate_prefixed_uuid(value: &str, field: &'static str, prefix: &'static str) -> ApiResult<()> {
    validate_non_empty(value, field)?;
    let Some(uuid_part) = value.strip_prefix(prefix) else {
        return Err(ApiErrorKind::ValidationFailed(format!(
            "{field} must use {prefix}<uuid> format"
        )));
    };
    uuid::Uuid::parse_str(uuid_part).map_err(|_| {
        ApiErrorKind::ValidationFailed(format!("{field} must use {prefix}<uuid> format"))
    })?;
    Ok(())
}

pub(crate) fn current_rfc3339() -> String {
    chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
}

pub(crate) fn current_unix_millis() -> u64 {
    chrono::Utc::now().timestamp_millis().max(0) as u64
}

pub(crate) fn audit_builder(
    correlation_id: &str,
    reason: &'static str,
) -> Result<AuditBuilder, EntitlementError> {
    Ok(AuditBuilder::system(
        reason,
        UnixMillis::new(current_unix_millis()),
        CorrelationId::new(correlation_id.to_string())?,
    ))
}

pub(crate) fn default_preconditions() -> Result<IssuePreconditions, EntitlementError> {
    let now = UnixMillis::new(current_unix_millis());
    Ok(IssuePreconditions::accepted(
        AcceptedFact::new(
            BookingFactRef::new(format!("booking-fact-{}", uuid::Uuid::now_v7()))?,
            BookingAcceptance::SegmentReservationConfirmed,
            now,
        ),
        AcceptedFact::new(
            CapacityFactRef::new(format!("capacity-fact-{}", uuid::Uuid::now_v7()))?,
            CapacityAcceptance::CapacityCommitted,
            now,
        ),
        AcceptedFact::new(
            PaymentFactRef::new(format!("payment-fact-{}", uuid::Uuid::now_v7()))?,
            PaymentAcceptance::PaymentCaptured,
            now,
        ),
        AcceptedFact::new(
            TravelerFactRef::new(format!("traveler-fact-{}", uuid::Uuid::now_v7()))?,
            TravelerAcceptance::TravelerSnapshotAccepted,
            now,
        ),
        AcceptedFact::new(
            RiskFactRef::new(format!("risk-fact-{}", uuid::Uuid::now_v7()))?,
            RiskAcceptance::Allowed,
            now,
        ),
    ))
}

pub(crate) fn credential_ref(credential_no: &str) -> Result<CredentialRef, EntitlementError> {
    CredentialRef::new(
        CredentialId::new(format!("cred-{}", uuid::Uuid::now_v7()))?,
        CredentialType::ETicket,
        credential_no.to_string(),
        None,
        CredentialDisplayReference::new(
            format!(
                "****{}",
                &credential_no[credential_no.len().saturating_sub(4)..]
            ),
            1,
            Some("QR".to_string()),
        )?,
    )
}

async fn publish_api_event<T: Serialize>(
    publisher: &dyn application::EventPublisher,
    event_type: &str,
    payload: &T,
    correlation_id: String,
) -> ApiResult<()> {
    publish_api_event_value(
        publisher,
        event_type,
        serde_json::to_value(payload).unwrap_or_else(|_| json!({})),
        correlation_id,
    )
    .await
}

async fn publish_api_event_value(
    publisher: &dyn application::EventPublisher,
    event_type: &str,
    payload: Value,
    correlation_id: String,
) -> ApiResult<()> {
    let envelope = application::EventEnvelope::new(
        event_type,
        current_rfc3339(),
        rust_kit::messaging::valid_or_generated_correlation_id(correlation_id),
        None::<String>,
        profile().service_id,
        payload,
    );
    publisher
        .publish(envelope)
        .await
        .map_err(|error| ApiErrorKind::Unavailable(error.to_string()))
}

impl From<EntitlementError> for ApiErrorKind {
    fn from(error: EntitlementError) -> Self {
        match error {
            EntitlementError::BlankReference { .. }
            | EntitlementError::InvalidValidityWindow
            | EntitlementError::InvalidDisplayVersion
            | EntitlementError::MissingAuditReason => Self::ValidationFailed(error.to_string()),
            EntitlementError::DuplicateCredential { .. } => Self::Conflict(error.to_string()),
            EntitlementError::NotIssued
            | EntitlementError::NotSuspended
            | EntitlementError::TerminalStatus(_)
            | EntitlementError::VoidRequiresCompensation
            | EntitlementError::BoardingRequiredForCompletion
            | EntitlementError::NoShowAfterBoarding
            | EntitlementError::ExceptionalVoidRuleRequired
            | EntitlementError::AlreadyIssued
            | EntitlementError::EntitlementFrozen
            | EntitlementError::IssueFailureNotRetryable
            | EntitlementError::DuplicateIssueAttempt
            | EntitlementError::IdempotencyKeyMismatch => {
                Self::DomainRuleViolation(error.to_string())
            }
        }
    }
}

#[cfg(test)]
mod api_domain_wiring_tests {
    use super::*;

    #[tokio::test]
    async fn fulfillment_events_drive_boarded_completion_and_noshow_statuses() {
        let service = InMemoryEntitlementService::default();
        let boarded = service
            .issue(
                IssueEntitlementRequest {
                    segment_booking_id: "sb-0194f2e0-7b3e-7610-8284-5c26e8b0aa11".to_string(),
                    journey_order_id: "ord-0194f2e0-7b3e-7610-8284-5c26e8b0aa12".to_string(),
                    traveler_ref: "tvl-0194f2e0-7b3e-7610-8284-5c26e8b0aa13".to_string(),
                    segment_ref: "seg-0194f2e0-7b3e-7610-8284-5c26e8b0aa14".to_string(),
                    issue_purpose: IssuePurposeDto::Initial,
                    seat_preferences: None,
                    scheduled_service_ref: None,
                    service_date: None,
                    capacity_hold_id: None,
                    capacity_unit_ref: None,
                    interval: None,
                    class_ref: None,
                    expires_at: None,
                },
                "0194f2e0-7b3e-7610-8284-5c26e8b0aa01".to_string(),
                "corr-0194f2e0-7b3e-7610-8284-5c26e8b0aa02".to_string(),
            )
            .await
            .unwrap();
        service
            .apply_subscribed_event(application::EventEnvelope::new(
                "BoardingVerified",
                current_rfc3339(),
                "corr-0194f2e0-7b3e-7610-8284-5c26e8b0aa03",
                Some("evt-0194f2e0-7b3e-7610-8284-5c26e8b0aa04"),
                "fulfillment",
                json!({"fulfillmentRecordId":"fr-0194f2e0-7b3e-7610-8284-5c26e8b0aa05","entitlementId": boarded.entitlement_id,"segmentBookingId":"sb-0194f2e0-7b3e-7610-8284-5c26e8b0aa11","sourceEventId":"scan-aa"}),
            ))
            .await
            .unwrap();
        assert_eq!(
            service
                .get(boarded.entitlement_id.clone())
                .await
                .unwrap()
                .status,
            EntitlementStatusDto::Boarded
        );
        service
            .apply_subscribed_event(application::EventEnvelope::new(
                "FulfillmentCompleted",
                current_rfc3339(),
                "corr-0194f2e0-7b3e-7610-8284-5c26e8b0aa06",
                Some("evt-0194f2e0-7b3e-7610-8284-5c26e8b0aa07"),
                "fulfillment",
                json!({"fulfillmentRecordId":"fr-0194f2e0-7b3e-7610-8284-5c26e8b0aa05","entitlementId": boarded.entitlement_id,"segmentBookingId":"sb-0194f2e0-7b3e-7610-8284-5c26e8b0aa11"}),
            ))
            .await
            .unwrap();
        assert_eq!(
            service.get(boarded.entitlement_id).await.unwrap().status,
            EntitlementStatusDto::Boarded
        );

        let no_show = service
            .issue(
                IssueEntitlementRequest {
                    segment_booking_id: "sb-0194f2e0-7b3e-7610-8284-5c26e8b0bb11".to_string(),
                    journey_order_id: "ord-0194f2e0-7b3e-7610-8284-5c26e8b0bb12".to_string(),
                    traveler_ref: "tvl-0194f2e0-7b3e-7610-8284-5c26e8b0bb13".to_string(),
                    segment_ref: "seg-0194f2e0-7b3e-7610-8284-5c26e8b0bb14".to_string(),
                    issue_purpose: IssuePurposeDto::Initial,
                    seat_preferences: None,
                    scheduled_service_ref: None,
                    service_date: None,
                    capacity_hold_id: None,
                    capacity_unit_ref: None,
                    interval: None,
                    class_ref: None,
                    expires_at: None,
                },
                "0194f2e0-7b3e-7610-8284-5c26e8b0bb01".to_string(),
                "corr-0194f2e0-7b3e-7610-8284-5c26e8b0bb02".to_string(),
            )
            .await
            .unwrap();
        service
            .apply_subscribed_event(application::EventEnvelope::new(
                "NoShowRecorded",
                current_rfc3339(),
                "corr-0194f2e0-7b3e-7610-8284-5c26e8b0bb03",
                Some("evt-0194f2e0-7b3e-7610-8284-5c26e8b0bb04"),
                "fulfillment",
                json!({"fulfillmentRecordId":"fr-0194f2e0-7b3e-7610-8284-5c26e8b0bb05","entitlementId": no_show.entitlement_id,"segmentBookingId":"sb-0194f2e0-7b3e-7610-8284-5c26e8b0bb11"}),
            ))
            .await
            .unwrap();
        assert_eq!(
            service.get(no_show.entitlement_id).await.unwrap().status,
            EntitlementStatusDto::NoShow
        );
    }

    #[tokio::test]
    async fn fulfillment_completed_without_boarding_is_idempotent_noop() {
        let service = InMemoryEntitlementService::default();
        let issued = service
            .issue(
                IssueEntitlementRequest {
                    segment_booking_id: "sb-0194f2e0-7b3e-7610-8284-5c26e8b0cc11".to_string(),
                    journey_order_id: "ord-0194f2e0-7b3e-7610-8284-5c26e8b0cc12".to_string(),
                    traveler_ref: "tvl-0194f2e0-7b3e-7610-8284-5c26e8b0cc13".to_string(),
                    segment_ref: "seg-0194f2e0-7b3e-7610-8284-5c26e8b0cc14".to_string(),
                    issue_purpose: IssuePurposeDto::Initial,
                    seat_preferences: None,
                    scheduled_service_ref: None,
                    service_date: None,
                    capacity_hold_id: None,
                    capacity_unit_ref: None,
                    interval: None,
                    class_ref: None,
                    expires_at: None,
                },
                "0194f2e0-7b3e-7610-8284-5c26e8b0cc01".to_string(),
                "corr-0194f2e0-7b3e-7610-8284-5c26e8b0cc02".to_string(),
            )
            .await
            .unwrap();
        service
            .handle_subscribed_event(application::EventEnvelope::new(
                "FulfillmentCompleted",
                current_rfc3339(),
                "corr-0194f2e0-7b3e-7610-8284-5c26e8b0cc03",
                Some("evt-0194f2e0-7b3e-7610-8284-5c26e8b0cc04"),
                "fulfillment",
                json!({"fulfillmentRecordId":"fr-0194f2e0-7b3e-7610-8284-5c26e8b0cc05","entitlementId": issued.entitlement_id}),
            ))
            .await
            .unwrap();
        assert_eq!(
            service.get(issued.entitlement_id).await.unwrap().status,
            EntitlementStatusDto::Issued
        );
    }

    #[tokio::test]
    async fn http_application_service_maps_domain_invariant_to_domain_rule_violation() {
        let service = InMemoryEntitlementService::default();
        let response = service
            .issue(
                IssueEntitlementRequest {
                    segment_booking_id: "sb-0194f2e0-7b3e-7610-0284-5c26e8b0e111".to_string(),
                    journey_order_id: "ord-0194f2e0-7b3e-7610-0284-5c26e8b0e222".to_string(),
                    traveler_ref: "tvl-0194f2e0-7b3e-7610-0284-5c26e8b0e333".to_string(),
                    segment_ref: "seg-0194f2e0-7b3e-7610-0284-5c26e8b0e444".to_string(),
                    issue_purpose: IssuePurposeDto::Initial,
                    seat_preferences: None,
                    scheduled_service_ref: None,
                    service_date: None,
                    capacity_hold_id: None,
                    capacity_unit_ref: None,
                    interval: None,
                    class_ref: None,
                    expires_at: None,
                },
                "018f2e07-b3e7-7100-8284-5c26e8b0d001".to_string(),
                "corr-domain-invariant".to_string(),
            )
            .await
            .unwrap();

        {
            let mut state = service.state.lock().expect("state lock poisoned");
            let aggregate = state.aggregates.get_mut(&response.entitlement_id).unwrap();
            aggregate
                .accept_fulfillment_fact(AcceptFulfillmentFact {
                    command_id: CommandId::new(format!("cmd-{}", uuid::Uuid::now_v7())).unwrap(),
                    fact: FulfillmentFact::BoardingVerified {
                        fact_ref: FulfillmentFactRef::new("fulfillment-domain-invariant").unwrap(),
                    },
                    audit: audit_builder("corr-domain-invariant", "test boarding fact").unwrap(),
                })
                .unwrap();
        }

        let error = service
            .void(
                response.entitlement_id,
                VoidEntitlementRequest {
                    reason: VoidReasonDto::Refund,
                    policy: VoidPolicyDto::Normal,
                    business_case_ref: Some("case-domain-invariant".to_string()),
                },
                "018f2e07-b3e7-7100-8284-5c26e8b0d002".to_string(),
                "corr-domain-invariant".to_string(),
            )
            .await
            .unwrap_err();
        assert!(matches!(error, ApiErrorKind::DomainRuleViolation(_)));
    }

    #[tokio::test]
    async fn post_sales_retry_after_publish_failure_publishes_pending_event_once() {
        let publisher = Arc::new(adapters::messaging::InMemoryEventPublisher::default());
        let service = InMemoryEntitlementService::new(publisher.clone());
        let issue_response = service
            .issue(
                IssueEntitlementRequest {
                    segment_booking_id: "sb-0194f2e0-7b3e-7610-0284-5c26e8b0e111".to_string(),
                    journey_order_id: "ord-0194f2e0-7b3e-7610-0284-5c26e8b0e222".to_string(),
                    traveler_ref: "tvl-0194f2e0-7b3e-7610-0284-5c26e8b0e333".to_string(),
                    segment_ref: "seg-0194f2e0-7b3e-7610-0284-5c26e8b0e444".to_string(),
                    issue_purpose: IssuePurposeDto::Initial,
                    seat_preferences: None,
                    scheduled_service_ref: None,
                    service_date: None,
                    capacity_hold_id: None,
                    capacity_unit_ref: None,
                    interval: None,
                    class_ref: None,
                    expires_at: None,
                },
                "018f2e07-b3e7-7100-8284-5c26e8b0e001".to_string(),
                "corr-post-sales-retry".to_string(),
            )
            .await
            .unwrap();
        publisher.fail_next("redis unavailable");
        let envelope = application::EventEnvelope::new(
            "PostSalesApproved",
            current_rfc3339(),
            "corr-0194f2e0-7b3e-7610-0284-5c26e8b0e555",
            Some("evt-0194f2e0-7b3e-7610-0284-5c26e8b0e666"),
            "post-sales",
            json!({
                "caseId": "psc-0194f2e0-7b3e-7610-0284-5c26e8b0e777",
                "orderId": "ord-0194f2e0-7b3e-7610-0284-5c26e8b0e222",
                "approvedActions": [
                    {"type": "VOID_ENTITLEMENT", "entitlementId": issue_response.entitlement_id, "reason": "REFUND", "policy": "NORMAL"}
                ]
            }),
        );

        assert!(matches!(
            service.handle_subscribed_event(envelope.clone()).await,
            Err(application::HandlerError::Transient(_))
        ));
        assert_eq!(
            publisher
                .published()
                .iter()
                .filter(|envelope| envelope.event_type == "EntitlementVoided")
                .count(),
            0
        );

        service.handle_subscribed_event(envelope).await.unwrap();
        assert_eq!(
            publisher
                .published()
                .iter()
                .filter(|envelope| envelope.event_type == "EntitlementVoided")
                .count(),
            1
        );
        assert!(service.state.lock().unwrap().pending_events.is_empty());
    }

    #[tokio::test]
    async fn post_sales_approved_contract_payload_voids_order_entitlement_and_publishes_event() {
        let publisher = Arc::new(adapters::messaging::InMemoryEventPublisher::default());
        let service = InMemoryEntitlementService::new(publisher.clone());
        let issue_response = service
            .issue(
                IssueEntitlementRequest {
                    segment_booking_id: "sb-0194f2e0-7b3e-7610-0284-5c26e8b0f111".to_string(),
                    journey_order_id: "ord-0194f2e0-7b3e-7610-0284-5c26e8b0f222".to_string(),
                    traveler_ref: "tvl-0194f2e0-7b3e-7610-0284-5c26e8b0f333".to_string(),
                    segment_ref: "seg-0194f2e0-7b3e-7610-0284-5c26e8b0f444".to_string(),
                    issue_purpose: IssuePurposeDto::Initial,
                    seat_preferences: None,
                    scheduled_service_ref: None,
                    service_date: None,
                    capacity_hold_id: None,
                    capacity_unit_ref: None,
                    interval: None,
                    class_ref: None,
                    expires_at: None,
                },
                "018f2e07-b3e7-7100-8284-5c26e8b0f001".to_string(),
                "corr-post-sales".to_string(),
            )
            .await
            .unwrap();

        service
            .apply_subscribed_event(application::EventEnvelope::new(
                "PostSalesApproved",
                current_rfc3339(),
                "corr-0194f2e0-7b3e-7610-0284-5c26e8b0f555",
                Some("evt-0194f2e0-7b3e-7610-0284-5c26e8b0f666"),
                "post-sales",
                json!({
                    "caseId": "psc-0194f2e0-7b3e-7610-0284-5c26e8b0f777",
                    "orderId": "ord-0194f2e0-7b3e-7610-0284-5c26e8b0f222",
                    "approvedActions": [
                        {"type": "VOID_ENTITLEMENT", "entitlementId": issue_response.entitlement_id, "reason": "REFUND", "policy": "NORMAL"}
                    ]
                }),
            ))
            .await
            .unwrap();

        let details = service
            .get(issue_response.entitlement_id.clone())
            .await
            .unwrap();
        assert_eq!(details.status, EntitlementStatusDto::Voided);
        let published = publisher.published();
        let voided = published
            .iter()
            .find(|envelope| envelope.event_type == "EntitlementVoided")
            .expect("bus-triggered void publishes EntitlementVoided");
        assert_eq!(
            voided.payload["entitlementId"],
            issue_response.entitlement_id
        );
        assert_eq!(
            voided.payload["segmentBookingId"],
            "sb-0194f2e0-7b3e-7610-0284-5c26e8b0f111"
        );
        assert_eq!(voided.payload["reason"], "REFUND");
        assert_eq!(voided.payload["policy"], "NORMAL");
        assert_eq!(
            voided.payload["businessCaseRef"],
            "psc-0194f2e0-7b3e-7610-0284-5c26e8b0f777"
        );
        assert!(voided.payload.get("status").is_none());
    }

    #[test]
    fn post_sales_void_action_requires_contract_fields_and_enums() {
        let missing_entitlement = approved_void_actions(Some(&json!({
            "steps": [{"type": "VOID_ENTITLEMENT", "reason": "REFUND", "policy": "NORMAL"}]
        })))
        .unwrap_err();
        assert!(missing_entitlement.contains("entitlementId"));

        let missing_reason = approved_void_actions(Some(&json!({
            "steps": [{"type": "VOID_ENTITLEMENT", "entitlementId": "ent-0194f2e0-7b3e-7610-0284-5c26e8b0f111", "policy": "NORMAL"}]
        })))
        .unwrap_err();
        assert!(missing_reason.contains("reason"));

        let invalid_policy = approved_void_actions(Some(&json!({
            "steps": [{"type": "VOID_ENTITLEMENT", "entitlementId": "ent-0194f2e0-7b3e-7610-0284-5c26e8b0f111", "reason": "REFUND", "policy": "FORCE"}]
        })))
        .unwrap_err();
        assert!(invalid_policy.contains("invalid policy"));
    }

    #[tokio::test]
    async fn post_sales_approved_without_void_actions_is_ackable_noop() {
        let service = InMemoryEntitlementService::default();
        service
            .apply_subscribed_event(application::EventEnvelope::new(
                "PostSalesApproved",
                current_rfc3339(),
                "corr-0194f2e0-7b3e-7610-0284-5c26e8b0f888",
                Some("evt-0194f2e0-7b3e-7610-0284-5c26e8b0f999"),
                "post-sales",
                json!({
                    "caseId": "psc-0194f2e0-7b3e-7610-0284-5c26e8b0faaa",
                    "orderId": "ord-0194f2e0-7b3e-7610-0284-5c26e8b0fbbb",
                    "approvedActions": []
                }),
            ))
            .await
            .unwrap();
    }
}
