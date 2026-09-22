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
        for kind in ["titles","requests","invoices","red_flushes","idempotency"] {
            for (id, entity) in data[kind].as_object().ok_or_else(|| InvoicingError::Internal("invalid command snapshot".into()))? {
                sqlx::query("INSERT INTO invoicing_command_rows(kind,id,account_id,order_id,data) VALUES($1,$2,$3,$4,$5) ON CONFLICT(kind,id) DO UPDATE SET data=EXCLUDED.data,order_id=EXCLUDED.order_id WHERE invoicing_command_rows.data IS DISTINCT FROM EXCLUDED.data")
                    .bind(kind).bind(id).bind(account).bind(entity.get("orderId").and_then(Value::as_str))
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
        let mut account = None;
        if matches!(envelope.event_type.as_str(), "PostSalesApproved" | "PostSalesApplied") {
            let order_id = envelope.payload.get("orderId").and_then(Value::as_str).unwrap_or("");
            let found: Option<(String,)> = sqlx::query_as(
                "SELECT account_id FROM invoicing_command_rows WHERE kind='invoices' AND order_id=$1 AND data->>'invoiceType'='BLUE' AND data->>'status'='ISSUED' LIMIT 1",
            ).bind(order_id).fetch_optional(self.pool()).await.map_err(internal)?;
            account = found.map(|row| row.0);
            if account.is_none() {
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
        let account = account.or_else(|| envelope.payload.get("accountId").and_then(Value::as_str).map(str::to_owned)).unwrap_or_default();
        let (svc, publisher) = Self::hydrate_locked(&mut tx, &account, &envelope.event_id).await?;
        svc.apply_subscribed_event(envelope).await?;
        Self::commit_state(tx, &svc, &publisher, &account).await
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
