use crate::*;
use serde_json::Value;
use std::sync::Arc;

/// PostgreSQL adapter: the whole aggregate/projection/idempotency state lives
/// in a single `invoicing_state` row. Every mutating command and every
/// consumed event runs as ONE database transaction:
/// `SELECT ... FOR UPDATE` -> hydrate domain engine -> apply -> write state
/// row -> append produced events to the outbox -> (consumers) processed_events
/// -> COMMIT. Nothing survives in process memory between calls, publish
/// failures roll the whole unit back, and the row lock serializes writers so
/// no update can be lost (readers hydrate without the lock).
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
        let row: (Value,) = sqlx::query_as("SELECT data FROM invoicing_state WHERE id = 1 FOR UPDATE")
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
        let data = serde_json::to_value(svc.snapshot()).map_err(internal)?;
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
            "InvoiceRequested" | "RequestEInvoice" | "IssueRedFlush"
        ) && envelope.producer == "booking-orchestration"
            || matches!(
                envelope.event_type.as_str(),
                "RequestEInvoice" | "IssueRedFlush"
            )
    }

    async fn consume_in_tx(
        &self,
        envelope: rust_kit::messaging::EventEnvelope,
        stream: &str,
    ) -> Result<(), InvoicingError> {
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
    async fn create_title(&self, cmd: CreateInvoiceTitleCommand, key: String, corr: String) -> Result<InvoiceTitle, InvoicingError> {
        delegate_mutation!(self, svc => svc.create_title(cmd, key, corr).await)
    }
    async fn list_titles(&self, account_id: String, status: Option<TitleStatus>, limit: usize, offset: usize) -> Result<Page<InvoiceTitle>, InvoicingError> {
        self.hydrate_read().await?.list_titles(account_id, status, limit, offset).await
    }
    async fn get_title(&self, id: String) -> Result<InvoiceTitle, InvoicingError> {
        self.hydrate_read().await?.get_title(id).await
    }
    async fn update_title(&self, id: String, cmd: UpdateInvoiceTitleCommand, key: String, corr: String) -> Result<InvoiceTitle, InvoicingError> {
        delegate_mutation!(self, svc => svc.update_title(id, cmd, key, corr).await)
    }
    async fn set_default_title(&self, id: String, cmd: SetDefaultTitleCommand, key: String, corr: String) -> Result<InvoiceTitle, InvoicingError> {
        delegate_mutation!(self, svc => svc.set_default_title(id, cmd, key, corr).await)
    }
    async fn deactivate_title(&self, id: String, cmd: DeactivateTitleCommand, key: String, corr: String) -> Result<DeactivateTitleResponse, InvoicingError> {
        delegate_mutation!(self, svc => svc.deactivate_title(id, cmd, key, corr).await)
    }
    async fn request_invoice(&self, cmd: RequestEInvoiceCommand, key: String, corr: String) -> Result<EInvoiceRequest, InvoicingError> {
        delegate_mutation!(self, svc => svc.request_invoice(cmd, key, corr).await)
    }
    async fn get_request(&self, id: String) -> Result<EInvoiceRequest, InvoicingError> {
        self.hydrate_read().await?.get_request(id).await
    }
    async fn get_invoice(&self, id: String) -> Result<EInvoice, InvoicingError> {
        self.hydrate_read().await?.get_invoice(id).await
    }
    async fn list_invoices(&self, order_id: String, invoice_type: Option<InvoiceType>, status: Option<InvoiceRequestStatus>, limit: usize, offset: usize) -> Result<Page<EInvoice>, InvoicingError> {
        self.hydrate_read().await?.list_invoices(order_id, invoice_type, status, limit, offset).await
    }
    async fn get_red_flush(&self, id: String) -> Result<RedFlushView, InvoicingError> {
        self.hydrate_read().await?.get_red_flush(id).await
    }
    async fn list_red_flushes(&self, order_id: Option<String>, case_id: Option<String>, status: Option<RedFlushStatus>, limit: usize, offset: usize) -> Result<Page<RedFlushView>, InvoicingError> {
        self.hydrate_read().await?.list_red_flushes(order_id, case_id, status, limit, offset).await
    }
    async fn generate_itinerary(&self, order_id: String, traveler_refs: Vec<String>, segment_refs: Vec<String>, receipt_version: i64) -> Result<ItineraryReceiptProjection, InvoicingError> {
        delegate_mutation!(self, svc => svc.generate_itinerary(order_id, traveler_refs, segment_refs, receipt_version).await)
    }
}
