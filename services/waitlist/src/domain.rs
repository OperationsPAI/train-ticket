use crate::utils::*;
use crate::*;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::{HashMap, VecDeque};
use std::fmt;

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
    pub(crate) fn is_active(self) -> bool {
        matches!(
            self,
            Self::Draft | Self::Queued | Self::Matching | Self::Suspended
        )
    }
    pub(crate) fn can_transition_to(self, next: Self) -> bool {
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
    pub account_id: String,
    pub traveler_ref: String,
    pub segment_ref: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub travel_class: Option<String>,
    pub deadline: String,
    pub payment_guarantee_ref: String,
    pub itinerary_ref: String,
    pub intent_fingerprint: String,
    pub status: WaitlistStatus,
    pub created_at: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub queued_at: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub order_ref: Option<String>,
    #[serde(default)]
    pub fulfillment_idempotency_keys: Option<FulfillmentIdempotencyKeys>,
    #[serde(default)]
    pub version: i64,
}
impl WaitlistRequest {
    pub fn create(
        command: CreateWaitlistCommand,
        now: String,
    ) -> Result<(Self, Vec<WaitlistEvent>), WaitlistError> {
        validate_non_empty(&command.account_id, "accountId")?;
        validate_prefixed_uuid(&command.traveler_ref, "travelerRef", "tvl-")?;
        validate_non_empty(&command.segment_ref, "segmentRef")?;
        validate_non_empty(&command.intent_fingerprint, "intentFingerprint")?;
        validate_non_empty(&command.itinerary_ref, "itineraryRef")?;
        validate_payment_guarantee(&command.payment_guarantee_ref)?;
        if !deadline_after(&command.deadline, &now) {
            return Err(WaitlistError::DomainRuleViolation(
                "deadline must be in the future".into(),
            ));
        }
        let mut r = Self {
            waitlist_request_id: format!("wlr-{}", uuid::Uuid::now_v7()),
            account_id: command.account_id,
            traveler_ref: command.traveler_ref,
            segment_ref: command.segment_ref,
            travel_class: command.travel_class,
            deadline: command.deadline,
            payment_guarantee_ref: command.payment_guarantee_ref,
            itinerary_ref: command.itinerary_ref,
            intent_fingerprint: command.intent_fingerprint,
            status: WaitlistStatus::Draft,
            created_at: now.clone(),
            queued_at: None,
            order_ref: None,
            fulfillment_idempotency_keys: None,
            version: 1,
        };
        let created = WaitlistEvent::created(&r, now.clone());
        let payment = WaitlistEvent::payment(&r, now.clone());
        r.transition(WaitlistStatus::Queued)?;
        r.queued_at = Some(now.clone());
        let queued = WaitlistEvent::queued(&r, now, None, None);
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
        let order_key = self
            .fulfillment_idempotency_keys
            .get_or_insert_with(FulfillmentIdempotencyKeys::new)
            .order
            .clone();
        Ok(WaitlistEvent::match_started(self, release, order_key, now))
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
        Ok(WaitlistEvent::queued(self, now, order, None))
    }
    pub fn requeue_after_fulfillment_rejected(
        &mut self,
        reason: String,
        now: String,
    ) -> Result<WaitlistEvent, WaitlistError> {
        if self.status == WaitlistStatus::Matching {
            self.transition(WaitlistStatus::Queued)?;
        }
        self.queued_at = Some(now.clone());
        self.order_ref = None;
        Ok(WaitlistEvent::queued(self, now, None, Some(reason)))
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

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FulfillmentIdempotencyKeys {
    pub quote: String,
    pub offer: String,
    pub order: String,
}
impl FulfillmentIdempotencyKeys {
    pub fn new() -> Self {
        Self {
            quote: uuid::Uuid::now_v7().to_string(),
            offer: uuid::Uuid::now_v7().to_string(),
            order: uuid::Uuid::now_v7().to_string(),
        }
    }
}

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
    pub(crate) fn envelope(
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
            json!({"waitlistRequestId":r.waitlist_request_id,"accountId":r.account_id,"travelerRef":r.traveler_ref,"segmentRef":r.segment_ref,"travelClass":r.travel_class,"deadline":r.deadline,"paymentGuaranteeRef":r.payment_guarantee_ref,"itineraryRef":r.itinerary_ref,"intentFingerprint":r.intent_fingerprint,"status":"DRAFT","createdAt":t}),
        )
    }
    fn payment(r: &WaitlistRequest, t: String) -> Self {
        Self::Payment(
            json!({"waitlistRequestId":r.waitlist_request_id,"accountId":r.account_id,"travelerRef":r.traveler_ref,"paymentGuaranteeRef":r.payment_guarantee_ref,"requestedAt":t,"status":r.status.as_contract()}),
        )
    }
    fn queued(
        r: &WaitlistRequest,
        t: String,
        order: Option<String>,
        requeue_reason: Option<String>,
    ) -> Self {
        let mut v = json!({"waitlistRequestId":r.waitlist_request_id,"accountId":r.account_id,"travelerRef":r.traveler_ref,"segmentRef":r.segment_ref,"travelClass":r.travel_class,"itineraryRef":r.itinerary_ref,"intentFingerprint":r.intent_fingerprint,"queuedAt":t,"status":"QUEUED"});
        if let Some(o) = order {
            v["journeyOrderRef"] = json!(o)
        }
        if let Some(reason) = requeue_reason {
            v["requeueReason"] = json!(reason)
        }
        Self::Queued(v)
    }
    fn match_started(r: &WaitlistRequest, rel: String, key: String, t: String) -> Self {
        Self::MatchStarted(
            json!({"waitlistRequestId":r.waitlist_request_id,"accountId":r.account_id,"travelerRef":r.traveler_ref,"segmentRef":r.segment_ref,"travelClass":r.travel_class,"matchedCapacityReleaseRef":rel,"journeyOrderIdempotencyKey":key,"startedAt":t,"status":"MATCHING"}),
        )
    }
    fn fulfilled(r: &WaitlistRequest, order: String, t: String) -> Self {
        Self::Fulfilled(
            json!({"waitlistRequestId":r.waitlist_request_id,"accountId":r.account_id,"travelerRef":r.traveler_ref,"segmentRef":r.segment_ref,"travelClass":r.travel_class,"journeyOrderRef":order,"fulfilledAt":t,"status":"FULFILLED"}),
        )
    }
    fn cancelled(r: &WaitlistRequest, reason: String, t: String) -> Self {
        Self::Cancelled(
            json!({"waitlistRequestId":r.waitlist_request_id,"accountId":r.account_id,"travelerRef":r.traveler_ref,"segmentRef":r.segment_ref,"travelClass":r.travel_class,"cancelledAt":t,"reason":reason,"status":"CANCELLED"}),
        )
    }
    fn expired(r: &WaitlistRequest, t: String) -> Self {
        Self::Expired(
            json!({"waitlistRequestId":r.waitlist_request_id,"accountId":r.account_id,"travelerRef":r.traveler_ref,"segmentRef":r.segment_ref,"travelClass":r.travel_class,"deadline":r.deadline,"expiredAt":t,"status":"EXPIRED"}),
        )
    }
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CreateWaitlistCommand {
    pub account_id: String,
    pub traveler_ref: String,
    pub segment_ref: String,
    pub travel_class: Option<String>,
    pub deadline: String,
    pub payment_guarantee_ref: String,
    pub itinerary_ref: String,
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
    pub account_id: String,
    pub traveler_ref: String,
    pub segment_ref: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub travel_class: Option<String>,
    pub deadline: String,
    pub payment_guarantee_ref: String,
    pub itinerary_ref: String,
    pub intent_fingerprint: String,
    pub status: WaitlistStatus,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub journey_order_ref: Option<String>,
}
impl From<&WaitlistRequest> for WaitlistResource {
    fn from(r: &WaitlistRequest) -> Self {
        Self {
            waitlist_request_id: r.waitlist_request_id.clone(),
            account_id: r.account_id.clone(),
            traveler_ref: r.traveler_ref.clone(),
            segment_ref: r.segment_ref.clone(),
            travel_class: r.travel_class.clone(),
            deadline: r.deadline.clone(),
            payment_guarantee_ref: r.payment_guarantee_ref.clone(),
            itinerary_ref: r.itinerary_ref.clone(),
            intent_fingerprint: r.intent_fingerprint.clone(),
            status: r.status,
            journey_order_ref: r.order_ref.clone(),
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
    pub(crate) fn code(&self) -> &'static str {
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
    pub(crate) fn message(&self) -> &str {
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

#[cfg(test)]
mod idempotency_key_persistence_tests {
    use super::*;

    #[test]
    fn fulfillment_keys_survive_snapshot_roundtrip() {
        let (mut request, _events) = WaitlistRequest::create(
            CreateWaitlistCommand {
                account_id: "acc-018f0000-0000-7000-8000-000000000001".into(),
                traveler_ref: "tvl-018f0000-0000-7000-8000-000000000002".into(),
                segment_ref: "seg-018f0000-0000-7000-8000-000000000003".into(),
                travel_class: None,
                deadline: "2027-01-01T00:00:00Z".into(),
                payment_guarantee_ref: "pay-auth-018f0000-0000-7000-8000-000000000004".into(),
                itinerary_ref: "itn-018f0000-0000-7000-8000-000000000005".into(),
                intent_fingerprint: "tvl-x:seg-y".into(),
            },
            "2026-07-09T00:00:00Z".into(),
        )
        .expect("create");
        while request.status != WaitlistStatus::Queued {
            request
                .transition(WaitlistStatus::Queued)
                .expect("reach QUEUED");
        }
        request
            .start_matching("evt-release-1".into(), "2026-07-09T00:00:02Z".into())
            .expect("matching");
        let keys = request
            .fulfillment_idempotency_keys
            .clone()
            .expect("keys generated on matching");

        // The exact round-3 blocker: keys must live in the persisted
        // snapshot so a redelivered release resumes with the SAME keys.
        let snapshot = serde_json::to_value(&request).expect("serialize");
        let restored: WaitlistRequest = serde_json::from_value(snapshot).expect("deserialize");
        let restored_keys = restored
            .fulfillment_idempotency_keys
            .expect("keys survive snapshot roundtrip");
        assert_eq!(restored_keys.quote, keys.quote);
        assert_eq!(restored_keys.offer, keys.offer);
        assert_eq!(restored_keys.order, keys.order);
    }
}
