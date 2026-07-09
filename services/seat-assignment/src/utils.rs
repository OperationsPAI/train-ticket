use crate::*;
use chrono::{SecondsFormat, Utc};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::fmt;

#[derive(Debug, Clone)]
pub struct SubscribeFailed(pub String);
impl fmt::Display for SubscribeFailed {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}
impl std::error::Error for SubscribeFailed {}
impl From<rust_kit::messaging::SubscribeFailed> for SubscribeFailed {
    fn from(error: rust_kit::messaging::SubscribeFailed) -> Self {
        Self(error.to_string())
    }
}

pub(crate) fn current_rfc3339() -> String {
    Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true)
}

pub(crate) fn validate_non_empty(
    value: &str,
    field: &'static str,
) -> Result<(), SeatAssignmentError> {
    if value.trim().is_empty() {
        Err(SeatAssignmentError::ValidationFailed(format!(
            "{field} must not be blank"
        )))
    } else {
        Ok(())
    }
}
pub(crate) fn validate_prefixed_uuid(
    value: &str,
    field: &'static str,
    prefix: &'static str,
) -> Result<(), SeatAssignmentError> {
    validate_non_empty(value, field)?;
    let Some(uuid_part) = value.strip_prefix(prefix) else {
        return Err(SeatAssignmentError::ValidationFailed(format!(
            "{field} must use {prefix}<uuid> format"
        )));
    };
    uuid::Uuid::parse_str(uuid_part).map_err(|_| {
        SeatAssignmentError::ValidationFailed(format!("{field} must use {prefix}<uuid> format"))
    })?;
    Ok(())
}
pub(crate) fn validate_date(value: &str, field: &'static str) -> Result<(), SeatAssignmentError> {
    chrono::NaiveDate::parse_from_str(value, "%Y-%m-%d").map_err(|_| {
        SeatAssignmentError::ValidationFailed(format!("{field} must be YYYY-MM-DD"))
    })?;
    Ok(())
}
pub(crate) fn validate_uuid_v7_key(value: &str) -> Result<(), SeatAssignmentError> {
    if rust_kit::messaging::is_uuid_v7(value) {
        Ok(())
    } else {
        Err(SeatAssignmentError::ValidationFailed(
            "Idempotency-Key header is required and must be a valid UUID v7".into(),
        ))
    }
}
pub(crate) fn folded_uuid_v7(material: &str) -> uuid::Uuid {
    let digest = Sha256::digest(material.as_bytes());
    let mut bytes = [0u8; 16];
    bytes.copy_from_slice(&digest[..16]);
    bytes[6] = (bytes[6] & 0x0f) | 0x70;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    uuid::Uuid::from_bytes(bytes)
}
pub(crate) fn deterministic_event_id(event_type: &str, aggregate_id: &str, version: i64) -> String {
    format!(
        "evt-{}",
        folded_uuid_v7(&format!(
            "seat-assignment:{event_type}:{aggregate_id}:{version}"
        ))
    )
}
pub(crate) fn command_id_from_key(key: &str) -> String {
    format!("cmd-{key}")
}
pub(crate) fn string_field(payload: &Value, field: &str) -> Option<String> {
    payload
        .get(field)
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty())
        .map(str::to_string)
}
pub(crate) fn handler_error(error: SeatAssignmentError) -> rust_kit::messaging::HandlerError {
    match error {
        SeatAssignmentError::Unavailable(message) | SeatAssignmentError::Internal(message) => {
            rust_kit::messaging::HandlerError::Transient(message)
        }
        other => rust_kit::messaging::HandlerError::Fatal(other.to_string()),
    }
}
pub(crate) fn storage_error(error: rust_kit::storage::StorageError) -> SeatAssignmentError {
    match error {
        rust_kit::storage::StorageError::IdempotencyKeyReused => {
            SeatAssignmentError::IdempotencyKeyReused(
                "Idempotency-Key was reused with a different request body".into(),
            )
        }
        other => SeatAssignmentError::Internal(other.to_string()),
    }
}
pub(crate) fn db_error(error: sqlx::Error) -> SeatAssignmentError {
    let message = error.to_string();
    if message.contains("idx_seat_maps_published") {
        SeatAssignmentError::Conflict(
            "a published SeatMap already exists for this service/date".into(),
        )
    } else if message.contains("idx_seat_allocations_active_booking") {
        SeatAssignmentError::Conflict(
            "segment booking already has an active seat allocation".into(),
        )
    } else {
        SeatAssignmentError::Internal(message)
    }
}
pub(crate) fn normalize_json<T: serde::Serialize>(value: &T) -> String {
    serde_json::to_string(value).unwrap_or_default()
}
