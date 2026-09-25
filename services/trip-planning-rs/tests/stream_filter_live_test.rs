use rust_kit::messaging::EventEnvelope;
use serde_json::Value;
use trip_planning::PlanIndex;

#[test]
fn recorded_capacity_events_do_not_change_plan_index() {
    let Ok(path) = std::env::var("TEST_CAPACITY_EVENTS") else {
        return;
    };
    let entries: Value = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
    let index = PlanIndex::new();
    for entry in entries.as_array().unwrap() {
        let event: EventEnvelope = serde_json::from_str(entry[1][1].as_str().unwrap()).unwrap();
        assert!(!PlanIndex::handles_event(&event));
        assert!(matches!(
            index.apply_event(&event.event_type, &event.payload),
            trip_planning::plan_index::IndexMutation::None
        ));
    }
    let plans: Value =
        serde_json::from_slice(&std::fs::read(std::env::var("TEST_PLAN_EVENTS").unwrap()).unwrap())
            .unwrap();
    let mut selected = 0;
    for entry in plans.as_array().unwrap() {
        let event: EventEnvelope = serde_json::from_str(entry[1][1].as_str().unwrap()).unwrap();
        if PlanIndex::handles_event(&event) {
            index.apply_event(&event.event_type, &event.payload);
            selected += 1;
        }
    }
    assert!(selected > 0);
    assert!(index.segment_count() > 0);
}
