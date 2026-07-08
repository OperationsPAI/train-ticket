#[cfg(test)]
mod tests {
    use super::*;
    use async_trait::async_trait;
    use serde_json::Value;
    use std::sync::Mutex;
    fn tvl(suffix: &str) -> String {
        format!("tvl-0194f2e0-7b3e-7610-8284-5c26e8b0{suffix}")
    }
    fn seg(suffix: &str) -> String {
        format!("seg-0194f2e0-7b3e-7610-8284-5c26e8b0{suffix}")
    }
    fn key(suffix: &str) -> String {
        format!("0194f2e0-7b3e-7610-8284-5c26e8b0{suffix}")
    }
    fn ord(suffix: &str) -> String {
        format!("ord-0194f2e0-7b3e-7610-8284-5c26e8b0{suffix}")
    }
    fn command(traveler: String, segment: String) -> CreateWaitlistCommand {
        CreateWaitlistCommand {
            account_id: "acct-test".into(),
            traveler_ref: traveler,
            segment_ref: segment,
            travel_class: Some("SECOND".into()),
            deadline: "2099-01-01T00:00:00.000Z".into(),
            payment_guarantee_ref: "pay-auth-test".into(),
            intent_fingerprint: "intent-a".into(),
        }
    }
    fn request() -> WaitlistRequest {
        WaitlistRequest::create(
            command(tvl("aa11"), seg("aa12")),
            "2026-01-01T00:00:00.000Z".into(),
        )
        .unwrap()
        .0
    }
    fn envelope(event_type: &str, payload: Value) -> rust_kit::messaging::EventEnvelope {
        rust_kit::messaging::EventEnvelope::canonical(
            event_type,
            rust_kit::messaging::correlation_id(),
            Some(rust_kit::messaging::command_id()),
            "test",
            payload,
        )
    }

    #[test]
    fn profile_matches_domain() {
        assert_eq!(profile().service_id, "waitlist");
        assert_eq!(health(), "ok");
    }
    #[test]
    fn state_machine_full_transition_table() {
        let statuses = [
            WaitlistStatus::Draft,
            WaitlistStatus::Queued,
            WaitlistStatus::Matching,
            WaitlistStatus::Fulfilled,
            WaitlistStatus::Expired,
            WaitlistStatus::Cancelled,
            WaitlistStatus::Suspended,
            WaitlistStatus::Closed,
        ];
        for from in statuses {
            for to in statuses {
                let allowed = matches!(
                    (from, to),
                    (WaitlistStatus::Draft, WaitlistStatus::Queued)
                        | (WaitlistStatus::Draft, WaitlistStatus::Cancelled)
                        | (WaitlistStatus::Queued, WaitlistStatus::Matching)
                        | (WaitlistStatus::Queued, WaitlistStatus::Expired)
                        | (WaitlistStatus::Queued, WaitlistStatus::Cancelled)
                        | (WaitlistStatus::Queued, WaitlistStatus::Suspended)
                        | (WaitlistStatus::Matching, WaitlistStatus::Fulfilled)
                        | (WaitlistStatus::Matching, WaitlistStatus::Queued)
                        | (WaitlistStatus::Fulfilled, WaitlistStatus::Closed)
                        | (WaitlistStatus::Expired, WaitlistStatus::Closed)
                        | (WaitlistStatus::Cancelled, WaitlistStatus::Closed)
                        | (WaitlistStatus::Suspended, WaitlistStatus::Queued)
                        | (WaitlistStatus::Suspended, WaitlistStatus::Cancelled)
                );
                assert_eq!(from.can_transition_to(to), allowed);
            }
        }
        assert!(request().transition(WaitlistStatus::Fulfilled).is_err());
    }
    #[tokio::test]
    async fn runtime_endpoints() {
        use axum::body::Body;
        use axum::http::Request;
        use shared_kernel::{CORRELATION_ID_HEADER, REQUEST_ID_HEADER};
        use tower::ServiceExt;
        let router = router_with_state(Arc::new(InMemoryWaitlistService::default()));
        for path in [
            "/health",
            "/live",
            "/livez",
            "/ready",
            "/readyz",
            "/metadata",
        ] {
            let response = router
                .clone()
                .oneshot(Request::builder().uri(path).body(Body::empty()).unwrap())
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::OK);
            assert!(!response.headers()[REQUEST_ID_HEADER].is_empty());
            assert!(!response.headers()[CORRELATION_ID_HEADER].is_empty());
        }
    }
    #[tokio::test]
    async fn mutual_exclusion() {
        let service = InMemoryWaitlistService::default();
        service
            .create(
                command(tvl("bb11"), seg("bb12")),
                key("bb01"),
                "corr".into(),
            )
            .await
            .unwrap();
        assert!(matches!(
            service
                .create(
                    command(tvl("bb11"), seg("bb13")),
                    key("bb02"),
                    "corr".into()
                )
                .await
                .unwrap_err(),
            WaitlistError::Conflict(_)
        ));
    }
    #[test]
    fn queue_fifo_stable() {
        let mut first = request();
        first.waitlist_request_id = "wlr-0194f2e0-7b3e-7610-8284-5c26e8b0c001".into();
        first.queued_at = Some("2026-01-01T00:00:00.000Z".into());
        let mut second = request();
        second.waitlist_request_id = "wlr-0194f2e0-7b3e-7610-8284-5c26e8b0c002".into();
        second.queued_at = first.queued_at.clone();
        let queue = WaitlistQueue::rebuild([second, first]);
        assert_eq!(
            queue.head_for_segment(&seg("aa12")),
            Some("wlr-0194f2e0-7b3e-7610-8284-5c26e8b0c001")
        );
    }
    #[derive(Default)]
    struct FakeOrder {
        result: Mutex<Option<Result<String, FulfillmentClientError>>>,
    }
    #[async_trait]
    impl FulfillmentClient for FakeOrder {
        async fn fulfill(
            &self,
            _: &WaitlistRequest,
            _: &str,
            _: &str,
        ) -> Result<String, FulfillmentClientError> {
            self.result
                .lock()
                .unwrap()
                .take()
                .unwrap_or_else(|| Ok(ord("cc01")))
        }
    }
    #[tokio::test]
    async fn capacity_released_match_and_skip() {
        let publisher = Arc::new(InMemoryEventPublisher::default());
        let service =
            InMemoryWaitlistService::new(publisher.clone(), Arc::new(FakeOrder::default()));
        let created = service
            .create(
                command(tvl("cc11"), seg("cc12")),
                key("cc02"),
                "corr".into(),
            )
            .await
            .unwrap();
        service
            .apply_subscribed_event(envelope(
                "CapacityReleased",
                json!({"segmentRef": seg("cc12")}),
            ))
            .await
            .unwrap();
        let stored = service
            .state
            .lock()
            .unwrap()
            .requests
            .get(&created.waitlist_request_id)
            .unwrap()
            .clone();
        assert_eq!(stored.status, WaitlistStatus::Matching);
        assert!(stored.order_ref.is_some());
        let before = publisher.published().len();
        service
            .apply_subscribed_event(envelope(
                "CapacityReleased",
                json!({"segmentRef": seg("dd12")}),
            ))
            .await
            .unwrap();
        assert_eq!(publisher.published().len(), before);
    }
    #[tokio::test]
    async fn order_confirm_cancel() {
        let service = InMemoryWaitlistService::default();
        let created = service
            .create(
                command(tvl("dd11"), seg("dd12")),
                key("dd01"),
                "corr".into(),
            )
            .await
            .unwrap();
        {
            let mut state = service.state.lock().unwrap();
            let request = state
                .requests
                .get_mut(&created.waitlist_request_id)
                .unwrap();
            request.transition(WaitlistStatus::Matching).unwrap();
            request.record_order_ref(ord("dd02"));
        }
        service
            .apply_subscribed_event(envelope(
                "JourneyOrderCancelled",
                json!({"orderId": ord("dd02")}),
            ))
            .await
            .unwrap();
        assert_eq!(
            service
                .state
                .lock()
                .unwrap()
                .requests
                .get(&created.waitlist_request_id)
                .unwrap()
                .status,
            WaitlistStatus::Queued
        );
        {
            let mut state = service.state.lock().unwrap();
            let request = state
                .requests
                .get_mut(&created.waitlist_request_id)
                .unwrap();
            request.transition(WaitlistStatus::Matching).unwrap();
            request.record_order_ref(ord("dd03"));
        }
        service
            .apply_subscribed_event(envelope(
                "JourneyOrderConfirmed",
                json!({"orderId": ord("dd03")}),
            ))
            .await
            .unwrap();
        assert_eq!(
            service
                .state
                .lock()
                .unwrap()
                .requests
                .get(&created.waitlist_request_id)
                .unwrap()
                .status,
            WaitlistStatus::Closed
        );
    }
    #[tokio::test]
    async fn expiry_scan() {
        let service = InMemoryWaitlistService::default();
        let mut cmd = command(tvl("ee11"), seg("ee12"));
        cmd.deadline = "2026-01-01T00:00:01.000Z".into();
        let (request, _) = WaitlistRequest::create(cmd, "2026-01-01T00:00:00.000Z".into()).unwrap();
        let id = request.waitlist_request_id.clone();
        service
            .state
            .lock()
            .unwrap()
            .requests
            .insert(id.clone(), request);
        assert_eq!(
            service
                .expire_due("2026-01-01T00:00:02.000Z".into(), "corr".into())
                .await
                .unwrap(),
            1
        );
        assert_eq!(
            service
                .state
                .lock()
                .unwrap()
                .requests
                .get(&id)
                .unwrap()
                .status,
            WaitlistStatus::Closed
        );
    }
    #[tokio::test]
    async fn idempotency_replay() {
        let service = InMemoryWaitlistService::default();
        let response = service
            .create(
                command(tvl("ff11"), seg("ff12")),
                key("ff01"),
                "corr".into(),
            )
            .await
            .unwrap();
        assert_eq!(
            response,
            service
                .create(
                    command(tvl("ff11"), seg("ff12")),
                    key("ff01"),
                    "corr".into()
                )
                .await
                .unwrap()
        );
        assert!(matches!(
            service
                .create(
                    command(tvl("ff11"), seg("ff13")),
                    key("ff01"),
                    "corr".into()
                )
                .await
                .unwrap_err(),
            WaitlistError::IdempotencyKeyReused(_)
        ));
    }
}
