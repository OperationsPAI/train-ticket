use crate::*;
use async_trait::async_trait;
use axum::{
    Json,
    extract::{Extension, Path, Query, State, rejection::JsonRejection},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use rust_kit::{http as kit_http, idempotency as kit_idempotency};
use serde::Deserialize;
use serde_json::json;
use shared_kernel::RequestContext;
use std::sync::Arc;

pub struct ApiState<S: SeatAssignmentApi + 'static> {
    pub(crate) service: Arc<S>,
}
impl<S: SeatAssignmentApi + 'static> Clone for ApiState<S> {
    fn clone(&self) -> Self {
        Self {
            service: self.service.clone(),
        }
    }
}
#[async_trait]
pub trait SeatAssignmentApi: Send + Sync {
    async fn create_seat_map(
        &self,
        c: CreateSeatMapCommand,
        key: String,
        corr: String,
    ) -> Result<SeatMap, SeatAssignmentError>;
    async fn publish_seat_map(
        &self,
        id: String,
        c: PublishSeatMapCommand,
        key: String,
        corr: String,
    ) -> Result<SeatMap, SeatAssignmentError>;
    async fn retire_seat_map(
        &self,
        id: String,
        c: RetireSeatMapCommand,
        key: String,
        corr: String,
    ) -> Result<SeatMap, SeatAssignmentError>;
    async fn mark_unavailable(
        &self,
        id: String,
        su: String,
        c: MarkUnavailableCommand,
        key: String,
        corr: String,
    ) -> Result<SeatMap, SeatAssignmentError>;
    async fn reopen(
        &self,
        id: String,
        su: String,
        c: ReopenSeatUnitCommand,
        key: String,
        corr: String,
    ) -> Result<SeatMap, SeatAssignmentError>;
    async fn get_seat_map(&self, id: String) -> Result<SeatMap, SeatAssignmentError>;
    async fn list_seat_maps(
        &self,
        scheduled: String,
        date: String,
        status: Option<SeatMapStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<PaginatedSeatMaps, SeatAssignmentError>;
    async fn allocate(
        &self,
        c: AllocateSeatCommand,
        key: String,
        corr: String,
    ) -> Result<AllocateSeatResponse, SeatAssignmentError>;
    async fn get_allocation(&self, id: String) -> Result<SeatAllocation, SeatAssignmentError>;
    async fn list_allocations(
        &self,
        sb: String,
        status: Option<AllocationStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<PaginatedSeatAllocations, SeatAssignmentError>;
}
fn key(headers: &HeaderMap) -> Result<String, kit_idempotency::IdempotencyError> {
    kit_idempotency::require_idempotency_key(headers)
}
fn validation(corr: String, msg: impl Into<String>) -> Response {
    kit_http::error_response(
        StatusCode::BAD_REQUEST,
        "VALIDATION_FAILED",
        msg.into(),
        corr,
        Some(json!({"domainCode":"VALIDATION_FAILED"})),
    )
}
fn idem_err(e: kit_idempotency::IdempotencyError, corr: String) -> Response {
    match e {
        kit_idempotency::IdempotencyError::Reused => api_error_response(
            SeatAssignmentError::IdempotencyKeyReused(
                "Idempotency-Key was reused with a different request body".into(),
            ),
            corr,
        ),
        _ => validation(
            corr,
            "Idempotency-Key header is required and must be a valid UUID v7",
        ),
    }
}
pub(crate) fn api_error_response(e: SeatAssignmentError, corr: String) -> Response {
    kit_http::error_response(
        error_status(&e),
        e.code(),
        e.message().to_string(),
        corr,
        Some(json!({"domainCode":e.code()})),
    )
}
fn error_status(e: &SeatAssignmentError) -> StatusCode {
    match e {
        SeatAssignmentError::ValidationFailed(_) => StatusCode::BAD_REQUEST,
        SeatAssignmentError::NotFound(_) => StatusCode::NOT_FOUND,
        SeatAssignmentError::Conflict(_) => StatusCode::CONFLICT,
        SeatAssignmentError::IdempotencyKeyReused(_) => StatusCode::UNPROCESSABLE_ENTITY,
        SeatAssignmentError::PreconditionFailed(_) => StatusCode::PRECONDITION_FAILED,
        SeatAssignmentError::DomainRuleViolation(_) => StatusCode::UNPROCESSABLE_ENTITY,
        SeatAssignmentError::Unavailable(_) => StatusCode::SERVICE_UNAVAILABLE,
        SeatAssignmentError::Internal(_) => StatusCode::INTERNAL_SERVER_ERROR,
    }
}

pub(crate) async fn create_map<S: SeatAssignmentApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    headers: HeaderMap,
    body: Result<Json<CreateSeatMapCommand>, JsonRejection>,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    let k = match key(&headers) {
        Ok(k) => k,
        Err(e) => return idem_err(e, corr),
    };
    let Json(cmd) = match body {
        Ok(b) => b,
        Err(e) => return validation(corr, e.body_text()),
    };
    match st.service.create_seat_map(cmd, k, corr.clone()).await {
        Ok(r) => (StatusCode::CREATED, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn publish_map<S: SeatAssignmentApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    headers: HeaderMap,
    Path(id): Path<String>,
    body: Result<Json<PublishSeatMapCommand>, JsonRejection>,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    let k = match key(&headers) {
        Ok(k) => k,
        Err(e) => return idem_err(e, corr),
    };
    let Json(cmd) = match body {
        Ok(b) => b,
        Err(e) => return validation(corr, e.body_text()),
    };
    match st.service.publish_seat_map(id, cmd, k, corr.clone()).await {
        Ok(r) => (StatusCode::OK, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn retire_map<S: SeatAssignmentApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    headers: HeaderMap,
    Path(id): Path<String>,
    body: Result<Json<RetireSeatMapCommand>, JsonRejection>,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    let k = match key(&headers) {
        Ok(k) => k,
        Err(e) => return idem_err(e, corr),
    };
    let Json(cmd) = match body {
        Ok(b) => b,
        Err(e) => return validation(corr, e.body_text()),
    };
    match st.service.retire_seat_map(id, cmd, k, corr.clone()).await {
        Ok(r) => (StatusCode::OK, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn mark_unavailable<S: SeatAssignmentApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    headers: HeaderMap,
    Path((id, su)): Path<(String, String)>,
    body: Result<Json<MarkUnavailableCommand>, JsonRejection>,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    let k = match key(&headers) {
        Ok(k) => k,
        Err(e) => return idem_err(e, corr),
    };
    let Json(cmd) = match body {
        Ok(b) => b,
        Err(e) => return validation(corr, e.body_text()),
    };
    match st
        .service
        .mark_unavailable(id, su, cmd, k, corr.clone())
        .await
    {
        Ok(r) => (StatusCode::OK, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn reopen<S: SeatAssignmentApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    headers: HeaderMap,
    Path((id, su)): Path<(String, String)>,
    body: Result<Json<ReopenSeatUnitCommand>, JsonRejection>,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    let k = match key(&headers) {
        Ok(k) => k,
        Err(e) => return idem_err(e, corr),
    };
    let Json(cmd) = match body {
        Ok(b) => b,
        Err(e) => return validation(corr, e.body_text()),
    };
    match st.service.reopen(id, su, cmd, k, corr.clone()).await {
        Ok(r) => (StatusCode::OK, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn get_map<S: SeatAssignmentApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    Path(id): Path<String>,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    match st.service.get_seat_map(id).await {
        Ok(r) => (StatusCode::OK, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MapQuery {
    scheduled_service_ref: String,
    service_date: String,
    status: Option<SeatMapStatus>,
    limit: Option<usize>,
    offset: Option<usize>,
}
pub(crate) async fn list_maps<S: SeatAssignmentApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    Query(q): Query<MapQuery>,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    match st
        .service
        .list_seat_maps(
            q.scheduled_service_ref,
            q.service_date,
            q.status,
            q.limit.unwrap_or(20).min(100),
            q.offset.unwrap_or(0),
        )
        .await
    {
        Ok(r) => (StatusCode::OK, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn allocate<S: SeatAssignmentApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    headers: HeaderMap,
    body: Result<Json<AllocateSeatCommand>, JsonRejection>,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    let k = match key(&headers) {
        Ok(k) => k,
        Err(e) => return idem_err(e, corr),
    };
    let Json(cmd) = match body {
        Ok(b) => b,
        Err(e) => return validation(corr, e.body_text()),
    };
    match st.service.allocate(cmd, k, corr.clone()).await {
        Ok(r) => (StatusCode::CREATED, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn get_allocation<S: SeatAssignmentApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    Path(id): Path<String>,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    match st.service.get_allocation(id).await {
        Ok(r) => (StatusCode::OK, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct AllocationQuery {
    segment_booking_id: String,
    status: Option<AllocationStatus>,
    limit: Option<usize>,
    offset: Option<usize>,
}
pub(crate) async fn list_allocations<S: SeatAssignmentApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    Query(q): Query<AllocationQuery>,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    match st
        .service
        .list_allocations(
            q.segment_booking_id,
            q.status,
            q.limit.unwrap_or(20).min(100),
            q.offset.unwrap_or(0),
        )
        .await
    {
        Ok(r) => (StatusCode::OK, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
