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
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CapacityDetails {
    hold_id: String,
    traveler_ref: Option<String>,
    status: String,
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
) -> ApiResult<IssuePreconditions> {
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
    if order.order_id != command.journey_order_id
        || !order.risk_clear
        || matches!(
            order.status.as_str(),
            "CANCELLED" | "FAILED" | "CLOSED" | "REFUNDED"
        )
        || !order.traveler_refs.contains(&command.traveler_ref)
        || !order.segment_refs.contains(&command.segment_ref)
    {
        return Err(ApiErrorKind::PreconditionFailed(
            "order, traveler or risk state does not permit issuance".into(),
        ));
    }
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
    let now = UnixMillis::new(current_unix_millis());
    Ok(IssuePreconditions::accepted(
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
            PaymentFactRef::new(payment.payment_intent_id).map_err(ApiErrorKind::from)?,
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
    ))
}
