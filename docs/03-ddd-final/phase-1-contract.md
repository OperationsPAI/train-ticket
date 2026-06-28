# Phase 1 Train Ticket Contract

Last updated: 2026-06-28

## 目的

这份文档定义第一阶段火车票务重构的全局契约。它不是 API 文档，也不是数据库设计，而是后续详细 domain 设计和工程任务拆分必须共同遵守的业务边界。

第一阶段目标是把火车固定班次票务闭环做成可解释、可恢复、可迁移的模型：

1. 查询和报价不锁库存。
2. 下单、占座、支付、出票拆成独立生命周期。
3. 支付成功不等于出票成功。
4. 出票成功不等于订单汇总已确认。
5. 售后先决策，再作废票证，再退款。
6. 人工和旧系统迁移不能绕过聚合命令。

## 范围假设

| Area | Phase 1 Assumption |
|---|---|
| Transport mode | 火车固定班次。 |
| Inventory authority | 默认平台内部库存权威，支持区间座席 Hold。 |
| Payment | 现金支付和原路退款；钱包、积分、券和多人组合支付已裁定为 future-scope。 |
| Offer | 不锁库存，只冻结报价、规则、可用性快照和有效期。 |
| Entitlement | 主运输票证；纸质票取票作为可选 CheckIn，不拆 CredentialRegistry。 |
| Fulfillment | 至少记录检票或进站事实；没有可信到达数据时不自动 Used。 |
| Provider Integration | 最小 ACL，包含支付渠道协议、供应商状态映射、幂等、raw archive 和错误归一。 |
| Waitlist | 已裁定为独立 future-scope 上下文；第一阶段不实现。 |
| Ancillary | 可作为独立 OrderItem 摘要，失败不阻断主票；复杂 bundle 和自动售后不进入第一阶段。 |
| Transfer | 可保留风险披露和模型引用；第一阶段不承诺保障联乘权益。 |
| Finance / Reporting | 消费事件和读模型，不作为第一阶段交易闭环 gate。 |

## 核心写模型所有权

| Aggregate | Owner | Owns | Does Not Own |
|---|---|---|---|
| TripIntent / Itinerary | Trip Planning | 查询条件、候选行程、排序解释 | 价格承诺、库存锁定、订单 |
| Offer | Offer Management | 报价快照、规则快照、风险披露、有效期 | 库存 Hold、支付、供应商确认 |
| JourneyOrder | Journey Order | 商业订单、OrderItem 摘要、旅客引用、订单汇总状态 | 库存、支付渠道、出票细节、供应商原始状态 |
| SegmentBooking | Booking Orchestration | 分段预订状态、供应确认映射、Saga 进度 | 用户商业金额、支付渠道、票证凭证 |
| CapacityHold | Capacity & Availability | 区间库存 Hold、Confirm、Release、Expire | 订单状态、支付状态、退改规则 |
| PaymentIntent / Refund | Payment | 应收、授权、扣款、退款、渠道回调幂等 | 是否允许出票、是否允许退票、订单确认 |
| Entitlement | Entitlement & Ticketing | 票证权益签发、作废、冻结、展示凭证 | 物理登乘事实、资金状态 |
| FulfillmentRecord | Fulfillment | 检票、登乘、完成、NoShow 等物理事实 | 权益签发、退款规则 |
| PostSalesCase | Post Sales | 退改 Case、规则评估、金额决策、执行步骤 | 支付渠道执行、订单内部状态直接写入 |
| NotificationTask | Notification | 模板、收件人、渠道、发送、回执、重试 | 是否推进核心业务状态 |
| ManualAction / AuditTrail | Admin & Audit | 权限、审批、审计、受控人工命令入口 | 目标 domain 不变量 |

## 正向购票链路

| Step | Command | Owner | Success Event | Rule |
|---|---|---|---|---|
| 1 | SearchItineraries | Trip Planning | ItineraryProposed | 只返回候选，不承诺可售。 |
| 2 | QuoteOffer | Offer Management | OfferQuoted | Offer 引用 AvailabilitySnapshot 和 PriceSnapshot，不 Hold。 |
| 3 | CreateJourneyOrder | Journey Order | JourneyOrderCreated | 必须引用有效 Offer 和旅客快照。 |
| 4 | StartBookingSaga | Booking Orchestration | BookingSagaStarted | Saga 状态必须可查。 |
| 5 | RequestSegmentReservation | Booking Orchestration | SegmentReservationRequested | 内部库存走 Capacity；外部供应商走 Provider Integration。 |
| 6 | HoldCapacity | Capacity & Availability | CapacityHeld | 火车必须按车次、日期、席别、座位、区间判断冲突。 |
| 7 | ConfirmSegmentReservation | Booking Orchestration | SegmentReservationConfirmed | 如果来自供应商，必须由 `ProviderReservationConfirmed` 映射。 |
| 8 | MarkPendingPayment | Journey Order | JourneyOrderPendingPayment | 只发布待支付商业事实。 |
| 9 | CreatePaymentIntent | Payment | PaymentIntentCreated | 由 Saga 或 Payment OHS 发起。 |
| 10 | CapturePayment | Payment | PaymentCaptured | 不能直接改订单、出票或库存。 |
| 11 | ConfirmHold | Capacity & Availability | CapacityHoldConfirmed | 只有确认后的 Hold 才可用于出票条件。 |
| 12 | IssueEntitlement | Entitlement & Ticketing | EntitlementIssued | 必须满足 Booking、Payment、Capacity、Traveler、Risk 条件。 |
| 13 | MarkSegmentTicketed | Booking Orchestration | SegmentTicketed | SegmentBooking 记录出票完成。 |
| 14 | ConfirmJourneyOrder | Journey Order | JourneyOrderConfirmed | 汇总条件满足后确认订单。 |
| 15 | ScheduleNotification | Notification | NotificationScheduled | 通知失败不回滚业务。 |

## 退票链路

| Step | Command | Owner | Success Event | Rule |
|---|---|---|---|---|
| 1 | RequestPostSales | Post Sales | PostSalesRequested | 售后入口，不直接退款。 |
| 2 | EvaluateRefundRule | Fare & Pricing / Post Sales | PostSalesEvaluated | Fare 计算规则，Post Sales 拥有 Case 决策。 |
| 3 | ApprovePostSales | Post Sales | PostSalesApproved | 冻结规则版本和金额摘要。 |
| 4 | VoidEntitlement | Entitlement & Ticketing | EntitlementVoided | 已 Used 不允许普通 Voided。 |
| 5 | CancelSegmentBooking | Booking Orchestration | SegmentBookingCancelled | 外部供应商取消经 Provider Integration。 |
| 6 | ReleaseCapacity | Capacity & Availability | CapacityReleased | 释放失败重试，不直接改订单。 |
| 7 | RequestRefund | Payment | RefundRequested | Payment 执行资金，不重新判定规则。 |
| 8 | SettleRefund | Payment | RefundSettled | 退款失败进入重试或人工，不恢复已作废票。 |
| 9 | ApplyPostSalesResult | Post Sales / Journey Order | PostSalesApplied / JourneyOrderAdjusted | Post Sales 发布决策，JourneyOrder 汇总自身订单项。 |

## 改签链路

第一阶段采用保守顺序：

1. 校验旧票可改。
2. 生成 ChangeOffer。
3. Hold 新库存。
4. 处理补差价；新票更便宜时可先完成改签，退款异步执行。
5. 作废旧 Entitlement。
6. 确认新 SegmentBooking。
7. 签发新 Entitlement。
8. 释放旧库存。
9. 更新 JourneyOrder 商业汇总。

禁止迁移旧系统中的删单式改签。旧订单和旧票必须通过 replacement booking、旧权益作废、差价支付或退款、库存释放形成可审计链路。

## 失败和晚到事件

| Scenario | Required Handling |
|---|---|
| PaymentCaptured late after order cancelled | 创建 LatePaymentCase，自动退款或人工确认；不能直接恢复订单。 |
| ProviderReservationConfirmed late after cancellation | 尝试供应商取消；不可取消时进入 ProviderConflictCase。 |
| EntitlementIssued late after refund request | 先 Suspend Entitlement，再由 Post Sales 判断作废或补偿。 |
| RefundFailed after EntitlementVoided | 重试退款或人工处理；不能静默恢复票证。 |
| CapacityHeld but PaymentExpired | Release 或 Expire Hold，订单取消或回到可支付策略。 |
| Ticket issued but JourneyOrder confirm failed | 重放汇总事件或人工修复订单汇总；不能重复出票。 |

## 事件命名契约

| External / ACL Fact | Internal Domain Fact |
|---|---|
| ProviderReservationConfirmed | SegmentReservationConfirmed |
| ProviderReservationFailed | SegmentReservationFailed |
| ChannelPaymentCaptured | PaymentCaptured |
| ChannelRefundSettled | RefundSettled |
| SupplierTicketIssued | EntitlementIssued |
| ProviderBoardingAccepted | BoardingVerified |

外部事实必须先经过 ACL 映射。核心域事件不能直接带供应商状态码作为业务状态。

## 第一阶段红线

1. `PaymentCaptured` 不能直接改 JourneyOrder、Entitlement、Capacity 或 Notification。
2. `JourneyOrder` 不能直接扣库存、调用供应商或调用支付渠道。
3. `Provider Integration` 不能拥有 SegmentBooking 聚合。
4. `Service Plan` 不能拥有停售限售、价格规则或用户购买资格。
5. `Reporting` 不能提供修订单、修库存、补退款的写入口。
6. `Customer Service` 不能直接改业务表；必须进入目标 domain 命令。
7. `Notification` 失败不能回滚业务成功。
8. 旧系统直接写状态能力必须封装为 Legacy ACL 和受控命令。

## 第一阶段可验收标准

| Gate | Criteria |
|---|---|
| Search / Offer | Search 不锁库存；Offer 有有效期、价格快照、规则快照和 AvailabilitySnapshot 引用。 |
| Order / Booking | JourneyOrder、SegmentBooking、CapacityHold、PaymentIntent、Entitlement 分开存储和推进。 |
| Payment | 支付回调幂等；晚到成功不会直接确认订单。 |
| Inventory | 火车区间库存按重叠区间判断 Hold 冲突。 |
| Ticketing | 出票失败可观察、可重试、可人工。 |
| Post Sales | 已出票退票先作废权益再退款；退款失败不恢复票。 |
| Migration | 旧 preserve、cancel、rebook、payment 副作用被拆成命令、事件和 Legacy ACL。 |
| Operations | 客服和后台有 OrderTimeline、Saga 状态和人工命令审计。 |
