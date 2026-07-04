use std::collections::{HashMap, HashSet};
use std::fmt;

use axum::Router;
use serde::Serialize;
use shared_kernel::{OpenTelemetryObserver, RuntimeConfig, apply_runtime, router_with_config};

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

pub fn router() -> Router {
    router_with_config(runtime_config())
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
    Used,
}

impl EntitlementStatus {
    pub fn is_terminal(&self) -> bool {
        matches!(self, Self::Voided | Self::Expired | Self::Used)
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
    id: EntitlementId,
    journey_order_ref: JourneyOrderRef,
    segment_booking_ref: SegmentBookingRef,
    traveler_ref: TravelerRef,
    segment_ref: SegmentRef,
    purpose: IssuePurpose,
    idempotency_key: IssueIdempotencyKey,
    validity_window: ValidityWindow,
    status: EntitlementStatus,
    credential_ref: Option<CredentialRef>,
    fulfillment_use_state: FulfillmentUseState,
    audit_trail: Vec<EntitlementLifecycleAudit>,
    processed_command_ids: HashSet<CommandId>,
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
            EntitlementStatus::Voided | EntitlementStatus::Expired | EntitlementStatus::Used => {
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
            EntitlementStatus::Voided | EntitlementStatus::Expired | EntitlementStatus::Used => {
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
            EntitlementStatus::Voided | EntitlementStatus::Expired | EntitlementStatus::Used
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
            EntitlementStatus::Expired | EntitlementStatus::Used
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
            EntitlementStatus::Voided | EntitlementStatus::Used
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
            EntitlementStatus::Voided | EntitlementStatus::Expired | EntitlementStatus::Used => {
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
                self.fulfillment_use_state = FulfillmentUseState::BoardingComplete {
                    fact_ref: fact_ref.clone(),
                };
                self.status = EntitlementStatus::Used;
                Ok(vec![DomainEvent::EntitlementUsed {
                    entitlement_id: self.id.clone(),
                    fulfillment_fact_ref: fact_ref,
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
    EntitlementUsed {
        entitlement_id: EntitlementId,
        fulfillment_fact_ref: FulfillmentFactRef,
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
        let _router = router();
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
            let response = router()
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
    fn normal_void_is_rejected_after_boarding_or_used_facts() {
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
