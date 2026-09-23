use crate::*;
use serde::de::DeserializeOwned;
use std::time::Duration;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct BookingDetails {
    segment_booking_id: String,
    journey_order_id: String,
    traveler_ref: String,
    segment_ref: String,
    status: String,
    capacity_hold_id: Option<String>,
    #[serde(default)]
    booking_purpose: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ChangeAuthorization {
    authorized: bool,
    case_id: String,
    journey_order_id: String,
    segment_booking_id: String,
    traveler_ref: String,
    segment_ref: String,
    payment_intent_id: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CapacityDetails {
    hold_id: String,
    segment_ref: String,
    segment_booking_ref: String,
    traveler_ref: Option<String>,
    status: String,
    capacity_unit_ref: String,
    interval: StationIntervalDto,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct MoneyDetails {
    minor_units: i64,
    currency: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PaymentDetails {
    payment_intent_id: String,
    business_ref: String,
    payer_ref: String,
    status: String,
    captured_amount: MoneyDetails,
    refunded_amount: MoneyDetails,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct OrderDetails {
    order_id: String,
    offer_id: String,
    account_id: String,
    status: String,
    risk_clear: bool,
    traveler_refs: Vec<String>,
    segment_refs: Vec<String>,
    monetary_summary: OrderMoney,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct OrderMoney {
    payable_total: MoneyDetails,
}

async fn read<T: DeserializeOwned>(
    client: &reqwest::Client,
    service: &str,
    setting: &str,
    path: &str,
    correlation_id: &str,
) -> ApiResult<T> {
    let base = std::env::var(setting).unwrap_or_else(|_| format!("http://{service}:8080"));
    let url = format!("{}{path}", base.trim_end_matches('/'));
    let context = seat_assignment_client_context(&url);
    let request = client.get(&url).header("X-Correlation-Id", correlation_id);
    let response = rust_kit::outbound::inject_trace_context_from(request, &context)
        .send()
        .await
        .map_err(|error| ApiErrorKind::Unavailable(format!("{service} issue facts: {error}")))?;
    if response.status() == StatusCode::NOT_FOUND {
        return Err(ApiErrorKind::PreconditionFailed(format!(
            "{service} issue facts are not available"
        )));
    }
    if !response.status().is_success() {
        return Err(ApiErrorKind::Unavailable(format!(
            "{service} issue facts returned {}",
            response.status()
        )));
    }
    response
        .json()
        .await
        .map_err(|error| ApiErrorKind::Unavailable(format!("{service} issue facts: {error}")))
}

pub(crate) async fn resolve(
    command: &IssueEntitlementRequest,
    correlation_id: &str,
) -> ApiResult<(IssuePreconditions, IssueEntitlementRequest)> {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(5))
        .build()
        .map_err(|error| ApiErrorKind::Unavailable(error.to_string()))?;
    let booking: BookingDetails = read(
        &client,
        "booking-orchestration",
        "BOOKING_ORCHESTRATION_BASE_URL",
        &format!(
            "/api/v1/internal/segment-bookings/{}",
            command.segment_booking_id
        ),
        correlation_id,
    )
    .await?;
    if booking.segment_booking_id != command.segment_booking_id
        || booking.journey_order_id != command.journey_order_id
        || booking.traveler_ref != command.traveler_ref
        || booking.segment_ref != command.segment_ref
        || !matches!(booking.status.as_str(), "CONFIRMED" | "TICKETED")
    {
        return Err(ApiErrorKind::PreconditionFailed(
            "segment reservation is not confirmed for this order and traveler".into(),
        ));
    }
    let hold_id = booking
        .capacity_hold_id
        .filter(|id| !id.is_empty())
        .ok_or_else(|| {
            ApiErrorKind::PreconditionFailed("segment reservation has no capacity hold".into())
        })?;
    if command
        .capacity_hold_id
        .as_ref()
        .is_some_and(|id| id != &hold_id)
    {
        return Err(ApiErrorKind::PreconditionFailed(
            "requested capacity hold differs from the reservation".into(),
        ));
    }
    let capacity: CapacityDetails = read(
        &client,
        "capacity-availability",
        "CAPACITY_AVAILABILITY_BASE_URL",
        &format!("/api/v1/capacity-holds/{hold_id}"),
        correlation_id,
    )
    .await?;
    if capacity.hold_id != hold_id
        || capacity.segment_ref != command.segment_ref
        || capacity.segment_booking_ref != command.segment_booking_id
        || capacity.traveler_ref.as_deref() != Some(command.traveler_ref.as_str())
        || capacity.status != "CONFIRMED"
    {
        return Err(ApiErrorKind::PreconditionFailed(
            "capacity is not confirmed for the traveler".into(),
        ));
    }
    let order: OrderDetails = read(
        &client,
        "journey-order",
        "JOURNEY_ORDER_BASE_URL",
        &format!("/api/v1/journey-orders/{}", command.journey_order_id),
        correlation_id,
    )
    .await?;
    let change_case = booking.booking_purpose.strip_prefix("change:");
    if change_case.is_some() && command.issue_purpose != IssuePurposeDto::Replacement {
        return Err(ApiErrorKind::PreconditionFailed("change booking requires replacement issuance".into()));
    }
    if order.order_id != command.journey_order_id
        || !order.risk_clear
        || matches!(
            order.status.as_str(),
            "CANCELLED" | "FAILED" | "CLOSED" | "REFUNDED"
        )
        || !order.traveler_refs.contains(&command.traveler_ref)
        || (change_case.is_none() && !order.segment_refs.contains(&command.segment_ref))
    {
        return Err(ApiErrorKind::PreconditionFailed(
            "order, traveler or risk state does not permit issuance".into(),
        ));
    }
    let mut offer_id = order.offer_id.clone();
    let payment_fact_id = if let Some(case_id) = change_case {
        let authorization: ChangeAuthorization = read(&client,"post-sales","POST_SALES_BASE_URL",
            &format!("/api/v1/post-sales-cases/{case_id}/issue-authorization/{}",command.segment_booking_id),correlation_id).await?;
        if !authorization.authorized || authorization.case_id != case_id
            || authorization.journey_order_id != command.journey_order_id || authorization.segment_booking_id != command.segment_booking_id
            || authorization.traveler_ref != command.traveler_ref || authorization.segment_ref != command.segment_ref {
            return Err(ApiErrorKind::PreconditionFailed("change authorization does not match replacement issuance".into()));
        }
        let change: Value = read(&client, "post-sales", "POST_SALES_BASE_URL",
            &format!("/api/v1/post-sales-cases/{case_id}"), correlation_id).await?;
        offer_id = required_field(&change, "targetOfferId")?.to_owned();
        authorization.payment_intent_id
    } else {
    let payment: PaymentDetails = read(
        &client,
        "payment",
        "PAYMENT_BASE_URL",
        &format!(
            "/api/v1/payment-intents/by-business/{}",
            command.journey_order_id
        ),
        correlation_id,
    )
    .await?;
    if payment.business_ref != command.journey_order_id
        || payment.payer_ref != order.account_id
        || payment.status != "CAPTURED"
        || payment.captured_amount.currency != order.monetary_summary.payable_total.currency
        || payment.captured_amount.minor_units != order.monetary_summary.payable_total.minor_units
        || payment.refunded_amount.minor_units != 0
    {
        return Err(ApiErrorKind::PreconditionFailed(
            "captured payment does not cover the order".into(),
        ));
    }
    payment.payment_intent_id
    };
    let offer: Value = read(&client, "offer-management", "OFFER_MANAGEMENT_BASE_URL",
        &format!("/api/v1/offers/{offer_id}"), correlation_id).await?;
    if required_field(&offer, "offerId")? != offer_id || required_field(&offer, "accountId")? != order.account_id {
        return Err(ApiErrorKind::PreconditionFailed("issuance offer references another order account".into()));
    }
    let itinerary: Value = read(&client, "trip-planning", "TRIP_PLANNING_BASE_URL",
        &format!("/api/v1/itineraries/{}", required_field(&offer, "itineraryRef")?), correlation_id).await?;
    let leg = itinerary.get("legs").and_then(Value::as_array)
        .and_then(|legs| legs.iter().find(|leg| leg.get("serviceSegmentRef").and_then(Value::as_str) == Some(command.segment_ref.as_str())))
        .ok_or_else(|| ApiErrorKind::PreconditionFailed("issuance segment is absent from purchased itinerary".into()))?;
    let fare: Value = read(&client, "fare-pricing", "FARE_PRICING_BASE_URL",
        &format!("/api/v1/fare-quotes/{}", required_field(&offer, "fareQuoteRef")?), correlation_id).await?;
    let class = required_field(&fare["breakdown"]["seatClassMultiplier"], "seatClass")?;
    let departure = required_field(leg, "departureTime")?;
    let date = chrono::DateTime::parse_from_rfc3339(departure)
        .map_err(|error| ApiErrorKind::PreconditionFailed(error.to_string()))?
        .with_timezone(&chrono::Utc).date_naive().to_string();
    let mut resolved = command.clone();
    verify_and_set(&mut resolved.scheduled_service_ref, required_field(leg, "servicePlanRef")?.to_owned(), "scheduledServiceRef")?;
    verify_and_set(&mut resolved.service_date, date, "serviceDate")?;
    verify_and_set(&mut resolved.capacity_hold_id, hold_id.clone(), "capacityHoldId")?;
    verify_and_set(&mut resolved.capacity_unit_ref, capacity.capacity_unit_ref, "capacityUnitRef")?;
    verify_and_set(&mut resolved.interval, capacity.interval, "interval")?;
    verify_and_set(&mut resolved.class_ref, class.to_owned(), "classRef")?;
    verify_and_set(&mut resolved.expires_at, departure.to_owned(), "expiresAt")?;
    let now = UnixMillis::new(current_unix_millis());
    Ok((IssuePreconditions::accepted(
        AcceptedFact::new(
            BookingFactRef::new(booking.segment_booking_id).map_err(ApiErrorKind::from)?,
            BookingAcceptance::SegmentReservationConfirmed,
            now,
        ),
        AcceptedFact::new(
            CapacityFactRef::new(hold_id).map_err(ApiErrorKind::from)?,
            CapacityAcceptance::CapacityCommitted,
            now,
        ),
        AcceptedFact::new(
            PaymentFactRef::new(payment_fact_id).map_err(ApiErrorKind::from)?,
            PaymentAcceptance::PaymentCaptured,
            now,
        ),
        AcceptedFact::new(
            TravelerFactRef::new(command.traveler_ref.clone()).map_err(ApiErrorKind::from)?,
            TravelerAcceptance::TravelerSnapshotAccepted,
            now,
        ),
        AcceptedFact::new(
            RiskFactRef::new(order.order_id).map_err(ApiErrorKind::from)?,
            RiskAcceptance::Allowed,
            now,
        ),
    ), resolved))
}

fn required_field<'a>(value: &'a Value, field: &str) -> ApiResult<&'a str> {
    value.get(field).and_then(Value::as_str).filter(|value| !value.is_empty())
        .ok_or_else(|| ApiErrorKind::PreconditionFailed(format!("issuance facts missing {field}")))
}

fn verify_and_set<T: PartialEq>(field: &mut Option<T>, authoritative: T, name: &str) -> ApiResult<()> {
    if field.as_ref().is_some_and(|supplied| supplied != &authoritative) {
        return Err(ApiErrorKind::PreconditionFailed(format!("requested {name} differs from issuance facts")));
    }
    *field = Some(authoritative);
    Ok(())
}
