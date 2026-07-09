use super::*;

fn key() -> String {
    uuid::Uuid::now_v7().to_string()
}
fn corr() -> String {
    rust_kit::messaging::correlation_id()
}
fn title_cmd() -> CreateInvoiceTitleCommand {
    CreateInvoiceTitleCommand {
        account_id: "acc-test".into(),
        title_type: TitleType::Personal,
        title_name: "个人".into(),
        tax_identity: None,
        registered_address: None,
        registered_phone: None,
        bank_name: None,
        bank_account: None,
        set_as_default: true,
    }
}
fn amount() -> AmountBasis {
    AmountBasis {
        basis_type: "REVENUE_RECOGNITION".into(),
        revenue_recognition_ids: vec!["rr-1".into()],
        finance_invoice_id: None,
        tax_lines: vec![TaxLine {
            tax_code: "VAT".into(),
            tax_rate_basis_points: 0,
            taxable_amount: Money {
                currency: "CNY".into(),
                minor_units: 1000,
            },
            tax_amount: Money {
                currency: "CNY".into(),
                minor_units: 0,
            },
        }],
        total_amount: Money {
            currency: "CNY".into(),
            minor_units: 1000,
        },
        amount_basis_hash: "sha256:test".into(),
    }
}

#[tokio::test]
async fn title_lifecycle_and_deactivated_title_blocks_invoice() {
    let svc = InMemoryInvoicingService::default();
    let title = svc.create_title(title_cmd(), key(), corr()).await.unwrap();
    assert!(title.is_default);
    let resp = svc
        .deactivate_title(
            title.title_id.clone(),
            DeactivateTitleCommand {
                expected_version: 1,
                reason: "USER_REQUEST".into(),
            },
            key(),
            corr(),
        )
        .await
        .unwrap();
    assert_eq!(resp.status, TitleStatus::Deactivated);
    let err = svc
        .request_invoice(
            RequestEInvoiceCommand {
                account_id: "acc-test".into(),
                order_id: "ord-none".into(),
                title_id: title.title_id,
                title_version: 1,
                invoice_scope: InvoiceScope {
                    scope_type: "ORDER".into(),
                    order_item_refs: vec![],
                    segment_refs: vec![],
                    traveler_refs: vec![],
                },
                amount_basis: amount(),
                recipient_email: None,
                gateway_profile: None,
                sim_seed_ref: None,
            },
            key(),
            corr(),
        )
        .await
        .unwrap_err();
    assert!(matches!(
        err,
        InvoicingError::PreconditionFailed(_) | InvoicingError::ValidationFailed(_)
    ));
}

#[tokio::test]
async fn deterministic_sim_accepts_and_rejects_by_seed() {
    let publisher = std::sync::Arc::new(InMemoryEventPublisher::default());
    let svc = InMemoryInvoicingService::new(publisher.clone());
    let title = svc.create_title(title_cmd(), key(), corr()).await.unwrap();
    svc.apply_subscribed_event(rust_kit::messaging::EventEnvelope::new("JourneyOrderConfirmed", rust_kit::messaging::now_rfc3339_utc(), corr(), Some(format!("evt-{}", uuid::Uuid::now_v7())), "journey-order", serde_json::json!({"orderId":"ord-ok","accountId":"acc-test","travelerRefs":["tvl-1"],"segmentRefs":["seg-1"]}))).await.unwrap();
    let req = svc
        .request_invoice(
            RequestEInvoiceCommand {
                account_id: "acc-test".into(),
                order_id: "ord-ok".into(),
                title_id: title.title_id.clone(),
                title_version: 1,
                invoice_scope: InvoiceScope {
                    scope_type: "ORDER".into(),
                    order_item_refs: vec![],
                    segment_refs: vec![],
                    traveler_refs: vec![],
                },
                amount_basis: amount(),
                recipient_email: Some("a@example.com".into()),
                gateway_profile: None,
                sim_seed_ref: Some("accept-1".into()),
            },
            key(),
            corr(),
        )
        .await
        .unwrap();
    assert_eq!(req.status, InvoiceRequestStatus::Issued);
    svc.apply_subscribed_event(rust_kit::messaging::EventEnvelope::new(
        "JourneyOrderConfirmed",
        rust_kit::messaging::now_rfc3339_utc(),
        corr(),
        Some(format!("evt-{}", uuid::Uuid::now_v7())),
        "journey-order",
        serde_json::json!({"orderId":"ord-rej","accountId":"acc-test"}),
    ))
    .await
    .unwrap();
    let mut a = amount();
    a.amount_basis_hash = "sha256:reject".into();
    let req2 = svc
        .request_invoice(
            RequestEInvoiceCommand {
                account_id: "acc-test".into(),
                order_id: "ord-rej".into(),
                title_id: title.title_id,
                title_version: 1,
                invoice_scope: InvoiceScope {
                    scope_type: "ORDER".into(),
                    order_item_refs: vec![],
                    segment_refs: vec![],
                    traveler_refs: vec![],
                },
                amount_basis: a,
                recipient_email: None,
                gateway_profile: None,
                sim_seed_ref: Some("reject-seed".into()),
            },
            key(),
            corr(),
        )
        .await
        .unwrap();
    assert_eq!(req2.status, InvoiceRequestStatus::Rejected);
    let types: Vec<_> = publisher
        .events()
        .into_iter()
        .map(|e| e.event_type)
        .collect();
    assert!(types.contains(&"EInvoiceIssued".to_string()));
    assert!(types.contains(&"EInvoiceRejected".to_string()));
}

#[tokio::test]
async fn post_sales_refund_observes_and_completes_red_flush() {
    let publisher = std::sync::Arc::new(InMemoryEventPublisher::default());
    let svc = InMemoryInvoicingService::new(publisher.clone());
    let title = svc.create_title(title_cmd(), key(), corr()).await.unwrap();
    svc.apply_subscribed_event(rust_kit::messaging::EventEnvelope::new(
        "JourneyOrderConfirmed",
        rust_kit::messaging::now_rfc3339_utc(),
        corr(),
        Some(format!("evt-{}", uuid::Uuid::now_v7())),
        "journey-order",
        serde_json::json!({"orderId":"ord-ref","accountId":"acc-test"}),
    ))
    .await
    .unwrap();
    let req = svc
        .request_invoice(
            RequestEInvoiceCommand {
                account_id: "acc-test".into(),
                order_id: "ord-ref".into(),
                title_id: title.title_id,
                title_version: 1,
                invoice_scope: InvoiceScope {
                    scope_type: "ORDER".into(),
                    order_item_refs: vec![],
                    segment_refs: vec![],
                    traveler_refs: vec![],
                },
                amount_basis: amount(),
                recipient_email: None,
                gateway_profile: None,
                sim_seed_ref: Some("accept".into()),
            },
            key(),
            corr(),
        )
        .await
        .unwrap();
    assert_eq!(req.status, InvoiceRequestStatus::Issued);
    svc.apply_subscribed_event(rust_kit::messaging::EventEnvelope::new(
        "PostSalesApplied",
        rust_kit::messaging::now_rfc3339_utc(),
        corr(),
        Some(format!("evt-{}", uuid::Uuid::now_v7())),
        "post-sales",
        serde_json::json!({"caseId":"psc-1","orderId":"ord-ref","resultSummary":{"refund":true}}),
    ))
    .await
    .unwrap();
    let page = svc
        .list_red_flushes(Some("ord-ref".into()), None, None, 20, 0)
        .await
        .unwrap();
    assert_eq!(page.items[0].status, RedFlushStatus::Completed);
    assert!(
        publisher
            .events()
            .iter()
            .any(|e| e.event_type == "RefundWithoutRedFlushObserved")
    );
}
