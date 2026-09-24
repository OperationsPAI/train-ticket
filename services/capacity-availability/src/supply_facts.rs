use crate::application::AppError;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Route and segment lists change only when the service plan changes, so a short lived cache keeps
/// admission from repeating the same upstream reads for every Hold.
const ROUTE_CACHE_TTL: Duration = Duration::from_secs(30);
const SERVICE_SEGMENTS_CACHE_TTL: Duration = Duration::from_secs(30);
/// Published seat supply is invalidated quickly because it carries allocations. Two seconds is
/// shorter than the load generator polling interval and every Capacity Hold in the same pool is
/// still counted from the database.
const SEAT_SUPPLY_CACHE_TTL: Duration = Duration::from_secs(2);
const SUPPLY_CACHE_CAPACITY: usize = 4096;

struct CacheEntry<T> {
    value: T,
    expires_at: Instant,
}

fn cache_lookup<T: Clone>(
    cache: &Mutex<HashMap<String, CacheEntry<T>>>,
    key: &str,
) -> Option<T> {
    let guard = cache.lock().ok()?;
    let entry = guard.get(key)?;
    if entry.expires_at <= Instant::now() {
        return None;
    }
    Some(entry.value.clone())
}

fn cache_store<T>(cache: &Mutex<HashMap<String, CacheEntry<T>>>, key: String, value: T, ttl: Duration) {
    if let Ok(mut guard) = cache.lock() {
        if guard.len() >= SUPPLY_CACHE_CAPACITY {
            guard.clear();
        }
        guard.insert(
            key,
            CacheEntry {
                value,
                expires_at: Instant::now() + ttl,
            },
        );
    }
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ReservationSupply {
    pub segment_booking_id: String,
    pub journey_order_id: String,
    pub traveler_ref: String,
    pub segment_ref: String,
    pub scheduled_service_ref: String,
    pub service_date: String,
    pub class_ref: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SegmentRoute {
    pub segment_ref: String,
    pub scheduled_service_ref: String,
    pub service_date: String,
    pub stop_sequence_version: String,
    pub stops: Vec<RouteStop>,
    pub interval: Interval,
    pub departure_time: String,
    pub arrival_time: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RouteStop {
    pub sequence: u32,
    pub node_ref: String,
}

pub struct PreparedSupply {
    pub segments: Vec<String>,
    pub routes: HashMap<String, SegmentRoute>,
}

impl PreparedSupply {
    pub fn route(&self, segment: &str) -> Result<&SegmentRoute, AppError> {
        self.routes
            .get(segment)
            .ok_or_else(|| AppError::Unavailable("segment has no validated route interval".into()))
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SeatSupply {
    pub scheduled_service_ref: String,
    pub service_date: String,
    pub class_ref: String,
    pub seat_map_id: String,
    pub seat_map_version: u64,
    pub composition_version: String,
    pub captured_at: String,
    pub units: Vec<PhysicalUnit>,
    pub allocations: Vec<Allocation>,
    pub standing_policy: Option<StandingPolicy>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StandingPolicy {
    pub version: String,
    pub basis_class: String,
    pub basis_units: usize,
    pub percent: usize,
    pub limit: usize,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PhysicalUnit {
    pub seat_unit_ref: String,
    pub assignable: bool,
    pub allocation_type: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Allocation {
    pub seat_allocation_id: String,
    pub capacity_hold_id: String,
    pub scheduled_service_ref: String,
    pub service_date: String,
    pub segment_booking_id: String,
    pub journey_order_id: String,
    pub traveler_ref: String,
    pub interval: Interval,
    pub seat_ref: SeatReference,
    pub status: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SeatReference {
    #[serde(default)]
    pub seat_unit_ref: String,
    pub allocation_type: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Interval {
    pub from_seq: u32,
    pub to_seq: u32,
}

impl Interval {
    pub fn overlaps(&self, other: &Self) -> bool {
        self.from_seq < other.to_seq && other.from_seq < self.to_seq
    }
}

#[derive(Debug, Clone)]
pub struct PendingOccupancy {
    pub hold_id: String,
    pub segment_booking_id: String,
    pub journey_order_id: String,
    pub traveler_ref: String,
    pub interval: Interval,
}

impl SeatSupply {
    pub fn capacity(&self) -> usize {
        self.standing_policy
            .as_ref()
            .map(|policy| policy.limit)
            .unwrap_or(self.units.len())
    }

    pub fn capacity_refs(&self) -> Vec<String> {
        if let Some(policy) = &self.standing_policy {
            return (0..policy.limit)
                .map(|index| format!("quota:{}:{}", policy.version, index))
                .collect();
        }
        self.units
            .iter()
            .map(|unit| unit.seat_unit_ref.clone())
            .collect()
    }

    pub fn validate(&self, requested: &ReservationSupply) -> Result<(), AppError> {
        require(
            self.scheduled_service_ref == requested.scheduled_service_ref
                && self.service_date == requested.service_date
                && self.class_ref == requested.class_ref,
            "seat supply references differ from the requested reservation",
        )?;
        require(
            !self.seat_map_id.is_empty()
                && self.seat_map_version > 0
                && !self.composition_version.is_empty(),
            "seat supply requires a published map version",
        )?;
        chrono::DateTime::parse_from_rfc3339(&self.captured_at)
            .map_err(|e| AppError::Unavailable(format!("seat supply capturedAt: {e}")))?;
        if self.class_ref == "STANDING" {
            let policy = self.standing_policy.as_ref().ok_or_else(|| {
                AppError::Unavailable("standing supply has no quota policy".into())
            })?;
            require(
                policy.version == "standing-second-class-10pct-v1"
                    && policy.basis_class == "SECOND_CLASS"
                    && policy.percent == 10
                    && policy.limit == policy.basis_units / 10
                    && self.units.is_empty(),
                "standing quota differs from its versioned policy",
            )?;
        } else {
            require(
                self.standing_policy.is_none(),
                "concrete seat supply cannot have a standing quota",
            )?;
        }
        let mut units = HashSet::new();
        for unit in &self.units {
            require(
                !unit.seat_unit_ref.is_empty()
                    && unit.assignable
                    && matches!(unit.allocation_type.as_str(), "SEAT" | "BERTH"),
                "seat supply contains an invalid physical unit",
            )?;
            require(
                units.insert(&unit.seat_unit_ref),
                "seat supply repeats a physical unit",
            )?;
        }
        let mut allocations = HashSet::new();
        for a in &self.allocations {
            require(
                a.scheduled_service_ref == self.scheduled_service_ref
                    && a.service_date == self.service_date,
                "allocation belongs to another service or date",
            )?;
            require(
                !a.capacity_hold_id.is_empty()
                    && !a.segment_booking_id.is_empty()
                    && !a.journey_order_id.is_empty()
                    && !a.traveler_ref.is_empty()
                    && ((self.class_ref == "STANDING" && a.seat_ref.allocation_type == "STANDING")
                        || (self.class_ref != "STANDING"
                            && matches!(a.seat_ref.allocation_type.as_str(), "SEAT" | "BERTH")
                            && !a.seat_ref.seat_unit_ref.is_empty()))
                    && matches!(a.status.as_str(), "ALLOCATED" | "CONFIRMED" | "STANDING")
                    && a.interval.from_seq < a.interval.to_seq,
                "seat supply contains an invalid allocation",
            )?;
            require(
                allocations.insert(&a.seat_allocation_id),
                "seat supply repeats an allocation",
            )?;
        }
        Ok(())
    }

    pub fn remaining(
        &self,
        interval: &Interval,
        holds: &[PendingOccupancy],
    ) -> Result<usize, AppError> {
        require(
            interval.from_seq < interval.to_seq,
            "requested station interval is invalid",
        )?;
        if self.class_ref == "STANDING" {
            let mut occupancy: HashMap<&str, (&str, &str, &str, &Interval)> = HashMap::new();
            for a in &self.allocations {
                require(
                    occupancy
                        .insert(
                            &a.capacity_hold_id,
                            (
                                &a.segment_booking_id,
                                &a.journey_order_id,
                                &a.traveler_ref,
                                &a.interval,
                            ),
                        )
                        .is_none(),
                    "standing allocation repeats a hold",
                )?;
            }
            let mut pending = HashSet::new();
            for hold in holds {
                require(
                    pending.insert(&hold.hold_id),
                    "standing occupancy repeats a pending hold",
                )?;
                if let Some(a) = occupancy.get(hold.hold_id.as_str()) {
                    require(
                        a.0 == hold.segment_booking_id
                            && a.1 == hold.journey_order_id
                            && a.2 == hold.traveler_ref
                            && a.3.from_seq == hold.interval.from_seq
                            && a.3.to_seq == hold.interval.to_seq,
                        "standing allocation differs from its capacity hold",
                    )?;
                } else {
                    occupancy.insert(
                        &hold.hold_id,
                        (
                            &hold.segment_booking_id,
                            &hold.journey_order_id,
                            &hold.traveler_ref,
                            &hold.interval,
                        ),
                    );
                }
            }
            let points = std::iter::once(interval.from_seq).chain(
                occupancy
                    .values()
                    .map(|a| a.3.from_seq)
                    .filter(|point| *point > interval.from_seq && *point < interval.to_seq),
            );
            let peak = points
                .map(|point| {
                    occupancy
                        .values()
                        .filter(|a| a.3.from_seq <= point && point < a.3.to_seq)
                        .count()
                })
                .max()
                .unwrap_or(0);
            return Ok(self.capacity().saturating_sub(peak));
        }
        let mut occupied: HashMap<&str, Vec<&Interval>> = HashMap::new();
        let mut allocated_holds = HashMap::new();
        for a in &self.allocations {
            if !a.interval.overlaps(interval) {
                continue;
            }
            let intervals = occupied.entry(&a.seat_ref.seat_unit_ref).or_default();
            require(
                intervals
                    .iter()
                    .all(|previous| !previous.overlaps(&a.interval)),
                "overlapping allocations occupy the same seat",
            )?;
            intervals.push(&a.interval);
            require(
                allocated_holds.insert(&a.capacity_hold_id, a).is_none(),
                "one hold has multiple overlapping allocations",
            )?;
        }
        let mut pending = HashSet::new();
        let mut seen_holds = HashSet::new();
        for hold in holds {
            require(
                seen_holds.insert(&hold.hold_id),
                "occupancy repeats a capacity Hold",
            )?;
            require(
                hold.interval.from_seq < hold.interval.to_seq,
                "capacity Hold interval is invalid",
            )?;
            if !hold.interval.overlaps(interval) {
                continue;
            }
            if let Some(a) = allocated_holds.get(&hold.hold_id) {
                require(
                    a.segment_booking_id == hold.segment_booking_id
                        && a.journey_order_id == hold.journey_order_id
                        && a.traveler_ref == hold.traveler_ref
                        && a.interval.from_seq == hold.interval.from_seq
                        && a.interval.to_seq == hold.interval.to_seq,
                    "seat allocation differs from its capacity hold",
                )?;
            } else {
                require(
                    pending.insert(&hold.hold_id),
                    "pending occupancy repeats a hold",
                )?;
            }
        }
        Ok(self
            .capacity()
            .saturating_sub(occupied.len() + pending.len()))
    }
}

#[derive(Clone)]
pub struct SupplyClient {
    client: reqwest::Client,
    base_template: String,
    reservation_permits: std::sync::Arc<tokio::sync::Semaphore>,
    routes: Arc<Mutex<HashMap<String, CacheEntry<SegmentRoute>>>>,
    services: Arc<Mutex<HashMap<String, CacheEntry<Vec<String>>>>>,
    seats: Arc<Mutex<HashMap<String, CacheEntry<SeatSupply>>>>,
}

impl SupplyClient {
    pub async fn route(&self, segment: &str, correlation: &str) -> Result<SegmentRoute, AppError> {
        if let Some(cached) = cache_lookup(&self.routes, segment) {
            return Ok(cached);
        }
        let route = self.fetch_route(segment, correlation).await?;
        cache_store(&self.routes, segment.to_owned(), route.clone(), ROUTE_CACHE_TTL);
        Ok(route)
    }

    async fn fetch_route(&self, segment: &str, correlation: &str) -> Result<SegmentRoute, AppError> {
        let value = self
            .get(
                "service-plan",
                &format!("/api/v1/internal/service-segments/{segment}/supply"),
                correlation,
            )
            .await?;
        let route: SegmentRoute =
            serde_json::from_value(value).map_err(|e| AppError::Unavailable(e.to_string()))?;
        require(
            route.segment_ref == segment
                && !route.scheduled_service_ref.is_empty()
                && route.stop_sequence_version.starts_with("sha256:"),
            "segment route identity is invalid",
        )?;
        require(
            route.stops.len() >= 2
                && route.stops.iter().enumerate().all(|(index, stop)| {
                    stop.sequence as usize == index && !stop.node_ref.is_empty()
                })
                && route.interval.from_seq < route.interval.to_seq
                && (route.interval.to_seq as usize) < route.stops.len(),
            "segment route interval is outside ordered stops",
        )?;
        let departure = chrono::DateTime::parse_from_rfc3339(&route.departure_time)
            .map_err(|e| AppError::Unavailable(e.to_string()))?;
        let arrival = chrono::DateTime::parse_from_rfc3339(&route.arrival_time)
            .map_err(|e| AppError::Unavailable(e.to_string()))?;
        require(
            departure < arrival
                && departure
                    .with_timezone(&chrono::Utc)
                    .date_naive()
                    .to_string()
                    == route.service_date,
            "segment route date or timestamps are inconsistent",
        )?;
        Ok(route)
    }

    pub fn new(base_template: String) -> Result<Self, AppError> {
        Ok(Self {
            client: reqwest::Client::builder()
                .timeout(Duration::from_secs(5))
                .build()
                .map_err(|e| AppError::Unavailable(e.to_string()))?,
            base_template,
            reservation_permits: std::sync::Arc::new(tokio::sync::Semaphore::new(8)),
            routes: Arc::new(Mutex::new(HashMap::new())),
            services: Arc::new(Mutex::new(HashMap::new())),
            seats: Arc::new(Mutex::new(HashMap::new())),
        })
    }

    pub async fn reservation(
        &self,
        booking_id: &str,
        correlation: &str,
    ) -> Result<ReservationSupply, AppError> {
        let _permit = self
            .reservation_permits
            .acquire()
            .await
            .map_err(|e| AppError::Unavailable(e.to_string()))?;
        let booking = self
            .get(
                "booking-orchestration",
                &format!("/api/v1/internal/segment-bookings/{booking_id}"),
                correlation,
            )
            .await?;
        require(
            text(&booking, "segmentBookingId")? == booking_id,
            "booking response has another reference",
        )?;
        let order_id = text(&booking, "journeyOrderId")?;
        if booking
            .get("scheduledServiceRef")
            .and_then(Value::as_str)
            .is_some()
            && booking.get("serviceDate").and_then(Value::as_str).is_some()
            && booking.get("classRef").and_then(Value::as_str).is_some()
        {
            let date = text(&booking, "serviceDate")?;
            chrono::NaiveDate::parse_from_str(date, "%Y-%m-%d")
                .map_err(|e| AppError::Unavailable(e.to_string()))?;
            return Ok(ReservationSupply {
                segment_booking_id: booking_id.to_owned(),
                journey_order_id: order_id.to_owned(),
                traveler_ref: text(&booking, "travelerRef")?.to_owned(),
                segment_ref: text(&booking, "segmentRef")?.to_owned(),
                scheduled_service_ref: text(&booking, "scheduledServiceRef")?.to_owned(),
                service_date: date.to_owned(),
                class_ref: text(&booking, "classRef")?.to_owned(),
            });
        }
        let order = self
            .get(
                "journey-order",
                &format!("/api/v1/journey-orders/{order_id}"),
                correlation,
            )
            .await?;
        require(
            text(&order, "orderId")? == order_id,
            "order response has another reference",
        )?;
        let purpose = text(&booking, "bookingPurpose")?;
        if booking
            .get("entitlementId")
            .and_then(Value::as_str)
            .is_some()
        {
            let allocation_request = self
                .client
                .get(format!(
                    "{}/api/v1/seat-allocations",
                    self.base("seat-assignment")
                ))
                .query(&[("segmentBookingId", booking_id), ("limit", "100")])
                .header("X-Correlation-Id", correlation);
            let response = rust_kit::outbound::inject_trace_context(allocation_request)
                .send()
                .await
                .map_err(|e| AppError::Unavailable(e.to_string()))?;
            if !response.status().is_success() {
                return Err(AppError::Unavailable(format!(
                    "reservation allocations returned {}",
                    response.status()
                )));
            }
            let page: Value = response
                .json()
                .await
                .map_err(|e| AppError::Unavailable(e.to_string()))?;
            if page["total"].as_u64() == Some(1) {
                let allocation = &page["items"][0];
                require(
                    text(allocation, "segmentBookingId")? == booking_id
                        && text(allocation, "journeyOrderId")? == order_id
                        && text(allocation, "travelerRef")? == text(&booking, "travelerRef")?
                        && text(allocation, "segmentRef")? == text(&booking, "segmentRef")?,
                    "allocation references differ from historical reservation",
                )?;
                let seat = &allocation["seatRef"];
                let class = if text(seat, "allocationType")? == "STANDING" {
                    "STANDING".to_owned()
                } else {
                    let map = self
                        .get(
                            "seat-assignment",
                            &format!("/api/v1/seat-maps/{}", text(seat, "seatMapId")?),
                            correlation,
                        )
                        .await?;
                    let coach = map["coaches"]
                        .as_array()
                        .and_then(|coaches| {
                            coaches.iter().find(|coach| {
                                coach["seatUnits"].as_array().is_some_and(|units| {
                                    units
                                        .iter()
                                        .any(|unit| unit["seatUnitRef"] == seat["seatUnitRef"])
                                })
                            })
                        })
                        .ok_or_else(|| {
                            AppError::Unavailable(
                                "historical allocation has no matching seat class".into(),
                            )
                        })?;
                    text(coach, "classRef")?.to_owned()
                };
                return Ok(ReservationSupply {
                    segment_booking_id: booking_id.to_owned(),
                    journey_order_id: order_id.to_owned(),
                    traveler_ref: text(&booking, "travelerRef")?.to_owned(),
                    segment_ref: text(&booking, "segmentRef")?.to_owned(),
                    scheduled_service_ref: text(allocation, "scheduledServiceRef")?.to_owned(),
                    service_date: text(allocation, "serviceDate")?.to_owned(),
                    class_ref: class,
                });
            }
            require(
                page["total"].as_u64() == Some(0),
                "historical reservation has ambiguous seat allocations",
            )?;
        }
        let offer_id = if let Some(case_id) = purpose.strip_prefix("change:") {
            let case = self
                .get(
                    "post-sales",
                    &format!("/api/v1/post-sales-cases/{case_id}"),
                    correlation,
                )
                .await?;
            require(
                text(&case, "journeyOrderId")? == order_id && text(&case, "caseId")? == case_id,
                "replacement case belongs to another order",
            )?;
            text(&case, "targetOfferId")?.to_owned()
        } else {
            require(purpose == "purchase", "reservation purpose is unsupported")?;
            let source = self
                .get(
                    "journey-order",
                    &format!("/api/v1/internal/journey-orders/{order_id}/purchase-source"),
                    correlation,
                )
                .await?;
            require(
                text(&source, "orderId")? == order_id
                    && text(&source, "accountId")? == text(&order, "accountId")?,
                "accepted purchase source belongs to another order or account",
            )?;
            text(&source, "timelineFactId")?;
            chrono::DateTime::parse_from_rfc3339(text(&source, "acceptedAt")?)
                .map_err(|e| AppError::Unavailable(e.to_string()))?;
            text(&source, "offerId")?.to_owned()
        };
        let offer = self
            .get(
                "offer-management",
                &format!("/api/v1/offers/{offer_id}"),
                correlation,
            )
            .await?;
        require(
            text(&offer, "offerId")? == offer_id
                && text(&offer, "accountId")? == text(&order, "accountId")?,
            "reservation offer belongs to another account",
        )?;
        let traveler = text(&booking, "travelerRef")?;
        require(
            offer["passengerMix"]["travelers"]
                .as_array()
                .is_some_and(|items| items.iter().any(|item| item["travelerId"] == traveler)),
            "reservation traveler is absent from offer",
        )?;
        let segment = text(&booking, "segmentRef")?;
        require(
            offer["items"]
                .as_array()
                .is_some_and(|items| items.iter().any(|item| item["segmentRef"] == segment)),
            "reservation segment is absent from offer",
        )?;
        let quote_id = text(&offer, "fareQuoteRef")?;
        let fare = self
            .get(
                "fare-pricing",
                &format!("/api/v1/fare-quotes/{quote_id}"),
                correlation,
            )
            .await?;
        require(
            text(&fare, "quoteId")? == quote_id,
            "fare quote response has another reference",
        )?;
        let itinerary_id = text(&offer, "itineraryRef")?;
        let itinerary = self
            .get(
                "trip-planning",
                &format!("/api/v1/itineraries/{itinerary_id}"),
                correlation,
            )
            .await?;
        require(
            text(&itinerary, "itineraryRef")? == itinerary_id,
            "itinerary response has another reference",
        )?;
        let leg = itinerary["legs"]
            .as_array()
            .and_then(|legs| legs.iter().find(|leg| leg["serviceSegmentRef"] == segment))
            .ok_or_else(|| {
                AppError::Unavailable("reservation segment is absent from itinerary".into())
            })?;
        let departure = chrono::DateTime::parse_from_rfc3339(text(leg, "departureTime")?)
            .map_err(|e| AppError::Unavailable(e.to_string()))?;
        Ok(ReservationSupply {
            segment_booking_id: booking_id.to_owned(),
            journey_order_id: order_id.to_owned(),
            traveler_ref: traveler.to_owned(),
            segment_ref: segment.to_owned(),
            scheduled_service_ref: text(leg, "servicePlanRef")?.to_owned(),
            service_date: departure
                .with_timezone(&chrono::Utc)
                .date_naive()
                .to_string(),
            class_ref: text(&fare["breakdown"]["seatClassMultiplier"], "seatClass")?.to_owned(),
        })
    }

    pub async fn seats(
        &self,
        reservation: &ReservationSupply,
        correlation: &str,
    ) -> Result<SeatSupply, AppError> {
        let key = format!(
            "{}|{}|{}",
            reservation.scheduled_service_ref, reservation.service_date, reservation.class_ref
        );
        if let Some(cached) = cache_lookup(&self.seats, &key) {
            cached.validate(reservation)?;
            return Ok(cached);
        }
        let supply = self.fetch_seats(reservation, correlation).await?;
        cache_store(&self.seats, key, supply.clone(), SEAT_SUPPLY_CACHE_TTL);
        Ok(supply)
    }

    async fn fetch_seats(
        &self,
        reservation: &ReservationSupply,
        correlation: &str,
    ) -> Result<SeatSupply, AppError> {
        let url = format!(
            "{}/api/v1/internal/capacity-facts",
            self.base("seat-assignment")
        );
        let request = self
            .client
            .get(url)
            .query(&[
                ("scheduledServiceRef", &reservation.scheduled_service_ref),
                ("serviceDate", &reservation.service_date),
                ("classRef", &reservation.class_ref),
            ])
            .header("X-Correlation-Id", correlation);
        let response = rust_kit::outbound::inject_trace_context(request)
            .send()
            .await
            .map_err(|e| AppError::Unavailable(e.to_string()))?;
        if !response.status().is_success() {
            return Err(AppError::Unavailable(format!(
                "seat supply returned {}",
                response.status()
            )));
        }
        let result: SeatSupply = response
            .json()
            .await
            .map_err(|e| AppError::Unavailable(e.to_string()))?;
        result.validate(reservation)?;
        Ok(result)
    }

    pub async fn segment(&self, segment: &str, correlation: &str) -> Result<Value, AppError> {
        let result = self
            .get(
                "service-plan",
                &format!("/api/v1/service-segments/{segment}"),
                correlation,
            )
            .await?;
        require(
            text(&result, "segmentRef")? == segment,
            "service segment response has another reference",
        )?;
        Ok(result)
    }

    pub async fn service_segments(
        &self,
        service: &str,
        correlation: &str,
    ) -> Result<Vec<String>, AppError> {
        if let Some(cached) = cache_lookup(&self.services, service) {
            return Ok(cached);
        }
        let segments = self.fetch_service_segments(service, correlation).await?;
        cache_store(
            &self.services,
            service.to_owned(),
            segments.clone(),
            SERVICE_SEGMENTS_CACHE_TTL,
        );
        Ok(segments)
    }

    async fn fetch_service_segments(
        &self,
        service: &str,
        correlation: &str,
    ) -> Result<Vec<String>, AppError> {
        let mut segments = std::collections::BTreeSet::new();
        let mut offset = 0usize;
        loop {
            let request = self
                .client
                .get(format!(
                    "{}/api/v1/service-segments",
                    self.base("service-plan")
                ))
                .query(&[
                    ("scheduledServiceRef", service.to_owned()),
                    ("limit", "100".to_owned()),
                    ("offset", offset.to_string()),
                ])
                .header("X-Correlation-Id", correlation);
            let response = rust_kit::outbound::inject_trace_context(request)
                .send()
                .await
                .map_err(|e| AppError::Unavailable(e.to_string()))?;
            if !response.status().is_success() {
                return Err(AppError::Unavailable(format!(
                    "service segments returned {}",
                    response.status()
                )));
            }
            let page: Value = response
                .json()
                .await
                .map_err(|e| AppError::Unavailable(e.to_string()))?;
            let total = page["total"]
                .as_u64()
                .ok_or_else(|| AppError::Unavailable("service segments total is missing".into()))?
                as usize;
            let items = page["items"].as_array().ok_or_else(|| {
                AppError::Unavailable("service segments items are missing".into())
            })?;
            require(
                page["offset"].as_u64() == Some(offset as u64)
                    && page["limit"].as_u64() == Some(100),
                "service segment pagination differs from request",
            )?;
            for segment in items {
                require(
                    text(segment, "scheduledServiceRef")? == service,
                    "listed segment belongs to another service",
                )?;
                require(
                    segments.insert(text(segment, "segmentRef")?.to_owned()),
                    "service segment listing repeats an identifier",
                )?;
            }
            offset += items.len();
            if offset >= total {
                require(offset == total, "service segment total differs from rows")?;
                break;
            }
            require(
                !items.is_empty(),
                "service segment listing ended before total",
            )?;
        }
        Ok(segments.into_iter().collect())
    }

    pub async fn published_classes(
        &self,
        service: &str,
        date: &str,
        correlation: &str,
    ) -> Result<Vec<String>, AppError> {
        let request = self
            .client
            .get(format!("{}/api/v1/seat-maps", self.base("seat-assignment")))
            .query(&[
                ("scheduledServiceRef", service),
                ("serviceDate", date),
                ("status", "PUBLISHED"),
                ("limit", "100"),
            ])
            .header("X-Correlation-Id", correlation);
        let response = rust_kit::outbound::inject_trace_context(request)
            .send()
            .await
            .map_err(|e| AppError::Unavailable(e.to_string()))?;
        if !response.status().is_success() {
            return Err(AppError::Unavailable(format!(
                "seat maps returned {}",
                response.status()
            )));
        }
        let maps: Value = response
            .json()
            .await
            .map_err(|e| AppError::Unavailable(e.to_string()))?;
        require(
            maps["total"].as_u64().is_some_and(|n| n <= 100),
            "published seat map response is incomplete",
        )?;
        let mut classes = std::collections::BTreeSet::new();
        for map in maps["items"]
            .as_array()
            .ok_or_else(|| AppError::Unavailable("seat map items are absent".into()))?
        {
            require(
                text(map, "scheduledServiceRef")? == service
                    && text(map, "serviceDate")? == date
                    && text(map, "status")? == "PUBLISHED",
                "seat map has inconsistent schedule facts",
            )?;
            for coach in map["coaches"]
                .as_array()
                .ok_or_else(|| AppError::Unavailable("seat map coaches are absent".into()))?
            {
                if coach["assignable"].as_bool() == Some(true) {
                    classes.insert(text(coach, "classRef")?.to_owned());
                }
            }
        }
        if classes.contains("SECOND_CLASS") {
            classes.insert("STANDING".to_owned());
        }
        Ok(classes.into_iter().collect())
    }

    fn base(&self, service: &str) -> String {
        self.base_template.replace("%s", service)
    }
    async fn get(&self, service: &str, path: &str, correlation: &str) -> Result<Value, AppError> {
        let request = self
            .client
            .get(format!("{}{path}", self.base(service)))
            .header("X-Correlation-Id", correlation);
        let response = rust_kit::outbound::inject_trace_context(request)
            .send()
            .await
            .map_err(|e| AppError::Unavailable(e.to_string()))?;
        if !response.status().is_success() {
            return Err(AppError::Unavailable(format!(
                "supply facts {service} returned {}",
                response.status()
            )));
        }
        response
            .json()
            .await
            .map_err(|e| AppError::Unavailable(e.to_string()))
    }
}

fn text<'a>(value: &'a Value, name: &str) -> Result<&'a str, AppError> {
    value
        .get(name)
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| AppError::Unavailable(format!("supply facts require {name}")))
}
fn require(condition: bool, message: &str) -> Result<(), AppError> {
    if condition {
        Ok(())
    } else {
        Err(AppError::Unavailable(message.into()))
    }
}
