//! Reconstruction of the capacity business levels from durable documents.
//!
//! The aggregate the request path mutates lives in memory, so a restart would
//! publish zero for a run that is not zero. This reads `inventory_pool_snapshots`
//! -- the durable document each pool is written as, holds included -- and the
//! fold in [`crate::metrics`] turns it into the published levels.
//!
//! `capacity_hold_snapshots` is deliberately not the source. It keeps one row
//! per hold ever created and is never pruned, so aggregating it on a timer would
//! scan a table that only grows. The pool document has one row per pool and
//! already carries the holds that block it, which is the same state the request
//! path reads back after a restart.

use super::*;
use crate::metrics::{CapacityBusinessMetrics, DurableHoldFact, DurablePoolFact, SnapshotFold};
use futures_util::TryStreamExt;
use serde::Deserialize;
use std::time::Duration;

/// Mirrors `PRODUCER`; the gauge belongs to this service, not to a consumer.
pub const SERVICE_NAME: &str = "capacity-availability";

/// How often the levels are rebuilt when nothing overrides it.
///
/// The rebuild must read every pool document, holds inline, to compute the
/// sellable part of the level. On this cluster that is seven hundred thousand
/// documents and about five seconds of work, so the cadence is set against the
/// read rather than against the hold lifetime: five minutes of staleness is
/// well inside the resolution a capacity level is read at.
const DEFAULT_REFRESH_INTERVAL: Duration = Duration::from_secs(300);

/// The rebuild cadence, overridable from the environment so the chart owns it.
/// A value below ten seconds is ignored rather than honoured: it would put the
/// full-table read on top of the request path continuously.
fn refresh_interval() -> Duration {
    std::env::var("CAPACITY_METRICS_REFRESH_MS")
        .ok()
        .and_then(|value| value.trim().parse::<u64>().ok())
        .filter(|millis| *millis >= 10_000)
        .map(Duration::from_millis)
        .unwrap_or(DEFAULT_REFRESH_INTERVAL)
}

const POOL_FACTS_SQL: &str = r#"
SELECT
  id,
  coalesce(data #>> '{identity,seatClassOrCabinRef}', '') AS class_ref,
  coalesce(data #>> '{identity,sellableUnitTypeRef}', '') AS sellable_unit_type_ref,
  CASE
    WHEN data ->> 'physicalUnitCount' ~ '^[0-9]+$' THEN (data ->> 'physicalUnitCount')::bigint
    WHEN data ->> 'capacityUnitCount' ~ '^[0-9]+$' THEN (data ->> 'capacityUnitCount')::bigint
    ELSE 0
  END AS physical_unit_count,
  CASE
    WHEN data #>> '{overbookingPolicy,maxOverbookingPct}' ~ '^[0-9]+(\.[0-9]+)?$'
      THEN (data #>> '{overbookingPolicy,maxOverbookingPct}')::float8
    ELSE 0
  END AS max_overbooking_pct,
  coalesce(data -> 'holds', '[]'::jsonb) AS holds
FROM inventory_pool_snapshots
"#;

/// The durable hold projection. Only the fields the gauges need are decoded, so
/// a field added elsewhere in the document cannot change a label.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct HoldRow {
    hold_id: String,
    state: String,
    requested_at: u64,
    expires_at: u64,
    #[serde(default)]
    confirmed_at: Option<u64>,
    #[serde(default)]
    supply_evidence: Option<SupplyEvidenceRow>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SupplyEvidenceRow {
    accepted_class_ref: String,
    occupancy_class_ref: String,
}

type PoolFactRow = (String, String, String, i64, f64, Value);

impl PostgresCapacityService {
    /// Rebuild both gauges from the durable pool documents and publish them.
    ///
    /// The rows are folded as they arrive rather than collected first. Every
    /// pool document carries its holds inline and the table is seven gigabytes
    /// on this cluster, so `fetch_all` held the whole expansion in memory and
    /// the pod was OOM-killed at its four gigabyte limit about a minute after
    /// starting. The fold state is bounded by the number of label
    /// combinations, so streaming keeps a refresh at constant memory no matter
    /// how many pools exist.
    pub async fn refresh_business_metrics(&self) -> Result<(), sqlx::Error> {
        let now = now_millis();
        let mut fold = SnapshotFold::new();
        let mut rows = sqlx::query_as::<_, PoolFactRow>(POOL_FACTS_SQL).fetch(self.pool());
        while let Some(row) = rows.try_next().await? {
            let (
                pool_id,
                class_ref,
                sellable_unit_type_ref,
                physical_unit_count,
                max_overbooking_pct,
                holds,
            ) = row;
            // A document that cannot be decoded is skipped rather than failing
            // the refresh: one malformed pool must not blank the level for
            // every other pool.
            let holds: Vec<HoldRow> = serde_json::from_value(holds).unwrap_or_default();
            fold.absorb(
                &DurablePoolFact {
                    pool_id,
                    class_ref,
                    sellable_unit_type_ref,
                    physical_unit_count: u64::try_from(physical_unit_count).unwrap_or(0),
                    max_overbooking_pct,
                    holds: holds
                        .into_iter()
                        .map(|hold| DurableHoldFact {
                            hold_id: hold.hold_id,
                            state: hold.state,
                            requested_at_millis: hold.requested_at,
                            expires_at_millis: hold.expires_at,
                            confirmed_at_millis: hold.confirmed_at,
                            accepted_class_ref: hold
                                .supply_evidence
                                .as_ref()
                                .map(|evidence| evidence.accepted_class_ref.clone()),
                            occupancy_class_ref: hold
                                .supply_evidence
                                .as_ref()
                                .map(|evidence| evidence.occupancy_class_ref.clone()),
                        })
                        .collect(),
                },
                now,
            );
        }

        self.business_metrics.record(fold.finish());
        Ok(())
    }

    /// Refresh on a timer. A failed refresh keeps the previous level rather than
    /// publishing zero: an unreadable table is unknown, not empty.
    pub fn spawn_business_metrics_refresh(self: std::sync::Arc<Self>) {
        let interval = refresh_interval();
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(interval);
            loop {
                ticker.tick().await;
                if let Err(error) = self.refresh_business_metrics().await {
                    log::warn!("capacity business metric refresh failed: {error}");
                }
            }
        });
    }
}

/// The recorder a service instance publishes through. Registering against the
/// global meter is a no-op when metric export is not configured.
pub(super) fn business_metrics() -> CapacityBusinessMetrics {
    CapacityBusinessMetrics::register(&rust_kit::metrics::meter(SERVICE_NAME))
}
