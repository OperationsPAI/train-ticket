// ---------------------------------------------------------------------------
// HTTP API layer per docs/08-contracts/api/capacity-availability.md
// ---------------------------------------------------------------------------

#[cfg(feature = "redis-impl")]
use crate::adapters::storage::PostgresCapacityService;
use crate::application::{
    AppError, AvailabilitySnapshotResponse, CapacityService, CapacitySnapshotResponse,
    HoldCapacityRequest,
};

use axum::{
    Json, Router,
    extract::{Extension, Query, rejection::JsonRejection},
    http::{HeaderMap, StatusCode},
    routing::{get, post},
};
use serde::{Deserialize, Serialize};
use shared_kernel::RequestContext;
use std::sync::Arc;

pub fn router(service: Arc<CapacityService>) -> Router {
    Router::new()
        .route("/api/v1/availability-snapshots", get(query_availability))
        .route("/api/v1/capacity-holds", post(hold_capacity))
        .route("/api/v1/capacity/holds", post(hold_capacity))
        .route(
            "/api/v1/capacity/segments/{segment_ref}/snapshot",
            get(get_capacity_snapshot),
        )
        .route(
            "/api/v1/capacity-holds/{hold_id}/confirm",
            post(confirm_hold),
        )
        .route(
            "/api/v1/capacity-holds/{hold_id}/release",
            post(release_hold),
        )
        .route("/api/v1/capacity-holds/{hold_id}", get(get_hold))
        .layer(Extension(service))
}

#[cfg(feature = "redis-impl")]
pub fn postgres_router(service: Arc<PostgresCapacityService>) -> Router {
    Router::new()
        .route("/api/v1/availability-snapshots", get(query_availability_pg))
        .route("/api/v1/capacity-holds", post(hold_capacity_pg))
        .route("/api/v1/capacity/holds", post(hold_capacity_pg))
        .route(
            "/api/v1/capacity-holds/{hold_id}/confirm",
            post(confirm_hold_pg),
        )
        .route(
            "/api/v1/capacity-holds/{hold_id}/release",
            post(release_hold_pg),
        )
        .route("/api/v1/capacity-holds/{hold_id}", get(get_hold_pg))
        .layer(Extension(service))
}

// ---------------------------------------------------------------------------
// GET /api/v1/availability-snapshots
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AvailabilityQuery {
    pub scheduled_service_ref: Option<String>,
    pub segment_ref: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AvailabilitySnapshotJson {
    pub snapshot_id: String,
    pub snapshot_version: u64,
    pub scheduled_service_ref: String,
    pub segment_ref: String,
    pub captured_at: String,
    pub valid_until: String,
    pub sellable: bool,
    pub remaining_by_class: Vec<RemainingByClassJson>,
    pub total_units: usize,
    pub available_count: usize,
    pub status: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RemainingByClassJson {
    pub class_ref: String,
    pub total: usize,
    pub available: usize,
}

async fn query_availability(
    Extension(service): Extension<Arc<CapacityService>>,
    Extension(context): Extension<RequestContext>,
    Query(query): Query<AvailabilityQuery>,
) -> Result<Json<AvailabilitySnapshotJson>, AppErrorResponse> {
    let correlation_id = context.correlation_id().to_string();
    let ss_ref = query.scheduled_service_ref.unwrap_or_default();
    let seg_ref = query.segment_ref.unwrap_or_default();
    let result = service
        .query_availability(&ss_ref, &seg_ref)
        .map_err(|e| AppErrorResponse::new(e, correlation_id))?;
    Ok(Json(to_availability_json(result)))
}

fn to_availability_json(r: AvailabilitySnapshotResponse) -> AvailabilitySnapshotJson {
    AvailabilitySnapshotJson {
        snapshot_id: r.snapshot_id,
        snapshot_version: r.snapshot_version,
        scheduled_service_ref: r.scheduled_service_ref,
        segment_ref: r.segment_ref,
        captured_at: r.captured_at,
        valid_until: r.valid_until,
        sellable: r.sellable,
        remaining_by_class: r
            .remaining_by_class
            .into_iter()
            .map(|c| RemainingByClassJson {
                class_ref: c.class_ref,
                total: c.total,
                available: c.available,
            })
            .collect(),
        total_units: r.total_units,
        available_count: r.available_count,
        status: r.status,
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CapacitySnapshotJson {
    pub segment_ref: String,
    pub departure_date: String,
    pub total_capacity: u32,
    pub remaining_capacity: u32,
    pub physical_capacity: u32,
    pub hold_count: u32,
    pub confirmed_count: u32,
    pub utilization_pct: f64,
    pub snapshot_version: String,
    pub classes: Vec<crate::domain::ClassCapacity>,
}

async fn get_capacity_snapshot(
    Extension(service): Extension<Arc<CapacityService>>,
    Extension(context): Extension<RequestContext>,
    axum::extract::Path(segment_ref): axum::extract::Path<String>,
) -> Result<Json<CapacitySnapshotJson>, AppErrorResponse> {
    let correlation_id = context.correlation_id().to_string();
    let result = service
        .query_capacity_snapshot(&segment_ref)
        .map_err(|e| AppErrorResponse::new(e, correlation_id))?;
    Ok(Json(to_capacity_snapshot_json(result)))
}

fn to_capacity_snapshot_json(r: CapacitySnapshotResponse) -> CapacitySnapshotJson {
    CapacitySnapshotJson {
        segment_ref: r.segment_ref,
        departure_date: r.departure_date,
        total_capacity: r.total_capacity,
        remaining_capacity: r.remaining_capacity,
        physical_capacity: r.physical_capacity,
        hold_count: r.hold_count,
        confirmed_count: r.confirmed_count,
        utilization_pct: r.utilization_pct,
        snapshot_version: r.snapshot_version,
        classes: r.classes,
    }
}

// ---------------------------------------------------------------------------
// POST /api/v1/capacity-holds
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HoldCapacityJson {
    pub segment_ref: String,
    pub traveler_ref: String,
    pub class_ref: Option<String>,
    pub seat_class: Option<String>,
    pub quantity: usize,
    pub segment_booking_id: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HoldCapacityResponseJson {
    pub hold_id: String,
    pub segment_ref: String,
    pub status: String,
    pub held_until: String,
}

async fn hold_capacity(
    Extension(service): Extension<Arc<CapacityService>>,
    Extension(context): Extension<RequestContext>,
    headers: HeaderMap,
    body: Result<Json<HoldCapacityJson>, JsonRejection>,
) -> Result<(StatusCode, Json<HoldCapacityResponseJson>), AppErrorResponse> {
    let correlation_id = context.correlation_id().to_string();
    let idempotency_key = get_idempotency_key(&headers, &correlation_id)?;
    let Json(body) = body.map_err(|e| {
        AppErrorResponse::new(
            AppError::ValidationFailed(e.body_text()),
            correlation_id.clone(),
        )
    })?;

    let req = HoldCapacityRequest {
        segment_ref: body.segment_ref,
        traveler_ref: body.traveler_ref,
        class_ref: body
            .class_ref
            .or(body.seat_class)
            .unwrap_or_else(|| "standard".to_string()),
        quantity: body.quantity,
        segment_booking_id: body.segment_booking_id,
    };

    let result = service
        .hold_capacity(req, &idempotency_key, &correlation_id)
        .map_err(|e| AppErrorResponse::new(e, correlation_id.clone()))?;
    Ok((
        StatusCode::CREATED,
        Json(HoldCapacityResponseJson {
            hold_id: result.hold_id,
            segment_ref: result.segment_ref,
            status: result.status,
            held_until: result.held_until,
        }),
    ))
}

// ---------------------------------------------------------------------------
// POST /api/v1/capacity-holds/{holdId}/confirm
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConfirmHoldResponseJson {
    pub hold_id: String,
    pub status: String,
}

async fn confirm_hold(
    Extension(service): Extension<Arc<CapacityService>>,
    Extension(context): Extension<RequestContext>,
    headers: HeaderMap,
    axum::extract::Path(hold_id): axum::extract::Path<String>,
) -> Result<Json<ConfirmHoldResponseJson>, AppErrorResponse> {
    let correlation_id = context.correlation_id().to_string();
    let idempotency_key = get_idempotency_key(&headers, &correlation_id)?;
    let result = service
        .confirm_hold(&hold_id, &idempotency_key, &correlation_id)
        .map_err(|e| AppErrorResponse::new(e, correlation_id.clone()))?;
    Ok(Json(ConfirmHoldResponseJson {
        hold_id: result.hold_id,
        status: result.status,
    }))
}

// ---------------------------------------------------------------------------
// POST /api/v1/capacity-holds/{holdId}/release
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReleaseHoldResponseJson {
    pub hold_id: String,
    pub status: String,
}

async fn release_hold(
    Extension(service): Extension<Arc<CapacityService>>,
    Extension(context): Extension<RequestContext>,
    headers: HeaderMap,
    axum::extract::Path(hold_id): axum::extract::Path<String>,
) -> Result<Json<ReleaseHoldResponseJson>, AppErrorResponse> {
    let correlation_id = context.correlation_id().to_string();
    let idempotency_key = get_idempotency_key(&headers, &correlation_id)?;
    let result = service
        .release_hold(&hold_id, &idempotency_key, &correlation_id)
        .map_err(|e| AppErrorResponse::new(e, correlation_id.clone()))?;
    Ok(Json(ReleaseHoldResponseJson {
        hold_id: result.hold_id,
        status: result.status,
    }))
}

// ---------------------------------------------------------------------------
// GET /api/v1/capacity-holds/{holdId}
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GetHoldResponseJson {
    pub hold_id: String,
    pub segment_ref: String,
    pub status: String,
    pub held_until: String,
    pub requested_at: String,
    pub traveler_ref: Option<String>,
    /// The unit held -- a seat number such as "09D".
    ///
    /// This was previously serialised as `classRef`, which is a different
    /// concept (a fare/seat class like "standard", and empty on these holds), so
    /// anything reading `classRef` off this endpoint got a seat number.
    pub capacity_unit_ref: String,
    /// Station interval the hold covers.
    ///
    /// Exposed because seat-assignment cannot otherwise match a CapacityReleased
    /// event back to its allocation: FindSeatAllocationsByCapacityRecovery keys
    /// on (capacityHoldId, capacityUnitRef, fromSeq, toSeq) and all four must
    /// agree. Without these a caller has no way to record an allocation the
    /// eventual release can find, and the release matches nothing -- silently,
    /// because that lookup returning empty is not an error.
    pub interval: StationIntervalJson,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StationIntervalJson {
    pub from_seq: u32,
    pub to_seq: u32,
}

async fn get_hold(
    Extension(service): Extension<Arc<CapacityService>>,
    Extension(context): Extension<RequestContext>,
    axum::extract::Path(hold_id): axum::extract::Path<String>,
) -> Result<Json<GetHoldResponseJson>, AppErrorResponse> {
    let correlation_id = context.correlation_id().to_string();
    let result = service
        .get_hold(&hold_id)
        .map_err(|e| AppErrorResponse::new(e, correlation_id))?;
    Ok(Json(GetHoldResponseJson {
        hold_id: result.hold_id,
        segment_ref: result.segment_ref,
        status: result.status,
        held_until: result.held_until,
        requested_at: result.requested_at,
        traveler_ref: result.traveler_ref,
        capacity_unit_ref: result.capacity_unit_ref,
        interval: StationIntervalJson {
            from_seq: result.from_seq,
            to_seq: result.to_seq,
        },
    }))
}

#[cfg(feature = "redis-impl")]
async fn query_availability_pg(
    Extension(service): Extension<Arc<PostgresCapacityService>>,
    Extension(context): Extension<RequestContext>,
    Query(query): Query<AvailabilityQuery>,
) -> Result<Json<AvailabilitySnapshotJson>, AppErrorResponse> {
    let correlation_id = context.correlation_id().to_string();
    let result = service
        .query_availability(
            &query.scheduled_service_ref.unwrap_or_default(),
            &query.segment_ref.unwrap_or_default(),
        )
        .await
        .map_err(|e| AppErrorResponse::new(e, correlation_id))?;
    Ok(Json(to_availability_json(result)))
}

#[cfg(feature = "redis-impl")]
async fn hold_capacity_pg(
    Extension(service): Extension<Arc<PostgresCapacityService>>,
    Extension(context): Extension<RequestContext>,
    headers: HeaderMap,
    body: Result<Json<HoldCapacityJson>, JsonRejection>,
) -> Result<(StatusCode, Json<HoldCapacityResponseJson>), AppErrorResponse> {
    let correlation_id = context.correlation_id().to_string();
    let idempotency_key = get_idempotency_key(&headers, &correlation_id)?;
    let Json(body) = body.map_err(|e| {
        AppErrorResponse::new(
            AppError::ValidationFailed(e.body_text()),
            correlation_id.clone(),
        )
    })?;
    let result = service
        .hold_capacity(
            HoldCapacityRequest {
                segment_ref: body.segment_ref,
                traveler_ref: body.traveler_ref,
                class_ref: body
                    .class_ref
                    .or(body.seat_class)
                    .unwrap_or_else(|| "standard".to_string()),
                quantity: body.quantity,
                segment_booking_id: body.segment_booking_id,
            },
            &idempotency_key,
            &correlation_id,
        )
        .await
        .map_err(|e| AppErrorResponse::new(e, correlation_id.clone()))?;
    Ok((
        StatusCode::CREATED,
        Json(HoldCapacityResponseJson {
            hold_id: result.hold_id,
            segment_ref: result.segment_ref,
            status: result.status,
            held_until: result.held_until,
        }),
    ))
}

#[cfg(feature = "redis-impl")]
async fn confirm_hold_pg(
    Extension(service): Extension<Arc<PostgresCapacityService>>,
    Extension(context): Extension<RequestContext>,
    headers: HeaderMap,
    axum::extract::Path(hold_id): axum::extract::Path<String>,
) -> Result<Json<ConfirmHoldResponseJson>, AppErrorResponse> {
    let correlation_id = context.correlation_id().to_string();
    let idempotency_key = get_idempotency_key(&headers, &correlation_id)?;
    let result = service
        .confirm_hold(&hold_id, &idempotency_key, &correlation_id)
        .await
        .map_err(|e| AppErrorResponse::new(e, correlation_id.clone()))?;
    Ok(Json(ConfirmHoldResponseJson {
        hold_id: result.hold_id,
        status: result.status,
    }))
}

#[cfg(feature = "redis-impl")]
async fn release_hold_pg(
    Extension(service): Extension<Arc<PostgresCapacityService>>,
    Extension(context): Extension<RequestContext>,
    headers: HeaderMap,
    axum::extract::Path(hold_id): axum::extract::Path<String>,
) -> Result<Json<ReleaseHoldResponseJson>, AppErrorResponse> {
    let correlation_id = context.correlation_id().to_string();
    let idempotency_key = get_idempotency_key(&headers, &correlation_id)?;
    let result = service
        .release_hold(&hold_id, &idempotency_key, &correlation_id)
        .await
        .map_err(|e| AppErrorResponse::new(e, correlation_id.clone()))?;
    Ok(Json(ReleaseHoldResponseJson {
        hold_id: result.hold_id,
        status: result.status,
    }))
}

#[cfg(feature = "redis-impl")]
async fn get_hold_pg(
    Extension(service): Extension<Arc<PostgresCapacityService>>,
    Extension(context): Extension<RequestContext>,
    axum::extract::Path(hold_id): axum::extract::Path<String>,
) -> Result<Json<GetHoldResponseJson>, AppErrorResponse> {
    let correlation_id = context.correlation_id().to_string();
    let result = service
        .get_hold(&hold_id)
        .await
        .map_err(|e| AppErrorResponse::new(e, correlation_id))?;
    Ok(Json(GetHoldResponseJson {
        hold_id: result.hold_id,
        segment_ref: result.segment_ref,
        status: result.status,
        held_until: result.held_until,
        requested_at: result.requested_at,
        traveler_ref: result.traveler_ref,
        capacity_unit_ref: result.capacity_unit_ref,
        interval: StationIntervalJson {
            from_seq: result.from_seq,
            to_seq: result.to_seq,
        },
    }))
}

// ---------------------------------------------------------------------------
// Error handling
// ---------------------------------------------------------------------------

pub type AppErrorResponse = rust_kit::http::AppErrorResponse<AppError>;

impl rust_kit::http::ContractErrorStatus for AppError {
    fn status_code(&self) -> u16 {
        AppError::status_code(self)
    }

    fn code(&self) -> &str {
        AppError::code(self)
    }

    fn message(&self) -> &str {
        AppError::message(self)
    }
}

// ---------------------------------------------------------------------------
// Header helpers
// ---------------------------------------------------------------------------

fn get_idempotency_key(
    headers: &HeaderMap,
    correlation_id: &str,
) -> Result<String, AppErrorResponse> {
    rust_kit::idempotency::require_idempotency_key(headers).map_err(|error| {
        let message = match error {
            rust_kit::idempotency::IdempotencyError::Missing => {
                "Idempotency-Key header is required on state-changing POST"
            }
            rust_kit::idempotency::IdempotencyError::InvalidUuidV7 => {
                "Idempotency-Key must be a valid UUID v7"
            }
            rust_kit::idempotency::IdempotencyError::Reused => {
                "Idempotency-Key was reused with a different request body"
            }
        };
        AppErrorResponse::new(
            AppError::ValidationFailed(message.to_string()),
            correlation_id.to_string(),
        )
    })
}
