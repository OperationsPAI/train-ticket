# HTTP Endpoint Contract Drift Audit — REQ-103

Last updated: 2026-07-08

## Scope and extraction method

Audited the 23 deployment services listed in `deploy/k8s/services.yaml` against `docs/08-contracts/api/<service>.md`. Runtime/health endpoints (`/health`, `/healthz`, `/live`, `/livez`, `/ready`, `/readyz`, `/metadata`) are intentionally excluded because `api/README.md` defines them globally.

Implementation routes were extracted from service source registrations: Spring `@RequestMapping`/`@*Mapping`, FastAPI `@app`/`@router` decorators (including `APIRouter(prefix=...)`), Fastify `app.get/post/patch/...`, Gin groups and `router.GET/POST/...`, and Axum `Router::route` with chained method routers. Path parameters are normalized to `{camelCaseName}` and query templates in docs are compared by path plus method.

## Summary

| Category | Count |
|---|---:|
| Consistent endpoint rows | 99 |
| Implementation-only endpoint rows | 1 |
| Documentation-only endpoint rows | 0 |
| Total matrix rows | 100 |

## Findings requiring action

### Implementation-only endpoints now documented

| Service | Method | Path | Action |
|---|---|---|---|
| fulfillment | POST | `/api/v1/fulfillment-records/completions` | Added to contract in this change. |

### Documentation-only endpoints

None found.


## Service coverage

| Service | Matrix endpoint rows | Notes |
|---|---:|---|
| account | 6 | Audited. |
| admin-audit | 6 | Audited. |
| booking-orchestration | 4 | Audited. |
| capacity-availability | 5 | Audited. |
| customer-service | 10 | Audited. |
| entitlement-ticketing | 4 | Audited. |
| fare-pricing | 5 | Audited. |
| finance-settlement | 5 | Audited. |
| fulfillment | 5 | Audited. |
| journey-order | 4 | Audited. |
| legacy-acl | 6 | Audited. |
| notification | 0 | Audited; no business HTTP endpoints in implementation or contract. |
| offer-management | 2 | Audited. |
| payment | 6 | Audited. |
| place-network | 5 | Audited. |
| post-sales | 4 | Audited. |
| provider-integration | 2 | Audited. |
| reporting | 4 | Audited. |
| risk-compliance | 3 | Audited. |
| service-plan | 4 | Audited. |
| supplier-catalog | 5 | Audited. |
| traveler-profile | 4 | Audited. |
| trip-planning | 2 | Audited. |

## Full service × endpoint matrix

| Service | Method | Path | Implementation has | Documentation has | Status |
|---|---|---|---|---|---|
| account | GET | `/api/v1/accounts/{accountId}` | yes | yes | consistent |
| account | PATCH | `/api/v1/accounts/{accountId}/preferences` | yes | yes | consistent |
| account | POST | `/api/v1/accounts` | yes | yes | consistent |
| account | POST | `/api/v1/accounts/{accountId}/freeze` | yes | yes | consistent |
| account | POST | `/api/v1/accounts/{accountId}/start-closure` | yes | yes | consistent |
| account | POST | `/api/v1/accounts/{accountId}/unfreeze` | yes | yes | consistent |
| admin-audit | GET | `/api/v1/admin/audit-trail` | yes | yes | consistent |
| admin-audit | GET | `/api/v1/admin/operators/{operatorId}` | yes | yes | consistent |
| admin-audit | POST | `/api/v1/admin/manual-actions` | yes | yes | consistent |
| admin-audit | POST | `/api/v1/admin/manual-actions/{manualActionId}/approve` | yes | yes | consistent |
| admin-audit | POST | `/api/v1/admin/manual-actions/{manualActionId}/reject` | yes | yes | consistent |
| admin-audit | POST | `/api/v1/admin/operators` | yes | yes | consistent |
| booking-orchestration | GET | `/api/v1/internal/booking-sagas/{sagaId}` | yes | yes | consistent |
| booking-orchestration | POST | `/api/v1/internal/booking-sagas` | yes | yes | consistent |
| booking-orchestration | POST | `/api/v1/internal/booking-sagas/{sagaId}/mark-ticketed` | yes | yes | consistent |
| booking-orchestration | POST | `/api/v1/internal/booking-sagas/{sagaId}/request-reservation` | yes | yes | consistent |
| capacity-availability | GET | `/api/v1/availability-snapshots` | yes | yes | consistent |
| capacity-availability | GET | `/api/v1/capacity-holds/{holdId}` | yes | yes | consistent |
| capacity-availability | POST | `/api/v1/capacity-holds` | yes | yes | consistent |
| capacity-availability | POST | `/api/v1/capacity-holds/{holdId}/confirm` | yes | yes | consistent |
| capacity-availability | POST | `/api/v1/capacity-holds/{holdId}/release` | yes | yes | consistent |
| customer-service | GET | `/api/v1/support-cases/{caseId}` | yes | yes | consistent |
| customer-service | POST | `/api/v1/support-cases` | yes | yes | consistent |
| customer-service | POST | `/api/v1/support-cases/{caseId}/assign` | yes | yes | consistent |
| customer-service | POST | `/api/v1/support-cases/{caseId}/classify` | yes | yes | consistent |
| customer-service | POST | `/api/v1/support-cases/{caseId}/close` | yes | yes | consistent |
| customer-service | POST | `/api/v1/support-cases/{caseId}/escalate` | yes | yes | consistent |
| customer-service | POST | `/api/v1/support-cases/{caseId}/evidence` | yes | yes | consistent |
| customer-service | POST | `/api/v1/support-cases/{caseId}/manual-action-requests` | yes | yes | consistent |
| customer-service | POST | `/api/v1/support-cases/{caseId}/reopen` | yes | yes | consistent |
| customer-service | POST | `/api/v1/support-cases/{caseId}/resolve` | yes | yes | consistent |
| entitlement-ticketing | GET | `/api/v1/entitlements` | yes | yes | consistent |
| entitlement-ticketing | GET | `/api/v1/entitlements/{entitlementId}` | yes | yes | consistent |
| entitlement-ticketing | POST | `/api/v1/entitlements` | yes | yes | consistent |
| entitlement-ticketing | POST | `/api/v1/entitlements/{entitlementId}/void` | yes | yes | consistent |
| fare-pricing | GET | `/api/v1/fare-quotes/{quoteId}` | yes | yes | consistent |
| fare-pricing | POST | `/api/v1/adjustment-quotes` | yes | yes | consistent |
| fare-pricing | POST | `/api/v1/fare-quotes` | yes | yes | consistent |
| fare-pricing | POST | `/api/v1/fare-rule-sets` | yes | yes | consistent |
| fare-pricing | POST | `/api/v1/fare-rule-sets/{ruleSetId}/publish` | yes | yes | consistent |
| finance-settlement | GET | `/api/v1/invoices/{invoiceId}` | yes | yes | consistent |
| finance-settlement | GET | `/api/v1/reconciliation-cases` | yes | yes | consistent |
| finance-settlement | GET | `/api/v1/reconciliation-cases/{reconciliationCaseId}` | yes | yes | consistent |
| finance-settlement | GET | `/api/v1/revenue-recognitions/{revenueRecognitionId}` | yes | yes | consistent |
| finance-settlement | POST | `/api/v1/invoices` | yes | yes | consistent |
| fulfillment | GET | `/api/v1/fulfillment-records/{fulfillmentRecordId}` | yes | yes | consistent |
| fulfillment | POST | `/api/v1/fulfillment-records/boarding` | yes | yes | consistent |
| fulfillment | POST | `/api/v1/fulfillment-records/completions` | yes | no | implementation-only (pre-remediation state; documented in api/fulfillment.md by this same change) |
| fulfillment | POST | `/api/v1/fulfillment-records/no-show` | yes | yes | consistent |
| journey-order | GET | `/api/v1/journey-orders` | yes | yes | consistent |
| journey-order | GET | `/api/v1/journey-orders/{orderId}` | yes | yes | consistent |
| journey-order | POST | `/api/v1/journey-orders` | yes | yes | consistent |
| journey-order | POST | `/api/v1/journey-orders/{orderId}/cancel` | yes | yes | consistent |
| legacy-acl | POST | `/api/v1/legacy/cancel` | yes | yes | consistent |
| legacy-acl | POST | `/api/v1/legacy/execute` | yes | yes | consistent |
| legacy-acl | POST | `/api/v1/legacy/inside_payment` | yes | yes | consistent |
| legacy-acl | POST | `/api/v1/legacy/preserve` | yes | yes | consistent |
| legacy-acl | POST | `/api/v1/legacy/rebook` | yes | yes | consistent |
| legacy-acl | POST | `/api/v1/legacy/ticket_issue` | yes | yes | consistent |
| offer-management | GET | `/api/v1/offers/{offerId}` | yes | yes | consistent |
| offer-management | POST | `/api/v1/offers` | yes | yes | consistent |
| payment | GET | `/api/v1/payment-intents/{paymentIntentId}` | yes | yes | consistent |
| payment | GET | `/api/v1/refunds/{refundId}` | yes | yes | consistent |
| payment | POST | `/api/v1/payment-intents` | yes | yes | consistent |
| payment | POST | `/api/v1/payment-intents/{paymentIntentId}/cancel` | yes | yes | consistent |
| payment | POST | `/api/v1/payment-intents/{paymentIntentId}/capture` | yes | yes | consistent |
| payment | POST | `/api/v1/refunds` | yes | yes | consistent |
| place-network | GET | `/api/v1/places` | yes | yes | consistent |
| place-network | GET | `/api/v1/places/{placeId}` | yes | yes | consistent |
| place-network | GET | `/api/v1/transport-nodes/{nodeId}` | yes | yes | consistent |
| place-network | POST | `/api/v1/places` | yes | yes | consistent |
| place-network | POST | `/api/v1/transport-nodes` | yes | yes | consistent |
| post-sales | GET | `/api/v1/post-sales-cases/{caseId}` | yes | yes | consistent |
| post-sales | POST | `/api/v1/post-sales-cases` | yes | yes | consistent |
| post-sales | POST | `/api/v1/post-sales-cases/{caseId}/approve` | yes | yes | consistent |
| post-sales | POST | `/api/v1/post-sales-cases/{caseId}/evaluate` | yes | yes | consistent |
| provider-integration | POST | `/api/v1/internal/provider-reservations` | yes | yes | consistent |
| provider-integration | POST | `/api/v1/internal/provider-reservations/{segmentBookingId}/cancel` | yes | yes | consistent |
| reporting | GET | `/api/v1/dashboards/{dashboardId}` | yes | yes | consistent |
| reporting | GET | `/api/v1/dashboards/{dashboardId}/rebuilds` | yes | yes | consistent |
| reporting | GET | `/api/v1/metrics` | yes | yes | consistent |
| reporting | GET | `/api/v1/metrics/{metricId}` | yes | yes | consistent |
| risk-compliance | GET | `/api/v1/risk-assessments/{assessmentId}` | yes | yes | consistent |
| risk-compliance | POST | `/api/v1/risk-assessments` | yes | yes | consistent |
| risk-compliance | POST | `/api/v1/risk-blocks/lift` | yes | yes | consistent |
| service-plan | GET | `/api/v1/scheduled-services` | yes | yes | consistent |
| service-plan | GET | `/api/v1/scheduled-services/{serviceRef}` | yes | yes | consistent |
| service-plan | POST | `/api/v1/scheduled-services` | yes | yes | consistent |
| service-plan | POST | `/api/v1/service-segments` | yes | yes | consistent |
| supplier-catalog | GET | `/api/v1/suppliers` | yes | yes | consistent |
| supplier-catalog | GET | `/api/v1/suppliers/{supplierId}` | yes | yes | consistent |
| supplier-catalog | POST | `/api/v1/carriers` | yes | yes | consistent |
| supplier-catalog | POST | `/api/v1/contracts` | yes | yes | consistent |
| supplier-catalog | POST | `/api/v1/suppliers` | yes | yes | consistent |
| traveler-profile | GET | `/api/v1/travelers/{travelerId}` | yes | yes | consistent |
| traveler-profile | PATCH | `/api/v1/travelers/{travelerId}` | yes | yes | consistent |
| traveler-profile | POST | `/api/v1/travelers` | yes | yes | consistent |
| traveler-profile | POST | `/api/v1/travelers/{travelerId}/eligibility` | yes | yes | consistent |
| trip-planning | GET | `/api/v1/itineraries/{itineraryRef}` | yes | yes | consistent |
| trip-planning | POST | `/api/v1/itineraries/search` | yes | yes | consistent |

> Post-audit note (2026-07-08): REQ-105 subsequently REMOVED the two
> provider-integration internal HTTP command endpoints (dead API ruling);
> the provider-integration rows above describe the pre-removal state.
