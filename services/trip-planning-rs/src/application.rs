//! Application wiring: connects domain logic to infrastructure adapters.

use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

use serde_json::Value;
use sqlx::PgPool;

use crate::api::ItineraryJson;
use crate::plan_index::{IndexMutation, PlanIndex};
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
    ///
    /// The index update is persisted as well as applied in memory. The startup
    /// loader rebuilds the index from `plan_segments` / `plan_nodes`, and the
    /// Redis consumer group is created at `$`, so an update that is only
    /// applied in memory is lost for good at the next restart.
    pub async fn handle_inbound_event(
        &self,
        envelope: &EventEnvelope,
    ) -> Result<(), StorageError> {
        let mutation = self
            .plan_index
            .apply_event(&envelope.event_type, &envelope.payload);
        let Some(pool) = &self.pool else {
            return Ok(());
        };
        match mutation {
            IndexMutation::Segment(entry) => {
                sqlx::query(
                    "INSERT INTO plan_segments (segment_ref, scheduled_service_ref, origin_stop_ref, destination_stop_ref, departure_time, departure_date, arrival_time, data) \
                     VALUES ($1, $2, $3, $4, $5, $6, $7, $8) \
                     ON CONFLICT (segment_ref) DO UPDATE SET \
                       version = plan_segments.version + 1, \
                       scheduled_service_ref = EXCLUDED.scheduled_service_ref, \
                       origin_stop_ref = EXCLUDED.origin_stop_ref, \
                       destination_stop_ref = EXCLUDED.destination_stop_ref, \
                       departure_time = EXCLUDED.departure_time, \
                       departure_date = EXCLUDED.departure_date, \
                       arrival_time = EXCLUDED.arrival_time, \
                       data = EXCLUDED.data, \
                       updated_at = now()",
                )
                .bind(&entry.segment_ref)
                .bind(&entry.scheduled_service_ref)
                .bind(&entry.origin_stop_ref)
                .bind(&entry.destination_stop_ref)
                .bind(entry.departure_time)
                .bind(entry.departure_date)
                .bind(entry.arrival_time)
                .bind(&envelope.payload)
                .execute(pool)
                .await?;
            }
            IndexMutation::Node(mapping) => {
                sqlx::query(
                    "INSERT INTO plan_nodes (node_id, place_id, data) VALUES ($1, $2, $3) \
                     ON CONFLICT (node_id) DO UPDATE SET \
                       version = plan_nodes.version + 1, \
                       place_id = EXCLUDED.place_id, \
                       data = EXCLUDED.data, \
                       updated_at = now()",
                )
                .bind(&mapping.node_id)
                .bind(&mapping.place_id)
                .bind(&envelope.payload)
                .execute(pool)
                .await?;
            }
            IndexMutation::None => {}
        }
        Ok(())
    }
}

#[cfg(all(test, feature = "redis-impl"))]
mod tests {
    use super::*;
    use crate::plan_index::PlanIndex;

    #[tokio::test]
    #[ignore = "requires TEST_DATABASE_URL pointing at a disposable Postgres database"]
    async fn inbound_segment_event_survives_an_index_rebuild() {
        let database_url =
            std::env::var("TEST_DATABASE_URL").expect("TEST_DATABASE_URL is required");
        let pool = sqlx::postgres::PgPoolOptions::new()
            .max_connections(2)
            .connect(&database_url)
            .await
            .unwrap();
        let storage = rust_kit::storage::Storage::new(pool.clone());
        storage
            .migrate_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/migrations"))
            .await
            .unwrap();

        let segment_ref = format!("seg-{}", uuid::Uuid::now_v7());
        let node_id = format!("tnd-{}", uuid::Uuid::now_v7());
        let place_id = format!("plc-{}", uuid::Uuid::now_v7());

        let state = AppState::new(Some(pool.clone()), Arc::new(PlanIndex::new()));
        let node_event = EventEnvelope::try_new(
            "TransportNodeRegistered",
            messaging::now_rfc3339_utc(),
            messaging::correlation_id(),
            None::<String>,
            "place-network",
            serde_json::json!({ "nodeId": node_id, "placeId": place_id }),
        )
        .unwrap();
        let segment_event = EventEnvelope::try_new(
            "ServicePlanChanged",
            messaging::now_rfc3339_utc(),
            messaging::correlation_id(),
            None::<String>,
            "service-plan",
            serde_json::json!({
                "segmentRef": segment_ref,
                "scheduledServiceRef": "ss-rebuild-probe",
                "originStopRef": node_id,
                "destinationStopRef": node_id,
                "departureTime": "2026-07-12T08:00:00Z",
                "arrivalTime": "2026-07-12T09:00:00Z",
            }),
        )
        .unwrap();
        state.handle_inbound_event(&node_event).await.unwrap();
        state.handle_inbound_event(&segment_event).await.unwrap();

        // A fresh index, as after a pod restart, must find the segment again.
        let rebuilt = PlanIndex::new();
        crate::load_index_from_db(&pool, &rebuilt).await;
        let date = chrono::NaiveDate::from_ymd_opt(2026, 7, 12).unwrap();
        let candidates = rebuilt.candidates(&place_id, &place_id, date);
        assert!(
            candidates
                .iter()
                .any(|itin| itin.legs[0].service_segment_ref == segment_ref),
            "segment {segment_ref} must be searchable after the index is rebuilt from Postgres"
        );

        sqlx::query("DELETE FROM plan_segments WHERE segment_ref = $1")
            .bind(&segment_ref)
            .execute(&pool)
            .await
            .unwrap();
        sqlx::query("DELETE FROM plan_nodes WHERE node_id = $1")
            .bind(&node_id)
            .execute(&pool)
            .await
            .unwrap();
    }
}
