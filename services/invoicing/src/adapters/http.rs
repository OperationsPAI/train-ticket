use crate::*;
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

pub struct ApiState<S: InvoicingApi + 'static> {
    pub(crate) service: Arc<S>,
}
impl<S: InvoicingApi + 'static> Clone for ApiState<S> {
    fn clone(&self) -> Self {
        Self {
            service: self.service.clone(),
        }
    }
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
            InvoicingError::IdempotencyKeyReused(
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
pub(crate) fn api_error_response(e: InvoicingError, corr: String) -> Response {
    kit_http::error_response(
        error_status(&e),
        e.code(),
        e.message().to_string(),
        corr,
        Some(json!({"domainCode":e.code()})),
    )
}
fn error_status(e: &InvoicingError) -> StatusCode {
    match e {
        InvoicingError::ValidationFailed(_) => StatusCode::BAD_REQUEST,
        InvoicingError::NotFound(_) => StatusCode::NOT_FOUND,
        InvoicingError::Conflict(_) => StatusCode::CONFLICT,
        InvoicingError::IdempotencyKeyReused(_) => StatusCode::UNPROCESSABLE_ENTITY,
        InvoicingError::PreconditionFailed(_) => StatusCode::PRECONDITION_FAILED,
        InvoicingError::DomainRuleViolation(_) => StatusCode::UNPROCESSABLE_ENTITY,
        InvoicingError::Unavailable(_) => StatusCode::SERVICE_UNAVAILABLE,
        InvoicingError::Internal(_) => StatusCode::INTERNAL_SERVER_ERROR,
    }
}

pub(crate) async fn create_title<S: InvoicingApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    headers: HeaderMap,
    body: Result<Json<CreateInvoiceTitleCommand>, JsonRejection>,
) -> Response {
    let corr = crate::utils::canonical_corr(ctx.correlation_id());
    let k = match key(&headers) {
        Ok(k) => k,
        Err(e) => return idem_err(e, corr),
    };
    let Json(cmd) = match body {
        Ok(b) => b,
        Err(e) => return validation(corr, e.body_text()),
    };
    match st.service.create_title(cmd, k, corr.clone()).await {
        Ok(r) => (StatusCode::CREATED, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ListTitlesQuery {
    account_id: String,
    status: Option<TitleStatus>,
    limit: Option<usize>,
    offset: Option<usize>,
}
pub(crate) async fn list_titles<S: InvoicingApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    Query(q): Query<ListTitlesQuery>,
) -> Response {
    let corr = crate::utils::canonical_corr(ctx.correlation_id());
    match st
        .service
        .list_titles(
            q.account_id,
            q.status,
            q.limit.unwrap_or(20).min(100),
            q.offset.unwrap_or(0),
        )
        .await
    {
        Ok(r) => Json(r).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn get_title<S: InvoicingApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    Path(id): Path<String>,
) -> Response {
    let corr = crate::utils::canonical_corr(ctx.correlation_id());
    match st.service.get_title(id).await {
        Ok(r) => Json(r).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn update_title<S: InvoicingApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    headers: HeaderMap,
    Path(id): Path<String>,
    body: Result<Json<UpdateInvoiceTitleCommand>, JsonRejection>,
) -> Response {
    let corr = crate::utils::canonical_corr(ctx.correlation_id());
    let k = match key(&headers) {
        Ok(k) => k,
        Err(e) => return idem_err(e, corr),
    };
    let Json(cmd) = match body {
        Ok(b) => b,
        Err(e) => return validation(corr, e.body_text()),
    };
    match st.service.update_title(id, cmd, k, corr.clone()).await {
        Ok(r) => Json(r).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn set_default_title<S: InvoicingApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    headers: HeaderMap,
    Path(id): Path<String>,
    body: Result<Json<SetDefaultTitleCommand>, JsonRejection>,
) -> Response {
    let corr = crate::utils::canonical_corr(ctx.correlation_id());
    let k = match key(&headers) {
        Ok(k) => k,
        Err(e) => return idem_err(e, corr),
    };
    let Json(cmd) = match body {
        Ok(b) => b,
        Err(e) => return validation(corr, e.body_text()),
    };
    match st.service.set_default_title(id, cmd, k, corr.clone()).await {
        Ok(r) => Json(r).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn deactivate_title<S: InvoicingApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    headers: HeaderMap,
    Path(id): Path<String>,
    body: Result<Json<DeactivateTitleCommand>, JsonRejection>,
) -> Response {
    let corr = crate::utils::canonical_corr(ctx.correlation_id());
    let k = match key(&headers) {
        Ok(k) => k,
        Err(e) => return idem_err(e, corr),
    };
    let Json(cmd) = match body {
        Ok(b) => b,
        Err(e) => return validation(corr, e.body_text()),
    };
    match st.service.deactivate_title(id, cmd, k, corr.clone()).await {
        Ok(r) => Json(r).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn request_invoice<S: InvoicingApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    headers: HeaderMap,
    body: Result<Json<RequestEInvoiceCommand>, JsonRejection>,
) -> Response {
    let corr = crate::utils::canonical_corr(ctx.correlation_id());
    let k = match key(&headers) {
        Ok(k) => k,
        Err(e) => return idem_err(e, corr),
    };
    let Json(cmd) = match body {
        Ok(b) => b,
        Err(e) => return validation(corr, e.body_text()),
    };
    match st.service.request_invoice(cmd, k, corr.clone()).await {
        Ok(r) => (StatusCode::CREATED, Json(r)).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn get_request<S: InvoicingApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    Path(id): Path<String>,
) -> Response {
    let corr = crate::utils::canonical_corr(ctx.correlation_id());
    match st.service.get_request(id).await {
        Ok(r) => Json(r).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn get_invoice<S: InvoicingApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    Path(id): Path<String>,
) -> Response {
    let corr = crate::utils::canonical_corr(ctx.correlation_id());
    match st.service.get_invoice(id).await {
        Ok(r) => Json(r).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ListInvoicesQuery {
    order_id: String,
    invoice_type: Option<InvoiceType>,
    status: Option<InvoiceRequestStatus>,
    limit: Option<usize>,
    offset: Option<usize>,
}
pub(crate) async fn list_invoices<S: InvoicingApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    Query(q): Query<ListInvoicesQuery>,
) -> Response {
    let corr = crate::utils::canonical_corr(ctx.correlation_id());
    match st
        .service
        .list_invoices(
            q.order_id,
            q.invoice_type,
            q.status,
            q.limit.unwrap_or(20).min(100),
            q.offset.unwrap_or(0),
        )
        .await
    {
        Ok(r) => Json(r).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
pub(crate) async fn get_red_flush<S: InvoicingApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    Path(id): Path<String>,
) -> Response {
    let corr = crate::utils::canonical_corr(ctx.correlation_id());
    match st.service.get_red_flush(id).await {
        Ok(r) => Json(r).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ListRedQuery {
    order_id: Option<String>,
    post_sales_case_id: Option<String>,
    status: Option<RedFlushStatus>,
    limit: Option<usize>,
    offset: Option<usize>,
}
pub(crate) async fn list_red_flushes<S: InvoicingApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    Query(q): Query<ListRedQuery>,
) -> Response {
    let corr = crate::utils::canonical_corr(ctx.correlation_id());
    if q.order_id.is_none() && q.post_sales_case_id.is_none() {
        return validation(corr, "orderId or postSalesCaseId is required");
    }
    match st
        .service
        .list_red_flushes(
            q.order_id,
            q.post_sales_case_id,
            q.status,
            q.limit.unwrap_or(20).min(100),
            q.offset.unwrap_or(0),
        )
        .await
    {
        Ok(r) => Json(r).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ItineraryQuery {
    order_id: String,
    traveler_refs: String,
    segment_refs: String,
    receipt_version: Option<i64>,
}
pub(crate) async fn generate_itinerary<S: InvoicingApi + 'static>(
    State(st): State<ApiState<S>>,
    Extension(ctx): Extension<RequestContext>,
    Query(q): Query<ItineraryQuery>,
) -> Response {
    let corr = crate::utils::canonical_corr(ctx.correlation_id());
    let travelers = q
        .traveler_refs
        .split(',')
        .filter(|v| !v.trim().is_empty())
        .map(|v| v.trim().to_string())
        .collect();
    let segments = q
        .segment_refs
        .split(',')
        .filter(|v| !v.trim().is_empty())
        .map(|v| v.trim().to_string())
        .collect();
    match st
        .service
        .generate_itinerary(
            q.order_id,
            travelers,
            segments,
            q.receipt_version.unwrap_or(1),
        )
        .await
    {
        Ok(r) => Json(r).into_response(),
        Err(e) => api_error_response(e, corr),
    }
}
