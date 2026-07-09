use crate::utils::{
    array_strings, handler_error, normalize_json, sha256_prefixed, storage_error, string_field,
};
use crate::*;
use rust_kit::storage::{DbIdempotencyStore, IdempotencyTxDecision};
use serde_json::Value;
use std::sync::Arc;

pub struct PostgresInvoicingService {
    storage: rust_kit::storage::Storage,
    inner: InMemoryInvoicingService,
    publisher: Arc<InMemoryEventPublisher>,
}
impl PostgresInvoicingService {
    pub async fn from_env() -> Result<Self, InvoicingError> {
        let storage = rust_kit::storage::Storage::from_env()
            .await
            .map_err(storage_error)?;
        let dir = std::env::var("MIGRATIONS_DIR").unwrap_or_else(|_| "/app/migrations".into());
        match storage.migrate_dir(&dir).await {
            Ok(()) => {}
            Err(e) => {
                if dir == "/app/migrations" {
                    storage
                        .migrate_dir("services/invoicing/migrations")
                        .await
                        .map_err(storage_error)?
                } else {
                    return Err(storage_error(e));
                }
            }
        }
        let publisher = Arc::new(InMemoryEventPublisher::default());
        let service = Self {
            storage,
            inner: InMemoryInvoicingService::new(publisher.clone()),
            publisher,
        };
        service.load_state_from_db().await?;
        Ok(service)
    }
    pub fn pool(&self) -> &sqlx::PgPool {
        self.storage.pool()
    }
    pub async fn is_ready(&self) -> bool {
        self.storage.is_ready().await
    }
    async fn load_state_from_db(&self) -> Result<(), InvoicingError> {
        let mut snapshot = InvoicingStateSnapshot::default();
        for (id, data) in
            sqlx::query_as::<_, (String, Value)>("SELECT title_id, data FROM invoice_titles")
                .fetch_all(self.pool())
                .await
                .map_err(|e| InvoicingError::Internal(e.to_string()))?
        {
            snapshot.titles.insert(
                id,
                serde_json::from_value(data)
                    .map_err(|e| InvoicingError::Internal(e.to_string()))?,
            );
        }
        for (id, data) in sqlx::query_as::<_, (String, Value)>(
            "SELECT invoice_request_id, data FROM e_invoice_requests",
        )
        .fetch_all(self.pool())
        .await
        .map_err(|e| InvoicingError::Internal(e.to_string()))?
        {
            snapshot.requests.insert(
                id,
                serde_json::from_value(data)
                    .map_err(|e| InvoicingError::Internal(e.to_string()))?,
            );
        }
        for (id, data) in
            sqlx::query_as::<_, (String, Value)>("SELECT e_invoice_id, data FROM e_invoices")
                .fetch_all(self.pool())
                .await
                .map_err(|e| InvoicingError::Internal(e.to_string()))?
        {
            snapshot.invoices.insert(
                id,
                serde_json::from_value(data)
                    .map_err(|e| InvoicingError::Internal(e.to_string()))?,
            );
        }
        for (id, data) in
            sqlx::query_as::<_, (String, Value)>("SELECT red_flush_id, data FROM red_flushes")
                .fetch_all(self.pool())
                .await
                .map_err(|e| InvoicingError::Internal(e.to_string()))?
        {
            snapshot.red_flushes.insert(
                id,
                serde_json::from_value(data)
                    .map_err(|e| InvoicingError::Internal(e.to_string()))?,
            );
        }
        for (order_id, account_id, traveler_refs, segment_refs) in sqlx::query_as::<_, (String, String, Value, Value)>("SELECT order_id, account_id, traveler_refs, segment_refs FROM invoice_eligibility_projection")
            .fetch_all(self.pool())
            .await
            .map_err(|e| InvoicingError::Internal(e.to_string()))?
        {
            snapshot.orders.insert(order_id, OrderProjection {
                account_id,
                traveler_refs: serde_json::from_value(traveler_refs).unwrap_or_default(),
                segment_refs: serde_json::from_value(segment_refs).unwrap_or_default(),
            });
        }
        for (order_id, amount_basis) in sqlx::query_as::<_, (String, Value)>(
            "SELECT order_id, amount_basis FROM amount_basis_projection",
        )
        .fetch_all(self.pool())
        .await
        .map_err(|e| InvoicingError::Internal(e.to_string()))?
        {
            snapshot.amounts.insert(
                order_id,
                serde_json::from_value(amount_basis)
                    .map_err(|e| InvoicingError::Internal(e.to_string()))?,
            );
        }
        for (key, request_hash, response_body) in sqlx::query_as::<_, (String, String, Value)>(
            "SELECT key, request_hash, COALESCE(response_body, 'null'::jsonb) FROM idempotency_records WHERE status_code BETWEEN 200 AND 299",
        )
        .fetch_all(self.pool())
        .await
        .map_err(|e| InvoicingError::Internal(e.to_string()))?
        {
            snapshot.idempotency.insert(
                key,
                IdemRecord {
                    op: "postgres_replay",
                    fp: request_hash,
                    response: response_body,
                },
            );
        }
        self.inner.import_snapshot(snapshot);
        Ok(())
    }

    async fn persist_snapshot(&self) -> Result<(), InvoicingError> {
        let snapshot = self.inner.snapshot();
        let mut tx = self
            .pool()
            .begin()
            .await
            .map_err(|e| InvoicingError::Internal(e.to_string()))?;
        for title in snapshot.titles.values() {
            sqlx::query("INSERT INTO invoice_titles (title_id, account_id, status, title_type, material_hash, version, data) VALUES ($1,$2,$3,$4,$5,$6,$7) ON CONFLICT (title_id) DO UPDATE SET account_id=EXCLUDED.account_id,status=EXCLUDED.status,title_type=EXCLUDED.title_type,material_hash=EXCLUDED.material_hash,version=EXCLUDED.version,data=EXCLUDED.data,updated_at=now()")
                .bind(&title.title_id).bind(&title.account_id).bind(title.status.as_contract()).bind(title.title_type.as_contract())
                .bind(InvoiceTitle::material_hash(&title.account_id, &title.title_type, &title.title_name, &title.tax_identity_hash, &title.bank_account_hash))
                .bind(title.version).bind(serde_json::to_value(title).map_err(|e| InvoicingError::Internal(e.to_string()))?)
                .execute(&mut *tx).await.map_err(|e| InvoicingError::Internal(e.to_string()))?;
        }
        for request in snapshot.requests.values() {
            sqlx::query("INSERT INTO e_invoice_requests (invoice_request_id, order_id, account_id, title_id, status, material_hash, e_invoice_id, data, version) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) ON CONFLICT (invoice_request_id) DO UPDATE SET order_id=EXCLUDED.order_id,account_id=EXCLUDED.account_id,title_id=EXCLUDED.title_id,status=EXCLUDED.status,e_invoice_id=EXCLUDED.e_invoice_id,data=EXCLUDED.data,version=EXCLUDED.version,updated_at=now()")
                .bind(&request.invoice_request_id).bind(&request.order_id).bind(&request.account_id).bind(&request.title_id).bind(request.status.as_contract())
                .bind(sha256_prefixed(&format!("{}|{}|{}", request.order_id, request.title_id, request.amount_basis.amount_basis_hash)))
                .bind(&request.e_invoice_id).bind(serde_json::to_value(request).map_err(|e| InvoicingError::Internal(e.to_string()))?).bind(request.version)
                .execute(&mut *tx).await.map_err(|e| InvoicingError::Internal(e.to_string()))?;
        }
        for invoice in snapshot.invoices.values() {
            sqlx::query("INSERT INTO e_invoices (e_invoice_id, invoice_request_id, order_id, invoice_type, status, data) VALUES ($1,$2,$3,$4,$5,$6) ON CONFLICT (e_invoice_id) DO UPDATE SET invoice_request_id=EXCLUDED.invoice_request_id,order_id=EXCLUDED.order_id,invoice_type=EXCLUDED.invoice_type,status=EXCLUDED.status,data=EXCLUDED.data,updated_at=now()")
                .bind(&invoice.e_invoice_id).bind(&invoice.invoice_request_id).bind(&invoice.order_id).bind(invoice.invoice_type.as_contract()).bind(invoice.status.as_contract()).bind(serde_json::to_value(invoice).map_err(|e| InvoicingError::Internal(e.to_string()))?)
                .execute(&mut *tx).await.map_err(|e| InvoicingError::Internal(e.to_string()))?;
        }
        for red_flush in snapshot.red_flushes.values() {
            sqlx::query("INSERT INTO red_flushes (red_flush_id, original_invoice_id, post_sales_case_id, order_id, status, data, version) VALUES ($1,$2,$3,$4,$5,$6,$7) ON CONFLICT (red_flush_id) DO UPDATE SET original_invoice_id=EXCLUDED.original_invoice_id,post_sales_case_id=EXCLUDED.post_sales_case_id,order_id=EXCLUDED.order_id,status=EXCLUDED.status,data=EXCLUDED.data,version=EXCLUDED.version,updated_at=now()")
                .bind(&red_flush.red_flush_id).bind(&red_flush.original_invoice_id).bind(&red_flush.post_sales_case_id).bind(&red_flush.order_id).bind(red_flush.status.as_contract()).bind(serde_json::to_value(red_flush).map_err(|e| InvoicingError::Internal(e.to_string()))?).bind(red_flush.version)
                .execute(&mut *tx).await.map_err(|e| InvoicingError::Internal(e.to_string()))?;
        }
        for envelope in self.publisher.drain() {
            rust_kit::storage::OutboxAppender::append(
                &mut tx,
                &rust_kit::messaging::stream_for_producer(profile().service_id),
                &envelope,
            )
            .await
            .map_err(storage_error)?;
        }
        tx.commit()
            .await
            .map_err(|e| InvoicingError::Internal(e.to_string()))?;
        Ok(())
    }

    async fn run_idempotent<T, F, Fut>(
        &self,
        key: String,
        request_hash: String,
        status_code: u16,
        f: F,
    ) -> Result<T, InvoicingError>
    where
        T: serde::Serialize + serde::de::DeserializeOwned + Clone,
        Self: Sync,
        F: FnOnce() -> Fut,
        Fut: std::future::Future<Output = Result<T, InvoicingError>>,
    {
        let mut tx = self
            .pool()
            .begin()
            .await
            .map_err(|e| InvoicingError::Internal(e.to_string()))?;
        match DbIdempotencyStore::claim_response(&mut tx, &key, &request_hash)
            .await
            .map_err(storage_error)?
        {
            IdempotencyTxDecision::Replay(record) => {
                tx.commit()
                    .await
                    .map_err(|e| InvoicingError::Internal(e.to_string()))?;
                return serde_json::from_value(record.body)
                    .map_err(|e| InvoicingError::Internal(e.to_string()));
            }
            IdempotencyTxDecision::Claimed => {
                tx.commit()
                    .await
                    .map_err(|e| InvoicingError::Internal(e.to_string()))?;
            }
        }

        let result = f().await?;
        self.persist_snapshot().await?;

        let body =
            serde_json::to_value(&result).map_err(|e| InvoicingError::Internal(e.to_string()))?;
        let mut tx = self
            .pool()
            .begin()
            .await
            .map_err(|e| InvoicingError::Internal(e.to_string()))?;
        match DbIdempotencyStore::record_response(&mut tx, &key, &request_hash, status_code, body)
            .await
            .map_err(storage_error)?
        {
            IdempotencyTxDecision::Replay(record) => {
                tx.commit()
                    .await
                    .map_err(|e| InvoicingError::Internal(e.to_string()))?;
                serde_json::from_value(record.body)
                    .map_err(|e| InvoicingError::Internal(e.to_string()))
            }
            IdempotencyTxDecision::Claimed => {
                tx.commit()
                    .await
                    .map_err(|e| InvoicingError::Internal(e.to_string()))?;
                Ok(result)
            }
        }
    }

    async fn process_subscribed_event_tx(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), InvoicingError> {
        let event_type = envelope.event_type.clone();
        let event_id = envelope.event_id.clone();
        let stream = rust_kit::messaging::stream_for_producer(&envelope.producer);
        let order_id = string_field(&envelope.payload, "orderId");
        let account_id = string_field(&envelope.payload, "accountId");
        let traveler_refs = array_strings(&envelope.payload, "travelerRefs");
        let segment_refs = array_strings(&envelope.payload, "segmentRefs");

        let mut tx = self
            .pool()
            .begin()
            .await
            .map_err(|e| InvoicingError::Internal(e.to_string()))?;
        let inserted = sqlx::query(
            "INSERT INTO processed_events (event_id, stream) VALUES ($1, $2) ON CONFLICT DO NOTHING",
        )
        .bind(&event_id)
        .bind(&stream)
        .execute(&mut *tx)
        .await
        .map_err(|e| InvoicingError::Internal(e.to_string()))?
        .rows_affected();
        if inserted == 0 {
            tx.commit()
                .await
                .map_err(|e| InvoicingError::Internal(e.to_string()))?;
            return Ok(());
        }

        self.inner.apply_subscribed_event(envelope).await?;
        match event_type.as_str() {
            "JourneyOrderConfirmed" | "JourneyOrderPostSalesAdjusted" => {
                if let (Some(order_id), Some(account_id)) =
                    (order_id.as_deref(), account_id.as_deref())
                {
                    sqlx::query("INSERT INTO invoice_eligibility_projection (order_id, account_id, status, traveler_refs, segment_refs, source_event_id) VALUES ($1,$2,'CONFIRMED',$3,$4,$5) ON CONFLICT (order_id) DO UPDATE SET account_id=EXCLUDED.account_id,status=EXCLUDED.status,traveler_refs=EXCLUDED.traveler_refs,segment_refs=EXCLUDED.segment_refs,source_event_id=EXCLUDED.source_event_id,updated_at=now()")
                        .bind(order_id)
                        .bind(account_id)
                        .bind(serde_json::to_value(&traveler_refs).map_err(|e| InvoicingError::Internal(e.to_string()))?)
                        .bind(serde_json::to_value(&segment_refs).map_err(|e| InvoicingError::Internal(e.to_string()))?)
                        .bind(&event_id)
                        .execute(&mut *tx)
                        .await
                        .map_err(|e| InvoicingError::Internal(e.to_string()))?;
                }
            }
            "RevenueRecognized" | "InvoiceGenerated" => {
                if let Some(order_id) = order_id.as_deref() {
                    if let Some(amount_basis) = self.inner.snapshot().amounts.get(order_id).cloned()
                    {
                        sqlx::query("INSERT INTO amount_basis_projection (order_id, amount_basis, source_event_id) VALUES ($1,$2,$3) ON CONFLICT (order_id) DO UPDATE SET amount_basis=EXCLUDED.amount_basis,source_event_id=EXCLUDED.source_event_id,updated_at=now()")
                            .bind(order_id)
                            .bind(serde_json::to_value(&amount_basis).map_err(|e| InvoicingError::Internal(e.to_string()))?)
                            .bind(&event_id)
                            .execute(&mut *tx)
                            .await
                            .map_err(|e| InvoicingError::Internal(e.to_string()))?;
                    }
                }
            }
            "PostSalesApproved" | "PostSalesApplied" => {
                let snapshot = self.inner.snapshot();
                for invoice in snapshot.invoices.values() {
                    sqlx::query("INSERT INTO e_invoices (e_invoice_id, invoice_request_id, order_id, invoice_type, status, data) VALUES ($1,$2,$3,$4,$5,$6) ON CONFLICT (e_invoice_id) DO UPDATE SET invoice_request_id=EXCLUDED.invoice_request_id,order_id=EXCLUDED.order_id,invoice_type=EXCLUDED.invoice_type,status=EXCLUDED.status,data=EXCLUDED.data,updated_at=now()")
                        .bind(&invoice.e_invoice_id)
                        .bind(&invoice.invoice_request_id)
                        .bind(&invoice.order_id)
                        .bind(invoice.invoice_type.as_contract())
                        .bind(invoice.status.as_contract())
                        .bind(serde_json::to_value(invoice).map_err(|e| InvoicingError::Internal(e.to_string()))?)
                        .execute(&mut *tx)
                        .await
                        .map_err(|e| InvoicingError::Internal(e.to_string()))?;
                }
                for red_flush in snapshot.red_flushes.values() {
                    sqlx::query("INSERT INTO red_flushes (red_flush_id, original_invoice_id, post_sales_case_id, order_id, status, data, version) VALUES ($1,$2,$3,$4,$5,$6,$7) ON CONFLICT (red_flush_id) DO UPDATE SET original_invoice_id=EXCLUDED.original_invoice_id,post_sales_case_id=EXCLUDED.post_sales_case_id,order_id=EXCLUDED.order_id,status=EXCLUDED.status,data=EXCLUDED.data,version=EXCLUDED.version,updated_at=now()")
                        .bind(&red_flush.red_flush_id)
                        .bind(&red_flush.original_invoice_id)
                        .bind(&red_flush.post_sales_case_id)
                        .bind(&red_flush.order_id)
                        .bind(red_flush.status.as_contract())
                        .bind(serde_json::to_value(red_flush).map_err(|e| InvoicingError::Internal(e.to_string()))?)
                        .bind(red_flush.version)
                        .execute(&mut *tx)
                        .await
                        .map_err(|e| InvoicingError::Internal(e.to_string()))?;
                }
            }
            _ => {}
        }
        for envelope in self.publisher.drain() {
            rust_kit::storage::OutboxAppender::append(
                &mut tx,
                &rust_kit::messaging::stream_for_producer(profile().service_id),
                &envelope,
            )
            .await
            .map_err(storage_error)?;
        }
        tx.commit()
            .await
            .map_err(|e| InvoicingError::Internal(e.to_string()))?;
        Ok(())
    }

    pub async fn handle_subscribed_event(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
    ) -> Result<(), rust_kit::messaging::HandlerError> {
        self.process_subscribed_event_tx(envelope)
            .await
            .map_err(handler_error)
    }
}

#[async_trait::async_trait]
impl InvoicingApi for PostgresInvoicingService {
    async fn create_title(
        &self,
        cmd: CreateInvoiceTitleCommand,
        key: String,
        corr: String,
    ) -> Result<InvoiceTitle, InvoicingError> {
        let fp = normalize_json(&cmd);
        self.run_idempotent(key.clone(), fp, 201, || async move {
            self.inner.create_title(cmd, key, corr).await
        })
        .await
    }
    async fn list_titles(
        &self,
        account_id: String,
        status: Option<TitleStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<Page<InvoiceTitle>, InvoicingError> {
        self.inner
            .list_titles(account_id, status, limit, offset)
            .await
    }
    async fn get_title(&self, id: String) -> Result<InvoiceTitle, InvoicingError> {
        self.inner.get_title(id).await
    }
    async fn update_title(
        &self,
        id: String,
        cmd: UpdateInvoiceTitleCommand,
        key: String,
        corr: String,
    ) -> Result<InvoiceTitle, InvoicingError> {
        let fp = normalize_json(&cmd);
        self.run_idempotent(key.clone(), fp, 200, || async move {
            self.inner.update_title(id, cmd, key, corr).await
        })
        .await
    }
    async fn set_default_title(
        &self,
        id: String,
        cmd: SetDefaultTitleCommand,
        key: String,
        corr: String,
    ) -> Result<InvoiceTitle, InvoicingError> {
        let fp = format!("{}:{}:SET_DEFAULT", cmd.account_id, id);
        self.run_idempotent(key.clone(), fp, 200, || async move {
            self.inner.set_default_title(id, cmd, key, corr).await
        })
        .await
    }
    async fn deactivate_title(
        &self,
        id: String,
        cmd: DeactivateTitleCommand,
        key: String,
        corr: String,
    ) -> Result<DeactivateTitleResponse, InvoicingError> {
        let fp = normalize_json(&cmd);
        self.run_idempotent(key.clone(), fp, 200, || async move {
            self.inner.deactivate_title(id, cmd, key, corr).await
        })
        .await
    }
    async fn request_invoice(
        &self,
        cmd: RequestEInvoiceCommand,
        key: String,
        corr: String,
    ) -> Result<EInvoiceRequest, InvoicingError> {
        let fp = normalize_json(&cmd);
        self.run_idempotent(key.clone(), fp, 201, || async move {
            self.inner.request_invoice(cmd, key, corr).await
        })
        .await
    }
    async fn get_request(&self, id: String) -> Result<EInvoiceRequest, InvoicingError> {
        self.inner.get_request(id).await
    }
    async fn get_invoice(&self, id: String) -> Result<EInvoice, InvoicingError> {
        self.inner.get_invoice(id).await
    }
    async fn list_invoices(
        &self,
        order_id: String,
        invoice_type: Option<InvoiceType>,
        status: Option<InvoiceRequestStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<Page<EInvoice>, InvoicingError> {
        self.inner
            .list_invoices(order_id, invoice_type, status, limit, offset)
            .await
    }
    async fn get_red_flush(&self, id: String) -> Result<RedFlushView, InvoicingError> {
        self.inner.get_red_flush(id).await
    }
    async fn list_red_flushes(
        &self,
        order_id: Option<String>,
        case_id: Option<String>,
        status: Option<RedFlushStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<Page<RedFlushView>, InvoicingError> {
        self.inner
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
        self.inner
            .generate_itinerary(order_id, traveler_refs, segment_refs, receipt_version)
            .await
    }
}
