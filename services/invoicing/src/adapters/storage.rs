use crate::*;
use serde_json::Value;
use std::sync::Arc;

/// Invoice commands use transactional state, while upstream projections are stored per entity.
pub struct PostgresInvoicingService {
    storage: rust_kit::storage::Storage,
}

const STREAM: &str = "events:invoicing";

fn internal(e: impl std::fmt::Display) -> InvoicingError {
    InvoicingError::Internal(e.to_string())
}

impl PostgresInvoicingService {
    pub async fn rebuild_amount_basis(&self, order: &str, correlation: &str) -> Result<AmountBasis, InvoicingError> {
        let mut tx = self.pool().begin().await.map_err(internal)?;
        for kind in ["amounts","orders"] {
            sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1,0))")
                .bind(format!("{kind}:{order}")).execute(&mut *tx).await.map_err(internal)?;
        }
        let template = std::env::var("INVOICE_REFERENCE_URL_TEMPLATE").unwrap_or_else(|_| "http://%s:8080".into());
        let client = reqwest::Client::builder().timeout(std::time::Duration::from_secs(5)).build().map_err(internal)?;
        let financial: Value = client.get(format!("{}/api/v1/order-revenue-bases/{order}",template.replace("%s","finance-settlement")))
            .header("X-Correlation-Id",correlation).send().await.map_err(internal)?.error_for_status().map_err(internal)?.json().await.map_err(internal)?;
        let observed_at = chrono::Utc::now();
        let current: Value = client.get(format!("{}/api/v1/journey-orders/{order}",template.replace("%s","journey-order")))
            .header("X-Correlation-Id",correlation).send().await.map_err(internal)?.error_for_status().map_err(internal)?.json().await.map_err(internal)?;
        if financial["orderId"] != order || current["orderId"] != order { return Err(InvoicingError::DomainRuleViolation("rebuild order reference mismatch".into())); }
        let total: Money = serde_json::from_value(financial["totalAmount"].clone()).map_err(internal)?;
        let expected: Money = serde_json::from_value(current["monetarySummary"]["total"].clone()).map_err(internal)?;
        if total != expected { return Err(InvoicingError::Unavailable("financial net revenue does not yet match order total".into())); }
        let mut history = crate::application::AmountHistory::default();
        let mut net = 0i64;
        for item in financial["items"].as_array().ok_or_else(|| internal("revenue items missing"))? {
            let id = item["revenueRecognitionId"].as_str().ok_or_else(|| internal("recognition identity missing"))?;
            let amount: Money = serde_json::from_value(item["amount"].clone()).map_err(internal)?;
            let returned: Money = serde_json::from_value(item["reversedAmount"].clone()).map_err(internal)?;
            if amount.currency != total.currency || returned.currency != total.currency || returned.minor_units < 0 || returned.minor_units > amount.minor_units {
                return Err(InvoicingError::DomainRuleViolation("invalid revenue component currency or reversal".into()));
            }
            net = net.checked_add(amount.minor_units).and_then(|value| value.checked_sub(returned.minor_units)).ok_or_else(|| internal("revenue overflow"))?;
            if history.recognitions.insert(id.into(),amount).is_some() { return Err(internal("duplicate recognition identity")); }
            let reversals: std::collections::BTreeMap<String, Money> = serde_json::from_value(item["reversals"].clone()).map_err(internal)?;
            let mut reversed_total = 0i64;
            for (reference, amount) in reversals {
                if amount.currency != total.currency || amount.minor_units <= 0 { return Err(internal("invalid reversal details")); }
                reversed_total = reversed_total.checked_add(amount.minor_units).ok_or_else(|| internal("reversal overflow"))?;
                history.reversals.insert(format!("{id}:{reference}"),amount);
            }
            if reversed_total != returned.minor_units { return Err(InvoicingError::Unavailable("reversal details require financial reconciliation".into())); }
        }
        if net != total.minor_units { return Err(internal("financial component net differs from total")); }
        let ids: Vec<String> = history.recognitions.keys().cloned().collect();
        let basis = AmountBasis { basis_type:"REVENUE_RECOGNITION".into(), revenue_recognition_ids:ids.clone(), finance_invoice_id:None,
            tax_lines:vec![TaxLine { tax_code:"VAT_SIM".into(),tax_rate_basis_points:0,taxable_amount:total.clone(),tax_amount:Money {currency:total.currency.clone(),minor_units:0} }],
            total_amount:total.clone(),amount_basis_hash:crate::utils::sha256_prefixed(&crate::utils::normalize_json(&json!({"revenueRecognitionIds":ids,"totalAmount":total,"reversals":history.reversals}))) };
        let projection = crate::application::OrderProjection { account_id: current["accountId"].as_str().ok_or_else(|| internal("order account missing"))?.into(),
            traveler_refs:serde_json::from_value(current["travelerRefs"].clone()).map_err(internal)?,segment_refs:serde_json::from_value(current["segmentRefs"].clone()).map_err(internal)?,expected_total:Some(expected),updated_at:Some(observed_at) };
        for (kind,data) in [("amounts",serde_json::to_value(&basis).map_err(internal)?),("amount_history",serde_json::to_value(history).map_err(internal)?),("orders",serde_json::to_value(projection).map_err(internal)?)] {
            sqlx::query("INSERT INTO invoicing_projection_rows(kind,id,data) VALUES($1,$2,$3) ON CONFLICT(kind,id) DO UPDATE SET data=EXCLUDED.data")
                .bind(kind).bind(order).bind(data).execute(&mut *tx).await.map_err(internal)?;
        }
        tx.commit().await.map_err(internal)?;
        Ok(basis)
    }

    pub async fn from_env() -> Result<Self, InvoicingError> {
        let storage = rust_kit::storage::Storage::from_env()
            .await
            .map_err(|e| internal(e))?;
        let dir = std::env::var("MIGRATIONS_DIR").unwrap_or_else(|_| "/app/migrations".into());
        match storage.migrate_dir(&dir).await {
            Ok(()) => {}
            Err(e) => {
                if dir == "/app/migrations" {
                    storage
                        .migrate_dir("services/invoicing/migrations")
                        .await
                        .map_err(|e| internal(e))?
                } else {
                    return Err(internal(e));
                }
            }
        }
        Ok(Self { storage })
    }

    pub fn pool(&self) -> &sqlx::PgPool {
        self.storage.pool()
    }

    pub async fn is_ready(&self) -> bool {
        self.storage.is_ready().await
    }

    fn engine() -> (InMemoryInvoicingService, Arc<InMemoryEventPublisher>) {
        let publisher = Arc::new(InMemoryEventPublisher::default());
        (InMemoryInvoicingService::new(publisher.clone()), publisher)
    }

    async fn hydrate_locked(
        tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        account: &str,
        key: &str,
    ) -> Result<(InMemoryInvoicingService, Arc<InMemoryEventPublisher>), InvoicingError> {
        for lock in [format!("invoice-key:{key}"), format!("invoice-account:{account}")] {
            sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1,0))")
                .bind(lock).execute(&mut **tx).await.map_err(internal)?;
        }
        let rows: Vec<(String,String,Value)> = sqlx::query_as(
            "SELECT kind,id,data FROM invoicing_command_rows WHERE (account_id=$1 AND kind <> 'idempotency') OR (kind='idempotency' AND id=$2)",
        ).bind(account).bind(key).fetch_all(&mut **tx).await.map_err(internal)?;
        let (svc, publisher) = Self::engine();
        svc.import_snapshot(Self::snapshot_from_rows(rows)?);
        Ok((svc, publisher))
    }

    fn snapshot_from_rows(rows: Vec<(String,String,Value)>) -> Result<InvoicingStateSnapshot, InvoicingError> {
        let mut snapshot = serde_json::to_value(InvoicingStateSnapshot::default()).map_err(internal)?;
        for (kind,id,data) in rows {
            snapshot.get_mut(&kind).and_then(Value::as_object_mut)
                .ok_or_else(|| InvoicingError::Internal(format!("unknown command row kind {kind}")))?.insert(id,data);
        }
        serde_json::from_value(snapshot).map_err(internal)
    }

    async fn account_for(&self, kind: &str, id: &str) -> Result<String, InvoicingError> {
        let row: Option<(String,)> = sqlx::query_as("SELECT account_id FROM invoicing_command_rows WHERE kind=$1 AND id=$2")
            .bind(kind).bind(id).fetch_optional(self.pool()).await.map_err(internal)?;
        row.map(|row| row.0).ok_or_else(|| InvoicingError::NotFound(format!("{kind} {id} not found")))
    }

    async fn hydrate_read(&self, account: Option<&str>, order: Option<&str>, kind: Option<&str>, id: Option<&str>) -> Result<InMemoryInvoicingService, InvoicingError> {
        let rows: Vec<(String,String,Value)> = sqlx::query_as(
            "SELECT kind,id,data FROM invoicing_command_rows WHERE kind <> 'idempotency' AND ($1::text IS NULL OR account_id=$1) AND ($2::text IS NULL OR order_id=$2) AND ($3::text IS NULL OR kind=$3) AND ($4::text IS NULL OR id=$4)",
        ).bind(account).bind(order).bind(kind).bind(id).fetch_all(self.pool()).await.map_err(internal)?;
        let (svc, _publisher) = Self::engine();
        svc.import_snapshot(Self::snapshot_from_rows(rows)?);
        Ok(svc)
    }

    async fn commit_state(
        mut tx: sqlx::Transaction<'_, sqlx::Postgres>,
        svc: &InMemoryInvoicingService,
        publisher: &InMemoryEventPublisher,
        account: &str,
    ) -> Result<(), InvoicingError> {
        let mut snapshot = svc.snapshot();
        snapshot.orders.clear();
        snapshot.amounts.clear();
        snapshot.saga_invoices.clear();
        let data = serde_json::to_value(snapshot).map_err(internal)?;
        for kind in ["titles","requests","invoices","red_flushes","idempotency","refund_observations"] {
            for (id, entity) in data[kind].as_object().ok_or_else(|| InvoicingError::Internal("invalid command snapshot".into()))? {
                sqlx::query("INSERT INTO invoicing_command_rows(kind,id,account_id,order_id,data) VALUES($1,$2,$3,$4,$5) ON CONFLICT(kind,id) DO UPDATE SET data=EXCLUDED.data,order_id=EXCLUDED.order_id WHERE invoicing_command_rows.data IS DISTINCT FROM EXCLUDED.data")
                    .bind(kind).bind(id).bind(account).bind(entity.get("orderId").or_else(||entity.get("order_id")).and_then(Value::as_str))
                    .bind(entity).execute(&mut *tx).await.map_err(internal)?;
            }
        }
        for envelope in publisher.drain() {
            let body = serde_json::to_value(&envelope).map_err(internal)?;
            sqlx::query(
                "INSERT INTO outbox (event_id, stream, envelope) VALUES ($1, $2, $3) ON CONFLICT (event_id) DO NOTHING",
            )
            .bind(&envelope.event_id)
            .bind(STREAM)
            .bind(body)
            .execute(&mut *tx)
            .await
            .map_err(internal)?;
        }
        tx.commit().await.map_err(internal)?;
        Ok(())
    }

    async fn mutate<T, F, Fut>(&self, account: String, key: String, f: F) -> Result<T, InvoicingError>
    where
        F: FnOnce(InMemoryInvoicingService) -> Fut,
        Fut: std::future::Future<Output = (InMemoryInvoicingService, Result<T, InvoicingError>)>,
    {
        let mut tx = self.pool().begin().await.map_err(internal)?;
        let (svc, publisher) = Self::hydrate_locked(&mut tx, &account, &key).await?;
        let (svc, out) = f(svc).await;
        match out {
            Ok(value) => {
                Self::commit_state(tx, &svc, &publisher, &account).await?;
                Ok(value)
            }
            Err(error) => Err(error), // tx dropped -> rollback, nothing published
        }
    }

    async fn load_order_context(
        &self, svc: &InMemoryInvoicingService, order_id: &str,
    ) -> Result<(), InvoicingError> {
        let rows: Vec<(String, Value)> = sqlx::query_as(
            "SELECT kind, data FROM invoicing_projection_rows WHERE id = $1 AND kind IN ('orders', 'amounts')",
        ).bind(order_id).fetch_all(self.pool()).await.map_err(internal)?;
        let mut snapshot = svc.snapshot();
        for (kind, data) in rows {
            if kind == "orders" { snapshot.orders.insert(order_id.into(), serde_json::from_value(data).map_err(internal)?); }
            else { snapshot.amounts.insert(order_id.into(), serde_json::from_value(data).map_err(internal)?); }
        }
        svc.import_snapshot(snapshot);
        Ok(())
    }

    async fn consume_projection(
        &self, envelope: rust_kit::messaging::EventEnvelope, stream: &str, kind: &str, id: &str,
    ) -> Result<(), InvoicingError> {
        let mut tx = self.pool().begin().await.map_err(internal)?;
        let claimed = sqlx::query("INSERT INTO processed_events (event_id, stream) VALUES ($1, $2) ON CONFLICT DO NOTHING")
            .bind(&envelope.event_id).bind(stream).execute(&mut *tx).await.map_err(internal)?;
        if claimed.rows_affected() == 0 { return Ok(()); }
        sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
            .bind(format!("{kind}:{id}")).execute(&mut *tx).await.map_err(internal)?;
        let existing: Option<(Value,)> = sqlx::query_as("SELECT data FROM invoicing_projection_rows WHERE kind = $1 AND id = $2")
            .bind(kind).bind(id).fetch_optional(&mut *tx).await.map_err(internal)?;
        let mut snapshot = InvoicingStateSnapshot::default();
        if let Some((data,)) = existing {
            match kind {
                "orders" => { snapshot.orders.insert(id.into(), serde_json::from_value(data).map_err(internal)?); }
                "amounts" => { snapshot.amounts.insert(id.into(), serde_json::from_value(data).map_err(internal)?); }
                "saga_invoices" => { tx.commit().await.map_err(internal)?; return Ok(()); }
                _ => unreachable!(),
            }
        }
        if kind == "amounts" {
            let history: Option<(Value,)> = sqlx::query_as("SELECT data FROM invoicing_projection_rows WHERE kind='amount_history' AND id=$1")
                .bind(id).fetch_optional(&mut *tx).await.map_err(internal)?;
            if let Some((data,)) = history { snapshot.amount_history.insert(id.into(),serde_json::from_value(data).map_err(internal)?); }
        }
        let (svc, publisher) = Self::engine();
        svc.import_snapshot(snapshot);
        svc.apply_subscribed_event(envelope).await?;
        let snapshot = svc.snapshot();
        let data = match kind {
            "orders" => snapshot.orders.get(id).map(serde_json::to_value).transpose().map_err(internal)?,
            "amounts" => snapshot.amounts.get(id).map(serde_json::to_value).transpose().map_err(internal)?,
            "saga_invoices" => snapshot.saga_invoices.values().next().map(serde_json::to_value).transpose().map_err(internal)?,
            _ => unreachable!(),
        };
        if let Some(data) = data {
            sqlx::query("INSERT INTO invoicing_projection_rows (kind,id,data) VALUES ($1,$2,$3) ON CONFLICT (kind,id) DO UPDATE SET data=EXCLUDED.data")
                .bind(kind).bind(id).bind(data).execute(&mut *tx).await.map_err(internal)?;
        }
        if let Some(history) = snapshot.amount_history.get(id) {
            sqlx::query("INSERT INTO invoicing_projection_rows(kind,id,data) VALUES ('amount_history',$1,$2) ON CONFLICT(kind,id) DO UPDATE SET data=EXCLUDED.data")
                .bind(id).bind(serde_json::to_value(history).map_err(internal)?).execute(&mut *tx).await.map_err(internal)?;
        }
        for envelope in publisher.drain() {
            sqlx::query("INSERT INTO outbox (event_id,stream,envelope) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING")
                .bind(&envelope.event_id).bind(STREAM).bind(serde_json::to_value(&envelope).map_err(internal)?)
                .execute(&mut *tx).await.map_err(internal)?;
        }
        tx.commit().await.map_err(internal)
    }

    pub async fn handle_subscribed_event(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), rust_kit::messaging::HandlerError> {
        if !Self::handles_event(&envelope) {
            return Ok(());
        }
        let stream = format!("events:{}", envelope.producer);
        self.consume_in_tx(envelope, &stream)
            .await
            .map_err(crate::utils::handler_error)
    }

    pub fn handles_event(envelope: &rust_kit::messaging::EventEnvelope) -> bool {
        matches!(
            envelope.event_type.as_str(),
            "JourneyOrderCreated"
                | "JourneyOrderConfirmed"
                | "JourneyOrderPostSalesAdjusted"
                | "RevenueRecognized"
                | "RevenueRecognitionReversed"
                | "InvoiceGenerated"
                | "PostSalesApplied"
                | "RefundSettled"
                | "PostSalesFailed"
                | "RequestEInvoice"
                | "IssueRedFlush"
        ) || (envelope.event_type == "InvoiceRequested"
            && envelope.producer == "booking-orchestration")
    }

    async fn consume_in_tx(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
        stream: &str,
    ) -> Result<(), InvoicingError> {
        if matches!(envelope.event_type.as_str(),"PostSalesApplied"|"RefundSettled") {
            return self.reconcile_settled_refunds(envelope).await;
        }
        let projection = match envelope.event_type.as_str() {
            "JourneyOrderCreated" | "JourneyOrderConfirmed" | "JourneyOrderPostSalesAdjusted" => Some(("orders", "orderId")),
            "RevenueRecognized" | "RevenueRecognitionReversed" | "InvoiceGenerated" => Some(("amounts", "orderId")),
            "InvoiceRequested" if envelope.producer == "booking-orchestration" => Some(("saga_invoices", "sagaId")),
            _ => None,
        };
        if let Some((kind, field)) = projection {
            if let Some(id) = envelope.payload.get(field).and_then(Value::as_str).map(str::to_owned) {
                return self.consume_projection(envelope, stream, kind, &id).await;
            }
        }
        let mut tx = self.pool().begin().await.map_err(internal)?;
        let claimed = sqlx::query(
            "INSERT INTO processed_events (event_id, stream) VALUES ($1, $2) ON CONFLICT DO NOTHING",
        )
        .bind(&envelope.event_id)
        .bind(stream)
        .execute(&mut *tx)
        .await
        .map_err(internal)?;
        if claimed.rows_affected() == 0 {
            return Ok(());
        }
        let account = envelope.payload.get("accountId").and_then(Value::as_str).unwrap_or_default().to_string();
        let (svc, publisher) = Self::hydrate_locked(&mut tx, &account, &envelope.event_id).await?;
        svc.apply_subscribed_event(envelope).await?;
        Self::commit_state(tx, &svc, &publisher, &account).await
    }

    async fn reference(&self,service:&str,path:&str,correlation:&str) -> Result<Value,InvoicingError> {
        let template=std::env::var("INVOICE_REFERENCE_URL_TEMPLATE").unwrap_or_else(|_|"http://%s:8080".into());
        reqwest::Client::builder().timeout(std::time::Duration::from_secs(5)).build().map_err(internal)?
            .get(format!("{}{path}",template.replace("%s",service))).header("X-Correlation-Id",correlation)
            .send().await.map_err(internal)?.error_for_status().map_err(internal)?.json().await.map_err(internal)
    }

    async fn reconcile_settled_refunds(&self,envelope:rust_kit::messaging::EventEnvelope) -> Result<(),InvoicingError> {
        let case_id=if envelope.event_type=="RefundSettled" {
            let id=envelope.payload["refundId"].as_str().ok_or_else(||internal("refund identity missing"))?;
            let refund=self.reference("payment",&format!("/api/v1/refunds/{id}"),&envelope.correlation_id).await?;
            refund["businessCaseRef"].as_str().ok_or_else(||internal("refund case missing"))?.to_string()
        } else { envelope.payload["caseId"].as_str().ok_or_else(||internal("case identity missing"))?.to_string() };
        if !case_id.starts_with("psc-") { return Ok(()); }
        let case=self.reference("post-sales",&format!("/api/v1/post-sales-cases/{case_id}"),&envelope.correlation_id).await?;
        if case["caseId"]!=case_id { return Err(internal("refund case identity mismatch")); }
        let order=case["journeyOrderId"].as_str().ok_or_else(||internal("refund order missing"))?;
        let requested_at=case["createdAt"].as_str().ok_or_else(||internal("refund request time missing"))?;
        let actual_order=self.reference("journey-order",&format!("/api/v1/journey-orders/{order}"),&envelope.correlation_id).await?;
        if actual_order["orderId"]!=order { return Err(internal("refund order identity mismatch")); }
        let account=actual_order["accountId"].as_str().ok_or_else(||internal("order account missing"))?;
        let refunds=self.reference("payment",&format!("/api/v1/refunds?businessCaseRef={case_id}"),&envelope.correlation_id).await?;
        let items=refunds["items"].as_array().ok_or_else(||internal("refund list missing"))?;
        if refunds["total"].as_u64()!=Some(items.len() as u64) { return Err(internal("refund list incomplete")); }
        let mut tx=self.pool().begin().await.map_err(internal)?;
        let (svc,publisher)=Self::hydrate_locked(&mut tx,account,&envelope.event_id).await?;
        for refund in items.iter().filter(|item|item["status"]=="SETTLED") {
            if refund["businessCaseRef"]!=case_id { return Err(internal("settled refund case mismatch")); }
            let id=refund["refundId"].as_str().ok_or_else(||internal("refund identity missing"))?;
            if !svc.snapshot().refund_observations.contains_key(id) {
                let payment_id=refund["paymentIntentId"].as_str().ok_or_else(||internal("refund payment identity missing"))?;
                let payment=self.reference("payment",&format!("/api/v1/payment-intents/{payment_id}"),&envelope.correlation_id).await?;
                if payment["paymentIntentId"]!=payment_id || payment["payerRef"]!=account || payment["status"]!="CAPTURED" { return Err(internal("refund payment owner mismatch")); }
                let business=payment["businessRef"].as_str().ok_or_else(||internal("payment business reference missing"))?;
                if business!=order {
                    if payment["purpose"]!="change" { return Err(internal("refund payment is outside the order")); }
                    let change=self.reference("post-sales",&format!("/api/v1/post-sales-cases/{business}"),&envelope.correlation_id).await?;
                    if change["journeyOrderId"]!=order || change["caseType"]!="CHANGE" { return Err(internal("refund change payment is outside the order")); }
                }
                let amount:Money=serde_json::from_value(refund["amount"].clone()).map_err(internal)?;
                let returned:Money=serde_json::from_value(payment["refundedAmount"].clone()).map_err(internal)?;
                if amount.currency!=returned.currency || amount.minor_units<=0 || amount.minor_units>returned.minor_units { return Err(internal("settled refund exceeds payment return")); }
            }
            let mut fact=envelope.clone();
            fact.event_id=crate::utils::deterministic_event_id("SettledInvoiceRefund",id,1);
            fact.event_type="SettledInvoiceRefund".into();
            fact.payload=json!({"caseId":case_id,"orderId":order,"refundId":id,"refundAmount":refund["amount"],"requestedAt":requested_at});
            svc.apply_subscribed_event(fact).await?;
        }
        Self::commit_state(tx,&svc,&publisher,account).await
    }
}

macro_rules! delegate_mutation {
    ($self:ident, $account:expr, $key:expr, $svc:ident => $body:expr) => {
        $self
            .mutate($account, $key, |$svc| async move {
                let out = $body;
                ($svc, out)
            })
            .await
    };
}

#[async_trait::async_trait]
impl InvoicingApi for PostgresInvoicingService {
    async fn get_amount_basis(&self, order_id: String) -> Result<AmountBasis, InvoicingError> {
        let (service, _) = Self::engine();
        self.load_order_context(&service, &order_id).await?;
        service.get_amount_basis(order_id).await
    }
    async fn create_title(
        &self,
        cmd: CreateInvoiceTitleCommand,
        key: String,
        corr: String,
    ) -> Result<InvoiceTitle, InvoicingError> {
        delegate_mutation!(self, cmd.account_id.clone(), key.clone(), svc => svc.create_title(cmd, key, corr).await)
    }
    async fn list_titles(
        &self,
        account_id: String,
        status: Option<TitleStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<Page<InvoiceTitle>, InvoicingError> {
        self.hydrate_read(Some(&account_id), None, Some("titles"), None)
            .await?
            .list_titles(account_id, status, limit, offset)
            .await
    }
    async fn get_title(&self, id: String) -> Result<InvoiceTitle, InvoicingError> {
        self.hydrate_read(None, None, Some("titles"), Some(&id)).await?.get_title(id).await
    }
    async fn update_title(
        &self,
        id: String,
        cmd: UpdateInvoiceTitleCommand,
        key: String,
        corr: String,
    ) -> Result<InvoiceTitle, InvoicingError> {
        delegate_mutation!(self, self.account_for("titles", &id).await?, key.clone(), svc => svc.update_title(id, cmd, key, corr).await)
    }
    async fn set_default_title(
        &self,
        id: String,
        cmd: SetDefaultTitleCommand,
        key: String,
        corr: String,
    ) -> Result<InvoiceTitle, InvoicingError> {
        delegate_mutation!(self, cmd.account_id.clone(), key.clone(), svc => svc.set_default_title(id, cmd, key, corr).await)
    }
    async fn deactivate_title(
        &self,
        id: String,
        cmd: DeactivateTitleCommand,
        key: String,
        corr: String,
    ) -> Result<DeactivateTitleResponse, InvoicingError> {
        delegate_mutation!(self, self.account_for("titles", &id).await?, key.clone(), svc => svc.deactivate_title(id, cmd, key, corr).await)
    }
    async fn request_invoice(
        &self,
        cmd: RequestEInvoiceCommand,
        key: String,
        corr: String,
    ) -> Result<EInvoiceRequest, InvoicingError> {
        self.mutate(cmd.account_id.clone(), key.clone(), |svc| async move {
            let result = match self.load_order_context(&svc, &cmd.order_id).await {
                Ok(()) => svc.request_invoice(cmd, key, corr).await,
                Err(error) => Err(error),
            };
            (svc, result)
        }).await
    }
    async fn get_request(&self, id: String) -> Result<EInvoiceRequest, InvoicingError> {
        self.hydrate_read(None, None, Some("requests"), Some(&id)).await?.get_request(id).await
    }
    async fn get_invoice(&self, id: String) -> Result<EInvoice, InvoicingError> {
        self.hydrate_read(None, None, Some("invoices"), Some(&id)).await?.get_invoice(id).await
    }
    async fn list_invoices(
        &self,
        order_id: String,
        invoice_type: Option<InvoiceType>,
        status: Option<InvoiceRequestStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<Page<EInvoice>, InvoicingError> {
        self.hydrate_read(None, Some(&order_id), Some("invoices"), None)
            .await?
            .list_invoices(order_id, invoice_type, status, limit, offset)
            .await
    }
    async fn get_red_flush(&self, id: String) -> Result<RedFlushView, InvoicingError> {
        self.hydrate_read(None, None, Some("red_flushes"), Some(&id)).await?.get_red_flush(id).await
    }
    async fn list_red_flushes(
        &self,
        order_id: Option<String>,
        case_id: Option<String>,
        status: Option<RedFlushStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<Page<RedFlushView>, InvoicingError> {
        self.hydrate_read(None, order_id.as_deref(), Some("red_flushes"), None)
            .await?
            .list_red_flushes(order_id, case_id, status, limit, offset)
            .await
    }
    async fn generate_itinerary(
        &self,
        order_id: String,
        traveler_refs: Vec<String>,
        segment_refs: Vec<String>,
        receipt_version: i64,
    ) -> Result<ItineraryReceiptProjection, InvoicingError> {
        let (svc, _) = Self::engine();
        self.load_order_context(&svc, &order_id).await?;
        svc.generate_itinerary(order_id, traveler_refs, segment_refs, receipt_version).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    #[ignore = "requires actual invoice/refund records and PostgreSQL"]
    async fn settled_refund_red_flush_matches_actual_amount_and_survives_restart() {
        let source: Value=serde_json::from_str(&std::fs::read_to_string(std::env::var("TEST_RED_FLUSH_SOURCE").unwrap()).unwrap()).unwrap();
        let url=std::env::var("TEST_DATABASE_URL").unwrap();
        let admin=sqlx::PgPool::connect(&url).await.unwrap();
        let schema=format!("red_flush_{}",uuid::Uuid::now_v7().simple());
        sqlx::query(&format!("CREATE SCHEMA {schema}")).execute(&admin).await.unwrap();
        let search=schema.clone();
        let pool=sqlx::postgres::PgPoolOptions::new().max_connections(4).after_connect(move|conn,_|{
            let sql=format!("SET search_path TO {search}");
            Box::pin(async move {sqlx::query(&sql).execute(conn).await?;Ok(())})
        }).connect(&url).await.unwrap();
        let storage=rust_kit::storage::Storage::new(pool.clone());
        storage.migrate_dir(concat!(env!("CARGO_MANIFEST_DIR"),"/migrations")).await.unwrap();
        let svc=PostgresInvoicingService{storage};
        for row in source["rows"].as_array().unwrap() {
            sqlx::query("INSERT INTO invoicing_command_rows(kind,id,account_id,order_id,data) VALUES($1,$2,$3,$4,$5)")
                .bind(row["kind"].as_str().unwrap()).bind(row["id"].as_str().unwrap()).bind(row["account"].as_str().unwrap())
                .bind(row["order"].as_str().unwrap()).bind(&row["data"]).execute(&pool).await.unwrap();
        }
        let mut approved=envelope("PostSalesApproved","post-sales");
        approved.payload=json!({"caseId":source["case"]["caseId"],"orderId":source["order"]});
        svc.handle_subscribed_event(approved).await.unwrap();
        assert_eq!(sqlx::query_scalar::<_,i64>("SELECT count(*) FROM invoicing_command_rows WHERE kind='red_flushes'").fetch_one(&pool).await.unwrap(),0);
        let mut settled=envelope("RefundSettled","payment");
        settled.payload=json!({"refundId":source["refunds"][0]["refundId"]});
        svc.handle_subscribed_event(settled.clone()).await.unwrap();
        let reds=svc.list_invoices(source["order"].as_str().unwrap().into(),Some(InvoiceType::Red),None,100,0).await.unwrap();
        let expected:i64=source["refunds"].as_array().unwrap().iter().map(|refund|refund["amount"]["minorUnits"].as_i64().unwrap()).sum();
        assert_eq!(reds.items.iter().map(|invoice|invoice.total_amount.minor_units).sum::<i64>(),expected);
        let count=sqlx::query_scalar::<_,i64>("SELECT count(*) FROM outbox").fetch_one(&pool).await.unwrap();
        let restarted=PostgresInvoicingService{storage:rust_kit::storage::Storage::new(pool.clone())};
        for _ in 0..2 {
            settled.event_id=format!("evt-{}",uuid::Uuid::now_v7());
            restarted.handle_subscribed_event(settled.clone()).await.unwrap();
            let mut applied=envelope("PostSalesApplied","post-sales");applied.payload=json!({"caseId":source["case"]["caseId"]});
            restarted.handle_subscribed_event(applied).await.unwrap();
        }
        let a=PostgresInvoicingService{storage:rust_kit::storage::Storage::new(pool.clone())};
        let b=PostgresInvoicingService{storage:rust_kit::storage::Storage::new(pool.clone())};
        let (left,right)=tokio::join!(a.handle_subscribed_event(settled.clone()),b.handle_subscribed_event(settled));
        left.unwrap();right.unwrap();
        assert_eq!(reds.items,restarted.list_invoices(source["order"].as_str().unwrap().into(),Some(InvoiceType::Red),None,100,0).await.unwrap().items);
        assert_eq!(count,sqlx::query_scalar::<_,i64>("SELECT count(*) FROM outbox").fetch_one(&pool).await.unwrap());
        pool.close().await;sqlx::query(&format!("DROP SCHEMA {schema} CASCADE")).execute(&admin).await.unwrap();admin.close().await;
    }

    #[tokio::test]
    #[ignore = "requires recorded invoice events and PostgreSQL"]
    async fn recorded_reversals_update_net_basis_and_survive_replay_and_reload() {
        let source: Value = serde_json::from_str(&std::fs::read_to_string(std::env::var("TEST_INVOICE_NET_EVENTS").unwrap()).unwrap()).unwrap();
        let created: rust_kit::messaging::EventEnvelope = serde_json::from_value(source["created"].clone()).unwrap();
        let adjusted: rust_kit::messaging::EventEnvelope = serde_json::from_value(source["adjusted"].clone()).unwrap();
        let financial: Vec<rust_kit::messaging::EventEnvelope> = serde_json::from_value(source["finance"].clone()).unwrap();
        let order = created.payload["orderId"].as_str().unwrap().to_string();
        let schema = format!("invoice_net_{}", uuid::Uuid::now_v7().simple());
        let url = std::env::var("TEST_DATABASE_URL").unwrap();
        let admin = sqlx::PgPool::connect(&url).await.unwrap();
        sqlx::query(&format!("CREATE SCHEMA {schema}")).execute(&admin).await.unwrap();
        let search = schema.clone();
        let pool = sqlx::postgres::PgPoolOptions::new().max_connections(3).after_connect(move |conn, _| {
            let sql = format!("SET search_path TO {search}");
            Box::pin(async move { sqlx::query(&sql).execute(conn).await?; Ok(()) })
        }).connect(&url).await.unwrap();
        let storage = rust_kit::storage::Storage::new(pool.clone());
        storage.migrate_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/migrations")).await.unwrap();
        let svc = PostgresInvoicingService { storage };
        svc.handle_subscribed_event(created.clone()).await.unwrap();
        let reversal = financial.iter().find(|e| e.event_type == "RevenueRecognitionReversed").unwrap();
        assert!(svc.handle_subscribed_event(reversal.clone()).await.is_err());
        let count: (i64,) = sqlx::query_as("SELECT count(*) FROM processed_events WHERE event_id=$1").bind(&reversal.event_id).fetch_one(&pool).await.unwrap();
        assert_eq!(count.0, 0);
        for event in &financial { svc.handle_subscribed_event(event.clone()).await.unwrap(); }
        svc.handle_subscribed_event(adjusted).await.unwrap();
        let first = svc.get_amount_basis(order.clone()).await.unwrap();
        assert_eq!(first.total_amount.minor_units,source["netMinor"].as_i64().unwrap());
        let restarted = PostgresInvoicingService { storage: rust_kit::storage::Storage::new(pool.clone()) };
        for event in financial.iter().rev() {
            let mut replay = event.clone(); replay.event_id = format!("evt-{}",uuid::Uuid::now_v7());
            restarted.handle_subscribed_event(replay).await.unwrap();
        }
        let mut earlier = created; earlier.event_id = format!("evt-{}",uuid::Uuid::now_v7());
        restarted.handle_subscribed_event(earlier).await.unwrap();
        assert_eq!(first,restarted.get_amount_basis(order.clone()).await.unwrap());
        let mut changed = reversal.clone(); changed.event_id = format!("evt-{}",uuid::Uuid::now_v7());
        changed.payload["amount"]["minorUnits"] = json!(reversal.payload["amount"]["minorUnits"].as_i64().unwrap()+1);
        assert!(restarted.handle_subscribed_event(changed).await.is_err());
        assert_eq!(first,restarted.get_amount_basis(order).await.unwrap());
        pool.close().await;
        sqlx::query(&format!("DROP SCHEMA {schema} CASCADE")).execute(&admin).await.unwrap();
        admin.close().await;
    }

    #[tokio::test]
    #[ignore = "requires TEST_DATABASE_URL pointing to a disposable database"]
    async fn command_rows_preserve_migrated_titles_and_isolate_account_transactions() {
        let admin = sqlx::PgPool::connect(&std::env::var("TEST_DATABASE_URL").unwrap()).await.unwrap();
        let schema = format!("invoice_rows_{}", uuid::Uuid::now_v7().simple());
        sqlx::query(&format!("CREATE SCHEMA {schema}")).execute(&admin).await.unwrap();
        let schema_for_connections = schema.clone();
        let pool = sqlx::postgres::PgPoolOptions::new().max_connections(8).after_connect(move |conn, _| {
            let query = format!("SET search_path TO {schema_for_connections}");
            Box::pin(async move { sqlx::query(&query).execute(conn).await?; Ok(()) })
        }).connect(&std::env::var("TEST_DATABASE_URL").unwrap()).await.unwrap();
        sqlx::raw_sql(include_str!("../../migrations/003_state_row.sql")).execute(&pool).await.unwrap();
        let engine = InMemoryInvoicingService::default();
        let account = format!("acc-{}", uuid::Uuid::now_v7());
        let command = CreateInvoiceTitleCommand { account_id: account.clone(), title_type: TitleType::Personal,
            title_name: "Tax receipt".into(), tax_identity: None, registered_address: None, registered_phone: None,
            bank_name: None, bank_account: None, set_as_default: true };
        let title_key = uuid::Uuid::now_v7().to_string();
        let title = engine.create_title(command.clone(), title_key.clone(), rust_kit::messaging::correlation_id()).await.unwrap();
        sqlx::query("INSERT INTO invoicing_state(id,data) VALUES(1,$1)").bind(serde_json::to_value(engine.snapshot()).unwrap()).execute(&pool).await.unwrap();
        let storage = rust_kit::storage::Storage::new(pool.clone());
        storage.migrate_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/migrations")).await.unwrap();
        let service = PostgresInvoicingService { storage };
        assert_eq!(service.get_title(title.title_id.clone()).await.unwrap().title_name, "Tax receipt");
        let replay = service.create_title(command.clone(), title_key, rust_kit::messaging::correlation_id()).await.unwrap();
        assert_eq!(replay.title_id, title.title_id);
        let mut blocker = pool.begin().await.unwrap();
        sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1,0))").bind(format!("invoice-account:{account}"))
            .execute(&mut *blocker).await.unwrap();
        let other = CreateInvoiceTitleCommand { account_id: format!("acc-{}", uuid::Uuid::now_v7()), ..command };
        tokio::time::timeout(std::time::Duration::from_secs(2), service.create_title(other, uuid::Uuid::now_v7().to_string(), rust_kit::messaging::correlation_id()))
            .await.unwrap().unwrap();
        blocker.rollback().await.unwrap();
        let restarted = PostgresInvoicingService { storage: rust_kit::storage::Storage::new(pool.clone()) };
        assert_eq!(restarted.list_titles(account, None, 20, 0).await.unwrap().total, 1);
        let state: (Value,) = sqlx::query_as("SELECT data FROM invoicing_state WHERE id=1").fetch_one(&pool).await.unwrap();
        assert_eq!(state.0, json!({}));
        pool.close().await;
        sqlx::query(&format!("DROP SCHEMA {schema} CASCADE")).execute(&admin).await.unwrap();
        admin.close().await;
    }

    #[tokio::test]
    #[ignore = "requires TEST_DATABASE_URL pointing to a disposable database"]
    async fn projections_and_saga_invoices_survive_restart_without_rewriting_state() {
        let pool = sqlx::PgPool::connect(&std::env::var("TEST_DATABASE_URL").unwrap()).await.unwrap();
        let storage = rust_kit::storage::Storage::new(pool.clone());
        storage.migrate_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/migrations")).await.unwrap();
        let service = PostgresInvoicingService { storage };
        let order_id = format!("ord-{}", uuid::Uuid::now_v7());
        let saga_id = format!("saga-{}", uuid::Uuid::now_v7());
        let mut order = envelope("JourneyOrderCreated", "journey-order");
        order.payload = json!({"orderId":order_id,"accountId":"acc-invoice-test","travelerRefs":["tvl-invoice-test"],"segmentRefs":["seg-invoice-test"]});
        service.handle_subscribed_event(order.clone()).await.unwrap();
        service.handle_subscribed_event(order).await.unwrap();
        let mut request = envelope("InvoiceRequested", "booking-orchestration");
        request.payload = json!({"sagaId":saga_id,"journeyOrderId":order_id});
        service.handle_subscribed_event(request.clone()).await.unwrap();
        request.event_id = format!("evt-{}", uuid::Uuid::now_v7());
        service.handle_subscribed_event(request).await.unwrap();
        let count: (i64,) = sqlx::query_as("SELECT count(*) FROM outbox WHERE envelope->'payload'->>'sagaId'=$1")
            .bind(&saga_id).fetch_one(&pool).await.unwrap();
        assert_eq!(count.0, 1);
        let restarted = PostgresInvoicingService { storage: rust_kit::storage::Storage::new(pool.clone()) };
        for (recognition, minor) in [("first", 6000), ("second", 4000), ("first", 6000)] {
            let mut event = envelope("RevenueRecognized", "finance-settlement");
            event.payload = json!({"orderId":order_id,"revenueRecognitionId":format!("{order_id}:{recognition}"),
                "amount":{"currency":"CNY","minorUnits":minor}});
            restarted.handle_subscribed_event(event).await.unwrap();
        }
        let basis = service.get_amount_basis(order_id.clone()).await.unwrap();
        assert_eq!(basis.total_amount.minor_units, 10000);
        assert_eq!(basis.revenue_recognition_ids.len(), 2);
        restarted.generate_itinerary(order_id, vec!["tvl-invoice-test".into()], vec!["seg-invoice-test".into()], 1).await.unwrap();
        let rows: (i64,) = sqlx::query_as("SELECT count(*) FROM invoicing_state").fetch_one(&pool).await.unwrap();
        assert_eq!(rows.0, 0);
    }

    fn envelope(event_type: &str, producer: &str) -> rust_kit::messaging::EventEnvelope {
        rust_kit::messaging::EventEnvelope::new(
            event_type,
            rust_kit::messaging::now_rfc3339_utc(),
            rust_kit::messaging::correlation_id(),
            None::<String>,
            producer,
            serde_json::json!({}),
        )
    }

    #[test]
    fn projection_events_require_persistence() {
        for (event_type, producer) in [
            ("JourneyOrderConfirmed", "journey-order"),
            ("InvoiceGenerated", "finance-settlement"),
            ("RevenueRecognized", "finance-settlement"),
            ("PostSalesApplied", "post-sales"),
        ] {
            assert!(PostgresInvoicingService::handles_event(&envelope(
                event_type, producer,
            )));
        }
        assert!(!PostgresInvoicingService::handles_event(&envelope(
            "UnrelatedEvent",
            "journey-order",
        )));
    }
}
