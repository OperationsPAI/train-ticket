//! Application wiring: connects domain logic to infrastructure adapters.

use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

use chrono::{DateTime, Duration, NaiveDate, TimeZone, Utc};
use serde_json::Value;
use sqlx::PgPool;

use crate::api::ItineraryJson;
use crate::domain::Itinerary;
use crate::plan_index::{IndexMutation, MinimumConnectionRule, PlanIndex, SegmentEntry};
use rust_kit::messaging::{self, EventEnvelope};
use rust_kit::storage::{OutboxAppender, SnapshotRepository, StorageError};

/// Central application state shared across handlers and the event subscriber.
pub struct AppState {
    pub plan_index: Arc<PlanIndex>,
    pool: Option<PgPool>,
    snapshots: Option<SnapshotRepository>,
    outbound_stream: String,
    /// Storage for deployments configured without PostgreSQL.
    mem_snapshots: RwLock<HashMap<String, Value>>,
}

impl AppState {
    pub fn new(
        pool: Option<PgPool>,
        plan_index: Arc<PlanIndex>,
        outbound_stream: impl Into<String>,
    ) -> Self {
        Self {
            plan_index,
            pool: pool.clone(),
            snapshots: Some(
                SnapshotRepository::new("itinerary_snapshots").expect("valid table name"),
            ),
            outbound_stream: outbound_stream.into(),
            mem_snapshots: RwLock::new(HashMap::new()),
        }
    }

    pub async fn save_itinerary(
        &self,
        itinerary_ref: &str,
        data: Value,
    ) -> Result<(), StorageError> {
        if let Some(pool) = &self.pool {
            sqlx::query("INSERT INTO itinerary_snapshots (id, version, data) VALUES ($1, 1, $2) ON CONFLICT (id) DO NOTHING")
                .bind(itinerary_ref).bind(data).execute(pool).await?;
        } else {
            self.mem_snapshots
                .write()
                .await
                .insert(itinerary_ref.to_string(), data);
        }
        Ok(())
    }

    /// Saves every snapshot of one search in a single statement. A search returns up to ten
    /// itineraries, and one round trip per itinerary put ten statements on the database for
    /// every search, which is what made the search path slow under a burst.
    pub async fn save_itineraries(
        &self,
        snapshots: &[(String, Value)],
    ) -> Result<(), StorageError> {
        if snapshots.is_empty() {
            return Ok(());
        }
        if let Some(pool) = &self.pool {
            let ids: Vec<String> = snapshots
                .iter()
                .map(|(reference, _)| reference.clone())
                .collect();
            let data: Vec<Value> = snapshots.iter().map(|(_, value)| value.clone()).collect();
            sqlx::query(
                "INSERT INTO itinerary_snapshots (id, version, data) \
                 SELECT id, 1, data FROM UNNEST($1::text[], $2::jsonb[]) AS rows(id, data) \
                 ON CONFLICT (id) DO NOTHING",
            )
            .bind(ids)
            .bind(data)
            .execute(pool)
            .await?;
        } else {
            let mut stored = self.mem_snapshots.write().await;
            for (reference, value) in snapshots {
                stored.insert(reference.clone(), value.clone());
            }
        }
        Ok(())
    }

    pub async fn get_itinerary(&self, itinerary_ref: &str) -> Result<Option<Value>, StorageError> {
        if let (Some(pool), Some(repo)) = (&self.pool, &self.snapshots) {
            let snap = repo.get::<Value>(pool, itinerary_ref).await?;
            return Ok(snap.map(|s| s.data));
        }
        Ok(self.mem_snapshots.read().await.get(itinerary_ref).cloned())
    }

    /// Load only the route/date frontier needed by one production search.
    ///
    /// The retained timetable is intentionally not rebuilt into every pod at
    /// startup: on the live data set that meant materializing hundreds of
    /// thousands of historical segments and nodes before the HTTP listener
    /// could bind. The request-local index keeps the existing route and MCT
    /// semantics while bounding memory by the selected search frontier.
    pub async fn search_candidates(
        &self,
        origin_ref: &str,
        destination_ref: &str,
        departure_date: NaiveDate,
        max_connections: u32,
        minimum_connection_minutes: u32,
        maximum_wait_minutes: u32,
    ) -> Result<Vec<Itinerary>, StorageError> {
        let database_index;
        let index = if let Some(pool) = &self.pool {
            database_index = load_search_index_from_db(
                pool,
                origin_ref,
                destination_ref,
                departure_date,
                max_connections,
                maximum_wait_minutes,
            )
            .await?;
            &database_index
        } else {
            self.plan_index.as_ref()
        };

        Ok(if max_connections > 0 {
            index.candidates_with_connections(
                origin_ref,
                destination_ref,
                departure_date,
                max_connections,
                minimum_connection_minutes,
                maximum_wait_minutes,
            )
        } else {
            index.candidates(origin_ref, destination_ref, departure_date)
        })
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
                OutboxAppender::append(&mut tx, &self.outbound_stream, &envelope).await?;
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
    pub async fn handle_inbound_event(&self, envelope: &EventEnvelope) -> Result<(), StorageError> {
        let mutation = if self.pool.is_some() {
            // PostgreSQL is the production search source. Parse the event on a
            // throwaway index so a long-running pod does not slowly rebuild the
            // entire retained timetable in memory again.
            PlanIndex::new().apply_event(&envelope.event_type, &envelope.payload)
        } else {
            self.plan_index
                .apply_event(&envelope.event_type, &envelope.payload)
        };
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
            IndexMutation::MinimumConnectionRulePublished(rule) => {
                sqlx::query(
                    "INSERT INTO plan_mct_rules (mct_rule_id, version, status, from_node_type, to_node_type, transfer_category, minimum_minutes, conditions, valid_from, valid_until, data) \
                     VALUES ($1, $2, 'PUBLISHED', $3, $4, $5, $6, $7, $8, $9, $10) \
                     ON CONFLICT (mct_rule_id) DO UPDATE SET \
                       version = EXCLUDED.version, \
                       status = EXCLUDED.status, \
                       from_node_type = EXCLUDED.from_node_type, \
                       to_node_type = EXCLUDED.to_node_type, \
                       transfer_category = EXCLUDED.transfer_category, \
                       minimum_minutes = EXCLUDED.minimum_minutes, \
                       conditions = EXCLUDED.conditions, \
                       valid_from = EXCLUDED.valid_from, \
                       valid_until = EXCLUDED.valid_until, \
                       data = EXCLUDED.data, \
                       updated_at = now() \
                     WHERE EXCLUDED.version > plan_mct_rules.version \
                        OR (EXCLUDED.version = plan_mct_rules.version AND plan_mct_rules.status <> 'RETIRED')",
                )
                .bind(&rule.rule_id)
                .bind(rule.version)
                .bind(&rule.from_node_type)
                .bind(&rule.to_node_type)
                .bind(&rule.transfer_category)
                .bind(i64::from(rule.minimum_minutes))
                .bind(&rule.conditions)
                .bind(rule.valid_from)
                .bind(rule.valid_until)
                .bind(&envelope.payload)
                .execute(pool)
                .await?;
            }
            IndexMutation::MinimumConnectionRuleRetired { rule_id, version } => {
                sqlx::query(
                    "INSERT INTO plan_mct_rules (mct_rule_id, version, status, data) \
                     VALUES ($1, $2, 'RETIRED', $3) \
                     ON CONFLICT (mct_rule_id) DO UPDATE SET \
                       version = EXCLUDED.version, \
                       status = EXCLUDED.status, \
                       data = EXCLUDED.data, \
                       updated_at = now() \
                     WHERE EXCLUDED.version >= plan_mct_rules.version",
                )
                .bind(rule_id)
                .bind(version)
                .bind(&envelope.payload)
                .execute(pool)
                .await?;
            }
            IndexMutation::None => {}
        }
        Ok(())
    }
}

async fn load_search_index_from_db(
    pool: &PgPool,
    origin_ref: &str,
    destination_ref: &str,
    departure_date: NaiveDate,
    max_connections: u32,
    maximum_wait_minutes: u32,
) -> Result<PlanIndex, StorageError> {
    type SegmentRow = (
        String,
        Option<String>,
        String,
        String,
        DateTime<Utc>,
        NaiveDate,
        DateTime<Utc>,
        String,
    );
    type MctRuleRow = (
        String,
        i64,
        String,
        Option<String>,
        Option<String>,
        Option<String>,
        Option<i64>,
        Option<Value>,
        Option<DateTime<Utc>>,
        Option<DateTime<Utc>>,
    );

    let index = PlanIndex::new();
    let mut frontier = vec![origin_ref.to_string()];
    let mut lower = Utc.from_utc_datetime(
        &departure_date
            .and_hms_opt(0, 0, 0)
            .expect("a valid date has a midnight"),
    );
    let mut upper = lower + Duration::days(1) - Duration::microseconds(1);
    let mut seen_segments = std::collections::HashSet::new();
    let mut stop_refs =
        std::collections::HashSet::from([origin_ref.to_string(), destination_ref.to_string()]);

    for _depth in 0..=max_connections {
        if frontier.is_empty() {
            break;
        }
        let rows: Vec<SegmentRow> = sqlx::query_as(
            r#"
            WITH requested(ref) AS (SELECT unnest($1::text[])),
            canonical(place_ref) AS (
              SELECT DISTINCT COALESCE(node.place_id,requested.ref)
              FROM requested LEFT JOIN plan_nodes node ON node.node_id=requested.ref
            ),
            matching_refs(ref) AS (
              SELECT place_ref FROM canonical
              UNION
              SELECT node.node_id FROM plan_nodes node JOIN canonical ON node.place_id=canonical.place_ref
            )
            SELECT segment.segment_ref,segment.scheduled_service_ref,
                   segment.origin_stop_ref,segment.destination_stop_ref,
                   segment.departure_time,segment.departure_date,segment.arrival_time,
                   COALESCE(destination.place_id,segment.destination_stop_ref)
            FROM plan_segments segment
            LEFT JOIN plan_nodes destination ON destination.node_id=segment.destination_stop_ref
            WHERE segment.departure_date BETWEEN $2 AND $3
              AND segment.departure_time >= $4 AND segment.departure_time <= $5
              AND segment.origin_stop_ref IN (SELECT ref FROM matching_refs)
            ORDER BY segment.departure_time,segment.segment_ref
            "#,
        )
        .bind(&frontier)
        .bind(lower.date_naive())
        .bind(upper.date_naive())
        .bind(lower)
        .bind(upper)
        .fetch_all(pool)
        .await?;

        let mut next_frontier = std::collections::HashSet::new();
        let mut next_lower: Option<DateTime<Utc>> = None;
        let mut next_upper: Option<DateTime<Utc>> = None;
        for (
            segment_ref,
            service_ref,
            origin,
            destination,
            departure,
            date,
            arrival,
            canonical_destination,
        ) in rows
        {
            if seen_segments.insert(segment_ref.clone()) {
                stop_refs.insert(origin.clone());
                stop_refs.insert(destination.clone());
                stop_refs.insert(canonical_destination.clone());
                index.upsert_segment(SegmentEntry {
                    segment_ref,
                    scheduled_service_ref: service_ref.unwrap_or_default(),
                    origin_stop_ref: origin,
                    destination_stop_ref: destination,
                    departure_time: departure,
                    departure_date: date,
                    arrival_time: arrival,
                });
                next_frontier.insert(canonical_destination);
                next_lower = Some(next_lower.map_or(arrival, |value| value.min(arrival)));
                let latest = arrival + Duration::minutes(i64::from(maximum_wait_minutes));
                next_upper = Some(next_upper.map_or(latest, |value| value.max(latest)));
            }
        }
        frontier = next_frontier.into_iter().collect();
        let (Some(new_lower), Some(new_upper)) = (next_lower, next_upper) else {
            break;
        };
        lower = new_lower;
        upper = new_upper;
    }

    let references: Vec<String> = stop_refs.into_iter().collect();
    let nodes: Vec<(String, String)> = sqlx::query_as(
        "SELECT node_id,place_id FROM plan_nodes WHERE node_id=ANY($1) OR place_id=ANY($1)",
    )
    .bind(&references)
    .fetch_all(pool)
    .await?;
    for (node_id, place_id) in nodes {
        index.upsert_node(node_id, place_id);
    }

    if max_connections > 0 {
        let rules: Vec<MctRuleRow> = sqlx::query_as(
            "SELECT mct_rule_id,version,status,from_node_type,to_node_type,transfer_category,minimum_minutes,conditions,valid_from,valid_until FROM plan_mct_rules",
        )
        .fetch_all(pool)
        .await?;
        for (
            rule_id,
            version,
            status,
            from_node_type,
            to_node_type,
            transfer_category,
            minimum_minutes,
            conditions,
            valid_from,
            valid_until,
        ) in rules
        {
            if status == "RETIRED" {
                index.retire_minimum_connection_rule(&rule_id, version);
                continue;
            }
            let (
                Some(from_node_type),
                Some(to_node_type),
                Some(transfer_category),
                Some(minimum_minutes),
                Some(valid_from),
            ) = (
                from_node_type,
                to_node_type,
                transfer_category,
                minimum_minutes,
                valid_from,
            )
            else {
                continue;
            };
            let Ok(minimum_minutes) = u32::try_from(minimum_minutes) else {
                continue;
            };
            index.upsert_minimum_connection_rule(MinimumConnectionRule {
                rule_id,
                version,
                from_node_type,
                to_node_type,
                transfer_category,
                minimum_minutes,
                conditions: conditions.unwrap_or_else(|| serde_json::json!({})),
                valid_from,
                valid_until,
            });
        }
    }

    Ok(index)
}

#[cfg(all(test, feature = "redis-impl"))]
mod tests {
    use super::*;
    use crate::adapters::messaging::ITINERARY_STREAM;
    use crate::plan_index::PlanIndex;

    #[tokio::test]
    #[ignore = "requires TEST_DATABASE_URL pointing at a disposable Postgres database"]
    async fn persisted_itineraries_do_not_accumulate_in_memory() {
        let pool = sqlx::postgres::PgPoolOptions::new()
            .connect(&std::env::var("TEST_DATABASE_URL").unwrap())
            .await
            .unwrap();
        rust_kit::storage::Storage::new(pool.clone())
            .migrate_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/migrations"))
            .await
            .unwrap();
        let state = AppState::new(
            Some(pool.clone()),
            Arc::new(PlanIndex::new()),
            ITINERARY_STREAM,
        );
        let id = format!("itin-{}", uuid::Uuid::now_v7());
        let data = serde_json::json!({"itineraryRef": id});
        state.save_itinerary(&id, data.clone()).await.unwrap();
        state.save_itinerary(&id, data.clone()).await.unwrap();
        assert!(state.mem_snapshots.read().await.is_empty());
        let restarted = AppState::new(
            Some(pool.clone()),
            Arc::new(PlanIndex::new()),
            ITINERARY_STREAM,
        );
        assert_eq!(restarted.get_itinerary(&id).await.unwrap(), Some(data));
        sqlx::query("DELETE FROM itinerary_snapshots WHERE id = $1")
            .bind(id)
            .execute(&pool)
            .await
            .unwrap();
    }

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

        let state = AppState::new(
            Some(pool.clone()),
            Arc::new(PlanIndex::new()),
            ITINERARY_STREAM,
        );
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

    #[tokio::test]
    #[ignore = "requires TEST_DATABASE_URL pointing at a disposable Postgres database"]
    async fn minimum_connection_rule_publication_and_retirement_survive_rebuilds() {
        let database_url =
            std::env::var("TEST_DATABASE_URL").expect("TEST_DATABASE_URL is required");
        let pool = sqlx::postgres::PgPoolOptions::new()
            .max_connections(2)
            .connect(&database_url)
            .await
            .unwrap();
        rust_kit::storage::Storage::new(pool.clone())
            .migrate_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/migrations"))
            .await
            .unwrap();

        let rule_id = format!("mct-{}", uuid::Uuid::now_v7());
        let state = AppState::new(
            Some(pool.clone()),
            Arc::new(PlanIndex::new()),
            ITINERARY_STREAM,
        );
        let published = EventEnvelope::try_new(
            "MctRulePublished",
            messaging::now_rfc3339_utc(),
            messaging::correlation_id(),
            None::<String>,
            "transfer-management",
            serde_json::json!({
                "mctRuleId": rule_id,
                "version": 3,
                "status": "PUBLISHED",
                "fromNodeType": "STATION",
                "toNodeType": "STATION",
                "transferCategory": "SAME_STATION",
                "minimumMinutes": 20,
                "conditions": {},
                "validFrom": "2026-07-01T00:00:00Z"
            }),
        )
        .unwrap();
        state.handle_inbound_event(&published).await.unwrap();

        let rebuilt = PlanIndex::new();
        crate::load_index_from_db(&pool, &rebuilt).await;
        assert_eq!(rebuilt.minimum_connection_rule_count(), 1);

        let retired = EventEnvelope::try_new(
            "MctRuleRetired",
            messaging::now_rfc3339_utc(),
            messaging::correlation_id(),
            Some(published.event_id.clone()),
            "transfer-management",
            serde_json::json!({
                "mctRuleId": rule_id,
                "version": 3,
                "status": "RETIRED"
            }),
        )
        .unwrap();
        state.handle_inbound_event(&retired).await.unwrap();

        let rebuilt_after_retirement = PlanIndex::new();
        crate::load_index_from_db(&pool, &rebuilt_after_retirement).await;
        assert_eq!(rebuilt_after_retirement.minimum_connection_rule_count(), 0);
        let status: String =
            sqlx::query_scalar("SELECT status FROM plan_mct_rules WHERE mct_rule_id = $1")
                .bind(&rule_id)
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(status, "RETIRED");

        sqlx::query("DELETE FROM plan_mct_rules WHERE mct_rule_id = $1")
            .bind(rule_id)
            .execute(&pool)
            .await
            .unwrap();
    }
}
