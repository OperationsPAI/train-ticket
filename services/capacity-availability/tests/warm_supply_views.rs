//! Warming the seat views must cover the class the admission will read and, when
//! the reservation accepts standing, the standing fallback as well.
//!
//! Capacity reads those views inside the transaction that holds the pool's
//! admission lock -- one lock for the whole service and date -- so a fetch that
//! waits on seat-assignment, or on the client's five second timeout, is a hold
//! that every other reservation for that train queues behind. This pins that the
//! wait happens before the lock and that a second admission reuses it.

use std::collections::BTreeMap;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::sync::{Arc, Mutex};

use capacity_availability::supply_facts::{ReservationSupply, SupplyClient};

fn query_param(path: &str, name: &str) -> String {
    let query = path.split_once('?').map(|(_, query)| query).unwrap_or("");
    for pair in query.split('&') {
        if let Some((key, value)) = pair.split_once('=') {
            if key == name {
                return value.to_owned();
            }
        }
    }
    String::new()
}

fn facts_body(class_ref: &str, view: &str) -> String {
    let identity = format!(
        r#""scheduledServiceRef":"ss-1","serviceDate":"2026-09-25","classRef":"{class_ref}","seatMapId":"map-1","seatMapVersion":2,"compositionVersion":"v1","capturedAt":"2026-09-25T00:00:00Z""#
    );
    if view == "composition" {
        if class_ref == "STANDING" {
            // Standing has no physical unit: it is a quota over the second class,
            // and the supply refuses a standing composition without its policy.
            return format!(
                r#"{{{identity},"units":[],"standingPolicy":{{"version":"standing-second-class-10pct-v1","basisClass":"SECOND_CLASS","basisUnits":100,"percent":10,"limit":10}},"replacementMappings":[]}}"#
            );
        }
        format!(
            r#"{{{identity},"units":[{{"seatUnitRef":"seat-a","assignable":true,"allocationType":"SEAT"}}],"replacementMappings":[]}}"#
        )
    } else {
        format!(r#"{{{identity},"allocations":[]}}"#)
    }
}

/// Serves the capacity-facts view of both classes and records what was asked for.
fn spawn_supply_stub() -> (String, Arc<Mutex<BTreeMap<String, usize>>>) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind stub");
    let address = listener.local_addr().expect("stub address");
    let seen: Arc<Mutex<BTreeMap<String, usize>>> = Arc::new(Mutex::new(BTreeMap::new()));
    let counted = Arc::clone(&seen);
    std::thread::spawn(move || {
        for connection in listener.incoming() {
            let Ok(mut stream) = connection else { break };
            let mut buffer = [0u8; 4096];
            let read = stream.read(&mut buffer).unwrap_or(0);
            let request = String::from_utf8_lossy(&buffer[..read]).to_string();
            let path = request.split_whitespace().nth(1).unwrap_or("/").to_owned();
            let class_ref = query_param(&path, "classRef");
            let view = query_param(&path, "view");
            *counted
                .lock()
                .expect("stub counts")
                .entry(format!("{class_ref}/{view}"))
                .or_insert(0) += 1;
            let body = facts_body(&class_ref, &view);
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                body.len(),
                body
            );
            let _ = stream.write_all(response.as_bytes());
            let _ = stream.flush();
        }
    });
    (format!("http://{address}"), seen)
}

fn reservation(class_ref: &str, standing_accepted: bool) -> ReservationSupply {
    ReservationSupply {
        segment_booking_id: "sb-1".into(),
        journey_order_id: "ord-1".into(),
        traveler_ref: "tvl-1".into(),
        segment_ref: "seg-1".into(),
        scheduled_service_ref: "ss-1".into(),
        service_date: "2026-09-25".into(),
        class_ref: class_ref.into(),
        booking_purpose: "purchase".into(),
        offer_item_ref: "ofi-1".into(),
        standing_accepted,
        source_type: "PURCHASE_ORDER".into(),
        source_ref: "ord-1".into(),
    }
}

#[tokio::test]
async fn warms_the_accepted_class_and_the_standing_fallback() {
    let (base, seen) = spawn_supply_stub();
    let client = SupplyClient::new(base).expect("supply client");
    let facts = reservation("SECOND_CLASS", true);

    client.warm_supply_views(&facts, "corr-1").await;
    client.warm_supply_views(&facts, "corr-1").await;

    let counts = seen.lock().expect("stub counts").clone();
    assert_eq!(
        counts.get("SECOND_CLASS/occupancy"),
        Some(&1),
        "the accepted class is fetched once and then cached: {counts:?}"
    );
    assert_eq!(
        counts.get("SECOND_CLASS/composition"),
        Some(&1),
        "{counts:?}"
    );
    assert_eq!(
        counts.get("STANDING/occupancy"),
        Some(&1),
        "a reservation that accepts standing reads the fallback inside its lock: {counts:?}"
    );
    assert_eq!(counts.get("STANDING/composition"), Some(&1), "{counts:?}");
}

#[tokio::test]
async fn leaves_standing_alone_when_the_reservation_refuses_it() {
    let (base, seen) = spawn_supply_stub();
    let client = SupplyClient::new(base).expect("supply client");

    client
        .warm_supply_views(&reservation("SECOND_CLASS", false), "corr-2")
        .await;

    let counts = seen.lock().expect("stub counts").clone();
    assert_eq!(counts.get("SECOND_CLASS/occupancy"), Some(&1), "{counts:?}");
    assert!(
        !counts.keys().any(|key| key.starts_with("STANDING/")),
        "no fallback is reachable, so none is fetched: {counts:?}"
    );
}
