//! Application wiring: connects domain logic to infrastructure adapters.

use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

use serde_json::Value;
use sqlx::PgPool;

use crate::api::ItineraryJson;
use crate::plan_index::PlanIndex;
use rust_kit::messaging::{self, EventEnvelope};
use rust_kit::storage::{OutboxAppender, SnapshotRepository, StorageError};

/// Central application state shared across handlers and the event subscriber.
pub struct AppState {
    pub plan_index: Arc<PlanIndex>,
    pool: Option<PgPool>,
    snapshots: Option<SnapshotRepository>,
    /// Fallback in-memory cache when Postgres is unavailable.
    mem_snapshots: RwLock<HashMap<String, Value>>,
}

impl AppState {
    pub fn new(pool: Option<PgPool>, plan_index: Arc<PlanIndex>) -> Self {
        Self {
            plan_index,
            pool: pool.clone(),
            snapshots: Some(
                SnapshotRepository::new("itinerary_snapshots")
                    .expect("valid table name"),
            ),
            mem_snapshots: RwLock::new(HashMap::new()),
        }
    }

    pub async fn save_itinerary(&self, itinerary_ref: &str, data: Value) {
        // Best-effort save to memory always
        self.mem_snapshots
            .write()
            .await
            .insert(itinerary_ref.to_string(), data.clone());

        // Try Postgres if available
        if let (Some(pool), Some(repo)) = (&self.pool, &self.snapshots) {
            let result: Result<(), StorageError> = async {
                let mut tx = pool.begin().await?;
                // Try insert, ignore conflict (idempotent)
                let _ = repo.save(&mut tx, itinerary_ref, None, &data).await;
                tx.commit().await?;
                Ok(())
            }
            .await;
            if let Err(e) = result {
                log::warn!("failed to persist itinerary snapshot: {}", e);
            }
        }
    }

    pub async fn get_itinerary(&self, itinerary_ref: &str) -> Option<Value> {
        // Check memory first
        if let Some(val) = self.mem_snapshots.read().await.get(itinerary_ref) {
            return Some(val.clone());
        }

        // Try Postgres
        if let (Some(pool), Some(repo)) = (&self.pool, &self.snapshots) {
            let result: Result<Option<Value>, StorageError> = async {
                let snap = repo.get::<Value>(pool, itinerary_ref).await?;
                Ok(snap.map(|s| s.data))
            }
            .await;
            match result {
                Ok(Some(val)) => return Some(val),
                Ok(None) => return None,
                Err(e) => {
                    log::warn!("failed to read itinerary snapshot: {}", e);
                    return None;
                }
            }
        }

        None
    }

    pub async fn publish_itinerary_proposed(
        &self,
        intent_ref: &str,
        itineraries: &[ItineraryJson],
        planning_snapshot_refs: &[String],
        correlation_id: &str,
    ) {
        let payload = serde_json::json!({
            "intentRef": intent_ref,
            "itineraries": itineraries.iter().map(|i| serde_json::json!({
                "itineraryRef": i.itinerary_ref,
                "legs": i.legs,
                "priceHint": i.price_hint,
                "availabilityHint": i.availability_hint,
            })).collect::<Vec<_>>(),
            "planningSnapshotRefs": planning_snapshot_refs,
        });

        let corr_id = messaging::valid_or_generated_correlation_id(correlation_id);

        let envelope = match EventEnvelope::try_new(
            "ItineraryProposed",
            messaging::now_rfc3339_utc(),
            corr_id,
            None::<String>,
            "trip-planning",
            payload,
        ) {
            Ok(e) => e,
            Err(err) => {
                log::error!("failed to build ItineraryProposed envelope: {}", err);
                return;
            }
        };

        if let Some(pool) = &self.pool {
            let result: Result<(), StorageError> = async {
                let mut tx = pool.begin().await?;
                OutboxAppender::append(&mut tx, "events:trip-planning", &envelope).await?;
                tx.commit().await?;
                Ok(())
            }
            .await;
            if let Err(e) = result {
                log::error!("failed to append ItineraryProposed to outbox: {}", e);
            }
        }
    }

    /// Handle an inbound event from subscribed streams.
    pub fn handle_inbound_event(&self, envelope: &EventEnvelope) {
        self.plan_index
            .apply_event(&envelope.event_type, &envelope.payload);
    }
}
