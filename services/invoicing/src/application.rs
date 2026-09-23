use crate::utils::*;
use crate::*;
use async_trait::async_trait;
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};

#[async_trait]
pub trait InvoicingApi: Send + Sync {
    async fn create_title(
        &self,
        cmd: CreateInvoiceTitleCommand,
        key: String,
        corr: String,
    ) -> Result<InvoiceTitle, InvoicingError>;
    async fn list_titles(
        &self,
        account_id: String,
        status: Option<TitleStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<Page<InvoiceTitle>, InvoicingError>;
    async fn get_title(&self, id: String) -> Result<InvoiceTitle, InvoicingError>;
    async fn update_title(
        &self,
        id: String,
        cmd: UpdateInvoiceTitleCommand,
        key: String,
        corr: String,
    ) -> Result<InvoiceTitle, InvoicingError>;
    async fn set_default_title(
        &self,
        id: String,
        cmd: SetDefaultTitleCommand,
        key: String,
        corr: String,
    ) -> Result<InvoiceTitle, InvoicingError>;
    async fn deactivate_title(
        &self,
        id: String,
        cmd: DeactivateTitleCommand,
        key: String,
        corr: String,
    ) -> Result<DeactivateTitleResponse, InvoicingError>;
    async fn request_invoice(
        &self,
        cmd: RequestEInvoiceCommand,
        key: String,
        corr: String,
    ) -> Result<EInvoiceRequest, InvoicingError>;
    async fn get_request(&self, id: String) -> Result<EInvoiceRequest, InvoicingError>;
    async fn get_invoice(&self, id: String) -> Result<EInvoice, InvoicingError>;
    async fn get_amount_basis(&self, order_id: String) -> Result<AmountBasis, InvoicingError>;
    async fn list_invoices(
        &self,
        order_id: String,
        invoice_type: Option<InvoiceType>,
        status: Option<InvoiceRequestStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<Page<EInvoice>, InvoicingError>;
    async fn get_red_flush(&self, id: String) -> Result<RedFlushView, InvoicingError>;
    async fn list_red_flushes(
        &self,
        order_id: Option<String>,
        case_id: Option<String>,
        status: Option<RedFlushStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<Page<RedFlushView>, InvoicingError>;
    async fn generate_itinerary(
        &self,
        order_id: String,
        traveler_refs: Vec<String>,
        segment_refs: Vec<String>,
        receipt_version: i64,
    ) -> Result<ItineraryReceiptProjection, InvoicingError>;
}

#[async_trait]
pub trait EventPublisher: Send + Sync {
    async fn publish(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), InvoicingError>;
}
#[derive(Default)]
pub struct InMemoryEventPublisher {
    events: Mutex<Vec<rust_kit::messaging::EventEnvelope>>,
}
impl InMemoryEventPublisher {
    pub fn events(&self) -> Vec<rust_kit::messaging::EventEnvelope> {
        self.events.lock().unwrap().clone()
    }

    pub(crate) fn drain(&self) -> Vec<rust_kit::messaging::EventEnvelope> {
        std::mem::take(&mut *self.events.lock().unwrap())
    }
}
#[async_trait]
impl EventPublisher for InMemoryEventPublisher {
    async fn publish(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), InvoicingError> {
        self.events.lock().unwrap().push(envelope);
        Ok(())
    }
}
async fn publish_events(
    publisher: &dyn EventPublisher,
    events: Vec<InvoicingEvent>,
    corr: String,
    cause: Option<String>,
) -> Result<(), InvoicingError> {
    for event in events {
        publisher
            .publish(event.envelope(corr.clone(), cause.clone()))
            .await?;
    }
    Ok(())
}

pub struct InMemoryInvoicingService {
    state: Mutex<InMemoryState>,
    publisher: Arc<dyn EventPublisher>,
}
impl Default for InMemoryInvoicingService {
    fn default() -> Self {
        Self::new(Arc::new(InMemoryEventPublisher::default()))
    }
}
impl InMemoryInvoicingService {
    pub fn new(publisher: Arc<dyn EventPublisher>) -> Self {
        Self {
            state: Mutex::new(InMemoryState::default()),
            publisher,
        }
    }

    pub(crate) fn import_snapshot(&self, snapshot: InvoicingStateSnapshot) {
        let mut state = self.state.lock().unwrap();
        state.titles = snapshot.titles;
        state.requests = snapshot.requests;
        state.invoices = snapshot.invoices;
        state.red_flushes = snapshot.red_flushes;
        state.orders = snapshot.orders;
        state.amounts = snapshot.amounts;
        state.amount_history = snapshot.amount_history;
        state.idempotency = snapshot.idempotency;
        state.saga_invoices = snapshot.saga_invoices;
    }

    pub(crate) fn snapshot(&self) -> InvoicingStateSnapshot {
        let state = self.state.lock().unwrap();
        InvoicingStateSnapshot {
            titles: state.titles.clone(),
            requests: state.requests.clone(),
            invoices: state.invoices.clone(),
            red_flushes: state.red_flushes.clone(),
            orders: state.orders.clone(),
            amounts: state.amounts.clone(),
            amount_history: state.amount_history.clone(),
            idempotency: state.idempotency.clone(),
            saga_invoices: state.saga_invoices.clone(),
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
    ) -> Result<(), InvoicingError> {
        if self
            .state
            .lock()
            .unwrap()
            .processed_events
            .contains(&envelope.event_id)
        {
            return Ok(());
        }
        match envelope.event_type.as_str() {
            "InvoiceRequested" if envelope.producer == "booking-orchestration" => {
                self.handle_saga_invoice_requested(envelope).await
            }
            "JourneyOrderCreated" | "JourneyOrderConfirmed" | "JourneyOrderPostSalesAdjusted" => {
                self.project_order(envelope).await
            }
            "RevenueRecognized" | "RevenueRecognitionReversed" | "InvoiceGenerated" => self.project_amount(envelope).await,
            "PostSalesApproved" | "PostSalesApplied" => self.observe_refund(envelope).await,
            "PostSalesFailed" => {
                self.state
                    .lock()
                    .unwrap()
                    .processed_events
                    .insert(envelope.event_id);
                Ok(())
            }
            "ManualActionApproved" | "ManualActionRejected" | "ManualActionExecuted" => {
                self.state
                    .lock()
                    .unwrap()
                    .processed_events
                    .insert(envelope.event_id);
                Ok(())
            }
            _ => {
                self.state
                    .lock()
                    .unwrap()
                    .processed_events
                    .insert(envelope.event_id);
                Ok(())
            }
        }
    }
    async fn project_order(
        &self,
        e: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), InvoicingError> {
        let order_id = string_field(&e.payload, "orderId").unwrap_or_default();
        let account_id = string_field(&e.payload, "accountId").unwrap_or_default();
        if order_id.is_empty() || account_id.is_empty() {
            return Ok(());
        }
        let travelers = array_strings(&e.payload, "travelerRefs");
        let segments = array_strings(&e.payload, "segmentRefs");
        let expected_total = e.payload.get("monetarySummary").and_then(|summary| summary.get("total"))
            .map(|amount| serde_json::from_value::<Money>(amount.clone())).transpose()
            .map_err(|error| InvoicingError::ValidationFailed(error.to_string()))?;
        let occurred_at = chrono::DateTime::parse_from_rfc3339(&e.occurred_at)
            .map_err(|error| InvoicingError::ValidationFailed(error.to_string()))?.with_timezone(&chrono::Utc);
        let mut s = self.state.lock().unwrap();
        if let Some(existing) = s.orders.get_mut(&order_id) {
            if existing.updated_at.is_some_and(|at| at > occurred_at) {
                if existing.traveler_refs.is_empty() { existing.traveler_refs = travelers; }
                if existing.segment_refs.is_empty() { existing.segment_refs = segments; }
                s.processed_events.insert(e.event_id);
                return Ok(());
            }
            existing.account_id = account_id;
            if expected_total.is_some() {
                existing.expected_total = expected_total;
            }
            existing.updated_at = Some(occurred_at);
            if e.event_type == "JourneyOrderPostSalesAdjusted" {
                if !travelers.is_empty() { existing.traveler_refs = travelers.clone(); }
                if !segments.is_empty() { existing.segment_refs = segments.clone(); }
            }
            for t in &travelers {
                if !existing.traveler_refs.contains(t) {
                    existing.traveler_refs.push(t.clone());
                }
            }
            for seg in &segments {
                if !existing.segment_refs.contains(seg) {
                    existing.segment_refs.push(seg.clone());
                }
            }
        } else {
            s.orders.insert(
                order_id.clone(),
                OrderProjection {
                    account_id,
                    traveler_refs: travelers,
                    segment_refs: segments,
                    expected_total,
                    updated_at: Some(occurred_at),
                },
            );
        }
        s.processed_events.insert(e.event_id);
        Ok(())
    }
    async fn project_amount(
        &self,
        e: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), InvoicingError> {
        let Some(order_id) = string_field(&e.payload, "orderId") else {
            return Ok(());
        };
        let amount = e
            .payload
            .get("amount")
            .cloned()
            .or_else(|| e.payload.get("totalAmount").cloned())
            .ok_or_else(|| InvoicingError::ValidationFailed("financial amount is required".into()))?;
        let mut money: Money = serde_json::from_value(amount)
            .map_err(|error| InvoicingError::ValidationFailed(error.to_string()))?;
        let mut rr = string_field(&e.payload, "revenueRecognitionId")
            .map(|v| vec![v])
            .or_else(|| {
                e.payload
                    .get("revenueRecognitionIds")
                    .and_then(|v| serde_json::from_value(v.clone()).ok())
            })
            .unwrap_or_default();
        let mut s = self.state.lock().unwrap();
        let mut history = s.amount_history.get(&order_id).cloned().unwrap_or_default();
        if e.event_type == "RevenueRecognitionReversed" {
            let recognition = string_field(&e.payload, "revenueRecognitionId")
                .ok_or_else(|| InvoicingError::ValidationFailed("revenueRecognitionId is required".into()))?;
            let reference = string_field(&e.payload, "sourceEventId")
                .ok_or_else(|| InvoicingError::ValidationFailed("reversal sourceEventId is required".into()))?;
            let identity = format!("{recognition}:{reference}");
            if let Some(previous) = history.reversals.get(&identity) {
                if *previous != money { return Err(InvoicingError::DomainRuleViolation("reversal identity reused with another amount".into())); }
                s.processed_events.insert(e.event_id);
                return Ok(());
            }
            let existing = s.amounts.get(&order_id).ok_or_else(|| InvoicingError::Unavailable("reversal awaits original revenue".into()))?;
            if !existing.revenue_recognition_ids.contains(&recognition) {
                return Err(InvoicingError::Unavailable("reversal awaits its revenue recognition".into()));
            }
            if money.minor_units <= 0 || money.currency != existing.total_amount.currency {
                return Err(InvoicingError::ValidationFailed("reversal requires a positive matching currency amount".into()));
            }
            if let Some(recognized) = history.recognitions.get(&recognition) {
                let prefix = format!("{recognition}:");
                let returned = history.reversals.iter().filter(|(key, _)| key.starts_with(&prefix))
                    .try_fold(money.minor_units, |sum, (_, value)| sum.checked_add(value.minor_units))
                    .ok_or_else(|| InvoicingError::DomainRuleViolation("reversal total overflow".into()))?;
                if returned > recognized.minor_units { return Err(InvoicingError::DomainRuleViolation("reversal exceeds its recognized component".into())); }
            }
            let mut basis = existing.clone();
            basis.total_amount.minor_units = basis.total_amount.minor_units.checked_sub(money.minor_units)
                .filter(|amount| *amount >= 0).ok_or_else(|| InvoicingError::DomainRuleViolation("reversal exceeds available revenue".into()))?;
            history.reversals.insert(identity, money);
            basis.basis_type = "REVENUE_RECOGNITION".into();
            basis.finance_invoice_id = None;
            basis.tax_lines = vec![TaxLine { tax_code: "VAT_SIM".into(), tax_rate_basis_points: 0,
                taxable_amount: basis.total_amount.clone(), tax_amount: Money { currency: basis.total_amount.currency.clone(), minor_units: 0 } }];
            basis.amount_basis_hash = sha256_prefixed(&normalize_json(&json!({"revenueRecognitionIds":basis.revenue_recognition_ids,"totalAmount":basis.total_amount,"reversals":history.reversals})));
            s.amounts.insert(order_id.clone(),basis);
            s.amount_history.insert(order_id,history);
            s.processed_events.insert(e.event_id);
            return Ok(());
        }
        if e.event_type == "RevenueRecognized" {
            if rr.len() != 1 {
                return Err(InvoicingError::ValidationFailed("revenueRecognitionId is required".into()));
            }
            if let Some(existing) = s.amounts.get(&order_id) {
                if existing.revenue_recognition_ids.contains(&rr[0]) {
                    if history.recognitions.get(&rr[0]).is_some_and(|previous| *previous != money) {
                        return Err(InvoicingError::DomainRuleViolation("recognition identity reused with another amount".into()));
                    }
                    s.processed_events.insert(e.event_id);
                    return Ok(());
                }
                if existing.total_amount.currency != money.currency {
                    return Err(InvoicingError::ValidationFailed("revenue recognition currency mismatch".into()));
                }
                history.recognitions.insert(rr[0].clone(), money.clone());
                money.minor_units = money.minor_units.checked_add(existing.total_amount.minor_units)
                    .ok_or_else(|| InvoicingError::ValidationFailed("revenue total overflow".into()))?;
                rr.extend(existing.revenue_recognition_ids.iter().cloned());
            } else {
                history.recognitions.insert(rr[0].clone(),money.clone());
            }
        } else if let Some(existing) = s.amounts.get(&order_id) {
            if !history.reversals.is_empty() || rr.iter().all(|id| existing.revenue_recognition_ids.contains(id)) {
                s.processed_events.insert(e.event_id);
                return Ok(());
            }
            return Err(InvoicingError::Unavailable("finance invoice awaits its revenue components".into()));
        }
        rr.sort();
        let hash = sha256_prefixed(&normalize_json(&json!({"revenueRecognitionIds": rr, "totalAmount": money, "invoiceId": e.payload.get("invoiceId")})));
        let basis_type = if e.event_type == "InvoiceGenerated" {
            "FINANCE_INVOICE"
        } else {
            "REVENUE_RECOGNITION"
        };
        let basis = AmountBasis {
            basis_type: basis_type.into(),
            revenue_recognition_ids: rr,
            finance_invoice_id: string_field(&e.payload, "invoiceId"),
            tax_lines: vec![TaxLine {
                tax_code: "VAT_SIM".into(),
                tax_rate_basis_points: 0,
                taxable_amount: money.clone(),
                tax_amount: Money {
                    currency: money.currency.clone(),
                    minor_units: 0,
                },
            }],
            total_amount: money,
            amount_basis_hash: hash,
        };
        s.amounts.insert(order_id.clone(), basis);
        s.amount_history.insert(order_id, history);
        s.processed_events.insert(e.event_id);
        Ok(())
    }
    async fn observe_refund(
        &self,
        e: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), InvoicingError> {
        let case_id = string_field(&e.payload, "caseId").unwrap_or_default();
        let order_id = string_field(&e.payload, "orderId").unwrap_or_default();
        if case_id.is_empty() || order_id.is_empty() {
            return Ok(());
        }
        let event_id = e.event_id.clone();
        let (events, red_invoice) = {
            let mut s = self.state.lock().unwrap();
            let original = s
                .invoices
                .values()
                .find(|i| {
                    i.order_id == order_id
                        && i.invoice_type == InvoiceType::Blue
                        && i.status == InvoiceRequestStatus::Issued
                })
                .cloned();
            let Some(original) = original else {
                s.processed_events.insert(event_id);
                return Ok(());
            };
            if s.red_flushes.values().any(|rf| {
                rf.original_invoice_id == original.e_invoice_id
                    && rf.status == RedFlushStatus::Completed
            }) {
                s.processed_events.insert(e.event_id);
                return Ok(());
            }
            let (rf, red, evs) = RedFlushView::create_and_complete(
                &original,
                case_id,
                e.event_id.clone(),
                e.occurred_at.clone(),
            );
            s.red_flushes.insert(rf.red_flush_id.clone(), rf);
            s.invoices.insert(red.e_invoice_id.clone(), red.clone());
            (evs, red)
        };
        let _ = red_invoice;
        publish_events(
            self.publisher.as_ref(),
            events,
            e.correlation_id,
            Some(e.event_id.clone()),
        )
        .await?;
        self.state
            .lock()
            .unwrap()
            .processed_events
            .insert(e.event_id);
        Ok(())
    }
    async fn handle_saga_invoice_requested(
        &self,
        e: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), InvoicingError> {
        let saga_id = string_field(&e.payload, "sagaId").unwrap_or_default();
        let journey_order_id = string_field(&e.payload, "journeyOrderId").unwrap_or_default();
        let payment_ref = string_field(&e.payload, "paymentRef").unwrap_or_default();
        if saga_id.is_empty() || journey_order_id.is_empty() {
            // paymentRef is optional on the saga InvoiceRequested (empty in the
            // normal flow); requiring it made invoicing skip every request and hang
            // the booking saga at INVOICING. Only sagaId + journeyOrderId are needed.
            log::warn!(
                "InvoiceRequested missing sagaId or journeyOrderId, skipping: {}",
                e.event_id,
            );
            self.state
                .lock()
                .unwrap()
                .processed_events
                .insert(e.event_id);
            return Ok(());
        }
        let now = current_rfc3339();
        let invoice_id = format!("inv-{}", uuid::Uuid::now_v7());
        let ts_millis = chrono::Utc::now().timestamp_millis();
        let invoice_number = format!("INV-{ts_millis}");
        let invoice = SagaInvoice {
            invoice_id: invoice_id.clone(),
            journey_order_id: journey_order_id.clone(),
            invoice_number: invoice_number.clone(),
            payment_ref: payment_ref.clone(),
            saga_id: saga_id.clone(),
            issued_at: now.clone(),
            created_at: now.clone(),
        };
        let event = InvoicingEvent::SagaInvoiceGenerated {
            invoice: invoice.clone(),
            at: now,
        };
        {
            let mut s = self.state.lock().unwrap();
            s.saga_invoices.insert(invoice_id, invoice);
        }
        publish_events(
            self.publisher.as_ref(),
            vec![event],
            e.correlation_id,
            Some(e.event_id.clone()),
        )
        .await?;
        self.state
            .lock()
            .unwrap()
            .processed_events
            .insert(e.event_id);
        Ok(())
    }
}

#[derive(Clone, Default, serde::Serialize, serde::Deserialize)]
pub(crate) struct InvoicingStateSnapshot {
    pub titles: HashMap<String, InvoiceTitle>,
    pub requests: HashMap<String, EInvoiceRequest>,
    pub invoices: HashMap<String, EInvoice>,
    pub red_flushes: HashMap<String, RedFlushView>,
    pub orders: HashMap<String, OrderProjection>,
    pub amounts: HashMap<String, AmountBasis>,
    #[serde(default)]
    pub amount_history: HashMap<String, AmountHistory>,
    pub idempotency: HashMap<String, IdemRecord>,
    #[serde(default)]
    pub saga_invoices: HashMap<String, SagaInvoice>,
}

#[derive(Default)]
struct InMemoryState {
    titles: HashMap<String, InvoiceTitle>,
    requests: HashMap<String, EInvoiceRequest>,
    invoices: HashMap<String, EInvoice>,
    red_flushes: HashMap<String, RedFlushView>,
    orders: HashMap<String, OrderProjection>,
    amounts: HashMap<String, AmountBasis>,
    amount_history: HashMap<String, AmountHistory>,
    idempotency: HashMap<String, IdemRecord>,
    processed_events: HashSet<String>,
    saga_invoices: HashMap<String, SagaInvoice>,
}
#[derive(Clone, serde::Serialize, serde::Deserialize)]
pub(crate) struct OrderProjection {
    pub account_id: String,
    pub traveler_refs: Vec<String>,
    pub segment_refs: Vec<String>,
    #[serde(default)]
    pub expected_total: Option<Money>,
    #[serde(default)]
    pub updated_at: Option<chrono::DateTime<chrono::Utc>>,
}
#[derive(Clone, Default, serde::Serialize, serde::Deserialize)]
pub(crate) struct AmountHistory {
    pub recognitions: std::collections::BTreeMap<String, Money>,
    pub reversals: std::collections::BTreeMap<String, Money>,
}
#[derive(Clone, serde::Serialize, serde::Deserialize)]
pub(crate) struct IdemRecord {
    pub(crate) op: String,
    pub(crate) fp: String,
    pub(crate) response: serde_json::Value,
}

fn amount_basis_matches_projection(requested: &AmountBasis, projected: &AmountBasis) -> bool {
    let mut requested = requested.clone();
    let mut projected = projected.clone();
    if requested.validate_and_sort().is_err() || projected.validate_and_sort().is_err() {
        return false;
    }
    requested.basis_type == projected.basis_type
        && requested.total_amount == projected.total_amount
        && requested.revenue_recognition_ids == projected.revenue_recognition_ids
        && requested.finance_invoice_id == projected.finance_invoice_id
        && requested.tax_lines == projected.tax_lines
}

#[async_trait]
impl InvoicingApi for InMemoryInvoicingService {
    async fn get_amount_basis(&self, order_id: String) -> Result<AmountBasis, InvoicingError> {
        let state = self.state.lock().unwrap();
        let basis = state.amounts.get(&order_id).cloned()
            .ok_or_else(|| InvoicingError::NotFound("amount basis not yet available".into()))?;
        if state.orders.get(&order_id).and_then(|order| order.expected_total.as_ref())
            .is_some_and(|total| *total != basis.total_amount) {
            return Err(InvoicingError::NotFound("complete amount basis not yet available".into()));
        }
        Ok(basis)
    }
    async fn create_title(
        &self,
        cmd: CreateInvoiceTitleCommand,
        key: String,
        corr: String,
    ) -> Result<InvoiceTitle, InvoicingError> {
        validate_uuid_v7_key(&key)?;
        let fp = normalize_json(&cmd);
        let (title, event) = {
            let mut s = self.state.lock().unwrap();
            if let Some(r) = s.idempotency.get(&key) {
                if r.op != "create_title" || r.fp != fp {
                    return Err(InvoicingError::IdempotencyKeyReused(
                        "Idempotency-Key was reused with a different request body".into(),
                    ));
                }
                return serde_json::from_value(r.response.clone())
                    .map_err(|e| InvoicingError::Internal(e.to_string()));
            }
            let (title, event) = InvoiceTitle::create(cmd, current_rfc3339())?;
            let mh = InvoiceTitle::material_hash(
                &title.account_id,
                &title.title_type,
                &title.title_name,
                &title.tax_identity_hash,
                &title.bank_account_hash,
            );
            if s.titles.values().any(|t| {
                t.account_id == title.account_id
                    && t.status == TitleStatus::Active
                    && InvoiceTitle::material_hash(
                        &t.account_id,
                        &t.title_type,
                        &t.title_name,
                        &t.tax_identity_hash,
                        &t.bank_account_hash,
                    ) == mh
            }) {
                return Err(InvoicingError::Conflict(
                    "active invoice title already exists for this account".into(),
                ));
            }
            if title.is_default {
                for t in s
                    .titles
                    .values_mut()
                    .filter(|t| t.account_id == title.account_id)
                {
                    t.is_default = false;
                }
            }
            s.titles.insert(title.title_id.clone(), title.clone());
            s.idempotency.insert(
                key,
                IdemRecord {
                    op: "create_title".to_string(),
                    fp,
                    response: serde_json::to_value(&title).unwrap(),
                },
            );
            (title, event)
        };
        publish_events(self.publisher.as_ref(), vec![event], corr, None).await?;
        Ok(title)
    }
    async fn list_titles(
        &self,
        account_id: String,
        status: Option<TitleStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<Page<InvoiceTitle>, InvoicingError> {
        let s = self.state.lock().unwrap();
        let mut items: Vec<_> = s
            .titles
            .values()
            .filter(|t| {
                t.account_id == account_id
                    && status.as_ref().map(|st| &t.status == st).unwrap_or(true)
            })
            .cloned()
            .collect();
        items.sort_by(|a, b| a.title_id.cmp(&b.title_id));
        let total = items.len();
        Ok(Page {
            items: items.into_iter().skip(offset).take(limit).collect(),
            total,
            limit,
            offset,
        })
    }
    async fn get_title(&self, id: String) -> Result<InvoiceTitle, InvoicingError> {
        self.state
            .lock()
            .unwrap()
            .titles
            .get(&id)
            .cloned()
            .ok_or_else(|| InvoicingError::NotFound("InvoiceTitle not found".into()))
    }
    async fn update_title(
        &self,
        id: String,
        cmd: UpdateInvoiceTitleCommand,
        key: String,
        corr: String,
    ) -> Result<InvoiceTitle, InvoicingError> {
        self.mutate_title(
            id,
            key,
            corr,
            normalize_json(&cmd),
            "update_title",
            |t, now| t.update(cmd, now),
        )
        .await
    }
    async fn set_default_title(
        &self,
        id: String,
        cmd: SetDefaultTitleCommand,
        key: String,
        corr: String,
    ) -> Result<InvoiceTitle, InvoicingError> {
        validate_uuid_v7_key(&key)?;
        let fp = format!("{}:{}:SET_DEFAULT", cmd.account_id, id);
        let (title, event) = {
            let mut s = self.state.lock().unwrap();
            if let Some(r) = s.idempotency.get(&key) {
                if r.fp != fp {
                    return Err(InvoicingError::IdempotencyKeyReused(
                        "Idempotency-Key was reused with a different request body".into(),
                    ));
                }
                return serde_json::from_value(r.response.clone())
                    .map_err(|e| InvoicingError::Internal(e.to_string()));
            }
            let previous = s
                .titles
                .values()
                .find(|t| t.account_id == cmd.account_id && t.is_default)
                .map(|t| t.title_id.clone());
            for t in s
                .titles
                .values_mut()
                .filter(|t| t.account_id == cmd.account_id)
            {
                t.is_default = false;
            }
            let title = s
                .titles
                .get_mut(&id)
                .ok_or_else(|| InvoicingError::NotFound("InvoiceTitle not found".into()))?;
            if title.account_id != cmd.account_id || title.status != TitleStatus::Active {
                return Err(InvoicingError::PreconditionFailed(
                    "title is not active for account".into(),
                ));
            }
            title.is_default = true;
            title.updated_at = current_rfc3339();
            let out = title.clone();
            s.idempotency.insert(
                key,
                IdemRecord {
                    op: "set_default".to_string(),
                    fp,
                    response: serde_json::to_value(&out).unwrap(),
                },
            );
            (
                out.clone(),
                InvoicingEvent::DefaultInvoiceTitleSet {
                    account_id: out.account_id.clone(),
                    title_id: out.title_id.clone(),
                    previous_default_title_id: previous,
                    at: out.updated_at.clone(),
                },
            )
        };
        publish_events(self.publisher.as_ref(), vec![event], corr, None).await?;
        Ok(title)
    }
    async fn deactivate_title(
        &self,
        id: String,
        cmd: DeactivateTitleCommand,
        key: String,
        corr: String,
    ) -> Result<DeactivateTitleResponse, InvoicingError> {
        validate_uuid_v7_key(&key)?;
        let fp = normalize_json(&cmd);
        let (resp, event) = {
            let mut s = self.state.lock().unwrap();
            if let Some(r) = s.idempotency.get(&key) {
                if r.fp != fp {
                    return Err(InvoicingError::IdempotencyKeyReused(
                        "Idempotency-Key was reused with a different request body".into(),
                    ));
                }
                return serde_json::from_value(r.response.clone())
                    .map_err(|e| InvoicingError::Internal(e.to_string()));
            }
            let title = s
                .titles
                .get_mut(&id)
                .ok_or_else(|| InvoicingError::NotFound("InvoiceTitle not found".into()))?;
            let ev = title.deactivate(cmd.expected_version, cmd.reason, current_rfc3339())?;
            let resp = DeactivateTitleResponse {
                title_id: title.title_id.clone(),
                status: TitleStatus::Deactivated,
                deactivated_at: title.updated_at.clone(),
            };
            s.idempotency.insert(
                key,
                IdemRecord {
                    op: "deactivate".to_string(),
                    fp,
                    response: serde_json::to_value(&resp).unwrap(),
                },
            );
            (resp, ev)
        };
        publish_events(self.publisher.as_ref(), vec![event], corr, None).await?;
        Ok(resp)
    }
    async fn request_invoice(
        &self,
        cmd: RequestEInvoiceCommand,
        key: String,
        corr: String,
    ) -> Result<EInvoiceRequest, InvoicingError> {
        validate_uuid_v7_key(&key)?;
        let fp = normalize_json(&cmd);
        let (req, events) = {
            let mut s = self.state.lock().unwrap();
            if let Some(r) = s.idempotency.get(&key) {
                if r.fp != fp {
                    return Err(InvoicingError::IdempotencyKeyReused(
                        "Idempotency-Key was reused with a different request body".into(),
                    ));
                }
                return serde_json::from_value(r.response.clone())
                    .map_err(|e| InvoicingError::Internal(e.to_string()));
            }
            let title = s
                .titles
                .get(&cmd.title_id)
                .cloned()
                .ok_or_else(|| InvoicingError::NotFound("InvoiceTitle not found".into()))?;
            let order = match s.orders.get(&cmd.order_id) {
                Some(order) => order,
                None => {
                    if title.status != TitleStatus::Active
                        || title.version != cmd.title_version
                        || title.account_id != cmd.account_id
                    {
                        return Err(InvoicingError::PreconditionFailed(
                            "invoice title is not current/usable for this account".into(),
                        ));
                    }
                    return Err(InvoicingError::PreconditionFailed(
                        "missing journey-order confirmation fact for order".into(),
                    ));
                }
            };
            if order.account_id != cmd.account_id {
                return Err(InvoicingError::PreconditionFailed(
                    "order confirmation account does not match invoice request".into(),
                ));
            }
            let Some(projected_amount) = s.amounts.get(&cmd.order_id) else {
                return Err(InvoicingError::PreconditionFailed(
                    "missing finance-settlement amount basis projection for order".into(),
                ));
            };
            if order.expected_total.as_ref().is_some_and(|total| *total != projected_amount.total_amount) {
                return Err(InvoicingError::PreconditionFailed("missing finance-settlement components for complete order amount".into()));
            }
            if !amount_basis_matches_projection(&cmd.amount_basis, projected_amount) {
                return Err(InvoicingError::PreconditionFailed(
                    "amountBasis does not match consumed finance-settlement projection".into(),
                ));
            }
            let mut scope_check = cmd.invoice_scope.clone();
            scope_check.validate_and_sort()?;
            if scope_check.scope_type == "SEGMENTS"
                && !scope_check
                    .segment_refs
                    .iter()
                    .all(|seg| order.segment_refs.contains(seg))
            {
                return Err(InvoicingError::PreconditionFailed(
                    "invoice scope segment is not proven by order facts".into(),
                ));
            }
            if scope_check.scope_type == "TRAVELERS"
                && !scope_check
                    .traveler_refs
                    .iter()
                    .all(|t| order.traveler_refs.contains(t))
            {
                return Err(InvoicingError::PreconditionFailed(
                    "invoice scope traveler is not proven by order facts".into(),
                ));
            }
            let (req, invoice, events) =
                EInvoiceRequest::create_and_submit(cmd, &title, key.clone(), current_rfc3339())?;
            if s.requests.values().any(|r| {
                r.order_id == req.order_id
                    && r.amount_basis.amount_basis_hash == req.amount_basis.amount_basis_hash
                    && !matches!(
                        r.status,
                        InvoiceRequestStatus::Rejected
                            | InvoiceRequestStatus::Failed
                            | InvoiceRequestStatus::Expired
                            | InvoiceRequestStatus::Cancelled
                    )
            }) {
                return Err(InvoicingError::Conflict(
                    "invoice already requested for this order and amount basis".into(),
                ));
            }
            if let Some(inv) = invoice {
                s.invoices.insert(inv.e_invoice_id.clone(), inv);
            }
            s.requests
                .insert(req.invoice_request_id.clone(), req.clone());
            s.idempotency.insert(
                key,
                IdemRecord {
                    op: "request_invoice".to_string(),
                    fp,
                    response: serde_json::to_value(&req).unwrap(),
                },
            );
            (req, events)
        };
        publish_events(
            self.publisher.as_ref(),
            events,
            corr,
            Some(command_id_from_key(&req.client_request_id)),
        )
        .await?;
        Ok(req)
    }
    async fn get_request(&self, id: String) -> Result<EInvoiceRequest, InvoicingError> {
        self.state
            .lock()
            .unwrap()
            .requests
            .get(&id)
            .cloned()
            .ok_or_else(|| InvoicingError::NotFound("EInvoiceRequest not found".into()))
    }
    async fn get_invoice(&self, id: String) -> Result<EInvoice, InvoicingError> {
        self.state
            .lock()
            .unwrap()
            .invoices
            .get(&id)
            .cloned()
            .ok_or_else(|| InvoicingError::NotFound("EInvoice not found".into()))
    }
    async fn list_invoices(
        &self,
        order_id: String,
        invoice_type: Option<InvoiceType>,
        status: Option<InvoiceRequestStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<Page<EInvoice>, InvoicingError> {
        let s = self.state.lock().unwrap();
        let mut items: Vec<_> = s
            .invoices
            .values()
            .filter(|i| {
                i.order_id == order_id
                    && invoice_type
                        .as_ref()
                        .map(|t| &i.invoice_type == t)
                        .unwrap_or(true)
                    && status.as_ref().map(|st| &i.status == st).unwrap_or(true)
            })
            .cloned()
            .collect();
        items.sort_by(|a, b| a.e_invoice_id.cmp(&b.e_invoice_id));
        let total = items.len();
        Ok(Page {
            items: items.into_iter().skip(offset).take(limit).collect(),
            total,
            limit,
            offset,
        })
    }
    async fn get_red_flush(&self, id: String) -> Result<RedFlushView, InvoicingError> {
        self.state
            .lock()
            .unwrap()
            .red_flushes
            .get(&id)
            .cloned()
            .ok_or_else(|| InvoicingError::NotFound("RedFlush not found".into()))
    }
    async fn list_red_flushes(
        &self,
        order_id: Option<String>,
        case_id: Option<String>,
        status: Option<RedFlushStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<Page<RedFlushView>, InvoicingError> {
        let s = self.state.lock().unwrap();
        let mut items: Vec<_> = s
            .red_flushes
            .values()
            .filter(|r| {
                order_id.as_ref().map(|o| &r.order_id == o).unwrap_or(true)
                    && case_id
                        .as_ref()
                        .map(|c| &r.post_sales_case_id == c)
                        .unwrap_or(true)
                    && status.as_ref().map(|st| &r.status == st).unwrap_or(true)
            })
            .cloned()
            .collect();
        items.sort_by(|a, b| a.red_flush_id.cmp(&b.red_flush_id));
        let total = items.len();
        Ok(Page {
            items: items.into_iter().skip(offset).take(limit).collect(),
            total,
            limit,
            offset,
        })
    }
    async fn generate_itinerary(
        &self,
        order_id: String,
        mut traveler_refs: Vec<String>,
        mut segment_refs: Vec<String>,
        receipt_version: i64,
    ) -> Result<ItineraryReceiptProjection, InvoicingError> {
        if order_id.trim().is_empty() || traveler_refs.is_empty() || segment_refs.is_empty() {
            return Err(InvoicingError::ValidationFailed(
                "orderId, travelerRefs and segmentRefs are required".into(),
            ));
        }
        traveler_refs.sort();
        segment_refs.sort();
        let Some(order) = self.state.lock().unwrap().orders.get(&order_id).cloned() else {
            return Err(InvoicingError::NotFound(
                "order confirmation projection not found".into(),
            ));
        };
        if !traveler_refs
            .iter()
            .all(|traveler| order.traveler_refs.contains(traveler))
            || !segment_refs
                .iter()
                .all(|segment| order.segment_refs.contains(segment))
        {
            return Err(InvoicingError::PreconditionFailed(
                "requested itinerary traveler/segment material is not proven by order facts".into(),
            ));
        }
        let material = normalize_json(&(
            order_id.clone(),
            traveler_refs.clone(),
            segment_refs.clone(),
            receipt_version,
        ));
        let hash = sha256_prefixed(&material);
        Ok(ItineraryReceiptProjection {
            itinerary_receipt_id: folded_id("itr", &hash),
            order_id,
            traveler_refs,
            segment_refs,
            receipt_version,
            receipt_no: format!("ITR{}", sha256_hex(&hash)[..12].to_ascii_uppercase()),
            display_name_masked: None,
            travel_summary: serde_json::json!({"source":"ORDER_PROJECTION","templateVersion":receipt_version}),
            artifact_ref: format!("artifact://itinerary/{}", sha256_hex(&hash)),
            generated_at: current_rfc3339(),
            source_material_hash: hash,
        })
    }
}
impl InMemoryInvoicingService {
    async fn mutate_title<F>(
        &self,
        id: String,
        key: String,
        corr: String,
        fp: String,
        op: &'static str,
        fun: F,
    ) -> Result<InvoiceTitle, InvoicingError>
    where
        F: FnOnce(&mut InvoiceTitle, String) -> Result<InvoicingEvent, InvoicingError>,
    {
        validate_uuid_v7_key(&key)?;
        let (title, event) = {
            let mut s = self.state.lock().unwrap();
            if let Some(r) = s.idempotency.get(&key) {
                if r.op != op || r.fp != fp {
                    return Err(InvoicingError::IdempotencyKeyReused(
                        "Idempotency-Key was reused with a different request body".into(),
                    ));
                }
                return serde_json::from_value(r.response.clone())
                    .map_err(|e| InvoicingError::Internal(e.to_string()));
            }
            let title = s
                .titles
                .get_mut(&id)
                .ok_or_else(|| InvoicingError::NotFound("InvoiceTitle not found".into()))?;
            let ev = fun(title, current_rfc3339())?;
            let out = title.clone();
            s.idempotency.insert(
                key,
                IdemRecord {
                    op: op.to_string(),
                    fp,
                    response: serde_json::to_value(&out).unwrap(),
                },
            );
            (out, ev)
        };
        publish_events(self.publisher.as_ref(), vec![event], corr, None).await?;
        Ok(title)
    }
}
