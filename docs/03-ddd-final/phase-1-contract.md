# 当前业务协作与接口

更新日期：2026 年 9 月 24 日。

本文件说明 Helm revision 279 基线下的业务所有权、实际接口和协作事实。
发布证据见[当前项目状态](../00-current-status.md)，各服务 API 源码入口见[领域所有权索引](domain-reduce-status.md)。
未完成工作与验收状态统一见 [TODO](../TODO.md)。

## 事实与生命周期

| 事实 | 所有者 | 关联内容 |
|---|---|---|
| 已接受购买来源 | Journey Order | 订单创建时的 Offer、旅客和分段引用。 |
| 分段预订 | Booking Orchestration | Saga、SegmentBooking、旅客与执行结果。 |
| 库存预留 | Capacity & Availability | CapacityHold、区间、有效期和确认状态。 |
| 座位或无座分配 | Seat Assignment | SeatAllocation、编组和政策证据。 |
| 平台支付和退款 | Payment | PaymentIntent、Refund、金额和渠道关联。 |
| 渠道交互 | Payment Channel | ChannelOrder、ChannelRefund 和渠道账单。 |
| 主票权益 | Entitlement & Ticketing | Entitlement、凭证、分配引用和使用状态。 |
| 售后决策与执行 | Post Sales | Case、审批金额、目标 Offer 和执行记录。 |
| 钱包补偿 | Wallet / Promotion | benefit、caseId、发放金额和余额。 |
| 履约 | Fulfillment | 进站、完成和未乘车事实。 |

每个负责服务校验自身命令并保存状态。
其他服务通过业务引用和事件读取结果。
事务内业务记录与 outbox、消费者去重语义见[消息说明](../08-contracts/messaging.md)。
历史事件恢复的验收状态见 [TODO](../TODO.md)。

## 购票协作

| 业务操作 | 接口与负责服务 | 结果与下游用途 |
|---|---|---|
| 核对旅客及购买条件 | Traveler Profile、Identity Verification、Risk & Compliance 的档案、核验和评估接口。 | 旅客引用、资格和风险结果用于报价及下单校验。 |
| 查询行程 | Trip Planning：`POST /api/v1/itineraries/search`。 | 候选行程及查询提示。 |
| 获取报价 | Offer Management：`POST /api/v1/offers`。 | Offer 及价格、规则、候选引用。 |
| 创建订单 | Journey Order：`POST /api/v1/journey-orders`。 | JourneyOrderCreated 与已接受购买来源。 |
| 管理分段预订 | Booking：`/api/v1/internal/booking-sagas`、`/api/v1/internal/segment-bookings/{segmentBookingId}`。 | Saga、SegmentBooking 与预订进度。 |
| 预留与确认容量 | Capacity：`POST /api/v1/capacity-holds`、`POST /api/v1/capacity-holds/{hold_id}/confirm`。 | Hold、CapacityHeld 与 CapacityHoldConfirmed。 |
| 创建并收取支付 | Payment：`POST /api/v1/payment-intents`、`POST /api/v1/payment-intents/{paymentIntentId}/capture`。 | 平台 PaymentIntent 与 PaymentCaptured。 |
| 执行渠道交互 | Payment Channel：`/api/v1/channel-orders`。 | 渠道提交、查询与结果事实返回 Payment。 |
| 核对并签发权益 | Entitlement：`POST /api/v1/entitlements`。 | 核对预订、支付、Hold 和订单事实，调用 Seat Assignment 分配，产生 EntitlementIssued。 |
| 汇总购买结果 | Booking 与 Journey Order 消费出票和资金事实。 | 更新各自分段执行状态与商业订单摘要。 |
| 发送通知 | Notification 消费业务事件。 | 记录触达尝试并提供 trail 查询。 |

搜索和报价只读取可用性信息。
出票要求持有负责服务确认的业务事实。
`PaymentCaptured` 表达资金已收取，`EntitlementIssued` 表达权益已签发，订单摘要由 Journey Order 自行推进。
多人购买按旅客与分段保留预订和权益引用。

## 供给与分配

Service Plan 的 supply 接口提供服务、日期相关运行区间、停站序列及版本依据。
Seat Assignment 的 capacity facts 提供已发布编组与可分配供给。
Capacity 管理 Hold 的区间占用和状态。
Seat Assignment 管理物理座位分配、无座配额和分配重放证据。
Entitlement 保存分配结果并形成可使用票证。
Capacity 新联合供给仍未提交、未部署，接入工作见 [TODO](../TODO.md)。

## 退票与取消

Post Sales 通过 Case 明确处理范围、退票原因、金额和批准的动作。
Fare 计算费用与退款依据，Payment 接收资金执行要求。
Entitlement、Booking、Capacity 和 Seat Assignment 分别记录权益作废、预订取消、Hold 释放和分配释放。
Post Sales 收集执行事实并形成 Case 结果，Journey Order、Finance 和 Invoicing 消费对应结果。

`PostSalesApproved` 由不同服务分别消费。
[PaymentInboundEventHandler](../../services/payment/src/main/java/com/trainticket/payment/application/PaymentInboundEventHandler.java)按批准的退款动作发起 Refund。
[Entitlement 事件处理](../../services/entitlement-ticketing/src/lib.rs)按批准的权益动作处理客票。
跨服务事件消费具有各自进度，Case 完成需要核对关联结果。
详细状态与消息处理要求见[跨域设计决策](decision-record.md)。

## 改签执行

当前执行入口为 `POST /api/v1/post-sales-cases/{caseId}/execute-change`。
[ChangeExecutionService](../../services/post-sales/src/main/java/com/trainticket/postsales/application/ChangeExecutionService.java)保存目标、支付、预订、出票和结算的执行记录。

1. 核对原订单、目标 Offer、目标行程、FareQuote 与已有支付余额，保存本次接受的目标资料。
2. 通过 Booking 的 `POST /api/v1/internal/change-reservations` 提交全部目标旅客与分段预订。
3. 读取替代 SegmentBooking 和 CapacityHold，确认目标容量结果。
4. 存在应补差额时，通过 Payment 创建并完成对应 Case 的差额收款。
5. 通过 Entitlement 签发 replacement ticket，出票授权来自 Post Sales 的 `issue-authorization/{bookingId}`。
6. 在替代票签发完成后作废原票，等待原预订取消和原 Hold 释放。
7. 存在应退差额时，按记录的资金来源请求退款并确认结算结果。
8. 调用 Journey Order 的 `POST /api/v1/journey-orders/{orderId}/apply-change`，完成 Case。

执行记录使重复推进能够读取已完成步骤。
目标 Offer 过期、差额支付终止和容量不足分别保留明确失败原因。
已部署的容量不足补偿会保留原票，作废已签发替代票，取消替代预订，释放替代容量，并退回已收差额。
Booking 的 `POST /api/v1/internal/change-compensations/{bookingId}` 核对 Case 后处理替代预订。
其他失败路径与完整验收范围见 [TODO](../TODO.md)。

## 服务质量补偿与钱包补偿额度

服务质量 `COMPENSATION` 的执行入口为 `POST /api/v1/post-sales-cases/{caseId}/execute-compensation`。
[CompensationExecutionService](../../services/post-sales/src/main/java/com/trainticket/postsales/application/CompensationExecutionService.java)负责核对审批和发放结果。

1. 读取已批准 Case，核对原订单、原票、原支付和 Case 下的退款记录。
2. 保存批准的收款账号、金额、caseId、有效期和 Wallet 请求。
3. 调用 Wallet 的 `POST /api/v1/benefits`，使用稳定幂等键和 Case 关联。
4. 读取 benefit，核对接收人、金额、来源和类型，再保存执行证据。
5. 完成 Post Sales Case，并发布业务结果。

发放字段为 `benefitType=COMPENSATION_CREDIT`、`balanceType=PROMOTION_CREDIT`、`issuanceSource=POST_SALES_COMP`。
当前请求的有效期为发放请求生成后的 30 天。
原票、SeatAllocation、CapacityHold、原支付和附加服务保持有效。
Payment 对该 Case 保留原收款，Finance 根据 `BenefitIssued` 记录补偿成本，Invoicing 保留原订单的金额依据。
原路退款使用 Payment Refund，改签失败补偿使用改签执行记录，两类操作各有明确业务原因和资金关联。

## 财务、发票与报表

| 处理 | 所有者与入口 | 依据 |
|---|---|---|
| 收入与结算 | Finance：`GET /api/v1/revenue-recognitions/{revenueRecognitionId}`、`GET /api/v1/settlements/daily/{date}`。 | 平台资金、订单、履约与售后事实。 |
| 补偿成本 | Finance：`GET /api/v1/benefit-costs`。 | BenefitIssued 和对应 Case。 |
| 电子发票 | Invoicing：`POST /api/v1/e-invoice-requests`。 | 订单投影、发票抬头与金额依据。 |
| 发票金额查询 | Invoicing：`GET /api/v1/invoice-amount-bases/{order_id}`。 | 本域已持久化的金额投影。 |
| gross captured payment | Reporting：`GET /api/v1/metrics/revenue`。 | 只累计 PaymentCaptured 金额。 |

Reporting 现有响应字段 `totalRevenue` 和 `revenue` 表示已收款总额。
支持的金额维度为 `route`、`seat_class`、`channel`、`passenger_type` 和 `time_period`。
`source_context` 与 `event_type` 返回事件计数口径。
币种、分页总额和维度完善工作见 [TODO](../TODO.md)。

## 履约、恢复与通知

Fulfillment 的 boarding、completions 和 no-show 接口形成使用事实。
Transfer Management 根据接续规则与运行信息形成接续判断。
Disruption Recovery 保存受影响旅程的选项及选择，售后动作由 Post Sales 执行。
Notification 保存业务通知与触达记录，`GET /api/v1/notifications/:id/trail` 提供发送尝试查询。
Notification durable 发送队列为 WIP、未部署，工作状态见 [TODO](../TODO.md)。
