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

pub struct ApiState<S: WaitlistApi + 'static> {
    pub(crate) service: Arc<S>,
}
impl<S: WaitlistApi + 'static> Clone for ApiState<S> {
    fn clone(&self) -> Self {
        Self {
            service: Arc::clone(&self.service),
        }
    }
}
#[async_trait]
pub trait WaitlistApi: Send + Sync {
    async fn create(
        &self,
        c: CreateWaitlistCommand,
        key: String,
        corr: String,
    ) -> Result<WaitlistResource, WaitlistError>;
    async fn get(&self, id: String) -> Result<WaitlistResource, WaitlistError>;
    async fn cancel(
        &self,
        id: String,
        c: CancelWaitlistCommand,
        key: String,
        corr: String,
    ) -> Result<CancelWaitlistResponse, WaitlistError>;
    async fn list(
        &self,
        traveler: String,
        status: Option<WaitlistStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<PaginatedWaitlistRequests, WaitlistError>;
}

pub(crate) async fn create_waitlist<S: WaitlistApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    headers: HeaderMap,
    body: Result<Json<CreateWaitlistCommand>, JsonRejection>,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    let key = match idempotency_key(&headers) {
        Ok(k) => k,
        Err(e) => return idempotency_error_response(e, corr),
    };
    let Json(cmd) = match body {
        Ok(b) => b,
        Err(e) => return validation_error(corr, e.body_text()),
    };
    match st.service.create(cmd, key, corr.clone()).await {
        Ok(r) => (StatusCode::CREATED, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn get_waitlist<S: WaitlistApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    Path(id): Path<String>,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    match st.service.get(id).await {
        Ok(r) => (StatusCode::OK, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn cancel_waitlist<S: WaitlistApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    headers: HeaderMap,
    Path(id): Path<String>,
    body: Result<Json<CancelWaitlistCommand>, JsonRejection>,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    let key = match idempotency_key(&headers) {
        Ok(k) => k,
        Err(e) => return idempotency_error_response(e, corr),
    };
    let Json(cmd) = match body {
        Ok(b) => b,
        Err(e) => return validation_error(corr, e.body_text()),
    };
    match st.service.cancel(id, cmd, key, corr.clone()).await {
        Ok(r) => (StatusCode::OK, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn list_waitlist<S: WaitlistApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    Query(q): Query<ListQuery>,
) -> Response {
    let corr = ctx.correlation_id().to_string();
    if q.traveler_ref.trim().is_empty() {
        return validation_error(corr, "travelerRef query parameter is required");
    }
    match st
        .service
        .list(
            q.traveler_ref,
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
fn validation_error(corr: String, msg: impl Into<String>) -> Response {
    kit_http::error_response(
        StatusCode::BAD_REQUEST,
        "VALIDATION_FAILED",
        msg.into(),
        corr,
        Some(json!({"domainCode":"VALIDATION_FAILED"})),
    )
}
fn idempotency_error_response(e: kit_idempotency::IdempotencyError, corr: String) -> Response {
    match e {
        kit_idempotency::IdempotencyError::Reused => api_error_response(
            WaitlistError::IdempotencyKeyReused(
                "Idempotency-Key was reused with a different request body".into(),
            ),
            corr,
        ),
        _ => validation_error(
            corr,
            "Idempotency-Key header is required and must be a valid UUID v7",
        ),
    }
}
fn idempotency_key(headers: &HeaderMap) -> Result<String, kit_idempotency::IdempotencyError> {
    kit_idempotency::require_idempotency_key(headers)
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ListQuery {
    traveler_ref: String,
    status: Option<WaitlistStatus>,
    limit: Option<usize>,
    offset: Option<usize>,
}

pub(crate) fn api_error_response(e: WaitlistError, corr: String) -> Response {
    kit_http::error_response(
        error_status(&e),
        e.code(),
        e.message().to_string(),
        corr,
        Some(json!({"domainCode":e.code()})),
    )
}

fn error_status(error: &WaitlistError) -> StatusCode {
    match error {
        WaitlistError::ValidationFailed(_) => StatusCode::BAD_REQUEST,
        WaitlistError::NotFound(_) => StatusCode::NOT_FOUND,
        WaitlistError::Conflict(_) => StatusCode::CONFLICT,
        WaitlistError::IdempotencyKeyReused(_) => StatusCode::UNPROCESSABLE_ENTITY,
        WaitlistError::PreconditionFailed(_) => StatusCode::PRECONDITION_FAILED,
        WaitlistError::DomainRuleViolation(_) => StatusCode::UNPROCESSABLE_ENTITY,
        WaitlistError::Unavailable(_) => StatusCode::SERVICE_UNAVAILABLE,
        WaitlistError::Internal(_) => StatusCode::INTERNAL_SERVER_ERROR,
    }
}
