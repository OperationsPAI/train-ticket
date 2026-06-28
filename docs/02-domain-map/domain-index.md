# Domain Map Index

Last updated: 2026-06-28

## 使用方式

每行代表一个可分配给 agent 的独立设计任务。Map 阶段只编辑对应的 `Output` 文件；跨域问题写入 `docs/03-ddd-reduce/conflict-log.md`。

| Domain | Type | Priority | Output | Primary Inputs |
|---|---|---|---|---|
| Account | 通用域 | P1 | `docs/02-domain-map/domains/account.md` | Account, Risk & Compliance |
| Traveler Profile | 通用域 | P0 | `docs/02-domain-map/domains/traveler-profile.md` | Passenger Profile, eligibility |
| Place & Network | 通用域 | P0 | `docs/02-domain-map/domains/place-network.md` | stations, airports, ports, POI |
| Supplier Catalog | 支撑域 | P1 | `docs/02-domain-map/domains/supplier-catalog.md` | provider and carrier registry |
| Service Plan | 支撑域 | P0 | `docs/02-domain-map/domains/service-plan.md` | train trips, schedules, operation calendar |
| Capacity & Availability | 核心域 | P0 | `docs/02-domain-map/domains/capacity-availability.md` | inventory, holds, quota, waitlist |
| Fare & Pricing | 支撑域 | P0 | `docs/02-domain-map/domains/fare-pricing.md` | fares, fees, refund/change rules |
| Trip Planning | 核心域 | P0 | `docs/02-domain-map/domains/trip-planning.md` | search, itinerary, route plan |
| Offer Management | 核心域 | P0 | `docs/02-domain-map/domains/offer-management.md` | quote, price snapshot, offer expiry |
| Journey Order | 核心域 | P0 | `docs/02-domain-map/domains/journey-order.md` | commercial order, order summary |
| Booking Orchestration | 核心域 | P0 | `docs/02-domain-map/domains/booking-orchestration.md` | segment booking, saga orchestration |
| Provider Integration | 支撑域 | P0 | `docs/02-domain-map/domains/provider-integration.md` | ACL, supplier status mapping |
| Entitlement & Ticketing | 支撑域 | P0 | `docs/02-domain-map/domains/entitlement-ticketing.md` | ticket, credential, boarding pass |
| Fulfillment | 支撑域 | P1 | `docs/02-domain-map/domains/fulfillment.md` | check-in, boarding, arrival, completion |
| Transfer Management | 核心域 | P1 | `docs/02-domain-map/domains/transfer-management.md` | transfer risk, connection contract |
| Post Sales | 支撑域 | P0 | `docs/02-domain-map/domains/post-sales.md` | cancel, refund, change, rebook |
| Disruption Recovery | 核心域 | P1 | `docs/02-domain-map/domains/disruption-recovery.md` | delay, cancellation, reaccommodation |
| Ancillary Service | 支撑域 | P1 | `docs/02-domain-map/domains/ancillary-service.md` | insurance, food, consign, baggage |
| Payment | 通用域 | P0 | `docs/02-domain-map/domains/payment.md` | payment intent, refund, authorization |
| Notification | 通用域 | P1 | `docs/02-domain-map/domains/notification.md` | message template, send retry |
| Customer Service | 通用域 | P1 | `docs/02-domain-map/domains/customer-service.md` | case, complaint, manual handling |
| Risk & Compliance | 通用域 | P1 | `docs/02-domain-map/domains/risk-compliance.md` | fraud, duplicate journey, restriction |
| Finance Settlement | 通用域 | P1 | `docs/02-domain-map/domains/finance-settlement.md` | reconciliation, settlement, invoice |
| Admin & Audit | 通用域 | P1 | `docs/02-domain-map/domains/admin-audit.md` | permission, approval, audit |
| Reporting | 通用域 | P2 | `docs/02-domain-map/domains/reporting.md` | operational and financial read models |

## 推荐并行批次

### Batch 1：交易核心

1. Capacity & Availability
2. Offer Management
3. Journey Order
4. Booking Orchestration
5. Payment
6. Entitlement & Ticketing
7. Post Sales

### Batch 2：供给和查询

1. Place & Network
2. Service Plan
3. Fare & Pricing
4. Trip Planning
5. Provider Integration

### Batch 3：履约、异常和联乘

1. Fulfillment
2. Transfer Management
3. Disruption Recovery
4. Ancillary Service

### Batch 4：平台治理

1. Account
2. Traveler Profile
3. Notification
4. Customer Service
5. Risk & Compliance
6. Finance Settlement
7. Admin & Audit
8. Reporting
