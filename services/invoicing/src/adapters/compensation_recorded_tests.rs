use super::PostgresInvoicingService;
use rust_kit::messaging::EventEnvelope;
use serde_json::Value;
use std::collections::BTreeMap;

#[tokio::test]
#[ignore = "requires TEST_DATABASE_URL for an isolated PostgreSQL schema"]
async fn recorded_compensation_preserves_invoices_and_projections_across_replay() {
    let source: Value = serde_json::from_str(include_str!("../../tests/fixtures/compensation-recorded.json")).unwrap();
    verify_preserved(source, true).await;
}

#[tokio::test]
#[ignore = "requires TEST_DATABASE_URL for an isolated PostgreSQL schema"]
async fn recorded_wallet_compensation_preserves_original_invoice_basis() {
    let source: Value = serde_json::from_str(include_str!("../../tests/fixtures/wallet-compensation-recorded.json")).unwrap();
    assert!(source["commands"].as_array().unwrap().is_empty());
    assert_eq!(source["approved"]["payload"]["approvedActions"]["compensation"]["method"], "WALLET_CREDIT");
    assert_eq!(source["case"]["decision"]["amountSnapshot"]["refundAmount"],
        source["approved"]["payload"]["approvedActions"]["compensation"]["amount"]);
    verify_preserved(source, false).await;
}

async fn verify_preserved(source: Value, historical: bool) {
    let approved: EventEnvelope = serde_json::from_value(source["approved"].clone()).unwrap();
    let mut applied: EventEnvelope = serde_json::from_value(source["applied"].clone()).unwrap();
    let recorded_case = &source["case"];
    assert_eq!(recorded_case["caseType"], "COMPENSATION");
    assert_eq!(recorded_case["decision"]["kind"], "COMPENSATION");
    assert_eq!(recorded_case["status"], "APPLIED");
    assert_eq!(format!("psc-{}", recorded_case["caseId"].as_str().unwrap()), applied.payload["caseId"]);
    assert_eq!(recorded_case["journeyOrderId"], applied.payload["orderId"]);
    if historical {
        assert!(applied.payload.get("caseType").is_none());
        // This historical envelope predates caseType; the recorded source case supplies it.
        applied.payload["caseType"] = recorded_case["caseType"].clone();
    }
    assert_eq!(approved.payload["approvedActions"]["decisionKind"], "COMPENSATION");
    assert_eq!(applied.payload["caseType"], "COMPENSATION");
    assert_eq!(approved.payload["caseId"], applied.payload["caseId"]);
    assert_eq!(approved.payload["orderId"], applied.payload["orderId"]);
    if historical {
        assert!(source["commands"].as_array().unwrap().iter().any(|row|
            row["kind"] == "invoices" && row["data"]["invoiceType"] == "BLUE" && row["data"]["status"] == "ISSUED"));
    }
    assert!(source["projections"].as_array().unwrap().iter().any(|row| row["kind"] == "amounts"));
    let database = std::env::var("TEST_DATABASE_URL").unwrap();
    let schema = format!("invoice_compensation_{}", uuid::Uuid::now_v7().simple());
    let admin = sqlx::postgres::PgPoolOptions::new().max_connections(1).connect(&database).await.unwrap();
    sqlx::query(&format!("CREATE SCHEMA {schema}")).execute(&admin).await.unwrap();
    let search = schema.clone();
    let pool = sqlx::postgres::PgPoolOptions::new().max_connections(2)
        .acquire_timeout(std::time::Duration::from_secs(3))
        .after_connect(move |connection, _| {
            let sql = format!("SET search_path TO {search}");
            Box::pin(async move {
                sqlx::query(&sql).execute(&mut *connection).await?;
                sqlx::query("SET statement_timeout='5s'").execute(&mut *connection).await?;
                sqlx::query("SET lock_timeout='2s'").execute(&mut *connection).await?;
                Ok(())
            })
        }).connect(&database).await.unwrap();
    let run_pool = pool.clone();
    let task = tokio::spawn(async move {
        let storage = rust_kit::storage::Storage::new(run_pool.clone());
        storage.migrate_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/migrations")).await.unwrap();
        for row in source["commands"].as_array().unwrap() {
            assert_eq!(row["order_id"], approved.payload["orderId"]);
            sqlx::query("INSERT INTO invoicing_command_rows(kind,id,account_id,order_id,data) VALUES($1,$2,$3,$4,$5)")
                .bind(row["kind"].as_str().unwrap()).bind(row["id"].as_str().unwrap())
                .bind(row["account_id"].as_str().unwrap()).bind(row["order_id"].as_str().unwrap())
                .bind(&row["data"]).execute(&run_pool).await.unwrap();
        }
        for row in source["projections"].as_array().unwrap() {
            assert_eq!(row["id"], approved.payload["orderId"]);
            sqlx::query("INSERT INTO invoicing_projection_rows(kind,id,data) VALUES($1,$2,$3)")
                .bind(row["kind"].as_str().unwrap()).bind(row["id"].as_str().unwrap())
                .bind(&row["data"]).execute(&run_pool).await.unwrap();
        }
        let before = state(&run_pool).await;
        for _ in 0..2 {
            let service = PostgresInvoicingService { storage: rust_kit::storage::Storage::new(run_pool.clone()) };
            for event in [&applied, &approved, &approved, &applied] {
                service.handle_subscribed_event(event.clone()).await.unwrap();
                assert_eq!(before, state(&run_pool).await);
            }
        }
        let service = PostgresInvoicingService { storage: rust_kit::storage::Storage::new(run_pool.clone()) };
        run_pool.close().await;
        for event in [approved, applied] {
            service.handle_subscribed_event(event).await.unwrap();
        }
        println!("Compensation invoicing: invoices, requests, red flushes, amount basis and outbox unchanged; replay succeeds with pool closed");
    });
    let result = task.await;
    pool.close().await;
    sqlx::query(&format!("DROP SCHEMA {schema} CASCADE")).execute(&admin).await.unwrap();
    admin.close().await;
    result.unwrap();
}

async fn state(pool: &sqlx::PgPool) -> BTreeMap<&'static str, Vec<Value>> {
    let mut result = BTreeMap::new();
    for table in ["invoicing_command_rows", "invoicing_projection_rows", "invoicing_state",
        "red_flush_correction_audit", "processed_events", "idempotency_records", "outbox"] {
        let rows = sqlx::query_scalar(&format!("SELECT to_jsonb(r) FROM {table} r ORDER BY to_jsonb(r)::text"))
            .fetch_all(pool).await.unwrap();
        result.insert(table, rows);
    }
    result
}
