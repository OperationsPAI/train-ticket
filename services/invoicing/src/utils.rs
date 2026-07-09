use crate::*;
use chrono::{SecondsFormat, Utc};
use serde::Serialize;
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

pub(crate) fn validate_non_empty(value: &str, field: &'static str) -> Result<(), InvoicingError> {
    if value.trim().is_empty() {
        Err(InvoicingError::ValidationFailed(format!(
            "{field} must not be blank"
        )))
    } else {
        Ok(())
    }
}

pub(crate) fn validate_uuid_v7_key(value: &str) -> Result<(), InvoicingError> {
    if rust_kit::messaging::is_uuid_v7(value) {
        Ok(())
    } else {
        Err(InvoicingError::ValidationFailed(
            "Idempotency-Key header is required and must be a valid UUID v7".into(),
        ))
    }
}

pub(crate) fn command_id_from_key(key: &str) -> String {
    format!("cmd-{key}")
}

pub(crate) fn folded_uuid_v7(material: &str) -> uuid::Uuid {
    let digest = Sha256::digest(material.as_bytes());
    let mut bytes = [0u8; 16];
    bytes.copy_from_slice(&digest[..16]);
    bytes[6] = (bytes[6] & 0x0f) | 0x70;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    uuid::Uuid::from_bytes(bytes)
}

pub(crate) fn folded_id(prefix: &str, material: &str) -> String {
    format!("{prefix}-{}", folded_uuid_v7(material))
}

pub(crate) fn deterministic_event_id(event_type: &str, aggregate_id: &str, version: i64) -> String {
    format!(
        "evt-{}",
        folded_uuid_v7(&format!("invoicing:{event_type}:{aggregate_id}:{version}"))
    )
}

pub(crate) fn deterministic_gateway_event_id(
    event_type: &str,
    gateway_request_id: &str,
    result_hash: &str,
) -> String {
    format!(
        "evt-{}",
        folded_uuid_v7(&format!(
            "invoicing:{event_type}:{gateway_request_id}:{result_hash}"
        ))
    )
}

pub(crate) fn deterministic_observation_event_id(
    event_type: &str,
    case_id: &str,
    refund_fact_event_id: &str,
    version: i64,
) -> String {
    format!(
        "evt-{}",
        folded_uuid_v7(&format!(
            "invoicing:{eventType}:{case_id}:{refund_fact_event_id}:{version}",
            eventType = event_type
        ))
    )
}

pub(crate) fn sha256_hex(value: &str) -> String {
    format!("{:x}", Sha256::digest(value.as_bytes()))
}
pub(crate) fn sha256_prefixed(value: &str) -> String {
    format!("sha256:{}", sha256_hex(value))
}

pub(crate) fn normalize_json<T: Serialize>(value: &T) -> String {
    serde_json::to_string(value).unwrap_or_default()
}

pub(crate) fn string_field(payload: &Value, field: &str) -> Option<String> {
    payload
        .get(field)
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty())
        .map(str::to_string)
}

pub(crate) fn array_strings(payload: &Value, field: &str) -> Vec<String> {
    payload
        .get(field)
        .and_then(Value::as_array)
        .map(|items| {
            items
                .iter()
                .filter_map(Value::as_str)
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default()
}

pub(crate) fn handler_error(error: InvoicingError) -> rust_kit::messaging::HandlerError {
    match error {
        InvoicingError::Unavailable(message) | InvoicingError::Internal(message) => {
            rust_kit::messaging::HandlerError::Transient(message)
        }
        other => rust_kit::messaging::HandlerError::Fatal(other.to_string()),
    }
}

pub(crate) fn storage_error(error: rust_kit::storage::StorageError) -> InvoicingError {
    match error {
        rust_kit::storage::StorageError::IdempotencyKeyReused => {
            InvoicingError::IdempotencyKeyReused(
                "Idempotency-Key was reused with a different request body".into(),
            )
        }
        rust_kit::storage::StorageError::Conflict(message) => InvoicingError::Conflict(message),
        other => InvoicingError::Internal(other.to_string()),
    }
}

pub(crate) fn mask_tail(value: &str, visible: usize) -> String {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return String::new();
    }
    let tail: String = trimmed
        .chars()
        .rev()
        .take(visible)
        .collect::<String>()
        .chars()
        .rev()
        .collect();
    format!(
        "{}{}",
        "*".repeat(trimmed.chars().count().saturating_sub(visible).max(4)),
        tail
    )
}

pub(crate) fn mask_email(value: &str) -> Option<String> {
    let (name, domain) = value.trim().split_once('@')?;
    let first = name.chars().next().unwrap_or('*');
    Some(format!("{first}***@{domain}"))
}


/// Wire correlation ids are `corr-<uuid-v7>`; the HTTP middleware hands us the
/// raw header value (or a bare uuid), and rust-kit's envelope builder PANICS on
/// unprefixed ids. Canonicalize before any envelope is built.
pub(crate) fn canonical_corr(raw: &str) -> String {
    if raw.starts_with("corr-") {
        return raw.to_string();
    }
    if uuid::Uuid::parse_str(raw).is_ok() {
        return format!("corr-{raw}");
    }
    rust_kit::messaging::correlation_id()
}
