use axum::{body::Body, http::Request};
use chrono::{NaiveDate, TimeZone, Utc};
use http_body_util::BodyExt;
use serde::Deserialize;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    sync::Arc,
    time::{Duration, Instant},
};
use tower::ServiceExt;
use trip_planning::{
    AppState, PlanIndex,
    domain::{PreferenceConstraints, TripIntent, search_itineraries},
    plan_index::SegmentEntry,
};

#[derive(Deserialize)]
struct Node {
    node_id: String,
    place_id: String,
}

#[derive(Deserialize)]
struct Route {
    origin_place: String,
    dest_place: String,
    date: NaiveDate,
}

#[derive(Deserialize)]
struct Recording {
    captured_at: String,
    segments: Vec<SegmentEntry>,
    nodes: Vec<Node>,
    routes: Vec<Route>,
}

fn recording() -> Recording {
    let path = std::env::var("TEST_SEARCH_RECORDING").expect("TEST_SEARCH_RECORDING is required");
    let source: Recording = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
    assert!(source.segments.len() >= 394_838);
    assert!(source.nodes.len() >= 389_797);
    assert_eq!(source.routes.len(), 45_816);
    source
}

fn index(source: &Recording) -> Arc<PlanIndex> {
    let index = Arc::new(PlanIndex::new());
    for segment in &source.segments {
        index.upsert_segment(segment.clone());
    }
    for node in &source.nodes {
        index.upsert_node(node.node_id.clone(), node.place_id.clone());
    }
    assert_eq!(index.segment_count(), source.segments.len());
    index
}

fn distribution(mut samples: Vec<f64>) -> Value {
    assert!(!samples.is_empty());
    samples.sort_by(f64::total_cmp);
    json!({"count":samples.len(), "mean":samples.iter().sum::<f64>() / samples.len() as f64,
        "p50":samples[samples.len()/2], "p95":samples[samples.len()*95/100],
        "p99":samples[samples.len()*99/100], "max":samples.last().unwrap()})
}

#[test]
#[ignore = "requires the complete recorded live plan index and loadgen routes"]
fn recorded_search_cpu_and_results() {
    let source = recording();
    let index = index(&source);
    let mut lookup_us = Vec::new();
    let mut ranking_us = Vec::new();
    let mut candidate_counts = Vec::new();
    let mut results = Vec::new();
    for route in &source.routes {
        let intent = TripIntent {
            origin_ref: route.origin_place.clone(),
            destination_ref: route.dest_place.clone(),
            departure_window_start: Utc
                .from_utc_datetime(&route.date.and_hms_opt(0, 0, 0).unwrap()),
            departure_window_end: Utc
                .from_utc_datetime(&route.date.and_hms_opt(23, 59, 59).unwrap()),
            passenger_count: 1,
            preferences: PreferenceConstraints::default(),
        };
        let start = Instant::now();
        let mut candidates = index.candidates(&route.origin_place, &route.dest_place, route.date);
        lookup_us.push(start.elapsed().as_secs_f64() * 1e6);
        candidate_counts.push(candidates.len() as f64);
        let start = Instant::now();
        for candidate in &mut candidates {
            candidate.search_origin_ref = Some(route.origin_place.clone());
            candidate.search_destination_ref = Some(route.dest_place.clone());
        }
        let result = search_itineraries(&intent, &candidates);
        ranking_us.push(start.elapsed().as_secs_f64() * 1e6);
        results.push(format!(
            "{:x}",
            Sha256::digest(format!("{}{:?}", result.intent_ref, result.candidates))
        ));
        if results.len() % 10_000 == 0 {
            eprintln!("measured {} recorded queries", results.len());
        }
    }
    if let Ok(path) = std::env::var("TEST_SEARCH_EXPECTED") {
        let expected: Vec<String> = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
        assert_eq!(
            results, expected,
            "every complete ranked candidate result must match the recorded baseline"
        );
    }
    if let Ok(path) = std::env::var("TEST_SEARCH_SAVE_RESULTS") {
        std::fs::write(path, serde_json::to_vec(&results).unwrap()).unwrap();
    }
    eprintln!(
        "{}",
        json!({"captured_at":source.captured_at,"segments":source.segments.len(),
        "nodes":source.nodes.len(),"routes":source.routes.len(),"lookup_us":distribution(lookup_us),
        "ranking_us":distribution(ranking_us),"candidate_counts":distribution(candidate_counts)})
    );
}

fn scan_refs(source: &Recording, origin: &str, destination: &str, date: NaiveDate) -> Vec<String> {
    let nodes: HashMap<_, _> = source
        .nodes
        .iter()
        .map(|node| (node.node_id.as_str(), node.place_id.as_str()))
        .collect();
    let matches = |stop: &str, requested: &str| {
        stop == requested
            || nodes.get(requested).is_some_and(|place| {
                stop == *place
                    || nodes
                        .get(stop)
                        .is_some_and(|stop_place| stop_place == place)
            })
            || nodes.get(stop).is_some_and(|place| *place == requested)
    };
    let mut refs: Vec<_> = source
        .segments
        .iter()
        .filter(|segment| {
            segment.departure_date == date
                && matches(&segment.origin_stop_ref, origin)
                && matches(&segment.destination_stop_ref, destination)
        })
        .map(|segment| segment.segment_ref.clone())
        .collect();
    refs.sort();
    refs
}

fn assert_scan(source: &Recording, index: &PlanIndex, segment: &SegmentEntry) {
    let nodes: HashMap<_, _> = source
        .nodes
        .iter()
        .map(|node| (node.node_id.as_str(), node.place_id.as_str()))
        .collect();
    let origin_place = nodes[segment.origin_stop_ref.as_str()];
    let destination_place = nodes[segment.destination_stop_ref.as_str()];
    for origin in [segment.origin_stop_ref.as_str(), origin_place] {
        for destination in [segment.destination_stop_ref.as_str(), destination_place] {
            let mut actual: Vec<_> = index
                .candidates(origin, destination, segment.departure_date)
                .into_iter()
                .map(|itinerary| itinerary.legs[0].service_segment_ref.clone())
                .collect();
            actual.sort();
            assert_eq!(
                actual,
                scan_refs(source, origin, destination, segment.departure_date)
            );
        }
    }
}

#[test]
#[ignore = "requires the complete recorded live plan index and loadgen routes"]
fn recorded_index_updates_match_scan() {
    let source = recording();
    let index = index(&source);
    let old = source.segments[0].clone();
    assert_scan(&source, &index, &old);
    index.upsert_segment(old.clone());
    assert_eq!(index.segment_count(), source.segments.len());
    assert_scan(&source, &index, &old);

    let other = source
        .segments
        .iter()
        .find(|segment| {
            segment.departure_date != old.departure_date
                && segment.origin_stop_ref != old.origin_stop_ref
                && segment.destination_stop_ref != old.destination_stop_ref
        })
        .unwrap()
        .clone();
    for segment in &source.segments {
        index.upsert_segment(segment.clone());
    }
    for node in &source.nodes {
        index.upsert_node(node.node_id.clone(), node.place_id.clone());
        index.upsert_node(node.node_id.clone(), node.place_id.clone());
    }
    assert_eq!(index.segment_count(), source.segments.len());
    assert_scan(&source, &index, &other);

    let late_nodes = PlanIndex::new();
    for segment in &source.segments {
        late_nodes.upsert_segment(segment.clone());
    }
    for node in &source.nodes {
        late_nodes.upsert_node(node.node_id.clone(), node.place_id.clone());
    }
    assert_scan(&source, &late_nodes, &old);
    assert_scan(&source, &late_nodes, &other);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "requires recorded live inventory and a dedicated local PostgreSQL database"]
async fn recorded_search_concurrent_postgres() {
    let source = Arc::new(recording());
    let purchases: Value = serde_json::from_slice(
        &std::fs::read(
            std::env::var("TEST_SEARCH_PURCHASES").expect("TEST_SEARCH_PURCHASES is required"),
        )
        .unwrap(),
    )
    .unwrap();
    let traveler = purchases["purchases"]
        .as_array()
        .unwrap()
        .iter()
        .find_map(|purchase| {
            purchase["traveler"]
                .as_str()
                .filter(|reference| !reference.is_empty())
        })
        .expect("a recorded purchase traveler is required")
        .to_string();
    let pool = sqlx::postgres::PgPoolOptions::new()
        .max_connections(10)
        .acquire_timeout(Duration::from_secs(5))
        .test_before_acquire(true)
        .connect(&std::env::var("TEST_DATABASE_URL").expect("TEST_DATABASE_URL is required"))
        .await
        .unwrap();
    rust_kit::storage::Storage::new(pool.clone())
        .migrate_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/migrations"))
        .await
        .unwrap();
    let before: i64 = sqlx::query_scalar("SELECT count(*) FROM outbox")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(before, 0, "the test requires an empty dedicated database");
    let state = Arc::new(AppState::new(
        Some(pool.clone()),
        index(&source),
        trip_planning::adapters::messaging::ITINERARY_STREAM,
    ));
    let app = trip_planning::router_with_state(state);
    let mut tasks = tokio::task::JoinSet::new();
    let start = Instant::now();
    for worker in 0..128 {
        let app = app.clone();
        let source = source.clone();
        let traveler = traveler.clone();
        tasks.spawn(async move {
            let mut responses = Vec::new();
            for number in (worker..source.routes.len()).step_by(128) {
                let route = &source.routes[number];
                let payload = json!({"originRef":route.origin_place,"destinationRef":route.dest_place,
                    "departureDate":route.date,"travelerRefs":[traveler],"channel":"WEB"});
                let request = Request::builder().method("POST").uri("/api/v1/itineraries/search")
                    .header("content-type","application/json")
                    .body(Body::from(serde_json::to_vec(&payload).unwrap())).unwrap();
                let start = Instant::now();
                let response = tokio::time::timeout(Duration::from_secs(10), app.clone().oneshot(request)).await;
                let elapsed = start.elapsed().as_secs_f64() * 1e3;
                let (status, digest) = match response {
                    Err(_) => (0, String::new()),
                    Ok(response) => {
                        let response = response.unwrap();
                        let status = response.status().as_u16();
                        let body = response.into_body().collect().await.unwrap().to_bytes();
                        let value: Value = serde_json::from_slice(&body).unwrap();
                        let digest = if status == 200 {
                            format!("{:x}", Sha256::digest(serde_json::to_vec(&value).unwrap()))
                        } else { String::new() };
                        (status, digest)
                    }
                };
                responses.push((number, status, elapsed, digest));
            }
            responses
        });
    }
    let mut responses = Vec::new();
    while let Some(result) = tasks.join_next().await {
        responses.extend(result.unwrap());
    }
    responses.sort_by_key(|response| response.0);
    let wall_seconds = start.elapsed().as_secs_f64();
    let mut statuses = HashMap::new();
    for response in &responses {
        *statuses.entry(response.1).or_insert(0) += 1;
    }
    let outbox: i64 = sqlx::query_scalar("SELECT count(*) FROM outbox")
        .fetch_one(&pool)
        .await
        .unwrap();
    let snapshots: i64 = sqlx::query_scalar("SELECT count(*) FROM itinerary_snapshots")
        .fetch_one(&pool)
        .await
        .unwrap();
    let digests: Vec<_> = responses
        .iter()
        .map(|response| response.3.clone())
        .collect();
    if let Ok(path) = std::env::var("TEST_SEARCH_HTTP_EXPECTED") {
        let expected: Vec<String> = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
        assert_eq!(
            digests, expected,
            "all HTTP responses must match the baseline"
        );
    }
    if let Ok(path) = std::env::var("TEST_SEARCH_HTTP_SAVE_RESULTS") {
        std::fs::write(path, serde_json::to_vec(&digests).unwrap()).unwrap();
    }
    eprintln!(
        "{}",
        json!({"routes":source.routes.len(),"concurrency":128,"workers":2,"pool":10,
        "timeout_seconds":10,"wall_seconds":wall_seconds,"statuses":statuses,"outbox":outbox,
        "snapshots":snapshots,"latency_ms":distribution(responses.iter().map(|response|response.2).collect())})
    );
    assert_eq!(statuses.get(&200), Some(&source.routes.len()));
    assert_eq!(outbox as usize, source.routes.len());
}
