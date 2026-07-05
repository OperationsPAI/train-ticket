use std::collections::HashMap;
use std::sync::Mutex;

use axum::http::HeaderMap;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};

const IDEMPOTENCY_KEY_HEADER: &str = "idempotency-key";

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum IdempotencyError {
    Missing,
    InvalidUuidV7,
    Reused,
}

pub fn validate_uuid_v7(value: &str) -> bool {
    let Ok(uuid) = uuid::Uuid::parse_str(value) else {
        return false;
    };
    uuid.get_version_num() == 7
}

pub fn require_idempotency_key(headers: &HeaderMap) -> Result<String, IdempotencyError> {
    let value = headers
        .get(IDEMPOTENCY_KEY_HEADER)
        .and_then(|value| value.to_str().ok())
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .ok_or(IdempotencyError::Missing)?;
    if !validate_uuid_v7(value) {
        return Err(IdempotencyError::InvalidUuidV7);
    }
    Ok(value.to_string())
}

pub fn request_fingerprint(method: &str, path: &str, body: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(method.as_bytes());
    hasher.update(b"\n");
    hasher.update(path.as_bytes());
    hasher.update(b"\n");
    hasher.update(body);
    format!("sha256:{:x}", hasher.finalize())
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct IdempotencyRecord {
    pub fingerprint: String,
    pub status_code: u16,
    pub body: Value,
}

pub trait IdempotencyStore: Send + Sync + 'static {
    fn get(&self, key: &str) -> Option<IdempotencyRecord>;
    fn set(&self, key: String, record: IdempotencyRecord);
}

#[derive(Default)]
pub struct InMemoryIdempotencyStore {
    records: Mutex<HashMap<String, IdempotencyRecord>>,
}

impl InMemoryIdempotencyStore {
    pub fn new() -> Self {
        Self::default()
    }
}

impl IdempotencyStore for InMemoryIdempotencyStore {
    fn get(&self, key: &str) -> Option<IdempotencyRecord> {
        self.records
            .lock()
            .expect("idempotency lock poisoned")
            .get(key)
            .cloned()
    }

    fn set(&self, key: String, record: IdempotencyRecord) {
        self.records
            .lock()
            .expect("idempotency lock poisoned")
            .insert(key, record);
    }
}

pub enum IdempotencyDecision {
    Proceed,
    Replay { status_code: u16, body: Value },
}

pub fn decide<S: IdempotencyStore>(
    store: &S,
    key: &str,
    fingerprint: &str,
) -> Result<IdempotencyDecision, IdempotencyError> {
    let Some(record) = store.get(key) else {
        return Ok(IdempotencyDecision::Proceed);
    };
    if record.fingerprint != fingerprint {
        return Err(IdempotencyError::Reused);
    }
    Ok(IdempotencyDecision::Replay {
        status_code: record.status_code,
        body: record.body,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uuid_v7_validation_rejects_v4_and_malformed_values() {
        assert!(validate_uuid_v7(&uuid::Uuid::now_v7().to_string()));
        assert!(!validate_uuid_v7(&uuid::Uuid::new_v4().to_string()));
        assert!(!validate_uuid_v7("not-a-uuid"));
    }

    #[test]
    fn same_key_different_fingerprint_is_reused_error() {
        let store = InMemoryIdempotencyStore::new();
        store.set(
            "k".into(),
            IdempotencyRecord {
                fingerprint: "a".into(),
                status_code: 201,
                body: serde_json::json!({"ok":true}),
            },
        );
        assert!(matches!(
            decide(&store, "k", "b"),
            Err(IdempotencyError::Reused)
        ));
    }
}
