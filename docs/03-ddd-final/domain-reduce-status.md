# 领域所有权与接口索引

更新日期：2026 年 9 月 24 日。

本表按事实所有者定位服务和已经注册的接口。
路径与语言见[语言及源码目录](../05-service-architecture/language-selection.md)，跨域规则见[设计决策](decision-record.md)。
部署基线为 Helm revision 279，发布证据见[当前项目状态](../00-current-status.md)。
工作进度及未完成能力统一见 [TODO](../TODO.md)。

## 查询、供给与报价

| 领域 | 拥有的事实与职责 | 已注册接口示例 | API 源码 |
|---|---|---|---|
| Place & Network | 标准地点、TransportNode 和地点查询。 | `POST /api/v1/places`；`POST /api/v1/transport-nodes`。 | [handler.go](../../services/place-network/internal/http/handler.go) |
| Service Plan | ScheduledService、ServiceSegment、运行日期、停站顺序和时刻变化。 | `GET /api/v1/internal/service-segments/:segmentRef/supply`；`POST /api/v1/scheduled-services/:serviceRef/delays`。 | [router.go](../../services/service-plan/internal/http/router.go) |
| Capacity & Availability | 可用性快照、区间 CapacityHold、确认和释放。 | `GET /api/v1/availability-snapshots`；`POST /api/v1/capacity-holds`。 | [api.rs](../../services/capacity-availability/src/api.rs) |
| Seat Assignment | SeatMap、SeatUnit、座位分配、无座分配和供给证据。 | `POST /api/v1/internal/seat-allocations`；`GET /api/v1/internal/capacity-facts`。 | [handlers.go](../../services/seat-assignment/internal/adapters/api/handlers.go) |
| Fare & Pricing | FareRuleSet、FareQuote、退改费和 AdjustmentQuote。 | `POST /api/v1/fare-quotes`；`POST /api/v1/adjustment-quotes`。 | [handlers.py](../../services/fare-pricing/src/fare_pricing/web/handlers.py) |
| Trip Planning | 行程搜索、候选详情、搜索价格及可用性提示。 | `POST /api/v1/itineraries/search`；`GET /api/v1/itineraries/{itineraryRef}`。 | [api.rs](../../services/trip-planning-rs/src/api.rs) |
| Offer Management | Offer 标识、有效期、报价与候选引用。 | `POST /api/v1/offers`；`GET /api/v1/offers/:offerId`。 | [app.ts](../../services/offer-management/src/app.ts) |
| Supplier Catalog | Supplier、Carrier、供货合同和供应商查询。 | `POST /api/v1/suppliers`；`POST /api/v1/contracts`。 | [router.go](../../services/supplier-catalog/internal/http/router.go) |
| Provider Integration | 供应商预留模型、供应商证据和外部状态归一职责。 | 当前 router 注册健康检查与 metadata 运行端点；供应商业务接入见 [TODO](../TODO.md)。 | [router.go](../../services/provider-integration/internal/http/router.go) |

Capacity 新联合供给已在提交 `caafa668` 中实现，尚未部署，工作状态见 [TODO](../TODO.md)。
Service Plan supply 与 Seat Assignment capacity facts 分别提供运行区间和编组供给依据。
Hold 的业务所有权归 Capacity，座位与无座分配事实归 Seat Assignment。

## 交易、支付与售后

| 领域 | 拥有的事实与职责 | 已注册接口示例 | API 源码 |
|---|---|---|---|
| Journey Order | JourneyOrder、商业金额摘要、订单项和已接受购买来源。 | `POST /api/v1/journey-orders`；`GET /api/v1/journey-orders/{orderId}`。 | [JourneyOrderController.java](../../services/journey-order/src/main/java/com/trainticket/journeyorder/api/JourneyOrderController.java) |
| Booking Orchestration | BookingSaga、SegmentBooking、预留状态和分段执行进度。 | `POST /api/v1/internal/booking-sagas`；`GET /api/v1/internal/segment-bookings/{segmentBookingId}`。 | [BookingOrchestrationController.java](../../services/booking-orchestration/src/main/java/com/trainticket/bookingorchestration/api/BookingOrchestrationController.java) |
| Payment | PaymentIntent、Refund、收款与退款状态、晚到收款处置。 | `POST /api/v1/payment-intents`；`POST /api/v1/refunds`。 | [PaymentController.java](../../services/payment/src/main/java/com/trainticket/payment/adapters/http/PaymentController.java) |
| Payment Channel | ChannelOrder、ChannelRefund、ChannelStatement 和渠道差异。 | `POST /api/v1/channel-orders`；`POST /api/v1/channel-refunds`；`GET /api/v1/channel-statements`。 | [handler.go](../../services/payment-channel/internal/http/handler.go) |
| Entitlement & Ticketing | Entitlement、出票前提核对、凭证和作废。 | `POST /api/v1/entitlements`；`POST /api/v1/entitlements/{entitlement_id}/void`。 | [lib.rs](../../services/entitlement-ticketing/src/lib.rs) |
| Fulfillment | 进站、完成、未乘车及运行状态事实。 | `POST /api/v1/fulfillment-records/boarding`；`POST /api/v1/fulfillment-records/completions`。 | [router.go](../../services/fulfillment/internal/http/router.go) |
| Post Sales | 售后 Case、评估、审批和执行计划。 | `POST /api/v1/post-sales-cases`；`POST /api/v1/post-sales-cases/{caseId}/approve`。 | [PostSalesController.java](../../services/post-sales/src/main/java/com/trainticket/postsales/api/PostSalesController.java) |
| Wallet / Promotion | PromotionInstrument、钱包余额、权益预留、核销和撤销。 | `POST /api/v1/benefits`；`GET /api/v1/wallet-accounts/{accountId}`。 | [WalletPromotionController.java](../../services/wallet-promotion/src/main/java/com/trainticket/walletpromotion/adapters/http/WalletPromotionController.java) |

Post Sales 的[改签执行接口](../../services/post-sales/src/main/java/com/trainticket/postsales/api/ChangeExecutionController.java)负责 `execute-change` 和出票授权查询。
[补偿执行接口](../../services/post-sales/src/main/java/com/trainticket/postsales/api/CompensationExecutionController.java)负责 `execute-compensation`，通过 Wallet 发放钱包补偿额度并保留原旅程权益。
具体事实与执行路由见[业务协作与接口](phase-1-contract.md)。

## 身份、通知与运营

| 领域 | 拥有的事实与职责 | 已注册接口示例 | API 源码 |
|---|---|---|---|
| Account | 账号、冻结状态、通知设置和关闭请求。 | `POST /api/v1/accounts`；`POST /api/v1/accounts/:accountId/start-closure`。 | [app.ts](../../services/account/src/app.ts) |
| Traveler Profile | TravelerProfile、证件资料和旅客资格摘要。 | `POST /api/v1/travelers`；`POST /api/v1/travelers/{travelerId}/eligibility`。 | [TravelerController.java](../../services/traveler-profile/src/main/java/com/trainticket/travelerprofile/adapters/http/TravelerController.java) |
| Identity Verification | 核验 Case、资格证明、购买前检查及限购使用事实。 | `POST /api/v1/identity-verification/verifications`；`POST /api/v1/identity-verification/pre-order-checks`。 | [handlers.py](../../services/identity-verification/src/identity_verification/web/handlers.py) |
| Risk & Compliance | 风险评估、规则判断、阻断与人工覆盖记录。 | `POST /api/v1/risk-assessments`；`POST /api/v1/risk-evaluations`。 | [api.py](../../services/risk-compliance/src/risk_compliance/api.py) |
| Notification | 通知任务、模板、触达尝试及投递记录。 | `GET /api/v1/notifications/:id/trail`。 | [app.ts](../../services/notification/src/app.ts) |
| Customer Service | SupportCase、证据、协作、升级和人工操作请求。 | `POST /api/v1/support-cases`；`POST /api/v1/support-cases/:caseId/manual-action-requests`。 | [app.ts](../../services/customer-service/src/app.ts) |
| Admin & Audit | 操作员、审批、ManualAction 与审计记录。 | `POST /api/v1/admin/manual-actions`；`GET /api/v1/admin/audit-trail`。 | [AdminAuditController.java](../../services/admin-audit/src/main/java/com/trainticket/adminaudit/adapters/http/AdminAuditController.java) |

Identity Verification 和 Risk & Compliance 使用 Python。
Notification durable 发送队列已在提交 `41917b85` 中实现、尚未部署，工作状态见 [TODO](../TODO.md)。

## 财务、恢复与商业服务

| 领域 | 拥有的事实与职责 | 已注册接口示例 | API 源码 |
|---|---|---|---|
| Finance Settlement | 收入确认、结算、财务对账、退款冲减和权益成本。 | `GET /api/v1/order-revenue-bases/{orderId}`；`GET /api/v1/benefit-costs`。 | [FinanceSettlementController.java](../../services/finance-settlement/src/main/java/com/trainticket/financesettlement/adapters/http/FinanceSettlementController.java) |
| Invoicing | 发票抬头、电子发票申请、金额依据、红冲与行程凭证。 | `POST /api/v1/e-invoice-requests`；`GET /api/v1/invoice-amount-bases/{order_id}`。 | [lib.rs](../../services/invoicing/src/lib.rs) |
| Reporting | 事件读模型、经营指标与 gross captured payment 金额统计。 | `GET /api/v1/metrics/revenue`；`GET /api/v1/metrics/operational`。 | [api.py](../../services/reporting/src/reporting/api.py) |
| Disruption Recovery | 运行异常、受影响旅程、恢复 Case 和选择结果。 | `POST /api/v1/disruptions`；`POST /api/v1/recovery-cases/{case_id}/select-option`。 | [handlers.py](../../services/disruption-recovery/src/disruption_recovery/web/handlers.py) |
| Transfer Management | 接续计划、ConnectionContract、MCT 规则和风险策略。 | `POST /api/v1/connection-contracts`；`POST /api/v1/connections/{connection_id}/reaccommodate`。 | [handlers.py](../../services/transfer-management/src/transfer_management/web/handlers.py) |
| Ancillary Service | 附加商品、报价、附加订单项、支付关联和履约事实。 | `POST /api/v1/ancillary-offers`；`POST /api/v1/ancillary-order-items/:ancillaryOrderItemId/fulfillment-facts`。 | [app.ts](../../services/ancillary-service/src/app.ts) |
| Waitlist | 候补请求、队列项、接受和取消。 | `POST /api/v1/waitlist/entries`；`POST /api/v1/waitlist/entries/:entryId/accept`。 | [app.ts](../../services/waitlist/src/app.ts) |
| Dispatch | RideRequest、司机分配、ETA 和行程状态。 | `POST /api/v1/ride-requests`；`POST /api/v1/ride-requests/:rideRequestId/assign`。 | [handler.go](../../services/dispatch/internal/http/handler.go) |
| Corporate Travel | 企业协议、授权、差旅规则检查和月结账单。 | `POST /api/v1/agreements`；`POST /api/v1/agreements/{agreementId}/monthly-invoices`。 | [api.py](../../services/corporate-travel/src/corporate_travel/api.py) |
| Group Booking | 团体订单、成员、确认和取消。 | `POST /api/v1/group-bookings`；`PUT /api/v1/group-bookings/{groupBookingId}/members`。 | [GroupBookingController.java](../../services/group-booking/src/main/java/com/trainticket/groupbooking/api/GroupBookingController.java) |
| Loyalty Membership | 会员、积分账户、等级和兑换。 | `POST /members/enroll`；`POST /members/:id/redeem-ticket`。 | [app.ts](../../services/loyalty-membership/src/app.ts) |
| Marketing Campaign | Campaign、CouponTemplate、IssuanceBatch 和活动预算。 | `POST /api/v1/campaigns`；`POST /api/v1/campaigns/{campaignId}/batches`。 | [CampaignController.java](../../services/marketing-campaign/src/main/java/com/trainticket/marketingcampaign/api/CampaignController.java)、[IssuanceBatchController.java](../../services/marketing-campaign/src/main/java/com/trainticket/marketingcampaign/api/IssuanceBatchController.java) |
| Travel Insurance | Policy、Claim 和理赔审批。 | `POST /api/v1/policies`；`POST /api/v1/claims/:id/approve`。 | [router.go](../../services/travel-insurance/api/router.go) |

上述 Corporate Travel、Group Booking、Loyalty Membership、Marketing Campaign、Travel Insurance 已有源码和 API，路径与语言见[语言及源码目录](../05-service-architecture/language-selection.md)。
catalog 登记差异统一见 [TODO T10](../TODO.md#t10-reconcile-repository-metadata-and-static-checks)。
接口调用必须同时定位服务与路径。
Finance 的 `/api/v1/invoices` 处理本域 Invoice 记录，电子发票申请路由至 Invoicing 的 `/api/v1/e-invoice-requests`。
