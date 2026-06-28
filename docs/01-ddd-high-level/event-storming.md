# 出行业务事件风暴草案

Last updated: 2026-06-28

## 目的

这份文档把核心业务流展开为 `Command -> Aggregate -> Domain Event -> Policy -> Read Model`。它面向后续重构任务拆分：当我们开始改代码时，不再围绕当前服务接口猜业务，而是围绕命令、聚合和事件推进。

本文优先覆盖 P0/P1 主链路：

1. 搜索和报价。
2. 创建订单、锁库存、支付、出票。
3. 取消、退票、退款。
4. 改签和差价结算。
5. 候补。
6. 联乘中转。
7. 异常恢复。

## 图例

| 类型 | 说明 | 示例 |
|---|---|---|
| Actor | 发起动作的人或外部系统 | 用户、运营、供应商、支付渠道 |
| Command | 试图改变系统状态的请求 | CreateJourneyOrder |
| Aggregate | 处理命令并保护不变量的聚合根 | JourneyOrder |
| Domain Event | 已经发生的业务事实 | JourneyOrderCreated |
| Policy | 对事件做出反应的业务策略 | 支付成功后触发出票 |
| Read Model | 面向查询或展示的投影 | OrderTimeline |
| External System | 外部系统 | 支付渠道、航司、铁路供应商 |

## Flow 1：搜索和报价

### 业务意图

用户表达出行意图，系统返回可购买或可继续确认的行程方案。这个流程不锁库存，只生成报价快照。

| Step | Actor | Command | Aggregate | Event | Policy | Read Model |
|---|---|---|---|---|---|---|
| 1 | 用户 | SearchItineraries | TripIntent | TripIntentCreated | 查询地点、运行计划、可用性和价格 | SearchResult |
| 2 | Trip Planning | ProposeItinerary | Itinerary | ItineraryProposed | 评估 Segment 和 Transfer 可达性 | ItineraryList |
| 3 | Transfer Management | EvaluateTransferRisk | TransferPlan | TransferRiskEvaluated | 标记保障联乘或自助中转 | TransferRiskView |
| 4 | Offer Management | QuoteOffer | Offer | OfferQuoted | 设置有效期、价格快照、票规快照 | OfferDetail |
| 5 | Offer Management | ExpireOffer | Offer | OfferExpired | 禁止继续下单，提示重新报价 | OfferStatusView |

### 关键规则

1. SearchResult 可以过期，下单前必须使用 Offer。
2. Offer 必须包含价格、规则、有效期和风险提示。
3. 非保障联乘必须在 Offer 中显式标注。
4. 查询链路不能直接创建订单或锁库存。

## Flow 2：创建订单、锁库存、支付、出票

### 业务意图

用户接受 Offer 后创建 Journey Order。系统确认每段可预订，完成支付，签发权益凭证。

| Step | Actor | Command | Aggregate | Event | Policy | Read Model |
|---|---|---|---|---|---|---|
| 1 | 用户 | CreateJourneyOrder | JourneyOrder | JourneyOrderCreated | 触发分段预订编排 | OrderTimeline |
| 2 | Booking Orchestration | RequestSegmentReservation | SegmentBooking | SegmentReservationRequested | 内部库存走 Hold，外部供应商走 ACL | BookingProgress |
| 3 | Booking Orchestration | HoldCapacity | CapacityHold | CapacityHeld | 如果所有必要 Segment 已 Hold，进入支付 | InventoryHoldView |
| 4 | Provider Integration | ConfirmSegmentReservation | SegmentBooking | SegmentReservationConfirmed | 更新订单分段确认进度 | BookingProgress |
| 5 | Journey Order | MarkPendingPayment | JourneyOrder | JourneyOrderPendingPayment | 创建 PaymentIntent | OrderTimeline |
| 6 | Payment | CreatePaymentIntent | PaymentIntent | PaymentIntentCreated | 打开收银台或发起预授权 | PaymentView |
| 7 | 支付渠道 | CapturePayment | PaymentIntent | PaymentCaptured | 触发确认 Hold 和出票 | PaymentView |
| 8 | Capacity | ConfirmHold | CapacityHold | CapacityHoldConfirmed | 通知 Booking 可以出票 | InventoryView |
| 9 | Ticketing | IssueEntitlement | Entitlement | EntitlementIssued | 汇总订单确认条件 | EntitlementView |
| 10 | Journey Order | ConfirmJourneyOrder | JourneyOrder | JourneyOrderConfirmed | 发送出票通知 | OrderTimeline |
| 11 | Notification | ScheduleNotification | NotificationTask | NotificationScheduled | 异步发送 | MessageOutbox |

### 失败分支

| 场景 | 事件 | 补偿策略 |
|---|---|---|
| 任一 Segment 库存不足 | SegmentReservationFailed | 释放已 Hold 资源，订单进入 PartiallyConfirmed 或 Failed。 |
| 支付超时 | PaymentIntentExpired | 取消订单，释放 Hold，通知用户。 |
| 支付成功但出票失败 | EntitlementIssueFailed | 保留订单为 Confirming 或 PartiallyConfirmed，重试出票或退款。 |
| 支付晚到成功 | PaymentLateSuccessDetected | 不直接确认订单，进入退款或人工处理。 |
| 外部供应商确认超时 | ProviderReservationTimeout | 查询供应商最终状态，不能盲目重试创建重复订单。 |

### 关键规则

1. `PaymentCaptured` 不等于 `EntitlementIssued`。
2. `CapacityHeld` 不等于 `JourneyOrderConfirmed`。
3. 订单确认条件由 Booking Orchestration 和 Journey Order 汇总，不由 Payment 独立决定。
4. 每个跨上下文命令必须携带幂等键。

## Flow 3：取消未支付订单

| Step | Actor | Command | Aggregate | Event | Policy | Read Model |
|---|---|---|---|---|---|---|
| 1 | 用户或超时任务 | CancelJourneyOrder | JourneyOrder | JourneyOrderCancelled | 释放库存，取消支付意图 | OrderTimeline |
| 2 | Booking Orchestration | CancelSegmentBooking | SegmentBooking | SegmentBookingCancelled | 释放 CapacityHold 或取消供应商预留 | BookingProgress |
| 3 | Capacity | ReleaseHold | CapacityHold | CapacityReleased | 更新可售库存 | InventoryView |
| 4 | Payment | CancelPaymentIntent | PaymentIntent | PaymentIntentCancelled | 防止后续误推进 | PaymentView |
| 5 | Notification | ScheduleNotification | NotificationTask | NotificationScheduled | 通知取消结果 | MessageOutbox |

关键规则：未支付订单取消没有退款；如果之后收到支付成功回调，必须作为晚到成功处理。

## Flow 4：已出票退票和退款

| Step | Actor | Command | Aggregate | Event | Policy | Read Model |
|---|---|---|---|---|---|---|
| 1 | 用户 | RequestRefundByRule | PostSalesCase | PostSalesRequested | 读取票证状态和票规 | PostSalesView |
| 2 | Post Sales | EvaluatePostSalesRule | PostSalesCase | PostSalesEvaluated | 计算手续费和应退金额 | RefundPreview |
| 3 | 用户 | ConfirmPostSales | PostSalesCase | PostSalesApproved | 开始作废票证、释放库存、退款 | PostSalesProgress |
| 4 | Ticketing | VoidEntitlement | Entitlement | EntitlementVoided | 触发 Segment 取消 | EntitlementView |
| 5 | Booking | CancelSegmentBooking | SegmentBooking | SegmentBookingCancelled | 释放正式占用或通知供应商取消 | BookingProgress |
| 6 | Capacity | ReleaseHold | CapacityHold | CapacityReleased | 更新余票和候补触发条件 | InventoryView |
| 7 | Payment | RequestRefund | Refund | RefundRequested | 提交渠道退款 | RefundView |
| 8 | 支付渠道 | SettleRefund | Refund | RefundSettled | 更新售后完成状态 | RefundView |
| 9 | Post Sales | ApplyPostSalesResult | PostSalesCase | PostSalesApplied | 汇总订单状态 | OrderTimeline |

### 关键规则

1. 退票规则以发起时刻和票证状态为依据。
2. 票证作废成功但退款失败时，不能恢复票证，应进入退款重试或人工。
3. 多旅客订单只退其中一张票时，JourneyOrder 需要汇总为部分取消。
4. 已检票、已登乘、已使用的票证退票规则不同，不能只看订单状态。

## Flow 5：改签和差价结算

| Step | Actor | Command | Aggregate | Event | Policy | Read Model |
|---|---|---|---|---|---|---|
| 1 | 用户 | RequestChange | PostSalesCase | ChangeRequested | 查询目标方案和原票规则 | ChangePreview |
| 2 | Offer Management | QuoteChangeOffer | Offer | ChangeOfferQuoted | 计算新旧差价和规则 | ChangePreview |
| 3 | Booking | RequestSegmentReservation | SegmentBooking | ReplacementReservationRequested | 锁定新 Segment 资源 | BookingProgress |
| 4 | Capacity | HoldCapacity | CapacityHold | ReplacementCapacityHeld | 等待差价处理 | InventoryHoldView |
| 5 | Payment | CaptureExtraPayment 或 RequestRefund | PaymentIntent/Refund | ChangeDifferenceSettled | 准备切换新旧票 | PaymentView |
| 6 | Ticketing | VoidEntitlement | Entitlement | OriginalEntitlementVoided | 旧票不可再用 | EntitlementView |
| 7 | Ticketing | IssueEntitlement | Entitlement | ReplacementEntitlementIssued | 新票可用 | EntitlementView |
| 8 | Booking | ChangeSegmentBooking | SegmentBooking | SegmentBookingChanged | 原 Booking 被替换 | BookingProgress |
| 9 | Post Sales | ApplyPostSalesResult | PostSalesCase | ChangeApplied | 订单时间线更新 | OrderTimeline |

### 失败和补偿

| 失败点 | 补偿 |
|---|---|
| 新库存锁定失败 | 原票保持不变，售后失败或重新选择方案。 |
| 补差价支付失败 | 释放新库存，原票保持不变。 |
| 旧票作废失败 | 不签发新票，释放新库存或进入人工。 |
| 新票出票失败 | 根据旧票是否已作废决定恢复旧票、重试出票或退款。 |

第一阶段建议采用保守顺序：先锁新库存，完成差价，再作废旧票，最后签发新票。

## Flow 6：候补

| Step | Actor | Command | Aggregate | Event | Policy | Read Model |
|---|---|---|---|---|---|---|
| 1 | 用户 | CreateWaitlistRequest | WaitlistRequest | WaitlistRequested | 校验旅客、冲突行程和截止时间 | WaitlistView |
| 2 | Payment | AuthorizePayment | PaymentIntent | WaitlistPaymentAuthorized | 候补成功后可扣款 | PaymentView |
| 3 | Inventory | EnqueueWaitlist | WaitlistQueue | WaitlistQueued | 按规则排序 | WaitlistQueueView |
| 4 | Capacity | ReleaseHold 或 AdjustCapacity | CapacityHold | CapacityReleased | 触发候补检查 | InventoryView |
| 5 | Waitlist | TryFulfillWaitlist | WaitlistRequest | WaitlistFulfillmentStarted | 锁定释放出的库存 | WaitlistProgress |
| 6 | Booking | RequestSegmentReservation | SegmentBooking | SegmentReservationConfirmed | 触发扣款和出票 | BookingProgress |
| 7 | Payment | CapturePayment | PaymentIntent | PaymentCaptured | 候补成功扣款 | PaymentView |
| 8 | Ticketing | IssueEntitlement | Entitlement | EntitlementIssued | 候补成功 | EntitlementView |
| 9 | Waitlist | CompleteWaitlist | WaitlistRequest | WaitlistFulfilled | 通知用户 | WaitlistView |

候补可以后续作为独立聚合细化。第一阶段如果不实现候补，仍应在库存和退票事件中预留 `CapacityReleased` 触发点。

## Flow 7：联乘和中转

| Step | Actor | Command | Aggregate | Event | Policy | Read Model |
|---|---|---|---|---|---|---|
| 1 | Trip Planning | BuildTransferPlan | TransferPlan | TransferPlanCreated | 计算最短换乘时间 | TransferRiskView |
| 2 | Transfer Management | EvaluateTransferRisk | TransferPlan | TransferRiskEvaluated | 写入 Connection Contract | TransferRiskView |
| 3 | Offer Management | QuoteOffer | Offer | OfferQuoted | 展示保障或自助联乘 | OfferDetail |
| 4 | Fulfillment | MarkSegmentArrived | FulfillmentRecord | SegmentArrived | 重新计算后续连接风险 | JourneyProgress |
| 5 | Transfer Management | MarkTransferAtRisk | TransferPlan | TransferAtRisk | 通知用户，准备恢复选项 | TransferRiskView |
| 6 | Transfer Management | MarkConnectionMissed | TransferPlan | ConnectionMissed | 根据保障契约触发恢复或提示自理 | TransferRiskView |
| 7 | Disruption Recovery | ProposeRecoveryOptions | DisruptionCase | ReaccommodationProposed | 用户选择替代方案 | RecoveryOptionsView |

关键规则：没有 Connection Contract 的多段组合只能按自助联乘处理，不能事后承诺平台保障。

## Flow 8：异常恢复

| Step | Actor | Command | Aggregate | Event | Policy | Read Model |
|---|---|---|---|---|---|---|
| 1 | 运营或供应商 | PublishDisruption | DisruptionCase | DisruptionPublished | 识别受影响订单和票证 | DisruptionDashboard |
| 2 | Disruption Recovery | AttachAffectedJourney | DisruptionCase | JourneyAffectedByDisruption | 标记订单异常 | OrderTimeline |
| 3 | Journey Order | MarkDisrupted | JourneyOrder | JourneyOrderDisrupted | 暂停普通售后或展示特殊规则 | OrderTimeline |
| 4 | Disruption Recovery | ProposeRecoveryOptions | DisruptionCase | ReaccommodationProposed | 通知用户可选动作 | RecoveryOptionsView |
| 5 | 用户或规则 | AcceptRecoveryOption | DisruptionCase | ReaccommodationAccepted | 触发改乘、退款或补偿 | RecoveryProgress |
| 6 | Post Sales | RequestRefundByDisruption 或 RequestProtectedChange | PostSalesCase | PostSalesRequested | 按异常规则免手续费或优先改签 | PostSalesProgress |
| 7 | Payment/Ticketing/Booking | ApplyRecovery | 多聚合 | RecoveryApplied | 更新异常处理进度 | DisruptionDashboard |
| 8 | Disruption Recovery | CloseDisruptionCase | DisruptionCase | DisruptionCaseClosed | 汇总遗留人工处理项 | DisruptionDashboard |

### 异常类型和默认策略

| 异常 | 默认策略 |
|---|---|
| 火车停运 | 停售、免手续费退票、候补关闭、保护性改签。 |
| 航班取消 | 供应商规则下改签、退票、住宿或补偿。 |
| 大巴停班 | 退票、改班次或人工安排。 |
| 网约车司机取消 | 重新派单、免取消费、补偿券或退款。 |
| 轮船停航 | 退票、改船班、港口服务取消。 |
| 中转错过 | 按 Connection Contract 判断平台保障、供应商保障或用户自理。 |

## 横切策略

### 幂等策略

| 命令 | 幂等键 |
|---|---|
| CreateJourneyOrder | accountId + offerId + clientRequestId |
| RequestSegmentReservation | orderId + segmentId + travelerId |
| CapturePayment | paymentIntentId + channelTxnId |
| IssueEntitlement | segmentBookingId + travelerId + issueAttempt |
| RequestRefund | postSalesCaseId + entitlementId + refundReason |
| ApplyRecovery | disruptionCaseId + journeyOrderId + recoveryOptionId |

### Outbox 策略

所有跨上下文事件先写入本地 Outbox，再异步发布。消费者必须幂等处理。关键事件包括：

1. JourneyOrderCreated
2. SegmentReservationConfirmed
3. PaymentCaptured
4. EntitlementIssued
5. EntitlementVoided
6. RefundSettled
7. DisruptionPublished
8. ConnectionMissed

### 读模型策略

| 读模型 | 事件来源 | 用途 |
|---|---|---|
| SearchResult | ServicePlan、Availability、Fare | 售前搜索。 |
| OfferDetail | OfferQuoted、OfferExpired | 报价确认页。 |
| OrderTimeline | 所有订单相关事件 | 用户和客服查看全链路。 |
| BookingProgress | SegmentBooking 事件 | 分段确认进度。 |
| PaymentView | Payment 和 Refund 事件 | 支付和退款进度。 |
| EntitlementView | Entitlement 事件 | 票证展示和核验。 |
| InventoryView | Capacity 事件 | 运营库存监控。 |
| DisruptionDashboard | Disruption 和 Recovery 事件 | 异常处理。 |

## 后续实现切片

建议按下面顺序把事件风暴转成代码任务：

1. `OfferQuoted -> JourneyOrderCreated`：让订单必须来自报价快照。
2. `JourneyOrderCreated -> CapacityHeld`：建立可信锁库存。
3. `PaymentCaptured -> EntitlementIssued`：拆开支付和出票。
4. `CancellationRequested -> RefundRequested`：重做退票退款链路。
5. `ChangeRequested -> ReplacementEntitlementIssued`：重做改签链路。
6. `DisruptionPublished -> ReaccommodationAccepted`：接入异常恢复。
7. `TransferAtRisk -> ConnectionMissed`：接入联乘中转保护。
