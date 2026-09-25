use std::collections::HashSet;
use std::time::{Duration, Instant};
use trip_planning::plan_index::{PlanIndex, SegmentEntry};

#[test]
fn recorded_plan_index_rebuild_and_replay() {
    let Ok(path) = std::env::var("TEST_PLAN_SEGMENTS") else {
        return;
    };
    let bytes = std::fs::read(path).unwrap();
    let source: Vec<SegmentEntry> = serde_json::from_slice(&bytes).unwrap();
    assert!(
        source.len() > 100_000,
        "full recorded plan index is required"
    );
    let first = source.first().unwrap().clone();
    let ids: HashSet<_> = source
        .iter()
        .map(|entry| entry.segment_ref.clone())
        .collect();
    let expected: HashSet<_> = source
        .iter()
        .filter(|entry| {
            entry.departure_date == first.departure_date
                && entry.origin_stop_ref == first.origin_stop_ref
                && entry.destination_stop_ref == first.destination_stop_ref
        })
        .map(|entry| entry.segment_ref.clone())
        .collect();
    let index = PlanIndex::new();
    let start = Instant::now();
    for entry in &source {
        index.upsert_segment(entry.clone());
    }
    let elapsed = start.elapsed();
    assert!(
        elapsed < Duration::from_secs(10),
        "index rebuild took {elapsed:?}"
    );
    assert_eq!(index.segment_count(), ids.len());
    for entry in source {
        index.upsert_segment(entry);
    }
    assert_eq!(index.segment_count(), ids.len());
    let actual: HashSet<_> = index
        .candidates(
            &first.origin_stop_ref,
            &first.destination_stop_ref,
            first.departure_date,
        )
        .iter()
        .map(|itinerary| itinerary.legs[0].service_segment_ref.clone())
        .collect();
    assert_eq!(actual, expected);
    eprintln!("rebuilt {} recorded segments in {elapsed:?}", ids.len());
}
