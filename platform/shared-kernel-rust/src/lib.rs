use std::fmt;
use std::str::FromStr;

use axum::{Router, routing::get};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServiceProfile {
    pub service_id: &'static str,
    pub domain: &'static str,
    pub language: &'static str,
    pub phase: &'static str,
    pub work_packages: &'static [&'static str],
    pub owns: &'static [&'static str],
}

pub fn profile() -> ServiceProfile {
    ServiceProfile {
        service_id: "shared-kernel",
        domain: "Shared Kernel / Platform",
        language: "rust",
        phase: "phase-1-foundation",
        work_packages: &["REQ-003"],
        owns: &[
            "reference implementation for IDs, value objects, and event envelope",
            "language-neutral contract source before code generation exists",
        ],
    }
}

pub fn health() -> &'static str {
    "ok"
}

pub fn router() -> Router {
    Router::new().route("/health", get(health_handler))
}

async fn health_handler() -> &'static str {
    health()
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ContractError {
    EmptyId { kind: &'static str },
    InvalidCurrencyCode(String),
    InvalidTimeWindow,
    EmptyEventType,
    InvalidSchemaVersion,
}

impl fmt::Display for ContractError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::EmptyId { kind } => write!(f, "{kind} must not be empty"),
            Self::InvalidCurrencyCode(code) => {
                write!(
                    f,
                    "currency code must be three uppercase ASCII letters: {code}"
                )
            }
            Self::InvalidTimeWindow => write!(f, "time window end must be after start"),
            Self::EmptyEventType => write!(f, "event type must not be empty"),
            Self::InvalidSchemaVersion => write!(f, "schema version must be greater than zero"),
        }
    }
}

impl std::error::Error for ContractError {}

macro_rules! id_type {
    ($name:ident) => {
        #[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
        pub struct $name(String);

        impl $name {
            pub fn new(value: impl Into<String>) -> Result<Self, ContractError> {
                let value = value.into();
                if value.trim().is_empty() {
                    return Err(ContractError::EmptyId {
                        kind: stringify!($name),
                    });
                }
                Ok(Self(value))
            }

            pub fn as_str(&self) -> &str {
                &self.0
            }
        }

        impl fmt::Display for $name {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str(&self.0)
            }
        }

        impl FromStr for $name {
            type Err = ContractError;

            fn from_str(value: &str) -> Result<Self, Self::Err> {
                Self::new(value)
            }
        }

        impl TryFrom<&str> for $name {
            type Error = ContractError;

            fn try_from(value: &str) -> Result<Self, Self::Error> {
                Self::new(value)
            }
        }
    };
}

id_type!(PlaceRef);
id_type!(TravelerRef);
id_type!(SegmentRef);
id_type!(EventId);
id_type!(CausationId);
id_type!(CorrelationId);

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct CurrencyCode(String);

impl CurrencyCode {
    pub fn new(code: impl Into<String>) -> Result<Self, ContractError> {
        let code = code.into();
        let valid = code.len() == 3 && code.bytes().all(|b| b.is_ascii_uppercase());
        if !valid {
            return Err(ContractError::InvalidCurrencyCode(code));
        }
        Ok(Self(code))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for CurrencyCode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl FromStr for CurrencyCode {
    type Err = ContractError;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        Self::new(value)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Money {
    amount_minor: i64,
    currency: CurrencyCode,
}

impl Money {
    pub fn new_minor(amount_minor: i64, currency: CurrencyCode) -> Self {
        Self {
            amount_minor,
            currency,
        }
    }

    pub fn amount_minor(&self) -> i64 {
        self.amount_minor
    }

    pub fn currency(&self) -> &CurrencyCode {
        &self.currency
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct UnixMillis(u64);

impl UnixMillis {
    pub fn new(value: u64) -> Self {
        Self(value)
    }

    pub fn as_u64(self) -> u64 {
        self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TimeWindow {
    start: UnixMillis,
    end: UnixMillis,
}

impl TimeWindow {
    pub fn new(start: UnixMillis, end: UnixMillis) -> Result<Self, ContractError> {
        if end <= start {
            return Err(ContractError::InvalidTimeWindow);
        }
        Ok(Self { start, end })
    }

    pub fn start(self) -> UnixMillis {
        self.start
    }

    pub fn end(self) -> UnixMillis {
        self.end
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EventEnvelope {
    event_id: EventId,
    event_type: String,
    schema_version: u32,
    occurred_at: UnixMillis,
    correlation_id: CorrelationId,
    causation_id: Option<CausationId>,
}

impl EventEnvelope {
    pub fn new(
        event_id: EventId,
        event_type: impl Into<String>,
        schema_version: u32,
        occurred_at: UnixMillis,
        correlation_id: CorrelationId,
        causation_id: Option<CausationId>,
    ) -> Result<Self, ContractError> {
        let event_type = event_type.into();
        if event_type.trim().is_empty() {
            return Err(ContractError::EmptyEventType);
        }
        if schema_version == 0 {
            return Err(ContractError::InvalidSchemaVersion);
        }
        Ok(Self {
            event_id,
            event_type,
            schema_version,
            occurred_at,
            correlation_id,
            causation_id,
        })
    }

    pub fn event_id(&self) -> &EventId {
        &self.event_id
    }

    pub fn event_type(&self) -> &str {
        &self.event_type
    }

    pub fn schema_version(&self) -> u32 {
        self.schema_version
    }

    pub fn occurred_at(&self) -> UnixMillis {
        self.occurred_at
    }

    pub fn correlation_id(&self) -> &CorrelationId {
        &self.correlation_id
    }

    pub fn causation_id(&self) -> Option<&CausationId> {
        self.causation_id.as_ref()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn skeleton_profile_matches_domain() {
        let profile = profile();
        assert_eq!(profile.service_id, "shared-kernel");
        assert_eq!(profile.domain, "Shared Kernel / Platform");
        assert_eq!(profile.work_packages, &["REQ-003"]);
        assert_eq!(health(), "ok");
    }

    #[test]
    fn axum_router_can_be_constructed() {
        let _router = router();
    }

    #[test]
    fn id_refs_reject_blank_values() {
        assert_eq!(
            TravelerRef::new("   ").unwrap_err(),
            ContractError::EmptyId {
                kind: "TravelerRef"
            }
        );
        assert_eq!(SegmentRef::new("seg-123").unwrap().as_str(), "seg-123");
    }

    #[test]
    fn money_requires_iso_like_currency_code() {
        let money = Money::new_minor(12345, CurrencyCode::new("CNY").unwrap());
        assert_eq!(money.amount_minor(), 12345);
        assert_eq!(money.currency().as_str(), "CNY");
        assert!(CurrencyCode::new("cny").is_err());
        assert!(CurrencyCode::new("CNYY").is_err());
    }

    #[test]
    fn time_window_requires_forward_time() {
        let start = UnixMillis::new(1_800_000_000_000);
        let end = UnixMillis::new(1_800_003_600_000);
        let window = TimeWindow::new(start, end).unwrap();
        assert_eq!(window.start(), start);
        assert_eq!(window.end(), end);
        assert_eq!(
            TimeWindow::new(end, start).unwrap_err(),
            ContractError::InvalidTimeWindow
        );
    }

    #[test]
    fn event_envelope_carries_trace_ids() {
        let envelope = EventEnvelope::new(
            EventId::new("evt-1").unwrap(),
            "JourneyOrderCreated",
            1,
            UnixMillis::new(1_800_000_000_000),
            CorrelationId::new("corr-1").unwrap(),
            Some(CausationId::new("cmd-1").unwrap()),
        )
        .unwrap();

        assert_eq!(envelope.event_id().as_str(), "evt-1");
        assert_eq!(envelope.event_type(), "JourneyOrderCreated");
        assert_eq!(envelope.schema_version(), 1);
        assert_eq!(envelope.correlation_id().as_str(), "corr-1");
        assert_eq!(envelope.causation_id().unwrap().as_str(), "cmd-1");
    }
}
