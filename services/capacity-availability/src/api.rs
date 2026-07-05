// ---------------------------------------------------------------------------
// HTTP API layer per docs/08-contracts/api/capacity-availability.md
// ---------------------------------------------------------------------------

use crate::application::{
    AppError, AvailabilitySnapshotResponse, CapacityService, HoldCapacityRequest,
};

use axum::{
    Json, Router,
    extract::{Extension, Query, rejection::JsonRejection},
    http::StatusCode,
    response::IntoResponse,
    routing::{get, post},
};
use serde::{Deserialize, Serialize};
use serde_json::json;
use shared_kernel::RequestContext;
use std::sync::Arc;

pub fn router(service: Arc<CapacityService>) -> Router {
    Router::new()
        .route("/api/v1/availability-snapshots", get(query_availability))
        .route("/api/v1/capacity-holds", post(hold_capacity))
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
        .map_err(|e| AppErrorResponse(e, correlation_id))?;
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

// ---------------------------------------------------------------------------
// POST /api/v1/capacity-holds
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HoldCapacityJson {
    pub segment_ref: String,
    pub traveler_ref: String,
    pub class_ref: String,
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
    headers: axum::http::HeaderMap,
    body: Result<Json<HoldCapacityJson>, JsonRejection>,
) -> Result<(StatusCode, Json<HoldCapacityResponseJson>), AppErrorResponse> {
    let correlation_id = context.correlation_id().to_string();
    let idempotency_key = get_idempotency_key(&headers, &correlation_id)?;
    let Json(body) = body.map_err(|e| {
        AppErrorResponse(
            AppError::ValidationFailed(e.body_text()),
            correlation_id.clone(),
        )
    })?;

    let req = HoldCapacityRequest {
        segment_ref: body.segment_ref,
        traveler_ref: body.traveler_ref,
        class_ref: body.class_ref,
        quantity: body.quantity,
        segment_booking_id: body.segment_booking_id,
    };

    let result = service
        .hold_capacity(req, &idempotency_key, &correlation_id)
        .map_err(|e| AppErrorResponse(e, correlation_id.clone()))?;
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
    headers: axum::http::HeaderMap,
    axum::extract::Path(hold_id): axum::extract::Path<String>,
) -> Result<Json<ConfirmHoldResponseJson>, AppErrorResponse> {
    let correlation_id = context.correlation_id().to_string();
    let idempotency_key = get_idempotency_key(&headers, &correlation_id)?;
    let result = service
        .confirm_hold(&hold_id, &idempotency_key, &correlation_id)
        .map_err(|e| AppErrorResponse(e, correlation_id.clone()))?;
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
    headers: axum::http::HeaderMap,
    axum::extract::Path(hold_id): axum::extract::Path<String>,
) -> Result<Json<ReleaseHoldResponseJson>, AppErrorResponse> {
    let correlation_id = context.correlation_id().to_string();
    let idempotency_key = get_idempotency_key(&headers, &correlation_id)?;
    let result = service
        .release_hold(&hold_id, &idempotency_key, &correlation_id)
        .map_err(|e| AppErrorResponse(e, correlation_id.clone()))?;
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
    pub class_ref: String,
}

async fn get_hold(
    Extension(service): Extension<Arc<CapacityService>>,
    Extension(context): Extension<RequestContext>,
    axum::extract::Path(hold_id): axum::extract::Path<String>,
) -> Result<Json<GetHoldResponseJson>, AppErrorResponse> {
    let correlation_id = context.correlation_id().to_string();
    let result = service
        .get_hold(&hold_id)
        .map_err(|e| AppErrorResponse(e, correlation_id))?;
    Ok(Json(GetHoldResponseJson {
        hold_id: result.hold_id,
        segment_ref: result.segment_ref,
        status: result.status,
        held_until: result.held_until,
        requested_at: result.requested_at,
        traveler_ref: result.traveler_ref,
        class_ref: result.class_ref,
    }))
}

// ---------------------------------------------------------------------------
// Error handling
// ---------------------------------------------------------------------------

pub struct AppErrorResponse(pub AppError, pub String); // (error, correlation_id)

impl IntoResponse for AppErrorResponse {
    fn into_response(self) -> axum::response::Response {
        let status_code =
            StatusCode::from_u16(self.0.status_code()).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
        let body = json!({
            "code": self.0.code(),
            "message": self.0.message(),
            "correlationId": self.1,
            "details": {}
        });
        (status_code, Json(body)).into_response()
    }
}

// ---------------------------------------------------------------------------
// Header helpers
// ---------------------------------------------------------------------------

const IDEMPOTENCY_KEY_HEADER: &str = "idempotency-key";
/// Validate that a string looks like a UUID v7 (hex with hyphens, version digit 7).
fn is_uuid_v7(s: &str) -> bool {
    let bytes = s.as_bytes();
    if bytes.len() != 36 {
        return false;
    }
    if bytes[8] != b'-' || bytes[13] != b'-' || bytes[18] != b'-' || bytes[23] != b'-' {
        return false;
    }
    // Version nibble at position 14 must be '7'
    if bytes[14] != b'7' {
        return false;
    }
    // Check all hex chars
    for (i, &b) in bytes.iter().enumerate() {
        if i == 8 || i == 13 || i == 18 || i == 23 {
            continue;
        }
        if !b.is_ascii_hexdigit() {
            return false;
        }
    }
    true
}

fn get_idempotency_key(
    headers: &axum::http::HeaderMap,
    correlation_id: &str,
) -> Result<String, AppErrorResponse> {
    let key = headers
        .get(IDEMPOTENCY_KEY_HEADER)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .ok_or_else(|| {
            AppErrorResponse(
                AppError::ValidationFailed(
                    "Idempotency-Key header is required on state-changing POST".into(),
                ),
                correlation_id.to_string(),
            )
        })?;

    // Validate UUID v7 format per api/README.md
    if !is_uuid_v7(&key) {
        return Err(AppErrorResponse(
            AppError::ValidationFailed("Idempotency-Key must be a valid UUID v7".into()),
            correlation_id.to_string(),
        ));
    }

    Ok(key)
}
