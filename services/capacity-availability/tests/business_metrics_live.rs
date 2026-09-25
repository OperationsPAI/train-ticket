//! The capacity gauges against a real `inventory_pool_snapshots` table.
//!
//! The in-crate test proves the fold is a pure function of durable rows. This
//! one proves the rows really are durable: a document is written, a second
//! service instance reads it and publishes the same level, which is the
//! restart case the contract requires.
//!
//! Marked `ignore` because it needs a disposable database:
//! `TEST_DATABASE_URL` plus `cargo test -- --ignored`.

use capacity_availability::adapters::storage::PostgresCapacityService;
use capacity_availability::metrics::{CapacityBusinessMetrics, CapacityUnitState};
use sqlx::postgres::{PgConnectOptions, PgPoolOptions};
use std::str::FromStr;

fn pool_document(segment_ref: &str, holds: serde_json::Value) -> serde_json::Value {
    serde_json::json!({
        "identity": {
            "poolId": format!("pool:{segment_ref}:SECOND_CLASS"),
            "scheduledServiceRef": "ss-live-1",
            "serviceDate": "2026-09-25",
            "seatClassOrCabinRef": "SECOND_CLASS",
            "sellableUnitTypeRef": "SEAT",
            "routeSegmentRef": segment_ref,
            "serviceSegmentRef": segment_ref,
            "stopSequenceVersion": "v1"
        },
        "capacityUnits": [],
        "physicalUnits": null,
        "capacityUnitCount": 10,
        "physicalUnitCount": 10,
        "holds": holds,
        "overbookingPolicy": {"maxOverbookingPct": 0.0, "noShowRate": 0.0, "safetyMarginPct": 0.0},
        "retiredIdempotencyKeys": []
    })
}

fn hold_document(
    hold_id: &str,
    state: &str,
    requested_at: u64,
    expires_at: u64,
) -> serde_json::Value {
    serde_json::json!({
        "holdId": hold_id,
        "inventoryPoolId": "pool:seg-live-1:SECOND_CLASS",
        "capacityUnitRef": "unit-1",
        "fromSeq": 0,
        "toSeq": 1,
        "idempotencyKey": format!("key-{hold_id}"),
        "requestedAt": requested_at,
        "expiresAt": expires_at,
        "state": state,
        "confirmedAt": null,
        "releasedAt": null,
        "expiredAt": null,
        "references": {
            "sourceContext": "journey-order",
            "orderRef": "ord-live-1",
            "segmentBookingRef": "sb-live-1",
            "travelerRef": "tvl-live-1",
            "reason": "live test"
        },
        "supplyEvidence": null
    })
}

#[tokio::test]
#[ignore = "requires TEST_DATABASE_URL pointing at a disposable Postgres database"]
async fn a_second_instance_publishes_the_same_level_from_the_durable_document() {
    let url = std::env::var("TEST_DATABASE_URL").expect("TEST_DATABASE_URL");
    let admin = PgPoolOptions::new()
        .max_connections(1)
        .connect(&url)
        .await
        .expect("connect");
    let schema = format!(
        "capacity_business_metrics_{}",
        uuid::Uuid::now_v7().simple()
    );
    sqlx::query(&format!("CREATE SCHEMA {schema}"))
        .execute(&admin)
        .await
        .expect("create schema");
    let pool = PgPoolOptions::new()
        .max_connections(2)
        .connect_with(
            PgConnectOptions::from_str(&url)
                .expect("parse url")
                .options([("search_path", schema.as_str())]),
        )
        .await
        .expect("connect with schema");
    sqlx::raw_sql(include_str!("../migrations/001_capacity_storage.sql"))
        .execute(&pool)
        .await
        .expect("migrate");

    // Far enough in the past that both holds are undisputedly live.
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .expect("clock")
        .as_millis() as u64;
    let document = pool_document(
        "seg-live-1",
        serde_json::json!([
            hold_document("hold-live-held", "Held", now - 60_000, now + 240_000),
            hold_document(
                "hold-live-confirmed",
                "Confirmed",
                now - 120_000,
                now + 180_000
            ),
        ]),
    );
    sqlx::query("INSERT INTO inventory_pool_snapshots(id, version, data) VALUES ($1, $2, $3)")
        .bind("pool:seg-live-1:SECOND_CLASS")
        .bind(1_i64)
        .bind(&document)
        .execute(&pool)
        .await
        .expect("insert pool document");

    let first = PostgresCapacityService::new(pool.clone()).expect("service");
    first.refresh_business_metrics().await.expect("refresh");
    let published = first.business_metrics().published();

    // A second service instance over the same durable state is a restart.
    let second = PostgresCapacityService::new(pool.clone()).expect("second service");
    second.refresh_business_metrics().await.expect("refresh");
    let republished: CapacityBusinessMetrics = second.business_metrics().clone();
    let republished = republished.published();
    assert_eq!(
        published.units, republished.units,
        "a restart must republish the same unit level"
    );
    // Ages are read from the wall clock, so a second refresh is a few
    // milliseconds older rather than different.
    assert_eq!(published.hold_age.len(), republished.hold_age.len());
    for (before, after) in published.hold_age.iter().zip(republished.hold_age.iter()) {
        assert_eq!(
            (before.state, before.travel_class),
            (after.state, after.travel_class)
        );
        assert!(
            (before.seconds - after.seconds).abs() < 5.0,
            "a restart changed the hold age: {before:?} vs {after:?}"
        );
    }
    assert!(
        published
            .units
            .iter()
            .any(|row| row.state == CapacityUnitState::Confirmed && row.units == 1),
        "the confirmed hold was not reconstructed: {:?}",
        published.units
    );
    assert!(
        published
            .units
            .iter()
            .any(|row| row.state == CapacityUnitState::Held && row.units == 1),
        "the live hold was not reconstructed: {:?}",
        published.units
    );
    assert!(
        published
            .units
            .iter()
            .any(|row| row.state == CapacityUnitState::Sellable && row.units == 8),
        "sellable units are capacity minus live holds: {:?}",
        published.units
    );

    let _ = sqlx::query(&format!("DROP SCHEMA {schema} CASCADE"))
        .execute(&admin)
        .await;
}
