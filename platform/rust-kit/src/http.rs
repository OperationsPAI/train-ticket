use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::Serialize;
use serde_json::Value;

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ErrorBody {
    pub code: String,
    pub message: String,
    pub correlation_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub details: Option<Value>,
}

impl ErrorBody {
    pub fn new(
        code: impl Into<String>,
        message: impl Into<String>,
        correlation_id: impl Into<String>,
    ) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
            correlation_id: correlation_id.into(),
            details: None,
        }
    }

    pub fn with_details(mut self, details: Value) -> Self {
        self.details = Some(details);
        self
    }
}

pub fn error_response(
    status: StatusCode,
    code: impl Into<String>,
    message: impl Into<String>,
    correlation_id: impl Into<String>,
    details: Option<Value>,
) -> Response {
    let mut body = ErrorBody::new(code, message, correlation_id);
    body.details = details;
    (status, Json(body)).into_response()
}

pub trait ContractErrorStatus {
    fn status_code(&self) -> u16;
    fn code(&self) -> &str;
    fn message(&self) -> &str;
}

pub struct AppErrorResponse<E> {
    pub error: E,
    pub correlation_id: String,
}

impl<E> AppErrorResponse<E> {
    pub fn new(error: E, correlation_id: impl Into<String>) -> Self {
        Self {
            error,
            correlation_id: correlation_id.into(),
        }
    }
}

impl<E: ContractErrorStatus> IntoResponse for AppErrorResponse<E> {
    fn into_response(self) -> Response {
        let status = StatusCode::from_u16(self.error.status_code())
            .unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
        error_response(
            status,
            self.error.code(),
            self.error.message(),
            self.correlation_id,
            None,
        )
    }
}

pub fn validation_error(message: impl Into<String>, correlation_id: impl Into<String>) -> Response {
    error_response(
        StatusCode::BAD_REQUEST,
        "VALIDATION_FAILED",
        message,
        correlation_id,
        None,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn error_body_omits_details_when_absent() {
        assert_eq!(
            serde_json::to_value(ErrorBody::new("VALIDATION_FAILED", "bad", "corr-1")).unwrap(),
            serde_json::json!({
                "code": "VALIDATION_FAILED", "message": "bad", "correlationId": "corr-1"
            })
        );
    }
}
