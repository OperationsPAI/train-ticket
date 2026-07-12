use crate::utils::*;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TitleType {
    Personal,
    Enterprise,
}
impl TitleType {
    pub fn as_contract(&self) -> &'static str {
        match self {
            Self::Personal => "PERSONAL",
            Self::Enterprise => "ENTERPRISE",
        }
    }
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TitleStatus {
    Active,
    Deactivated,
}
impl TitleStatus {
    pub fn as_contract(&self) -> &'static str {
        match self {
            Self::Active => "ACTIVE",
            Self::Deactivated => "DEACTIVATED",
        }
    }
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum InvoiceRequestStatus {
    Requested,
    AmountReady,
    Submitted,
    Accepted,
    Issued,
    RedFlushPending,
    RedFlushed,
    Cancelled,
    Rejected,
    Failed,
    Expired,
}
impl InvoiceRequestStatus {
    pub fn as_contract(&self) -> &'static str {
        match self {
            Self::Requested => "REQUESTED",
            Self::AmountReady => "AMOUNT_READY",
            Self::Submitted => "SUBMITTED",
            Self::Accepted => "ACCEPTED",
            Self::Issued => "ISSUED",
            Self::RedFlushPending => "RED_FLUSH_PENDING",
            Self::RedFlushed => "RED_FLUSHED",
            Self::Cancelled => "CANCELLED",
            Self::Rejected => "REJECTED",
            Self::Failed => "FAILED",
            Self::Expired => "EXPIRED",
        }
    }
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum InvoiceType {
    Blue,
    Red,
}
impl InvoiceType {
    pub fn as_contract(&self) -> &'static str {
        match self {
            Self::Blue => "BLUE",
            Self::Red => "RED",
        }
    }
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RedFlushStatus {
    Requested,
    BlockingRefund,
    Submitted,
    Accepted,
    Completed,
    Rejected,
    Failed,
    Cancelled,
}
impl RedFlushStatus {
    pub fn as_contract(&self) -> &'static str {
        match self {
            Self::Requested => "REQUESTED",
            Self::BlockingRefund => "BLOCKING_REFUND",
            Self::Submitted => "SUBMITTED",
            Self::Accepted => "ACCEPTED",
            Self::Completed => "COMPLETED",
            Self::Rejected => "REJECTED",
            Self::Failed => "FAILED",
            Self::Cancelled => "CANCELLED",
        }
    }
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ObservationStatus {
    NoInvoice,
    RedFlushCompleted,
    Suspended,
    ViolationObserved,
}
impl ObservationStatus {
    pub fn as_contract(&self) -> &'static str {
        match self {
            Self::NoInvoice => "NO_INVOICE",
            Self::RedFlushCompleted => "RED_FLUSH_COMPLETED",
            Self::Suspended => "SUSPENDED",
            Self::ViolationObserved => "VIOLATION_OBSERVED",
        }
    }
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ReleaseFlagStatus {
    Allowed,
    Suspended,
    ReleasedAfterRedFlush,
    ViolationObserved,
}
impl ReleaseFlagStatus {
    pub fn as_contract(&self) -> &'static str {
        match self {
            Self::Allowed => "ALLOWED",
            Self::Suspended => "SUSPENDED",
            Self::ReleasedAfterRedFlush => "RELEASED_AFTER_RED_FLUSH",
            Self::ViolationObserved => "VIOLATION_OBSERVED",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Money {
    pub currency: String,
    pub minor_units: i64,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TaxLine {
    pub tax_code: String,
    pub tax_rate_basis_points: i64,
    pub taxable_amount: Money,
    pub tax_amount: Money,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InvoiceScope {
    pub scope_type: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub order_item_refs: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub segment_refs: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub traveler_refs: Vec<String>,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AmountBasis {
    pub basis_type: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub revenue_recognition_ids: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub finance_invoice_id: Option<String>,
    pub tax_lines: Vec<TaxLine>,
    pub total_amount: Money,
    pub amount_basis_hash: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InvoiceTitle {
    pub title_id: String,
    pub account_id: String,
    pub title_type: TitleType,
    pub title_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tax_identity_masked: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tax_identity_hash: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub registered_address: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub registered_phone_masked: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bank_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bank_account_masked: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bank_account_hash: Option<String>,
    pub is_default: bool,
    pub status: TitleStatus,
    pub version: i64,
    pub valid_from: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub valid_to: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EInvoiceRequest {
    pub invoice_request_id: String,
    pub account_id: String,
    pub order_id: String,
    pub title_id: String,
    pub title_version: i64,
    pub invoice_scope: InvoiceScope,
    pub amount_basis: AmountBasis,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub recipient_email_masked: Option<String>,
    pub client_request_id: String,
    pub gateway_profile: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sim_seed_ref: Option<String>,
    pub status: InvoiceRequestStatus,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub e_invoice_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rejection_code: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub failure_class: Option<String>,
    pub created_at: String,
    pub updated_at: String,
    pub version: i64,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EInvoice {
    pub e_invoice_id: String,
    pub invoice_request_id: String,
    pub order_id: String,
    pub invoice_type: InvoiceType,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub invoice_number: Option<String>,
    pub gateway_request_id: String,
    pub gateway_profile: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub gateway_status: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub download_ref: Option<String>,
    pub total_amount: Money,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub issued_at: Option<String>,
    pub status: InvoiceRequestStatus,
    pub created_at: String,
    pub updated_at: String,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RedFlushView {
    pub red_flush_id: String,
    pub original_invoice_id: String,
    pub post_sales_case_id: String,
    pub order_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub refund_fact_event_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub red_invoice_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub red_invoice_number: Option<String>,
    pub status: RedFlushStatus,
    pub refund_red_flush_observation_status: ObservationStatus,
    pub refund_release_flag_status: ReleaseFlagStatus,
    pub created_at: String,
    pub updated_at: String,
    pub version: i64,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ItineraryReceiptProjection {
    pub itinerary_receipt_id: String,
    pub order_id: String,
    pub traveler_refs: Vec<String>,
    pub segment_refs: Vec<String>,
    pub receipt_version: i64,
    pub receipt_no: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub display_name_masked: Option<String>,
    pub travel_summary: Value,
    pub artifact_ref: String,
    pub generated_at: String,
    pub source_material_hash: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateInvoiceTitleCommand {
    pub account_id: String,
    pub title_type: TitleType,
    pub title_name: String,
    pub tax_identity: Option<String>,
    pub registered_address: Option<String>,
    pub registered_phone: Option<String>,
    pub bank_name: Option<String>,
    pub bank_account: Option<String>,
    #[serde(default)]
    pub set_as_default: bool,
}
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInvoiceTitleCommand {
    pub expected_version: i64,
    pub title_name: String,
    pub tax_identity: Option<String>,
    pub registered_address: Option<String>,
    pub registered_phone: Option<String>,
    pub bank_name: Option<String>,
    pub bank_account: Option<String>,
}
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetDefaultTitleCommand {
    pub account_id: String,
}
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeactivateTitleCommand {
    pub expected_version: i64,
    pub reason: String,
}
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RequestEInvoiceCommand {
    pub account_id: String,
    pub order_id: String,
    pub title_id: String,
    pub title_version: i64,
    pub invoice_scope: InvoiceScope,
    pub amount_basis: AmountBasis,
    pub recipient_email: Option<String>,
    pub gateway_profile: Option<String>,
    pub sim_seed_ref: Option<String>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeactivateTitleResponse {
    pub title_id: String,
    pub status: TitleStatus,
    pub deactivated_at: String,
}
/// Invoice record created in response to a booking saga's InvoiceRequested event.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SagaInvoice {
    pub invoice_id: String,
    pub journey_order_id: String,
    pub invoice_number: String,
    pub payment_ref: String,
    pub saga_id: String,
    pub issued_at: String,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Page<T> {
    pub items: Vec<T>,
    pub total: usize,
    pub limit: usize,
    pub offset: usize,
}

impl InvoiceTitle {
    pub fn material_hash(
        account_id: &str,
        title_type: &TitleType,
        title_name: &str,
        tax_identity_hash: &Option<String>,
        bank_account_hash: &Option<String>,
    ) -> String {
        sha256_prefixed(&format!(
            "{}|{}|{}|{}|{}",
            account_id.trim(),
            title_type.as_contract(),
            title_name.split_whitespace().collect::<Vec<_>>().join(" "),
            tax_identity_hash.clone().unwrap_or_default(),
            bank_account_hash.clone().unwrap_or_default()
        ))
    }
    pub fn create(
        cmd: CreateInvoiceTitleCommand,
        now: String,
    ) -> Result<(Self, InvoicingEvent), InvoicingError> {
        validate_non_empty(&cmd.account_id, "accountId")?;
        validate_non_empty(&cmd.title_name, "titleName")?;
        let tax_hash = cmd.tax_identity.as_ref().map(|v| sha256_prefixed(v.trim()));
        if matches!(cmd.title_type, TitleType::Enterprise) && tax_hash.is_none() {
            return Err(InvoicingError::DomainRuleViolation(
                "enterprise invoice title requires taxIdentity".into(),
            ));
        }
        let bank_hash = cmd.bank_account.as_ref().map(|v| sha256_prefixed(v.trim()));
        let id = format!("ivt-{}", uuid::Uuid::now_v7());
        let title = Self {
            title_id: id,
            account_id: cmd.account_id,
            title_type: cmd.title_type,
            title_name: cmd.title_name.trim().into(),
            tax_identity_masked: cmd.tax_identity.as_ref().map(|v| mask_tail(v, 4)),
            tax_identity_hash: tax_hash,
            registered_address: cmd.registered_address,
            registered_phone_masked: cmd.registered_phone.as_ref().map(|v| mask_tail(v, 4)),
            bank_name: cmd.bank_name,
            bank_account_masked: cmd.bank_account.as_ref().map(|v| mask_tail(v, 4)),
            bank_account_hash: bank_hash,
            is_default: cmd.set_as_default,
            status: TitleStatus::Active,
            version: 1,
            valid_from: now.clone(),
            valid_to: None,
            created_at: now.clone(),
            updated_at: now.clone(),
        };
        Ok((
            title.clone(),
            InvoicingEvent::InvoiceTitleCreated { title, at: now },
        ))
    }
    pub fn update(
        &mut self,
        cmd: UpdateInvoiceTitleCommand,
        now: String,
    ) -> Result<InvoicingEvent, InvoicingError> {
        if self.status != TitleStatus::Active || self.version != cmd.expected_version {
            return Err(InvoicingError::PreconditionFailed(
                "title version is not current or title is deactivated".into(),
            ));
        }
        validate_non_empty(&cmd.title_name, "titleName")?;
        let prev = self.version;
        self.version += 1;
        self.title_name = cmd.title_name.trim().into();
        if let Some(tax) = cmd.tax_identity {
            self.tax_identity_masked = Some(mask_tail(&tax, 4));
            self.tax_identity_hash = Some(sha256_prefixed(tax.trim()));
        }
        self.registered_address = cmd.registered_address;
        self.registered_phone_masked = cmd.registered_phone.as_ref().map(|v| mask_tail(v, 4));
        self.bank_name = cmd.bank_name;
        if let Some(bank) = cmd.bank_account {
            self.bank_account_masked = Some(mask_tail(&bank, 4));
            self.bank_account_hash = Some(sha256_prefixed(bank.trim()));
        }
        self.updated_at = now.clone();
        Ok(InvoicingEvent::InvoiceTitleUpdated {
            title: self.clone(),
            previous_version: prev,
            at: now,
        })
    }
    pub fn deactivate(
        &mut self,
        expected_version: i64,
        reason: String,
        now: String,
    ) -> Result<InvoicingEvent, InvoicingError> {
        if self.status != TitleStatus::Active || self.version != expected_version {
            return Err(InvoicingError::PreconditionFailed(
                "title version is not current or title is already deactivated".into(),
            ));
        }
        validate_non_empty(&reason, "reason")?;
        self.status = TitleStatus::Deactivated;
        self.is_default = false;
        self.valid_to = Some(now.clone());
        self.updated_at = now.clone();
        Ok(InvoicingEvent::InvoiceTitleDeactivated {
            title: self.clone(),
            reason,
            at: now,
        })
    }
}

impl InvoiceScope {
    pub fn validate_and_sort(&mut self) -> Result<(), InvoicingError> {
        self.scope_type = self.scope_type.trim().to_ascii_uppercase();
        self.order_item_refs.sort();
        self.segment_refs.sort();
        self.traveler_refs.sort();
        match self.scope_type.as_str() {
            "ORDER" => Ok(()),
            "ORDER_ITEMS" if !self.order_item_refs.is_empty() => Ok(()),
            "SEGMENTS" if !self.segment_refs.is_empty() => Ok(()),
            "TRAVELERS" if !self.traveler_refs.is_empty() => Ok(()),
            _ => Err(InvoicingError::DomainRuleViolation(
                "unsupported or incomplete invoiceScope".into(),
            )),
        }
    }
}
impl AmountBasis {
    pub fn validate_and_sort(&mut self) -> Result<(), InvoicingError> {
        self.basis_type = self.basis_type.trim().to_ascii_uppercase();
        self.total_amount.currency = self.total_amount.currency.trim().to_ascii_uppercase();
        if self.total_amount.minor_units <= 0 {
            return Err(InvoicingError::DomainRuleViolation(
                "amountBasis.totalAmount must be positive".into(),
            ));
        }
        self.amount_basis_hash = self.amount_basis_hash.trim().to_string();
        if self.amount_basis_hash.is_empty() {
            return Err(InvoicingError::DomainRuleViolation(
                "amountBasisHash must be supplied from Finance Settlement projection".into(),
            ));
        }
        self.revenue_recognition_ids.sort();
        for line in &mut self.tax_lines {
            line.tax_code = line.tax_code.trim().to_ascii_uppercase();
            line.taxable_amount.currency = line.taxable_amount.currency.trim().to_ascii_uppercase();
            line.tax_amount.currency = line.tax_amount.currency.trim().to_ascii_uppercase();
        }
        Ok(())
    }
}

impl EInvoiceRequest {
    pub fn material_hash(cmd: &RequestEInvoiceCommand) -> String {
        sha256_prefixed(&normalize_json(cmd))
    }
    pub fn create_and_submit(
        mut cmd: RequestEInvoiceCommand,
        title: &InvoiceTitle,
        client_key: String,
        now: String,
    ) -> Result<(Self, Option<EInvoice>, Vec<InvoicingEvent>), InvoicingError> {
        if title.status != TitleStatus::Active
            || title.version != cmd.title_version
            || title.account_id != cmd.account_id
        {
            return Err(InvoicingError::PreconditionFailed(
                "invoice title is not current/usable for this account".into(),
            ));
        }
        validate_non_empty(&cmd.order_id, "orderId")?;
        cmd.invoice_scope.validate_and_sort()?;
        cmd.amount_basis.validate_and_sort()?;
        let request_id = format!("ivr-{}", uuid::Uuid::now_v7());
        let profile = cmd
            .gateway_profile
            .clone()
            .unwrap_or_else(|| "SIM_TAX_BUREAU_CN_V1".into());
        let fingerprint = sha256_prefixed(&format!(
            "{}|{}|{}|{}",
            cmd.order_id,
            title.title_id,
            cmd.amount_basis.amount_basis_hash,
            cmd.sim_seed_ref.clone().unwrap_or_default()
        ));
        let gateway_request_id = folded_id("igr", &fingerprint);
        let mut req = Self {
            invoice_request_id: request_id.clone(),
            account_id: cmd.account_id,
            order_id: cmd.order_id,
            title_id: title.title_id.clone(),
            title_version: title.version,
            invoice_scope: cmd.invoice_scope,
            amount_basis: cmd.amount_basis,
            recipient_email_masked: cmd.recipient_email.as_deref().and_then(mask_email),
            client_request_id: client_key,
            gateway_profile: profile.clone(),
            sim_seed_ref: cmd.sim_seed_ref.clone(),
            status: InvoiceRequestStatus::AmountReady,
            e_invoice_id: None,
            rejection_code: None,
            failure_class: None,
            created_at: now.clone(),
            updated_at: now.clone(),
            version: 1,
        };
        let mut events = vec![
            InvoicingEvent::InvoiceRequested {
                request: req.clone(),
                at: now.clone(),
            },
            InvoicingEvent::InvoiceAmountBasisAttached {
                request: req.clone(),
                at: now.clone(),
            },
        ];
        req.status = InvoiceRequestStatus::Submitted;
        req.version += 1;
        req.updated_at = now.clone();
        events.push(InvoicingEvent::EInvoiceSubmitted {
            request: req.clone(),
            gateway_request_id: gateway_request_id.clone(),
            request_fingerprint: fingerprint.clone(),
            at: now.clone(),
        });
        let seed = cmd
            .sim_seed_ref
            .unwrap_or_else(|| fingerprint.clone())
            .to_ascii_lowercase();
        if seed.contains("fail") {
            req.status = InvoiceRequestStatus::Failed;
            req.failure_class = Some("SIM_TECHNICAL_FAILURE".into());
            req.version += 1;
            req.updated_at = now.clone();
            events.push(InvoicingEvent::EInvoiceFailed {
                request: req.clone(),
                at: now,
            });
            return Ok((req, None, events));
        }
        if seed.contains("reject") || seed.ends_with('0') || seed.ends_with('5') {
            req.status = InvoiceRequestStatus::Rejected;
            req.rejection_code = Some("SIM_DETERMINISTIC_REJECTED".into());
            req.version += 1;
            req.updated_at = now.clone();
            events.push(InvoicingEvent::EInvoiceRejected {
                request: req.clone(),
                gateway_request_id,
                at: now,
            });
            return Ok((req, None, events));
        }
        req.status = InvoiceRequestStatus::Accepted;
        req.version += 1;
        req.updated_at = now.clone();
        events.push(InvoicingEvent::EInvoiceAccepted {
            request: req.clone(),
            gateway_request_id: gateway_request_id.clone(),
            at: now.clone(),
        });
        let ein_id = format!("ein-{}", uuid::Uuid::now_v7());
        let number = format!("SIM{}", sha256_hex(&fingerprint)[..16].to_ascii_uppercase());
        let invoice = EInvoice {
            e_invoice_id: ein_id.clone(),
            invoice_request_id: request_id,
            order_id: req.order_id.clone(),
            invoice_type: InvoiceType::Blue,
            invoice_number: Some(number),
            gateway_request_id,
            gateway_profile: profile,
            gateway_status: Some("ACCEPTED".into()),
            download_ref: Some(format!("artifact://invoicing/{ein_id}")),
            total_amount: req.amount_basis.total_amount.clone(),
            issued_at: Some(now.clone()),
            status: InvoiceRequestStatus::Issued,
            created_at: now.clone(),
            updated_at: now.clone(),
        };
        req.status = InvoiceRequestStatus::Issued;
        req.e_invoice_id = Some(ein_id);
        req.version += 1;
        req.updated_at = now.clone();
        events.push(InvoicingEvent::EInvoiceIssued {
            request: req.clone(),
            invoice: invoice.clone(),
            at: now,
        });
        Ok((req, Some(invoice), events))
    }
}

impl RedFlushView {
    pub fn create_and_complete(
        original: &EInvoice,
        post_sales_case_id: String,
        refund_fact_event_id: String,
        now: String,
    ) -> (Self, EInvoice, Vec<InvoicingEvent>) {
        let refund_scope_hash = sha256_prefixed(&format!(
            "{}|{}|{}",
            original.order_id, post_sales_case_id, refund_fact_event_id
        ));
        let id = folded_id(
            "irf",
            &format!(
                "{}|{}|{}|{}",
                original.e_invoice_id, original.order_id, post_sales_case_id, refund_fact_event_id
            ),
        );
        let mut rf = Self {
            red_flush_id: id.clone(),
            original_invoice_id: original.e_invoice_id.clone(),
            post_sales_case_id,
            order_id: original.order_id.clone(),
            refund_fact_event_id: Some(refund_fact_event_id),
            red_invoice_id: None,
            red_invoice_number: None,
            status: RedFlushStatus::Requested,
            refund_red_flush_observation_status: ObservationStatus::ViolationObserved,
            refund_release_flag_status: ReleaseFlagStatus::ViolationObserved,
            created_at: now.clone(),
            updated_at: now.clone(),
            version: 1,
        };
        let mut events = vec![
            InvoicingEvent::RefundWithoutRedFlushObserved {
                red_flush: rf.clone(),
                original_invoice_ids: vec![original.e_invoice_id.clone()],
                refund_scope_hash: refund_scope_hash.clone(),
                at: now.clone(),
            },
            InvoicingEvent::RedFlushRequested {
                red_flush: rf.clone(),
                original_invoice_number: original.invoice_number.clone().unwrap_or_default(),
                refund_scope_hash,
                at: now.clone(),
            },
        ];
        rf.status = RedFlushStatus::Submitted;
        rf.version += 1;
        rf.updated_at = now.clone();
        let gateway_request_id = folded_id(
            "igr",
            &format!("red|{}|{}", rf.red_flush_id, original.e_invoice_id),
        );
        events.push(InvoicingEvent::RedFlushSubmitted {
            red_flush: rf.clone(),
            gateway_request_id: gateway_request_id.clone(),
            request_fingerprint: sha256_prefixed(&rf.red_flush_id),
            at: now.clone(),
        });
        rf.status = RedFlushStatus::Accepted;
        rf.version += 1;
        rf.updated_at = now.clone();
        events.push(InvoicingEvent::RedFlushAccepted {
            red_flush: rf.clone(),
            gateway_request_id: gateway_request_id.clone(),
            at: now.clone(),
        });
        let red_id = format!("ein-{}", uuid::Uuid::now_v7());
        let red_number = format!(
            "RED{}",
            sha256_hex(&rf.red_flush_id)[..16].to_ascii_uppercase()
        );
        let red_invoice = EInvoice {
            e_invoice_id: red_id.clone(),
            invoice_request_id: original.invoice_request_id.clone(),
            order_id: original.order_id.clone(),
            invoice_type: InvoiceType::Red,
            invoice_number: Some(red_number.clone()),
            gateway_request_id,
            gateway_profile: original.gateway_profile.clone(),
            gateway_status: Some("ACCEPTED".into()),
            download_ref: Some(format!("artifact://invoicing/{red_id}")),
            total_amount: Money {
                currency: original.total_amount.currency.clone(),
                minor_units: original.total_amount.minor_units.abs(),
            },
            issued_at: Some(now.clone()),
            status: InvoiceRequestStatus::RedFlushed,
            created_at: now.clone(),
            updated_at: now.clone(),
        };
        rf.status = RedFlushStatus::Completed;
        rf.red_invoice_id = Some(red_id);
        rf.red_invoice_number = Some(red_number);
        rf.version += 1;
        rf.updated_at = now.clone();
        events.push(InvoicingEvent::RedFlushCompleted {
            red_flush: rf.clone(),
            original_invoice_number: original.invoice_number.clone().unwrap_or_default(),
            red_invoice: red_invoice.clone(),
            at: now,
        });
        (rf, red_invoice, events)
    }
}

#[derive(Debug, Clone)]
pub enum InvoicingEvent {
    InvoiceTitleCreated {
        title: InvoiceTitle,
        at: String,
    },
    InvoiceTitleUpdated {
        title: InvoiceTitle,
        previous_version: i64,
        at: String,
    },
    DefaultInvoiceTitleSet {
        account_id: String,
        title_id: String,
        previous_default_title_id: Option<String>,
        at: String,
    },
    InvoiceTitleDeactivated {
        title: InvoiceTitle,
        reason: String,
        at: String,
    },
    InvoiceRequested {
        request: EInvoiceRequest,
        at: String,
    },
    InvoiceAmountBasisAttached {
        request: EInvoiceRequest,
        at: String,
    },
    EInvoiceSubmitted {
        request: EInvoiceRequest,
        gateway_request_id: String,
        request_fingerprint: String,
        at: String,
    },
    EInvoiceAccepted {
        request: EInvoiceRequest,
        gateway_request_id: String,
        at: String,
    },
    EInvoiceRejected {
        request: EInvoiceRequest,
        gateway_request_id: String,
        at: String,
    },
    EInvoiceFailed {
        request: EInvoiceRequest,
        at: String,
    },
    EInvoiceIssued {
        request: EInvoiceRequest,
        invoice: EInvoice,
        at: String,
    },
    RefundWithoutRedFlushObserved {
        red_flush: RedFlushView,
        original_invoice_ids: Vec<String>,
        refund_scope_hash: String,
        at: String,
    },
    RedFlushRequested {
        red_flush: RedFlushView,
        original_invoice_number: String,
        refund_scope_hash: String,
        at: String,
    },
    RedFlushSubmitted {
        red_flush: RedFlushView,
        gateway_request_id: String,
        request_fingerprint: String,
        at: String,
    },
    RedFlushAccepted {
        red_flush: RedFlushView,
        gateway_request_id: String,
        at: String,
    },
    RedFlushCompleted {
        red_flush: RedFlushView,
        original_invoice_number: String,
        red_invoice: EInvoice,
        at: String,
    },
    SagaInvoiceGenerated {
        invoice: SagaInvoice,
        at: String,
    },
}

impl InvoicingEvent {
    pub fn envelope(
        &self,
        corr: String,
        cause: Option<String>,
    ) -> rust_kit::messaging::EventEnvelope {
        let (typ, agg, version, at, payload, gateway_hash, obs) = match self {
            Self::InvoiceTitleCreated { title, at } => (
                "InvoiceTitleCreated",
                title.title_id.clone(),
                title.version,
                at.clone(),
                json!({"titleId":title.title_id,"accountId":title.account_id,"titleType":title.title_type,"titleName":title.title_name,"taxIdentityMasked":title.tax_identity_masked,"taxIdentityHash":title.tax_identity_hash,"isDefault":title.is_default,"version":title.version,"status":title.status,"createdAt":at}),
                None,
                None,
            ),
            Self::InvoiceTitleUpdated {
                title,
                previous_version,
                at,
            } => (
                "InvoiceTitleUpdated",
                title.title_id.clone(),
                title.version,
                at.clone(),
                json!({"titleId":title.title_id,"accountId":title.account_id,"titleType":title.title_type,"titleName":title.title_name,"taxIdentityMasked":title.tax_identity_masked,"taxIdentityHash":title.tax_identity_hash,"previousVersion":previous_version,"version":title.version,"updatedAt":at}),
                None,
                None,
            ),
            Self::DefaultInvoiceTitleSet {
                account_id,
                title_id,
                previous_default_title_id,
                at,
            } => (
                "DefaultInvoiceTitleSet",
                title_id.clone(),
                1,
                at.clone(),
                json!({"accountId":account_id,"titleId":title_id,"previousDefaultTitleId":previous_default_title_id,"setAt":at}),
                None,
                None,
            ),
            Self::InvoiceTitleDeactivated { title, reason, at } => (
                "InvoiceTitleDeactivated",
                title.title_id.clone(),
                title.version,
                at.clone(),
                json!({"titleId":title.title_id,"accountId":title.account_id,"version":title.version,"reason":reason,"status":"DEACTIVATED","deactivatedAt":at}),
                None,
                None,
            ),
            Self::InvoiceRequested { request, at } => (
                "InvoiceRequested",
                request.invoice_request_id.clone(),
                request.version,
                at.clone(),
                json!({"invoiceRequestId":request.invoice_request_id,"accountId":request.account_id,"orderId":request.order_id,"titleId":request.title_id,"titleVersion":request.title_version,"invoiceScope":request.invoice_scope,"amountBasis":request.amount_basis,"clientRequestId":request.client_request_id,"status":request.status,"requestedAt":at}),
                None,
                None,
            ),
            Self::InvoiceAmountBasisAttached { request, at } => (
                "InvoiceAmountBasisAttached",
                request.invoice_request_id.clone(),
                request.version + 1,
                at.clone(),
                json!({"invoiceRequestId":request.invoice_request_id,"orderId":request.order_id,"amountBasis":request.amount_basis,"status":"AMOUNT_READY","attachedAt":at}),
                None,
                None,
            ),
            Self::EInvoiceSubmitted {
                request,
                gateway_request_id,
                request_fingerprint,
                at,
            } => (
                "EInvoiceSubmitted",
                request.invoice_request_id.clone(),
                request.version,
                at.clone(),
                json!({"invoiceRequestId":request.invoice_request_id,"orderId":request.order_id,"gatewayProfile":request.gateway_profile,"gatewayRequestId":gateway_request_id,"submitAttemptNo":1,"requestFingerprint":request_fingerprint,"submittedAt":at,"status":"SUBMITTED"}),
                None,
                None,
            ),
            Self::EInvoiceAccepted {
                request,
                gateway_request_id,
                at,
            } => (
                "EInvoiceAccepted",
                request.invoice_request_id.clone(),
                request.version,
                at.clone(),
                json!({"invoiceRequestId":request.invoice_request_id,"orderId":request.order_id,"simGatewayResult":{"gatewayProfile":request.gateway_profile,"gatewayRequestId":gateway_request_id,"simSeedRef":request.sim_seed_ref,"gatewayStatus":"ACCEPTED","gatewayAcceptedAt":at},"acceptedAt":at,"status":"ACCEPTED"}),
                Some((gateway_request_id.clone(), sha256_prefixed("ACCEPTED"))),
                None,
            ),
            Self::EInvoiceRejected {
                request,
                gateway_request_id,
                at,
            } => (
                "EInvoiceRejected",
                request.invoice_request_id.clone(),
                request.version,
                at.clone(),
                json!({"invoiceRequestId":request.invoice_request_id,"orderId":request.order_id,"rejectionCode":request.rejection_code,"rejectionReason":"Deterministic SIM rejection","simGatewayResult":{"gatewayProfile":request.gateway_profile,"gatewayRequestId":gateway_request_id,"simSeedRef":request.sim_seed_ref,"gatewayStatus":"REJECTED","rejectionCode":request.rejection_code},"rejectedAt":at,"status":"REJECTED"}),
                Some((gateway_request_id.clone(), sha256_prefixed("REJECTED"))),
                None,
            ),
            Self::EInvoiceFailed { request, at } => (
                "EInvoiceFailed",
                request.invoice_request_id.clone(),
                request.version,
                at.clone(),
                json!({"invoiceRequestId":request.invoice_request_id,"orderId":request.order_id,"failureClass":request.failure_class,"attemptNo":1,"retryable":false,"failedAt":at,"status":"FAILED"}),
                None,
                None,
            ),
            Self::EInvoiceIssued {
                request,
                invoice,
                at,
            } => (
                "EInvoiceIssued",
                request.invoice_request_id.clone(),
                request.version,
                at.clone(),
                json!({"eInvoiceId":invoice.e_invoice_id,"invoiceRequestId":request.invoice_request_id,"orderId":request.order_id,"invoiceType":"BLUE","invoiceNumber":invoice.invoice_number,"gatewayProfile":invoice.gateway_profile,"gatewayRequestId":invoice.gateway_request_id,"downloadRef":invoice.download_ref,"totalAmount":invoice.total_amount,"amountBasisHash":request.amount_basis.amount_basis_hash,"issuedAt":at,"status":"ISSUED"}),
                None,
                None,
            ),
            Self::RefundWithoutRedFlushObserved {
                red_flush,
                original_invoice_ids,
                refund_scope_hash,
                at,
            } => (
                "RefundWithoutRedFlushObserved",
                red_flush.red_flush_id.clone(),
                red_flush.version,
                at.clone(),
                json!({"observationId":red_flush.red_flush_id,"postSalesCaseId":red_flush.post_sales_case_id,"orderId":red_flush.order_id,"refundFactEventId":red_flush.refund_fact_event_id,"originalInvoiceIds":original_invoice_ids,"refundScopeHash":refund_scope_hash,"refundReleaseFlagStatus":"SUSPENDED","observationStatus":"VIOLATION_OBSERVED","ruling":"OBSERVE_ONLY_NO_POST_SALES_BLOCKER_WAVE_A","observedAt":at}),
                None,
                Some((
                    red_flush.post_sales_case_id.clone(),
                    red_flush.refund_fact_event_id.clone().unwrap_or_default(),
                    red_flush.version,
                )),
            ),
            Self::RedFlushRequested {
                red_flush,
                original_invoice_number,
                refund_scope_hash,
                at,
            } => (
                "RedFlushRequested",
                red_flush.red_flush_id.clone(),
                red_flush.version,
                at.clone(),
                json!({"redFlushId":red_flush.red_flush_id,"originalInvoiceId":red_flush.original_invoice_id,"originalInvoiceNumber":original_invoice_number,"postSalesCaseId":red_flush.post_sales_case_id,"orderId":red_flush.order_id,"refundScopeHash":refund_scope_hash,"refundFactEventId":red_flush.refund_fact_event_id,"status":"REQUESTED","requestedAt":at}),
                None,
                None,
            ),
            Self::RedFlushSubmitted {
                red_flush,
                gateway_request_id,
                request_fingerprint,
                at,
            } => (
                "RedFlushSubmitted",
                red_flush.red_flush_id.clone(),
                red_flush.version,
                at.clone(),
                json!({"redFlushId":red_flush.red_flush_id,"originalInvoiceId":red_flush.original_invoice_id,"postSalesCaseId":red_flush.post_sales_case_id,"gatewayProfile":"SIM_TAX_BUREAU_CN_V1","gatewayRequestId":gateway_request_id,"submitAttemptNo":1,"requestFingerprint":request_fingerprint,"submittedAt":at,"status":"SUBMITTED"}),
                None,
                None,
            ),
            Self::RedFlushAccepted {
                red_flush,
                gateway_request_id,
                at,
            } => (
                "RedFlushAccepted",
                red_flush.red_flush_id.clone(),
                red_flush.version,
                at.clone(),
                json!({"redFlushId":red_flush.red_flush_id,"originalInvoiceId":red_flush.original_invoice_id,"postSalesCaseId":red_flush.post_sales_case_id,"simGatewayResult":{"gatewayProfile":"SIM_TAX_BUREAU_CN_V1","gatewayRequestId":gateway_request_id,"gatewayStatus":"ACCEPTED","gatewayAcceptedAt":at},"acceptedAt":at,"status":"ACCEPTED"}),
                None,
                None,
            ),
            Self::RedFlushCompleted {
                red_flush,
                original_invoice_number,
                red_invoice,
                at,
            } => (
                "RedFlushCompleted",
                red_flush.red_flush_id.clone(),
                red_flush.version,
                at.clone(),
                json!({"redFlushId":red_flush.red_flush_id,"originalInvoiceId":red_flush.original_invoice_id,"originalInvoiceNumber":original_invoice_number,"redInvoiceId":red_invoice.e_invoice_id,"redInvoiceNumber":red_invoice.invoice_number,"postSalesCaseId":red_flush.post_sales_case_id,"orderId":red_flush.order_id,"totalAmount":red_invoice.total_amount,"completedAt":at,"status":"COMPLETED"}),
                None,
                None,
            ),
            Self::SagaInvoiceGenerated { invoice, at } => (
                "InvoiceGenerated",
                invoice.invoice_id.clone(),
                1,
                at.clone(),
                json!({"sagaId":invoice.saga_id,"journeyOrderId":invoice.journey_order_id,"invoiceId":invoice.invoice_id,"invoiceNumber":invoice.invoice_number,"paymentRef":invoice.payment_ref,"issuedAt":invoice.issued_at}),
                None,
                None,
            ),
        };
        let mut env =
            rust_kit::messaging::EventEnvelope::new(typ, at, corr, cause, "invoicing", payload);
        env.event_id = if let Some((gw, hash)) = gateway_hash {
            deterministic_gateway_event_id(typ, &gw, &hash)
        } else if let Some((case, fact, ver)) = obs {
            deterministic_observation_event_id(typ, &case, &fact, ver)
        } else {
            deterministic_event_id(typ, &agg, version)
        };
        env
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InvoicingError {
    ValidationFailed(String),
    NotFound(String),
    Conflict(String),
    IdempotencyKeyReused(String),
    PreconditionFailed(String),
    DomainRuleViolation(String),
    Unavailable(String),
    Internal(String),
}
impl InvoicingError {
    pub fn code(&self) -> &'static str {
        match self {
            Self::ValidationFailed(_) => "VALIDATION_FAILED",
            Self::NotFound(_) => "NOT_FOUND",
            Self::Conflict(_) => "CONFLICT",
            Self::IdempotencyKeyReused(_) => "IDEMPOTENCY_KEY_REUSED",
            Self::PreconditionFailed(_) => "PRECONDITION_FAILED",
            Self::DomainRuleViolation(_) => "DOMAIN_RULE_VIOLATION",
            Self::Unavailable(_) => "UNAVAILABLE",
            Self::Internal(_) => "INTERNAL",
        }
    }
    pub fn message(&self) -> &str {
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
impl fmt::Display for InvoicingError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.code(), self.message())
    }
}
impl std::error::Error for InvoicingError {}
