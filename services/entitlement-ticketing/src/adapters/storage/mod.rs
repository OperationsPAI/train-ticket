use crate::*;
use rust_kit::messaging::stream_for_producer;
use rust_kit::storage::{
    DbIdempotencyStore, IdempotencyTxDecision, OutboxAppender, PgTransaction, Snapshot,
    SnapshotRepository, Storage, StorageError, mark_event_processing,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sqlx::PgPool;

const ENTITLEMENT_TABLE: &str = "entitlement_snapshots";
const PRODUCER: &str = "entitlement-ticketing";
const MAX_RETRIES: usize = 3;

type PgResult<T> = Result<T, ApiErrorKind>;

/// How often `train_ticket.ticketing.active` is rebuilt from durable rows. The
/// same cadence the other business levels use.
const ACTIVE_TICKETING_REFRESH: std::time::Duration = std::time::Duration::from_secs(60);

const ACTIVE_TICKETING_SQL: &str = r#"
SELECT
  coalesce(data #>> '{status,kind}', '') AS status_kind,
  coalesce(data #>> '{status,retryable}', '') AS retryable,
  coalesce(data #>> '{credentialRef,credentialType}', '') AS credential_type,
  count(*)::bigint AS tickets
FROM entitlement_snapshots
WHERE data #>> '{status,kind}' IN ('REQUESTED', 'ISSUE_FAILED', 'ISSUED', 'SUSPENDED')
GROUP BY 1, 2, 3
"#;

#[derive(Clone)]
pub struct PostgresEntitlementService {
    storage: Storage,
    entitlement_repo: SnapshotRepository,
    /// Publishes `train_ticket.ticketing.*`. See `metrics.rs`.
    metrics: crate::metrics::TicketingMetrics,
}

impl PostgresEntitlementService {
    pub async fn from_env() -> Result<Self, StorageError> {
        let storage = Storage::from_env().await?;
        let migrations_dir =
            std::env::var("MIGRATIONS_DIR").unwrap_or_else(|_| "/app/migrations".into());
        match storage.migrate_dir(&migrations_dir).await {
            Ok(()) => {}
            Err(primary_error) => {
                if migrations_dir == "/app/migrations" {
                    storage
                        .migrate_dir("services/entitlement-ticketing/migrations")
                        .await?;
                } else {
                    return Err(primary_error);
                }
            }
        }
        Self::from_storage(storage)
    }
    pub fn new(pool: PgPool) -> Result<Self, StorageError> {
        let storage = Storage::new(pool);
        storage.mark_migrations_ready();
        Self::from_storage(storage)
    }
    pub fn from_storage(storage: Storage) -> Result<Self, StorageError> {
        Ok(Self {
            storage,
            entitlement_repo: SnapshotRepository::new(ENTITLEMENT_TABLE)?,
            metrics: crate::metrics::TicketingMetrics::register(&rust_kit::metrics::meter(PRODUCER)),
        })
    }
    pub fn pool(&self) -> &PgPool {
        self.storage.pool()
    }
    pub async fn is_ready(&self) -> bool {
        self.storage.is_ready().await
    }

    /// Record the outcome of a whole ticketing call.
    ///
    /// `domain_rejection_recorded` says the domain site already published this
    /// rejection, with the reason it still had in hand. Recording it again from
    /// the flattened error would count one rejection twice, and the reason would
    /// have to be guessed from a message.
    fn record_transport_outcome(
        &self,
        stage: crate::metrics::TicketStage,
        ticket_type: crate::metrics::TicketType,
        outcome: Result<(), &ApiErrorKind>,
        domain_rejection_recorded: bool,
    ) {
        if domain_rejection_recorded {
            return;
        }
        let transition = match outcome {
            Ok(()) => crate::metrics::TicketingTransition {
                stage,
                outcome: crate::metrics::TicketOutcome::Accepted,
                ticket_type,
                reason_code: crate::metrics::TicketReasonCode::None,
            },
            Err(error) => crate::metrics::TicketingTransition {
                stage,
                outcome: transport_outcome(error),
                ticket_type,
                reason_code: transport_reason(error),
            },
        };
        self.metrics.transition(transition);
    }

    /// Record a rejection the domain decided, with the reason the domain named.
    fn record_domain_rejection(
        &self,
        stage: crate::metrics::TicketStage,
        ticket_type: crate::metrics::TicketType,
        error: &EntitlementError,
    ) {
        self.metrics.transition(crate::metrics::TicketingTransition {
            stage,
            outcome: crate::metrics::TicketOutcome::Rejected,
            ticket_type,
            reason_code: domain_reason(error),
        });
    }

    /// Rebuild `train_ticket.ticketing.active` from durable entitlement rows.
    pub async fn refresh_active_ticketing(&self) -> Result<(), sqlx::Error> {
        let rows: Vec<(String, String, String, i64)> = sqlx::query_as(ACTIVE_TICKETING_SQL)
            .fetch_all(self.pool())
            .await?;
        let buckets: Vec<crate::metrics::DurableEntitlementBucket> = rows
            .into_iter()
            .map(
                |(status_kind, retryable, credential_type, tickets)| {
                    crate::metrics::DurableEntitlementBucket {
                        status_kind,
                        retryable: match retryable.trim() {
                            "true" => Some(true),
                            "false" => Some(false),
                            _ => None,
                        },
                        credential_type: (!credential_type.trim().is_empty())
                            .then_some(credential_type),
                        tickets,
                    }
                },
            )
            .collect();
        self.metrics
            .publish_active(crate::metrics::build_active_rows(&buckets));
        Ok(())
    }

    /// Refresh the active level on a timer. A failed refresh keeps the previous
    /// level: an unreadable table is unknown, not "no tickets in progress".
    pub fn spawn_active_ticketing_refresh(self: std::sync::Arc<Self>) {
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(ACTIVE_TICKETING_REFRESH);
            loop {
                ticker.tick().await;
                if let Err(error) = self.refresh_active_ticketing().await {
                    log::warn!("ticketing active metric refresh failed: {error}");
                }
            }
        });
    }

    async fn idempotency_replay<T: for<'de> Deserialize<'de>>(
        &self,
        key: &str,
        fingerprint: &str,
    ) -> PgResult<Option<T>> {
        let Some(record) = DbIdempotencyStore::new(self.pool().clone())
            .get_async(key)
            .await
            .map_err(to_api_storage)?
        else {
            return Ok(None);
        };
        if record.fingerprint != fingerprint {
            return Err(ApiErrorKind::IdempotencyKeyReused(
                "Idempotency-Key was reused with a different request body".into(),
            ));
        }
        if record.status_code == 0 { return Ok(None); }
        serde_json::from_value(record.body)
            .map(Some)
            .map_err(|e| ApiErrorKind::Unavailable(e.to_string()))
    }
    async fn claim_idempotency<T: for<'de> Deserialize<'de>>(
        &self,
        tx: &mut PgTransaction<'_>,
        key: &str,
        fingerprint: &str,
    ) -> PgResult<Option<T>> {
        match DbIdempotencyStore::claim_response(tx, key, fingerprint)
            .await
            .map_err(to_api_storage)?
        {
            IdempotencyTxDecision::Claimed => Ok(None),
            IdempotencyTxDecision::Replay(record) => serde_json::from_value(record.body)
                .map(Some)
                .map_err(|e| ApiErrorKind::Unavailable(e.to_string())),
        }
    }
    async fn finish_idempotency(
        &self,
        tx: &mut PgTransaction<'_>,
        key: &str,
        fingerprint: &str,
        status: u16,
        body: Value,
    ) -> PgResult<()> {
        DbIdempotencyStore::record_response(tx, key, fingerprint, status, body)
            .await
            .map_err(to_api_storage)?;
        Ok(())
    }
    async fn next_credential_no(&self, tx: &mut PgTransaction<'_>) -> PgResult<String> {
        let (seq,): (i64,) = sqlx::query_as("SELECT nextval('entitlement_ticket_no_seq')")
            .fetch_one(&mut **tx)
            .await
            .map_err(to_api_storage)?;
        Ok(format!("ETK-{seq:012}"))
    }
    async fn save_entitlement(
        &self,
        tx: &mut PgTransaction<'_>,
        entitlement: &Entitlement,
        expected: Option<i64>,
    ) -> PgResult<i64> {
        self.entitlement_repo
            .save(
                tx,
                entitlement.id().as_str(),
                expected,
                &EntitlementSnapshot::from_domain(entitlement),
            )
            .await
            .map_err(to_api_storage)
    }
    async fn load_entitlement(
        &self,
        tx: &mut PgTransaction<'_>,
        id: &str,
    ) -> PgResult<Option<Snapshot<EntitlementSnapshot>>> {
        self.entitlement_repo
            .get::<EntitlementSnapshot>(&mut **tx, id)
            .await
            .map_err(to_api_storage)
    }
    pub async fn handle_subscribed_event(
        &self,
        envelope: application::EventEnvelope,
    ) -> Result<(), application::HandlerError> {
        self.apply_subscribed_event(envelope)
            .await
            .map_err(|error| match error {
                InboundEventError::Transient(m) => application::HandlerError::Transient(m),
                InboundEventError::Fatal(m) => application::HandlerError::Fatal(m),
            })
    }
    async fn apply_subscribed_event(
        &self,
        envelope: application::EventEnvelope,
    ) -> Result<(), InboundEventError> {
        match envelope.event_type.as_str() {
            "BoardingVerified" => self.apply_boarding_verified(envelope).await,
            "FulfillmentCompleted" => self.apply_fulfillment_completed(envelope).await,
            "NoShowRecorded" => self.apply_no_show_recorded(envelope).await,
            "PostSalesApproved" => self.apply_post_sales_approved(envelope).await,
            "SegmentTicketed" => self.apply_segment_ticketed(envelope).await,
            _ => Ok(()),
        }
    }
    async fn apply_boarding_verified(
        &self,
        envelope: application::EventEnvelope,
    ) -> Result<(), InboundEventError> {
        for field in [
            "fulfillmentRecordId",
            "entitlementId",
            "segmentBookingId",
            "journeyOrderId",
            "travelerId",
            "segmentRef",
            "source",
            "sourceEventId",
            "occurredAt",
            "receivedAt",
        ] {
            require_string(&envelope.payload, field)?;
        }
        let entitlement_id = require_string(&envelope.payload, "entitlementId")?;
        let fact_ref = require_string(&envelope.payload, "sourceEventId")?;
        self.accept_fulfillment_fact(
            envelope,
            &entitlement_id,
            FulfillmentFact::BoardingVerified {
                fact_ref: FulfillmentFactRef::new(fact_ref).map_err(inbound_fatal)?,
            },
            "event bus boarding verified",
            false,
        )
        .await
    }
    async fn apply_fulfillment_completed(
        &self,
        envelope: application::EventEnvelope,
    ) -> Result<(), InboundEventError> {
        for field in [
            "fulfillmentRecordId",
            "entitlementId",
            "segmentBookingId",
            "journeyOrderId",
            "travelerId",
            "completedAt",
            "completionSource",
        ] {
            require_string(&envelope.payload, field)?;
        }
        let entitlement_id = require_string(&envelope.payload, "entitlementId")?;
        let fact_ref = require_string(&envelope.payload, "fulfillmentRecordId")?;
        self.accept_fulfillment_fact(
            envelope,
            &entitlement_id,
            FulfillmentFact::BoardingComplete {
                fact_ref: FulfillmentFactRef::new(fact_ref).map_err(inbound_fatal)?,
            },
            "event bus fulfillment completed",
            true,
        )
        .await
    }
    async fn apply_no_show_recorded(
        &self,
        envelope: application::EventEnvelope,
    ) -> Result<(), InboundEventError> {
        for field in [
            "fulfillmentRecordId",
            "entitlementId",
            "segmentBookingId",
            "journeyOrderId",
            "travelerId",
            "segmentRef",
            "reason",
            "assessedAt",
        ] {
            require_string(&envelope.payload, field)?;
        }
        let entitlement_id = require_string(&envelope.payload, "entitlementId")?;
        let fact_ref = require_string(&envelope.payload, "fulfillmentRecordId")?;
        self.accept_fulfillment_fact(
            envelope,
            &entitlement_id,
            FulfillmentFact::NoShowRecorded {
                fact_ref: FulfillmentFactRef::new(fact_ref).map_err(inbound_fatal)?,
            },
            "event bus no-show recorded",
            false,
        )
        .await
    }
    async fn apply_segment_ticketed(
        &self,
        envelope: application::EventEnvelope,
    ) -> Result<(), InboundEventError> {
        require_string(&envelope.payload, "segmentBookingId")?;
        require_string(&envelope.payload, "entitlementId")?;
        let mut tx = self.pool().begin().await.map_err(inbound_transient)?;
        mark_once(&mut tx, &envelope).await?;
        tx.commit().await.map_err(inbound_transient)?;
        Ok(())
    }
    async fn accept_fulfillment_fact(
        &self,
        envelope: application::EventEnvelope,
        entitlement_id: &str,
        fact: FulfillmentFact,
        audit_reason: &'static str,
        ignore_missing_boarding: bool,
    ) -> Result<(), InboundEventError> {
        for attempt in 0..MAX_RETRIES {
            let result = self
                .try_accept_fulfillment_fact(
                    &envelope,
                    entitlement_id,
                    fact.clone(),
                    audit_reason,
                    ignore_missing_boarding,
                )
                .await;
            match result {
                Err(InboundEventError::Transient(m))
                    if m.contains("optimistic concurrency conflict")
                        && attempt + 1 < MAX_RETRIES =>
                {
                    continue;
                }
                result => return result,
            }
        }
        Err(InboundEventError::Transient(
            "entitlement write conflict".into(),
        ))
    }
    async fn try_accept_fulfillment_fact(
        &self,
        envelope: &application::EventEnvelope,
        entitlement_id: &str,
        fact: FulfillmentFact,
        audit_reason: &'static str,
        ignore_missing_boarding: bool,
    ) -> Result<(), InboundEventError> {
        let mut tx = self.pool().begin().await.map_err(inbound_transient)?;
        if !mark_once(&mut tx, envelope).await? {
            tx.rollback().await.map_err(inbound_transient)?;
            return Ok(());
        }
        let Some(snapshot) = self
            .load_entitlement(&mut tx, entitlement_id)
            .await
            .map_err(inbound_from_api_error)?
        else {
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        };
        let mut entitlement = snapshot
            .data
            .try_into_domain()
            .map_err(inbound_from_api_error)?;
        let accepted = entitlement.accept_fulfillment_fact(AcceptFulfillmentFact {
            command_id: CommandId::new(format!("cmd-{}", uuid::Uuid::now_v7()))
                .map_err(inbound_fatal)?,
            fact,
            audit: audit_builder(&envelope.correlation_id, audit_reason).map_err(inbound_fatal)?,
        });
        if ignore_missing_boarding
            && matches!(
                accepted,
                Err(EntitlementError::BoardingRequiredForCompletion)
            )
        {
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        }
        if let Err(error) = accepted {
            // A refused boarding, check-in or no-show fact is a real ticketing
            // transition: the ticket was presented and not accepted.
            self.record_domain_rejection(
                crate::metrics::TicketStage::Validation,
                ticket_type_of(&entitlement),
                &error,
            );
            return Err(inbound_fatal(error));
        }
        self.metrics.transition(crate::metrics::TicketingTransition {
            stage: crate::metrics::TicketStage::Validation,
            outcome: crate::metrics::TicketOutcome::Accepted,
            ticket_type: ticket_type_of(&entitlement),
            reason_code: crate::metrics::TicketReasonCode::None,
        });
        self.save_entitlement(&mut tx, &entitlement, Some(snapshot.version))
            .await
            .map_err(inbound_from_api_error)?;
        tx.commit().await.map_err(inbound_transient)?;
        Ok(())
    }
    async fn apply_post_sales_approved(
        &self,
        envelope: application::EventEnvelope,
    ) -> Result<(), InboundEventError> {
        let case_id = require_string(&envelope.payload, "caseId")?;
        let order_id = require_string(&envelope.payload, "orderId")?;
        if envelope
            .payload
            .get("approvedActions")
            .is_none_or(Value::is_null)
        {
            return Err(InboundEventError::Fatal("missing approvedActions".into()));
        }
        let actions = approved_void_actions(envelope.payload.get("approvedActions"))
            .map_err(InboundEventError::Fatal)?;
        if actions.is_empty() {
            let mut tx = self.pool().begin().await.map_err(inbound_transient)?;
            mark_once(&mut tx, &envelope).await?;
            tx.commit().await.map_err(inbound_transient)?;
            return Ok(());
        }
        // The load generator approves a refund and polls the case for ten
        // seconds; measured at the cycle's burst, a failing case's entitlements
        // were voided 7.2 seconds after the approval while this consumer's lag
        // and pending stayed at zero. This line separates delivery from handling:
        // it prints how long this handler's own work took.
        let started = std::time::Instant::now();
        for attempt in 0..MAX_RETRIES {
            let result = self
                .try_apply_post_sales_approved(&envelope, &case_id, &order_id, &actions)
                .await;
            match result {
                Err(InboundEventError::Transient(m))
                    if m.contains("optimistic concurrency conflict")
                        && attempt + 1 < MAX_RETRIES =>
                {
                    continue;
                }
                result => {
                    log::info!(
                        "post-sales approval for case={} order={} handled in {:?} ({} entitlement action(s))",
                        case_id,
                        order_id,
                        started.elapsed(),
                        actions.len()
                    );
                    return result;
                }
            }
        }
        Err(InboundEventError::Transient(
            "entitlement write conflict".into(),
        ))
    }
    async fn try_apply_post_sales_approved(
        &self,
        envelope: &application::EventEnvelope,
        case_id: &str,
        order_id: &str,
        actions: &[ApprovedVoidAction],
    ) -> Result<(), InboundEventError> {
        let mut tx = self.pool().begin().await.map_err(inbound_transient)?;
        if !mark_once(&mut tx, envelope).await? {
            tx.rollback().await.map_err(inbound_transient)?;
            return Ok(());
        }
        let snapshots = self
            .load_entitlements_for_order(&mut tx, order_id)
            .await
            .map_err(inbound_from_api_error)?;
        for snapshot in snapshots {
            let Some(action) = actions
                .iter()
                .find(|action| action.matches_entitlement(&snapshot.id))
            else {
                continue;
            };
            let reason = action.reason.clone();
            let policy = action.policy.clone();
            let mut entitlement = snapshot
                .data
                .clone()
                .try_into_domain()
                .map_err(inbound_from_api_error)?;
            let ticket_type = ticket_type_of(&entitlement);
            let events = match entitlement.void(VoidEntitlement {
                    command_id: CommandId::new(format!("cmd-{}", uuid::Uuid::now_v7()))
                        .map_err(inbound_fatal)?,
                    reason: reason.clone(),
                    policy: policy.clone(),
                    business_case_ref: BusinessCaseRef::new(case_id.to_string())
                        .map_err(inbound_fatal)?,
                    audit: audit_builder(&envelope.correlation_id, "event bus post-sales approved")
                        .map_err(inbound_fatal)?,
                }) {
                Ok(events) => events,
                Err(error) => {
                    self.record_domain_rejection(
                        crate::metrics::TicketStage::Void,
                        ticket_type,
                        &error,
                    );
                    return Err(inbound_fatal(error));
                }
            };
            if events.is_empty() {
                // The entitlement was already voided, so this action changed
                // nothing. That is a no-op, not a second void.
                self.metrics.transition(crate::metrics::TicketingTransition {
                    stage: crate::metrics::TicketStage::Void,
                    outcome: crate::metrics::TicketOutcome::Noop,
                    ticket_type,
                    reason_code: crate::metrics::TicketReasonCode::TerminalStatus,
                });
                continue;
            }
            self.metrics.transition(crate::metrics::TicketingTransition {
                stage: crate::metrics::TicketStage::Void,
                outcome: crate::metrics::TicketOutcome::Accepted,
                ticket_type,
                reason_code: crate::metrics::TicketReasonCode::None,
            });
            self.save_entitlement(&mut tx, &entitlement, Some(snapshot.version))
                .await
                .map_err(inbound_from_api_error)?;
            let seat_allocation_id = snapshot
                .data
                .seat_ref
                .as_ref()
                .map(|seat| seat.seat_allocation_id.clone());
            let outbound = entitlement_voided_envelope(
                &entitlement,
                &reason,
                &policy,
                Some(case_id.to_string()),
                seat_allocation_id,
                &envelope.correlation_id,
            )
            .map_err(inbound_from_api_error)?;
            OutboxAppender::append(&mut tx, &stream_for_producer(PRODUCER), &outbound)
                .await
                .map_err(inbound_transient)?;
        }
        tx.commit().await.map_err(inbound_transient)?;
        Ok(())
    }
    async fn load_entitlements_for_order(
        &self,
        tx: &mut PgTransaction<'_>,
        order_id: &str,
    ) -> PgResult<Vec<Snapshot<EntitlementSnapshot>>> {
        let sql = format!(
            "SELECT id, version, data FROM {ENTITLEMENT_TABLE} WHERE journey_order_id = $1 ORDER BY id"
        );
        let rows: Vec<(String, i64, Value)> = sqlx::query_as(&sql)
            .bind(order_id)
            .fetch_all(&mut **tx)
            .await
            .map_err(to_api_storage)?;
        rows.into_iter()
            .map(|(id, version, data)| {
                Ok(Snapshot {
                    id,
                    version,
                    data: serde_json::from_value(data)
                        .map_err(|e| ApiErrorKind::Unavailable(e.to_string()))?,
                })
            })
            .collect()
    }
}
#[async_trait::async_trait]
impl EntitlementApi for PostgresEntitlementService {
    async fn issue(
        &self,
        command: IssueEntitlementRequest,
        key: String,
        correlation_id: String,
    ) -> PgResult<IssueEntitlementResponse> {
        // The stage is decided before anything else runs: a replacement is a
        // different funnel from a first issue even though the code path is
        // shared, and a rejection has to land in the stage it was attempted in.
        let stage = crate::metrics::TicketStage::from_issue_purpose(command.issue_purpose.to_contract());
        validate_issue_request(&command)?;
        let fingerprint = serde_json::to_string(&command).unwrap_or_default();
        if let Some(response) = self
            .idempotency_replay::<IssueEntitlementResponse>(&key, &fingerprint)
            .await?
        {
            self.metrics.transition(crate::metrics::TicketingTransition {
                stage,
                outcome: crate::metrics::TicketOutcome::Noop,
                ticket_type: crate::metrics::TicketType::ETicket,
                reason_code: crate::metrics::TicketReasonCode::IdempotentReplay,
            });
            return Ok(response);
        }
        let mut domain_rejection_recorded = false;
        let mut result = Err(ApiErrorKind::Conflict(
            "entitlement write conflict".into(),
        ));
        for attempt in 0..MAX_RETRIES {
            match self
                .try_issue(
                    &command,
                    &key,
                    &fingerprint,
                    &correlation_id,
                    &mut domain_rejection_recorded,
                )
                .await
            {
                Err(ApiErrorKind::Conflict(m))
                    if m.contains("optimistic concurrency conflict")
                        && attempt + 1 < MAX_RETRIES =>
                {
                    continue;
                }
                outcome => {
                    result = outcome;
                    break;
                }
            }
        }
        self.record_transport_outcome(
            stage,
            crate::metrics::TicketType::ETicket,
            result.as_ref().map(|_| ()),
            domain_rejection_recorded,
        );
        result
    }
    async fn void(
        &self,
        entitlement_id: String,
        command: VoidEntitlementRequest,
        key: String,
        correlation_id: String,
    ) -> PgResult<VoidEntitlementResponse> {
        validate_prefixed_uuid(&entitlement_id, "entitlementId", "ent-")?;
        if command
            .business_case_ref
            .as_deref()
            .is_some_and(|v| v.trim().is_empty())
        {
            return Err(ApiErrorKind::ValidationFailed(
                "businessCaseRef must not be blank when provided".into(),
            ));
        }
        let fingerprint = format!(
            "{}:{}",
            entitlement_id,
            serde_json::to_string(&command).unwrap_or_default()
        );
        if let Some(response) = self
            .idempotency_replay::<VoidEntitlementResponse>(&key, &fingerprint)
            .await?
        {
            return Ok(response);
        }
        let mut domain_rejection_recorded = false;
        for attempt in 0..MAX_RETRIES {
            let outcome = self
                .try_void(
                    &entitlement_id,
                    &command,
                    &key,
                    &fingerprint,
                    &correlation_id,
                    &mut domain_rejection_recorded,
                )
                .await;
            match &outcome {
                Err(ApiErrorKind::Conflict(m))
                    if m.contains("optimistic concurrency conflict")
                        && attempt + 1 < MAX_RETRIES =>
                {
                    continue;
                }
                _ => {
                    self.record_transport_outcome(
                        crate::metrics::TicketStage::Void,
                        crate::metrics::TicketType::Unknown,
                        outcome.as_ref().map(|_| ()),
                        domain_rejection_recorded,
                    );
                    return outcome;
                }
            }
        }
        let exhausted = Err(ApiErrorKind::Conflict(
            "entitlement write conflict".into(),
        ));
        self.record_transport_outcome(
            crate::metrics::TicketStage::Void,
            crate::metrics::TicketType::Unknown,
            exhausted.as_ref().map(|_| ()),
            domain_rejection_recorded,
        );
        exhausted
    }
    async fn get(&self, entitlement_id: String) -> PgResult<EntitlementDetails> {
        validate_prefixed_uuid(&entitlement_id, "entitlementId", "ent-")?;
        let snapshot = self
            .entitlement_repo
            .get::<EntitlementSnapshot>(self.pool(), &entitlement_id)
            .await
            .map_err(to_api_storage)?
            .ok_or_else(|| ApiErrorKind::NotFound("entitlement not found".into()))?;
        Ok(snapshot.data.to_details())
    }
    async fn list(
        &self,
        journey_order_id: String,
        limit: usize,
        offset: usize,
    ) -> PgResult<PaginatedEntitlements> {
        validate_prefixed_uuid(&journey_order_id, "journeyOrderId", "ord-")?;
        let sql = format!(
            "SELECT data, count(*) OVER() AS total FROM {ENTITLEMENT_TABLE} WHERE journey_order_id = $1 ORDER BY id LIMIT $2 OFFSET $3"
        );
        let rows: Vec<(Value, i64)> = sqlx::query_as(&sql)
            .bind(&journey_order_id)
            .bind(i64::try_from(limit).unwrap_or(i64::MAX))
            .bind(i64::try_from(offset).unwrap_or(i64::MAX))
            .fetch_all(self.pool())
            .await
            .map_err(to_api_storage)?;
        let total = rows.first().map(|row| row.1 as usize).unwrap_or(0);
        let items = rows
            .into_iter()
            .map(|(data, _)| {
                serde_json::from_value::<EntitlementSnapshot>(data)
                    .map(|s| s.to_details())
                    .map_err(|e| ApiErrorKind::Unavailable(e.to_string()))
            })
            .collect::<PgResult<Vec<_>>>()?;
        Ok(PaginatedEntitlements {
            items,
            total,
            limit,
            offset,
        })
    }
}

impl PostgresEntitlementService {
    async fn try_issue(
        &self,
        command: &IssueEntitlementRequest,
        key: &str,
        fingerprint: &str,
        correlation_id: &str,
        domain_rejection_recorded: &mut bool,
    ) -> PgResult<IssueEntitlementResponse> {
        let (preconditions, resolved) = crate::issue_facts::resolve(command, correlation_id).await?;
        let command = &resolved;
        let seat_key = if command.needs_seat_assignment() {
            Some(seat_assignment_idempotency_key(command)?)
        } else {
            None
        };
        let entitlement_id = format!("ent-{}", uuid::Uuid::now_v7());
        let (mut aggregate, _) = Entitlement::request(RequestEntitlement {
            command_id: CommandId::new(format!("cmd-{}", uuid::Uuid::now_v7()))
                .map_err(ApiErrorKind::from)?,
            entitlement_id: EntitlementId::new(entitlement_id.clone())
                .map_err(ApiErrorKind::from)?,
            journey_order_ref: JourneyOrderRef::new(command.journey_order_id.clone())
                .map_err(ApiErrorKind::from)?,
            segment_booking_ref: SegmentBookingRef::new(command.segment_booking_id.clone())
                .map_err(ApiErrorKind::from)?,
            traveler_ref: TravelerRef::new(command.traveler_ref.clone())
                .map_err(ApiErrorKind::from)?,
            segment_ref: SegmentRef::new(command.segment_ref.clone())
                .map_err(ApiErrorKind::from)?,
            purpose: command.issue_purpose.clone().into(),
            validity_window: ValidityWindow::new(
                UnixMillis::new(current_unix_millis()),
                UnixMillis::new(current_unix_millis().saturating_add(86_400_000)),
            )
            .map_err(ApiErrorKind::from)?,
            audit: audit_builder(correlation_id, "HTTP issue entitlement")
                .map_err(ApiErrorKind::from)?,
        })
        .map_err(ApiErrorKind::from)?;
        let mut tx = self.pool().begin().await.map_err(to_api_storage)?;
        sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1,0))")
            .bind(format!("entitlement-issue:{}",command.segment_booking_id)).execute(&mut *tx).await.map_err(to_api_storage)?;
        if let Some(response) = self
            .claim_idempotency::<IssueEntitlementResponse>(&mut tx, key, fingerprint)
            .await?
        {
            tx.commit().await.map_err(to_api_storage)?;
            return Ok(response);
        }
        let mut initial_snapshot = EntitlementSnapshot::from_domain(&aggregate);
        initial_snapshot.seat_assignment_idempotency_key = seat_key.clone();
        let pending: Option<(i64,serde_json::Value)> = sqlx::query_as("SELECT version,data FROM entitlement_snapshots WHERE segment_booking_id=$1 AND data->>'purpose'=$2 ORDER BY id LIMIT 1 FOR UPDATE")
            .bind(&command.segment_booking_id).bind(command.issue_purpose.to_contract()).fetch_optional(&mut *tx).await.map_err(to_api_storage)?;
        let version = if let Some((version,data))=pending {
            let snapshot:EntitlementSnapshot=serde_json::from_value(data).map_err(|error|ApiErrorKind::Unavailable(error.to_string()))?;
            if snapshot.journey_order_id!=command.journey_order_id || snapshot.traveler_ref!=command.traveler_ref || snapshot.segment_ref!=command.segment_ref
                || snapshot.seat_assignment_idempotency_key!=seat_key {return Err(ApiErrorKind::PreconditionFailed("pending issuance has different business references".into()));}
            aggregate=snapshot.try_into_domain()?;
            version
        }else{
            self.entitlement_repo.save(&mut tx,aggregate.id().as_str(),None,&initial_snapshot).await.map_err(to_api_storage)?;
            1
        };
        let entitlement_id=aggregate.id().to_string();

        let seat_ref =
            allocate_seat_for_issue(command, seat_key.as_deref().unwrap_or(key), correlation_id)
                .await?;

        let issued_at = current_rfc3339();
        let credential_no = self.next_credential_no(&mut tx).await?;
        let response = IssueEntitlementResponse {
            entitlement_id: entitlement_id.clone(),
            segment_booking_id: command.segment_booking_id.clone(),
            journey_order_id: command.journey_order_id.clone(),
            credential_no: credential_no.clone(),
            credential_type: CredentialTypeDto::ETicket,
            status: EntitlementStatusDto::Issued,
            issued_at: issued_at.clone(),
            seat_ref: seat_ref.clone(),
        };
        let mut registry = CredentialRegistry::new();
        if let Err(error) = aggregate.issue(
                IssueEntitlement {
                    command_id: CommandId::new(format!("cmd-{}", uuid::Uuid::now_v7()))
                        .map_err(ApiErrorKind::from)?,
                    idempotency_key: aggregate.idempotency_key().clone(),
                    preconditions,
                    credential_ref: credential_ref(&credential_no).map_err(ApiErrorKind::from)?,
                    refund_request_fact: None,
                    audit: audit_builder(correlation_id, "HTTP issue entitlement")
                        .map_err(ApiErrorKind::from)?,
                },
                &mut registry,
        ) {
            *domain_rejection_recorded = true;
            self.record_domain_rejection(
                crate::metrics::TicketStage::from_issue_purpose(
                    command.issue_purpose.to_contract(),
                ),
                crate::metrics::TicketType::ETicket,
                &error,
            );
            return Err(ApiErrorKind::from(error));
        }
        let mut issued_snapshot = EntitlementSnapshot::from_domain(&aggregate);
        issued_snapshot.seat_ref = seat_ref.clone();
        issued_snapshot.seat_assignment_idempotency_key = seat_key;
        self.entitlement_repo
            .save(&mut tx, aggregate.id().as_str(), Some(version), &issued_snapshot)
            .await
            .map_err(to_api_storage)?;
        let outbound = entitlement_issued_envelope(&response, command, correlation_id)?;
        OutboxAppender::append(&mut tx, &stream_for_producer(PRODUCER), &outbound)
            .await
            .map_err(to_api_storage)?;
        self.finish_idempotency(
            &mut tx,
            key,
            fingerprint,
            201,
            serde_json::to_value(&response)
                .map_err(|e| ApiErrorKind::Unavailable(e.to_string()))?,
        )
        .await?;
        tx.commit().await.map_err(to_api_storage)?;
        Ok(response)
    }
    async fn try_void(
        &self,
        entitlement_id: &str,
        command: &VoidEntitlementRequest,
        key: &str,
        fingerprint: &str,
        correlation_id: &str,
        domain_rejection_recorded: &mut bool,
    ) -> PgResult<VoidEntitlementResponse> {
        let mut tx = self.pool().begin().await.map_err(to_api_storage)?;
        if let Some(response) = self
            .claim_idempotency::<VoidEntitlementResponse>(&mut tx, key, fingerprint)
            .await?
        {
            tx.commit().await.map_err(to_api_storage)?;
            return Ok(response);
        }
        let snapshot = self
            .load_entitlement(&mut tx, entitlement_id)
            .await?
            .ok_or_else(|| ApiErrorKind::NotFound("entitlement not found".into()))?;
        let mut aggregate = snapshot.data.clone().try_into_domain()?;
        let business_case_ref = command
            .business_case_ref
            .clone()
            .unwrap_or_else(|| format!("case-{}", uuid::Uuid::now_v7()));
        let reason: VoidReason = command.reason.clone().into();
        let policy: VoidPolicy = command.policy.clone().into();
        let ticket_type = ticket_type_of(&aggregate);
        let events = match aggregate.void(VoidEntitlement {
                command_id: CommandId::new(format!("cmd-{}", uuid::Uuid::now_v7()))
                    .map_err(ApiErrorKind::from)?,
                reason: reason.clone(),
                policy: policy.clone(),
                business_case_ref: BusinessCaseRef::new(business_case_ref.clone())
                    .map_err(ApiErrorKind::from)?,
                audit: audit_builder(correlation_id, "HTTP void entitlement")
                    .map_err(ApiErrorKind::from)?,
            }) {
            Ok(events) => events,
            Err(error) => {
                *domain_rejection_recorded = true;
                self.record_domain_rejection(
                    crate::metrics::TicketStage::Void,
                    ticket_type,
                    &error,
                );
                return Err(ApiErrorKind::from(error));
            }
        };
        if events.is_empty() {
            // Already voided. The caller sees a failed precondition; the
            // transport-level mapping records that once, with
            // `preconditions_unmet`.
            return Err(ApiErrorKind::PreconditionFailed(
                "entitlement is already voided".into(),
            ));
        }
        let voided_at = current_rfc3339();
        self.save_entitlement(&mut tx, &aggregate, Some(snapshot.version))
            .await?;
        let response = VoidEntitlementResponse {
            entitlement_id: entitlement_id.to_string(),
            status: EntitlementStatusDto::Voided,
            voided_at,
        };
        let outbound = entitlement_voided_envelope(
            &aggregate,
            &reason,
            &policy,
            command.business_case_ref.clone(),
            snapshot
                .data
                .seat_ref
                .as_ref()
                .map(|seat| seat.seat_allocation_id.clone()),
            correlation_id,
        )?;
        OutboxAppender::append(&mut tx, &stream_for_producer(PRODUCER), &outbound)
            .await
            .map_err(to_api_storage)?;
        self.finish_idempotency(
            &mut tx,
            key,
            fingerprint,
            200,
            serde_json::to_value(&response)
                .map_err(|e| ApiErrorKind::Unavailable(e.to_string()))?,
        )
        .await?;
        tx.commit().await.map_err(to_api_storage)?;
        Ok(response)
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct EntitlementSnapshot {
    entitlement_id: String,
    journey_order_id: String,
    segment_booking_id: String,
    traveler_ref: String,
    segment_ref: String,
    purpose: String,
    validity_starts_at: u64,
    validity_ends_at: u64,
    status: StatusSnapshot,
    #[serde(default)]
    seat_ref: Option<SeatRefDto>,
    #[serde(default)]
    seat_assignment_idempotency_key: Option<String>,
    credential_ref: Option<CredentialRefSnapshot>,
    fulfillment_use_state: FulfillmentUseStateSnapshot,
    audit_trail: Vec<AuditSnapshot>,
    processed_command_ids: Vec<String>,
}

impl EntitlementSnapshot {
    fn from_domain(entitlement: &Entitlement) -> Self {
        let mut processed_command_ids: Vec<_> = entitlement
            .processed_command_ids
            .iter()
            .map(ToString::to_string)
            .collect();
        processed_command_ids.sort();
        Self {
            entitlement_id: entitlement.id.to_string(),
            journey_order_id: entitlement.journey_order_ref.to_string(),
            segment_booking_id: entitlement.segment_booking_ref.to_string(),
            traveler_ref: entitlement.traveler_ref.to_string(),
            segment_ref: entitlement.segment_ref.to_string(),
            purpose: purpose_to_contract(&entitlement.purpose).to_string(),
            validity_starts_at: entitlement.validity_window.starts_at().as_u64(),
            validity_ends_at: entitlement.validity_window.ends_at().as_u64(),
            status: StatusSnapshot::from_domain(&entitlement.status),
            seat_ref: None,
            seat_assignment_idempotency_key: None,
            credential_ref: entitlement
                .credential_ref
                .as_ref()
                .map(CredentialRefSnapshot::from_domain),
            fulfillment_use_state: FulfillmentUseStateSnapshot::from_domain(
                &entitlement.fulfillment_use_state,
            ),
            audit_trail: entitlement
                .audit_trail
                .iter()
                .map(AuditSnapshot::from_domain)
                .collect(),
            processed_command_ids,
        }
    }
    fn try_into_domain(self) -> PgResult<Entitlement> {
        let purpose = parse_purpose(&self.purpose)?;
        Ok(Entitlement {
            id: EntitlementId::new(self.entitlement_id).map_err(ApiErrorKind::from)?,
            journey_order_ref: JourneyOrderRef::new(self.journey_order_id.clone())
                .map_err(ApiErrorKind::from)?,
            segment_booking_ref: SegmentBookingRef::new(self.segment_booking_id.clone())
                .map_err(ApiErrorKind::from)?,
            traveler_ref: TravelerRef::new(self.traveler_ref.clone())
                .map_err(ApiErrorKind::from)?,
            segment_ref: SegmentRef::new(self.segment_ref).map_err(ApiErrorKind::from)?,
            purpose: purpose.clone(),
            idempotency_key: IssueIdempotencyKey::new(
                SegmentBookingRef::new(self.segment_booking_id).map_err(ApiErrorKind::from)?,
                TravelerRef::new(self.traveler_ref).map_err(ApiErrorKind::from)?,
                purpose,
            ),
            validity_window: ValidityWindow::new(
                UnixMillis::new(self.validity_starts_at),
                UnixMillis::new(self.validity_ends_at),
            )
            .map_err(ApiErrorKind::from)?,
            status: self.status.try_into_domain()?,
            credential_ref: self
                .credential_ref
                .map(CredentialRefSnapshot::try_into_domain)
                .transpose()?,
            fulfillment_use_state: self.fulfillment_use_state.try_into_domain()?,
            audit_trail: self
                .audit_trail
                .into_iter()
                .map(AuditSnapshot::try_into_domain)
                .collect::<PgResult<Vec<_>>>()?,
            processed_command_ids: self
                .processed_command_ids
                .into_iter()
                .map(CommandId::new)
                .collect::<Result<std::collections::HashSet<_>, _>>()
                .map_err(ApiErrorKind::from)?,
        })
    }
    fn to_details(&self) -> EntitlementDetails {
        let (status, voided_at) = match &self.status {
            StatusSnapshot::Voided => (
                EntitlementStatusDto::Voided,
                self.audit_trail
                    .iter()
                    .rev()
                    .find(|a| a.action == "void")
                    .map(|a| unix_millis_to_rfc3339(a.occurred_at)),
            ),
            StatusSnapshot::Suspended { .. } => (EntitlementStatusDto::Suspended, None),
            StatusSnapshot::NoShow => (EntitlementStatusDto::NoShow, None),
            _ if matches!(
                self.fulfillment_use_state,
                FulfillmentUseStateSnapshot::BoardingVerified { .. }
                    | FulfillmentUseStateSnapshot::BoardingComplete { .. }
            ) =>
            {
                (EntitlementStatusDto::Boarded, None)
            }
            _ => (EntitlementStatusDto::Issued, None),
        };
        EntitlementDetails {
            entitlement_id: self.entitlement_id.clone(),
            segment_booking_id: self.segment_booking_id.clone(),
            journey_order_id: self.journey_order_id.clone(),
            traveler_ref: self.traveler_ref.clone(),
            segment_ref: self.segment_ref.clone(),
            credential_no: self
                .credential_ref
                .as_ref()
                .map(|c| c.credential_no.clone())
                .unwrap_or_default(),
            credential_type: self
                .credential_ref
                .as_ref()
                .map(|c| c.credential_type.to_dto())
                .unwrap_or(CredentialTypeDto::ETicket),
            status,
            issued_at: self
                .audit_trail
                .iter()
                .find(|a| a.action == "issue")
                .map(|a| unix_millis_to_rfc3339(a.occurred_at))
                .unwrap_or_else(current_rfc3339),
            voided_at,
            seat_ref: self.seat_ref.clone(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "SCREAMING_SNAKE_CASE")]
enum StatusSnapshot {
    Requested,
    Issued,
    IssueFailed { retryable: bool },
    Suspended { previous: Box<StatusSnapshot> },
    Voided,
    Expired,
    NoShow,
}
impl StatusSnapshot {
    fn from_domain(s: &EntitlementStatus) -> Self {
        match s {
            EntitlementStatus::Requested => Self::Requested,
            EntitlementStatus::Issued => Self::Issued,
            EntitlementStatus::IssueFailed { retryable } => Self::IssueFailed {
                retryable: *retryable,
            },
            EntitlementStatus::Suspended { previous } => Self::Suspended {
                previous: Box::new(Self::from_domain(previous)),
            },
            EntitlementStatus::Voided => Self::Voided,
            EntitlementStatus::Expired => Self::Expired,
            EntitlementStatus::NoShow => Self::NoShow,
        }
    }
    fn try_into_domain(self) -> PgResult<EntitlementStatus> {
        Ok(match self {
            Self::Requested => EntitlementStatus::Requested,
            Self::Issued => EntitlementStatus::Issued,
            Self::IssueFailed { retryable } => EntitlementStatus::IssueFailed { retryable },
            Self::Suspended { previous } => EntitlementStatus::Suspended {
                previous: Box::new(previous.try_into_domain()?),
            },
            Self::Voided => EntitlementStatus::Voided,
            Self::Expired => EntitlementStatus::Expired,
            Self::NoShow => EntitlementStatus::NoShow,
        })
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CredentialRefSnapshot {
    credential_id: String,
    credential_type: CredentialTypeSnapshot,
    credential_no: String,
    provider_credential_ref: Option<ProviderCredentialRefSnapshot>,
    display: CredentialDisplaySnapshot,
}
impl CredentialRefSnapshot {
    fn from_domain(c: &CredentialRef) -> Self {
        Self {
            credential_id: c.credential_id.to_string(),
            credential_type: CredentialTypeSnapshot::from_domain(&c.credential_type),
            credential_no: c.credential_no.clone(),
            provider_credential_ref: c
                .provider_credential_ref
                .as_ref()
                .map(ProviderCredentialRefSnapshot::from_domain),
            display: CredentialDisplaySnapshot::from_domain(&c.display),
        }
    }
    fn try_into_domain(self) -> PgResult<CredentialRef> {
        CredentialRef::new(
            CredentialId::new(self.credential_id).map_err(ApiErrorKind::from)?,
            self.credential_type.to_domain(),
            self.credential_no,
            self.provider_credential_ref
                .map(ProviderCredentialRefSnapshot::try_into_domain)
                .transpose()?,
            self.display.try_into_domain()?,
        )
        .map_err(ApiErrorKind::from)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum CredentialTypeSnapshot {
    ETicket,
    PaperTicket,
    PickupCode,
    BoardingPass,
    FerryTicket,
    CoachETicket,
    RideCode,
}
impl CredentialTypeSnapshot {
    fn from_domain(v: &CredentialType) -> Self {
        match v {
            CredentialType::ETicket => Self::ETicket,
            CredentialType::PaperTicket => Self::PaperTicket,
            CredentialType::PickupCode => Self::PickupCode,
            CredentialType::BoardingPass => Self::BoardingPass,
            CredentialType::FerryTicket => Self::FerryTicket,
            CredentialType::CoachETicket => Self::CoachETicket,
            CredentialType::RideCode => Self::RideCode,
        }
    }
    fn to_domain(&self) -> CredentialType {
        match self {
            Self::ETicket => CredentialType::ETicket,
            Self::PaperTicket => CredentialType::PaperTicket,
            Self::PickupCode => CredentialType::PickupCode,
            Self::BoardingPass => CredentialType::BoardingPass,
            Self::FerryTicket => CredentialType::FerryTicket,
            Self::CoachETicket => CredentialType::CoachETicket,
            Self::RideCode => CredentialType::RideCode,
        }
    }
    fn to_dto(&self) -> CredentialTypeDto {
        match self {
            Self::ETicket => CredentialTypeDto::ETicket,
            Self::PaperTicket => CredentialTypeDto::PaperTicket,
            Self::PickupCode => CredentialTypeDto::PickupCode,
            Self::BoardingPass => CredentialTypeDto::BoardingPass,
            Self::FerryTicket => CredentialTypeDto::FerryTicket,
            Self::CoachETicket => CredentialTypeDto::CoachETicket,
            Self::RideCode => CredentialTypeDto::RideCode,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ProviderCredentialRefSnapshot {
    provider_ref: String,
    confirmation_no: String,
    ticket_no: String,
    mapped_status: String,
}
impl ProviderCredentialRefSnapshot {
    fn from_domain(v: &ProviderCredentialRef) -> Self {
        Self {
            provider_ref: v.provider_ref.to_string(),
            confirmation_no: v.confirmation_no.clone(),
            ticket_no: v.ticket_no.clone(),
            mapped_status: v.mapped_status.clone(),
        }
    }
    fn try_into_domain(self) -> PgResult<ProviderCredentialRef> {
        ProviderCredentialRef::new(
            ProviderRef::new(self.provider_ref).map_err(ApiErrorKind::from)?,
            self.confirmation_no,
            self.ticket_no,
            self.mapped_status,
        )
        .map_err(ApiErrorKind::from)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CredentialDisplaySnapshot {
    masked_code: String,
    display_version: u32,
    barcode_format: Option<String>,
}
impl CredentialDisplaySnapshot {
    fn from_domain(v: &CredentialDisplayReference) -> Self {
        Self {
            masked_code: v.masked_code.clone(),
            display_version: v.display_version,
            barcode_format: v.barcode_format.clone(),
        }
    }
    fn try_into_domain(self) -> PgResult<CredentialDisplayReference> {
        CredentialDisplayReference::new(self.masked_code, self.display_version, self.barcode_format)
            .map_err(ApiErrorKind::from)
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "SCREAMING_SNAKE_CASE")]
enum FulfillmentUseStateSnapshot {
    NotUsed,
    CheckInAccepted { fact_ref: String },
    BoardingVerified { fact_ref: String },
    BoardingComplete { fact_ref: String },
    NoShowRecorded { fact_ref: String },
}
impl FulfillmentUseStateSnapshot {
    fn from_domain(v: &FulfillmentUseState) -> Self {
        match v {
            FulfillmentUseState::NotUsed => Self::NotUsed,
            FulfillmentUseState::CheckInAccepted { fact_ref } => Self::CheckInAccepted {
                fact_ref: fact_ref.to_string(),
            },
            FulfillmentUseState::BoardingVerified { fact_ref } => Self::BoardingVerified {
                fact_ref: fact_ref.to_string(),
            },
            FulfillmentUseState::BoardingComplete { fact_ref } => Self::BoardingComplete {
                fact_ref: fact_ref.to_string(),
            },
            FulfillmentUseState::NoShowRecorded { fact_ref } => Self::NoShowRecorded {
                fact_ref: fact_ref.to_string(),
            },
        }
    }
    fn try_into_domain(self) -> PgResult<FulfillmentUseState> {
        Ok(match self {
            Self::NotUsed => FulfillmentUseState::NotUsed,
            Self::CheckInAccepted { fact_ref } => FulfillmentUseState::CheckInAccepted {
                fact_ref: FulfillmentFactRef::new(fact_ref).map_err(ApiErrorKind::from)?,
            },
            Self::BoardingVerified { fact_ref } => FulfillmentUseState::BoardingVerified {
                fact_ref: FulfillmentFactRef::new(fact_ref).map_err(ApiErrorKind::from)?,
            },
            Self::BoardingComplete { fact_ref } => FulfillmentUseState::BoardingComplete {
                fact_ref: FulfillmentFactRef::new(fact_ref).map_err(ApiErrorKind::from)?,
            },
            Self::NoShowRecorded { fact_ref } => FulfillmentUseState::NoShowRecorded {
                fact_ref: FulfillmentFactRef::new(fact_ref).map_err(ApiErrorKind::from)?,
            },
        })
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AuditSnapshot {
    action: String,
    reason: String,
    actor_type: String,
    actor_id: String,
    occurred_at: u64,
    correlation_id: String,
}
impl AuditSnapshot {
    fn from_domain(v: &EntitlementLifecycleAudit) -> Self {
        Self {
            action: v.action.to_string(),
            reason: v.reason.clone(),
            actor_type: actor_type_to_contract(&v.actor_type).to_string(),
            actor_id: v.actor_id.clone(),
            occurred_at: v.occurred_at.as_u64(),
            correlation_id: v.correlation_id.to_string(),
        }
    }
    fn try_into_domain(self) -> PgResult<EntitlementLifecycleAudit> {
        let action: &'static str = match self.action.as_str() {
            "request" => "request",
            "issue" => "issue",
            "fail_issue" => "fail_issue",
            "suspend" => "suspend",
            "resume" => "resume",
            "void" => "void",
            "expire" => "expire",
            "accept_fulfillment_fact" => "accept_fulfillment_fact",
            "suspend_after_late_refund" => "suspend_after_late_refund",
            _ => "restore",
        };
        EntitlementLifecycleAudit::new(
            action,
            self.reason,
            parse_actor_type(&self.actor_type),
            self.actor_id,
            UnixMillis::new(self.occurred_at),
            CorrelationId::new(self.correlation_id).map_err(ApiErrorKind::from)?,
        )
        .map_err(ApiErrorKind::from)
    }
}

fn validate_issue_request(c: &IssueEntitlementRequest) -> PgResult<()> {
    validate_prefixed_uuid(&c.segment_booking_id, "segmentBookingId", "sb-")?;
    validate_prefixed_uuid(&c.journey_order_id, "journeyOrderId", "ord-")?;
    validate_prefixed_uuid(&c.traveler_ref, "travelerRef", "tvl-")?;
    validate_prefixed(&c.segment_ref, "segmentRef", "seg-")?;
    if let Some(prefs) = &c.seat_preferences {
        if prefs.preference_version.trim().is_empty() {
            return Err(ApiErrorKind::ValidationFailed(
                "seatPreferences.preferenceVersion must not be blank".into(),
            ));
        }
        for seat_unit in prefs.avoid_seat_unit_refs.iter().flatten() {
            if seat_unit.trim().is_empty() {
                return Err(ApiErrorKind::ValidationFailed(
                    "seatPreferences.avoidSeatUnitRefs must not contain blank values".into(),
                ));
            }
        }
    }
    if c.needs_seat_assignment() {
        for (name, present) in [
            ("scheduledServiceRef", c.scheduled_service_ref.as_ref()),
            ("serviceDate", c.service_date.as_ref()),
            ("capacityHoldId", c.capacity_hold_id.as_ref()),
            ("capacityUnitRef", c.capacity_unit_ref.as_ref()),
            ("classRef", c.class_ref.as_ref()),
            ("expiresAt", c.expires_at.as_ref()),
        ] {
            if present.is_none_or(|value| value.trim().is_empty()) {
                return Err(ApiErrorKind::PreconditionFailed(format!(
                    "{name} is required from accepted upstream facts for seat assignment"
                )));
            }
        }
        if c.interval.is_none() {
            return Err(ApiErrorKind::PreconditionFailed(
                "interval is required from accepted upstream facts for seat assignment".into(),
            ));
        }
    }
    Ok(())
}
fn to_api_storage(error: impl std::fmt::Display) -> ApiErrorKind {
    let message = error.to_string();
    if message.contains("IDEMPOTENCY_KEY_REUSED") {
        ApiErrorKind::IdempotencyKeyReused(
            "Idempotency-Key was reused with a different request body".into(),
        )
    } else if message.contains("optimistic concurrency conflict")
        || message.contains("duplicate key value")
    {
        ApiErrorKind::Conflict(message)
    } else {
        ApiErrorKind::Unavailable(message)
    }
}

/// The outcome a transport-level failure is published under. A rejected caller
/// is not the same event as a dependency being unavailable, and a conservation
/// check reads them differently.
fn transport_outcome(error: &ApiErrorKind) -> crate::metrics::TicketOutcome {
    match error {
        ApiErrorKind::Unavailable(_) | ApiErrorKind::Conflict(_) => {
            crate::metrics::TicketOutcome::Failed
        }
        ApiErrorKind::ValidationFailed(_)
        | ApiErrorKind::NotFound(_)
        | ApiErrorKind::IdempotencyKeyReused(_)
        | ApiErrorKind::PreconditionFailed(_)
        | ApiErrorKind::DomainRuleViolation(_) => crate::metrics::TicketOutcome::Rejected,
    }
}

/// Map a transport-level failure onto the owned reason vocabulary. The message
/// is never inspected: it is free text, and a reason code has to be a token.
fn transport_reason(error: &ApiErrorKind) -> crate::metrics::TicketReasonCode {
    match error {
        ApiErrorKind::ValidationFailed(_) => crate::metrics::TicketReasonCode::InvalidRequest,
        ApiErrorKind::NotFound(_) => crate::metrics::TicketReasonCode::NotFound,
        ApiErrorKind::IdempotencyKeyReused(_) => crate::metrics::TicketReasonCode::DuplicateIssue,
        ApiErrorKind::PreconditionFailed(_) => crate::metrics::TicketReasonCode::PreconditionsUnmet,
        ApiErrorKind::Unavailable(_) => crate::metrics::TicketReasonCode::Unavailable,
        ApiErrorKind::Conflict(_) => crate::metrics::TicketReasonCode::Other,
        // Recorded at the domain-decision site with the precise reason; reaching
        // this branch means the reason was not recoverable, which is exactly
        // what `other` is for.
        ApiErrorKind::DomainRuleViolation(_) => crate::metrics::TicketReasonCode::Other,
    }
}

/// Map a domain rejection onto the owned reason vocabulary.
fn domain_reason(error: &EntitlementError) -> crate::metrics::TicketReasonCode {
    use crate::metrics::TicketReasonCode as Reason;
    match error {
        EntitlementError::TerminalStatus(_) => Reason::TerminalStatus,
        EntitlementError::DuplicateIssueAttempt
        | EntitlementError::DuplicateCredential { .. }
        | EntitlementError::AlreadyIssued => Reason::DuplicateIssue,
        EntitlementError::IssueFailureNotRetryable => Reason::IssueFailureNotRetryable,
        EntitlementError::EntitlementFrozen => Reason::Frozen,
        EntitlementError::NotSuspended => Reason::NotSuspended,
        EntitlementError::VoidRequiresCompensation => Reason::VoidBlockedByUse,
        EntitlementError::ExceptionalVoidRuleRequired => Reason::ExceptionalRuleRequired,
        EntitlementError::NotIssued | EntitlementError::BoardingRequiredForCompletion => {
            Reason::PreconditionsUnmet
        }
        EntitlementError::BlankReference { .. }
        | EntitlementError::InvalidValidityWindow
        | EntitlementError::InvalidDisplayVersion
        | EntitlementError::MissingAuditReason
        | EntitlementError::IdempotencyKeyMismatch => Reason::InvalidRequest,
        EntitlementError::NoShowAfterBoarding => Reason::Other,
    }
}

/// The credential type an entitlement already carries, as a bounded ticket
/// type. An entitlement without a credential has no type yet, not a wrong one.
fn ticket_type_of(entitlement: &Entitlement) -> crate::metrics::TicketType {
    entitlement
        .credential_ref()
        .map(|credential| crate::metrics::TicketType::from_ref(&format!("{:?}", credential.credential_type)))
        .unwrap_or(crate::metrics::TicketType::Unknown)
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum InboundEventError {
    Transient(String),
    Fatal(String),
}
fn inbound_transient(error: impl std::fmt::Display) -> InboundEventError {
    InboundEventError::Transient(error.to_string())
}
fn inbound_fatal(error: impl std::fmt::Display) -> InboundEventError {
    InboundEventError::Fatal(error.to_string())
}
fn inbound_from_api_error(error: ApiErrorKind) -> InboundEventError {
    match error {
        ApiErrorKind::Unavailable(m) => InboundEventError::Transient(m),
        ApiErrorKind::Conflict(m) if m.contains("optimistic concurrency conflict") => {
            InboundEventError::Transient(m)
        }
        error => InboundEventError::Fatal(error.message().to_string()),
    }
}
async fn mark_once(
    tx: &mut PgTransaction<'_>,
    envelope: &application::EventEnvelope,
) -> Result<bool, InboundEventError> {
    mark_event_processing(
        tx,
        &envelope.event_id,
        &stream_for_producer(&envelope.producer),
    )
    .await
    .map_err(inbound_transient)
}
fn require_string(payload: &Value, field: &'static str) -> Result<String, InboundEventError> {
    payload
        .get(field)
        .and_then(Value::as_str)
        .filter(|v| !v.trim().is_empty())
        .map(ToString::to_string)
        .ok_or_else(|| InboundEventError::Fatal(format!("missing {field}")))
}

fn entitlement_issued_envelope(
    response: &IssueEntitlementResponse,
    command: &IssueEntitlementRequest,
    correlation_id: &str,
) -> PgResult<application::EventEnvelope> {
    application::EventEnvelope::try_new(
        "EntitlementIssued",
        response.issued_at.clone(),
        rust_kit::messaging::valid_or_generated_correlation_id(correlation_id),
        None::<String>,
        PRODUCER,
        serde_json::to_value(EntitlementIssuedPayload {
            entitlement_id: response.entitlement_id.clone(),
            segment_booking_id: command.segment_booking_id.clone(),
            journey_order_id: command.journey_order_id.clone(),
            traveler_ref: command.traveler_ref.clone(),
            segment_ref: command.segment_ref.clone(),
            issue_purpose: command.issue_purpose.to_contract(),
            credential_no: response.credential_no.clone(),
            credential_type: response.credential_type.to_contract(),
            issued_at: response.issued_at.clone(),
            seat_ref: response.seat_ref.clone(),
            seat_allocation_id: response
                .seat_ref
                .as_ref()
                .map(|s| s.seat_allocation_id.clone()),
        })
        .map_err(|e| ApiErrorKind::Unavailable(e.to_string()))?,
    )
    .map_err(|e| ApiErrorKind::Unavailable(e.to_string()))
}
fn entitlement_voided_envelope(
    aggregate: &Entitlement,
    reason: &VoidReason,
    policy: &VoidPolicy,
    business_case_ref: Option<String>,
    seat_allocation_id: Option<String>,
    correlation_id: &str,
) -> PgResult<application::EventEnvelope> {
    let voided_at = current_rfc3339();
    application::EventEnvelope::try_new(
        "EntitlementVoided",
        voided_at.clone(),
        rust_kit::messaging::valid_or_generated_correlation_id(correlation_id),
        None::<String>,
        PRODUCER,
        serde_json::to_value(EntitlementVoidedPayload {
            entitlement_id: aggregate.id.to_string(),
            segment_booking_id: aggregate.segment_booking_ref.to_string(),
            references: EntitlementVoidedReferences {
                segment_booking_ref: aggregate.segment_booking_ref.to_string(),
                order_ref: Some(aggregate.journey_order_ref.to_string()),
                traveler_ref: Some(aggregate.traveler_ref.to_string()),
            },
            voided_at,
            reason: reason_to_contract(reason),
            policy: policy_to_contract(policy),
            business_case_ref,
            seat_allocation_id,
        })
        .map_err(|e| ApiErrorKind::Unavailable(e.to_string()))?,
    )
    .map_err(|e| ApiErrorKind::Unavailable(e.to_string()))
}
fn unix_millis_to_rfc3339(millis: u64) -> String {
    chrono::DateTime::<chrono::Utc>::from_timestamp_millis(millis as i64)
        .unwrap_or_else(chrono::Utc::now)
        .to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
}
fn purpose_to_contract(v: &IssuePurpose) -> &'static str {
    match v {
        IssuePurpose::Initial => "INITIAL",
        IssuePurpose::Replacement => "REPLACEMENT",
        IssuePurpose::ManualRecovery => "MANUAL_RECOVERY",
        IssuePurpose::ProviderRebuild => "PROVIDER_REBUILD",
        IssuePurpose::DisruptionReplacement => "DISRUPTION_REPLACEMENT",
    }
}
fn parse_purpose(v: &str) -> PgResult<IssuePurpose> {
    Ok(match v {
        "INITIAL" => IssuePurpose::Initial,
        "REPLACEMENT" => IssuePurpose::Replacement,
        "MANUAL_RECOVERY" => IssuePurpose::ManualRecovery,
        "PROVIDER_REBUILD" => IssuePurpose::ProviderRebuild,
        "DISRUPTION_REPLACEMENT" => IssuePurpose::DisruptionReplacement,
        _ => {
            return Err(ApiErrorKind::Unavailable(format!(
                "unknown issue purpose {v}"
            )));
        }
    })
}
fn actor_type_to_contract(v: &ActorType) -> &'static str {
    match v {
        ActorType::System => "SYSTEM",
        ActorType::Operator => "OPERATOR",
        ActorType::Saga => "SAGA",
        ActorType::ProviderAdapter => "PROVIDER_ADAPTER",
    }
}
fn parse_actor_type(v: &str) -> ActorType {
    match v {
        "OPERATOR" => ActorType::Operator,
        "SAGA" => ActorType::Saga,
        "PROVIDER_ADAPTER" => ActorType::ProviderAdapter,
        _ => ActorType::System,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::str::FromStr;

    fn issue_request() -> IssueEntitlementRequest {
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
        }
    }

    #[test]
    fn post_sales_void_action_contract_violations_are_fatal_before_db_writes() {
        let missing_entitlement = approved_void_actions(Some(&serde_json::json!({
            "steps": [{"type": "VOID_ENTITLEMENT", "reason": "REFUND", "policy": "NORMAL"}]
        })))
        .unwrap_err();
        assert!(missing_entitlement.contains("entitlementId"));

        let invalid_reason = approved_void_actions(Some(&serde_json::json!({
            "steps": [{"type": "VOID_ENTITLEMENT", "entitlementId": "ent-0194f2e0-7b3e-7610-8284-5c26e8b0aa15", "reason": "OTHER", "policy": "NORMAL"}]
        })))
        .unwrap_err();
        assert!(invalid_reason.contains("invalid reason"));
    }

    /// Every rejection the domain can raise lands on an owned reason token.
    /// The `Display` text of these errors names entitlements and credentials,
    /// and none of it may reach a label.
    #[test]
    fn every_domain_rejection_maps_to_an_owned_reason_token() {
        let rejections = [
            EntitlementError::BlankReference { kind: "entitlement_id" },
            EntitlementError::InvalidValidityWindow,
            EntitlementError::InvalidDisplayVersion,
            EntitlementError::MissingAuditReason,
            EntitlementError::IdempotencyKeyMismatch,
            EntitlementError::DuplicateIssueAttempt,
            EntitlementError::DuplicateCredential {
                credential_no: "ETK-000000000123".to_string(),
                existing_entitlement_id: EntitlementId::new("ent-0194f2e0-7b3e-7610-8284-5c26e8b0aa01")
                    .unwrap(),
            },
            EntitlementError::IssueFailureNotRetryable,
            EntitlementError::AlreadyIssued,
            EntitlementError::EntitlementFrozen,
            EntitlementError::NotSuspended,
            EntitlementError::NotIssued,
            EntitlementError::TerminalStatus(EntitlementStatus::Voided),
            EntitlementError::ExceptionalVoidRuleRequired,
            EntitlementError::VoidRequiresCompensation,
            EntitlementError::BoardingRequiredForCompletion,
            EntitlementError::NoShowAfterBoarding,
        ];
        for rejection in &rejections {
            let reason = domain_reason(rejection).as_str();
            assert!(
                !reason.is_empty() && !reason.contains(' ') && reason.len() <= 32,
                "reason_code must be an owned token, got {reason:?} for {rejection}"
            );
            assert!(
                !reason.contains("ent-") && !reason.contains("ETK-"),
                "a reference reached a reason code: {reason}"
            );
        }
    }

    #[test]
    fn transport_rejections_map_to_owned_reason_tokens() {
        let transport_errors = [
            ApiErrorKind::ValidationFailed("x".into()),
            ApiErrorKind::NotFound("x".into()),
            ApiErrorKind::Conflict("x".into()),
            ApiErrorKind::IdempotencyKeyReused("x".into()),
            ApiErrorKind::PreconditionFailed("x".into()),
            ApiErrorKind::DomainRuleViolation("x".into()),
            ApiErrorKind::Unavailable("x".into()),
        ];
        for error in &transport_errors {
            let reason = transport_reason(error).as_str();
            assert!(!reason.is_empty() && !reason.contains(' '), "got {reason:?}");
            let outcome = transport_outcome(error).as_str();
            assert!(
                ["accepted", "rejected", "noop", "failed"].contains(&outcome),
                "got {outcome:?}"
            );
        }
    }

    #[tokio::test]
    #[ignore = "requires TEST_DATABASE_URL pointing at a disposable Postgres database"]
    async fn optimistic_concurrency_conflict_uses_real_two_write_race() {
        let database_url =
            std::env::var("TEST_DATABASE_URL").expect("TEST_DATABASE_URL is required");
        let storage = Storage::connect(&database_url).await.unwrap();
        storage
            .migrate_dir("services/entitlement-ticketing/migrations")
            .await
            .unwrap();
        let service = PostgresEntitlementService::from_storage(storage).unwrap();
        let issued = service
            .issue(
                issue_request(),
                "0194f2e0-7b3e-7610-8284-5c26e8b0aa01".to_string(),
                "corr-0194f2e0-7b3e-7610-8284-5c26e8b0aa02".to_string(),
            )
            .await
            .unwrap();

        let mut read_tx = service.pool().begin().await.unwrap();
        let snapshot = service
            .load_entitlement(&mut read_tx, &issued.entitlement_id)
            .await
            .unwrap()
            .unwrap();
        read_tx.commit().await.unwrap();

        let aggregate = snapshot.data.clone().try_into_domain().unwrap();
        let mut first_tx = service.pool().begin().await.unwrap();
        service
            .save_entitlement(&mut first_tx, &aggregate, Some(snapshot.version))
            .await
            .unwrap();
        first_tx.commit().await.unwrap();

        let mut second_tx = service.pool().begin().await.unwrap();
        let conflict = service
            .save_entitlement(&mut second_tx, &aggregate, Some(snapshot.version))
            .await
            .unwrap_err();
        second_tx.rollback().await.unwrap();
        assert!(matches!(conflict, ApiErrorKind::Conflict(_)));
    }

    /// The `active` level against the real table. The in-crate metrics test
    /// proves the fold is a pure function of durable rows; this proves the rows
    /// really are durable, which is the restart case the contract requires.
    #[tokio::test]
    #[ignore = "requires TEST_DATABASE_URL pointing at a disposable Postgres database"]
    async fn active_level_is_reconstructed_from_the_durable_snapshot() {
        let database_url =
            std::env::var("TEST_DATABASE_URL").expect("TEST_DATABASE_URL is required");
        let admin = Storage::connect(&database_url).await.unwrap();
        let schema = format!("entitlement_active_{}", uuid::Uuid::now_v7().simple());
        sqlx::query(&format!("CREATE SCHEMA {schema}"))
            .execute(admin.pool())
            .await
            .unwrap();
        // Search path so the service's own migration lands in the scratch schema.
        let options = sqlx::postgres::PgConnectOptions::from_str(&database_url)
            .unwrap()
            .options([("search_path", schema.as_str())]);
        let pool = sqlx::postgres::PgPoolOptions::new()
            .max_connections(2)
            .connect_with(options)
            .await
            .unwrap();
        let scoped = Storage::new(pool);
        scoped
            .migrate_dir("migrations")
            .await
            .unwrap();
        let service = PostgresEntitlementService::from_storage(scoped).unwrap();

        let (issued, _) = Entitlement::request(RequestEntitlement {
            command_id: CommandId::new("cmd-active-1").unwrap(),
            entitlement_id: EntitlementId::new("ent-active-issued").unwrap(),
            journey_order_ref: JourneyOrderRef::new("ord-active-1").unwrap(),
            segment_booking_ref: SegmentBookingRef::new("sb-active-1").unwrap(),
            traveler_ref: TravelerRef::new("tvl-active-1").unwrap(),
            segment_ref: SegmentRef::new("seg-active-1").unwrap(),
            purpose: IssuePurpose::Initial,
            validity_window: ValidityWindow::new(UnixMillis::new(1), UnixMillis::new(2)).unwrap(),
            audit: audit_builder("corr-active-1", "live active level test").unwrap(),
        })
        .unwrap();
        let mut issued = issued;
        issued
            .issue(
                IssueEntitlement {
                    command_id: CommandId::new("cmd-active-2").unwrap(),
                    idempotency_key: issued.idempotency_key().clone(),
                    preconditions: IssuePreconditions::accepted(
                        AcceptedFact::new(
                            BookingFactRef::new("bf-active-1").unwrap(),
                            BookingAcceptance::SegmentReservationConfirmed,
                            UnixMillis::new(1),
                        ),
                        AcceptedFact::new(
                            CapacityFactRef::new("cf-active-1").unwrap(),
                            CapacityAcceptance::CapacityCommitted,
                            UnixMillis::new(1),
                        ),
                        AcceptedFact::new(
                            PaymentFactRef::new("pf-active-1").unwrap(),
                            PaymentAcceptance::PaymentCaptured,
                            UnixMillis::new(1),
                        ),
                        AcceptedFact::new(
                            TravelerFactRef::new("tf-active-1").unwrap(),
                            TravelerAcceptance::TravelerSnapshotAccepted,
                            UnixMillis::new(1),
                        ),
                        AcceptedFact::new(
                            RiskFactRef::new("rf-active-1").unwrap(),
                            RiskAcceptance::Allowed,
                            UnixMillis::new(1),
                        ),
                    ),
                    credential_ref: CredentialRef::new(
                        CredentialId::new("cred-active-1").unwrap(),
                        CredentialType::ETicket,
                        "ETK-ACTIVE-1",
                        None,
                        CredentialDisplayReference::new("****1", 1, None).unwrap(),
                    )
                    .unwrap(),
                    refund_request_fact: None,
                    audit: audit_builder("corr-active-2", "live active level test").unwrap(),
                },
                &mut CredentialRegistry::new(),
            )
            .unwrap();

        let mut write = service.pool().begin().await.unwrap();
        service
            .save_entitlement(&mut write, &issued, None)
            .await
            .unwrap();
        write.commit().await.unwrap();

        service.refresh_active_ticketing().await.unwrap();
        let first = service.metrics.published_active();
        // A second instance over the same table is a restart.
        let restarted = PostgresEntitlementService::new(service.pool().clone()).unwrap();
        restarted.refresh_active_ticketing().await.unwrap();
        let second = restarted.metrics.published_active();
        assert_eq!(first, second, "a restart must republish the same level");
        assert_eq!(
            first
                .iter()
                .find(|row| row.stage == crate::metrics::TicketingActiveStage::Issued)
                .map(|row| (row.ticket_type, row.tickets)),
            Some((crate::metrics::TicketType::ETicket, 1)),
            "the live ticket was not reconstructed: {first:?}"
        );

        let _ = sqlx::query(&format!("DROP SCHEMA {schema} CASCADE"))
            .execute(service.pool())
            .await;
    }
}
