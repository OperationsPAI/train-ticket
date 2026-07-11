# Finance Settlement Enrichment — Reconciliation & Supplier Settlement

## Context

**Service**: finance-settlement (Java, `services/finance-settlement/`)
**Current state**: 876-line domain, basic settlement event consumption. No reconciliation, no supplier split, no daily cut-off.

## Requirements

### R1: Daily Reconciliation (日切对账)

Daily cut-off at 00:00: snapshot all transactions for T-1, compare platform records vs payment channel records. Reconciliation statuses: MATCHED (both sides agree), PLATFORM_ONLY (we recorded, channel didn't), CHANNEL_ONLY (channel recorded, we didn't), AMOUNT_MISMATCH (both exist, amounts differ). Generate ReconciliationReport with match rate, exceptions list, total variance.

Domain: ReconciliationBatch, ReconciliationEntry, ReconciliationReport. Events: ReconciliationCompleted.

### R2: Supplier Revenue Split (供应商分账)

Split rules per supplier agreement: platform_commission_pct (e.g., 5%), supplier_net = revenue - commission - taxes_withheld, settlement_frequency (DAILY, WEEKLY, MONTHLY). Track: gross_revenue, platform_commission, taxes_withheld, supplier_payable, adjustments (refunds, penalties).

Domain: SupplierSettlement, SettlementPeriod, RevenueAllocation. Events: SupplierSettlementCalculated.

### R3: Fee Accrual & Tax Handling

Service fees: platform_service_fee (retained), supplier_service_fee (passed through). Tax handling: VAT on service fees (6%), stamp duty on tickets (0.05%), withholding on supplier payments. Refund impact: tax reversal on refund, partial fee retention on voluntary cancellation.

Domain: FeeAccrual, TaxCalculation. Events: FeeAccrued, TaxCalculated.

### R4: Settlement Reports API

GET /api/v1/settlements/daily/{date} -- daily reconciliation summary. GET /api/v1/settlements/suppliers/{supplierId}/period -- supplier settlement. GET /api/v1/settlements/reconciliation/{batchId} -- detailed reconciliation entries.

## Events Consumed

events:payment (PaymentCaptured, PaymentRefunded), events:post-sales (RefundCompleted), events:journey-order (OrderCreated).

## Events Produced

ReconciliationCompleted, SupplierSettlementCalculated, FeeAccrued.

## Test Criteria

1. 100 payments in T-1 day -> reconciliation batch created with all entries
2. Supplier with 5% commission -> correct net calculation
3. Refund reverses tax and adjusts supplier payable
4. Amount mismatch -> flagged in reconciliation report

## Files to Modify

- src/main/java/.../financesettlement/domain/
- src/main/java/.../financesettlement/application/
- migrations/
