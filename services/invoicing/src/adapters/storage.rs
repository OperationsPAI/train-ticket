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
    ) -> Result<(InMemoryInvoicingService, Arc<InMemoryEventPublisher>), InvoicingError> {
        sqlx::query("INSERT INTO invoicing_state (id, data) VALUES (1, '{}'::jsonb) ON CONFLICT (id) DO NOTHING")
            .execute(&mut **tx)
            .await
            .map_err(internal)?;
        let row: (Value,) =
            sqlx::query_as("SELECT data FROM invoicing_state WHERE id = 1 FOR UPDATE")
                .fetch_one(&mut **tx)
                .await
                .map_err(internal)?;
        let (svc, publisher) = Self::engine();
        if row.0.as_object().map(|o| !o.is_empty()).unwrap_or(false) {
            let snapshot: InvoicingStateSnapshot =
                serde_json::from_value(row.0).map_err(internal)?;
            svc.import_snapshot(snapshot);
        }
        Ok((svc, publisher))
    }

    async fn hydrate_read(&self) -> Result<InMemoryInvoicingService, InvoicingError> {
        let row: Option<(Value,)> = sqlx::query_as("SELECT data FROM invoicing_state WHERE id = 1")
            .fetch_optional(self.pool())
            .await
            .map_err(internal)?;
        let (svc, _publisher) = Self::engine();
        if let Some((data,)) = row {
            if data.as_object().map(|o| !o.is_empty()).unwrap_or(false) {
                let snapshot: InvoicingStateSnapshot =
                    serde_json::from_value(data).map_err(internal)?;
                svc.import_snapshot(snapshot);
            }
        }
        Ok(svc)
    }

    async fn commit_state(
        mut tx: sqlx::Transaction<'_, sqlx::Postgres>,
        svc: &InMemoryInvoicingService,
        publisher: &InMemoryEventPublisher,
    ) -> Result<(), InvoicingError> {
        let mut snapshot = svc.snapshot();
        snapshot.orders.clear();
        snapshot.amounts.clear();
        snapshot.saga_invoices.clear();
        let data = serde_json::to_value(snapshot).map_err(internal)?;
        sqlx::query("UPDATE invoicing_state SET data = $1 WHERE id = 1")
            .bind(data)
            .execute(&mut *tx)
            .await
            .map_err(internal)?;
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

    async fn mutate<T, F, Fut>(&self, f: F) -> Result<T, InvoicingError>
    where
        F: FnOnce(InMemoryInvoicingService) -> Fut,
        Fut: std::future::Future<Output = (InMemoryInvoicingService, Result<T, InvoicingError>)>,
    {
        let mut tx = self.pool().begin().await.map_err(internal)?;
        let (svc, publisher) = Self::hydrate_locked(&mut tx).await?;
        let (svc, out) = f(svc).await;
        match out {
            Ok(value) => {
                Self::commit_state(tx, &svc, &publisher).await?;
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
        let stream = format!("events:{}", envelope.producer);
        self.consume_in_tx(envelope, &stream)
            .await
            .map_err(crate::utils::handler_error)
    }

    fn requires_persistence(envelope: &rust_kit::messaging::EventEnvelope) -> bool {
        matches!(
            envelope.event_type.as_str(),
            "JourneyOrderCreated"
                | "JourneyOrderConfirmed"
                | "JourneyOrderPostSalesAdjusted"
                | "RevenueRecognized"
                | "InvoiceGenerated"
                | "PostSalesApproved"
                | "PostSalesApplied"
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
        let projection = match envelope.event_type.as_str() {
            "JourneyOrderCreated" | "JourneyOrderConfirmed" | "JourneyOrderPostSalesAdjusted" => Some(("orders", "orderId")),
            "RevenueRecognized" | "InvoiceGenerated" => Some(("amounts", "orderId")),
            "InvoiceRequested" if envelope.producer == "booking-orchestration" => Some(("saga_invoices", "sagaId")),
            _ => None,
        };
        if let Some((kind, field)) = projection {
            if let Some(id) = envelope.payload.get(field).and_then(Value::as_str).map(str::to_owned) {
                return self.consume_projection(envelope, stream, kind, &id).await;
            }
        }
        if matches!(envelope.event_type.as_str(), "PostSalesApproved" | "PostSalesApplied") {
            let order_id = envelope.payload.get("orderId").and_then(Value::as_str).unwrap_or("");
            let exists: (bool,) = sqlx::query_as(
                "SELECT EXISTS (SELECT 1 FROM invoicing_state, LATERAL jsonb_each(COALESCE(data->'invoices','{}'::jsonb)) i WHERE i.value->>'orderId'=$1 AND i.value->>'invoiceType'='BLUE' AND i.value->>'status'='ISSUED')",
            ).bind(order_id).fetch_one(self.pool()).await.map_err(internal)?;
            if !exists.0 {
                sqlx::query("INSERT INTO processed_events (event_id,stream) VALUES ($1,$2) ON CONFLICT DO NOTHING")
                    .bind(&envelope.event_id).bind(stream).execute(self.pool()).await.map_err(internal)?;
                return Ok(());
            }
        }
        if !Self::requires_persistence(&envelope) {
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
            tx.commit().await.map_err(internal)?;
            return Ok(());
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
        let (svc, publisher) = Self::hydrate_locked(&mut tx).await?;
        svc.apply_subscribed_event(envelope).await?;
        Self::commit_state(tx, &svc, &publisher).await
    }
}

macro_rules! delegate_mutation {
    ($self:ident, $svc:ident => $body:expr) => {
        $self
            .mutate(|$svc| async move {
                let out = $body;
                ($svc, out)
            })
            .await
    };
}

#[async_trait::async_trait]
impl InvoicingApi for PostgresInvoicingService {
    async fn get_amount_basis(&self, order_id: String) -> Result<AmountBasis, InvoicingError> {
        let row: Option<(Value,)> = sqlx::query_as("SELECT data FROM invoicing_projection_rows WHERE kind='amounts' AND id=$1")
            .bind(order_id).fetch_optional(self.pool()).await.map_err(internal)?;
        let (data,) = row.ok_or_else(|| InvoicingError::NotFound("amount basis not yet available".into()))?;
        serde_json::from_value(data).map_err(internal)
    }
    async fn create_title(
        &self,
        cmd: CreateInvoiceTitleCommand,
        key: String,
        corr: String,
    ) -> Result<InvoiceTitle, InvoicingError> {
        delegate_mutation!(self, svc => svc.create_title(cmd, key, corr).await)
    }
    async fn list_titles(
        &self,
        account_id: String,
        status: Option<TitleStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<Page<InvoiceTitle>, InvoicingError> {
        self.hydrate_read()
            .await?
            .list_titles(account_id, status, limit, offset)
            .await
    }
    async fn get_title(&self, id: String) -> Result<InvoiceTitle, InvoicingError> {
        self.hydrate_read().await?.get_title(id).await
    }
    async fn update_title(
        &self,
        id: String,
        cmd: UpdateInvoiceTitleCommand,
        key: String,
        corr: String,
    ) -> Result<InvoiceTitle, InvoicingError> {
        delegate_mutation!(self, svc => svc.update_title(id, cmd, key, corr).await)
    }
    async fn set_default_title(
        &self,
        id: String,
        cmd: SetDefaultTitleCommand,
        key: String,
        corr: String,
    ) -> Result<InvoiceTitle, InvoicingError> {
        delegate_mutation!(self, svc => svc.set_default_title(id, cmd, key, corr).await)
    }
    async fn deactivate_title(
        &self,
        id: String,
        cmd: DeactivateTitleCommand,
        key: String,
        corr: String,
    ) -> Result<DeactivateTitleResponse, InvoicingError> {
        delegate_mutation!(self, svc => svc.deactivate_title(id, cmd, key, corr).await)
    }
    async fn request_invoice(
        &self,
        cmd: RequestEInvoiceCommand,
        key: String,
        corr: String,
    ) -> Result<EInvoiceRequest, InvoicingError> {
        self.mutate(|svc| async move {
            let result = match self.load_order_context(&svc, &cmd.order_id).await {
                Ok(()) => svc.request_invoice(cmd, key, corr).await,
                Err(error) => Err(error),
            };
            (svc, result)
        }).await
    }
    async fn get_request(&self, id: String) -> Result<EInvoiceRequest, InvoicingError> {
        self.hydrate_read().await?.get_request(id).await
    }
    async fn get_invoice(&self, id: String) -> Result<EInvoice, InvoicingError> {
        self.hydrate_read().await?.get_invoice(id).await
    }
    async fn list_invoices(
        &self,
        order_id: String,
        invoice_type: Option<InvoiceType>,
        status: Option<InvoiceRequestStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<Page<EInvoice>, InvoicingError> {
        self.hydrate_read()
            .await?
            .list_invoices(order_id, invoice_type, status, limit, offset)
            .await
    }
    async fn get_red_flush(&self, id: String) -> Result<RedFlushView, InvoicingError> {
        self.hydrate_read().await?.get_red_flush(id).await
    }
    async fn list_red_flushes(
        &self,
        order_id: Option<String>,
        case_id: Option<String>,
        status: Option<RedFlushStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<Page<RedFlushView>, InvoicingError> {
        self.hydrate_read()
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
            assert!(PostgresInvoicingService::requires_persistence(&envelope(
                event_type, producer,
            )));
        }
        assert!(!PostgresInvoicingService::requires_persistence(&envelope(
            "UnrelatedEvent",
            "journey-order",
        )));
    }
}
