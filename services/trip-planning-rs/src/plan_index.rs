//! In-memory plan segment index for sub-millisecond candidate lookup.
//!
//! Populated from the plan_segments DB table on startup and kept warm by
//! event subscription (ServicePlanChanged, TransportNodeRegistered, etc.).
//! `apply_event` reports each change so the caller writes it to those tables
//! as well; the in-memory map alone does not survive a restart.

use chrono::{DateTime, NaiveDate, Utc};
use serde_json::Value;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::RwLock;

use crate::domain::{AvailabilityHint, Itinerary, LegCandidate, PriceHint};

#[derive(Debug, Clone, serde::Deserialize)]
pub struct SegmentEntry {
    pub segment_ref: String,
    pub scheduled_service_ref: String,
    pub origin_stop_ref: String,
    pub destination_stop_ref: String,
    pub departure_time: DateTime<Utc>,
    pub departure_date: NaiveDate,
    pub arrival_time: DateTime<Utc>,
}

#[derive(Debug, Clone)]
pub struct NodeMapping {
    pub node_id: String,
    pub place_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MinimumConnectionRule {
    pub rule_id: String,
    pub version: i64,
    pub from_node_type: String,
    pub to_node_type: String,
    pub transfer_category: String,
    pub minimum_minutes: u32,
    pub conditions: Value,
    pub valid_from: DateTime<Utc>,
    pub valid_until: Option<DateTime<Utc>>,
}

impl MinimumConnectionRule {
    fn applies_at(&self, transfer_category: &str, at: DateTime<Utc>) -> bool {
        self.from_node_type == "STATION"
            && self.to_node_type == "STATION"
            && self.transfer_category == transfer_category
            && self.conditions.as_object().is_some_and(serde_json::Map::is_empty)
            && self.valid_from <= at
            && self.valid_until.is_none_or(|until| at < until)
    }
}

#[derive(Default)]
struct MinimumConnectionRuleIndex {
    active: HashMap<String, MinimumConnectionRule>,
    versions: HashMap<String, i64>,
}

/// What an inbound event changed in the index.
///
/// Returned by `apply_event` so the caller can write the same change to
/// `plan_segments` / `plan_nodes`. Without this the index exists only in
/// memory: the startup loader reads those tables, so every restart begins
/// with an empty index and the segments published while the pod was down are
/// unrecoverable, because the Redis consumer group is created at `$`.
#[derive(Debug, Clone)]
pub enum IndexMutation {
    Segment(SegmentEntry),
    Node(NodeMapping),
    MinimumConnectionRulePublished(MinimumConnectionRule),
    MinimumConnectionRuleRetired { rule_id: String, version: i64 },
    None,
}

/// Thread-safe in-memory index for plan segments and node->place mappings.
pub struct PlanIndex {
    segments: RwLock<SegmentIndex>,
    nodes: RwLock<NodeIndex>,
    minimum_connection_rules: RwLock<MinimumConnectionRuleIndex>,
}

impl Default for PlanIndex {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Default)]
struct SegmentIndex {
    by_ref: HashMap<String, SegmentEntry>,
    by_date_origin: BTreeMap<NaiveDate, HashMap<String, HashSet<String>>>,
}

#[derive(Default)]
struct NodeIndex {
    place_by_node: HashMap<String, String>,
    nodes_by_place: HashMap<String, HashSet<String>>,
}

impl PlanIndex {
    pub fn handles_event(event: &rust_kit::messaging::EventEnvelope) -> bool {
        matches!(
            event.event_type.as_str(),
            "ServicePlanChanged"
                | "ServiceSegmentCreated"
                | "TransportNodeRegistered"
                | "TransportNodeAdded"
                | "TransportNodeUpdated"
                | "MctRulePublished"
                | "MctRuleRetired"
        )
    }
    pub fn new() -> Self {
        Self {
            segments: RwLock::new(SegmentIndex::default()),
            nodes: RwLock::new(NodeIndex::default()),
            minimum_connection_rules: RwLock::new(MinimumConnectionRuleIndex::default()),
        }
    }

    pub fn upsert_segment(&self, entry: SegmentEntry) {
        let mut index = self.segments.write().unwrap();
        if let Some(previous) = index
            .by_ref
            .insert(entry.segment_ref.clone(), entry.clone())
        {
            if previous.departure_date == entry.departure_date
                && previous.origin_stop_ref == entry.origin_stop_ref
            {
                return;
            }
            let origins = index
                .by_date_origin
                .get_mut(&previous.departure_date)
                .unwrap();
            let refs = origins.get_mut(&previous.origin_stop_ref).unwrap();
            refs.remove(&previous.segment_ref);
            if refs.is_empty() {
                origins.remove(&previous.origin_stop_ref);
            }
            if origins.is_empty() {
                index.by_date_origin.remove(&previous.departure_date);
            }
        }
        index
            .by_date_origin
            .entry(entry.departure_date)
            .or_default()
            .entry(entry.origin_stop_ref)
            .or_default()
            .insert(entry.segment_ref);
    }

    pub fn upsert_node(&self, node_id: String, place_id: String) {
        let mut index = self.nodes.write().unwrap();
        if let Some(previous) = index
            .place_by_node
            .insert(node_id.clone(), place_id.clone())
        {
            if previous == place_id {
                return;
            }
            let nodes = index.nodes_by_place.get_mut(&previous).unwrap();
            nodes.remove(&node_id);
            if nodes.is_empty() {
                index.nodes_by_place.remove(&previous);
            }
        }
        index
            .nodes_by_place
            .entry(place_id)
            .or_default()
            .insert(node_id);
    }

    pub fn upsert_minimum_connection_rule(&self, rule: MinimumConnectionRule) -> bool {
        let mut index = self.minimum_connection_rules.write().unwrap();
        let current_version = index.versions.get(&rule.rule_id).copied().unwrap_or_default();
        if rule.version < current_version
            || (rule.version == current_version
                && index.versions.contains_key(&rule.rule_id)
                && !index.active.contains_key(&rule.rule_id))
        {
            return false;
        }
        index.versions.insert(rule.rule_id.clone(), rule.version);
        index.active.insert(rule.rule_id.clone(), rule);
        true
    }

    pub fn retire_minimum_connection_rule(&self, rule_id: &str, version: i64) -> bool {
        let mut index = self.minimum_connection_rules.write().unwrap();
        let current_version = index.versions.get(rule_id).copied().unwrap_or_default();
        if version < current_version {
            return false;
        }
        index.versions.insert(rule_id.to_string(), version);
        index.active.remove(rule_id);
        true
    }

    /// Finds candidate segments matching origin/destination/date using place resolution.
    pub fn candidates(
        &self,
        origin_ref: &str,
        destination_ref: &str,
        departure_date: NaiveDate,
    ) -> Vec<Itinerary> {
        let segments = self.segments.read().unwrap();
        let nodes = self.nodes.read().unwrap();

        let Some(origins) = segments.by_date_origin.get(&departure_date) else {
            return Vec::new();
        };

        // Resolve aliases at lookup time so node events also affect existing segments.
        let mut matching_origins = HashSet::from([origin_ref]);
        if let Some(place) = nodes.place_by_node.get(origin_ref) {
            matching_origins.insert(place.as_str());
            if let Some(refs) = nodes.nodes_by_place.get(place) {
                matching_origins.extend(refs.iter().map(String::as_str));
            }
        }
        if let Some(refs) = nodes.nodes_by_place.get(origin_ref) {
            matching_origins.extend(refs.iter().map(String::as_str));
        }
        let mut results = Vec::new();
        for origin in matching_origins {
            if let Some(refs) = origins.get(origin) {
                for segment_ref in refs {
                    let entry = &segments.by_ref[segment_ref];
                    if self.matches_ref(
                        &entry.destination_stop_ref,
                        destination_ref,
                        &nodes.place_by_node,
                    ) {
                        results.push(self.entry_to_itinerary(entry));
                    }
                }
            }
        }
        results
    }

    /// Finds direct and connected itineraries. Connected search is opt-in via
    /// `max_connections`; direct callers retain the existing lookup behavior.
    /// Every connection fails closed unless an applicable published MCT rule is
    /// present. A caller minimum can strengthen, but never weaken, that rule.
    pub fn candidates_with_connections(
        &self,
        origin_ref: &str,
        destination_ref: &str,
        departure_date: NaiveDate,
        max_connections: u32,
        requested_minimum_minutes: u32,
        maximum_wait_minutes: u32,
    ) -> Vec<Itinerary> {
        let direct = self.candidates(origin_ref, destination_ref, departure_date);
        if max_connections == 0 {
            return direct;
        }

        let segments = self.segments.read().unwrap();
        let nodes = self.nodes.read().unwrap();
        let rules = self.minimum_connection_rules.read().unwrap();
        let mut results = direct;
        let mut path = Vec::new();
        let mut path_rules = Vec::new();
        let mut used_segments = HashSet::new();

        self.extend_paths(
            &segments,
            &nodes,
            &rules.active,
            origin_ref,
            destination_ref,
            departure_date,
            max_connections as usize + 1,
            requested_minimum_minutes,
            maximum_wait_minutes,
            &mut path,
            &mut path_rules,
            &mut used_segments,
            &mut results,
        );

        results.sort_by(|left, right| {
            left.departure_time()
                .cmp(&right.departure_time())
                .then_with(|| left.arrival_time().cmp(&right.arrival_time()))
                .then_with(|| left.itinerary_ref.cmp(&right.itinerary_ref))
        });
        results.dedup_by(|left, right| left.itinerary_ref == right.itinerary_ref);
        results
    }

    #[allow(clippy::too_many_arguments)]
    fn extend_paths(
        &self,
        segments: &SegmentIndex,
        nodes: &NodeIndex,
        rules: &HashMap<String, MinimumConnectionRule>,
        current_origin: &str,
        destination_ref: &str,
        first_departure_date: NaiveDate,
        maximum_legs: usize,
        requested_minimum_minutes: u32,
        maximum_wait_minutes: u32,
        path: &mut Vec<SegmentEntry>,
        path_rules: &mut Vec<MinimumConnectionRule>,
        used_segments: &mut HashSet<String>,
        results: &mut Vec<Itinerary>,
    ) {
        if path.len() >= maximum_legs {
            return;
        }

        let earliest_departure = path.last().map(|entry| entry.arrival_time);
        let latest_departure = earliest_departure
            .map(|arrival| arrival + chrono::Duration::minutes(i64::from(maximum_wait_minutes)));
        let first_date = earliest_departure
            .map(|arrival| arrival.date_naive())
            .unwrap_or(first_departure_date);
        let last_date = latest_departure
            .map(|latest| latest.date_naive())
            .unwrap_or(first_departure_date);

        let matching_origins = equivalent_refs(current_origin, nodes);
        let mut next_entries = Vec::new();
        for (_date, origins) in segments.by_date_origin.range(first_date..=last_date) {
            for origin in &matching_origins {
                if let Some(references) = origins.get(*origin) {
                    next_entries.extend(
                        references
                            .iter()
                            .filter_map(|reference| segments.by_ref.get(reference)),
                    );
                }
            }
        }
        next_entries.sort_by(|left, right| {
            left.departure_time
                .cmp(&right.departure_time)
                .then_with(|| left.segment_ref.cmp(&right.segment_ref))
        });
        next_entries.dedup_by(|left, right| left.segment_ref == right.segment_ref);

        for entry in next_entries {
            if used_segments.contains(&entry.segment_ref) {
                continue;
            }

            let selected_rule = if let Some(previous) = path.last() {
                let wait_minutes = (entry.departure_time - previous.arrival_time).num_minutes();
                if wait_minutes < 0 || wait_minutes > i64::from(maximum_wait_minutes) {
                    continue;
                }
                let category = transfer_category(
                    &previous.destination_stop_ref,
                    &entry.origin_stop_ref,
                    nodes,
                );
                let Some(rule) = applicable_rule(rules, category, previous.arrival_time) else {
                    continue;
                };
                let required_minutes = requested_minimum_minutes.max(rule.minimum_minutes);
                if wait_minutes < i64::from(required_minutes) {
                    continue;
                }
                Some(rule.clone())
            } else {
                if entry.departure_date != first_departure_date {
                    continue;
                }
                None
            };

            used_segments.insert(entry.segment_ref.clone());
            path.push(entry.clone());
            if let Some(rule) = selected_rule {
                path_rules.push(rule);
            }

            if self.matches_ref(
                &entry.destination_stop_ref,
                destination_ref,
                &nodes.place_by_node,
            ) {
                if path.len() > 1 {
                    results.push(self.path_to_itinerary(path, path_rules));
                }
            } else {
                self.extend_paths(
                    segments,
                    nodes,
                    rules,
                    &entry.destination_stop_ref,
                    destination_ref,
                    first_departure_date,
                    maximum_legs,
                    requested_minimum_minutes,
                    maximum_wait_minutes,
                    path,
                    path_rules,
                    used_segments,
                    results,
                );
            }

            if path.len() > 1 {
                path_rules.pop();
            }
            path.pop();
            used_segments.remove(&entry.segment_ref);
        }
    }

    fn matches_ref(
        &self,
        stop_ref: &str,
        requested: &str,
        node_place: &HashMap<String, String>,
    ) -> bool {
        if stop_ref == requested {
            return true;
        }
        let requested_place = node_place.get(requested);
        let stop_place = node_place.get(stop_ref);

        if let Some(rp) = requested_place {
            if stop_ref == rp.as_str() {
                return true;
            }
            if let Some(sp) = stop_place {
                if sp == rp {
                    return true;
                }
            }
        }
        if let Some(sp) = stop_place {
            if sp == requested {
                return true;
            }
        }
        false
    }

    fn entry_to_itinerary(&self, entry: &SegmentEntry) -> Itinerary {
        let leg = segment_to_leg(entry);
        let itin_ref = Itinerary::compute_ref(std::slice::from_ref(&leg));
        Itinerary {
            itinerary_ref: itin_ref,
            legs: vec![leg],
            price_hint: Some(PriceHint {
                amount_minor: 0,
                currency: "CNY".to_string(),
                snapshot_ref: format!("fare-snapshot:{}", entry.segment_ref),
                captured_at: entry.departure_time,
                confidence: 50,
            }),
            availability_hint: Some(AvailabilityHint {
                status: "UNKNOWN".to_string(),
                snapshot_ref: format!("availability-snapshot:{}", entry.segment_ref),
                captured_at: entry.departure_time,
                confidence: 50,
            }),
            planning_snapshot_refs: vec![format!("planning-snapshot:{}", entry.segment_ref)],
            search_origin_ref: None,
            search_destination_ref: None,
        }
    }

    fn path_to_itinerary(
        &self,
        entries: &[SegmentEntry],
        rules: &[MinimumConnectionRule],
    ) -> Itinerary {
        let legs: Vec<LegCandidate> = entries.iter().map(segment_to_leg).collect();
        let itinerary_ref = Itinerary::compute_ref(&legs);
        let segment_refs: Vec<&str> = entries
            .iter()
            .map(|entry| entry.segment_ref.as_str())
            .collect();
        let mut planning_snapshot_refs: Vec<String> = segment_refs
            .iter()
            .map(|reference| format!("planning-snapshot:{reference}"))
            .collect();
        planning_snapshot_refs.extend(
            rules
                .iter()
                .map(|rule| format!("mct-rule:{}:v{}", rule.rule_id, rule.version)),
        );
        let first_departure = entries[0].departure_time;
        Itinerary {
            itinerary_ref,
            legs,
            price_hint: Some(PriceHint {
                amount_minor: 0,
                currency: "CNY".to_string(),
                snapshot_ref: format!("fare-snapshot:{}", segment_refs.join("+")),
                captured_at: first_departure,
                confidence: 50,
            }),
            availability_hint: Some(AvailabilityHint {
                status: "UNKNOWN".to_string(),
                snapshot_ref: format!("availability-snapshot:{}", segment_refs.join("+")),
                captured_at: first_departure,
                confidence: 50,
            }),
            planning_snapshot_refs,
            search_origin_ref: None,
            search_destination_ref: None,
        }
    }

    /// Apply an upstream event to the in-memory index.
    ///
    /// Returns what changed so the caller can persist it.
    pub fn apply_event(&self, event_type: &str, payload: &serde_json::Value) -> IndexMutation {
        match event_type {
            "ServicePlanPublished" | "ScheduledServiceCreated" => {
                // Services don't directly affect segment lookup, but we track them
                // for the scheduled_service_ref join.
                IndexMutation::None
            }
            "ServicePlanChanged" | "ServiceSegmentCreated" => {
                let seg = payload
                    .get("segmentRef")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let origin = payload
                    .get("originStopRef")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let dest = payload
                    .get("destinationStopRef")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let dep_raw = payload
                    .get("departureTime")
                    .and_then(|v| v.as_str())
                    .unwrap_or("");
                let arr_raw = payload
                    .get("arrivalTime")
                    .and_then(|v| v.as_str())
                    .unwrap_or(dep_raw);
                let service_ref = payload
                    .get("scheduledServiceRef")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();

                if seg.is_empty() || origin.is_empty() || dest.is_empty() || dep_raw.is_empty() {
                    return IndexMutation::None;
                }
                let Some(dep) = parse_datetime(dep_raw) else {
                    return IndexMutation::None;
                };
                let arr = parse_datetime(arr_raw).unwrap_or(dep);
                let date = dep.date_naive();

                let entry = SegmentEntry {
                    segment_ref: seg,
                    scheduled_service_ref: service_ref,
                    origin_stop_ref: origin,
                    destination_stop_ref: dest,
                    departure_time: dep,
                    departure_date: date,
                    arrival_time: arr,
                };
                self.upsert_segment(entry.clone());
                IndexMutation::Segment(entry)
            }
            "TransportNodeRegistered" | "TransportNodeAdded" | "TransportNodeUpdated" => {
                let node = payload
                    .get("nodeId")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let place = payload
                    .get("placeId")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                if node.is_empty() || place.is_empty() {
                    return IndexMutation::None;
                }
                self.upsert_node(node.clone(), place.clone());
                IndexMutation::Node(NodeMapping {
                    node_id: node,
                    place_id: place,
                })
            }
            "MctRulePublished" => {
                let Some(rule) = minimum_connection_rule(payload) else {
                    return IndexMutation::None;
                };
                if self.upsert_minimum_connection_rule(rule.clone()) {
                    IndexMutation::MinimumConnectionRulePublished(rule)
                } else {
                    IndexMutation::None
                }
            }
            "MctRuleRetired" => {
                let rule_id = payload
                    .get("mctRuleId")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .trim()
                    .to_string();
                let version = payload.get("version").and_then(Value::as_i64).unwrap_or(0);
                if rule_id.is_empty() || version < 1 {
                    return IndexMutation::None;
                }
                if self.retire_minimum_connection_rule(&rule_id, version) {
                    IndexMutation::MinimumConnectionRuleRetired { rule_id, version }
                } else {
                    IndexMutation::None
                }
            }
            _ => IndexMutation::None,
        }
    }

    pub fn segment_count(&self) -> usize {
        self.segments.read().unwrap().by_ref.len()
    }

    pub fn minimum_connection_rule_count(&self) -> usize {
        self.minimum_connection_rules.read().unwrap().active.len()
    }
}

fn equivalent_refs<'a>(reference: &'a str, nodes: &'a NodeIndex) -> HashSet<&'a str> {
    let mut refs = HashSet::from([reference]);
    if let Some(place) = nodes.place_by_node.get(reference) {
        refs.insert(place.as_str());
        if let Some(node_refs) = nodes.nodes_by_place.get(place) {
            refs.extend(node_refs.iter().map(String::as_str));
        }
    }
    if let Some(node_refs) = nodes.nodes_by_place.get(reference) {
        refs.extend(node_refs.iter().map(String::as_str));
    }
    refs
}

fn transfer_category<'a>(
    arrival_stop_ref: &'a str,
    departure_stop_ref: &'a str,
    nodes: &NodeIndex,
) -> &'a str {
    if refs_match(
        arrival_stop_ref,
        departure_stop_ref,
        &nodes.place_by_node,
    ) {
        "SAME_STATION"
    } else {
        // Service Plan does not publish enough topology to distinguish an
        // in-station platform change from a cross-station transfer. Do not
        // guess a category and accidentally apply a less restrictive rule.
        "UNCLASSIFIED"
    }
}

fn refs_match(
    stop_ref: &str,
    requested: &str,
    node_place: &HashMap<String, String>,
) -> bool {
    if stop_ref == requested {
        return true;
    }
    let requested_place = node_place.get(requested);
    let stop_place = node_place.get(stop_ref);
    if requested_place.is_some_and(|place| stop_ref == place) {
        return true;
    }
    if stop_place.is_some_and(|place| place == requested) {
        return true;
    }
    requested_place
        .zip(stop_place)
        .is_some_and(|(requested_place, stop_place)| requested_place == stop_place)
}

fn applicable_rule<'a>(
    rules: &'a HashMap<String, MinimumConnectionRule>,
    category: &str,
    at: DateTime<Utc>,
) -> Option<&'a MinimumConnectionRule> {
    rules
        .values()
        .filter(|rule| rule.applies_at(category, at))
        .max_by(|left, right| {
            (left.version, &left.rule_id).cmp(&(right.version, &right.rule_id))
        })
}

fn segment_to_leg(entry: &SegmentEntry) -> LegCandidate {
    LegCandidate {
        service_plan_ref: entry.scheduled_service_ref.clone(),
        service_segment_ref: entry.segment_ref.clone(),
        origin_stop_ref: entry.origin_stop_ref.clone(),
        destination_stop_ref: entry.destination_stop_ref.clone(),
        departure_time: entry.departure_time,
        arrival_time: entry.arrival_time,
        mode: "train".to_string(),
        stop_refs: vec![entry.origin_stop_ref.clone(), entry.destination_stop_ref.clone()],
        segment_refs: vec![entry.segment_ref.clone()],
    }
}

fn minimum_connection_rule(payload: &Value) -> Option<MinimumConnectionRule> {
    if payload.get("status").and_then(Value::as_str) != Some("PUBLISHED") {
        return None;
    }
    let minimum_minutes = u32::try_from(payload.get("minimumMinutes")?.as_u64()?).ok()?;
    let conditions = payload.get("conditions")?.as_object()?.clone();
    let rule = MinimumConnectionRule {
        rule_id: payload.get("mctRuleId")?.as_str()?.trim().to_string(),
        version: payload.get("version")?.as_i64()?,
        from_node_type: payload.get("fromNodeType")?.as_str()?.to_string(),
        to_node_type: payload.get("toNodeType")?.as_str()?.to_string(),
        transfer_category: payload.get("transferCategory")?.as_str()?.to_string(),
        minimum_minutes,
        conditions: Value::Object(conditions),
        valid_from: parse_datetime(payload.get("validFrom")?.as_str()?)?,
        valid_until: match payload.get("validUntil").and_then(Value::as_str) {
            Some(value) => Some(parse_datetime(value)?),
            None => None,
        },
    };
    (!rule.rule_id.is_empty() && rule.version > 0).then_some(rule)
}

fn parse_datetime(s: &str) -> Option<DateTime<Utc>> {
    // Handle both Z-suffix and +00:00 offset
    let normalized = s.replace('Z', "+00:00");
    DateTime::parse_from_rfc3339(&normalized)
        .ok()
        .map(|dt| dt.with_timezone(&Utc))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn segment(
        reference: &str,
        origin: &str,
        destination: &str,
        departure: &str,
        arrival: &str,
    ) -> SegmentEntry {
        let departure_time = parse_datetime(departure).unwrap();
        SegmentEntry {
            segment_ref: reference.to_string(),
            scheduled_service_ref: format!("ss-{reference}"),
            origin_stop_ref: origin.to_string(),
            destination_stop_ref: destination.to_string(),
            departure_time,
            departure_date: departure_time.date_naive(),
            arrival_time: parse_datetime(arrival).unwrap(),
        }
    }

    fn published_rule(
        rule_id: &str,
        version: i64,
        minimum_minutes: u32,
    ) -> MinimumConnectionRule {
        MinimumConnectionRule {
            rule_id: rule_id.to_string(),
            version,
            from_node_type: "STATION".to_string(),
            to_node_type: "STATION".to_string(),
            transfer_category: "SAME_STATION".to_string(),
            minimum_minutes,
            conditions: serde_json::json!({}),
            valid_from: parse_datetime("2026-07-01T00:00:00Z").unwrap(),
            valid_until: None,
        }
    }

    #[test]
    fn test_upsert_and_lookup() {
        let index = PlanIndex::new();
        let date = NaiveDate::from_ymd_opt(2026, 7, 10).unwrap();
        let dep = Utc::now();
        let arr = dep + chrono::Duration::hours(1);

        index.upsert_segment(SegmentEntry {
            segment_ref: "seg-1".to_string(),
            scheduled_service_ref: "sp-1".to_string(),
            origin_stop_ref: "A".to_string(),
            destination_stop_ref: "B".to_string(),
            departure_time: dep,
            departure_date: date,
            arrival_time: arr,
        });

        let results = index.candidates("A", "B", date);
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].legs[0].service_segment_ref, "seg-1");
    }

    #[test]
    fn test_node_place_resolution() {
        let index = PlanIndex::new();
        let date = NaiveDate::from_ymd_opt(2026, 7, 10).unwrap();
        let dep = Utc::now();
        let arr = dep + chrono::Duration::hours(1);

        index.upsert_node("node-A".to_string(), "place-A".to_string());
        index.upsert_node("node-B".to_string(), "place-B".to_string());

        index.upsert_segment(SegmentEntry {
            segment_ref: "seg-1".to_string(),
            scheduled_service_ref: "sp-1".to_string(),
            origin_stop_ref: "node-A".to_string(),
            destination_stop_ref: "node-B".to_string(),
            departure_time: dep,
            departure_date: date,
            arrival_time: arr,
        });

        // Lookup by place_id should work
        let results = index.candidates("place-A", "place-B", date);
        assert_eq!(results.len(), 1);
    }

    #[test]
    fn test_apply_event_segment() {
        let index = PlanIndex::new();
        let payload = serde_json::json!({
            "segmentRef": "seg-2",
            "originStopRef": "X",
            "destinationStopRef": "Y",
            "departureTime": "2026-07-10T08:00:00Z",
            "arrivalTime": "2026-07-10T09:00:00Z",
            "scheduledServiceRef": "sp-2"
        });
        index.apply_event("ServiceSegmentCreated", &payload);
        assert_eq!(index.segment_count(), 1);

        let date = NaiveDate::from_ymd_opt(2026, 7, 10).unwrap();
        let results = index.candidates("X", "Y", date);
        assert_eq!(results.len(), 1);
    }

    #[test]
    fn apply_event_reports_the_segment_to_persist() {
        let index = PlanIndex::new();
        let payload = serde_json::json!({
            "segmentRef": "seg-3",
            "originStopRef": "tnd-origin",
            "destinationStopRef": "tnd-dest",
            "departureTime": "2026-07-11T08:00:00Z",
            "arrivalTime": "2026-07-11T09:00:00Z",
            "scheduledServiceRef": "ss-3"
        });

        // service-plan publishes ServicePlanChanged when a segment is created.
        let mutation = index.apply_event("ServicePlanChanged", &payload);

        let IndexMutation::Segment(entry) = mutation else {
            panic!("segment event must report a segment to persist, got {mutation:?}");
        };
        assert_eq!(entry.segment_ref, "seg-3");
        assert_eq!(entry.scheduled_service_ref, "ss-3");
        assert_eq!(entry.origin_stop_ref, "tnd-origin");
        assert_eq!(entry.destination_stop_ref, "tnd-dest");
        assert_eq!(
            entry.departure_date,
            NaiveDate::from_ymd_opt(2026, 7, 11).unwrap()
        );
    }

    #[test]
    fn apply_event_reports_the_node_to_persist() {
        let index = PlanIndex::new();
        let payload = serde_json::json!({
            "nodeId": "tnd-1",
            "placeId": "plc-1"
        });

        let mutation = index.apply_event("TransportNodeRegistered", &payload);

        let IndexMutation::Node(mapping) = mutation else {
            panic!("node event must report a node to persist, got {mutation:?}");
        };
        assert_eq!(mapping.node_id, "tnd-1");
        assert_eq!(mapping.place_id, "plc-1");
    }

    #[test]
    fn apply_event_reports_nothing_for_unrelated_and_incomplete_events() {
        let index = PlanIndex::new();

        assert!(matches!(
            index.apply_event("CapacityHeld", &serde_json::json!({"holdId": "hold-1"})),
            IndexMutation::None
        ));
        assert!(matches!(
            index.apply_event(
                "ServicePlanChanged",
                &serde_json::json!({"segmentRef": "seg-4"})
            ),
            IndexMutation::None
        ));
        assert_eq!(index.segment_count(), 0);
    }

    #[test]
    fn connected_search_requires_a_published_rule_and_accepts_the_exact_boundary() {
        let index = PlanIndex::new();
        index.upsert_segment(segment(
            "seg-a-b",
            "A",
            "B",
            "2026-07-10T08:00:00Z",
            "2026-07-10T09:00:00Z",
        ));
        index.upsert_segment(segment(
            "seg-b-c-too-short",
            "B",
            "C",
            "2026-07-10T09:19:00Z",
            "2026-07-10T10:00:00Z",
        ));
        index.upsert_segment(segment(
            "seg-b-c-boundary",
            "B",
            "C",
            "2026-07-10T09:20:00Z",
            "2026-07-10T10:01:00Z",
        ));
        let date = NaiveDate::from_ymd_opt(2026, 7, 10).unwrap();

        assert!(
            index
                .candidates_with_connections("A", "C", date, 1, 5, 120)
                .is_empty(),
            "a connection without an applicable published MCT rule must fail closed"
        );

        assert!(index.upsert_minimum_connection_rule(published_rule("mct-1", 1, 20)));
        let candidates =
            index.candidates_with_connections("A", "C", date, 1, 5, 120);

        assert_eq!(candidates.len(), 1);
        assert_eq!(
            candidates[0]
                .legs
                .iter()
                .map(|leg| leg.service_segment_ref.as_str())
                .collect::<Vec<_>>(),
            vec!["seg-a-b", "seg-b-c-boundary"]
        );
        assert_eq!(
            candidates[0].planning_snapshot_refs.last().unwrap(),
            "mct-rule:mct-1:v1"
        );
    }

    #[test]
    fn connected_search_uses_the_stricter_client_minimum_and_highest_rule_version() {
        let index = PlanIndex::new();
        index.upsert_segment(segment(
            "seg-a-b",
            "A",
            "B",
            "2026-07-10T08:00:00Z",
            "2026-07-10T09:00:00Z",
        ));
        index.upsert_segment(segment(
            "seg-b-c",
            "B",
            "C",
            "2026-07-10T09:25:00Z",
            "2026-07-10T10:00:00Z",
        ));
        assert!(index.upsert_minimum_connection_rule(published_rule("mct-old", 1, 10)));
        assert!(index.upsert_minimum_connection_rule(published_rule("mct-new", 2, 20)));
        let date = NaiveDate::from_ymd_opt(2026, 7, 10).unwrap();

        let accepted = index.candidates_with_connections("A", "C", date, 1, 20, 120);
        assert_eq!(accepted.len(), 1);
        assert_eq!(
            accepted[0].planning_snapshot_refs.last().unwrap(),
            "mct-rule:mct-new:v2"
        );
        assert!(
            index
                .candidates_with_connections("A", "C", date, 1, 30, 120)
                .is_empty(),
            "the request may require more time than the published minimum"
        );
    }

    #[test]
    fn expired_conditional_and_retired_rules_do_not_authorize_connections() {
        let index = PlanIndex::new();
        index.upsert_segment(segment(
            "seg-a-b",
            "A",
            "B",
            "2026-07-10T08:00:00Z",
            "2026-07-10T09:00:00Z",
        ));
        index.upsert_segment(segment(
            "seg-b-c",
            "B",
            "C",
            "2026-07-10T09:30:00Z",
            "2026-07-10T10:00:00Z",
        ));
        let date = NaiveDate::from_ymd_opt(2026, 7, 10).unwrap();

        let mut expired = published_rule("mct-expired", 1, 10);
        expired.valid_until = Some(parse_datetime("2026-07-10T09:00:00Z").unwrap());
        assert!(index.upsert_minimum_connection_rule(expired));
        let mut conditional = published_rule("mct-conditional", 2, 10);
        conditional.conditions = serde_json::json!({"serviceClass": "HIGH_SPEED"});
        assert!(index.upsert_minimum_connection_rule(conditional));
        assert!(
            index
                .candidates_with_connections("A", "C", date, 1, 5, 120)
                .is_empty()
        );

        let published = published_rule("mct-live", 3, 20);
        assert!(index.upsert_minimum_connection_rule(published.clone()));
        assert_eq!(
            index
                .candidates_with_connections("A", "C", date, 1, 5, 120)
                .len(),
            1
        );
        assert!(index.retire_minimum_connection_rule("mct-live", 3));
        assert!(!index.upsert_minimum_connection_rule(published));
        assert!(
            index
                .candidates_with_connections("A", "C", date, 1, 5, 120)
                .is_empty(),
            "an out-of-order publish at the retired version must not revive the rule"
        );
    }

    #[test]
    fn published_and_retired_events_update_the_rule_index() {
        let index = PlanIndex::new();
        let published = serde_json::json!({
            "mctRuleId": "mct-event",
            "version": 4,
            "status": "PUBLISHED",
            "fromNodeType": "STATION",
            "toNodeType": "STATION",
            "transferCategory": "SAME_STATION",
            "minimumMinutes": 18,
            "conditions": {},
            "validFrom": "2026-07-01T00:00:00Z"
        });

        assert!(matches!(
            index.apply_event("MctRulePublished", &published),
            IndexMutation::MinimumConnectionRulePublished(_)
        ));
        assert_eq!(index.minimum_connection_rule_count(), 1);
        assert!(matches!(
            index.apply_event(
                "MctRuleRetired",
                &serde_json::json!({
                    "mctRuleId": "mct-event",
                    "version": 4,
                    "status": "RETIRED"
                })
            ),
            IndexMutation::MinimumConnectionRuleRetired { .. }
        ));
        assert_eq!(index.minimum_connection_rule_count(), 0);
        assert!(matches!(
            index.apply_event("MctRulePublished", &published),
            IndexMutation::None
        ));
    }
}
