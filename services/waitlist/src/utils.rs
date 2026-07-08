use crate::*;
use chrono::{DateTime, SecondsFormat, Utc};
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
pub(crate) fn handler_error(error: WaitlistError) -> rust_kit::messaging::HandlerError {
    match error {
        WaitlistError::Unavailable(message) | WaitlistError::Internal(message) => {
            rust_kit::messaging::HandlerError::Transient(message)
        }
        other => rust_kit::messaging::HandlerError::Fatal(other.to_string()),
    }
}
pub(crate) fn storage_error(error: rust_kit::storage::StorageError) -> WaitlistError {
    match error {
        rust_kit::storage::StorageError::IdempotencyKeyReused => {
            WaitlistError::IdempotencyKeyReused(
                "Idempotency-Key was reused with a different request body".into(),
            )
        }
        other => WaitlistError::Internal(other.to_string()),
    }
}
pub(crate) fn db_error(error: sqlx::Error) -> WaitlistError {
    let message = error.to_string();
    if message.contains("active_waitlist_request") {
        WaitlistError::Conflict(
            "traveler already has an active waitlist request for this intent".into(),
        )
    } else {
        WaitlistError::Internal(message)
    }
}
pub(crate) fn current_rfc3339() -> String {
    Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true)
}
pub(crate) fn validate_non_empty(value: &str, field: &'static str) -> Result<(), WaitlistError> {
    if value.trim().is_empty() {
        Err(WaitlistError::ValidationFailed(format!(
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
) -> Result<(), WaitlistError> {
    validate_non_empty(value, field)?;
    let Some(uuid_part) = value.strip_prefix(prefix) else {
        return Err(WaitlistError::ValidationFailed(format!(
            "{field} must use {prefix}<uuid> format"
        )));
    };
    uuid::Uuid::parse_str(uuid_part).map_err(|_| {
        WaitlistError::ValidationFailed(format!("{field} must use {prefix}<uuid> format"))
    })?;
    Ok(())
}
pub(crate) fn validate_payment_guarantee(value: &str) -> Result<(), WaitlistError> {
    validate_non_empty(value, "paymentGuaranteeRef")?;
    if value.starts_with("pay-auth-") {
        Ok(())
    } else {
        validate_prefixed_uuid(value, "paymentGuaranteeRef", "pi-")
    }
}
pub(crate) fn deadline_after(deadline: &str, now: &str) -> bool {
    let Ok(deadline) = DateTime::parse_from_rfc3339(deadline) else {
        return false;
    };
    let Ok(now) = DateTime::parse_from_rfc3339(now) else {
        return false;
    };
    deadline > now
}
pub(crate) fn deterministic_event_id(event_type: &str, id: &str, version: i64) -> String {
    let seed = format!("waitlist:{event_type}:{id}:{version}");
    format!("evt-wl-{:x}", Sha256::digest(seed.as_bytes()))
}
pub(crate) fn string_field(payload: &Value, field: &str) -> Option<String> {
    payload
        .get(field)
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty())
        .map(str::to_string)
}
pub(crate) fn segment_from_payload(payload: &Value) -> Option<String> {
    string_field(payload, "segmentRef").or_else(|| string_field(payload, "serviceSegmentRef"))
}
pub(crate) fn order_ref_from_payload(payload: &Value) -> Option<String> {
    string_field(payload, "orderRef")
        .or_else(|| string_field(payload, "orderId"))
        .or_else(|| string_field(payload, "journeyOrderRef"))
}
