use chrono::{DateTime, Utc};
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ValidationError(pub String);

impl std::fmt::Display for ValidationError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}
impl std::error::Error for ValidationError {}

#[allow(dead_code)]
fn require_ref(value: &str, field_name: &str) -> Result<String, ValidationError> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return Err(ValidationError(format!(
            "{field_name} must be a non-empty upstream reference"
        )));
    }
    Ok(trimmed.to_string())
}

pub fn stable_ref(prefix: &str, parts: &[&str]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(parts.join("|").as_bytes());
    let digest = format!("{:x}", hasher.finalize());
    format!("{}_{}", prefix, &digest[..16])
}

#[derive(Debug, Clone)]
pub struct PreferenceConstraints {
    pub allowed_modes: BTreeSet<String>,
    pub max_connections: Option<u32>,
    pub min_connection_minutes: u32,
    pub max_connection_wait_minutes: Option<u32>,
    pub max_price_minor: Option<i64>,
    pub prefer_low_price: bool,
    pub prefer_short_duration: bool,
}

impl Default for PreferenceConstraints {
    fn default() -> Self {
        Self {
            allowed_modes: BTreeSet::new(),
            max_connections: None,
            min_connection_minutes: 5,
            max_connection_wait_minutes: None,
            max_price_minor: None,
            prefer_low_price: false,
            prefer_short_duration: true,
        }
    }
}

#[derive(Debug, Clone)]
pub struct TripIntent {
    pub origin_ref: String,
    pub destination_ref: String,
    pub departure_window_start: DateTime<Utc>,
    pub departure_window_end: DateTime<Utc>,
    pub passenger_count: u32,
    pub preferences: PreferenceConstraints,
}

impl TripIntent {
    pub fn fingerprint_parts(&self) -> Vec<String> {
        let modes_str = self
            .preferences
            .allowed_modes
            .iter()
            .cloned()
            .collect::<Vec<_>>()
            .join(",");
        vec![
            self.origin_ref.clone(),
            self.destination_ref.clone(),
            self.departure_window_start.to_rfc3339(),
            self.departure_window_end.to_rfc3339(),
            self.passenger_count.to_string(),
            modes_str,
            self.preferences
                .max_connections
                .map_or("None".to_string(), |v| v.to_string()),
            self.preferences.min_connection_minutes.to_string(),
            self.preferences
                .max_connection_wait_minutes
                .map_or("None".to_string(), |v| v.to_string()),
            self.preferences
                .max_price_minor
                .map_or("None".to_string(), |v| v.to_string()),
            "self_transfer_allowed".to_string(),
        ]
    }
}

#[derive(Debug, Clone)]
pub struct PriceHint {
    pub amount_minor: i64,
    pub currency: String,
    pub snapshot_ref: String,
    pub captured_at: DateTime<Utc>,
    pub confidence: u32,
}

#[derive(Debug, Clone)]
pub struct AvailabilityHint {
    pub status: String,
    pub snapshot_ref: String,
    pub captured_at: DateTime<Utc>,
    pub confidence: u32,
}

#[derive(Debug, Clone)]
pub struct LegCandidate {
    pub service_plan_ref: String,
    pub service_segment_ref: String,
    pub origin_stop_ref: String,
    pub destination_stop_ref: String,
    pub departure_time: DateTime<Utc>,
    pub arrival_time: DateTime<Utc>,
    pub mode: String,
    pub stop_refs: Vec<String>,
    pub segment_refs: Vec<String>,
}

impl LegCandidate {
    pub fn duration_minutes(&self) -> i64 {
        (self.arrival_time - self.departure_time).num_minutes()
    }

    pub fn leg_ref(&self) -> String {
        let parts: Vec<&str> = vec![
            &self.service_plan_ref,
            &self.service_segment_ref,
            &self.origin_stop_ref,
            &self.destination_stop_ref,
        ];
        let dep = self.departure_time.to_rfc3339();
        let arr = self.arrival_time.to_rfc3339();
        let all_parts: Vec<&str> = parts
            .into_iter()
            .chain(std::iter::once(dep.as_str()))
            .chain(std::iter::once(arr.as_str()))
            .collect();
        stable_ref("leg", &all_parts)
    }
}

#[derive(Debug, Clone)]
pub struct Itinerary {
    pub itinerary_ref: String,
    pub legs: Vec<LegCandidate>,
    pub price_hint: Option<PriceHint>,
    pub availability_hint: Option<AvailabilityHint>,
    pub planning_snapshot_refs: Vec<String>,
    pub search_origin_ref: Option<String>,
    pub search_destination_ref: Option<String>,
}

impl Itinerary {
    pub fn origin_ref(&self) -> &str {
        self.search_origin_ref
            .as_deref()
            .unwrap_or(&self.legs[0].origin_stop_ref)
    }

    pub fn destination_ref(&self) -> &str {
        self.search_destination_ref
            .as_deref()
            .unwrap_or(&self.legs.last().unwrap().destination_stop_ref)
    }

    pub fn departure_time(&self) -> DateTime<Utc> {
        self.legs[0].departure_time
    }

    pub fn arrival_time(&self) -> DateTime<Utc> {
        self.legs.last().unwrap().arrival_time
    }

    pub fn duration_minutes(&self) -> i64 {
        (self.arrival_time() - self.departure_time()).num_minutes()
    }

    pub fn connection_count(&self) -> usize {
        self.legs.len().saturating_sub(1)
    }

    pub fn connection_waits_minutes(&self) -> Vec<i64> {
        self.legs
            .windows(2)
            .map(|pair| (pair[1].departure_time - pair[0].arrival_time).num_minutes())
            .collect()
    }

    pub fn fingerprint_parts(&self) -> Vec<String> {
        let mut parts: Vec<String> = self
            .legs
            .iter()
            .flat_map(|leg| leg.segment_refs.clone())
            .collect();
        for leg in &self.legs {
            parts.push(leg.departure_time.to_rfc3339());
        }
        parts
    }

    pub fn compute_ref(legs: &[LegCandidate]) -> String {
        let mut parts: Vec<String> = legs
            .iter()
            .flat_map(|leg| leg.segment_refs.clone())
            .collect();
        for leg in legs {
            parts.push(leg.departure_time.to_rfc3339());
        }
        let refs: Vec<&str> = parts.iter().map(|s| s.as_str()).collect();
        stable_ref("itin", &refs)
    }
}

#[derive(Debug, Clone)]
pub struct ScoreComponent {
    pub name: String,
    pub value: i64,
    pub weight: i64,
    pub contribution: i64,
    pub explanation: String,
}

#[derive(Debug, Clone)]
pub struct PlanningScore {
    pub total: i64,
    pub profile_version: String,
    pub components: Vec<ScoreComponent>,
    pub explanation: String,
}

pub const RANKING_PROFILE_VERSION: &str = "trip-planning-search-v1";

pub fn validate_itinerary_for_intent(
    intent: &TripIntent,
    itinerary: &Itinerary,
) -> Vec<String> {
    let mut exclusions = Vec::new();

    if itinerary.origin_ref() != intent.origin_ref {
        exclusions.push("ORIGIN_MISMATCH".to_string());
    }
    if itinerary.destination_ref() != intent.destination_ref {
        exclusions.push("DESTINATION_MISMATCH".to_string());
    }
    let dep = itinerary.departure_time();
    if dep < intent.departure_window_start || dep > intent.departure_window_end {
        exclusions.push("DEPARTURE_OUTSIDE_WINDOW".to_string());
    }
    if !intent.preferences.allowed_modes.is_empty() {
        for leg in &itinerary.legs {
            if !intent.preferences.allowed_modes.contains(&leg.mode) {
                exclusions.push("MODE_NOT_ALLOWED".to_string());
                break;
            }
        }
    }
    if let Some(max_conn) = intent.preferences.max_connections {
        if itinerary.connection_count() > max_conn as usize {
            exclusions.push("TOO_MANY_CONNECTIONS".to_string());
        }
    }
    for wait in itinerary.connection_waits_minutes() {
        if wait < intent.preferences.min_connection_minutes as i64 {
            exclusions.push("IMPOSSIBLE_CONNECTION_WINDOW".to_string());
        }
        if let Some(max_wait) = intent.preferences.max_connection_wait_minutes {
            if wait > max_wait as i64 {
                exclusions.push("CONNECTION_WAIT_TOO_LONG".to_string());
            }
        }
    }
    if let Some(max_price) = intent.preferences.max_price_minor {
        if let Some(ref hint) = itinerary.price_hint {
            if hint.amount_minor > max_price {
                exclusions.push("PRICE_HINT_OVER_BUDGET".to_string());
            }
        }
    }
    if let Some(ref hint) = itinerary.availability_hint {
        if hint.status == "UNAVAILABLE" {
            exclusions.push("UNAVAILABLE_SNAPSHOT_HINT".to_string());
        }
    }

    exclusions
}

pub fn rank_itinerary(intent: &TripIntent, itinerary: &Itinerary) -> PlanningScore {
    let mut components = Vec::new();

    let duration_value = (1440 - itinerary.duration_minutes()).max(0);
    let duration_weight: i64 = if intent.preferences.prefer_short_duration {
        2
    } else {
        1
    };
    components.push(ScoreComponent {
        name: "duration".to_string(),
        value: duration_value,
        weight: duration_weight,
        contribution: duration_value * duration_weight,
        explanation: format!("{} minute end-to-end duration", itinerary.duration_minutes()),
    });

    let transfer_value = (500 - itinerary.connection_count() as i64 * 120).max(0);
    components.push(ScoreComponent {
        name: "connections".to_string(),
        value: transfer_value,
        weight: 1,
        contribution: transfer_value,
        explanation: format!("{} connection(s)", itinerary.connection_count()),
    });

    if let Some(ref hint) = itinerary.price_hint {
        let budget = intent
            .preferences
            .max_price_minor
            .unwrap_or(hint.amount_minor.max(1));
        let price_value = (1000 - ((hint.amount_minor as f64 / budget as f64) * 1000.0) as i64).max(0);
        let price_weight: i64 = if intent.preferences.prefer_low_price {
            2
        } else {
            1
        };
        components.push(ScoreComponent {
            name: "price_hint".to_string(),
            value: price_value,
            weight: price_weight,
            contribution: price_value * price_weight,
            explanation: format!(
                "non-authoritative {} {} price hint",
                hint.currency, hint.amount_minor
            ),
        });
    } else {
        components.push(ScoreComponent {
            name: "price_hint".to_string(),
            value: 50,
            weight: 1,
            contribution: 50,
            explanation: "no price hint; candidate remains searchable but less auditable"
                .to_string(),
        });
    }

    let availability_value = match itinerary.availability_hint.as_ref().map(|h| h.status.as_str()) {
        Some("AVAILABLE") => 200,
        Some("LIMITED") => 120,
        Some("UNKNOWN") => 60,
        Some("UNAVAILABLE") => 0,
        None => 80,
        _ => 60,
    };
    components.push(ScoreComponent {
        name: "availability_hint".to_string(),
        value: availability_value,
        weight: 1,
        contribution: availability_value,
        explanation: "non-authoritative availability snapshot only; no inventory lock".to_string(),
    });

    let total: i64 = components.iter().map(|c| c.contribution).sum();
    PlanningScore {
        total,
        profile_version: RANKING_PROFILE_VERSION.to_string(),
        components,
        explanation: format!(
            "Deterministic score {} from duration, connection count, price hint, \
             and availability hint. Hints are snapshots, not offers or locks.",
            total
        ),
    }
}

pub struct SearchResult {
    pub intent_ref: String,
    pub candidates: Vec<(Itinerary, PlanningScore)>,
}

pub fn search_itineraries(
    intent: &TripIntent,
    candidates: &[Itinerary],
) -> SearchResult {
    let mut accepted: Vec<(Itinerary, PlanningScore)> = Vec::new();

    for candidate in candidates {
        let exclusions = validate_itinerary_for_intent(intent, candidate);
        if exclusions.is_empty() {
            let score = rank_itinerary(intent, candidate);
            accepted.push((candidate.clone(), score));
        }
    }

    accepted.sort_by(|a, b| {
        b.1.total
            .cmp(&a.1.total)
            .then_with(|| a.0.arrival_time().cmp(&b.0.arrival_time()))
            .then_with(|| a.0.itinerary_ref.cmp(&b.0.itinerary_ref))
    });

    let fp_parts = intent.fingerprint_parts();
    let fp_refs: Vec<&str> = fp_parts.iter().map(|s| s.as_str()).collect();
    let intent_ref = stable_ref("intent", &fp_refs);

    SearchResult {
        intent_ref,
        candidates: accepted,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    fn make_intent() -> TripIntent {
        TripIntent {
            origin_ref: "station-A".to_string(),
            destination_ref: "station-B".to_string(),
            departure_window_start: Utc.with_ymd_and_hms(2026, 7, 10, 0, 0, 0).unwrap(),
            departure_window_end: Utc.with_ymd_and_hms(2026, 7, 10, 23, 59, 59).unwrap(),
            passenger_count: 1,
            preferences: PreferenceConstraints::default(),
        }
    }

    fn make_itinerary(dep_hour: u32, arr_hour: u32) -> Itinerary {
        let dep = Utc.with_ymd_and_hms(2026, 7, 10, dep_hour, 0, 0).unwrap();
        let arr = Utc.with_ymd_and_hms(2026, 7, 10, arr_hour, 0, 0).unwrap();
        let leg = LegCandidate {
            service_plan_ref: "sp-1".to_string(),
            service_segment_ref: "seg-1".to_string(),
            origin_stop_ref: "station-A".to_string(),
            destination_stop_ref: "station-B".to_string(),
            departure_time: dep,
            arrival_time: arr,
            mode: "train".to_string(),
            stop_refs: vec!["station-A".to_string(), "station-B".to_string()],
            segment_refs: vec!["seg-1".to_string()],
        };
        let itin_ref = Itinerary::compute_ref(&[leg.clone()]);
        Itinerary {
            itinerary_ref: itin_ref,
            legs: vec![leg],
            price_hint: Some(PriceHint {
                amount_minor: 0,
                currency: "CNY".to_string(),
                snapshot_ref: "fare-snapshot:seg-1".to_string(),
                captured_at: dep,
                confidence: 50,
            }),
            availability_hint: Some(AvailabilityHint {
                status: "UNKNOWN".to_string(),
                snapshot_ref: "availability-snapshot:seg-1".to_string(),
                captured_at: dep,
                confidence: 50,
            }),
            planning_snapshot_refs: vec!["planning-snapshot:seg-1".to_string()],
            search_origin_ref: None,
            search_destination_ref: None,
        }
    }

    #[test]
    fn test_validate_passes_for_valid_itinerary() {
        let intent = make_intent();
        let itin = make_itinerary(9, 10);
        let exclusions = validate_itinerary_for_intent(&intent, &itin);
        assert!(exclusions.is_empty());
    }

    #[test]
    fn test_validate_rejects_departure_outside_window() {
        let intent = make_intent();
        let dep = Utc.with_ymd_and_hms(2026, 7, 11, 9, 0, 0).unwrap();
        let arr = Utc.with_ymd_and_hms(2026, 7, 11, 10, 0, 0).unwrap();
        let leg = LegCandidate {
            service_plan_ref: "sp-1".to_string(),
            service_segment_ref: "seg-1".to_string(),
            origin_stop_ref: "station-A".to_string(),
            destination_stop_ref: "station-B".to_string(),
            departure_time: dep,
            arrival_time: arr,
            mode: "train".to_string(),
            stop_refs: vec!["station-A".to_string(), "station-B".to_string()],
            segment_refs: vec!["seg-1".to_string()],
        };
        let itin = Itinerary {
            itinerary_ref: "itin_test".to_string(),
            legs: vec![leg],
            price_hint: None,
            availability_hint: None,
            planning_snapshot_refs: vec![],
            search_origin_ref: None,
            search_destination_ref: None,
        };
        let exclusions = validate_itinerary_for_intent(&intent, &itin);
        assert!(exclusions.contains(&"DEPARTURE_OUTSIDE_WINDOW".to_string()));
    }

    #[test]
    fn test_rank_prefers_shorter_duration() {
        let intent = make_intent();
        let short = make_itinerary(9, 10);
        let long = make_itinerary(9, 12);
        let short_score = rank_itinerary(&intent, &short);
        let long_score = rank_itinerary(&intent, &long);
        assert!(short_score.total > long_score.total);
    }

    #[test]
    fn test_search_orders_by_score_descending() {
        let intent = make_intent();
        let fast = make_itinerary(9, 10);
        let slow = make_itinerary(9, 14);
        let candidates = vec![slow.clone(), fast.clone()];
        let result = search_itineraries(&intent, &candidates);
        assert_eq!(result.candidates.len(), 2);
        assert!(result.candidates[0].1.total >= result.candidates[1].1.total);
    }

    #[test]
    fn test_stable_ref_deterministic() {
        let a = stable_ref("itin", &["seg-1", "2026-07-10T09:00:00+00:00"]);
        let b = stable_ref("itin", &["seg-1", "2026-07-10T09:00:00+00:00"]);
        assert_eq!(a, b);
        assert!(a.starts_with("itin_"));
    }
}
