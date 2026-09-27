//! HTTP API layer per docs/08-contracts/api/trip-planning.md

use axum::{
    Json, Router,
    extract::Extension,
    http::StatusCode,
    routing::{get, post},
};
use chrono::{NaiveDate, TimeZone, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use shared_kernel::RequestContext;
use std::sync::Arc;

use crate::application::AppState;
use crate::domain::{Itinerary, PreferenceConstraints, TripIntent, search_itineraries};

const MAX_CONNECTIONS: u32 = 3;
const DEFAULT_MAX_CONNECTION_WAIT_MINUTES: u32 = 24 * 60;

pub fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route(
            "/api/v1/itineraries/search",
            post(search_itineraries_handler),
        )
        .route(
            "/api/v1/itineraries/{itineraryRef}",
            get(get_itinerary_handler),
        )
        .layer(Extension(state))
}

// ---------------------------------------------------------------------------
// POST /api/v1/itineraries/search
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchRequest {
    pub origin_ref: String,
    pub destination_ref: String,
    pub departure_date: String,
    pub return_date: Option<String>,
    pub traveler_refs: Vec<String>,
    pub channel: String,
    pub max_results: Option<u32>,
    pub max_connections: Option<u32>,
    pub min_connection_minutes: Option<u32>,
    pub max_connection_wait_minutes: Option<u32>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchResponse {
    pub intent_ref: String,
    pub itineraries: Vec<ItineraryJson>,
    pub planning_snapshot_refs: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ItineraryJson {
    pub itinerary_ref: String,
    pub legs: Vec<LegJson>,
    pub price_hint: Option<PriceHintJson>,
    pub availability_hint: Option<AvailabilityHintJson>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LegJson {
    pub service_plan_ref: String,
    pub service_segment_ref: String,
    pub origin_stop_ref: String,
    pub destination_stop_ref: String,
    pub departure_time: String,
    pub arrival_time: String,
    pub mode: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PriceHintJson {
    pub currency: String,
    pub minor_units: i64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AvailabilityHintJson {
    pub status: String,
    pub confidence: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ErrorResponse {
    pub code: String,
    pub message: String,
    pub correlation_id: String,
    pub details: Value,
}

/// One search attempt on the order funnel.
///
/// `attempted` is recorded when the guard is built and exactly one outcome when
/// it is dropped, so every exit records a rejection -- the early `return Err`
/// paths, a `?` that propagates a storage failure, and a panic alike. Recording
/// the two outcomes at each return instead would need one transition per exit,
/// and the first one missed would break `attempted = accepted + rejected`, which
/// is the only identity a reader of this counter relies on.
struct SearchAttempt<'a> {
    funnel: &'a rust_kit::metrics::OrderFunnel,
    channel: &'a str,
    accepted: bool,
}

impl<'a> SearchAttempt<'a> {
    fn begin(funnel: &'a rust_kit::metrics::OrderFunnel, channel: &'a str) -> Self {
        funnel.transition("search", "attempted", channel);
        Self {
            funnel,
            channel,
            accepted: false,
        }
    }

    fn accept(&mut self) {
        self.accepted = true;
        self.funnel.transition("search", "accepted", self.channel);
    }
}

impl Drop for SearchAttempt<'_> {
    fn drop(&mut self) {
        if !self.accepted {
            self.funnel.transition("search", "rejected", self.channel);
        }
    }
}

async fn search_itineraries_handler(
    Extension(state): Extension<Arc<AppState>>,
    Extension(context): Extension<RequestContext>,
    body: Result<Json<SearchRequest>, axum::extract::rejection::JsonRejection>,
) -> Result<Json<SearchResponse>, (StatusCode, Json<ErrorResponse>)> {
    let correlation_id = context.correlation_id().to_string();

    let Json(req) = body.map_err(|e| {
        (
            StatusCode::BAD_REQUEST,
            Json(ErrorResponse {
                code: "VALIDATION_FAILED".to_string(),
                message: format!("Request validation failed: {}", e.body_text()),
                correlation_id: correlation_id.clone(),
                details: serde_json::json!({}),
            }),
        )
    })?;

    // From here on the request is a search and its channel is known. The guard
    // records the attempt now and the outcome on every exit.
    let mut attempt = SearchAttempt::begin(state.order_funnel(), &req.channel);

    // Validate required fields
    if let Err(msg) = validate_search_request(&req) {
        return Err((
            StatusCode::BAD_REQUEST,
            Json(ErrorResponse {
                code: "VALIDATION_FAILED".to_string(),
                message: msg,
                correlation_id,
                details: serde_json::json!({}),
            }),
        ));
    }

    let max_results = req.max_results.unwrap_or(10).clamp(1, 50);
    let departure_date =
        NaiveDate::parse_from_str(&req.departure_date, "%Y-%m-%d").map_err(|_| {
            (
                StatusCode::BAD_REQUEST,
                Json(ErrorResponse {
                    code: "VALIDATION_FAILED".to_string(),
                    message: "departureDate must be an ISO-8601 date".to_string(),
                    correlation_id: correlation_id.clone(),
                    details: serde_json::json!({}),
                }),
            )
        })?;

    // Build TripIntent
    let window_start = Utc
        .from_local_datetime(&departure_date.and_hms_opt(0, 0, 0).unwrap())
        .unwrap();
    let window_end = Utc
        .from_local_datetime(&departure_date.and_hms_opt(23, 59, 59).unwrap())
        .unwrap();

    let preferences = PreferenceConstraints {
        max_connections: req.max_connections,
        min_connection_minutes: req.min_connection_minutes.unwrap_or(5),
        max_connection_wait_minutes: req.max_connection_wait_minutes,
        ..PreferenceConstraints::default()
    };

    let intent = TripIntent {
        origin_ref: req.origin_ref.trim().to_string(),
        destination_ref: req.destination_ref.trim().to_string(),
        departure_window_start: window_start,
        departure_window_end: window_end,
        passenger_count: req.traveler_refs.len() as u32,
        preferences,
    };

    let max_connections = intent.preferences.max_connections.unwrap_or(0);
    let maximum_wait_minutes = intent
        .preferences
        .max_connection_wait_minutes
        .unwrap_or(DEFAULT_MAX_CONNECTION_WAIT_MINUTES);
    let real_candidates = state
        .search_candidates(
            &intent.origin_ref,
            &intent.destination_ref,
            departure_date,
            max_connections,
            intent.preferences.min_connection_minutes,
            maximum_wait_minutes,
        )
        .await
        .map_err(|error| storage_error(error, &correlation_id))?;

    // No candidate means no itinerary. An empty result is the honest answer and
    // the one every caller already handles: the load generator records
    // `available-train: no routes` as a real user outcome, and the legacy facade
    // returns "no itinerary found".
    //
    // It used to synthesise one instead, with a segment ref of its own making
    // (`contract_candidate` below). That is worse than an empty result rather
    // than a softer version of it: the caller receives something that looks
    // bookable, quotes and offers against a segment no other service knows, and
    // the refusal surfaces three services later as offer-management's
    // `No consumed Fare Pricing quote`, which names neither this service nor the
    // missing inventory.
    //
    // Measured: 7119 of 15017 itineraries offer-management projected in four
    // minutes carried a synthetic `seg-web-...` ref, and that refusal was 634 of
    // 763 recorded journey failures while every consumer sat caught up with its
    // stream.
    let candidate_pool: Vec<Itinerary> = real_candidates
        .into_iter()
        .map(|c| candidate_for_intent(c, &intent.origin_ref, &intent.destination_ref))
        .collect();

    let result = search_itineraries(&intent, &candidate_pool);
    let selected: Vec<_> = result
        .candidates
        .into_iter()
        .take(max_results as usize)
        .collect();

    let itineraries: Vec<ItineraryJson> = selected
        .iter()
        .map(|(itin, _score)| itinerary_to_contract(itin))
        .collect();

    let planning_snapshot_refs: Vec<String> = selected
        .iter()
        .flat_map(|(itin, _)| itin.planning_snapshot_refs.clone())
        .collect();

    let response = SearchResponse {
        intent_ref: result.intent_ref.clone(),
        itineraries: itineraries.clone(),
        planning_snapshot_refs: planning_snapshot_refs.clone(),
    };

    // Persist itinerary snapshots in one statement for the whole search.
    let snapshots: Vec<(String, serde_json::Value)> = itineraries
        .iter()
        .map(|itinerary| {
            (
                itinerary.itinerary_ref.clone(),
                serde_json::to_value(itinerary).unwrap_or_default(),
            )
        })
        .collect();
    state
        .save_itineraries(&snapshots)
        .await
        .map_err(|error| storage_error(error, &correlation_id))?;

    // Publish ItineraryProposed event via outbox
    state
        .publish_itinerary_proposed(
            &result.intent_ref,
            &itineraries,
            &planning_snapshot_refs,
            &correlation_id,
        )
        .await;

    attempt.accept();
    Ok(Json(response))
}

// ---------------------------------------------------------------------------
// GET /api/v1/itineraries/{itineraryRef}
// ---------------------------------------------------------------------------

async fn get_itinerary_handler(
    Extension(state): Extension<Arc<AppState>>,
    Extension(context): Extension<RequestContext>,
    axum::extract::Path(itinerary_ref): axum::extract::Path<String>,
) -> Result<Json<Value>, (StatusCode, Json<ErrorResponse>)> {
    let correlation_id = context.correlation_id().to_string();

    if let Some(snapshot) = state
        .get_itinerary(&itinerary_ref)
        .await
        .map_err(|error| storage_error(error, &correlation_id))?
    {
        return Ok(Json(snapshot));
    }

    Err((
        StatusCode::NOT_FOUND,
        Json(ErrorResponse {
            code: "NOT_FOUND".to_string(),
            message: format!("Itinerary {} not found", itinerary_ref),
            correlation_id,
            details: serde_json::json!({}),
        }),
    ))
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fn storage_error(
    error: rust_kit::storage::StorageError,
    correlation_id: &str,
) -> (StatusCode, Json<ErrorResponse>) {
    log::error!("itinerary storage failed: {}", error);
    (
        StatusCode::SERVICE_UNAVAILABLE,
        Json(ErrorResponse {
            code: "UNAVAILABLE".into(),
            message: "Itinerary storage unavailable".into(),
            correlation_id: correlation_id.into(),
            details: serde_json::json!({}),
        }),
    )
}

fn validate_search_request(req: &SearchRequest) -> Result<(), String> {
    if req.origin_ref.trim().is_empty() {
        return Err("originRef is required".to_string());
    }
    if req.destination_ref.trim().is_empty() {
        return Err("destinationRef is required".to_string());
    }
    if req.departure_date.trim().is_empty() {
        return Err("departureDate is required".to_string());
    }
    if req.traveler_refs.is_empty() {
        return Err("travelerRefs must be a non-empty array".to_string());
    }
    for tref in &req.traveler_refs {
        if tref.trim().is_empty() {
            return Err("travelerRefs must contain only strings".to_string());
        }
    }
    if req.channel.trim().is_empty() {
        return Err("channel is required".to_string());
    }
    if req
        .max_results
        .is_some_and(|value| !(1..=50).contains(&value))
    {
        return Err("maxResults must be between 1 and 50".to_string());
    }
    if req
        .max_connections
        .is_some_and(|value| value > MAX_CONNECTIONS)
    {
        return Err(format!(
            "maxConnections must be between 0 and {MAX_CONNECTIONS}"
        ));
    }
    if req
        .min_connection_minutes
        .is_some_and(|value| value > DEFAULT_MAX_CONNECTION_WAIT_MINUTES)
    {
        return Err(format!(
            "minConnectionMinutes must be between 0 and {DEFAULT_MAX_CONNECTION_WAIT_MINUTES}"
        ));
    }
    if let Some(maximum) = req.max_connection_wait_minutes {
        if maximum == 0 || maximum > DEFAULT_MAX_CONNECTION_WAIT_MINUTES {
            return Err(format!(
                "maxConnectionWaitMinutes must be between 1 and {DEFAULT_MAX_CONNECTION_WAIT_MINUTES}"
            ));
        }
        if req
            .min_connection_minutes
            .is_some_and(|minimum| minimum > maximum)
        {
            return Err(
                "minConnectionMinutes must not exceed maxConnectionWaitMinutes".to_string(),
            );
        }
    }
    Ok(())
}

fn candidate_for_intent(
    candidate: Itinerary,
    origin_ref: &str,
    destination_ref: &str,
) -> Itinerary {
    if candidate.origin_ref() == origin_ref && candidate.destination_ref() == destination_ref {
        return candidate;
    }
    Itinerary {
        search_origin_ref: Some(origin_ref.to_string()),
        search_destination_ref: Some(destination_ref.to_string()),
        ..candidate
    }
}

fn itinerary_to_contract(itinerary: &Itinerary) -> ItineraryJson {
    ItineraryJson {
        itinerary_ref: itinerary.itinerary_ref.clone(),
        legs: itinerary
            .legs
            .iter()
            .map(|leg| LegJson {
                service_plan_ref: leg.service_plan_ref.clone(),
                service_segment_ref: leg.service_segment_ref.clone(),
                origin_stop_ref: leg.origin_stop_ref.clone(),
                destination_stop_ref: leg.destination_stop_ref.clone(),
                departure_time: leg.departure_time.format("%Y-%m-%dT%H:%M:%SZ").to_string(),
                arrival_time: leg.arrival_time.format("%Y-%m-%dT%H:%M:%SZ").to_string(),
                mode: leg.mode.clone(),
            })
            .collect(),
        price_hint: itinerary.price_hint.as_ref().map(|h| PriceHintJson {
            currency: h.currency.clone(),
            minor_units: h.amount_minor,
        }),
        availability_hint: itinerary
            .availability_hint
            .as_ref()
            .map(|h| AvailabilityHintJson {
                status: h.status.clone(),
                confidence: h.confidence,
            }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use opentelemetry::metrics::MeterProvider;
    use opentelemetry_sdk::metrics::data::{AggregatedMetrics, MetricData};
    use opentelemetry_sdk::metrics::{InMemoryMetricExporter, SdkMeterProvider};

    /// Every exit from a search has to record exactly one outcome, because
    /// `attempted = accepted + rejected` is the identity a reader of the funnel
    /// relies on. The guard is what makes that true for the early `return Err`
    /// paths and for a `?` that propagates a storage failure, so both are checked
    /// here: the successful exit, and an exit that abandons the guard.
    ///
    /// The provider is local to this test rather than the process-global one, so
    /// it cannot race with the other tests in this binary.
    fn funnel_with_exporter() -> (rust_kit::metrics::OrderFunnel, InMemoryMetricExporter, SdkMeterProvider) {
        let exporter = InMemoryMetricExporter::default();
        let provider = SdkMeterProvider::builder()
            .with_periodic_exporter(exporter.clone())
            .build();
        let funnel = rust_kit::metrics::OrderFunnel::new(&provider.meter("trip-planning-test"));
        (funnel, exporter, provider)
    }

    fn points(exporter: &InMemoryMetricExporter) -> std::collections::BTreeMap<String, u64> {
        let mut found = std::collections::BTreeMap::new();
        for resource_metric in exporter.get_finished_metrics().expect("finished metrics") {
            for scope_metric in resource_metric.scope_metrics() {
                for metric in scope_metric.metrics() {
                    if metric.name() != "train_ticket.order.transitions" {
                        continue;
                    }
                    let AggregatedMetrics::U64(MetricData::Sum(sum)) = metric.data() else {
                        panic!("the funnel counter is a u64 sum");
                    };
                    for point in sum.data_points() {
                        let mut outcome = String::new();
                        let mut channel = String::new();
                        for kv in point.attributes() {
                            match kv.key.as_str() {
                                "outcome" => outcome = kv.value.to_string(),
                                "channel" => channel = kv.value.to_string(),
                                _ => {}
                            }
                        }
                        found.insert(format!("{outcome}/{channel}"), point.value());
                    }
                }
            }
        }
        found
    }

    #[test]
    fn an_accepted_search_records_one_attempt_and_one_acceptance() {
        let (funnel, exporter, provider) = funnel_with_exporter();

        let mut attempt = SearchAttempt::begin(&funnel, "web");
        attempt.accept();
        drop(attempt);
        provider.force_flush().expect("flush metrics");

        assert_eq!(
            points(&exporter),
            std::collections::BTreeMap::from([
                ("accepted/web".to_string(), 1),
                ("attempted/web".to_string(), 1),
            ])
        );
    }

    #[test]
    fn an_abandoned_search_records_a_rejection_and_never_an_acceptance() {
        let (funnel, exporter, provider) = funnel_with_exporter();

        {
            // Standing in for a `return Err(...)` or a `?`: the guard is dropped
            // without ever being accepted.
            let _attempt = SearchAttempt::begin(&funnel, "acme-partner-7");
        }
        provider.force_flush().expect("flush metrics");

        assert_eq!(
            points(&exporter),
            std::collections::BTreeMap::from([
                // An unknown channel collapses to `other` rather than becoming a
                // series of its own.
                ("attempted/other".to_string(), 1),
                ("rejected/other".to_string(), 1),
            ])
        );
    }
}
