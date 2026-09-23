use axum::{
    body::{Body, to_bytes},
    http::{Request, StatusCode},
};
use invoicing::{AmountBasis, InvoicingApi, PostgresInvoicingService};
use rust_kit::messaging::EventEnvelope;
use serde_json::Value;
use std::{sync::Arc, time::Duration};
use tower::ServiceExt;

async fn response(service: Arc<PostgresInvoicingService>, order: &str) -> (StatusCode, Value) {
    let response = invoicing::router_with_postgres_state(service)
        .oneshot(
            Request::builder()
                .uri(format!("/api/v1/invoice-amount-bases/{order}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let status = response.status();
    let body = to_bytes(response.into_body(), 65536).await.unwrap();
    (status, serde_json::from_slice(&body).unwrap())
}

#[test]
#[ignore = "requires TEST_DATABASE_URL for isolated PostgreSQL schemas"]
fn recorded_amount_basis_requires_order_and_matching_total() {
    let source: Value =
        serde_json::from_str(include_str!("fixtures/amount-basis-recorded.json")).unwrap();
    let database = std::env::var("TEST_DATABASE_URL").unwrap();
    let schema = format!("invoice_basis_{}", uuid::Uuid::now_v7().simple());
    let mut url = reqwest::Url::parse(&database).unwrap();
    url.query_pairs_mut().append_pair(
        "options",
        &format!("-csearch_path={schema} -cstatement_timeout=5000 -clock_timeout=2000"),
    );
    // This integration binary has one test and configures its environment before starting Tokio.
    unsafe {
        std::env::set_var("DATABASE_URL", url.as_str());
        std::env::set_var(
            "MIGRATIONS_DIR",
            concat!(env!("CARGO_MANIFEST_DIR"), "/migrations"),
        );
        std::env::set_var("PG_MAX_POOL_SIZE", "1");
    }
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    runtime.block_on(async move {
        let admin = sqlx::postgres::PgPoolOptions::new().max_connections(1)
            .acquire_timeout(Duration::from_secs(3)).connect(&database).await.unwrap();
        sqlx::query(&format!("CREATE SCHEMA {schema}")).execute(&admin).await.unwrap();
        let task = tokio::spawn(async move {
            let service = Arc::new(PostgresInvoicingService::from_env().await.unwrap());
            let order = source["orderId"].as_str().unwrap();
            let created: EventEnvelope = serde_json::from_value(source["orderEvents"][0]["envelope"].clone()).unwrap();
            let confirmed: EventEnvelope = serde_json::from_value(source["orderEvents"][1]["envelope"].clone()).unwrap();
            assert_eq!(created.event_type, "JourneyOrderCreated");
            assert_eq!(confirmed.event_type, "JourneyOrderConfirmed");
            assert_eq!(created.payload["monetarySummary"]["total"], confirmed.payload["monetarySummary"]["total"]);
            for row in source["financeEvents"].as_array().unwrap() {
                let event: EventEnvelope = serde_json::from_value(row["envelope"].clone()).unwrap();
                assert_eq!(event.payload["orderId"], order);
                service.handle_subscribed_event(event).await.unwrap();
            }
            let (status, body) = response(service.clone(), order).await;
            assert_eq!(status, StatusCode::NOT_FOUND);
            assert_eq!(body["code"], "NOT_FOUND");
            println!("Recorded order {order}: financial projection without order returns 404 NOT_FOUND");

            service.handle_subscribed_event(created.clone()).await.unwrap();
            let (created_status, _) = response(service.clone(), order).await;
            assert_eq!(created_status, StatusCode::OK);
            println!("Recorded order {order}: order projection present with equal total returns 200");

            service.handle_subscribed_event(confirmed.clone()).await.unwrap();
            let (status, body) = response(service.clone(), order).await;
            assert_eq!(status, StatusCode::OK);
            let basis: AmountBasis = serde_json::from_value(body).unwrap();
            assert_eq!(serde_json::to_value(&basis.total_amount).unwrap(), confirmed.payload["monetarySummary"]["total"]);
            let persisted: Value = sqlx::query_scalar("SELECT data FROM invoicing_projection_rows WHERE kind='orders' AND id=$1")
                .bind(order).fetch_one(service.pool()).await.unwrap();
            assert_eq!(persisted["expected_total"], confirmed.payload["monetarySummary"]["total"]);
            println!("Recorded order {order}: confirmed with matching total returns 200, total={:?}", basis.total_amount);

            // Removing only the receipt marker exercises delivery order without changing the recorded envelope.
            sqlx::query("DELETE FROM processed_events WHERE event_id=$1").bind(&created.event_id)
                .execute(service.pool()).await.unwrap();
            service.handle_subscribed_event(created).await.unwrap();
            let after: Value = sqlx::query_scalar("SELECT data FROM invoicing_projection_rows WHERE kind='orders' AND id=$1")
                .bind(order).fetch_one(service.pool()).await.unwrap();
            assert_eq!(after, persisted);
            assert_eq!(service.get_amount_basis(order.into()).await.unwrap(), basis);
            println!("Recorded earlier creation replay preserves confirmed projection and timestamp");

            let net_order = source["net"]["created"]["payload"]["orderId"].as_str().unwrap();
            for key in ["created", "adjusted"] {
                service.handle_subscribed_event(serde_json::from_value(source["net"][key].clone()).unwrap()).await.unwrap();
            }
            service.handle_subscribed_event(serde_json::from_value(source["net"]["finance"][0].clone()).unwrap()).await.unwrap();
            assert_eq!(response(service.clone(), net_order).await.0, StatusCode::NOT_FOUND);
            service.handle_subscribed_event(serde_json::from_value(source["net"]["finance"][1].clone()).unwrap()).await.unwrap();
            assert_eq!(response(service.clone(), net_order).await.0, StatusCode::OK);
            println!("Recorded adjusted order {net_order}: incomplete net amount returns 404; matching net amount returns 200");

            let title = &source["legacy"]["title"];
            sqlx::query("INSERT INTO invoicing_command_rows(kind,id,account_id,data) VALUES('titles',$1,$2,$3)")
                .bind(title["titleId"].as_str().unwrap()).bind(title["accountId"].as_str().unwrap())
                .bind(title).execute(service.pool()).await.unwrap();
            let cmd: invoicing::RequestEInvoiceCommand = serde_json::from_value(source["legacy"]["request"].clone()).unwrap();
            let key = source["legacy"]["request"]["clientRequestId"].as_str().unwrap().to_string();
            let legacy_order = &cmd.order_id;
            for row in source["legacy"]["projections"].as_array().unwrap().iter().filter(|row| row["kind"] == "amounts") {
                sqlx::query("INSERT INTO invoicing_projection_rows(kind,id,data) VALUES($1,$2,$3)")
                    .bind(row["kind"].as_str().unwrap()).bind(row["id"].as_str().unwrap())
                    .bind(&row["data"]).execute(service.pool()).await.unwrap();
            }
            assert_eq!(response(service.clone(), legacy_order).await.0, StatusCode::NOT_FOUND);
            assert!(matches!(service.request_invoice(cmd.clone(), key.clone(), rust_kit::messaging::correlation_id()).await,
                Err(invoicing::InvoicingError::PreconditionFailed(_))));
            for row in source["legacy"]["projections"].as_array().unwrap().iter().filter(|row| row["kind"] == "orders") {
                sqlx::query("INSERT INTO invoicing_projection_rows(kind,id,data) VALUES($1,$2,$3)")
                    .bind(row["kind"].as_str().unwrap()).bind(row["id"].as_str().unwrap())
                    .bind(&row["data"]).execute(service.pool()).await.unwrap();
            }
            assert_eq!(response(service.clone(), legacy_order).await.0, StatusCode::OK);
            // Omit a projection field to exercise an incomplete stored record without inventing a value.
            let mut omission = service.pool().begin().await.unwrap();
            sqlx::query("UPDATE invoicing_projection_rows SET data=data-'expected_total' WHERE kind='orders' AND id=$1")
                .bind(legacy_order).execute(&mut *omission).await.unwrap();
            omission.commit().await.unwrap();
            assert_eq!(response(service.clone(), legacy_order).await.0, StatusCode::NOT_FOUND);
            assert!(matches!(service.request_invoice(cmd.clone(), key.clone(), rust_kit::messaging::correlation_id()).await,
                Err(invoicing::InvoicingError::PreconditionFailed(_))));
            assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM invoicing_command_rows WHERE kind IN ('requests','invoices','idempotency')")
                .fetch_one(service.pool()).await.unwrap(), 0);
            assert_eq!(sqlx::query_scalar::<_, i64>("SELECT count(*) FROM outbox").fetch_one(service.pool()).await.unwrap(), 0);
            let recorded_order = source["legacy"]["projections"].as_array().unwrap().iter().find(|row| row["kind"] == "orders").unwrap();
            sqlx::query("UPDATE invoicing_projection_rows SET data=$1 WHERE kind='orders' AND id=$2")
                .bind(&recorded_order["data"]).bind(legacy_order).execute(service.pool()).await.unwrap();
            let request = service.request_invoice(cmd.clone(), key.clone(), rust_kit::messaging::correlation_id()).await.unwrap();
            assert_eq!(request.status, invoicing::InvoiceRequestStatus::Issued);
            let outbox: i64 = sqlx::query_scalar("SELECT count(*) FROM outbox").fetch_one(service.pool()).await.unwrap();
            let replay = service.request_invoice(cmd, key, rust_kit::messaging::correlation_id()).await.unwrap();
            assert_eq!(replay.invoice_request_id, request.invoice_request_id);
            assert_eq!(outbox, sqlx::query_scalar::<_, i64>("SELECT count(*) FROM outbox").fetch_one(service.pool()).await.unwrap());
            println!("Recorded legacy projection: missing order or total gives GET 404 and request PRECONDITION_FAILED with no writes; complete record issues once and replays");
            let restarted = Arc::new(PostgresInvoicingService::from_env().await.unwrap());
            assert_eq!(restarted.get_amount_basis(order.into()).await.unwrap(), basis);
            restarted.pool().close().await;
            service.pool().close().await;
        });
        let result = task.await;
        sqlx::query(&format!("DROP SCHEMA {schema} CASCADE")).execute(&admin).await.unwrap();
        admin.close().await;
        result.unwrap();
    });
}
