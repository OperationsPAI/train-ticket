//! In-memory plan segment index for sub-millisecond candidate lookup.
//!
//! Populated from the plan_segments DB table on startup and kept warm by
//! event subscription (ServicePlanPublished, PlaceNetworkUpdated, etc.).

use chrono::{DateTime, NaiveDate, Utc};
use std::collections::HashMap;
use std::sync::RwLock;

use crate::domain::{AvailabilityHint, Itinerary, LegCandidate, PriceHint};

#[derive(Debug, Clone)]
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

/// Thread-safe in-memory index for plan segments and node->place mappings.
pub struct PlanIndex {
    /// Segments indexed by departure_date for fast date-prefix lookups.
    segments_by_date: RwLock<HashMap<NaiveDate, Vec<SegmentEntry>>>,
    /// node_id -> place_id mapping.
    node_place: RwLock<HashMap<String, String>>,
}

impl PlanIndex {
    pub fn new() -> Self {
        Self {
            segments_by_date: RwLock::new(HashMap::new()),
            node_place: RwLock::new(HashMap::new()),
        }
    }

    pub fn upsert_segment(&self, entry: SegmentEntry) {
        let mut map = self.segments_by_date.write().unwrap();
        let entries = map.entry(entry.departure_date).or_default();
        // Replace existing entry with same segment_ref, or insert new.
        if let Some(existing) = entries
            .iter_mut()
            .find(|e| e.segment_ref == entry.segment_ref)
        {
            *existing = entry;
        } else {
            entries.push(entry);
        }
    }

    pub fn upsert_node(&self, node_id: String, place_id: String) {
        let mut map = self.node_place.write().unwrap();
        map.insert(node_id, place_id);
    }

    /// Finds candidate segments matching origin/destination/date using place resolution.
    pub fn candidates(
        &self,
        origin_ref: &str,
        destination_ref: &str,
        departure_date: NaiveDate,
    ) -> Vec<Itinerary> {
        let segments = self.segments_by_date.read().unwrap();
        let node_place = self.node_place.read().unwrap();

        let Some(entries) = segments.get(&departure_date) else {
            return Vec::new();
        };

        let mut results = Vec::new();
        for entry in entries {
            if self.matches_ref(&entry.origin_stop_ref, origin_ref, &node_place)
                && self.matches_ref(&entry.destination_stop_ref, destination_ref, &node_place)
            {
                results.push(self.entry_to_itinerary(entry));
            }
        }
        results
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
        let leg = LegCandidate {
            service_plan_ref: entry.scheduled_service_ref.clone(),
            service_segment_ref: entry.segment_ref.clone(),
            origin_stop_ref: entry.origin_stop_ref.clone(),
            destination_stop_ref: entry.destination_stop_ref.clone(),
            departure_time: entry.departure_time,
            arrival_time: entry.arrival_time,
            mode: "train".to_string(),
            stop_refs: vec![
                entry.origin_stop_ref.clone(),
                entry.destination_stop_ref.clone(),
            ],
            segment_refs: vec![entry.segment_ref.clone()],
        };
        let itin_ref = Itinerary::compute_ref(&[leg.clone()]);
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

    /// Apply an upstream event to the in-memory index.
    pub fn apply_event(&self, event_type: &str, payload: &serde_json::Value) {
        match event_type {
            "ServicePlanPublished" | "ScheduledServiceCreated" => {
                // Services don't directly affect segment lookup, but we track them
                // for the scheduled_service_ref join.
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
                    return;
                }
                let Some(dep) = parse_datetime(dep_raw) else {
                    return;
                };
                let arr = parse_datetime(arr_raw).unwrap_or(dep);
                let date = dep.date_naive();

                self.upsert_segment(SegmentEntry {
                    segment_ref: seg,
                    scheduled_service_ref: service_ref,
                    origin_stop_ref: origin,
                    destination_stop_ref: dest,
                    departure_time: dep,
                    departure_date: date,
                    arrival_time: arr,
                });
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
                if !node.is_empty() && !place.is_empty() {
                    self.upsert_node(node, place);
                }
            }
            _ => {}
        }
    }

    pub fn segment_count(&self) -> usize {
        self.segments_by_date
            .read()
            .unwrap()
            .values()
            .map(|v| v.len())
            .sum()
    }
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
}
